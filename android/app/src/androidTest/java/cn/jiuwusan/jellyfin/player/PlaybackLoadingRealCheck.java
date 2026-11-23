package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in real-media regression. Uses the device's saved connection; never writes settings. */
final class PlaybackLoadingRealCheck {
    private final Instrumentation test;
    private Activity activity;
    private String decoder = "not captured";
    private void waitOrientation(int requested, int configuration) {
        java.util.concurrent.atomic.AtomicBoolean matches = new java.util.concurrent.atomic.AtomicBoolean();
        for (int i = 0; i < 60 && !matches.get(); i++) {
            ui(() -> matches.set(activity.getRequestedOrientation() == requested
                    && activity.getResources().getConfiguration().orientation == configuration));
            SystemClock.sleep(100);
        }
        check(matches.get(), "Screen did not enter expected orientation: " + configuration);
    }
    PlaybackLoadingRealCheck(Instrumentation test) { this.test = test; }
    private void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
    private Object field(Object target, String name) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private void invoke(Object target, String name, Class<?>[] types, Object... values) {
        try { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(target, values); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private void ui(Runnable work) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        test.runOnMainSync(() -> { try { work.run(); } catch (Throwable e) { error.set(e); } });
        if (error.get() != null) throw new AssertionError(error.get());
    }
    private FullscreenPlaybackView screen() { return (FullscreenPlaybackView) field(field(activity, "playback"), "screen"); }
    private ExoPlayer waitForFrame() {
        long until = SystemClock.uptimeMillis() + 90_000;
        AtomicReference<ExoPlayer> ready = new AtomicReference<>();
        AtomicReference<ExoPlayer> listened = new AtomicReference<>();
        while (SystemClock.uptimeMillis() < until && ready.get() == null) {
            ui(() -> {
                ExoPlayer active = (ExoPlayer) field(activity, "player");
                if (active != null && active != listened.get()) {
                    listened.set(active);
                    active.addAnalyticsListener(new AnalyticsListener() {
                        @Override public void onVideoDecoderInitialized(EventTime t, String name, long at, long duration) { decoder = name; }
                    });
                }
                if (active != null && active.isPlaying() && screen().findViewById(R.id.playback_preparation_cover).getVisibility() == View.GONE)
                    ready.set(active);
            });
            SystemClock.sleep(80);
        }
        check(ready.get() != null, "Real media did not reach first frame");
        return ready.get();
    }
    void run() {
        Bundle result = new Bundle();
        int previousAutoRotation = android.provider.Settings.System.getInt(test.getTargetContext().getContentResolver(),
                android.provider.Settings.System.ACCELEROMETER_ROTATION, 1);
        int previousRotation = android.provider.Settings.System.getInt(test.getTargetContext().getContentResolver(),
                android.provider.Settings.System.USER_ROTATION, 0);
        try {
            activity = test.startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName(test.getTargetContext().getPackageName(), MainActivity.class.getName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            test.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_90);
            waitOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    android.content.res.Configuration.ORIENTATION_PORTRAIT);
            String saved = activity.getSharedPreferences("connection", 0).getString("sealed", null);
            check(saved != null, "Device has no saved connection");
            AtomicReference<JellyfinApi> connection = new AtomicReference<>();
            for (int i = 0; i < 300 && connection.get() == null; i++) {
                ui(() -> {
                    JellyfinApi candidate = (JellyfinApi) field(activity, "api");
                    if (candidate != null && field(candidate, "userId") != null) connection.set(candidate);
                });
                SystemClock.sleep(100);
            }
            check(connection.get() != null, "Saved connection failed to log in");
            JellyfinApi.Item selected = null;
            for (JellyfinApi.Item item : connection.get().resumeItems())
                if ("Episode".equals(item.type) && "魅影神捕".equals(item.seriesName)) { selected = item; break; }
            check(selected != null, "No resume episode for regression title");
            JellyfinApi.Item item = selected;
            ui(() -> invoke(activity, "playbackPage", new Class<?>[]{JellyfinApi.Item.class, Long.class, Runnable.class},
                    item, Long.valueOf(item.resumeMs), (Runnable) () -> invoke(activity, "homePage", new Class<?>[]{})));
            ExoPlayer original = waitForFrame();
            waitOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE);
            ui(() -> check(!activity.getWindow().getDecorView().getRootWindowInsets().isVisible(
                    android.view.WindowInsets.Type.systemBars()), "Playback system bars still visible"));
            final long[] retry = new long[1];
            final Object[] policy = new Object[1];
            ui(() -> {
                Format format = original.getVideoFormat();
                check(format != null && "video/avc".equals(format.sampleMimeType), "Unexpected video codec");
                check(format.width <= 1920 && format.height <= 1080, "Video exceeded resolution limit");
                result.putString("video", format.sampleMimeType + " " + format.width + "x" + format.height + " fps=" + format.frameRate);
                check(Math.abs(original.getCurrentPosition() - item.resumeMs) < 8000, "Initial resume position changed");
                original.seekTo(original.getCurrentPosition() + 5000);
                Object current = field(activity, "playback");
                policy[0] = field(current, "policy");
                retry[0] = original.getCurrentPosition();
                invoke(current, "failPreparation", new Class<?>[]{ExoPlayer.class, JellyfinApi.Stream.class}, original, field(activity, "stream"));
                check(!screen().findViewById(R.id.playback_preparation_spinner).isShown(), "Failure kept spinner");
                check(!screen().findViewById(R.id.playback_toggle).isEnabled(), "Failure kept controls enabled");
                screen().findViewById(R.id.playback_preparation_retry).performClick();
            });
            ExoPlayer retried = waitForFrame();
            ui(() -> {
                check(retried != original, "Retry reused the failed player");
                check(Math.abs(retried.getCurrentPosition() - retry[0]) < 8000, "Retry lost playback position");
                check(field(field(activity, "playback"), "policy") == policy[0], "Retry changed quality policy");
                screen().findViewById(R.id.playback_toggle).performClick();
                check(!retried.getPlayWhenReady(), "Pause failed after retry");
                screen().findViewById(R.id.playback_toggle).performClick();
                check(retried.getPlayWhenReady(), "Play failed after retry");
                activity.onBackPressed();
                check(field(activity, "playback") == null && field(activity, "player") == null, "Back left playback active");
            });
            SystemClock.sleep(1500);
            waitOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    android.content.res.Configuration.ORIENTATION_PORTRAIT);
            check(saved.equals(activity.getSharedPreferences("connection", 0).getString("sealed", null)), "Saved settings changed");
            result.putString("decoder", decoder);
            result.putString("result", "ALL_PASS: portrait browsing despite landscape rotation / landscape fullscreen playback / portrait after back / proxy-login / first frame / retry-resume / unchanged settings");
            test.finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            result.putString("failure", android.util.Log.getStackTraceString(e));
            test.finish(Activity.RESULT_CANCELED, result);
        } finally {
            test.getUiAutomation().setRotation(previousRotation);
            if (previousAutoRotation == 1) test.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_UNFREEZE);
        }
    }
}
