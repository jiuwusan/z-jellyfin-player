package cn.jiuwusan.jellyfin.player;

import android.content.Context;

/** Local presentation only: never part of a subscription or Jellyfin account configuration. */
final class DisplayStyle {
    static final String STORE = "display-preferences";
    static final String LARGE_UI = "large_ui";
    final boolean large;
    DisplayStyle(boolean large) { this.large = large; }
    int choose(int largeValue, int standardValue) { return large ? largeValue : standardValue; }
    int columns(int widthDp) { return large ? widthDp >= 650 ? 3 : 2 : widthDp >= 650 ? 5 : 3; }
    static DisplayStyle load(Context context) { return new DisplayStyle(context.getSharedPreferences(STORE,0).getBoolean(LARGE_UI,false)); }
    static void save(Context context, boolean large) {
        if(!context.getSharedPreferences(STORE,0).edit().putBoolean(LARGE_UI,large).commit())
            throw new IllegalStateException("无法保存显示偏好");
    }
}
