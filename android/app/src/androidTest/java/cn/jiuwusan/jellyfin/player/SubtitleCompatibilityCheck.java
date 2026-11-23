package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.extractor.text.ssa.SsaParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.MediaType;
import org.json.JSONArray;
import org.json.JSONObject;

/** Uses upstream Media3 MKV test files; all fixture traffic/reports stay inside the test interceptor. */
final class SubtitleCompatibilityCheck {
    private final Instrumentation test;
    private Activity activity;
    private JellyfinApi api;
    private OkHttpClient original;
    private boolean srtCompatibility;
    SubtitleCompatibilityCheck(Instrumentation test) { this.test = test; }
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
    private void parser() {
        String ass = "[Script Info]\nScriptType: v4.00+\nPlayResX: 1920\nPlayResY: 1080\n\n"
                + "[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, Bold, Italic, Alignment\n"
                + "Style: Default,sans-serif,64,&H0000FFFF,-1,-1,2\n\n"
                + "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
                + "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\\pos(960,864)}中文\\N第二行\n"
                + "Dialogue: 1,0:00:02.00,0:00:04.00,Default,,0,0,0,,{\\move(0,0,100,100)\\k20\\t(0,500,\\fs90)}特效文本\n";
        byte[] bytes = ass.getBytes(StandardCharsets.UTF_8);
        List<CuesWithTiming> output = new ArrayList<>();
        new SsaParser().parse(bytes, 0, bytes.length, SubtitleParser.OutputOptions.allCues(), output::add);
        Cue first = output.stream().flatMap(c -> c.cues.stream()).filter(c -> c.text != null && c.text.toString().contains("中文")).findFirst().orElseThrow();
        stage("ASS diagnostic text=" + first.text.toString().replace('\n', '|') + " position=" + first.position + " line=" + first.line);
        check(first.text.toString().contains("\n第二行") && Math.abs(first.position - .5f) < .01f && Math.abs(first.line - .8f) < .01f, "ASS positioning or line break lost");
        check(first.text instanceof android.text.Spanned && ((android.text.Spanned) first.text).getSpans(0, first.text.length(), android.text.style.StyleSpan.class).length > 0, "ASS bold/italic lost");
        check(output.stream().anyMatch(c -> c.cues.size() == 2), "Overlapping ASS cues lost");
        check(output.stream().anyMatch(c -> c.startTimeUs == 1_000_000), "ASS absolute time lost");
        stage("ASS parser PASS: Chinese/newline/position/bold-italic/overlap/time; move/karaoke/animation remain unsupported effects");
    }
    private JSONObject info(String codec) throws Exception {
        JSONArray tracks = new JSONArray()
                .put(new JSONObject().put("Type", "Video").put("Index", 0).put("Codec", "h264").put("Width", 1080).put("Height", 720))
                .put(new JSONObject().put("Type", "Audio").put("Index", 1).put("Codec", "aac"))
                .put(new JSONObject().put("Type", "Subtitle").put("Index", 2).put("Codec", codec).put("DeliveryMethod", "Embed").put("Language", "eng"));
        return new JSONObject().put("PlaySessionId", "compat-fixture").put("MediaSources", new JSONArray().put(new JSONObject()
                .put("Id", "compat-source").put("Bitrate", 300_000).put("Container", "mkv").put("MediaStreams", tracks)
                .put("SupportsDirectPlay", true).put("SupportsDirectStream", true).put("SupportsTranscoding", false)
                .put("DefaultSubtitleStreamIndex", 2).put("DefaultAudioStreamIndex", 1)));
    }
    private void embedded(String file, String codec) throws Exception {
        java.io.File asset = new java.io.File(activity.getExternalFilesDir(null), "subtitle-compat/" + file);
        byte[] bytes;
        try (java.io.FileInputStream in = new java.io.FileInputStream(asset); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n; while ((n = in.read(b)) >= 0) out.write(b, 0, n); bytes = out.toByteArray();
        }
        String metadata = info(codec).toString();
        java.util.concurrent.atomic.AtomicInteger downloads = new java.util.concurrent.atomic.AtomicInteger();
        boolean extracted = srtCompatibility && "subrip".equals(codec);
        String srt = file.contains("overlapping")
                ? "1\n00:00:00,100 --> 00:00:00,330\nFirst subtitle - end overlaps second\n\n"
                    + "2\n00:00:00,150 --> 00:00:00,550\nThird subtitle - fully encompasses second\n\n"
                    + "3\n00:00:00,200 --> 00:00:00,450\nSecond subtitle - beginning overlaps first\n\n"
                : "1\n00:00:00,000 --> 00:00:01,234\nThis is the first subtitle.\n\n";
        OkHttpClient client = original.newBuilder().addInterceptor(chain -> {
            String path = chain.request().url().encodedPath();
            if (path.contains("/Sessions/Playing")) return new Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(204).message("fixture report suppressed").body(ResponseBody.create(new byte[0], null)).build();
            if (path.contains("/subtitle-compat/PlaybackInfo")) {
                String offered = metadata;
                if (extracted) try {
                    okio.Buffer buffer = new okio.Buffer(); chain.request().body().writeTo(buffer);
                    JSONArray profiles = new JSONObject(buffer.readUtf8()).getJSONObject("DeviceProfile").getJSONArray("SubtitleProfiles");
                    boolean embed = false;
                    for (int i = 0; i < profiles.length(); i++) {
                        JSONObject profile = profiles.getJSONObject(i);
                        if (("srt".equals(profile.getString("Format")) || "subrip".equals(profile.getString("Format"))) && "Embed".equals(profile.getString("Method"))) embed = true;
                    }
                    // Behave like the server: offer extraction only when the client no longer asks for Embed.
                    if (!embed) {
                        JSONObject response = info(codec);
                        response.getJSONArray("MediaSources").getJSONObject(0).getJSONArray("MediaStreams").getJSONObject(2)
                                .put("DeliveryMethod", "External").put("DeliveryUrl", "/Videos/subtitle-compat/compat-source/Subtitles/2/Stream.srt");
                        offered = response.toString();
                    }
                } catch (Exception e) { throw new java.io.IOException(e); }
                return new Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("fixture").body(ResponseBody.create(offered, MediaType.get("application/json"))).build();
            }
            if (path.contains("/Videos/subtitle-compat/compat-source/Subtitles/2/Stream.srt")) {
                downloads.incrementAndGet();
                return new Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("fixture extracted SRT").body(ResponseBody.create(srt, MediaType.get("application/x-subrip"))).build();
            }
            if (path.contains("/Videos/subtitle-compat/stream")) {
                String range = chain.request().header("Range"); int offset = range == null ? 0 : Integer.parseInt(range.substring(6).split("-", -1)[0]);
                Response.Builder response = new Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(offset > 0 ? 206 : 200).message("fixture");
                if (offset > 0) response.header("Content-Range", "bytes " + offset + "-" + (bytes.length - 1) + "/" + bytes.length);
                return response.body(ResponseBody.create(java.util.Arrays.copyOfRange(bytes, offset, bytes.length), MediaType.get("video/x-matroska"))).build();
            }
            return chain.proceed(chain.request());
        }).build(); set(api, "http", client);
        JellyfinApi.Item item = new JellyfinApi.Item(new JSONObject().put("Id", "subtitle-compat").put("Name", "内嵌字幕验证").put("Type", "Movie").put("RunTimeTicks", 12_340_000));
        long initial = extracted && file.contains("overlapping") ? 250L : 0L;
        api.subtitleCache.clear();
        ui(() -> call(activity, "playbackPage", new Class<?>[]{JellyfinApi.Item.class, Long.class, Runnable.class}, item, initial, (Runnable) () -> call(activity, "homePage", new Class<?>[]{})));
        AtomicReference<ExoPlayer> ready = new AtomicReference<>();
        long until = SystemClock.uptimeMillis() + 30_000;
        while (ready.get() == null && SystemClock.uptimeMillis() < until) {
            ui(() -> {
                ExoPlayer p = (ExoPlayer) field(activity, "player");
                if (p != null && p.getPlaybackState() == Player.STATE_READY) { p.pause(); ready.set(p); }
            }); SystemClock.sleep(20);
        }
        check(ready.get() != null, "Embedded fixture did not become ready: " + file);
        ExoPlayer p = ready.get();
        if (initial > 0) {
            final boolean[] resumed = {false}; long untilResume = SystemClock.uptimeMillis() + 5000;
            while (!resumed[0] && SystemClock.uptimeMillis() < untilResume) {
                ui(() -> resumed[0] = Math.abs(p.getCurrentPosition() - initial) < 60 && p.getCurrentCues().cues.stream()
                        .anyMatch(c -> c.text != null && c.text.toString().split("\n").length == 3));
                SystemClock.sleep(50);
            }
            check(resumed[0], "Initial resume inside overlap did not show all three cues");
        }
        ui(() -> {
            check(p.getPlayerError() == null, "Embedded fixture playback failed");
            JellyfinApi.Stream stream = (JellyfinApi.Stream) field(activity, "stream");
            check(!stream.hls && stream.burnedSubtitleIndex < 0, "Subtitle extraction changed the video into a transcode");
            check(extracted ? PlaybackTracks.externalSubtitleIndex(stream, PlaybackTracks.AUTO) == 2
                    : PlaybackTracks.externalSubtitleIndex(stream, PlaybackTracks.AUTO) < 0, "Subtitle delivery negotiation was not respected");
            check(!p.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) && p.getCurrentTracks().isTypeSelected(C.TRACK_TYPE_TEXT), "Embedded group mapping failed");
            if (extracted) for (androidx.media3.common.Tracks.Group group : p.getCurrentTracks().getGroups()) if (group.getType() == C.TRACK_TYPE_TEXT) {
                androidx.media3.common.Format format = group.getTrackFormat(0);
                stage("text diagnostic id=" + format.id + " selected=" + group.isTrackSelected(0) + " replacement=" + format.cueReplacementBehavior + " downloads=" + downloads.get());
            }
        });
        boolean overlap = file.contains("overlapping");
        boolean assSample = file.contains("ass_subtitles");
        for (long position : overlap ? new long[]{120, 250, 400, 500, 800, 120} : assSample ? new long[]{100, 300, 700, 100} : new long[]{500, 1050, 100}) {
            // Expected timings come from the MKV BlockGroup timestamps/durations, not current player output.
            java.util.Set<String> expected = new java.util.HashSet<>();
            if (!overlap) { if (!assSample || position < 500) expected.add("This is the first subtitle."); }
            else {
                if (position >= 100 && position < 330) expected.add("First subtitle - end overlaps second");
                if (position >= 150 && position < 550) expected.add("Third subtitle - fully encompasses second");
                if (position >= 200 && position < 450) expected.add("Second subtitle - beginning overlaps first");
            }
            ui(() -> p.seekTo(position)); final boolean[] matched = {false}; long untilMatch = SystemClock.uptimeMillis() + 5000;
            while (!matched[0] && SystemClock.uptimeMillis() < untilMatch) {
                ui(() -> {
                    java.util.Set<String> actual = new java.util.HashSet<>();
                    for (Cue cue : p.getCurrentCues().cues) if (cue.text != null) java.util.Collections.addAll(actual, cue.text.toString().trim().split("\n"));
                    matched[0] = p.getPlaybackState() == Player.STATE_READY && Math.abs(p.getCurrentPosition() - position) < 60 && actual.equals(expected);
                    check(p.getPlayerError() == null && !p.getPlayWhenReady(), "Seek failed or unpaused");
                }); SystemClock.sleep(50);
            }
            if (!matched[0]) ui(() -> stage("Embed diagnostic at " + p.getCurrentPosition() + " cues=" + p.getCurrentCues().cues.stream().map(c -> String.valueOf(c.text)).collect(java.util.stream.Collectors.joining("|"))));
            check(matched[0], "Embedded subtitle text/time mismatch: " + file + " at " + position);
            if (extracted && overlap && position == 250) {
                ui(() -> call(field(field(activity, "playback"), "screen"), "setControlsVisible", new Class<?>[]{boolean.class}, false));
                SystemClock.sleep(200);
                BitmapCapture.save(test, activity, "srt-overlap-fixed.png");
            }
        }
        if (extracted && overlap) {
            ui(() -> p.seekTo(250)); SystemClock.sleep(100);
            String videoUrl = ((JellyfinApi.Stream) field(activity, "stream")).url.toString();
            ui(() -> call(field(activity, "playback"), "selectSubtitle", new Class<?>[]{int.class, PlaybackTracks.LocalTrack.class}, PlaybackTracks.OFF, null));
            ExoPlayer off = switchedFrame(p);
            ui(() -> check(off.getCurrentCues().cues.isEmpty() && !off.getPlayWhenReady() && Math.abs(off.getCurrentPosition() - 250) < 60, "Off changed pause/position or kept subtitles"));
            ui(() -> call(field(activity, "playback"), "selectSubtitle", new Class<?>[]{int.class, PlaybackTracks.LocalTrack.class}, 2, null));
            ExoPlayer restored = switchedFrame(off);
            ui(() -> check(!restored.getPlayWhenReady() && Math.abs(restored.getCurrentPosition() - 250) < 60
                    && restored.getCurrentCues().cues.stream().anyMatch(c -> c.text != null && c.text.toString().split("\n").length == 3)
                    && videoUrl.equals(((JellyfinApi.Stream) field(activity, "stream")).url.toString()), "Cached subtitle restore lost overlap/pause/position/video"));
            stage("overlap OFF/ON cache PASS");
        }
        if (extracted) check(downloads.get() == 1, "Seeking downloaded the SRT again");
        ui(() -> call(activity, "onBackPressed", new Class<?>[]{}));
        stage(file + " PASS: direct/" + (extracted ? "extracted snapshots" : "embedded") + "/selected/cues/forward-backward seek/paused");
        set(api, "http", original);
    }
    private ExoPlayer switchedFrame(ExoPlayer previous) {
        AtomicReference<ExoPlayer> ready = new AtomicReference<>(); long until = SystemClock.uptimeMillis() + 10_000;
        while (ready.get() == null && SystemClock.uptimeMillis() < until) {
            ui(() -> {
                ExoPlayer active = (ExoPlayer) field(activity, "player");
                if (active != null && active != previous && active.getPlaybackState() == Player.STATE_READY && field(field(activity, "playback"), "change") == null) ready.set(active);
            }); SystemClock.sleep(50);
        }
        check(ready.get() != null, "Subtitle switch did not settle"); return ready.get();
    }
    void run() { run(true); }
    void runSrtCompatibility() { srtCompatibility = true; run(true); }
    void run(boolean includeOverlap) {
        Bundle result = new Bundle();
        try {
            parser();
            activity = test.startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(test.getTargetContext().getPackageName(), MainActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            String sealed = activity.getSharedPreferences("connection", 0).getString("sealed", null);
            for (int i = 0; i < 400 && api == null; i++) { ui(() -> api = (JellyfinApi) field(activity, "api")); SystemClock.sleep(100); }
            check(api != null, "Saved login failed"); original = api.client();
            embedded("sample_with_srt.mkv", "subrip"); embedded("sample_with_ass_subtitles.mkv", "ass");
            if (includeOverlap) embedded("sample_with_overlapping_srt.mkv", "subrip");
            check(sealed.equals(activity.getSharedPreferences("connection", 0).getString("sealed", null)), "Connection settings changed");
            result.putString("result", "ALL_PASS: " + (srtCompatibility ? "negotiated External SRT snapshots" : "production direct Embed SRT/ASS") + " / paused seeks / ASS supported styles / unchanged configuration"
                    + (includeOverlap ? " / overlapping SRT" : " / overlapping SRT excluded: strict suite records known seek failure")); test.finish(Activity.RESULT_OK, result);
        } catch (Throwable e) { result.putString("failure", android.util.Log.getStackTraceString(e)); test.finish(Activity.RESULT_CANCELED, result); }
        finally { if (api != null && original != null) try { set(api, "http", original); } catch (Exception ignored) { } }
    }

    private static final class BitmapCapture {
        static void save(Instrumentation test, Activity activity, String name) throws Exception {
            android.graphics.Bitmap screenshot = test.getUiAutomation().takeScreenshot();
            if (screenshot == null) throw new AssertionError("Screenshot unavailable");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(activity.getExternalFilesDir(null), name))) {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            } finally { screenshot.recycle(); }
        }
    }
}
