package dev.android.dynamicisland.island.view;

import android.graphics.Color;

public final class AppGlowColors{

    private AppGlowColors() {}

    public static int getColor(String packageName){
        if(packageName == null) return Color.WHITE;

        switch (packageName) {
            case "com.instagram.android":
                return 0xFFB04CEB;
            case "com.facebook.katana":
                return 0xFF1877F2;
            case "com.whatsapp":
            case "com.whatsapp.w4b":
                return 0xFF25D366;
            case "com.snapchat.android":
                return 0xFFFFFC00;
            case "org.telegram.android":
            case "org.telegram.messenger":
                return 0xFF39A8F5;
            case "com.reddit.frontpage":
                return 0xFFFF4500;
            case "com.tiktok.android":
                return 0xFF69C9D0;
            default:
                return Color.WHITE;
        }
    }

    public static int getColor(dev.android.dynamicisland.island.model.IslandItem item) {
        if (item == null) return Color.WHITE;
        int known = getColor(item.packageName);
        return known != Color.WHITE ? known : item.accent != -1 && Color.alpha(item.accent) != 0
                ? item.accent | 0xFF000000 : Color.WHITE;
    }
}
