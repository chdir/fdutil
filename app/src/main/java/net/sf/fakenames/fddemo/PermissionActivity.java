package net.sf.fakenames.fddemo;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.support.v4.os.ResultReceiver;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.widget.TextView;

import java.util.Arrays;

import butterknife.BindView;
import butterknife.OnClick;

public final class PermissionActivity extends BaseActivity {
    public static final String EXTRA_CALLBACK = "net.sf.callback";
    public static final String EXTRA_CALLER = "net.sf.source";
    public static final String EXTRA_UID = "net.sf.uid";
    public static final String EXTRA_PATH = "net.sf.path";
    public static final String EXTRA_MODE = "net.sf.mode";
    public static final String EXTRA_RESPONSE = "net.sf.res";

    public static final int RESPONSE_ALLOW = 1;
    public static final int RESPONSE_DENY = 2;
    public static final int RESPONSE_TIMEOUT = 3;

    @BindView(android.R.id.message)
    TextView message;

    @BindView(android.R.id.button1)
    TextView allow;

    @BindView(android.R.id.button2)
    TextView deny;

    private ResultReceiver resultReceiver;
    private int uid;
    private String mode;
    private String packageName;
    private String path;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Intent intent = getIntent();

        if (intent == null) {
            finish();
            return;
        }

        uid = intent.getIntExtra(EXTRA_UID, -1);
        mode = intent.getStringExtra(EXTRA_MODE);
        resultReceiver = intent.getParcelableExtra(EXTRA_CALLBACK);
        packageName = intent.getStringExtra(EXTRA_CALLER);
        path = intent.getStringExtra(EXTRA_PATH);

        if (uid == -1 || mode == null || resultReceiver == null) {
            finish();
            return;
        }

        setContentView(R.layout.permission_dialog);

        allow.setText(R.string.allow);
        deny.setText(R.string.deny);

        CharSequence name;

        String packageNames[];

        final PackageManager pm = getPackageManager();
        if (TextUtils.isEmpty(packageName)) {
            packageNames = pm.getPackagesForUid(uid);
        } else {
            packageNames = new String[] { packageName };
        }

        name = toLabel(uid, packageNames);

        final CharSequence req = SpanFormatter.format(getString(R.string.perm_req),
                name, accessPattern(mode), path);

        message.setText(req);
    }

    private CharSequence toLabel(int uid, String[] packages) {
        final SpannableStringBuilder ssb = new SpannableStringBuilder();

        if (packages != null) {
            try {
                final PackageManager pm = getPackageManager();

                if (packages.length == 1) {
                    final ApplicationInfo soleInfo = pm.getApplicationInfo(packages[0], 0);
                    if (soleInfo != null) {
                        final CharSequence label = soleInfo.loadLabel(pm);

                        if (!TextUtils.isEmpty(label)) {
                            ssb.append(label);
                            setBold(ssb, 0, ssb.length());
                            return ssb;
                        }
                    }
                } else {
                    Arrays.sort(packages);

                    int named = 0;

                    ssb.append("UID ").append(String.valueOf(uid)).append(" (");

                    final ApplicationInfo firstInfo = pm.getApplicationInfo(packages[0], 0);

                    if (firstInfo != null) {
                        final CharSequence label = firstInfo.loadLabel(pm);

                        if (!TextUtils.isEmpty(label)) {
                            ++named;

                            int old = ssb.length();
                            ssb.append(label);
                            setBold(ssb, old, ssb.length());
                        }
                    }

                    final int maxSummary = Math.min(packages.length, 3);
                    int i;
                    for (i = 1; i < maxSummary; ++i) {
                        final ApplicationInfo appInfo = pm.getApplicationInfo(packages[i], 0);

                        if (appInfo != null) {
                            final CharSequence label = appInfo.loadLabel(pm);

                            if (!TextUtils.isEmpty(label)) {
                                ++named;

                                ssb.append(", ");
                                int old = ssb.length();
                                ssb.append(label);
                                setBold(ssb, old, ssb.length());
                            }
                        }
                    }

                    if (named == 0) {
                        ssb.append(String.valueOf(packages.length)).append(" packages)");
                        setBold(ssb, 0, ssb.length());
                    } else {
                        if (packages.length <= 4) {
                            ssb.append(")");
                        } else {
                            ssb.append(" and ");
                            ssb.append(String.valueOf(packages.length - named));
                            ssb.append(" others)");
                        }
                    }

                    return ssb;
                }
            } catch(PackageManager.NameNotFoundException ignored) {
            }
        }

        ssb.append("UID ").append(String.valueOf(uid));
        setBold(ssb, 0, ssb.length());
        return ssb;
    }

    private static void setBold(Spannable s, int start, int end) {
        s.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private String accessPattern(String access) {
        final int accessMode = ParcelFileDescriptor.parseMode(access);

        if ((accessMode & ParcelFileDescriptor.MODE_READ_ONLY) == accessMode) {
            return "read";
        }

        if ((accessMode & ParcelFileDescriptor.MODE_WRITE_ONLY) == accessMode) {
            return "write";
        }

        return "full";
    }

    @OnClick(android.R.id.button1)
    void allow() {
        timeoutHandler.removeCallbacksAndMessages(null);

        final Bundle bundle = new Bundle();
        bundle.putInt(EXTRA_RESPONSE, RESPONSE_ALLOW);

        resultReceiver.send(0, bundle);

        finish();
    }

    @OnClick(android.R.id.button2)
    void deny() {
        timeoutHandler.removeCallbacksAndMessages(null);

        final Bundle bundle = new Bundle();
        bundle.putInt(EXTRA_RESPONSE, RESPONSE_DENY);

        resultReceiver.send(0, bundle);

        finish();
    }

    private final Handler timeoutHandler = new Handler();

    private Runnable timeout;

    @Override
    protected void onResume() {
        super.onResume();

        if (timeout == null) {
            timeout = () -> {
                final Bundle bundle = new Bundle();
                bundle.putInt(EXTRA_RESPONSE, RESPONSE_TIMEOUT);
                finish();
            };

            timeoutHandler.postDelayed(timeout, 8000);
        }
    }

    @Override
    protected void onDestroy() {
        if (!isChangingConfigurations()) {
            resultReceiver.send(0, Bundle.EMPTY);
        }

        super.onDestroy();
    }
}
