package cn.jiuwusan.jellyfin.player;

final class PlaybackPolicy {
    static final int WIDTH = 1920;
    static final int HEIGHT = 1080;
    static final int TOTAL_BPS = 4_000_000;
    static final int AUDIO_BPS = 192_000;
    static final int VIDEO_BPS = TOTAL_BPS - AUDIO_BPS;
    static final int[] WIDTHS = {0, -1, 640, 852, 1280, 1920, 3840, 7680};
    static final String[] RESOLUTION_LABELS = {"自动", "屏幕分辨率", "360p", "480p", "720p", "1080p", "4K", "8K"};
    static final int[] RATES = {0, 120_000_000, 80_000_000, 60_000_000, 40_000_000, 20_000_000,
            15_000_000, 10_000_000, 8_000_000, 6_000_000, 4_000_000, 3_000_000, 1_500_000, 720_000, 420_000};
    static final String[] RATE_LABELS = {"自动", "120 Mbps", "80 Mbps", "60 Mbps", "40 Mbps", "20 Mbps",
            "15 Mbps", "10 Mbps", "8 Mbps", "6 Mbps", "4 Mbps", "3 Mbps", "1.5 Mbps", "720 kbps", "420 kbps"};

    static boolean needsResize(int width, int height) {
        return new PlaybackPolicy(WIDTH, HEIGHT, TOTAL_BPS).requiresTranscode(width, height, 0);
    }

    final int maxWidth, maxHeight, totalBitrate;

    private PlaybackPolicy(int maxWidth, int maxHeight, int totalBitrate) {
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.totalBitrate = totalBitrate;
    }

    static PlaybackPolicy fromSettings(int resolution, int bitrate, int screenWidth, int screenHeight) {
        int width = resolution == -1 ? Math.max(screenWidth, screenHeight) : resolution;
        int height = resolution == -1 ? Math.min(screenWidth, screenHeight)
                : width == 852 ? 480 : (int) Math.round(width * 9.0 / 16.0);
        return new PlaybackPolicy(width, height, bitrate);
    }

    boolean requiresTranscode(int width, int height, long bitrate) {
        return (maxWidth > 0 && (width <= 0 || height <= 0 || width > maxWidth || height > maxHeight))
                || (totalBitrate > 0 && bitrate > totalBitrate);
    }

    int audioBitrate() {
        return totalBitrate <= 0 ? 0 : Math.min(AUDIO_BPS, totalBitrate / 4);
    }

    static int optionIndex(int[] options, int value, int fallback) {
        for (int i = 0; i < options.length; i++) {
            if (options[i] == value) return i;
        }
        for (int i = 0; i < options.length; i++) {
            if (options[i] == fallback) return i;
        }
        return 0;
    }
}
