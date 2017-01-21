#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/ioctl.h>
#include <sys/uio.h>
#include <sys/un.h>
#include <sys/inotify.h>
#include <sys/mman.h>

#include <stdlib.h> // exit
#include <stdio.h> // printf

#include <android/log.h>
#include <sepol/policydb/symtab.h>
#include <private.h>

#include "linux_syscall_support.h"
#include "moar_syscalls.h"
#include "sepol/policydb/policydb.h"
#include "sepol/policydb/services.h"
#include "sepol/policydb/constraint.h"
#include "sepol/sepol.h"
#include "common.h"

static inline int skip_from_current(policy_file_t* fp, size_t bytes) {
    if (bytes > fp->len) {
        return 1;
    }

    fp -> data += bytes;
    fp -> len -= bytes;

    return 0;
}

static inline size_t get_len_with_checks(policy_file_t* fp, size_t* value) {
    if (fp -> len < sizeof(uint32_t)) {
        return 1;
    }

    *value = le32_to_cpu(*(uint32_t*)(fp->data));

    if (zero_or_saturated(*value)) {
        return 1;
    }

    fp -> data += sizeof(uint32_t);
    fp -> len -= sizeof(uint32_t);

    return 0;
}

static inline size_t get_len(policy_file_t* fp, uint32_t* value) {
    if (fp -> len < sizeof(uint32_t)) {
        return 1;
    }

    *value = le32_to_cpu(*(uint32_t*)(fp->data));

    fp -> data += sizeof(uint32_t);
    fp -> len -= sizeof(uint32_t);

    return 0;
}

#define SKIP(x) { if (skip_from_current(fp, sizeof(uint32_t) * (x))) { LOG("SKIP"); return 1; } }
#define B_SKIP(x) {  if (skip_from_current(fp, (x))) { LOG("B_SKIP"); return 1; } }
#define GET(var) { if (get_len(fp, (var))) { LOG("GET"); return 1; } }
#define GET_LEN(var) { if (get_len_with_checks(fp, (var))) { LOG("GET_LEN"); return 1; } }

static int common_skip(policy_file_t* fp, int ver) {
    size_t nameLen;
    size_t permCnt;

    GET_LEN(&nameLen);
    SKIP(2);
    GET_LEN(&permCnt);
    B_SKIP(nameLen);

    for (size_t i = 0; i < permCnt; ++i) {
        GET_LEN(&nameLen);
        B_SKIP(sizeof(uint32_t) + nameLen);
    }

    //LOG("skipped common data");

    return 0;
}

static int skip_constraints(policy_file_t* fp, size_t constrCnt, int version) {
    size_t constrExprCnt;
    ebitmap_t bitmap;

    for (size_t i = 0; i < constrCnt; ++i) {
        SKIP(1);
        GET_LEN(&constrExprCnt);

        for (size_t j = 0; j < constrExprCnt; ++j) {
            uint exprType;
            GET(&exprType);
            SKIP(2);

            if (exprType == CEXPR_NAMES) {
                ebitmap_init(&bitmap);
                ebitmap_read(&bitmap, fp);
                ebitmap_destroy(&bitmap);

                if (version >= POLICYDB_VERSION_CONSTRAINT_NAMES) {
                    ebitmap_init(&bitmap);
                    ebitmap_read(&bitmap, fp);
                    ebitmap_destroy(&bitmap);

                    ebitmap_init(&bitmap);
                    ebitmap_read(&bitmap, fp);
                    ebitmap_destroy(&bitmap);

                    SKIP(1);
                }
            }
        }
    }

    //LOG("Skipped %d constraints", constrCnt);

    return 0;
}

static int class_skip(policy_file_t* fp, int version) {
    size_t nameLen;
    size_t commKeyLen;
    size_t permCnt;
    size_t constrCnt;

    GET_LEN(&nameLen);
    GET(&commKeyLen);
    SKIP(2);

    GET(&permCnt);

    //LOG("perm count is %d", permCnt);

    GET(&constrCnt);

    B_SKIP(nameLen);

    if (commKeyLen != 0) B_SKIP(commKeyLen);

    //LOG("Skipped generic header, perm count: %d", permCnt);

    for (size_t i = 0; i < permCnt; ++i) {
        GET_LEN(&nameLen);
        B_SKIP(sizeof(uint32_t) + nameLen);
    }

    // skip constraints
    skip_constraints(fp, constrCnt, version);

    //LOG("proceeding to validatetrans");

    // skip validatetrans
    GET(&constrCnt);
    skip_constraints(fp, constrCnt, version);

    if (version >= POLICYDB_VERSION_NEW_OBJECT_DEFAULTS) {
        SKIP(3);

        if (version >= POLICYDB_VERSION_DEFAULT_TYPE) {
            SKIP(1);
        }
    }

    return 0;
}

