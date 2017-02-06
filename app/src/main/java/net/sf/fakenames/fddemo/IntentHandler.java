/**
 * Copyright © 2017 Alexander Rvachev
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.sf.fakenames.fddemo;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.LabeledIntent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Parcelable;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import com.carrotsearch.hppc.ObjectHashSet;
import com.carrotsearch.hppc.ObjectSet;

import net.sf.fakenames.fddemo.provider.ProviderBase;
import net.sf.fakenames.fddemo.provider.PublicProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static android.content.ContentResolver.SCHEME_CONTENT;
import static net.sf.fakenames.fddemo.provider.PublicProvider.AUTHORITY;
import static net.sf.fakenames.fddemo.provider.PublicProvider.URI_ARG_TYPE;

/**
 * This class can open files in 4 ways:
 * 1) Passing Uri of non-privileged ContentProvider to apps. This ensures, that compatible
 * apps will be able to access the contents, as long as this app can do it.
 * 2) Passing Uri of privileged RootFileProvider to apps. This ensures, that compatible
 * apps will be able to access the contents, as long as there is a root access.
 * 3) Passing file:// Uri to apps. They may be able to open them, as long as they have access.
 *
 * All of ways above are tried and combined by mixing together a handful of labeled Intents and doing batch
 * PackageManager queries to make sure, that every possible way is used. The content type of file is determined
 * beforehand to make sure, that every capable app is counted in.
 */
@SuppressLint("InlinedApi")
public class IntentHandler extends ContextWrapper {
    private final String intentAction;

    public IntentHandler(@NonNull Context base, @NonNull String intentAction) {
        super(base.getApplicationContext());

        this.intentAction = intentAction;
    }

    /**
     * Create an Intent for performing an action on file. Created Intent may resolve to single Activity or chooser
     * dialog. It will behave as expected when passed to {@link #startActivity} and
     * {@link android.app.Activity#startActivityForResult}, but may point at some intermediate Activity to perform
     * additional processing and filtering.
     * <p>
     * *You should canonicalize paths before passing to this method, if only to prevent symlink attacks*
     *
     * @param filePath canonical path to file, such as returned by {@link File#getCanonicalPath()}
     * @param tryFastPath true, if a fast Intent resolution (e.g. when user simply clicks a file icon) is needed
     * @return created Intent, if any is available, null otherwise
     */
    public @Nullable Intent createIntentForFile(@NonNull String filePath, boolean tryFastPath) {
        final PackageManager pm = getPackageManager();
        final ContentResolver cr = getContentResolver();

        // 1) Make up ContentProvider uri
        final Uri providerUri = PublicProvider.publicUri(this, filePath);
        // If we can not access the file or create uri, bail early.
        if (providerUri == null)
            return null;

        // 2) Try to canonicalize the Uri / file path, using chosen provider. If failed, bail out. Real app should show
        // this canonical path to user somewhere to ensure, that he knows, what he opens.
        final Uri canonicalUri = providerUri; // TODO

        // 3) Get MIME type (for clients, that handle only file://)
        String[] mimeCandidates = cr.getStreamTypes(providerUri, "*/*");
        if (mimeCandidates == null) {
            mimeCandidates = new String[] {"*/*"};
        }

        // 4) Generate 2 types of Uri: file:// and content://-based
        final Intent fileReferenceIntent = intentFor(Uri.parse("file://" + filePath));
        final Intent contentReferenceIntent = intentFor(canonicalUri);

        // 6) These flags will be used for all PackageManager queries. Filters are used to simplify out judgement,
        // as well as detect stuff like ResolverActivity, that does not belong here in the first place
        final int pmFlags = PackageManager.GET_RESOLVED_FILTER | (tryFastPath ? PackageManager.MATCH_DEFAULT_ONLY : 0);

        // 7) If we are acting upon something like user's tap on file icon, just pick whatever comes first (unless
        // it is a system Intent resolver, in which case we must offer user a better choice)
        if (tryFastPath) {
            ResolveInfo defaultApp; Intent luckyIntent;

            luckyIntent = contentReferenceIntent;
            defaultApp = pm.resolveActivity(luckyIntent, pmFlags);

            if (canAccess(filePath)) {
                if (isEmptyMatch(defaultApp)) {
                    luckyIntent = fileReferenceIntent;
                    defaultApp = pm.resolveActivity(luckyIntent, pmFlags);
                }

                if (isEmptyMatch(defaultApp)) {
                    for (String mime : mimeCandidates) {
                        fileReferenceIntent.setDataAndType(fileReferenceIntent.getData(), mime);

                        defaultApp = pm.resolveActivity(fileReferenceIntent, pmFlags);

                        if (defaultApp != null && defaultApp.filter != null)
                            break;
                    }
                }
            }

            if (!isEmptyMatch(defaultApp)) {
                luckyIntent.setClassName(defaultApp.activityInfo.applicationInfo.packageName, defaultApp.activityInfo.name);
                return luckyIntent;
            }
        }

        // 8) Let's do some PackageManager queries to get as much results as possible, and combine them together via
        // Intent chooser LabeledIntent support. In order to do so we will use specially prepared Intents with all
        // possible MIME types, added file extensions etc. to work around limitations of Intent resolution mechanism.
        // Those "reference" Intents aren't necessarily what will be sent to clients.
        final List<Intent> variousApproaches = new ArrayList<>();

        // Special intent, that tells content provider to give away no content type. Useful as placeholder and to
        // match otherwise unmatchable, weird intent filters.
        final Intent untypedContentIntent = new Intent(contentReferenceIntent)
                .setData(contentReferenceIntent.getData().buildUpon().appendQueryParameter(URI_ARG_TYPE, "").build());
        final Intent[] extraIntents = makeMultipleIntents(contentReferenceIntent, mimeCandidates);

        variousApproaches.add(contentReferenceIntent);
        variousApproaches.add(untypedContentIntent);
        variousApproaches.addAll(Arrays.asList(extraIntents));

        if (canAccess(filePath)) {
            final Intent[] extraFileIntents = makeMultipleIntents(fileReferenceIntent, mimeCandidates);
            final Intent untypedFileIntent = new Intent(fileReferenceIntent).setData(Uri.parse("file://" + filePath));

            variousApproaches.add(untypedFileIntent);
            variousApproaches.addAll(Arrays.asList(extraFileIntents));
        }

        // 9) Put result of Intent resolution queries in Map, using component name as key to ensure, that no duplicates
        // exist. Additionally, ensure, that no duplicate labels will slip in the list (e.g. if some developer was
        // expecting, that only one of Activities will ever be matched and gave them same labels). The later may
        // reduce user's choice, but duplicates are worse and will surely be considered a fault on our side
        final Map<ComponentName, ResolveInfo> resolved = new HashMap<>();
        final ObjectSet<String> names = new ObjectHashSet<>();
        for (Intent approach:variousApproaches) {
            List<ResolveInfo> result = pm.queryIntentActivities(approach, pmFlags);
            for (ResolveInfo res:result) {
                final String visualId = res.activityInfo.applicationInfo.packageName + res.loadLabel(pm);

                if (res.filter.hasDataScheme(SCHEME_CONTENT) || !names.contains(visualId)) {
                    resolved.put(new ComponentName(res.activityInfo.packageName, res.activityInfo.name), res);
                    names.add(visualId);
                }
            }
        }

        final ArrayList<LabeledIntent> actvityFilters = new ArrayList<>(resolved.size());

        for (Map.Entry<ComponentName, ResolveInfo> resEntry:resolved.entrySet()) {
            final ResolveInfo info = resEntry.getValue();

            if (isEmptyMatch(info))
                continue;

            final Intent realIntent = new Intent(info.filter.hasDataScheme(SCHEME_CONTENT) ? contentReferenceIntent : fileReferenceIntent);

            realIntent.setComponent(resEntry.getKey());

            final String suppliedLabel = info.activityInfo.loadLabel(pm).toString();

            // Note, that we app icon is used here instead of Intent filter icon. Those icons are bad idea, and
            // may be highly misleading to users, when the choice pops up
            final LabeledIntent intent = new LabeledIntent(realIntent, info.activityInfo.packageName,
                    suppliedLabel, info.activityInfo.applicationInfo.icon);

            actvityFilters.add(intent);
        }

        switch (actvityFilters.size()) {
            case 0:
                return null;
            case 1:
                return new Intent(actvityFilters.get(0));
            default:
                return Intent.createChooser(actvityFilters.remove(actvityFilters.size() - 1), "Choose app")
                        .putExtra(Intent.EXTRA_INITIAL_INTENTS, actvityFilters.toArray(new Parcelable[actvityFilters.size()]));
        }
    }

