package cn.jiuwusan.jellyfin.player;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.util.LruCache;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Activity-scoped artwork. All bookkeeping runs on main; only decoding/network run on workers. */
final class ArtworkLoader {
    interface Source { Bitmap load(int width, int height) throws IOException; }
    private final Handler main;
    private final ExecutorService workers = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> smallCache, largeCache;
    private final Map<ImageView, Binding> attached = new WeakHashMap<>();
    private final Map<ImageView, Binding> bindings = new WeakHashMap<>();
    private final Map<String, Job> jobs = new HashMap<>();
    private final LruCache<String, Long> failedRoutes = new LruCache<>(64);
    private final Rect viewport = new Rect();
    private final int margin;
    private long generation;
    private boolean closed, scanQueued;
    private View observedRoot;
    private final Runnable scanTask = () -> { scanQueued = false; scan(); };
    private final ViewTreeObserver.OnScrollChangedListener scrollChanged = this::scheduleScan;
    private final ViewTreeObserver.OnGlobalLayoutListener layoutChanged = this::scheduleScan;

    ArtworkLoader(Context context, Handler main) {
        this.main = main;
        ActivityManager manager = (ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        int memoryMiB = manager == null ? 128 : manager.getMemoryClass();
        int budget = Math.max(2, Math.min(24, memoryMiB / 8)) * 1024 * 1024;
        smallCache = bitmapCache(budget);
        largeCache = bitmapCache(budget);
        margin = Math.round(100 * context.getResources().getDisplayMetrics().density);
    }

    private static LruCache<String, Bitmap> bitmapCache(int bytes) {
        return new LruCache<String, Bitmap>(bytes) {
            @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
        };
    }

    void bind(ImageView image, String key, boolean large, Source source) {
        bind(image, key, large, source, null, null);
    }

    void bind(ImageView image, String key, boolean large, Source source, String fallbackKey, Source fallback) {
        unbind(image);
        if (closed) return;
        Binding binding = new Binding(image, key, large, source, generation);
        binding.fallbackKey = fallbackKey; binding.fallback = fallback;
        Long failedAt = failedRoutes.get((large ? "large:" : "small:") + key);
        if (fallback != null && failedAt != null && android.os.SystemClock.uptimeMillis() - failedAt < 30_000)
            binding.useFallback();
        Bitmap cached = cache(large).get(binding.key);
        if (cached != null) { image.setImageBitmap(cached); binding.complete = true; }
        else if (binding.fallback != null) {
            Bitmap fallbackHit = cache(large).get(binding.fallbackKey);
            if (fallbackHit != null) image.setImageBitmap(fallbackHit);
        }
        binding.listener = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                ImageView target = (ImageView) view;
                if (binding.epoch != generation || closed || bindings.get(target) != binding) return;
                attached.put(target, binding);
                Bitmap hit = cache(binding.large).get(binding.key);
                if (hit != null) { target.setImageBitmap(hit); binding.complete = true; }
                observe(target.getRootView());
                scheduleScan();
            }
            @Override public void onViewDetachedFromWindow(View view) {
                ImageView target = (ImageView) view;
                if (attached.get(target) == binding) attached.remove(target);
                unsubscribe(binding);
                target.setImageDrawable(null);
                binding.complete = false;
            }
        };
        bindings.put(image, binding);
        image.addOnAttachStateChangeListener(binding.listener);
        if (image.isAttachedToWindow()) {
            attached.put(image, binding);
            observe(image.getRootView());
            scheduleScan();
        }
    }

    void unbind(ImageView image) {
        Binding previous = bindings.remove(image);
        if (previous != null) {
            image.removeOnAttachStateChangeListener(previous.listener);
            unsubscribe(previous);
            if (attached.get(image) == previous) attached.remove(image);
        }
        image.setImageDrawable(null);
    }

    private LruCache<String, Bitmap> cache(boolean large) { return large ? largeCache : smallCache; }
    private void observe(View root) {
        if (observedRoot == root) return;
        unobserve();
        observedRoot = root;
        root.getViewTreeObserver().addOnScrollChangedListener(scrollChanged);
        root.getViewTreeObserver().addOnGlobalLayoutListener(layoutChanged);
    }
    private void unobserve() {
        if (observedRoot != null && observedRoot.getViewTreeObserver().isAlive()) {
            observedRoot.getViewTreeObserver().removeOnScrollChangedListener(scrollChanged);
            observedRoot.getViewTreeObserver().removeOnGlobalLayoutListener(layoutChanged);
        }
        observedRoot = null;
    }
    private void scheduleScan() {
        if (closed || scanQueued) return;
        scanQueued = true;
        main.post(scanTask);
    }
    private boolean nearViewport(ImageView image) {
        if (!image.isShown() || image.getWidth() <= 0 || image.getHeight() <= 0) return false;
        if (image.getGlobalVisibleRect(viewport)) return true;
        // A small look-ahead avoids blank images at the edge without loading an entire page.
        View root = image.getRootView();
        if (!root.getGlobalVisibleRect(viewport)) return false;
        int[] point = new int[2]; image.getLocationOnScreen(point);
        return point[0] < viewport.right && point[0] + image.getWidth() > viewport.left
                && point[1] < viewport.bottom + margin && point[1] + image.getHeight() > viewport.top - margin;
    }
    private void scan() {
        if (closed) return;
        for (Binding binding : new ArrayList<>(attached.values())) {
            ImageView image = binding.image.get();
            if (image == null || binding.epoch != generation || binding.complete || binding.failed) continue;
            if (!nearViewport(image)) { unsubscribe(binding); continue; }
            if (binding.job != null) continue;
            Bitmap cached = cache(binding.large).get(binding.key);
            if (cached != null) {
                image.setImageBitmap(cached); binding.complete = true;
                continue;
            }
            String jobKey = (binding.large ? "large:" : "small:") + binding.key;
            Job job = jobs.get(jobKey);
            if (job == null) {
                job = new Job(jobKey, binding, image.getWidth(), image.getHeight());
                jobs.put(jobKey, job);
                Job request = job;
                job.future = workers.submit(() -> {
                    request.started = true;
                    Bitmap bitmap = null;
                    try { bitmap = request.source.load(request.width, request.height); }
                    catch (IOException | RuntimeException ignored) { /* Preserve the clickable placeholder. */ }
                    Bitmap result = bitmap;
                    main.post(() -> deliver(request, result));
                });
            }
            job.waiters.add(binding);
            binding.job = job;
        }
    }
    private void unsubscribe(Binding binding) {
        Job job = binding.job;
        if (job == null) return;
        binding.job = null;
        job.waiters.remove(binding);
        if (job.waiters.isEmpty() && !job.started) {
            if (job.future != null) job.future.cancel(false);
            if (jobs.get(job.key) == job) jobs.remove(job.key);
        }
    }
    private void deliver(Job job, Bitmap bitmap) {
        if (closed || job.epoch != generation || jobs.get(job.key) != job) return;
        jobs.remove(job.key);
        if (bitmap == null) failedRoutes.put(job.key, android.os.SystemClock.uptimeMillis());
        else failedRoutes.remove(job.key);
        LruCache<String, Bitmap> cache = cache(job.large);
        // A single oversize image must never empty the rest of its cache.
        if (bitmap != null && bitmap.getAllocationByteCount() <= cache.maxSize()) cache.put(job.cacheKey, bitmap);
        for (Binding binding : new ArrayList<>(job.waiters)) {
            if (binding.job != job) continue;
            binding.job = null;
            ImageView image = binding.image.get();
            if (image == null || !image.isAttachedToWindow() || attached.get(image) != binding) continue;
            if (bitmap == null) {
                if (binding.fallback != null) { binding.useFallback(); scheduleScan(); }
                else binding.failed = true;
                continue;
            }
            image.setImageBitmap(bitmap);
            binding.complete = true;
            LoadingUi.ready(image);
        }
        job.waiters.clear();
    }
    void reset() {
        generation++;
        for (Job job : jobs.values()) if (job.future != null) job.future.cancel(false);
        jobs.clear();
        for (ImageView image : new ArrayList<>(bindings.keySet())) if (image != null) unbind(image);
        attached.clear();
        failedRoutes.evictAll();
        smallCache.evictAll(); largeCache.evictAll();
    }
    void close() {
        reset(); closed = true;
        unobserve(); main.removeCallbacks(scanTask);
        workers.shutdownNow();
    }
    private static final class Binding {
        final WeakReference<ImageView> image;
        String key;
        final boolean large;
        Source source;
        String fallbackKey;
        Source fallback;
        final long epoch;
        boolean complete, failed;
        Job job;
        View.OnAttachStateChangeListener listener;
        Binding(ImageView image, String key, boolean large, Source source, long epoch) {
            this.image = new WeakReference<>(image); this.key = key; this.large = large; this.source = source; this.epoch = epoch;
        }
        void useFallback() {
            key = fallbackKey; source = fallback; fallback = null; fallbackKey = null;
        }
    }
    private static final class Job {
        final String key, cacheKey;
        final boolean large;
        final long epoch;
        final Source source;
        final int width, height;
        final ArrayList<Binding> waiters = new ArrayList<>();
        volatile boolean started;
        Future<?> future;
        Job(String key, Binding binding, int width, int height) {
            this.key = key; cacheKey = binding.key; large = binding.large; epoch = binding.epoch;
            source = binding.source; this.width = width; this.height = height;
        }
    }
}
