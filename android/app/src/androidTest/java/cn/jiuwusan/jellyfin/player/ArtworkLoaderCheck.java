package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Controlled image pipeline checks, no real server requests or saved-setting writes. */
final class ArtworkLoaderCheck {
    private final Instrumentation test;
    private Activity activity;
    private ArtworkLoader loader;
    private LinearLayout content;
    ArtworkLoaderCheck(Instrumentation test) { this.test = test; }
    private void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private void ui(Runnable work) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        test.runOnMainSync(() -> { try { work.run(); } catch (Throwable e) { failure.set(e); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    private Bitmap bitmap(int color) { Bitmap b = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888); b.eraseColor(color); return b; }
    private ImageView image(String key, boolean large, ArtworkLoader.Source source) {
        ImageView view = new ImageView(activity);
        int height = Math.round(160 * activity.getResources().getDisplayMetrics().density);
        loader.bind(view, key, large, source);
        content.addView(view, new LinearLayout.LayoutParams(-1, height));
        return view;
    }
    private void waitImage(ImageView view) {
        boolean[] ready = {false};
        for (int i = 0; i < 150 && !ready[0]; i++) { ui(() -> ready[0] = view.getDrawable() != null); SystemClock.sleep(50); }
        check(ready[0], "Visible image did not load");
    }
    @SuppressWarnings("unchecked") void run() {
        Bundle result = new Bundle();
        CountDownLatch release = new CountDownLatch(1), oldRelease = new CountDownLatch(1);
        try {
            activity = test.startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName(test.getTargetContext().getPackageName(), MainActivity.class.getName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            ui(() -> {
                try { java.lang.reflect.Field page = MainActivity.class.getDeclaredField("page"); page.setAccessible(true); page.setInt(activity, page.getInt(activity) + 1); }
                catch (Exception e) { throw new AssertionError(e); }
                loader = new ArtworkLoader(activity, new Handler(Looper.getMainLooper()));
                content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
                ScrollView scroll = new ScrollView(activity); scroll.addView(content); activity.setContentView(scroll);
            });
            AtomicInteger requests = new AtomicInteger();
            ImageView[] shared = new ImageView[2];
            ArtworkLoader.Source blocked = (w, h) -> {
                requests.incrementAndGet();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new java.io.IOException(e); }
                return bitmap(Color.CYAN);
            };
            ui(() -> { shared[0] = image("shared", false, blocked); shared[1] = image("shared", false, blocked); });
            SystemClock.sleep(450); check(requests.get() == 1, "Duplicate visible image requests were not coalesced");
            release.countDown(); waitImage(shared[0]); waitImage(shared[1]);
            ui(() -> {
                content.removeAllViews();
                ImageView hit = image("shared", false, blocked);
                check(hit.getDrawable() != null && hit.getAlpha() == 1f, "Cached bitmap was not applied synchronously without fade");
            });
            AtomicInteger offscreen = new AtomicInteger();
            ui(() -> {
                content.removeAllViews();
                for (int i = 0; i < 80; i++) image("scroll-" + i, false, (w, h) -> { offscreen.incrementAndGet(); return bitmap(Color.GRAY); });
            });
            SystemClock.sleep(900);
            check(offscreen.get() > 0 && offscreen.get() < 12, "Entire offscreen catalog loaded: " + offscreen.get());
            result.putInt("initialVisibleImageLoads", offscreen.get());
            ImageView[] scrolled = new ImageView[1];
            ui(() -> {
                scrolled[0] = (ImageView)content.getChildAt(30);
                ((ScrollView)content.getParent()).scrollTo(0, scrolled[0].getTop());
            });
            waitImage(scrolled[0]);
            check(offscreen.get() < 25, "Scrolling loaded the whole catalog");
            result.putInt("afterScrollImageLoads", offscreen.get());
            java.lang.reflect.Field small = ArtworkLoader.class.getDeclaredField("smallCache"), large = ArtworkLoader.class.getDeclaredField("largeCache");
            small.setAccessible(true); large.setAccessible(true);
            LruCache<String, Bitmap> smallCache = (LruCache<String, Bitmap>)small.get(loader);
            LruCache<String, Bitmap> largeCache = (LruCache<String, Bitmap>)large.get(loader);
            ui(() -> {
                check(smallCache.get("shared") != null, "Shared thumbnail unexpectedly missing");
                largeCache.put("detail", bitmap(Color.RED)); largeCache.evictAll();
                check(smallCache.get("shared") != null, "Detail cache evicted a thumbnail");
                content.removeAllViews();
            });
            AtomicInteger obsolete = new AtomicInteger();
            ui(() -> image("account", false, (w, h) -> {
                obsolete.incrementAndGet();
                try { oldRelease.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new java.io.IOException(e); }
                return bitmap(Color.RED);
            }));
            for (int i = 0; i < 100 && obsolete.get() == 0; i++) SystemClock.sleep(20);
            check(obsolete.get() == 1, "Old connection image did not start");
            ImageView[] fresh = new ImageView[1];
            ui(() -> { loader.reset(); content.removeAllViews(); fresh[0] = image("account", false, (w, h) -> bitmap(Color.BLUE)); });
            waitImage(fresh[0]); oldRelease.countDown(); SystemClock.sleep(300);
            ui(() -> {
                Bitmap b = ((BitmapDrawable)fresh[0].getDrawable()).getBitmap();
                check(b.getPixel(0, 0) == Color.BLUE, "Obsolete connection overwrote the new image");
                check(smallCache.get("shared") == null, "Account reset retained old thumbnails");
            });
            ImageView[] fallback = new ImageView[2];
            AtomicInteger primaryCalls = new AtomicInteger();
            ui(() -> {
                content.removeAllViews();
                for (int i = 0; i < 2; i++) {
                    final int color = i == 0 ? Color.RED : Color.GREEN;
                    ImageView image = new ImageView(activity);
                    loader.bind(image, "missing-series", false, (w, h) -> { primaryCalls.incrementAndGet(); return null; },
                            "episode-fallback-" + i, (w, h) -> bitmap(color));
                    content.addView(image, new LinearLayout.LayoutParams(-1, 300)); fallback[i] = image;
                }
            });
            waitImage(fallback[0]); waitImage(fallback[1]);
            check(primaryCalls.get() == 1, "Missing shared resource was not coalesced");
            ui(() -> {
                check(((BitmapDrawable)fallback[0].getDrawable()).getBitmap().getPixel(0, 0) == Color.RED
                        && ((BitmapDrawable)fallback[1].getDrawable()).getBitmap().getPixel(0, 0) == Color.GREEN,
                        "Shared failed resource mixed episode-specific fallback images");
                content.removeAllViews();
                ImageView cachedFallback = new ImageView(activity);
                loader.bind(cachedFallback, "missing-series", false, (w, h) -> { primaryCalls.incrementAndGet(); return null; },
                        "episode-fallback-0", (w, h) -> bitmap(Color.RED));
                content.addView(cachedFallback, new LinearLayout.LayoutParams(-1, 300));
                check(cachedFallback.getDrawable() != null && cachedFallback.getAlpha() == 1f,
                        "Cached fallback was not immediately reused");
            });
            ImageView[] recycled = new ImageView[1];
            ui(() -> {
                content.removeAllViews();
                recycled[0] = image("recycle-old", false, (w, h) -> bitmap(Color.RED));
            });
            waitImage(recycled[0]);
            CountDownLatch rebound = new CountDownLatch(1);
            ui(() -> {
                loader.bind(recycled[0], "recycle-new", false, (w, h) -> {
                    try { rebound.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new java.io.IOException(e); }
                    return bitmap(Color.BLUE);
                });
                check(recycled[0].getDrawable() == null, "Rebinding retained the previous item's image");
            });
            rebound.countDown(); waitImage(recycled[0]);
            CountDownLatch obsoleteImage = new CountDownLatch(1);
            AtomicInteger obsoleteStarted = new AtomicInteger();
            ui(() -> loader.bind(recycled[0], "late-old", false, (w, h) -> {
                obsoleteStarted.incrementAndGet();
                try { obsoleteImage.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new java.io.IOException(e); }
                return bitmap(Color.RED);
            }));
            for (int i = 0; i < 100 && obsoleteStarted.get() == 0; i++) SystemClock.sleep(10);
            check(obsoleteStarted.get() == 1, "Delayed old image did not start");
            ui(() -> loader.bind(recycled[0], "recycle-new", false, (w, h) -> bitmap(Color.BLUE)));
            obsoleteImage.countDown(); SystemClock.sleep(200);
            ui(() -> {
                check(((BitmapDrawable)recycled[0].getDrawable()).getBitmap().getPixel(0, 0) == Color.BLUE,
                        "Late image response overwrote a rebound card");
                content.removeView(recycled[0]);
                check(recycled[0].getDrawable() == null, "Detached card retained its bitmap");
                content.addView(recycled[0], new LinearLayout.LayoutParams(-1, 300));
                check(recycled[0].getDrawable() != null, "Reattached cached image was not restored synchronously");
                loader.unbind(recycled[0]);
                check(recycled[0].getDrawable() == null, "Explicit recycling retained bitmap");
            });
            java.lang.reflect.Field bindings = ArtworkLoader.class.getDeclaredField("bindings"); bindings.setAccessible(true);
            ui(() -> check(!((java.util.Map<?, ?>) read(bindings, loader)).containsKey(recycled[0]), "Image binding/listener survived explicit unbind"));
            result.putString("result", "ALL_PASS: coalescing / synchronous cache / visible-only loading / separate caches / obsolete account rejected / distinct fallback / rebind+late response / detach+reattach / explicit unbind");
            test.finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            result.putString("failure", android.util.Log.getStackTraceString(e)); test.finish(Activity.RESULT_CANCELED, result);
        } finally {
            release.countDown(); oldRelease.countDown(); if (loader != null) ui(loader::close);
        }
    }
    private static Object read(java.lang.reflect.Field field, Object target) {
        try { return field.get(target); } catch (Exception e) { throw new AssertionError(e); }
    }
}