    private Intent[] makeMultipleIntents(Intent untypedIntent, String[] possibleMimeTypes) {
        final Uri intentUri = untypedIntent.getData();

        final Intent[] extraIntents = new Intent[possibleMimeTypes.length];

        for (int i = 0; i < possibleMimeTypes.length; i ++) {
            Uri newUri;

            if (SCHEME_CONTENT.equals(intentUri.getScheme())) {
                newUri = intentUri.buildUpon().appendQueryParameter(URI_ARG_TYPE, possibleMimeTypes[i]).build();
            } else {
                newUri = intentUri;

                // apply extra effort for files without extensions

                /*
                if (TextUtils.isEmpty(ProviderBase.getExtensionFromPath(intentUri.toString()))) {

                    final ContentType libmagicType = ContentType.fromMimeType(possibleMimeTypes[i]);
                    if (libmagicType != null) {
                        final String[] possibleExtensions = libmagicType.getFileExtensions();

                        if (possibleExtensions != null && possibleExtensions.length != 0) {
                            newUri = newUri.buildUpon().path(newUri.getPath() + '.' + possibleExtensions[0]).build();
                        }
                    }
                }
                */
            }

            extraIntents[i] = new Intent(untypedIntent)
                    .setDataAndType(newUri, possibleMimeTypes[i]);
        }
        return extraIntents;
    }

    private Intent intentFor(Uri uri) {
        //noinspection deprecation
        return new Intent(intentAction)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET)
                .setData(uri);
    }

    private boolean canAccess(String file) {
        switch (intentAction) {
            case Intent.ACTION_EDIT:
                return new File(file).canWrite();
            default:
                return new File(file).canRead();
        }
    }

    private static boolean isEmptyMatch(ResolveInfo info) {
        return info == null || info.filter == null
                || info.activityInfo.name.endsWith("ResolverActivity")
                || "com.android.fallback.Fallback".equals(info.activityInfo.name);
    }
}
