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
import androidx.media3.common.Player;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Saved-account real-media check; counts requests without logging addresses or tokens. */
final class BrowseReturnCheck {
    private final Instrumentation test;
    private Activity activity;
    private final AtomicInteger requests = new AtomicInteger();
    private String decoder = "not captured";
    private final java.util.concurrent.atomic.AtomicBoolean delayResume = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean failResume = new java.util.concurrent.atomic.AtomicBoolean();
    private final AtomicInteger failedRequests = new AtomicInteger();
    BrowseReturnCheck(Instrumentation test) { this.test = test; }
    private void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private Object field(Object object, String name) {
        try { java.lang.reflect.Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private void invoke(String name, Class<?>[] types, Object... args) {
        try { java.lang.reflect.Method m = MainActivity.class.getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(activity, args); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private void ui(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        test.runOnMainSync(() -> { try { action.run(); } catch (Throwable e) { failure.set(e); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    private interface Condition { boolean ready(); }
    private void waitFor(Condition condition, String why) {
        boolean[] ready = {false};
        for (int i = 0; i < 600 && !ready[0]; i++) { ui(() -> ready[0] = condition.ready()); SystemClock.sleep(100); }
        check(ready[0], why);
    }
    private View root() { return activity.findViewById(android.R.id.content); }
    private ScrollView scroll(View view) {
        if (view instanceof ScrollView) return (ScrollView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            ScrollView found = scroll(((ViewGroup)view).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
    private boolean has(View view, String text) {
        if (view instanceof TextView && view.isShown() && ((TextView)view).getText().toString().contains(text)) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++)
            if (has(((ViewGroup)view).getChildAt(i), text)) return true;
        return false;
    }
    private void noSkeleton() { check(root().findViewById(R.id.loading_placeholder) == null, "Cached return displayed skeleton"); }
    private void stage(String value) { Bundle b = new Bundle(); b.putString("stage", value); test.sendStatus(0, b); }
    void run() {
        Bundle result = new Bundle();
        try {
            activity = test.startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName(test.getTargetContext().getPackageName(), MainActivity.class.getName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            String sealed = activity.getSharedPreferences("connection", 0).getString("sealed", null);
            check(sealed != null, "No saved connection");
            waitFor(() -> field(activity, "api") != null && field(field(activity, "api"), "userId") != null
                    && (has(root(), "继续观看") || has(root(), "最近添加")), "Auto-login/home not ready");
            JellyfinApi api = (JellyfinApi) field(activity, "api");
            java.lang.reflect.Field http = JellyfinApi.class.getDeclaredField("http"); http.setAccessible(true);
            http.set(api, api.client().newBuilder().addInterceptor(chain -> {
                String path = chain.request().url().encodedPath();
                if (!path.contains("/Images/") && !path.contains("/Sessions/")) requests.incrementAndGet();
                if (path.endsWith("/Items/Resume")) {
                    if (delayResume.get()) SystemClock.sleep(3000);
                    if (failResume.get()) { failedRequests.incrementAndGet(); throw new java.io.IOException("Controlled refresh failure"); }
                }
                return chain.proceed(chain.request());
            }).build());
            // Reflection deliberately makes the old implementation fail here, before playback.
            BrowseMemory memory = (BrowseMemory) field(activity, "browseMemory");
            HomeFeed.Result<JellyfinApi.Item> home = memory.get("home");
            check(home != null && !home.items.isEmpty(), "No cached home content");
            JellyfinApi.Item selected = home.items.get(0);
            int[] homeY = {0};
            ui(() -> { ScrollView s = scroll(root()); s.scrollTo(0, 160); homeY[0] = s.getScrollY();
                invoke("detailsPage", new Class<?>[]{JellyfinApi.Item.class, Runnable.class}, selected,
                        (Runnable) () -> invoke("homePage", new Class<?>[]{})); });
            String detailId = "Episode".equals(selected.type) ? selected.seriesId : selected.id;
            waitFor(() -> memory.get("detail:" + detailId) != null && has(root(), "媒体详情"), "Detail not loaded");
            if ("Episode".equals(selected.type) || selected.series)
                waitFor(() -> memory.get("seasons:" + detailId) != null && memory.get("target:" + detailId) != null
                        && root().findViewById(R.id.loading_placeholder) == null, "Season/episodes not loaded");
            SystemClock.sleep(600);
            ui(() -> activity.onBackPressed());
            SystemClock.sleep(300);
            ui(() -> { noSkeleton(); check(Math.abs(scroll(root()).getScrollY() - homeY[0]) <= 3, "Home return lost position"); });
            int baseline = requests.get();
            ui(() -> invoke("detailsPage", new Class<?>[]{JellyfinApi.Item.class, Runnable.class}, selected,
                    (Runnable) () -> invoke("homePage", new Class<?>[]{})));
            SystemClock.sleep(400);
            ui(this::noSkeleton);
            check(requests.get() == baseline, "Cached detail made duplicate requests");
            ui(() -> activity.onBackPressed()); SystemClock.sleep(250);
            check(requests.get() == baseline, "Home return made duplicate requests");
            stage("home/detail/season return: no duplicate requests, no skeleton; scroll restored");
            ui(() -> invoke("librariesPage", new Class<?>[]{}));
            waitFor(() -> field(activity, "savedCatalog") != null && field(field(field(activity, "savedCatalog"), "selected"), "loaded").equals(true), "Library not loaded");
            SystemClock.sleep(500);
            int[] libraryY = {0};
            ui(() -> { ScrollView s = scroll(root()); s.scrollTo(0, 500); libraryY[0] = s.getScrollY();
                invoke("homePage", new Class<?>[]{}); });
            baseline = requests.get();
            ui(() -> invoke("librariesPage", new Class<?>[]{})); SystemClock.sleep(400);
            ui(() -> { noSkeleton(); check(Math.abs(scroll(root()).getScrollY() - libraryY[0]) <= 3, "Bottom navigation lost library position"); });
            check(requests.get() == baseline, "Bottom navigation reloaded library");
            stage("library bottom navigation: data and scroll retained");
            ui(() -> invoke("playbackPage", new Class<?>[]{JellyfinApi.Item.class, Long.class, Runnable.class}, selected,
                    Long.valueOf(Math.max(60_000, selected.resumeMs)), (Runnable) () -> invoke("homePage", new Class<?>[]{})));
            AtomicReference<ExoPlayer> listened = new AtomicReference<>();
            waitFor(() -> {
                ExoPlayer p = (ExoPlayer) field(activity, "player");
                if (p != null && p != listened.get()) { listened.set(p); p.addAnalyticsListener(new AnalyticsListener() {
                    @Override public void onVideoDecoderInitialized(EventTime t, String name, long at, long duration) { decoder = name; }
                }); }
                return p != null && p.isPlaying() && root().findViewById(R.id.playback_preparation_cover).getVisibility() == View.GONE;
            }, "Playback did not render");
            SystemClock.sleep(1500);
            delayResume.set(true);
            long[] position = {0};
            ui(() -> {
                ExoPlayer p = (ExoPlayer)field(activity, "player"); p.pause(); position[0] = p.getCurrentPosition();
                Format f = p.getVideoFormat(); check(f != null && f.width <= 1920 && f.height <= 1080, "Resolution cap exceeded");
                result.putString("video", f.sampleMimeType + " " + f.width + "x" + f.height);
                activity.onBackPressed(); noSkeleton();
                check(has(root(), "继续观看"), "Playback return lost home content");
                HomeFeed.Result<JellyfinApi.Item> cached = memory.get("home");
                boolean found = false;
                for (JellyfinApi.Item item : cached.items) if (item.id.equals(selected.id)) {
                    found = true; check(Math.abs(item.resumeMs - position[0]) < 1500, "Local progress was not applied immediately");
                }
                check(found, "Played item missing from cached home");
            });
            SystemClock.sleep(800);
            ui(() -> {
                noSkeleton();
                android.widget.ImageView image = largeImage(root());
                check(image != null && Math.abs(image.getWidth() * 9 / 16 - image.getHeight()) < 8,
                        "Immediate portrait return sized home artwork using landscape width");
                check(!((androidx.swiperefreshlayout.widget.SwipeRefreshLayout) root().findViewById(R.id.home_refresh)).isRefreshing(),
                        "Silent progress synchronization displayed refresh spinner");
            });
            SystemClock.sleep(4500);
            delayResume.set(false);
            waitFor(() -> !((Boolean)field(activity, "homeDirty")), "Silent synchronization did not finish");
            ui(this::noSkeleton);
            stage("delayed server progress refresh: cached content stays visible without spinner");
            failResume.set(true);
            ui(this::refreshHome);
            waitFor(() -> failedRequests.get() > 0, "Refresh listener did not send the controlled request");
            stage("controlled refresh request failed as intended");
            waitFor(() -> has(root(), "刷新失败，保留原有内容"), "Refresh failure feedback absent");
            ui(() -> { noSkeleton(); check(has(root(), "继续观看"), "Failed refresh removed home content"); });
            failResume.set(false);
            ui(this::refreshHome);
            waitFor(() -> !((androidx.swiperefreshlayout.widget.SwipeRefreshLayout)root().findViewById(R.id.home_refresh)).isRefreshing()
                    && !has(root(), "刷新失败，保留原有内容"), "Explicit refresh retry did not finish");
            stage("explicit pull refresh: failure preserves content and retry succeeds");
            check(sealed.equals(activity.getSharedPreferences("connection", 0).getString("sealed", null)), "Saved connection changed");
            check(!"not captured".equals(decoder) && !decoder.startsWith("c2.android."), "Hardware decoder not observed");
            result.putString("decoder", decoder);
            result.putString("result", "ALL_PASS: home/detail/season reuse / library bottom-nav scroll / hardware first-frame / immediate stopped progress / silent delayed sync / refresh failure+retry / no skeleton / unchanged connection");
            test.finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            result.putString("failure", android.util.Log.getStackTraceString(e));
            test.finish(Activity.RESULT_CANCELED, result);
        }
    }
    private void refreshHome() {
        androidx.swiperefreshlayout.widget.SwipeRefreshLayout refresh = root().findViewById(R.id.home_refresh);
        try {
            java.lang.reflect.Field listener = androidx.swiperefreshlayout.widget.SwipeRefreshLayout.class.getDeclaredField("mListener");
            listener.setAccessible(true);
            refresh.setRefreshing(true);
            ((androidx.swiperefreshlayout.widget.SwipeRefreshLayout.OnRefreshListener)listener.get(refresh)).onRefresh();
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private android.widget.ImageView largeImage(View view) {
        if (view instanceof android.widget.ImageView && view.getWidth() > 400) return (android.widget.ImageView)view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            android.widget.ImageView found = largeImage(((ViewGroup)view).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
}
