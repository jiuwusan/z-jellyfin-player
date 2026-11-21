package cn.jiuwusan.jellyfin.playbackprobe;
public final class PlaybackPolicyCheck {
    public static void main(String[] args) {
        check(PlaybackPolicy.needsResize(3840, 1600));
        check(PlaybackPolicy.needsResize(1920, 1200));
        check(!PlaybackPolicy.needsResize(1920, 800));
        check(!PlaybackPolicy.needsResize(1920, 1080));
        check(PlaybackPolicy.VIDEO_BPS + PlaybackPolicy.AUDIO_BPS == 4_000_000);
    }
    private static void check(boolean result) {
        if (!result) throw new AssertionError();
    }
}
