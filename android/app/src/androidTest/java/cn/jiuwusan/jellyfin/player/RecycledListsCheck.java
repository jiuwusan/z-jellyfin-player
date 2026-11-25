package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Bounded long-list rendering and keyed return/section insertion, without a media server. */
final class RecycledListsCheck {
    private final Instrumentation test;
    private Activity activity;
    private LibraryUi style;
    private RecycledMediaGrid grid;
    private final List<JellyfinApi.Item> items = new ArrayList<>();
    private String clicked;
    RecycledListsCheck(Instrumentation test) { this.test = test; }
    private void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private void ui(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        test.runOnMainSync(() -> { try { action.run(); } catch (Throwable e) { failure.set(e); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    private JellyfinApi.Item item(String id, String type) {
        try { return new JellyfinApi.Item(new JSONObject().put("Id", id).put("Name", "影片 " + id).put("Type", type)); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private RecycledMediaGrid create(boolean header) {
        LinearLayout heading = style.column();
        heading.addView(style.label("合集顶部", 24, LibraryUi.TEXT, true));
        return new RecycledMediaGrid(style, activity.getResources().getDisplayMetrics().widthPixels - style.dp(40),
                style.dp(20), header ? heading : null, style.column(), style.column(), item -> clicked = item.id);
    }
    private void settle() { test.waitForIdleSync(); SystemClock.sleep(120); test.waitForIdleSync(); }
    private void position(int index) { ui(() -> ((GridLayoutManager) grid.getLayoutManager()).scrollToPositionWithOffset(index, 0)); settle(); }
    private List<String> labels(View view) {
        List<String> found = new ArrayList<>();
        if (view instanceof TextView) found.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) found.addAll(labels(((ViewGroup) view).getChildAt(i)));
        return found;
    }
    void run() {
        Bundle result = new Bundle();
        try {
            activity = test.startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName(test.getTargetContext().getPackageName(), MainActivity.class.getName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            ui(() -> {
                try {
                    java.lang.reflect.Field page = MainActivity.class.getDeclaredField("page"); page.setAccessible(true);
                    page.setInt(activity, page.getInt(activity) + 1);
                } catch (Exception e) { throw new AssertionError(e); }
                style = new LibraryUi(activity, new Handler(Looper.getMainLooper()));
                for (int i = 0; i < 1000; i++) items.add(item("m" + i, "Movie"));
                grid = create(false); grid.display(items, false, false); activity.setContentView(grid);
            });
            settle();
            ui(() -> {
                check(grid.getAdapter().getItemCount() == 1001, "Missing logical items");
                check(grid.getChildCount() > 0 && grid.getChildCount() < 25, "Long list attached all cards");
                RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(0);
                int[] point = new int[2]; holder.itemView.getLocationOnScreen(point);
                check(Math.abs(point[0] - style.dp(20)) <= 1, "Poster gutter changed");
                int expected = (grid.getWidth() - style.dp(40) - style.dp(14)) / 2;
                check(Math.abs(holder.itemView.getWidth() - expected) <= 2, "Poster width changed");
            });
            int maximum = 0;
            for (int position : new int[]{200, 500, 850, 998, 400, 0}) {
                position(position);
                int[] count = {0}; ui(() -> count[0] = grid.getChildCount()); maximum = Math.max(maximum, count[0]);
                check(count[0] < 25, "Offscreen views accumulated: " + count[0]);
            }
            result.putInt("logicalMovies", items.size()); result.putInt("maximumAttachedCards", maximum);
            position(400);
            RecycledMediaGrid.Anchor[] before = new RecycledMediaGrid.Anchor[1];
            ui(() -> {
                before[0] = grid.anchor();
                grid = create(false); grid.display(items, false, false); grid.restore(before[0]); activity.setContentView(grid);
            });
            settle();
            ui(() -> check(grid.anchor().key.equals(before[0].key) && Math.abs(grid.anchor().offset - before[0].offset) <= 1,
                    "Recreated page lost item anchor"));
            ui(() -> {
                items.clear(); for (int i = 0; i < 100; i++) items.add(item("s" + i, "Series"));
                grid = create(true); grid.display(items, true, false); activity.setContentView(grid);
            });
            settle(); position(42);
            ui(() -> {
                before[0] = grid.anchor();
                items.add(item("late-movie", "Movie"));
                grid.display(items, true, true);
            });
            settle();
            ui(() -> check(grid.anchor().key.equals(before[0].key) && Math.abs(grid.anchor().offset - before[0].offset) <= 1,
                    "New section inserted above shifted visible card"));
            position(0);
            ui(() -> {
                check(labels(grid).contains("合集顶部") && labels(grid).contains("电影"), "Missing full-span header/heading");
                RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(2);
                check(holder != null, "Missing first collection member");
                long at = SystemClock.uptimeMillis();
                int slop = android.view.ViewConfiguration.get(activity).getScaledTouchSlop();
                for (int action : new int[]{android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE, android.view.MotionEvent.ACTION_UP}) {
                    android.view.MotionEvent event = android.view.MotionEvent.obtain(at, SystemClock.uptimeMillis(), action,
                            style.dp(20), style.dp(20) + (action == android.view.MotionEvent.ACTION_DOWN ? 0 : slop * 3), 0);
                    holder.itemView.dispatchTouchEvent(event); event.recycle();
                }
                check(clicked == null, "Dragging a recycled card triggered its click");
                holder.itemView.performClick(); check("late-movie".equals(clicked), "Recycled card clicked stale item");
            });
            // Replacing with a pure movie collection preserves the existing no-heading presentation.
            ui(() -> { items.clear(); items.add(item("single", "Movie")); grid.display(items, true, false); });
            settle();
            ui(() -> check(grid.getAdapter().getItemCount() == 3, "Pure movie collection gained an extra heading"));
            ui(() -> {
                items.clear(); for(int i=0;i<1000;i++) items.add(item("density-"+i,"Movie"));
                grid=create(false); grid.display(items,false,false); activity.setContentView(grid);
            });
            settle(); position(400);
            ui(() -> {
                before[0]=grid.anchor(); style.setLargeUi(false);
                grid=create(false); grid.display(items,false,false); grid.restore(before[0]); activity.setContentView(grid);
            });
            settle();
            ui(() -> {
                check(((GridLayoutManager)grid.getLayoutManager()).getSpanCount()==3,"Standard grid is not three columns");
                int original=RecycledListDriver.position(grid,"影片 density-400");
                View target=((GridLayoutManager)grid.getLayoutManager()).findViewByPosition(original);
                check(target!=null && target.getGlobalVisibleRect(new android.graphics.Rect()),"Density switch lost visible item");
                check(grid.getChildCount()<30 && grid.getAdapter().getItemCount()==1001,"Standard grid lost bounded rendering or data");
            });
            result.putString("result", "ALL_PASS: 1000 items / bounded attached cards / poster dimensions / keyed return / late section anchor / recycled click / collection header / density switch preserves visible item");
            test.finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            result.putString("failure", android.util.Log.getStackTraceString(e)); test.finish(Activity.RESULT_CANCELED, result);
        }
    }
}
