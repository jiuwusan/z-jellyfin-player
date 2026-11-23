package cn.jiuwusan.jellyfin.player;
public final class LoadTimingCheck {
    public static void main(String[] args) {
        LoadTiming fast = new LoadTiming(1000);
        check(!fast.skeleton(1149) && !fast.slow(1149));
        fast.finish();
        check(!fast.skeleton(2000) && !fast.slow(9000));
        LoadTiming pending = new LoadTiming(0);
        check(pending.skeleton(150) && !pending.slow(5999));
        check(pending.slow(6000));
        pending.finish();
        check(!pending.skeleton(6100) && !pending.slow(6100));
        LoadTiming instant = new LoadTiming(0);
        instant.finish();
        check(!instant.skeleton(0));
        System.out.println("LoadTimingCheck passed: fast / skeleton / slow / complete / cancel");
    }
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
}
