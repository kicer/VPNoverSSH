package ru.anton2319.vpnoverssh.data;

import android.graphics.drawable.Drawable;

public class AppInfo {
    private String humanName;
    private String packageName;
    private Drawable icon;
    private boolean systemApp;

    public AppInfo(String humanName, String packageName, Drawable icon, boolean systemApp) {
        this.humanName = humanName;
        this.packageName = packageName;
        this.icon = icon;
        this.systemApp = systemApp;
    }

    public String getHumanName() {
        return humanName;
    }

    public String getPackageName() {
        return packageName;
    }

    public Drawable getIcon() {
        return icon;
    }

    public boolean isSystemApp() {
        return systemApp;
    }
}