static int role_skip(policy_file_t* fp, int version) {
    size_t nameLen;

    GET_LEN(&nameLen);
    SKIP(version >= POLICYDB_VERSION_BOUNDARY ? 2 : 1);
    B_SKIP(nameLen);

    ebitmap_t bitmap;

    ebitmap_init(&bitmap);
    ebitmap_read(&bitmap, fp);
    ebitmap_destroy(&bitmap);

    ebitmap_init(&bitmap);
    ebitmap_read(&bitmap, fp);
    ebitmap_destroy(&bitmap);

    return 0;
}

static int (*skip_f[SYM_NUM]) (policy_file_t* fp, int ver) = {
        common_skip, class_skip, role_skip,
};

static int lookup_type(policy_file_t* fp, int version, const char* type_name, uint32_t* result) {
    size_t targLen = strlen(type_name);
    size_t typeLen;

    size_t elCount;

    SKIP(1);
    GET_LEN(&elCount);

    for (size_t i = 0; i < elCount; ++i) {
        GET_LEN(&typeLen);
        GET(result);

        SKIP(version >= POLICYDB_VERSION_BOUNDARY ? 2 : 1);

        if (typeLen == targLen) {
            if (strncmp(type_name, fp->data, targLen) == 0) {
                LOG("Match at position %d", i);
                return 0;
            }
        }

        B_SKIP(typeLen);
    }

    return 1;
}

int load_policy_from_kernel(policy_file_t *pf) {
    const char *filename = "/sys/fs/selinux/policy";

    int fd;
    struct stat sb;
    void *map;

    fd = open(filename, O_RDONLY);
    if (fd < 0) {
        LOG("Can't open '%s':  %s", filename, strerror(errno));
        return -1;
    }

    if (fstat(fd, &sb) < 0) {
        LOG("Can't stat '%s':  %s", filename, strerror(errno));
        close(fd);
        return -1;
    }

    map = mmap(NULL, (size_t) sb.st_size, PROT_READ | PROT_WRITE, MAP_PRIVATE, fd, 0);
    if (map != MAP_FAILED) {
        policy_file_init(pf);
        pf->type = PF_USE_MEMORY;
        pf->data = map;
        pf->len = (size_t) sb.st_size;
        pf->size = pf->len;

        return fd;
    } else {
        LOG("Can't mmap '%s':  %s", filename, strerror(errno));
    }

    close(fd);

    return -1;
}

int load_policy_into_kernel(policy_file_t *fp) {
    char *filename = "/sys/fs/selinux/load";
    int fd, ret;

    fd = open(filename, O_RDWR);
    if (fd < 0) {
        LOG("Can't open '%s':  %s", filename, strerror(errno));

        return 1;
    }

    ret = write(fd, fp -> data, fp -> size);
    int err = errno;

    close(fd);

    if (ret < 0) {
        LOG("Could not write policy to %s: %s", filename, strerror(err));
        return 1;
    }

    return 0;
}

static int ebitmap_write(ebitmap_t * e, struct policy_file *fp)
{
    ebitmap_node_t *n;
    uint32_t buf[32], bit, count;
    uint64_t map;
    size_t items;

    buf[0] = cpu_to_le32(MAPSIZE);
    buf[1] = cpu_to_le32(e->highbit);

    count = 0;
    for (n = e->node; n; n = n->next)
        count++;
    buf[2] = cpu_to_le32(count);

    items = put_entry(buf, sizeof(uint32_t), 3, fp);
    if (items != 3)
        return POLICYDB_ERROR;

    for (n = e->node; n; n = n->next) {
        bit = cpu_to_le32(n->startbit);
        items = put_entry(&bit, sizeof(uint32_t), 1, fp);
        if (items != 1)
            return POLICYDB_ERROR;
        map = cpu_to_le64(n->map);
        items = put_entry(&map, sizeof(uint64_t), 1, fp);
        if (items != 1)
            return POLICYDB_ERROR;

    }

    return POLICYDB_SUCCESS;
}

