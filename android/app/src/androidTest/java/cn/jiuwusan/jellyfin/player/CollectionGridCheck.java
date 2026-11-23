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
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Purely local mixed-media/paging checks. No server or saved settings mutations. */
final class CollectionGridCheck {
    private final Instrumentation test;
    private Activity activity;
    private LibraryUi style;
    private LinearLayout target;
    private ScrollView scroll;
    private CollectionGrid grid;
    private final List<JellyfinApi.Item> items = new ArrayList<>();
    private String clicked;
    CollectionGridCheck(Instrumentation test) { this.test = test; }
    private void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private void ui(Runnable action) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        test.runOnMainSync(() -> { try { action.run(); } catch (Throwable e) { error.set(e); } });
        if (error.get() != null) throw new AssertionError(error.get());
    }
    private JellyfinApi.Item item(String id, String title, String type) {
        try { return new JellyfinApi.Item(new JSONObject().put("Id", id).put("Name", title).put("Type", type)); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private View text(View view, String title) {
        if (view instanceof TextView && title.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = text(((ViewGroup) view).getChildAt(i), title); if (found != null) return found;
        }
        return null;
    }
    private int top(View view) { int[] point = new int[2]; view.getLocationOnScreen(point); return point[1]; }
    private void titles(View view, List<String> found) {
        if (view instanceof TextView) found.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) titles(((ViewGroup) view).getChildAt(i), found);
    }
    private void click(String title) {
        View card = text(target, title);
        check(card != null, "Missing card " + title);
        while (!card.isClickable() && card.getParent() instanceof View) card = (View) card.getParent();
        card.performClick();
    }
    void run() {
        Bundle result = new Bundle();
        try {
            activity = test.startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName(test.getTargetContext().getPackageName(), MainActivity.class.getName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            SystemClock.sleep(600);
            ui(() -> {
                try {
                    java.lang.reflect.Field page = MainActivity.class.getDeclaredField("page"); page.setAccessible(true);
                    page.setInt(activity, page.getInt(activity) + 1);
                } catch (Exception e) { throw new RuntimeException(e); }
                style = new LibraryUi(activity, new Handler(Looper.getMainLooper()));
                target = style.column(); target.setBackgroundColor(LibraryUi.BACKGROUND);
                scroll = new ScrollView(activity); scroll.addView(target);
                activity.setContentView(scroll);
                grid = new CollectionGrid(target, style, activity.getResources().getDisplayMetrics().widthPixels, item -> clicked = item.id);
                for (int i = 0; i < 35; i++) items.add(item("s" + i, "剧集样例" + i, "Series"));
                grid.append(items, 0, scroll);
            });
            SystemClock.sleep(300);
            final int[] oldTop = new int[1];
            final View[] anchor = new View[1];
            ui(() -> {
                anchor[0] = text(target, "剧集样例12");
                scroll.scrollTo(0, top(anchor[0]) - top(scroll) - style.dp(40));
                oldTop[0] = top(anchor[0]);
                items.add(item("m3", "电影第三部", "Movie"));
                items.add(item("m1", "电影第一部", "Movie"));
                items.add(item("m2", "电影第二部", "Movie"));
                items.add(item("nested", "嵌套合集", "BoxSet"));
                grid.append(items, 35, scroll);
            });
            SystemClock.sleep(300);
            ui(() -> {
                check(Math.abs(top(anchor[0]) - oldTop[0]) <= 3, "New section moved visible item during paging");
                check(target.getChildCount() == 3, "Mixed media not grouped into movie/series/collection");
                List<String> labels = new ArrayList<>(); titles(target, labels);
                check(labels.indexOf("电影第三部") < labels.indexOf("电影第一部")
                        && labels.indexOf("电影第一部") < labels.indexOf("电影第二部"), "Members changed server order");
                click("嵌套合集"); check("nested".equals(clicked), "Nested collection action lost identity");
                click("电影第一部"); check("m1".equals(clicked), "Member action lost identity");
                items.clear(); items.add(item("duplicate1", "同名电影", "Movie")); items.add(item("duplicate2", "同名电影", "Movie"));
                grid.append(items, 0, scroll);
                check(target.getChildCount() == 1, "Refresh left old media sections");
                LinearLayout section = (LinearLayout) target.getChildAt(0);
                check(section.getChildAt(0).getVisibility() == View.GONE, "Single movie collection has unnecessary heading");
                List<String> refreshed = new ArrayList<>(); titles(target, refreshed);
                check(java.util.Collections.frequency(refreshed, "同名电影") == 2, "Same-name members were merged");
                items.clear(); grid.append(items, 0, scroll);
                check(target.getChildCount() == 0, "Empty refresh left stale cards");
                style.close();
            });
            result.putString("result", "ALL_PASS: mixed sections / member order / paging anchor / identity / refresh / empty / same-name members");
            test.finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            result.putString("failure", android.util.Log.getStackTraceString(e)); test.finish(Activity.RESULT_CANCELED, result);
        }
    }
}
