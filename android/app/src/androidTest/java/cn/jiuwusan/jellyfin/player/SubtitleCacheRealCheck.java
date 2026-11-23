package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleParser;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import org.json.JSONObject;

/** Real server/video: counts subtitle HTTP loads and compares displayed text against absolute cue times. */
final class SubtitleCacheRealCheck {
    private final Instrumentation test;
    private Activity activity;
    private JellyfinApi api;
    private OkHttpClient original;
    SubtitleCacheRealCheck(Instrumentation test) { this.test = test; }
    private void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private Object field(Object o, String name) {
        try { java.lang.reflect.Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private void set(Object o, String name, Object value) throws Exception { java.lang.reflect.Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); f.set(o, value); }
    private void call(Object o, String name, Class<?>[] types, Object... args) {
        try { java.lang.reflect.Method m = o.getClass().getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(o, args); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private void ui(Runnable work) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        test.runOnMainSync(() -> { try { work.run(); } catch (Throwable e) { error.set(e); } });
        if (error.get() != null) throw new AssertionError(error.get());
    }
    private void stage(String value) { Bundle b = new Bundle(); b.putString("stage", value); test.sendStatus(0, b); }
    private ExoPlayer frame() {
        AtomicReference<ExoPlayer> ready = new AtomicReference<>();
        long until = SystemClock.uptimeMillis() + 90_000;
        while (ready.get() == null && SystemClock.uptimeMillis() < until) {
            ui(() -> {
                ExoPlayer p = (ExoPlayer) field(activity, "player"); Object page = field(activity, "playback");
                if (p != null && page != null && p.getPlaybackState() == Player.STATE_READY && field(page, "change") == null
                        && (boolean) field(activity, "playbackStarted")) ready.set(p);
            }); SystemClock.sleep(50);
        }
        check(ready.get() != null, "Real playback did not reach first frame"); return ready.get();
    }
    private List<String> expected(List<CuesWithTiming> parsed, long positionUs) {
        List<String> texts = new ArrayList<>();
        for (CuesWithTiming part : parsed) if (part.startTimeUs <= positionUs && part.startTimeUs + part.durationUs > positionUs)
            for (Cue cue : part.cues) if (cue.text != null) texts.add(cue.text.toString());
        return texts;
    }
    void run() {
        Bundle result = new Bundle();
        try {
            activity = test.startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(test.getTargetContext().getPackageName(), MainActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            String sealed = activity.getSharedPreferences("connection", 0).getString("sealed", null);
            for (int i = 0; i < 400 && api == null; i++) { ui(() -> api = (JellyfinApi) field(activity, "api")); SystemClock.sleep(100); }
            check(api != null, "Saved login failed"); original = api.client(); api.subtitleCache.clear();
            AtomicInteger requests = new AtomicInteger();
            java.util.Map<String, AtomicInteger> byUri = new java.util.concurrent.ConcurrentHashMap<>();
            set(api, "http", original.newBuilder().addInterceptor(chain -> {
                if (chain.request().url().encodedPath().toLowerCase(java.util.Locale.ROOT).contains("/subtitles/")) {
                    requests.incrementAndGet(); byUri.computeIfAbsent(chain.request().url().toString(), key -> new AtomicInteger()).incrementAndGet();
                }
                return chain.proceed(chain.request());
            }).build());
            JellyfinApi.Item episode = null;
            for (JellyfinApi.Item show : api.search("魅影神捕")) if ("Series".equals(show.type)) {
                List<JellyfinApi.Item> episodes = api.episodes(show, null); if (!episodes.isEmpty()) episode = episodes.get(Math.min(1, episodes.size() - 1)); break;
            }
            check(episode != null, "Missing real text-subtitle episode"); JellyfinApi.Item item = episode;
            ui(() -> call(activity, "playbackPage", new Class<?>[]{JellyfinApi.Item.class, Long.class, Runnable.class}, item, 180_000L, (Runnable) () -> call(activity, "homePage", new Class<?>[]{})));
            ExoPlayer initial = frame(); final JellyfinApi.MediaTrack[] text = {null};
            ui(() -> {
                initial.pause(); JellyfinApi.Stream stream = (JellyfinApi.Stream) field(activity, "stream");
                for (JellyfinApi.MediaTrack track : stream.subtitles) if ("External".equals(track.deliveryMethod) && track.available() && "srt".equals(track.format)) { text[0] = track; break; }
                check(text[0] != null, "No real SRT track");
                call(field(activity, "playback"), "selectSubtitle", new Class<?>[]{int.class, PlaybackTracks.LocalTrack.class}, text[0].index, null);
            });
            ExoPlayer active = frame();
            AtomicInteger selectedRequests = byUri.get(text[0].subtitleUrl.toString());
            stage("downloads total=" + requests.get() + " selected=" + (selectedRequests == null ? 0 : selectedRequests.get()));
            check(selectedRequests != null && selectedRequests.get() == 1, "Expected one initial selected subtitle download");
            byte[] content = api.subtitleCache.get(text[0].subtitleUrl.toString(), SystemClock.elapsedRealtime());
            check(content != null, "Successful real subtitle was not cached");
            List<CuesWithTiming> parsed = new ArrayList<>();
            new DefaultSubtitleParserFactory().create(new Format.Builder().setSampleMimeType(PlaybackTracks.subtitleMime(text[0].format)).build())
                    .parse(content, 0, content.length, SubtitleParser.OutputOptions.allCues(), parsed::add);
            List<Long> targets = new ArrayList<>();
            for (long threshold : new long[]{180_000_000L, 600_000_000L, 120_000_000L}) {
                CuesWithTiming part = parsed.stream().filter(c -> !c.cues.isEmpty() && c.startTimeUs >= threshold && c.durationUs > 2_000_000).findFirst().orElseThrow();
                targets.add((part.startTimeUs + part.durationUs / 2) / 1000);
            }
            for (long target : targets) {
                ui(() -> active.seekTo(target)); final boolean[] matched = {false}; long until = SystemClock.uptimeMillis() + 30_000;
                while (!matched[0] && SystemClock.uptimeMillis() < until) {
                    ui(() -> {
                        List<String> texts = expected(parsed, active.getCurrentPosition() * 1000);
                        matched[0] = active.getPlaybackState() == Player.STATE_READY && !active.getPlayWhenReady()
                                && Math.abs(active.getCurrentPosition() - target) < 1000 && !active.getCurrentCues().cues.isEmpty()
                                && active.getCurrentCues().cues.stream().allMatch(c -> c.text != null && texts.contains(c.text.toString()));
                    }); SystemClock.sleep(100);
                }
                check(matched[0], "Displayed subtitle did not match absolute timeline at " + target); stage("seek/time match PASS at " + target + "ms");
            }
            ui(() -> call(field(activity, "playback"), "selectSubtitle", new Class<?>[]{int.class, PlaybackTracks.LocalTrack.class}, PlaybackTracks.OFF, null)); frame();
            ui(() -> call(field(activity, "playback"), "selectSubtitle", new Class<?>[]{int.class, PlaybackTracks.LocalTrack.class}, text[0].index, null)); ExoPlayer cached = frame();
            check(selectedRequests.get() == 1, "Turning subtitle back on repeated its download"); stage("real cache reuse PASS: selected track off/on uses one HTTP download");
            AtomicInteger cueUpdates = new AtomicInteger(), mismatch = new AtomicInteger();
            ui(() -> {
                cached.addListener(new Player.Listener() { @Override public void onCues(CueGroup group) {
                    if (group.cues.isEmpty()) return;
                    long time = cached.getCurrentPosition() * 1000; List<String> texts = expected(parsed, time);
                    texts.addAll(expected(parsed, time + 200_000)); texts.addAll(expected(parsed, Math.max(0, time - 200_000)));
                    cueUpdates.incrementAndGet();
                    if (group.cues.stream().anyMatch(c -> c.text != null && !texts.contains(c.text.toString()))) mismatch.incrementAndGet();
                } }); cached.play();
            });
            long end = SystemClock.uptimeMillis() + 120_000, next = SystemClock.uptimeMillis() + 30_000;
            while (SystemClock.uptimeMillis() < end) {
                SystemClock.sleep(250);
                ui(() -> check(cached.getPlayerError() == null && field(activity, "player") == cached, "Long playback failed/rebuilt"));
                if (SystemClock.uptimeMillis() >= next) { stage("soak progress cues=" + cueUpdates.get() + " mismatches=" + mismatch.get()); next += 30_000; }
            }
            check(cueUpdates.get() > 3 && mismatch.get() == 0 && selectedRequests.get() == 1, "Long playback cue drift or repeated download");
            ui(() -> call(activity, "onBackPressed", new Class<?>[]{}));
            check(sealed.equals(activity.getSharedPreferences("connection", 0).getString("sealed", null)), "Saved connection changed");
            result.putString("result", "ALL_PASS: real subtitle one HTTP download / off-on cache / forward-backward absolute cue match / 120s playback / unchanged connection"); test.finish(Activity.RESULT_OK, result);
        } catch (Throwable e) { result.putString("failure", android.util.Log.getStackTraceString(e)); test.finish(Activity.RESULT_CANCELED, result); }
        finally { if (api != null && original != null) try { set(api, "http", original); } catch (Exception ignored) { } }
    }
}
