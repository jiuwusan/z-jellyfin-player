package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import java.lang.reflect.*;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Controlled ended events, loopback session reports; never plays or updates a real library item. */
final class PlaybackEndCheck {
    private final Instrumentation test;
    private Activity activity;
    private Object page;
    private ExoPlayer player;
    private int state = Player.STATE_ENDED;
    private boolean playWhenReady = true;
    private final AtomicInteger returns = new AtomicInteger(), releases = new AtomicInteger();
    PlaybackEndCheck(Instrumentation test) { this.test = test; }
    private void check(boolean value, String why) { if (!value) throw new AssertionError(why); }
    private Field field(Object value, String name) throws Exception {
        Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }
    private void set(Object value, String name, Object content) throws Exception { field(value, name).set(value, content); }
    private Object get(Object value, String name) throws Exception { return field(value, name).get(value); }
    private Object call(Object value, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = value.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(value, args);
    }
    private void ui(Work work) {
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
        test.runOnMainSync(() -> { try { work.run(); } catch (Throwable e) { error.set(e); } });
        if (error.get() != null) throw new AssertionError(error.get());
    }
    interface Work { void run() throws Exception; }
    private void waitNeighbors() {
        java.util.concurrent.atomic.AtomicBoolean loading = new java.util.concurrent.atomic.AtomicBoolean(true);
        for (int i = 0; i < 100 && loading.get(); i++) {
            ui(() -> loading.set((Boolean) get(page, "neighborsLoading")));
            if (loading.get()) SystemClock.sleep(50);
        }
        check(!loading.get(), "Neighbor query did not finish");
        test.waitForIdleSync();
    }
    private void prepare(String type, boolean resolved) throws Exception {
        state = Player.STATE_ENDED; playWhenReady = true;
        JellyfinApi.Item item = new JellyfinApi.Item(new JSONObject().put("Id", "end-fixture")
                .put("Name", "结束验证").put("Type", type).put("SeriesId", "Episode".equals(type) ? "fixture-series" : ""));
        Constructor<?> constructor = Class.forName(MainActivity.class.getName() + "$PlaybackPage")
                .getDeclaredConstructor(MainActivity.class, JellyfinApi.Item.class, PlaybackPolicy.class, Runnable.class);
        constructor.setAccessible(true);
        Runnable close = () -> {
            try {
                call(activity, "stopPlayback", new Class<?>[]{});
                activity.setContentView(new View(activity));
                returns.incrementAndGet();
            } catch (Exception error) { throw new AssertionError(error); }
        };
        page = constructor.newInstance(activity, item, PlaybackPolicy.fromSettings(1920, 4_000_000, 1080, 2400), close);
        set(page, "neighborsResolved", resolved);
        player = (ExoPlayer) Proxy.newProxyInstance(ExoPlayer.class.getClassLoader(), new Class<?>[]{ExoPlayer.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getPlaybackState": return state;
                        case "getPlayWhenReady": return playWhenReady;
                        case "getCurrentTracks": return androidx.media3.common.Tracks.EMPTY;
                        case "getTrackSelectionParameters": return androidx.media3.common.TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT;
                        case "getCurrentPosition": case "getDuration": return 10_000L;
                        case "release": releases.incrementAndGet(); return null;
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    if (method.getReturnType() == float.class) return 0f;
                    return null;
                });
        set(activity, "playback", page); set(activity, "player", player); set(activity, "playbackStarted", true);
        set(activity, "stream", new JellyfinApi.Stream(item.id, "fixture-source", "fixture-session",
                okhttp3.HttpUrl.get("http://fixture.invalid/video"), false, false, -1, -1,
                Collections.emptyList(), Collections.emptyList(), -1));
    }
    private void ended(long start, boolean sought) throws Exception {
        call(page, "onPlaybackEnded", new Class<?>[]{ExoPlayer.class, long.class, boolean.class}, player, start, sought);
    }
    private void discard() throws Exception { call(activity, "stopPlayback", new Class<?>[]{}); }
    void run() {
        Bundle result = new Bundle();
        try (SubtitleHandlingCheck.Fixture fixture = new SubtitleHandlingCheck.Fixture()) {
            activity = test.startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName(test.getTargetContext().getPackageName(), MainActivity.class.getName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            for (int i = 0; i < 200 && get(activity, "api") == null; i++) SystemClock.sleep(100);
            check(get(activity, "api") != null, "Device did not connect");
            ConnectionSettings settings = new ConnectionSettings("http://fixture.invalid", "127.0.0.1", fixture.server.getLocalPort(),
                    "", "", "fixture", "fixture", 1920, 4_000_000);
            JellyfinApi api = new JellyfinApi(settings, "end-fixture"); api.login();
            ui(() -> {
                Object controller = get(activity, "subscriptionController");
                if (controller != null) call(controller, "close", new Class<?>[]{});
                set(activity, "subscriptionController", null);
                discard(); set(activity, "api", api); set(activity, "settings", settings);
                prepare("Movie", true);
                call(activity, "enterFullscreenPlayback", new Class<?>[]{});
                ended(0, false); ended(0, false);
            });
            test.waitForIdleSync();
            ui(() -> {
                check(returns.get() == 1 && releases.get() == 1 && get(activity, "player") == null,
                        "Movie end did not return exactly once and release player");
                check(activity.getRequestedOrientation() == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                        "Movie end did not restore portrait");
                prepare("Episode", false); ended(0, false);
            });
            test.waitForIdleSync();
            ui(() -> {
                check(returns.get() == 1, "Unknown neighbors incorrectly closed playback");
                fixture.adjacentItems = new org.json.JSONArray().put(new JSONObject()
                        .put("Id", "end-fixture").put("Name", "末集").put("Type", "Episode").put("SeriesId", "fixture-series"));
                call(page, "loadNeighbors", new Class<?>[]{});
            });
            waitNeighbors();
            ui(() -> {
                check(returns.get() == 2, "Delayed last episode result did not return: resolved=" + get(page, "neighborsResolved")
                        + " pending=" + get(page, "autoAdvancePending") + " foreground=" + get(page, "foreground")
                        + " active=" + (get(activity, "playback") == page) + " returns=" + returns.get());
                for (String reason : new String[]{"seek", "resume-end", "paused", "background", "error", "buffering"}) {
                    prepare("Movie", true);
                    if ("seek".equals(reason)) set(get(page, "screen"), "soughtToEnd", true);
                    if ("paused".equals(reason)) playWhenReady = false;
                    if ("background".equals(reason)) set(page, "foreground", false);
                    if ("error".equals(reason)) set(page, "preparationFailed", true);
                    if ("buffering".equals(reason)) state = Player.STATE_BUFFERING;
                    ended("resume-end".equals(reason) ? 10_000 : 0, false);
                    check(!((Boolean) get(page, "autoAdvancePending")), "Unsafe end became eligible: " + reason);
                    discard();
                }
                prepare("Episode", false); ended(0, false);
                // Failure/missing-current remains unresolved; a null next alone must never imply the finale.
                call(page, "advanceAfterEnd", new Class<?>[]{});
                check(returns.get() == 2, "Unresolved episode closed playback");
                discard();
                prepare("Episode", true);
                JellyfinApi.Item next = new JellyfinApi.Item(new JSONObject().put("Id", "next-fixture")
                        .put("Name", "下一集").put("Type", "Episode").put("SeriesId", "fixture-series"));
                set(page, "next", next); ended(0, false);
                check(get(page, "change") != null && get(get(page, "change"), "target") == next,
                        "Natural episode end did not enter the next episode switch");
                discard();
                prepare("Movie", true); ended(0, false);
                // User pauses before the deferred return: the queued completion must recheck state.
                playWhenReady = false;
            });
            test.waitForIdleSync();
            check(returns.get() == 2, "An unsafe or stale deferred end closed another player");
            ui(() -> {
                discard(); prepare("Episode", false);
                fixture.adjacentItems = new org.json.JSONArray();
                call(page, "loadNeighbors", new Class<?>[]{}); ended(0, false);
            });
            waitNeighbors();
            ui(() -> {
                check(returns.get() == 2 && !((Boolean) get(page, "neighborsResolved")),
                        "A response missing the current episode was mistaken for the finale");
                discard();
                fixture.adjacentItems = null;
                prepare("Episode", false); call(page, "loadNeighbors", new Class<?>[]{}); ended(0, false);
            });
            waitNeighbors();
            ui(() -> {
                check(returns.get() == 2 && !((Boolean) get(page, "neighborsResolved")), "Failed neighbor query closed playback");
                discard();
            });
            for (int i = 0; i < 100 && fixture.reports.size() < 4; i++) SystemClock.sleep(50);
            check(fixture.reports.stream().anyMatch(r -> "end-fixture".equals(r.optString("ItemId"))
                    && r.optLong("PositionTicks") == 100_000_000L), "Final position was not reported");
            result.putString("result", "ALL_PASS: movie / delayed finale / next switch / missing-current / query failure / duplicate / seek / resume-end / pause / background / failure / buffering / canceled return / portrait / release / final report");
            test.finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("failure", android.util.Log.getStackTraceString(error)); test.finish(Activity.RESULT_CANCELED, result);
        } finally { if (activity != null) ui(() -> activity.finish()); }
    }
}
