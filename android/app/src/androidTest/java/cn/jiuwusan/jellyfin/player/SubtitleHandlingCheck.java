package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import androidx.media3.common.C;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** In-device loopback API fixture; no settings or actual Jellyfin service changes. */
final class SubtitleHandlingCheck {
    private final Instrumentation test;
    SubtitleHandlingCheck(Instrumentation test) { this.test = test; }
    private void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private int burned(JellyfinApi.Stream stream) throws Exception {
        java.lang.reflect.Field f = stream.getClass().getDeclaredField("burnedSubtitleIndex"); f.setAccessible(true); return f.getInt(stream);
    }
    private JellyfinApi.Stream select(JellyfinApi api, JellyfinApi.Item item, PlaybackPolicy policy, Integer audio, int subtitle) throws Exception {
        java.lang.reflect.Method m = JellyfinApi.class.getDeclaredMethod("stream", JellyfinApi.Item.class, PlaybackPolicy.class,
                String.class, Integer.class, int.class); m.setAccessible(true);
        return (JellyfinApi.Stream) m.invoke(api, item, policy, "source", audio, subtitle);
    }
    void run() {
        Bundle result = new Bundle();
        try (Fixture fixture = new Fixture()) {
            JellyfinApi api = new JellyfinApi(new ConnectionSettings("http://fixture.invalid", "127.0.0.1", fixture.server.getLocalPort(),
                    "", "", "fixture", "fixture", 1920, 4_000_000), "subtitle-check"); api.login();
            JellyfinApi.Item item = new JellyfinApi.Item(new JSONObject().put("Id", "film").put("Name", "字幕验证").put("Type", "Movie"));
            PlaybackPolicy policy = PlaybackPolicy.fromSettings(1920, 4_000_000, 1080, 2400);
            JellyfinApi.Stream automatic = api.stream(item, policy);
            check(automatic.subtitles.size() == 2, "PGS subtitle was filtered out");
            check(burned(automatic) == 4 && automatic.defaultSubtitleIndex == 4, "Default PGS not selected for burn-in");
            check("4".equals(automatic.url.queryParameter("SubtitleStreamIndex"))
                    && "Encode".equals(automatic.url.queryParameter("SubtitleMethod")), "Burn-in request missing subtitle selection");
            check(fixture.last().getInt("SubtitleStreamIndex") == 4 && !fixture.last().optBoolean("AllowVideoStreamCopy", true), "Burn-in did not force encoding");
            check(PlaybackTracks.mediaItem(automatic).localConfiguration.subtitleConfigurations.size() == 1, "Bitmap track attached as a text file");
            JellyfinApi.Stream off = select(api, item, policy, 1, PlaybackTracks.OFF);
            check(burned(off) == -1 && fixture.last().getInt("SubtitleStreamIndex") == -1, "Off left server subtitle enabled");
            check("-1".equals(off.url.queryParameter("SubtitleStreamIndex")) && !"Encode".equals(off.url.queryParameter("SubtitleMethod")), "Off URL still burns subtitles");
            JellyfinApi.Stream text = select(api, item, policy, 1, 5);
            check(burned(text) == -1 && fixture.last().getInt("SubtitleStreamIndex") == -1, "Text subtitle should be rendered by client");
            check(PlaybackTracks.mediaItem(text).localConfiguration.subtitleConfigurations.size() == 1, "Missing client text subtitle");
            JellyfinApi.Stream audio = select(api, item, policy, 2, 4);
            check(audio.audioIndex == 2 && burned(audio) == 4 && fixture.last().getInt("AudioStreamIndex") == 2, "Audio switch lost selected subtitle");
            check("1920".equals(audio.url.queryParameter("MaxWidth")) && "1080".equals(audio.url.queryParameter("MaxHeight"))
                    && "3808000".equals(audio.url.queryParameter("VideoBitRate")) && "192000".equals(audio.url.queryParameter("AudioBitRate")), "Subtitle switch changed quality budget");
            PlaybackPolicy unlimited = PlaybackPolicy.fromSettings(0, 0, 1080, 2400);
            check(!select(api, item, unlimited, null, PlaybackTracks.OFF).hls, "Off unnecessarily forced direct source to HLS");
            check(!select(api, item, unlimited, null, 5).hls, "Client text unnecessarily forced HLS");
            JellyfinApi.Stream forced = select(api, item, unlimited, null, 4);
            check(forced.hls && burned(forced) == 4 && !fixture.last().optBoolean("AllowVideoStreamCopy", true), "Direct source PGS did not force video encoding");
            AtomicReference<Throwable> problem = new AtomicReference<>();
            test.runOnMainSync(() -> {
                ExoPlayer player = new ExoPlayer.Builder(test.getTargetContext()).build();
                try {
                    PlaybackTracks.setSubtitle(player, Tracks.EMPTY, automatic, PlaybackTracks.AUTO, null);
                    check(player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT), "Burn-in left client text enabled");
                    PlaybackTracks.setSubtitle(player, Tracks.EMPTY, text, 5, null);
                    check(!player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT), "Text choice is disabled");
                    PlaybackTracks.setSubtitle(player, Tracks.EMPTY, off, PlaybackTracks.OFF, null);
                    check(player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT), "Off left client text enabled");
                } catch (Throwable e) { problem.set(e); } finally { player.release(); }
            });
            if (problem.get() != null) throw new AssertionError(problem.get());
            result.putString("result", "ALL_PASS: default PGS / server burn-in / off / client text / audio preservation / quality / no double captions");
            test.finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            result.putString("failure", android.util.Log.getStackTraceString(e)); test.finish(Activity.RESULT_CANCELED, result);
        }
    }
    static final class Fixture implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"));
        final List<JSONObject> bodies = java.util.Collections.synchronizedList(new ArrayList<>());
        volatile boolean running = true;
        volatile boolean languageScenario;
        volatile JSONArray subtitleOverrides;
        volatile JSONArray negotiatedSubtitleOverrides;
        volatile Integer defaultSubtitleOverride;
        volatile int configurationWrites;
        volatile boolean failConfigurationRead, rejectConfigurationWrite;
        volatile JSONObject configuration = new JSONObject("{\"AudioLanguagePreference\":\"eng\",\"SubtitleLanguagePreference\":\"eng\",\"SubtitleMode\":\"None\",\"RememberSubtitleSelections\":true,\"PlayDefaultAudioTrack\":true,\"OtherSetting\":42}");
        Fixture() throws Exception {
            Thread worker = new Thread(() -> {
                while (running) try (Socket socket = server.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String first = in.readLine(); if (first == null) continue;
                    int size = 0; String line;
                    while (!(line = in.readLine()).isEmpty()) if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) size = Integer.parseInt(line.substring(15).trim());
                    StringBuilder body = new StringBuilder(); for (int i = 0; i < size; i++) body.append((char) in.read());
                    JSONObject request = size == 0 ? new JSONObject() : new JSONObject(body.toString());
                    String response;
                    String status = "200 OK";
                    if (first.contains("AuthenticateByName")) response = "{\"AccessToken\":\"fixture\",\"User\":{\"Id\":\"fixture\"}}";
                    else if (first.startsWith("POST ") && first.contains("/Users/Configuration?userId=fixture")) {
                        if (rejectConfigurationWrite) status = "403 Forbidden";
                        else { configuration = request; configurationWrites++; }
                        response = "{}";
                    }
                    else if (first.startsWith("GET ") && first.contains("/Users/fixture ")) response = failConfigurationRead ? "{}" : new JSONObject().put("Configuration", configuration).toString();
                    else { bodies.add(request); response = playback(request).toString(); }
                    byte[] data = response.getBytes(StandardCharsets.UTF_8);
                    socket.getOutputStream().write(("HTTP/1.1 " + status + "\r\nContent-Type: application/json\r\nContent-Length: " + data.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(data);
                } catch (Exception e) { if (running) throw new RuntimeException(e); }
            }, "subtitle-api-fixture"); worker.setDaemon(true); worker.start();
        }
        JSONObject last() { return bodies.get(bodies.size() - 1); }
        JSONObject playback(JSONObject request) throws Exception {
            int subtitle = request.optInt("SubtitleStreamIndex", languageScenario ? ("None".equals(configuration.optString("SubtitleMode")) ? -1 : 5) : 4), audio = request.optInt("AudioStreamIndex", 1);
            if (!request.has("SubtitleStreamIndex") && defaultSubtitleOverride != null) subtitle = defaultSubtitleOverride;
            JSONArray tracks = new JSONArray().put(new JSONObject().put("Type", "Video").put("Index", 0).put("Codec", "hevc").put("Width", 1920).put("Height", 800))
                    .put(new JSONObject().put("Type", "Audio").put("Index", 1).put("Codec", "aac").put("Language", languageScenario ? "eng" : ""))
                    .put(new JSONObject().put("Type", "Audio").put("Index", 2).put("Codec", "aac").put("Language", languageScenario ? "chi" : ""))
                    .put(new JSONObject().put("Type", "Subtitle").put("Index", 4).put("Codec", "PGSSUB").put("IsTextSubtitleStream", false).put("Language", languageScenario ? "zh-Hant" : "zho").put("IsDefault", !languageScenario))
                    .put(new JSONObject().put("Type", "Subtitle").put("Index", 5).put("Codec", "subrip").put("IsTextSubtitleStream", true).put("Language", "eng").put("IsExternal", true));
            JSONArray selectedOverrides = request.has("MediaSourceId") && negotiatedSubtitleOverrides != null
                    ? negotiatedSubtitleOverrides : subtitleOverrides;
            if (selectedOverrides != null) {
                JSONArray customized = new JSONArray();
                for (int i = 0; i < 3; i++) customized.put(tracks.get(i));
                for (int i = 0; i < selectedOverrides.length(); i++) customized.put(selectedOverrides.get(i));
                tracks = customized;
            }
            JSONObject source = new JSONObject().put("Id", "source").put("Bitrate", 18_000_000).put("MediaStreams", tracks)
                    .put("SupportsDirectPlay", true).put("SupportsDirectStream", true).put("SupportsTranscoding", true)
                    .put("DefaultSubtitleStreamIndex", subtitle).put("DefaultAudioStreamIndex", audio)
                    .put("TranscodingUrl", "/Videos/film/master.m3u8?AudioStreamIndex=" + audio + "&SubtitleStreamIndex=" + subtitle + "&SubtitleMethod=Encode");
            return new JSONObject().put("PlaySessionId", "fixture-session").put("MediaSources", new JSONArray().put(source));
        }
        @Override public void close() throws Exception { running = false; server.close(); }
    }
}
