package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.media3.common.C;
import androidx.media3.exoplayer.ExoPlayer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Opt-in real video with client-only HTTP fault injection. Never changes server configuration. */
final class SubtitleRecoveryRealCheck {
    private final Instrumentation test;
    private Activity activity;
    private JellyfinApi api;
    private OkHttpClient original;
    SubtitleRecoveryRealCheck(Instrumentation test) { this.test = test; }
    private void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
    private Object field(Object object, String name) {
        try { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private void set(Object object, String name, Object value) {
        try { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); f.set(object, value); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private void call(Object object, String name, Class<?>[] types, Object... arguments) {
        try { Method m = object.getClass().getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(object, arguments); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private void ui(Runnable work) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        test.runOnMainSync(() -> { try { work.run(); } catch (Throwable e) { failure.set(e); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    private void stage(String stage) { Bundle b = new Bundle(); b.putString("stage", stage); test.sendStatus(0, b); }
    private boolean settled(String mode) {
        Object page = field(activity, "playback");
        ExoPlayer player = (ExoPlayer) field(activity, "player");
        if ("refresh-video".equals(mode)) return page != null && (boolean) field(page, "preparationFailed");
        if (page == null || player == null || player.getPlaybackState() != 3 || field(page, "change") != null) return false;
        JellyfinApi.Stream stream = (JellyfinApi.Stream) field(activity, "stream");
        if (stream == null) return false;
        if ("healthy".equals(mode)) return (boolean) field(activity, "playbackStarted");
        if ("burn-failed".equals(mode) || "auth".equals(mode) || "refresh-auth".equals(mode))
            return (int) field(page, "subtitle") == PlaybackTracks.OFF && player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT);
        if ("parser".equals(mode)) return stream.burnedSubtitleIndex >= 0;
        return (boolean) field(page, "loggedSubtitle");
    }
    private void exercise(JellyfinApi.Item item, int index, String mode) throws Exception {
        api.subtitleCache.clear(); // Each fault scenario must reach the HTTP layer independently.
        stage(mode + " START index=" + index);
        AtomicInteger subtitleRequests = new AtomicInteger(), playbackRequests = new AtomicInteger(), burnRequests = new AtomicInteger();
        AtomicReference<ExoPlayer> firstPlayer = new AtomicReference<>();
        AtomicReference<String> subtitleUri = new AtomicReference<>();
        OkHttpClient injected = original.newBuilder().addInterceptor(chain -> {
            Request request = chain.request();
            String path = request.url().encodedPath();
            boolean playback = path.contains("/" + item.id + "/PlaybackInfo");
            boolean force = false;
            if (playback && request.body() != null) {
                Buffer buffer = new Buffer(); request.body().writeTo(buffer);
                try {
                    JSONObject body = new JSONObject(buffer.readUtf8());
                    JSONArray profiles = body.getJSONObject("DeviceProfile").getJSONArray("SubtitleProfiles");
                    force = profiles.length() > 0;
                    for (int i = 0; i < profiles.length(); i++) force &= "Encode".equals(profiles.getJSONObject(i).getString("Method"));
                } catch (Exception e) { throw new java.io.IOException(e); }
                playbackRequests.incrementAndGet();
                if ("refresh-auth".equals(mode) && playbackRequests.get() > 2) return failure(request, 401, "refresh unauthorized");
                if (force) burnRequests.incrementAndGet();
                if (force && "burn-failed".equals(mode)) return failure(request, 503, "burn negotiation rejected");
            }
            if (path.toLowerCase(java.util.Locale.ROOT).contains("/subtitles/") && path.toLowerCase(java.util.Locale.ROOT).contains("stream.")) {
                subtitleUri.set(request.url().toString());
                int attempt = subtitleRequests.incrementAndGet();
                if ("transient".equals(mode) && attempt == 1) return failure(request, 503, "temporary subtitle failure");
                if (("stale".equals(mode) || mode.startsWith("refresh-")) && playbackRequests.get() <= 2) return failure(request, 404, "expired subtitle address");
                if ("auth".equals(mode)) return failure(request, 401, "subtitle unauthorized");
                if ("parser".equals(mode) || "burn-failed".equals(mode))
                    return new Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("fixture malformed TTML")
                            .body(ResponseBody.create("this is not valid XML", MediaType.get("application/ttml+xml"))).build();
            }
            if ("refresh-video".equals(mode) && playbackRequests.get() > 2 && (path.endsWith(".m3u8") || path.endsWith(".ts")))
                return failure(request, 401, "video unavailable after refresh");
            Response response = chain.proceed(request);
            if (!playback || force || response.body() == null) return response;
            try {
                JSONObject data = new JSONObject(response.body().string());
                JSONArray sources = data.getJSONArray("MediaSources");
                for (int i = 0; i < sources.length(); i++) {
                    JSONObject source = sources.getJSONObject(i); source.put("DefaultSubtitleStreamIndex", index);
                    JSONArray tracks = source.getJSONArray("MediaStreams");
                    for (int j = 0; j < tracks.length(); j++) {
                        JSONObject track = tracks.getJSONObject(j);
                        if (track.optInt("Index", -1) == index && "Subtitle".equals(track.optString("Type"))
                                && ("parser".equals(mode) || "burn-failed".equals(mode))) {
                            track.put("Codec", "ttml").put("DeliveryMethod", "External")
                                    .put("DeliveryUrl", "/Videos/" + item.id + "/" + source.getString("Id") + "/Subtitles/" + index + "/Stream.ttml");
                        }
                    }
                }
                return response.newBuilder().body(ResponseBody.create(data.toString(), MediaType.get("application/json"))).build();
            } catch (Exception e) { response.close(); throw new java.io.IOException(e); }
        }).build();
        set(api, "http", injected);
        check(api.client() == injected, "Client interceptor was not installed");
        ui(() -> call(activity, "playbackPage", new Class<?>[]{JellyfinApi.Item.class, Long.class, Runnable.class},
                item, Long.valueOf(180_000), (Runnable) () -> call(activity, "homePage", new Class<?>[]{})));
        boolean paused = "burn-failed".equals(mode);
        long until = SystemClock.uptimeMillis() + 120_000;
        long diagnosticAt = SystemClock.uptimeMillis() + 12_000;
        java.util.concurrent.atomic.AtomicBoolean diagnosed = new java.util.concurrent.atomic.AtomicBoolean();
        final boolean[] passed = {false};
        while (SystemClock.uptimeMillis() < until && !passed[0]) {
            ui(() -> {
                ExoPlayer player = (ExoPlayer) field(activity, "player");
                if (player != null && firstPlayer.get() == null) {
                    firstPlayer.set(player);
                    if (paused) player.pause();
                }
                passed[0] = settled(mode);
                if (player != null && SystemClock.uptimeMillis() >= diagnosticAt && diagnosed.compareAndSet(false, true)) {
                    // One early snapshot makes a missed cue distinguishable from a failed download or wrong seek.
                    if (firstPlayer.get() == player) {
                        StringBuilder groups = new StringBuilder();
                        for (androidx.media3.common.Tracks.Group group : player.getCurrentTracks().getGroups()) if (group.getType() == C.TRACK_TYPE_TEXT)
                            groups.append('[').append(group.getTrackFormat(0).id).append('/').append(group.getTrackFormat(0).sampleMimeType)
                                    .append(" supported=").append(group.isTrackSupported(0)).append(" selected=").append(group.isTrackSelected(0)).append(']');
                        stage(mode + " diagnostic pos=" + player.getCurrentPosition() + " textDisabled="
                                + player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) + " groups=" + groups);
                    }
                }
            });
            SystemClock.sleep(100);
        }
        if (!passed[0]) ui(() -> {
            Object page = field(activity, "playback");
            ExoPlayer p = (ExoPlayer) field(activity, "player");
            JellyfinApi.Stream stream = (JellyfinApi.Stream) field(activity, "stream");
            stage(mode + " timeout requests=" + subtitleRequests.get() + " info=" + playbackRequests.get() + " burn=" + burnRequests.get()
                    + " playerState=" + (p == null ? -1 : p.getPlaybackState()) + " position=" + (p == null ? -1 : p.getCurrentPosition())
                    + " selection=" + (page == null ? "none" : field(page, "subtitle"))
                    + " default=" + (stream == null ? -9 : stream.defaultSubtitleIndex) + " baked=" + (stream == null ? -9 : stream.burnedSubtitleIndex)
                    + " external=" + (stream == null ? -9 : PlaybackTracks.externalSubtitleIndex(stream, (int) field(page, "subtitle"))));
        });
        check(passed[0], mode + " did not settle");
        if (("parser".equals(mode) || "burn-failed".equals(mode)) && subtitleUri.get() != null)
            check(api.subtitleCache.get(subtitleUri.get(), SystemClock.elapsedRealtime()) == null, "Malformed subtitle remained cached");
        if ("auth".equals(mode)) {
            // Make the server healthy again, then exercise the actual menu handler's explicit retry.
            set(api, "http", original);
            check(subtitleUri.get() != null, "Missing failed subtitle URI");
            api.subtitleCache.put(subtitleUri.get(), "stale invalid cached subtitle".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    SystemClock.elapsedRealtime(), api.subtitleCache.generation());
            ui(() -> call(field(activity, "playback"), "selectSubtitle", new Class<?>[]{int.class, PlaybackTracks.LocalTrack.class}, index, null));
            final boolean[] retried = {false};
            long retryUntil = SystemClock.uptimeMillis() + 60_000;
            while (!retried[0] && SystemClock.uptimeMillis() < retryUntil) {
                ui(() -> retried[0] = settled("transient")); SystemClock.sleep(100);
            }
            check(retried[0], "Explicit retry could not restore text subtitles after disable");
            stage("explicit retry PASS");
        }
        if ("parser".equals(mode)) {
            SystemClock.sleep(1500);
            android.graphics.Bitmap screenshot = test.getUiAutomation().takeScreenshot();
            if (screenshot != null) try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                    new java.io.File(activity.getExternalFilesDir(null), "subtitle-recovery-burn.png"))) {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            } finally { screenshot.recycle(); }
        }
        ui(() -> {
            Object page = field(activity, "playback");
            ExoPlayer player = (ExoPlayer) field(activity, "player");
            JellyfinApi.Stream stream = (JellyfinApi.Stream) field(activity, "stream");
            check(Math.abs(player.getCurrentPosition() - 180_000) < 15_000, mode + " lost playback position");
            if (paused) check(!player.getPlayWhenReady(), "Recovery lost paused state");
            if ("parser".equals(mode)) check(stream.burnedSubtitleIndex == index && player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT), "Parser fallback did not disable local text");
            check("3808000".equals(stream.url.queryParameter("VideoBitRate")), "Recovery changed quality budget");
            call(activity, "onBackPressed", new Class<?>[]{});
            // Old asynchronous failure must not restart a closed player.
            call(page, "subtitleFailed", new Class<?>[]{ExoPlayer.class, JellyfinApi.Stream.class, int.class, java.io.IOException.class},
                    firstPlayer.get(), stream, index, new java.io.IOException("stale callback"));
            check(field(activity, "playback") == null, "Stale callback reopened player");
        });
        if ("transient".equals(mode)) check(subtitleRequests.get() == 2 && burnRequests.get() == 0 && playbackRequests.get() == 2, "Transient retry exceeded budget or rebuilt video");
        if ("stale".equals(mode)) check(subtitleRequests.get() == 2 && burnRequests.get() == 0 && playbackRequests.get() == 4, "Stale address was not refreshed exactly once");
        if ("auth".equals(mode)) check(subtitleRequests.get() == 1 && burnRequests.get() == 0, "Auth failure retried or burned");
        if ("refresh-auth".equals(mode) || "refresh-video".equals(mode)) check(burnRequests.get() == 0, "Refresh auth/video failure was misclassified as subtitle burn");
        if ("parser".equals(mode)) check(burnRequests.get() == 2, "Parser fallback did not negotiate one forced stream");
        if ("burn-failed".equals(mode)) check(burnRequests.get() == 1, "Failed burn looped");
        stage(mode + " PASS subtitles=" + subtitleRequests.get() + " playbackInfo=" + playbackRequests.get() + " burnInfo=" + burnRequests.get());
        set(api, "http", original);
        SystemClock.sleep(1000);
    }
    private Response failure(Request request, int code, String message) {
        return new Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(code).message(message)
                .body(ResponseBody.create("fixture", MediaType.get("text/plain"))).build();
    }
    void run() {
        Bundle result = new Bundle();
        try {
            activity = test.startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(test.getTargetContext().getPackageName(), MainActivity.class.getName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            String sealed = activity.getSharedPreferences("connection", 0).getString("sealed", null);
            for (int i = 0; i < 400 && api == null; i++) { ui(() -> api = (JellyfinApi) field(activity, "api")); SystemClock.sleep(100); }
            check(api != null, "Saved login failed"); original = api.client();
            JellyfinApi.Item episode = null;
            for (JellyfinApi.Item show : api.search("魅影神捕")) if ("Series".equals(show.type)) {
                java.util.List<JellyfinApi.Item> episodes = api.episodes(show, null);
                if (!episodes.isEmpty()) episode = episodes.get(Math.min(1, episodes.size() - 1)); break;
            }
            check(episode != null, "Missing regression episode");
            JellyfinApi.Stream source = api.stream(episode, PlaybackPolicy.fromSettings(1920, 4_000_000, 1264, 2800));
            JellyfinApi.MediaTrack text = null;
            for (JellyfinApi.MediaTrack track : source.subtitles) if ("External".equals(track.deliveryMethod) && track.available()) {
                text = track;
                break;
            }
            check(text != null, "No external subtitle for fault injection");
            for (String mode : new String[]{"healthy", "transient", "stale", "parser", "burn-failed", "auth", "refresh-auth", "refresh-video"}) exercise(episode, text.index, mode);
            check(sealed.equals(activity.getSharedPreferences("connection", 0).getString("sealed", null)), "Saved connection changed");
            result.putString("result", "ALL_PASS: transient retry / stale refresh / real text burn / burn failure continues video / pause-position-quality / auth stop / stale callback / unchanged connection");
            test.finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("failure", android.util.Log.getStackTraceString(error)); test.finish(Activity.RESULT_CANCELED, result);
        } finally { if (api != null && original != null) set(api, "http", original); }
    }
}
