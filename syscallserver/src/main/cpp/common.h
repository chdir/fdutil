#define MAX(a,b) (((a)>(b))?(a):(b))
#define MIN(a,b) (((a)<(b))?(a):(b))

#define LOG_TAG "fdshare"

#define LOG(...) ((void) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__))

enum patch_state {
    PATCH_DONE,
    ALREADY_PATCHED,
    PATCH_ERR_RO,
    PATCH_ERR_RW,
};

typedef enum patch_state patch_state_t;

extern int load_policy_from_kernel(policy_file_t *pf);

extern int load_policy_into_kernel(policy_file_t *fp);

extern patch_state_t issue_indulgence(const char* type_name, policy_file_t* fp, policy_file_t* newPolicyFile);