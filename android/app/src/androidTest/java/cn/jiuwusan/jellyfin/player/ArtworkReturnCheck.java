package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.media3.exoplayer.ExoPlayer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Read-only network/cache diagnostic on an existing account; does not play media. */
final class ArtworkReturnCheck {
    private final Instrumentation test;
    private Activity activity;
    private LruCache<String, Bitmap> cache;
    private final AtomicInteger images = new AtomicInteger(), data = new AtomicInteger(), active = new AtomicInteger();
    ArtworkReturnCheck(Instrumentation test) { this.test = test; }
    private void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
    private Object field(Object object, String name) {
        try { java.lang.reflect.Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private void ui(Runnable work) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        test.runOnMainSync(() -> { try { work.run(); } catch (Throwable e) { error.set(e); } });
        if (error.get() != null) throw new AssertionError(error.get());
    }
    private void invoke(String name, Class<?>[] types, Object... args) {
        try { java.lang.reflect.Method m = MainActivity.class.getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(activity, args); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private interface Ready { boolean get(); }
    private void waitFor(Ready condition, String why) {
        boolean[] ok = {false};
        for (int i = 0; i < 600 && !ok[0]; i++) { ui(() -> ok[0] = condition.get()); SystemClock.sleep(100); }
        check(ok[0], why);
    }
    private void settleImages() {
        int unchanged = 0, last = -1;
        for (int i = 0; i < 100; i++) {
            SystemClock.sleep(500);
            int count = images.get();
            if (count == last && active.get() == 0) unchanged++; else unchanged = 0;
            if (unchanged >= 6) break;
            last = count;
        }
        test.waitForIdleSync();
    }
    private void stage(String name) {
        Bundle b = new Bundle();
        b.putString("stage", name + " cacheBytes=" + cache.size() + " entries=" + cache.snapshot().size()
                + " evictions=" + cache.evictionCount() + " hits=" + cache.hitCount() + " misses=" + cache.missCount()
                + " imageRequests=" + images.get() + " dataRequests=" + data.get());
        test.sendStatus(0, b);
    }
    private View text(View view, String value) {
        if (view instanceof TextView && !(view instanceof android.widget.EditText)
                && value.equals(((TextView)view).getText().toString())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            View found = text(((ViewGroup)view).getChildAt(i), value); if (found != null) return found;
        }
        return null;
    }
    private void click(String value) {
        View view = text(activity.findViewById(android.R.id.content), value);
        check(view != null, "Missing search result");
        while (!view.isClickable() && view.getParent() instanceof View) view = (View)view.getParent();
        check(view.performClick(), "Search result not clickable");
    }
    @SuppressWarnings("unchecked") void run() {
        Bundle result = new Bundle();
        try {
            activity = test.startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName(test.getTargetContext().getPackageName(), MainActivity.class.getName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            String sealed = activity.getSharedPreferences("connection", 0).getString("sealed", null);
            waitFor(() -> field(activity, "api") != null && field(field(activity, "api"), "userId") != null
                    && activity.findViewById(R.id.home_refresh) != null, "Home not ready");
            JellyfinApi api = (JellyfinApi)field(activity, "api");
            java.lang.reflect.Field http = JellyfinApi.class.getDeclaredField("http"); http.setAccessible(true);
            http.set(api, api.client().newBuilder().addInterceptor(chain -> {
                boolean image = chain.request().url().encodedPath().contains("/Images/");
                if (image) { images.incrementAndGet(); active.incrementAndGet(); } else data.incrementAndGet();
                try { return chain.proceed(chain.request()); }
                finally { if (image) active.decrementAndGet(); }
            }).build());
            cache = (LruCache<String, Bitmap>)field(field(field(activity, "libraryUi"), "artwork"), "smallCache");
            settleImages();
            ui(() -> invoke("librariesPage", new Class<?>[]{}));
            waitFor(() -> field(activity, "savedCatalog") != null
                    && field(field(activity, "savedCatalog"), "selected") != null
                    && (Boolean)field(field(field(activity, "savedCatalog"), "selected"), "loaded"), "Library not ready");
            settleImages(); stage("library initial");
            Object catalog = field(activity, "savedCatalog");
            List<JellyfinApi.Item> items = (List<JellyfinApi.Item>)field(field(catalog, "selected"), "items");
            JellyfinApi.Item selected = null;
            for (JellyfinApi.Item item : items) if (item.series || "Movie".equals(item.type)) { selected = item; break; }
            check(selected != null, "No playable detail card");
            JellyfinApi.Item item = selected;
            int[] cachedVisible = {0};
            ui(() -> { for (int i = 0; i < Math.min(6, items.size()); i++)
                if (cache.snapshot().containsKey(LibraryUi.artworkKey(items.get(i), false, false, false))) cachedVisible[0]++; });
            result.putInt("cachedFirstSixBeforeDetail", cachedVisible[0]);
            ui(() -> invoke("detailsPage", new Class<?>[]{JellyfinApi.Item.class, Runnable.class}, item,
                    (Runnable) () -> invoke("librariesPage", new Class<?>[]{})));
            waitFor(() -> activity.findViewById(android.R.id.content).findViewById(R.id.loading_placeholder) == null,
                    "Details not ready");
            settleImages(); stage("detail loaded");
            int beforeImages = images.get(), beforeData = data.get(), beforeEvictions = cache.evictionCount();
            ui(() -> activity.onBackPressed());
            settleImages(); stage("library returned");
            result.putInt("returnImageRequests", images.get() - beforeImages);
            result.putInt("returnDataRequests", data.get() - beforeData);
            result.putInt("returnEvictions", cache.evictionCount() - beforeEvictions);
            result.putInt("cacheLimitBytes", cache.maxSize());
            check(images.get() == beforeImages, "Returning to library downloaded " + (images.get() - beforeImages) + " images again");
            check(data.get() == beforeData, "Returning to library reloaded data");
            ui(() -> invoke("homePage", new Class<?>[]{})); settleImages();
            BrowseMemory memory = (BrowseMemory)field(activity, "browseMemory");
            HomeFeed.Result<JellyfinApi.Item> home = memory.get("home");
            check(home != null && !home.items.isEmpty(), "No home card to test");
            JellyfinApi.Item homeItem = home.items.get(0);
            ui(() -> invoke("openHomeDetails", new Class<?>[]{JellyfinApi.Item.class}, homeItem));
            settleImages(); int homeBefore = images.get();
            ui(() -> activity.onBackPressed()); settleImages();
            check(images.get() == homeBefore, "Home return reloaded artwork");
            result.putInt("homeReturnImageRequests", images.get() - homeBefore);
            stage("home returned without image requests");

            ui(() -> {
                try { java.lang.reflect.Field query = MainActivity.class.getDeclaredField("query"); query.setAccessible(true); query.set(activity, item.title); }
                catch (Exception e) { throw new AssertionError(e); }
                invoke("searchPage", new Class<?>[]{});
            });
            waitFor(() -> text(activity.findViewById(android.R.id.content), item.title) != null, "Search result not loaded");
            settleImages();
            ui(() -> click(item.title)); settleImages();
            int searchBefore = images.get();
            ui(() -> activity.onBackPressed()); settleImages();
            check(images.get() == searchBefore, "Search return reloaded artwork");
            result.putInt("searchReturnImageRequests", images.get() - searchBefore);
            stage("search returned without image requests");

            ui(() -> invoke("detailsPage", new Class<?>[]{JellyfinApi.Item.class, Runnable.class}, homeItem,
                    (Runnable) () -> invoke("homePage", new Class<?>[]{})));
            settleImages();
            ui(() -> invoke("playbackPage", new Class<?>[]{JellyfinApi.Item.class, Long.class, Runnable.class}, homeItem,
                    Long.valueOf(Math.max(60_000, homeItem.resumeMs)),
                    (Runnable) () -> invoke("detailsPage", new Class<?>[]{JellyfinApi.Item.class, Runnable.class}, homeItem,
                            (Runnable) () -> invoke("homePage", new Class<?>[]{}))));
            waitFor(() -> {
                ExoPlayer player = (ExoPlayer)field(activity, "player");
                return player != null && player.isPlaying()
                        && activity.findViewById(R.id.playback_preparation_cover).getVisibility() == View.GONE;
            }, "Real playback first frame absent");
            settleImages(); int detailBefore = images.get();
            ui(() -> activity.onBackPressed()); settleImages();
            check(images.get() == detailBefore, "Playback return reloaded detail artwork");
            result.putInt("playbackDetailReturnImageRequests", images.get() - detailBefore);
            stage("playback to detail returned without image requests");
            check(sealed != null && sealed.equals(activity.getSharedPreferences("connection", 0).getString("sealed", null)),
                    "Saved connection changed");
            result.putString("result", "ALL_PASS: zero image requests on library/home/search/playback-detail return; account unchanged");
            test.finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            result.putString("failure", android.util.Log.getStackTraceString(e));
            test.finish(Activity.RESULT_CANCELED, result);
        }
    }
}
