#define MAX(a,b) (((a)>(b))?(a):(b))
#define MIN(a,b) (((a)<(b))?(a):(b))

#define LOG_TAG "fdshare"

#include <android/log.h>

#define LOG(...) ((void) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__))

enum patch_state {
    PATCH_DONE,
    ALREADY_PATCHED,
    PATCH_ERR_RO,
    PATCH_ERR_RW,
};


static inline void decrypt(volatile const char* str, char* cleartext, size_t len) {
    if (cleartext[0] != '\0') {
        return;
    }

    LOG("string length is %d", len);

    str += 6;

    volatile const char* key = str + len + 1;

     //size_t  keylen = strlen(key);

    //if (keylen != len) {
    //    LOG("Key length is %d but string length is %d", keylen, len);
    //}

    //LOG("Decrypting %s with %s", str, key);

    size_t i;
    for (i = 0; i < len; i++) {
        unsigned char tmp = (unsigned char) str[i];

        if (key[i] != '/') {
            tmp -= key[i];
        }

        //LOG("Got %c at %u", tmp, i);

        cleartext[i] = tmp;
    }

    //LOG("Terminated at %u by %c", i, str[i]);

    cleartext[len] = '\0';
}// + 1

//#define ENC(str) str

#define ENC(str) ({                              \
    size_t len = sizeof(str);                    \
    static char tc[sizeof(str)];                 \
    decrypt("/mark/" str "\0" str, tc, len - 1); \
    tc;                                          \
})

typedef enum patch_state patch_state_t;

extern int load_policy_from_kernel(policy_file_t *pf);

extern int load_policy_into_kernel(policy_file_t *fp);

extern patch_state_t issue_indulgence(const char* type_name, policy_file_t* fp, policy_file_t* newPolicyFile);