/**
 * Shitty SELinux policy format: "everything is a variable length blob"
 *
 * Some record groups are arbitrarily grouped in clusters, called "tables".
 *
 * Enjoy absence of fixed sizes and offsets. But at least it can be piped! (not really)
 *
 * -- start of header --
 *
 * uint32 magic
 * uint32 name length
 * some bytes...
 * uint32 version
 * uint32 options
 * uint32 symbol table count
 * uint32 ocon_num (?)
 * ebitmap policy capabilities
 * ebitmap permissives
 *
 * -- end of header --
 *
 * -- start of symbol tables --
 *
 * common entry types:
 *
 * permission entry:
 *
 * uint32 name length
 * uint32 some crap
 * name bytes...
 *
 * constraint entry:
 *
 * uint32 permissions
 * uint32 number of expressions in constraint
 * bunch of constraint expressions...
 *
 * constraint expression:
 *
 * uint32 type
 * uint32 attribute
 * uint32 operation
 * ebitmap names (if type == "name")
 * constraint name(s) (if type == "name" and version >= POLICYDB_VERSION_CONSTRAINT_NAMES):
 * ebitmap some crap
 * ebitmap moar crap
 * uint32 flags (?)
 *
 * type set:
 *
 * ebitmap some crap
 * ebitmap moar crap
 * uint32 whatever
 *
 * validatetrans entry: same as constraints so far
 *
 * tables:
 *
 * common "table":
 *
 * uint32 name length
 * uint32 some crap
 * uint32 nprim (?)
 * uint32 number of permission entries
 * name bytes...
 * bunch of permission entries...
 *
 * class "table":
 *
 * uint32 name length
 * uint32 common key length (may be 0 if no common key)
 * uint32 some crap
 * uint32 permission nprim (?)
 * uint32 number of permission entries
 * uint32 number of constraints
 * name bytes...
 * optional common key bytes...
 * bunch of permission entries...
 * bunch of constraint entries...
 * uint32_t number of validatetrans rules
 * bunch of validatetrans rules (same overall format as constraints)...
 * uint32_t
 * uint32_t object defaults (if version >= POLICYDB_VERSION_NEW_OBJECT_DEFAULTS)
 * uint32_t
 * uint32_t default type (if version >= POLICYDB_VERSION_DEFAULT_TYPE)
 *
 * roles "table":
 *
 * uint32_t length of role name
 * uint32_t some crap
 * uint32_t role bounds (if version >= POLICYDB_VERSION_BOUNDARY)
 * role name bytes...
 * ebitmap dominates (?)
 * ebitmap types (?)
 *
 * types "table":
 *
 * uint32_t length of type name
 * uint32_t some crap (!)
 * uint32_t primary flag/properties (later if version >= POLICYDB_VERSION_BOUNDARY)
 * uint32_t bounds (if version >= POLICYDB_VERSION_BOUNDARY)
 * name bytes...
 *
 * -- end of interesting stuff --
 */
