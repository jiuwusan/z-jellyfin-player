package cn.jiuwusan.jellyfin.player;

public final class TapGestureCheck {
    public static void main(String[] args) {
        TapGesture gesture = new TapGesture(8);
        gesture.start(100, 100); gesture.move(103, 104);
        check(!gesture.blocked(), "Small finger jitter remains a tap");
        gesture.move(100, 108);
        check(!gesture.blocked(), "Exact slop remains a tap");
        gesture.move(100, 109);
        check(gesture.blocked(), "Dragging past slop cancels a tap");
        gesture.move(100, 100);
        check(gesture.blocked(), "Returning to origin must not restore a tap");
        gesture.start(10, 10); gesture.move(25, 10);
        check(gesture.blocked(), "Final up position catches gestures without MOVE");
        gesture.start(50, 50); gesture.move(50, 30);
        check(gesture.blocked(), "Upward dragging also cancels");
        gesture.start(50, 50); gesture.move(44, 44);
        check(gesture.blocked(), "Diagonal distance uses both axes");
        gesture.start(10, 10); gesture.cancel();
        check(gesture.blocked(), "Cancellation or a second pointer cancels the tap");
        gesture.start(10, 10);
        check(!gesture.blocked(), "A new touch resets the cancelled gesture");
        System.out.println("TapGestureCheck passed: jitter, threshold, drag, return, up, cancel, reset");
    }
    private static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
}
