package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Real-library browsing only: no playback, configuration or library writes. */
final class CollectionsUiCheck {
    private final Instrumentation test;
    private Activity activity;
    CollectionsUiCheck(Instrumentation test) { this.test = test; }
    private void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private Object field(Object target, String name) {
        try { java.lang.reflect.Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private void invoke(String name) {
        try { java.lang.reflect.Method m = MainActivity.class.getDeclaredMethod(name); m.setAccessible(true); m.invoke(activity); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private void ui(Runnable work) {
        AtomicReference<Throwable> problem = new AtomicReference<>();
        test.runOnMainSync(() -> { try { work.run(); } catch (Throwable e) { problem.set(e); } });
        if (problem.get() != null) throw new AssertionError(problem.get());
    }
    private View root() { return activity.findViewById(android.R.id.content); }
    private View text(View root, String value) {
        if (root instanceof TextView && value.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View child = text(((ViewGroup) root).getChildAt(i), value); if (child != null) return child;
        }
        return null;
    }
    private ScrollView scroll(View root) {
        if (root instanceof ScrollView) return (ScrollView) root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            ScrollView found = scroll(((ViewGroup) root).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
    private View waitText(String value) {
        AtomicReference<View> found = new AtomicReference<>();
        for (int i = 0; i < 300 && found.get() == null; i++) {
            ui(() -> found.set(text(root(), value))); SystemClock.sleep(100);
        }
        check(found.get() != null, "Missing UI content: " + value); return found.get();
    }
    private void clickCard(String title) {
        View label = waitText(title);
        ui(() -> {
            View card = label;
            while (!card.isClickable() && card.getParent() instanceof View) card = (View) card.getParent();
            check(card.isClickable(), "Card has no action"); card.performClick();
        });
    }
    private void collect(View view, List<String> titles, List<String> actual) {
        if (view instanceof TextView && titles.contains(((TextView) view).getText().toString())) actual.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collect(((ViewGroup) view).getChildAt(i), titles, actual);
    }
    private void shot(String name) throws Exception {
        android.graphics.Bitmap bitmap = test.getUiAutomation().takeScreenshot();
        if (bitmap == null) return;
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(activity.getExternalFilesDir(null), name + ".png"))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        } finally { bitmap.recycle(); }
    }
    void run() {
        Bundle result = new Bundle();
        try {
            activity = test.startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName(test.getTargetContext().getPackageName(), MainActivity.class.getName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            String sealed = activity.getSharedPreferences("connection", 0).getString("sealed", null);
            AtomicReference<JellyfinApi> api = new AtomicReference<>();
            for (int i = 0; i < 300 && api.get() == null; i++) {
                ui(() -> {
                    JellyfinApi candidate = (JellyfinApi) field(activity, "api");
                    if (candidate != null && field(candidate, "userId") != null) api.set(candidate);
                }); SystemClock.sleep(100);
            }
            check(api.get() != null, "Saved login failed");
            JellyfinApi.Item movies = null;
            for (JellyfinApi.Item library : api.get().libraries()) if ("movies".equals(library.collectionType)) { movies = library; break; }
            check(movies != null, "No movie library");
            List<JellyfinApi.Item> catalog = new ArrayList<>();
            for (int offset = 0; offset < 5000;) {
                JellyfinApi.LibraryItemsPage batch = api.get().libraryItems(movies, true, offset);
                catalog.addAll(batch.items); offset += batch.items.size(); if (!batch.more) break;
            }
            ui(() -> invoke("librariesPage"));
            waitText(movies.title);
            String movieTitle = movies.title;
            ui(() -> text(root(), movieTitle).performClick());
            for (String prefix : new String[]{"速度与激情", "人类清除计划"}) {
                JellyfinApi.Item box = null;
                for (JellyfinApi.Item item : catalog) if (item.collection && item.title.startsWith(prefix)) { box = item; break; }
                check(box != null, "No visible collection: " + prefix);
                JellyfinApi.Item selected = box;
                List<String> expected = new ArrayList<>();
                for (JellyfinApi.Item member : api.get().libraryItems(box, false, 0).items) expected.add(member.title);
                check(!expected.isEmpty(), "Empty regression collection");
                for (int page = 0; page < 100; page++) {
                    AtomicReference<View> found = new AtomicReference<>(); ui(() -> found.set(text(root(), selected.title)));
                    if (found.get() != null) break;
                    ui(() -> { ScrollView list = scroll(root()); if (list != null) list.scrollTo(0, list.getChildAt(0).getHeight()); });
                    SystemClock.sleep(250);
                }
                View card = waitText(selected.title);
                final int[] outer = new int[1], inner = new int[1];
                ui(() -> {
                    ScrollView list = scroll(root());
                    int[] p = new int[2], q = new int[2]; card.getLocationOnScreen(p); list.getLocationOnScreen(q);
                    list.scrollBy(0, p[1] - q[1] - 120);
                });
                SystemClock.sleep(200);
                ui(() -> outer[0] = scroll(root()).getScrollY());
                clickCard(selected.title);
                waitText("合集详情"); waitText(expected.get(0));
                ui(() -> {
                    check(text(root(), "播放") == null && text(root(), "从头播放") == null, "Collection has direct playback action");
                    List<String> actual = new ArrayList<>(); collect(root(), expected, actual);
                    check(actual.equals(expected), "Collection members changed server order");
                });
                SystemClock.sleep(1200);
                shot("collection-" + prefix);
                ui(() -> { ScrollView list = scroll(root()); list.scrollTo(0, list.getChildAt(0).getHeight()); });
                SystemClock.sleep(200);
                ui(() -> inner[0] = scroll(root()).getScrollY());
                clickCard(expected.get(expected.size() - 1));
                waitText("媒体详情"); waitText(expected.get(expected.size() - 1));
                ui(() -> activity.onBackPressed());
                waitText("合集详情"); waitText(expected.get(0)); SystemClock.sleep(250);
                ui(() -> {
                    check(Math.abs(scroll(root()).getScrollY() - inner[0]) <= 3, "Collection scroll position lost after detail: before="
                            + inner[0] + " after=" + scroll(root()).getScrollY());
                    activity.onBackPressed();
                });
                waitText(selected.title); SystemClock.sleep(250);
                ui(() -> check(Math.abs(scroll(root()).getScrollY() - outer[0]) <= 3, "Outer catalog scroll position lost"));
                result.putString(prefix, "members=" + expected.size() + "; order/inner/outer restored");
            }
            check(sealed.equals(activity.getSharedPreferences("connection", 0).getString("sealed", null)), "Saved settings changed");
            result.putString("result", "ALL_PASS: real BoxSets / server member order / movie detail / inner+outer scroll / saved settings");
            test.finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            result.putString("failure", android.util.Log.getStackTraceString(e)); test.finish(Activity.RESULT_CANCELED, result);
        }
    }
}
