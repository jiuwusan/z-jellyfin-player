package cn.jiuwusan.jellyfin.playbackprobe;

final class PlaybackPolicy {
    static final int WIDTH = 1920;
    static final int HEIGHT = 1080;
    static final int TOTAL_BPS = 4_000_000;
    static final int AUDIO_BPS = 192_000;
    static final int VIDEO_BPS = TOTAL_BPS - AUDIO_BPS;

    static boolean needsResize(int width, int height) {
        return width <= 0 || height <= 0 || width > WIDTH || height > HEIGHT;
    }
}
