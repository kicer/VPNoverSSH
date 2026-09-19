package ru.anton2319.vpnoverssh.utils;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ru.anton2319.vpnoverssh.data.AppInfo;

public class AppInfoExtractor {

    private PackageManager packageManager;

    public AppInfoExtractor(PackageManager packageManager) {
        this.packageManager = packageManager;
    }

    /**
     * Apps that have a launcher entry, i.e. the ones the user can actually
     * see in the app drawer. Invisible services/providers are never useful
     * as tunnel-allowlist candidates and just bury the list.
     */
    public List<AppInfo> getLaunchableApps() {
        List<AppInfo> appList = new ArrayList<>();
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolveInfos =
                packageManager.queryIntentActivities(launcherIntent, 0);
        Set<String> seenPackages = new HashSet<>();

        for (ResolveInfo resolveInfo : resolveInfos) {
            ApplicationInfo packageInfo = resolveInfo.activityInfo.applicationInfo;
            // The app itself is always excluded from the tunnel and must not
            // be offered as a candidate: selecting it would deadlock SSH.
            if (packageInfo.packageName.equals("ru.anton2319.vpnoverssh")) {
                continue;
            }
            if (!seenPackages.add(packageInfo.packageName)) {
                continue;
            }
            boolean isSystemApp = (packageInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    && (packageInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
            String humanName = packageManager.getApplicationLabel(packageInfo).toString();
            Drawable icon = packageManager.getApplicationIcon(packageInfo);
            appList.add(new AppInfo(humanName, packageInfo.packageName, icon, isSystemApp));
        }

        Collections.sort(appList, (a, b) ->
                a.getHumanName().compareToIgnoreCase(b.getHumanName()));
        return appList;
    }
}