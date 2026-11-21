package cn.jiuwusan.jellyfin.player;
public final class PlaybackPolicyCheck {
    public static void main(String[] args) {
        check(PlaybackPolicy.needsResize(3840, 1600));
        check(PlaybackPolicy.needsResize(1920, 1200));
        check(!PlaybackPolicy.needsResize(1920, 800));
        check(!PlaybackPolicy.needsResize(1920, 1080));
        check(PlaybackPolicy.VIDEO_BPS + PlaybackPolicy.AUDIO_BPS == 4_000_000);
        PlaybackPolicy hd = PlaybackPolicy.fromSettings(1920, 4_000_000, 1080, 2400);
        check(hd.requiresTranscode(3840, 1608, 3_000_000));
        check(hd.requiresTranscode(1920, 1080, 4_000_001));
        check(!hd.requiresTranscode(1920, 1080, 4_000_000));
        check(hd.audioBitrate() == 192_000);
        PlaybackPolicy sd = PlaybackPolicy.fromSettings(852, 420_000, 1080, 2400);
        check(sd.maxHeight == 480 && sd.audioBitrate() == 105_000);
        PlaybackPolicy display = PlaybackPolicy.fromSettings(-1, 0, 1080, 2400);
        check(display.maxWidth == 2400 && display.maxHeight == 1080);
        PlaybackPolicy auto = PlaybackPolicy.fromSettings(0, 0, 1080, 2400);
        check(auto.maxWidth == 0 && !auto.requiresTranscode(3840, 2160, 18_000_000));
    }
    private static void check(boolean result) {
        if (!result) throw new AssertionError();
    }
}
