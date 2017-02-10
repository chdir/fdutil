package net.sf.xfd.provider;

public interface PermissionDelegate {
    String ACTION_PERMISSION_REQUEST = "net.sf.xfd.permission_request";

    String META_IS_PERMISSION_DELEGATE = "net.sf.xfd.is_permission_delegate";

    /**
     * type: ResultReceiver (Parcelable)
     */
    String EXTRA_CALLBACK = "net.sf.xfd.callback";
    /**
     * type: String, nullable
     */
    String EXTRA_CALLER = "net.sf.xfd.source";
    /**
     * type: int
     */
    String EXTRA_UID = "net.sf.xfd.uid";
    /**
     * type: String
     */
    String EXTRA_PATH = "net.sf.xfd.path";
    /**
     * type: String
     */
    String EXTRA_MODE = "net.sf.xfd.mode";

    /**
     * type: int (see below)
     */
    String EXTRA_RESPONSE = "net.sf.xfd.res";

    int RESPONSE_ALLOW = 1;
    int RESPONSE_DENY = 2;
    int RESPONSE_TIMEOUT = 3;
}
