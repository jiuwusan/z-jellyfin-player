package cn.jiuwusan.jellyfin.player;

final class LoadTiming {
    static final long SKELETON_DELAY_MS = 150, SLOW_DELAY_MS = 6000;
    private final long started;
    private boolean finished;
    LoadTiming(long started) { this.started = started; }
    boolean skeleton(long now) { return !finished && now - started >= SKELETON_DELAY_MS; }
    boolean slow(long now) { return !finished && now - started >= SLOW_DELAY_MS; }
    void finish() { finished = true; }
}
