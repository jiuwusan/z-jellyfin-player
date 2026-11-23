package cn.jiuwusan.jellyfin.player;

/** Sticky cancellation for one pointer sequence, independent of scroll position. */
final class TapGesture {
    private final float slopSquared;
    private float downX, downY;
    private boolean blocked;

    TapGesture(float slop) { slopSquared = slop * slop; }

    void start(float x, float y) {
        downX = x;
        downY = y;
        blocked = false;
    }

    void move(float x, float y) {
        float dx = x - downX, dy = y - downY;
        if (dx * dx + dy * dy > slopSquared) blocked = true;
    }

    void cancel() { blocked = true; }
    boolean blocked() { return blocked; }
}
