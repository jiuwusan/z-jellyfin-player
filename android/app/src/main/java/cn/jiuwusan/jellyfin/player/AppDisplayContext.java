package cn.jiuwusan.jellyfin.player;

import android.content.res.Configuration;

/** Local font policy. Display density, orientation, locale and screen geometry stay inherited. */
final class AppDisplayContext {
    private AppDisplayContext() {}
    static Configuration fontOverride() {
        Configuration override = new Configuration();
        override.fontScale = 1f;
        return override;
    }
}
