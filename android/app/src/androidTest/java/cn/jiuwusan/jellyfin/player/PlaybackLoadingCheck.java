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
import android.widget.TextView;
import androidx.media3.exoplayer.ExoPlayer;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the real fullscreen view, with no changes to saved settings or remote services. */
final class PlaybackLoadingCheck {
    private final Instrumentation test;
    private FullscreenPlaybackView screen;
    private Activity activity;
    private ExoPlayer player;
    private final AtomicInteger retries = new AtomicInteger();
    PlaybackLoadingCheck(Instrumentation test) { this.test = test; }
    private void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private void ui(Runnable work) {
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
        test.runOnMainSync(() -> { try { work.run(); } catch (Throwable e) { error.set(e); } });
        if (error.get() != null) throw new AssertionError(error.get());
    }
    private void call(String method, Class<?>[] types, Object... args) {
        try {
            java.lang.reflect.Method found = FullscreenPlaybackView.class.getDeclaredMethod(method, types);
            found.setAccessible(true); found.invoke(screen, args);
        } catch (Exception e) { throw new AssertionError("Missing or failed preparation transition: " + method, e); }
    }
    private View named(String id) {
        int value = activity.getResources().getIdentifier(id, "id", activity.getPackageName());
        View view = value == 0 ? null : screen.findViewById(value);
        check(view != null, "Missing " + id); return view;
    }
    private boolean text(View view, String value) {
        if (!view.isShown()) return false;
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(value)) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (text(((ViewGroup) view).getChildAt(i), value)) return true;
        return false;
    }
    private void begin() {
        call("beginPreparation", new Class<?>[]{View.class, long.class, long.class, Runnable.class},
                new View(activity), 1_961_000L, 2_780_000L, (Runnable) () -> { retries.incrementAndGet(); begin(); });
    }

    private void checkChoiceRows(int count) {
        try {
            java.lang.reflect.Field field = FullscreenPlaybackView.class.getDeclaredField("trackList");
            field.setAccessible(true);
            ViewGroup list = (ViewGroup) field.get(screen);
            for (int i = 0; i < count; i++) {
                View row = list.getChildAt(i);
                android.graphics.Rect visible = new android.graphics.Rect();
                check(row != null && row.getHeight() > 0 && row.getGlobalVisibleRect(visible)
                        && visible.height() == row.getHeight(), "Bubble clipped complete choice row " + i);
            }
            if (list.getChildCount() == 1) {
                View scroll = (View) list.getParent();
                check(scroll.getHeight() == list.getChildAt(0).getHeight(), "Single choice left empty rows");
            }
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void capture(String name) throws Exception {
        android.graphics.Bitmap image = test.getUiAutomation().takeScreenshot();
        if (image == null) return;
        try (java.io.FileOutputStream file = new java.io.FileOutputStream(
                new java.io.File(activity.getExternalFilesDir(null), name + ".png"))) {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, file);
        } finally { image.recycle(); }
    }
    void run() {
        Bundle result = new Bundle();
        try {
            Intent launch = new Intent(Intent.ACTION_MAIN).setClassName(test.getTargetContext().getPackageName(), MainActivity.class.getName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity = test.startActivitySync(launch);
            SystemClock.sleep(600);
            ui(() -> {
                try {
                    java.lang.reflect.Field page = MainActivity.class.getDeclaredField("page");
                    page.setAccessible(true); page.setInt(activity, page.getInt(activity) + 1);
                } catch (Exception e) { throw new RuntimeException(e); }
                screen = new FullscreenPlaybackView(activity, new LibraryUi(activity, new Handler(Looper.getMainLooper())),
                        "1080p · 4 Mbps", () -> {}, () -> {}, () -> {}, () -> {}, () -> {});
                screen.setTitle("取流加载验证", "第 2 集");
                activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                activity.setContentView(screen);
                try {
                    java.lang.reflect.Method full = MainActivity.class.getDeclaredMethod("enterFullscreenPlayback");
                    full.setAccessible(true); full.invoke(activity);
                } catch (Exception e) { throw new RuntimeException(e); }
                float density = activity.getResources().getDisplayMetrics().density;
                screen.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                        Math.round(640 * density), Math.round(360 * density), android.view.Gravity.CENTER));
                begin();
                check(!named("playback_preparation_spinner").isShown(), "Spinner flashed immediately");
            });
            SystemClock.sleep(350);
            ui(() -> {
                check(named("playback_preparation_spinner").isShown(), "Missing delayed spinner");
                check(!text(screen, "准备较慢"), "Slow hint shown too early");
                check(text(screen, "32:41 / 46:20"), "Preparation lost resume time");
                player = new ExoPlayer.Builder(activity).build();
                screen.setPlayer(player);
                screen.updateActions(true, true, true, true, true);
                check(!named("playback_toggle").isEnabled(), "Controls enabled before first frame");
            });
            capture("player-preparation-waiting");
            SystemClock.sleep(6000);
            ui(() -> {
                check(text(screen, "视频准备较慢"), "Missing slow state");
            });
            capture("player-preparation-slow");
            ui(() -> {
                call("showPreparationFailure", new Class<?>[]{String.class}, "暂时无法播放，请重试。");
                check(!named("playback_preparation_spinner").isShown(), "Failure left spinner running");
                check(text(screen, "播放失败"), "Failure not displayed");
            });
            SystemClock.sleep(150);
            capture("player-preparation-error");
            ui(() -> {
                View retry = named("playback_preparation_retry");
                android.graphics.Rect visible = new android.graphics.Rect();
                check(retry.getGlobalVisibleRect(visible) && visible.height() == retry.getHeight(), "Retry clipped on landscape screen");
                named("playback_preparation_retry").performClick();
                check(retries.get() == 1, "Retry was not dispatched once");
                named("playback_preparation_retry").performClick();
                check(retries.get() == 1, "Duplicate retry accepted");
                check(text(screen, "32:41 / 46:20"), "Retry lost resume position");
            });
            SystemClock.sleep(250);
            ui(() -> screen.showVideo());
            SystemClock.sleep(250);
            ui(() -> {
                check(!named("playback_preparation_cover").isShown(), "Cover not dismissed after first frame");
                check(!named("playback_preparation_spinner").isShown(), "Spinner not dismissed after first frame");
                check(named("playback_toggle").isEnabled(), "First frame did not unlock transport");
                screen.showChoices("音轨", new String[]{"单条音轨"}, 0, index -> {});
            });
            SystemClock.sleep(120);
            ui(() -> {
                checkChoiceRows(1);
                screen.dismissChoices();
                screen.showChoices("字幕", new String[]{"简体中文", "繁体中文", "关闭字幕"}, 0, index -> {});
            });
            SystemClock.sleep(120);
            ui(() -> {
                checkChoiceRows(2);
                screen.dismissChoices();
                begin();
                activity.setContentView(new View(activity));
            });
            SystemClock.sleep(6200);
            ui(() -> {
                check(!text(screen, "视频准备较慢"), "Detached screen received stale timer");
                player.release();
            });
            result.putString("result", "ALL_PASS: delayed spinner / slow / retry / first-frame gate / resume / bubble one-row fit and two complete rows / detach");
            test.finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            result.putString("failure", android.util.Log.getStackTraceString(e));
            test.finish(Activity.RESULT_CANCELED, result);
        }
    }
}
