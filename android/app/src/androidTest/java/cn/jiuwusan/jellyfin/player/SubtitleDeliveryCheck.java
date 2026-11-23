package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Loopback protocol tests: no server or device connection settings are modified. */
final class SubtitleDeliveryCheck {
    private final Instrumentation test;
    SubtitleDeliveryCheck(Instrumentation test) { this.test = test; }
    private void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
    private JSONObject track(String method, String codec) throws Exception {
        return new JSONObject().put("Type", "Subtitle").put("Index", 4).put("Codec", codec)
                .put("Language", "zho").put("DeliveryMethod", method).put("IsTextSubtitleStream", true);
    }
    private Tracks.Group group(String id, String mime) {
        return new Tracks.Group(new TrackGroup(id, new Format.Builder().setId(id).setSampleMimeType(mime).build()),
                false, new int[]{C.FORMAT_HANDLED}, new boolean[]{false});
    }
    void run() {
        Bundle result = new Bundle();
        try (SubtitleHandlingCheck.Fixture fixture = new SubtitleHandlingCheck.Fixture()) {
            JellyfinApi api = new JellyfinApi(new ConnectionSettings("http://fixture.invalid", "127.0.0.1", fixture.server.getLocalPort(),
                    "", "", "fixture", "fixture", 1920, 4_000_000), "delivery-check"); api.login();
            JellyfinApi.Item item = new JellyfinApi.Item(new JSONObject().put("Id", "film").put("Name", "字幕投递检查").put("Type", "Movie"));
            PlaybackPolicy automatic = PlaybackPolicy.fromSettings(0, 0, 1080, 2400);
            fixture.subtitleOverrides = new JSONArray().put(track("Encode", "ass"));
            JellyfinApi.Stream encoded = api.stream(item, automatic);
            check(encoded.hls && encoded.burnedSubtitleIndex == 4, "Encode text subtitle did not force burn-in");
            fixture.subtitleOverrides = new JSONArray().put(track("External", "subrip")
                    .put("DeliveryUrl", "/Videos/film/source/Subtitles/4/Stream.vtt?startPositionTicks=120000000&custom=keep&api_key=fixture"));
            JellyfinApi.Stream external = api.stream(item, automatic);
            check(external.subtitles.get(0).subtitleUrl.encodedPath().endsWith("Stream.vtt"), "Returned subtitle URL was replaced");
            check("keep".equals(external.subtitles.get(0).subtitleUrl.queryParameter("custom")), "Delivery parameters lost");
            check("0".equals(external.subtitles.get(0).subtitleUrl.queryParameter("startPositionTicks")), "Full subtitle timeline not requested");
            check(external.subtitles.get(0).subtitleUrl.queryParameter("api_key") == null, "URL token retained instead of header auth");
            check(MimeTypes.TEXT_VTT.equals(PlaybackTracks.mediaItem(external).localConfiguration.subtitleConfigurations.get(0).mimeType), "Delivery MIME ignored actual file format");
            fixture.subtitleOverrides = new JSONArray().put(track("Embed", "subrip"));
            JellyfinApi.Stream embedded = api.stream(item, automatic);
            check(PlaybackTracks.mediaItem(embedded).localConfiguration.subtitleConfigurations.isEmpty(), "Embed subtitle unnecessarily extracted");
            fixture.defaultSubtitleOverride = -1;
            JellyfinApi.Stream noDefault = api.stream(item, automatic);
            AtomicReference<Throwable> problem = new AtomicReference<>();
            test.runOnMainSync(() -> {
                ExoPlayer player = new ExoPlayer.Builder(test.getTargetContext()).build();
                try {
                    Tracks.Group subtitle = group("native-subtitle-without-jellyfin-id", MimeTypes.APPLICATION_SUBRIP);
                    Tracks tracks = new Tracks(Arrays.asList(group("video", MimeTypes.VIDEO_H264), group("audio1", MimeTypes.AUDIO_AAC),
                            group("audio2", MimeTypes.AUDIO_AAC), subtitle));
                    PlaybackTracks.setSubtitle(player, tracks, embedded, PlaybackTracks.AUTO, null);
                    check(player.getTrackSelectionParameters().overrides.containsKey(subtitle.getMediaTrackGroup()), "Embedded stream index was not mapped");
                    PlaybackTracks.setSubtitle(player, tracks, noDefault, PlaybackTracks.AUTO, null);
                    check(player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT), "Direct automatic no-subtitle left renderer enabled");
                    PlaybackTracks.setSubtitle(player, tracks, embedded, PlaybackTracks.OFF, null);
                    check(player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT), "Off left renderer enabled");
                } catch (Throwable error) { problem.set(error); } finally { player.release(); }
            });
            if (problem.get() != null) throw new AssertionError(problem.get());
            fixture.defaultSubtitleOverride = null;
            fixture.negotiatedSubtitleOverrides = new JSONArray().put(track("External", "subrip")
                    .put("DeliveryUrl", "/Videos/film/source/Subtitles/4/Stream.vtt"));
            PlaybackPolicy limited = PlaybackPolicy.fromSettings(1920, 4_000_000, 1080, 2400);
            JellyfinApi.Stream negotiated = api.stream(item, limited);
            check(negotiated.hls && negotiated.burnedSubtitleIndex == -1
                    && MimeTypes.TEXT_VTT.equals(PlaybackTracks.mediaItem(negotiated).localConfiguration.subtitleConfigurations.get(0).mimeType)
                    && "-1".equals(negotiated.url.queryParameter("SubtitleStreamIndex")), "Embed-to-External renegotiation lost final delivery method");
            fixture.negotiatedSubtitleOverrides = new JSONArray().put(track("Encode", "ass"));
            negotiated = api.stream(item, limited);
            check(negotiated.burnedSubtitleIndex == 4 && "Encode".equals(negotiated.url.queryParameter("SubtitleMethod"))
                    && "false".equals(negotiated.url.queryParameter("AllowVideoStreamCopy")), "Embed-to-Encode renegotiation did not burn selected subtitle");
            fixture.negotiatedSubtitleOverrides = null;
            fixture.subtitleOverrides = new JSONArray().put(track("External", "subrip").put("DeliveryUrl", "http://elsewhere.invalid/Stream.srt"));
            check(PlaybackTracks.mediaItem(api.stream(item, automatic)).localConfiguration.subtitleConfigurations.isEmpty(), "Cross-origin URL silently replaced with fabricated URL");
            fixture.subtitleOverrides = new JSONArray().put(track("FutureMethod", "subrip"));
            check(PlaybackTracks.mediaItem(api.stream(item, automatic)).localConfiguration.subtitleConfigurations.isEmpty(), "Unknown delivery method silently treated as text");
            result.putString("result", "ALL_PASS: Encode text / returned URL and MIME / full timeline / header auth / Embed mapping / renegotiation / direct auto-off / explicit off / invalid origin / unknown method");
            test.finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("failure", android.util.Log.getStackTraceString(error)); test.finish(Activity.RESULT_CANCELED, result);
        }
    }
}