patch_state_t issue_indulgence(const char* type_name, policy_file_t* fp, policy_file_t* newPolicyFile) {
    const void* oldPolicy = fp -> data;
    void* newPolicy = newPolicyFile -> data;

    uint32_t buf[5];
    size_t len, nprim, nel;
    int rc;

    unsigned int i, j;

    // magic number and policy name length.
    rc = next_entry(buf, fp, sizeof(uint32_t) * 2);
    if (rc < 0) {
        LOG("Broken policy file!");
        return PATCH_ERR_RO;
    }

    for (i = 0; i < 2; i++)
        buf[i] = le32_to_cpu(buf[i]);

    if (buf[0] != POLICYDB_MAGIC) {
        LOG("Invalid policy file magic: %#08x", buf[0]);
        return PATCH_ERR_RO;
    }

    // skip the ID string
    len = buf[1];
    if (len == 0 || len > POLICYDB_STRING_MAX_LENGTH || len > fp->len) {
        LOG("policydb string length %s", len ? "too long" : "zero");
        return PATCH_ERR_RO;
    }

    fp->data += len;
    fp->len -= len;

    nel = 4; // kernel policy version header
    rc = next_entry(buf, fp, sizeof(uint32_t) * 4);
    if (rc < 0) {
        return PATCH_ERR_RO;
    }

    for (i = 0; i < nel; i++)
        buf[i] = le32_to_cpu(buf[i]);

    unsigned int r_policyvers = buf[0];
    if (r_policyvers < POLICYDB_VERSION_PERMISSIVE || r_policyvers > POLICYDB_VERSION_MAX) {
        LOG("policydb version %d does not match supported version range %d-%d", buf[0],
            POLICYDB_VERSION_PERMISSIVE, POLICYDB_VERSION_MAX);
        return PATCH_ERR_RO;
    }

    if (!(buf[1] & POLICYDB_CONFIG_MLS)) {
        LOG("Expected the MLS policy, but this isn't one");
    }

    unsigned handle_unknown = buf[1] & POLICYDB_CONFIG_UNKNOWN_MASK;
    if (handle_unknown == ALLOW_UNKNOWN) {
        LOG("'handle unknown' is already set to allow");
    }

    struct policydb_compat_info *info = policydb_lookup_compat(r_policyvers, POLICY_KERN, SEPOL_TARGET_SELINUX);

    if (!info) {
        LOG("unable to find policy compat info for version %d", r_policyvers);
        return PATCH_ERR_RO;
    }

    if (buf[2] != info->sym_num || buf[3] != info->ocon_num) {
        LOG("policydb table sizes (%d,%d) do not match mine (%d,%d)!!!",
            buf[2], buf[3], info->sym_num, info->ocon_num);

        info -> sym_num = buf[2];
        info -> ocon_num = buf[3];
    }

    if (info -> sym_num < SYM_TYPES + 1) {
        LOG("Types table is supposed to be at %d but symbol count is %d, bailing",
            SYM_TYPES, info -> sym_num);

        return PATCH_ERR_RO;
    }

    ebitmap_t foobar;

    // skip policy capabilities
    ebitmap_init(&foobar);
    if (ebitmap_read(&foobar, fp)) {
        LOG("unable to measure capability block, bailing");
        return PATCH_ERR_RO;
    }
    ebitmap_destroy(&foobar);

    void* fixed_header_end = fp -> data;

    ebitmap_init(&foobar);
    if (ebitmap_read(&foobar, fp)) {
        LOG("unable to read permissive block, bailing");
        return PATCH_ERR_RO;
    }

    void* permissives_end = fp -> data;

    for (i = 0; i < SYM_TYPES; i++) {
        rc = next_entry(buf, fp, sizeof(uint32_t) * 2);
        if (rc < 0) {
            LOG("unable to measure table at %d, bailing", i);
        }
        nprim = le32_to_cpu(buf[0]);
        nel = le32_to_cpu(buf[1]);
        if (nel && !nprim) {
            LOG("unexpected items in symbol table with no symbol");
            return PATCH_ERR_RO;
        }

        LOG("Table at %d has %d entries", i, nel);

        for (j = 0; j < nel; j++) {
            //LOG("processing %d entry", j);

            if (skip_f[i](fp, r_policyvers)) {
                LOG("unable to skip table at %d : %d, bailing", i, j);
                return PATCH_ERR_RO;
            }
        }
    }

    uint32_t desirableTypeId;
    if (lookup_type(fp, r_policyvers, type_name, &desirableTypeId)) {
        LOG("Failed to locate %s in type table, aborting", type_name);
        return PATCH_ERR_RO;
    }

    LOG("Found %s under id %u", type_name, desirableTypeId);

    if (!ebitmap_get_bit(&foobar, desirableTypeId)) {
        if (ebitmap_set_bit(&foobar, desirableTypeId, 1)) {
            LOG("Could not set bit in permissive map");
            return PATCH_ERR_RO;
        }

        size_t initialSegment = fixed_header_end - oldPolicy;

        LOG("Copy %u bytes", initialSegment);
        memcpy(newPolicy, oldPolicy, initialSegment);

        newPolicyFile -> data += initialSegment;
        newPolicyFile -> len -= initialSegment;

        void* dataCur = newPolicyFile -> data;

        if (ebitmap_write(&foobar, newPolicyFile)) {
            LOG("Failed to write down new permissives map");
            return PATCH_ERR_RO;
        }

        void* dataNow = newPolicyFile -> data;

        LOG("Data position changed by %u", dataNow - dataCur);

        size_t bytesAdded = (((void*) newPolicyFile -> data) - newPolicy) - (permissives_end - oldPolicy);
        newPolicyFile -> size = fp->size + bytesAdded;

        LOG("Size before: %u, size after: %u", fp -> size, newPolicyFile -> size);

        size_t toCopy = oldPolicy + fp->size - permissives_end;

        LOG("Copy %u bytes", toCopy);
        memcpy(newPolicyFile -> data, permissives_end, toCopy);

        newPolicyFile -> data = newPolicy;

        return PATCH_DONE;
    } else {
        LOG("%s is already permissive", type_name);

        return ALREADY_PATCHED;
    }
}