package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in real PGS check using saved settings. Never changes server or connection settings. */
final class SubtitlePlaybackCheck {
    private final Instrumentation test;
    private Activity activity;
    private String decoder = "not captured";
    SubtitlePlaybackCheck(Instrumentation test) { this.test = test; }
    private void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private Object field(Object object, String name) {
        try { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private void invoke(Object object, String name, Class<?>[] types, Object... args) {
        try { Method m = object.getClass().getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(object, args); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private void ui(Runnable work) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        test.runOnMainSync(() -> { try { work.run(); } catch (Throwable e) { error.set(e); } });
        if (error.get() != null) throw new AssertionError(error.get());
    }
    private ExoPlayer frame(ExoPlayer previous) {
        long until = SystemClock.uptimeMillis() + 120_000;
        AtomicReference<ExoPlayer> ready = new AtomicReference<>(), listened = new AtomicReference<>();
        while (SystemClock.uptimeMillis() < until && ready.get() == null) {
            ui(() -> {
                ExoPlayer p = (ExoPlayer) field(activity, "player");
                Object page = field(activity, "playback");
                if (p != null && p != previous && p != listened.get()) {
                    listened.set(p);
                    p.addAnalyticsListener(new AnalyticsListener() {
                        @Override public void onVideoDecoderInitialized(EventTime t, String name, long at, long duration) { decoder = name; }
                    });
                }
                if (page != null && p != null && p != previous && p.getPlaybackState() == 3
                        && field(page, "change") == null
                        && ((View) field(page, "screen")).findViewById(R.id.playback_preparation_cover).getVisibility() == View.GONE)
                    ready.set(p);
            });
            SystemClock.sleep(100);
        }
        check(ready.get() != null, "Subtitle switch never reached a frame");
        return ready.get();
    }
    private void shot(String name) throws Exception {
        Bitmap bitmap = test.getUiAutomation().takeScreenshot();
        check(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(new File(activity.getExternalFilesDir(null), "subtitle-" + name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally { bitmap.recycle(); }
    }
    private ExoPlayer select(ExoPlayer previous, int index, int burned, long position, Bundle result, String label) {
        ui(() -> invoke(field(activity, "playback"), "selectSubtitle", new Class<?>[]{int.class, PlaybackTracks.LocalTrack.class}, index, null));
        ExoPlayer next = frame(previous);
        ui(() -> {
            JellyfinApi.Stream stream = (JellyfinApi.Stream) field(activity, "stream");
            check(stream.burnedSubtitleIndex == burned, "Wrong burned track after " + label);
            check(!next.getPlayWhenReady(), "Paused state lost after " + label);
            check(Math.abs(next.getCurrentPosition() - position) < 1500, "Position lost after " + label);
            check(stream.audioIndex == 1, "Audio selection changed");
            result.putLong(label + "PositionMs", next.getCurrentPosition());
        });
        return next;
    }
    void run() { run(false); }
    void run(boolean textOnly) {
        Bundle result = new Bundle();
        try {
            activity = test.startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName(test.getTargetContext().getPackageName(), MainActivity.class.getName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            String saved = activity.getSharedPreferences("connection", 0).getString("sealed", null);
            check(saved != null, "Missing saved connection");
            AtomicReference<JellyfinApi> api = new AtomicReference<>();
            for (int i = 0; i < 600 && api.get() == null; i++) {
                ui(() -> { JellyfinApi candidate = (JellyfinApi) field(activity, "api");
                    if (candidate != null && field(candidate, "userId") != null) api.set(candidate); });
                SystemClock.sleep(100);
            }
            check(api.get() != null, "Automatic login failed");
            if (!textOnly) {
            JellyfinApi.Item movie = null;
            for (JellyfinApi.Item item : api.get().resumeItems()) if (item.title.contains("人类清除计划2")) { movie = item; break; }
            check(movie != null, "Movie absent from continue watching");
            JellyfinApi.Item selected = movie;
            ui(() -> invoke(activity, "playbackPage", new Class<?>[]{JellyfinApi.Item.class, Long.class, Runnable.class},
                    selected, Long.valueOf(598_000), (Runnable) () -> invoke(activity, "homePage", new Class<?>[]{})));
            ExoPlayer player = frame(null);
            ui(() -> {
                JellyfinApi.Stream stream = (JellyfinApi.Stream) field(activity, "stream");
                check(stream.defaultSubtitleIndex == 4 && stream.burnedSubtitleIndex == 4, "Default Chinese PGS missing");
                Format format = ((ExoPlayer) field(activity, "player")).getVideoFormat();
                check(format != null && "video/avc".equals(format.sampleMimeType) && format.width <= 1920 && format.height <= 1080, "Unexpected output format");
                check("3808000".equals(stream.url.queryParameter("VideoBitRate")), "Video budget changed");
                result.putString("video", format.sampleMimeType + " " + format.width + "x" + format.height + " fps=" + format.frameRate);
                result.putString("defaultSubtitle", "4 / PGS / server burn-in");
            });
            for (int i = 0; i < 3; i++) { SystemClock.sleep(1000); shot("chinese-" + i); }
            final long[] position = new long[1];
            ExoPlayer paused = player;
            ui(() -> { paused.pause(); paused.seekTo(600_000); position[0] = 600_000; });
            java.util.concurrent.atomic.AtomicBoolean sought = new java.util.concurrent.atomic.AtomicBoolean();
            for (int i = 0; i < 300 && !sought.get(); i++) {
                ui(() -> sought.set(paused.getPlaybackState() == 3 && Math.abs(paused.getCurrentPosition() - position[0]) < 1000));
                SystemClock.sleep(100);
            }
            check(sought.get(), "Paused seek did not finish");
            SystemClock.sleep(500); shot("chinese-paused");
            player = select(player, 2, 2, position[0], result, "english"); shot("english-paused");
            player = select(player, PlaybackTracks.OFF, -1, position[0], result, "off"); shot("off-paused");
            player = select(player, PlaybackTracks.AUTO, 4, position[0], result, "restored"); shot("restored-paused");
            ExoPlayer restored = player;
            ui(restored::play); SystemClock.sleep(2500); shot("restored-playing");
            ui(() -> activity.onBackPressed()); SystemClock.sleep(1000);
            }
            JellyfinApi.Item episode = null;
            for (JellyfinApi.Item item : api.get().resumeItems())
                if ("Episode".equals(item.type) && "魅影神捕".equals(item.seriesName)) { episode = item; break; }
            if (episode == null) for (JellyfinApi.Item show : api.get().search("魅影神捕")) {
                if (!"Series".equals(show.type)) continue;
                java.util.List<JellyfinApi.Item> episodes = api.get().episodes(show, null);
                if (!episodes.isEmpty()) episode = episodes.get(Math.min(1, episodes.size() - 1));
                break;
            }
            check(episode != null, "No text-subtitle episode available");
            JellyfinApi.Item textEpisode = episode;
            ui(() -> invoke(activity, "playbackPage", new Class<?>[]{JellyfinApi.Item.class, Long.class, Runnable.class},
                    textEpisode, Long.valueOf(180_000), (Runnable) () -> invoke(activity, "homePage", new Class<?>[]{})));
            frame(null);
            ui(() -> {
                JellyfinApi.Stream stream = (JellyfinApi.Stream) field(activity, "stream");
                JellyfinApi.MediaTrack text = null;
                for (JellyfinApi.MediaTrack track : stream.subtitles) if (!track.burnIn) { text = track; break; }
                check(text != null && stream.burnedSubtitleIndex == -1, "Text subtitle was burned or unavailable");
                invoke(field(activity, "playback"), "selectSubtitle", new Class<?>[]{int.class, PlaybackTracks.LocalTrack.class}, text.index, null);
            });
            java.util.concurrent.atomic.AtomicBoolean cues = new java.util.concurrent.atomic.AtomicBoolean();
            for (int i = 0; i < 300 && !cues.get(); i++) {
                ui(() -> cues.set((boolean) field(field(activity, "playback"), "loggedSubtitle")));
                SystemClock.sleep(100);
            }
            check(cues.get(), "Client text subtitle produced no cues");
            ui(() -> ((ExoPlayer) field(activity, "player")).pause());
            frame(null); // A cue can arrive before the replacement video's first frame.
            ui(() -> {
                Object screen = field(field(activity, "playback"), "screen");
                androidx.media3.ui.SubtitleView captions = ((androidx.media3.ui.PlayerView) field(screen, "video")).getSubtitleView();
                androidx.media3.ui.CaptionStyleCompat style = (androidx.media3.ui.CaptionStyleCompat) field(captions, "style");
                check(style.backgroundColor == android.graphics.Color.TRANSPARENT && style.windowColor == android.graphics.Color.TRANSPARENT
                        && style.edgeType == androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE && style.edgeColor == android.graphics.Color.BLACK,
                        "Real fullscreen caption style retained background or lost outline");
                java.util.List<?> displayed = (java.util.List<?>) field(captions, "cues");
                check(!displayed.isEmpty(), "No displayed caption after first frame");
                for (Object value : displayed) {
                    androidx.media3.common.text.Cue cue = (androidx.media3.common.text.Cue) value;
                    check(!cue.windowColorSet, "Real caption has a window background");
                    if (cue.text instanceof android.text.Spanned) {
                        android.text.Spanned text = (android.text.Spanned) cue.text;
                        check(text.getSpans(0, text.length(), android.text.style.BackgroundColorSpan.class).length == 0, "Real caption has a text background");
                    }
                }
            });
            shot("client-text");
            ui(() -> invoke(field(field(activity, "playback"), "screen"), "setControlsVisible", new Class<?>[]{boolean.class}, false));
            SystemClock.sleep(300); shot("client-text-hidden");
            ui(() -> activity.onBackPressed()); SystemClock.sleep(1000);
            check(saved.equals(activity.getSharedPreferences("connection", 0).getString("sealed", null)), "Settings changed");
            result.putString("decoder", decoder);
            result.putString("result", textOnly ? "ALL_PASS: real client text cues / unchanged connection"
                    : "ALL_PASS: real default Chinese PGS / English switch / off / auto restore / preserved position, pause, audio, quality / client text cues / unchanged connection");
            test.finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            result.putString("failure", android.util.Log.getStackTraceString(e)); test.finish(Activity.RESULT_CANCELED, result);
        }
    }
}
