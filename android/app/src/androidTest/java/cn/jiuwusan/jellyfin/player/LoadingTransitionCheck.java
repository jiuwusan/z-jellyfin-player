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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Controlled view checks in the target process; no server or saved settings are changed. */
public final class LoadingTransitionCheck extends Instrumentation {
    private Activity activity;
    private LibraryUi style;
    private LinearLayout target, root, oldContent;
    private LoadingUi.Pending pending;
    private LoadingUi.RefreshNotice notice;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicInteger retries = new AtomicInteger();

    private boolean playbackSuite;
    private boolean realPlaybackSuite;
    private String collectionSuite;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        playbackSuite = args != null && "playback".equals(args.getString("suite"));
        realPlaybackSuite = args != null && "playback-real".equals(args.getString("suite"));
        collectionSuite = args == null ? "" : args.getString("suite", "");
        start();
    }
    private void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private boolean hasText(View view, String text) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(text)) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (hasText(((ViewGroup) view).getChildAt(i), text)) return true;
        return false;
    }
    private TextView button(View view) {
        if (view instanceof TextView && "重试".contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView child = button(((ViewGroup) view).getChildAt(i)); if (child != null) return child;
        }
        return null;
    }
    private void stage(String text) { Bundle b = new Bundle(); b.putString("stage", text); sendStatus(0, b); }
    private void onUi(Runnable work) {
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        runOnMainSync(() -> { try { work.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    @Override public void onStart() {
        if ("recycled-lists".equals(collectionSuite)) { new RecycledListsCheck(this).run(); return; }
        if ("recycled-paging".equals(collectionSuite)) { new RecycledPagingCheck(this).run(); return; }
        if ("fullscreen-controls".equals(collectionSuite)) { new FullscreenControlsCheck(this).run(); return; }
        if ("playback-end".equals(collectionSuite)) { new PlaybackEndCheck(this).run(); return; }
        if ("track-memory".equals(collectionSuite)) { new TrackMemoryCheck(this).run(); return; }
        if ("chinese-tracks".equals(collectionSuite)) { new ChineseTrackCheck(this).run(); return; }
        if ("subscription-recovery".equals(collectionSuite)) { new SubscriptionLifecycleCheck(this).runConnectionRecovery(); return; }
        if ("subscription-controller".equals(collectionSuite)) { new SubscriptionControllerCheck(this).run(); return; }
        if ("subscription-lifecycle".equals(collectionSuite)) { new SubscriptionLifecycleCheck(this).run(); return; }
        if ("subscription".equals(collectionSuite)) { new SubscriptionCheck(this).run(); return; }
        if ("artwork-loader".equals(collectionSuite)) { new ArtworkLoaderCheck(this).run(); return; }
        if ("artwork-return".equals(collectionSuite)) { new ArtworkReturnCheck(this).run(); return; }
        if ("browse-return".equals(collectionSuite)) { new BrowseReturnCheck(this).run(); return; }
        if ("srt-timeline".equals(collectionSuite)) { new SrtTimelineCheck(this).run(); return; }
        if ("srt-seek".equals(collectionSuite)) { new SubtitleCompatibilityCheck(this).runSrtCompatibility(); return; }
        if ("srt-budget".equals(collectionSuite)) { new SubtitleRecoveryRealCheck(this).run("srt-budget"); return; }
        if ("subtitle-style".equals(collectionSuite)) { new SubtitleStyleCheck(this).run(); return; }
        if ("subtitle-cache".equals(collectionSuite)) { new SubtitleCacheCheck(this).run(); return; }
        if ("subtitle-compat".equals(collectionSuite)) { new SubtitleCompatibilityCheck(this).run(); return; }
        if ("subtitle-compat-supported".equals(collectionSuite)) { new SubtitleCompatibilityCheck(this).run(false); return; }
        if ("subtitle-cache-real".equals(collectionSuite)) { new SubtitleCacheRealCheck(this).run(); return; }
        if ("subtitle-recovery-real".equals(collectionSuite)) { new SubtitleRecoveryRealCheck(this).run(); return; }
        if ("subtitle-recovery".equals(collectionSuite)) { new SubtitleRecoveryCheck(this).run(); return; }
        if ("subtitle-delivery".equals(collectionSuite)) { new SubtitleDeliveryCheck(this).run(); return; }
        if ("languages".equals(collectionSuite)) { new LanguagePreferencesCheck(this).run(); return; }
        if ("subtitles".equals(collectionSuite)) { new SubtitleHandlingCheck(this).run(); return; }
        if ("subtitles-real".equals(collectionSuite)) { new SubtitlePlaybackCheck(this).run(); return; }
        if ("subtitles-text".equals(collectionSuite)) { new SubtitlePlaybackCheck(this).run(true); return; }
        if (collectionSuite.startsWith("collections")) { new CollectionCheck(this).run(collectionSuite); return; }
        if (realPlaybackSuite) { new PlaybackLoadingRealCheck(this).run(); return; }
        if (playbackSuite) { new PlaybackLoadingCheck(this).run(); return; }
        Bundle result = new Bundle();
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN).setClassName(getTargetContext().getPackageName(), MainActivity.class.getName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity = startActivitySync(intent);
            SystemClock.sleep(500);
            onUi(() -> {
                try {
                    java.lang.reflect.Field page = MainActivity.class.getDeclaredField("page");
                    page.setAccessible(true); page.setInt(activity, page.getInt(activity) + 1);
                } catch (Exception error) { throw new RuntimeException(error); }
                style = new LibraryUi(activity, handler);
                root = style.column(); root.setBackgroundColor(LibraryUi.BACKGROUND);
                ScrollView scroll = new ScrollView(activity); target = style.column();
                target.setPadding(style.dp(20), style.dp(20), style.dp(20), style.dp(20));
                scroll.addView(target); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
                oldContent = style.column(); oldContent.addView(style.label("原有内容", 20, LibraryUi.TEXT, false));
                root.addView(oldContent);
                notice = new LoadingUi.RefreshNotice(root, oldContent, style, handler, active::get, retries::incrementAndGet);
                activity.setContentView(root);
                pending = new LoadingUi.Pending(target, style, handler, LoadingUi.Kind.HOME, null, active::get);
                pending.finish();
            });
            stage("fast request created");
            SystemClock.sleep(250);
            onUi(() -> check(target.getChildCount() == 0, "Fast request flashed skeleton"));
            stage("fast request passed");
            for (LoadingUi.Kind kind : new LoadingUi.Kind[] {LoadingUi.Kind.HOME, LoadingUi.Kind.GRID, LoadingUi.Kind.DETAIL, LoadingUi.Kind.EPISODES}) {
                onUi(() -> pending = new LoadingUi.Pending(target, style, handler, kind, "示例片名", active::get));
                stage("skeleton " + kind);
                SystemClock.sleep(220);
                onUi(() -> {
                    check(pending.wasShown(), "Skeleton did not appear");
                    check(target.findViewById(R.id.loading_placeholder).getVisibility() == View.VISIBLE, "Skeleton is hidden");
                    check(target.getHeight() > 0, "Skeleton has no measured region");
                    pending.finish();
                });
            }
            onUi(() -> {
                pending = new LoadingUi.Pending(target, style, handler, LoadingUi.Kind.GRID, null, active::get);
                notice.start();
            });
            stage("waiting for slow state");
            SystemClock.sleep(6250);
            onUi(() -> {
                check(hasText(target, "加载较慢"), "Missing slow request state");
                check(hasText(root, "刷新较慢"), "Missing slow refresh feedback");
                check(hasText(oldContent, "原有内容"), "Refresh removed existing data");
                pending.finish();
                check(target.getChildCount() == 0, "Completed request left loading views");
                notice.failed("测试错误");
                check(hasText(root, "刷新失败，保留原有内容"), "Missing refresh failure feedback");
                check(hasText(oldContent, "原有内容"), "Refresh failure removed existing data");
                button(root).performClick(); check(retries.get() == 1, "Refresh retry was not clickable"); notice.clear();
                LoadingUi.failure(target, style, "测试错误", retries::incrementAndGet, true);
                button(target).performClick(); check(retries.get() == 2, "Pagination retry was not clickable");
                target.removeAllViews();
                pending = new LoadingUi.Pending(target, style, handler, LoadingUi.Kind.FOOTER, null, active::get);
            });
            SystemClock.sleep(220);
            onUi(() -> {
                check(hasText(target, "正在加载更多"), "Missing pagination footer"); pending.finish();
                pending = new LoadingUi.Pending(target, style, handler, LoadingUi.Kind.EPISODES, null, active::get);
                active.set(false);
            });
            SystemClock.sleep(220);
            onUi(() -> {
                check(!pending.wasShown(), "Stale request displayed skeleton"); pending.finish(); active.set(true);
                pending = new LoadingUi.Pending(target, style, handler, LoadingUi.Kind.HOME, null, active::get);
                target.removeAllViews();
            });
            SystemClock.sleep(220);
            onUi(() -> check(target.getChildCount() == 0, "Detached region received stale loading views"));
            result.putString("result", "ALL_PASS: fast, four skeletons, slow, refresh preservation/error/retry, footer, stale, detach");

        } catch (Throwable error) {
            result.putString("error", error.toString());
        } finally {
            if (activity != null) onUi(() -> { if (pending != null) pending.finish(); if (notice != null) notice.clear(); if (style != null) style.close(); });
        }
        finish(result.containsKey("error") ? Activity.RESULT_CANCELED : Activity.RESULT_OK, result);
    }
}
