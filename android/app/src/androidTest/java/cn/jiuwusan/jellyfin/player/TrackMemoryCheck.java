package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class TrackMemoryCheck {
    private final Instrumentation test;
    TrackMemoryCheck(Instrumentation test) { this.test = test; }
    private void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
    private List<JellyfinApi.MediaTrack> tracks(JellyfinApi api, String type, JSONArray values) throws Exception {
        java.lang.reflect.Method method = JellyfinApi.class.getDeclaredMethod("mediaTracks", String.class, JSONObject.class, String.class);
        method.setAccessible(true);
        return (List<JellyfinApi.MediaTrack>) method.invoke(api, "fixture", new JSONObject().put("Id", "source").put("MediaStreams", values), type);
    }
    private JSONObject track(String type, int index, String language, String title) throws Exception {
        return new JSONObject().put("Type", type).put("Index", index).put("Language", language)
                .put("Title", title).put("Codec", "Subtitle".equals(type) ? "subrip" : "aac");
    }
    void run() {
        Bundle out = new Bundle();
        try (SubtitleHandlingCheck.Fixture fixture = new SubtitleHandlingCheck.Fixture()) {
            JellyfinApi api = new JellyfinApi(new ConnectionSettings("http://fixture.invalid", "127.0.0.1", fixture.server.getLocalPort(),
                    "", "", "fixture", "fixture", 1920, 4_000_000), "track-memory-check");
            api.login();
            JellyfinApi.Item item = new JellyfinApi.Item(new JSONObject().put("Id", "film").put("Name", "Fixture").put("Type", "Movie"));
            JellyfinApi.Stream stream = api.stream(item, PlaybackPolicy.fromSettings(1920, 4_000_000, 1080, 2400));
            api.report(stream, "", 1000, false);
            check(fixture.reports.get(0).optInt("AudioStreamIndex", -99) == stream.audioIndex
                    && fixture.reports.get(0).optInt("SubtitleStreamIndex", -99) == stream.defaultSubtitleIndex,
                    "Playback reports omitted actual track indices");
            java.lang.reflect.Field sub = JellyfinApi.Stream.class.getDeclaredField("reportSubtitleIndex"); sub.setAccessible(true); sub.set(stream, -1);
            for (String event : new String[]{"Progress", "Stopped"}) api.report(stream, event, 2000, false);
            check(fixture.reports.get(1).getInt("SubtitleStreamIndex") == -1 && fixture.reports.get(2).getInt("SubtitleStreamIndex") == -1,
                    "Disabled subtitles were not reported as -1");
            Class<?> preference = Class.forName("cn.jiuwusan.jellyfin.player.PlaybackTrackPreference");
            java.lang.reflect.Method of = preference.getDeclaredMethod("of", JellyfinApi.MediaTrack.class); of.setAccessible(true);
            java.lang.reflect.Method match = preference.getDeclaredMethod("match", List.class, boolean.class); match.setAccessible(true);
            Object audio = of.invoke(null, tracks(api, "Audio", new JSONArray().put(track("Audio", 1, "chi", "国语"))).get(0));
            List<JellyfinApi.MediaTrack> nextAudio = tracks(api, "Audio", new JSONArray()
                    .put(track("Audio", 1, "eng", "English")).put(track("Audio", 7, "zho", "国语")));
            check(Integer.valueOf(7).equals(match.invoke(audio, nextAudio, false)), "Next episode reused audio index instead of language/title");
            Object subtitle = of.invoke(null, tracks(api, "Subtitle", new JSONArray().put(track("Subtitle", 4, "zh-Hans", "简体"))).get(0));
            List<JellyfinApi.MediaTrack> nextSubtitles = tracks(api, "Subtitle", new JSONArray()
                    .put(track("Subtitle", 4, "eng", "English"))
                    .put(track("Subtitle", 8, "zh-Hant", "繁体"))
                    .put(track("Subtitle", 9, "zh-Hans", "简体"))
                    .put(track("Subtitle", 10, "zh-Hans", "简体").put("IsForced", true)));
            check(Integer.valueOf(9).equals(match.invoke(subtitle, nextSubtitles, true)), "Subtitle preference mismatched language/forced flags");
            check(match.invoke(subtitle, tracks(api, "Subtitle", new JSONArray().put(track("Subtitle", 10, "zh-Hans", "简体").put("IsForced", true))), true) == null,
                    "Forced subtitles replaced full subtitles");
            check(match.invoke(subtitle, tracks(api, "Subtitle", new JSONArray().put(track("Subtitle", 10, "zh-Hans", "简体").put("IsHearingImpaired", true))), true) == null,
                    "Hearing-impaired subtitles replaced regular subtitles");
            check(match.invoke(audio, tracks(api, "Audio", new JSONArray().put(track("Audio", 7, "zho", "国语").put("IsCommentary", true))), false) == null,
                    "Commentary audio replaced main audio");
            check(match.invoke(audio, tracks(api, "Audio", new JSONArray().put(track("Audio", 8, "zho", "国语")).put(track("Audio", 9, "zho", "国语"))), false) == null,
                    "Ambiguous tracks were selected arbitrarily");
            check(match.invoke(subtitle, tracks(api, "Subtitle", new JSONArray().put(track("Subtitle", 9, "zh-Hans", "简体").put("DeliveryMethod", "Unsupported"))), true) == null,
                    "Unavailable subtitle selected");
            fixture.languageScenario = true;
            JellyfinApi.Stream original = api.stream(item, PlaybackPolicy.fromSettings(1920, 4_000_000, 1080, 2400));
            PlaybackTrackPreference chosenAudio = PlaybackTrackPreference.of(original.audio.get(1));
            PlaybackTrackPreference chosenSubtitle = PlaybackTrackPreference.of(original.subtitles.get(0));
            fixture.defaultSubtitleOverride = 4;
            fixture.subtitleOverrides = new JSONArray().put(track("Subtitle", 4, "eng", ""))
                    .put(track("Subtitle", 9, "zh-Hant", ""));
            JellyfinApi.Item next = new JellyfinApi.Item(new JSONObject().put("Id", "next").put("Name", "Next").put("Type", "Episode"));
            PlaybackPolicy policy = PlaybackPolicy.fromSettings(1920, 4_000_000, 1080, 2400);
            JellyfinApi.Stream inherited = api.streamNextEpisode(next, policy, chosenAudio, chosenSubtitle, PlaybackTracks.AUTO);
            check(inherited.audioIndex == 2 && inherited.subtitleSelection == 9 && inherited.reportSubtitleIndex == 9,
                    "Negotiation lost inherited track selection");
            check("source".equals(fixture.last().optString("MediaSourceId")) && fixture.last().getInt("AudioStreamIndex") == 2,
                    "Inherited audio index was sent without source identity");
            api.report(inherited, "Progress", 3000, false);
            JSONObject reported = fixture.reports.get(fixture.reports.size() - 1);
            check(reported.getInt("SubtitleStreamIndex") == 9 && "-1".equals(inherited.url.queryParameter("SubtitleStreamIndex")),
                    "Client-drawn subtitle report incorrectly followed disabled server-burn index");
            JellyfinApi.Stream disabled = api.streamNextEpisode(next, policy, chosenAudio, chosenSubtitle, PlaybackTracks.OFF);
            check(disabled.subtitleSelection == -1 && disabled.reportSubtitleIndex == -1, "Cross-episode subtitle off was overridden");
            fixture.subtitleOverrides = new JSONArray().put(track("Subtitle", 4, "eng", ""));
            JellyfinApi.Stream fallback = api.streamNextEpisode(next, policy, null, chosenSubtitle, PlaybackTracks.AUTO);
            check(fallback.subtitleSelection == PlaybackTracks.AUTO && fallback.reportSubtitleIndex == 4,
                    "Missing subtitle preference did not fall back to server default");
            sub.set(stream, null);
            api.report(stream, "Progress", 4000, false);
            check(!fixture.reports.get(fixture.reports.size() - 1).has("SubtitleStreamIndex"), "Unknown local index was fabricated");
            out.putString("result", "ALL_PASS: report indices/off/changed indices/title/flags/ambiguity/negotiation/source identity/client text/unknown local");
            test.finish(Activity.RESULT_OK, out);
        } catch (Throwable e) { out.putString("failure", android.util.Log.getStackTraceString(e)); test.finish(Activity.RESULT_CANCELED, out); }
    }
}
