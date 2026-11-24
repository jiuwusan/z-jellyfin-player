package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import java.util.Arrays;
import java.util.List;

/** Synthetic metadata only: no account preferences or production history are changed. */
final class ChineseTrackCheck {
    private final Instrumentation test;
    ChineseTrackCheck(Instrumentation test) { this.test = test; }
    private JellyfinApi.MediaTrack track(int index, String language, String title) {
        return new JellyfinApi.MediaTrack(index, title, language, "srt", null, "Encode", -1,
                title, false, false, false);
    }
    private void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
    private Integer select(boolean subtitle, int current, JellyfinApi.MediaTrack... tracks) throws Exception {
        Class<?> type = Class.forName("cn.jiuwusan.jellyfin.player.ChineseTrackSelector");
        java.lang.reflect.Method method = type.getDeclaredMethod("select", List.class, int.class, boolean.class);
        method.setAccessible(true);
        return (Integer) method.invoke(null, Arrays.asList(tracks), current, subtitle);
    }
    void run() {
        Bundle out = new Bundle();
        try {
            JellyfinApi.MediaTrack cantonese = track(1, "zho", "Cantonese TrueHD");
            JellyfinApi.MediaTrack cantonese2 = track(2, "chi", "Cantonese AC3");
            JellyfinApi.MediaTrack mandarin = track(3, "zho", "Mandarin DTS");
            check(Integer.valueOf(3).equals(select(false, 1, cantonese, cantonese2, mandarin)), "First Chinese default was not refined");
            check(select(false, 2, cantonese, cantonese2, mandarin) == null, "Second Chinese default was overridden");
            check(select(false, 3, cantonese, cantonese2, mandarin) == null, "Manual Mandarin default was overridden");
            check(Integer.valueOf(3).equals(select(false, 1, mandarin, cantonese2, cantonese)), "Group first must follow stream index, not list order");
            check(Integer.valueOf(3).equals(select(false, 1, track(0, "eng", "English"), cantonese, mandarin)), "First means language group, not all streams");
            check(select(false, 0, track(0, "eng", "English"), cantonese, mandarin) == null, "Foreign server default was overridden");
            check(select(false, 1, track(1, "zho", "中文 DTS"), track(2, "zho", "中文 AAC")) == null, "Generic Chinese was guessed as Mandarin");
            check(select(false, 1, cantonese, track(3, "zho", "Mandarin Commentary")) == null, "Commentary was selected");
            check(select(false, 1, cantonese, track(3, "cmn", "Cantonese")) == null, "Conflicting language and title selected");
            check(Integer.valueOf(3).equals(select(false, 1, track(1, "yue", "粤语"), track(3, "cmn", "普通话"))), "Explicit language aliases not supported");
            JellyfinApi.MediaTrack traditional = track(4, "zho", "Cht/SUP 原盘繁体");
            JellyfinApi.MediaTrack simplified = track(5, "zho", "Chs/SUP 原盘简体");
            check(Integer.valueOf(5).equals(select(true, 4, traditional, simplified)), "First Traditional was not refined to Simplified");
            check(select(true, 5, traditional, simplified) == null, "Second subtitle default was overridden");
            check(select(true, -1, traditional, simplified) == null, "Disabled subtitles were enabled");
            check(select(true, 4, traditional, track(5, "zho", "Cht 原盘简体")) == null, "Conflicting script was selected");
            check(select(true, 4, traditional, track(5, "zho", "Chs Commentary")) == null, "Commentary subtitles selected");
            check(select(true, 4, traditional, track(5, "zho", "简中 SDH")) == null, "SDH selected automatically");
            check(select(true, 4, traditional, track(5, "zho", "Chs Forced")) == null, "Forced title ignored");
            check(select(false, 1, cantonese, track(3, "zho", "Mandarin Description")) == null, "Description audio selected");
            check(select(true, 4, traditional, new JellyfinApi.MediaTrack(5, "Chs", "zho", "srt", null,
                    "Unsupported", -1, "Chs", false, false, false)) == null, "Unavailable subtitle selected");
            check(select(true, 4, traditional, new JellyfinApi.MediaTrack(5, "Chs", "zho", "srt", null,
                    "Encode", -1, "Chs", true, false, false)) == null, "Structured forced flag ignored");
            check(Integer.valueOf(6).equals(select(true, 4, traditional, track(5, "zho", "简英双语"), track(6, "zh-Hans", "简中"))), "Plain Simplified should precede bilingual");
            check(Integer.valueOf(5).equals(select(true, 4, traditional, track(5, "zho", "简英双语"))), "Bilingual fallback missing");
            check(select(true, 4, traditional, track(5, "zho", "Eng/SUP")) == null, "Mistagged English selected as Chinese");
            check(select(true, 4, traditional) == null, "Single candidate changed");
            check(Integer.valueOf(3).equals(select(false, 1, cantonese, track(3, "zho", "Mandarin"), track(7, "zho", "Mandarin"))), "Equivalent Mandarin tracks should use first index");
            check(Integer.valueOf(3).equals(select(false, 1, cantonese,
                    track(7, "zho", "Mandarin Dolby Digital Audio 5.1 @ 640 kbps"),
                    track(3, "zho", "Mandarin Dolby TrueHD Audio 5.1 @ 2480 kbps"))), "Mandarin codec variants did not use stable index order");
            check(Integer.valueOf(5).equals(select(true, 4, traditional,
                    track(8, "zh-Hans", "Chs/SRT 原盘简体"), track(5, "zho", "Chs/SUP 原盘简体"))), "Equivalent Simplified subtitle formats did not use stable index order");
            check(Integer.valueOf(5).equals(select(true, 4, traditional,
                    track(8, "zh-Hans", "简体中文"), track(5, "zho", "Chs"))), "Chinese script aliases were treated as different purposes");
            check(select(false, 1, cantonese, track(3, "zho", "Mandarin 国配"), track(7, "zho", "Mandarin 台配")) == null, "Different dub variants were guessed");
            check(select(true, 4, traditional, track(5, "zho", "Chs/SUP 原盘简体"), track(8, "zh-Hans", "Chs/SRT 国配简体")) == null, "Different subtitle purposes were guessed");
            check(select(false, 1, track(1, "zho", "Mandarin TrueHD"), track(3, "zho", "Mandarin AC3")) == null, "Already suitable server audio was replaced");
            integration();
            out.putString("result", "ALL_PASS: first-group gate/later defaults/manual protection/account isolation/Chinese aliases/conflicts/roles/negotiation");
            test.finish(Activity.RESULT_OK, out);
        } catch (Throwable error) {
            out.putString("failure", android.util.Log.getStackTraceString(error));
            test.finish(Activity.RESULT_CANCELED, out);
        }
    }

    private org.json.JSONObject jsonTrack(String type, int index, String language, String title) throws Exception {
        return new org.json.JSONObject().put("Type", type).put("Index", index).put("Language", language)
                .put("Title", title).put("Codec", "Subtitle".equals(type) ? "pgssub" : "aac")
                .put("IsTextSubtitleStream", false);
    }

    private JellyfinApi api(SubtitleHandlingCheck.Fixture fixture, ManualTrackChoices choices) throws Exception {
        JellyfinApi api = new JellyfinApi(new ConnectionSettings("http://fixture.invalid", "127.0.0.1", fixture.server.getLocalPort(),
                "", "", "fixture", "fixture", 1920, 4_000_000), "chinese-track-check");
        api.useManualChoices(choices); api.login(); return api;
    }

    private void integration() throws Exception {
        android.content.SharedPreferences preferences = test.getTargetContext().getSharedPreferences("chinese-track-test-only", 0);
        preferences.edit().clear().commit();
        try (SubtitleHandlingCheck.Fixture fixture = new SubtitleHandlingCheck.Fixture()) {
            fixture.includeLoginConfiguration = true;
            fixture.configuration = new org.json.JSONObject().put("AudioLanguagePreference", "zho")
                    .put("SubtitleLanguagePreference", "zho").put("SubtitleMode", "Default")
                    .put("RememberAudioSelections", true).put("RememberSubtitleSelections", true);
            fixture.audioOverrides = new org.json.JSONArray().put(jsonTrack("Audio", 1, "zho", "Cantonese"))
                    .put(jsonTrack("Audio", 2, "zho", "Cantonese AC3"))
                    .put(jsonTrack("Audio", 3, "zho", "Mandarin Dolby TrueHD Audio 5.1 @ 2480 kbps"))
                    .put(jsonTrack("Audio", 7, "zho", "Mandarin Dolby Digital Audio 5.1 @ 640 kbps"));
            fixture.subtitleOverrides = new org.json.JSONArray().put(jsonTrack("Subtitle", 4, "zho", "Cht"))
                    .put(jsonTrack("Subtitle", 5, "zho", "Chs/SUP 原盘简体"))
                    .put(jsonTrack("Subtitle", 8, "zh-Hans", "Chs/SRT 原盘简体").put("Codec", "subrip").put("IsTextSubtitleStream", true));
            ManualTrackChoices choices = new ManualTrackChoices(preferences);
            JellyfinApi api = api(fixture, choices);
            JellyfinApi.Item item = new JellyfinApi.Item(new org.json.JSONObject().put("Id", "film").put("Name", "Fixture").put("Type", "Movie"));
            PlaybackPolicy policy = PlaybackPolicy.fromSettings(1920, 4_000_000, 1080, 2400);
            JellyfinApi.Stream result = api.stream(item, policy);
            check(result.audioIndex == 3 && result.defaultSubtitleIndex == 5 && result.burnedSubtitleIndex == 5,
                    "Refinement did not negotiate actual audio and image subtitle burn-in");
            check("source".equals(fixture.last().optString("MediaSourceId")) && fixture.last().optInt("AudioStreamIndex") == 3
                    && fixture.last().optInt("SubtitleStreamIndex") == 5, "Refined selection lost its source identity");
            check(fixture.configurationWrites == 0, "Selection changed global account preferences");
            fixture.defaultAudioOverride = 2; fixture.defaultSubtitleOverride = 5;
            result = api.stream(item, policy);
            check(result.audioIndex == 2 && result.defaultSubtitleIndex == 5, "Later server defaults were overridden");
            fixture.defaultAudioOverride = 1; fixture.defaultSubtitleOverride = 4;
            result = api.stream(item, policy, "source", 1, PlaybackTracks.OFF);
            check(result.audioIndex == 1 && result.reportSubtitleIndex == -1, "Explicit choices were overridden");
            api.rememberTrack(result, false, true); api.rememberTrack(result, true, true);
            result = api(fixture, new ManualTrackChoices(preferences)).stream(item, policy);
            check(result.audioIndex == 1 && result.defaultSubtitleIndex == 4, "Manual first-track choice lost across login/recreation");
            api.rememberTrack(result, true, false);
            result = api.stream(item, policy);
            check(result.audioIndex == 1 && result.defaultSubtitleIndex == 5, "Subtitle AUTO did not clear only subtitle protection");
            check(!choices.contains("different-server", "fixture", "film", "source", false)
                    && !choices.contains("http://fixture.invalid/", "other-user", "film", "source", false)
                    && !choices.contains("http://fixture.invalid/", "fixture", "other-item", "source", false)
                    && !choices.contains("http://fixture.invalid/", "fixture", "film", "other-source", false), "Manual marker leaked across scope");
            fixture.configuration.put("RememberAudioSelections", false);
            result = api(fixture, choices).stream(item, policy);
            check(result.audioIndex == 3, "Disabled account memory was ignored");
            fixture.configuration.put("AudioLanguagePreference", "eng").put("SubtitleLanguagePreference", "eng");
            result = api(fixture, choices).stream(item, policy);
            check(result.audioIndex == 1 && result.defaultSubtitleIndex == 4, "Non-Chinese preference was overridden");
            fixture.configuration.put("AudioLanguagePreference", "yue").put("SubtitleLanguagePreference", "zh-Hant");
            result = api(fixture, choices).stream(item, policy);
            check(result.audioIndex == 1 && result.defaultSubtitleIndex == 4, "Explicit Cantonese/Traditional preference overridden");
            fixture.configuration.put("AudioLanguagePreference", "zho").put("SubtitleLanguagePreference", "zho").put("SubtitleMode", "OnlyForced");
            result = api(fixture, choices).stream(item, policy);
            check(result.defaultSubtitleIndex == 4, "Forced-only mode was second-guessed");
            fixture.includeLoginConfiguration = false;
            result = api(fixture, choices).stream(item, policy);
            check(result.audioIndex == 1 && result.defaultSubtitleIndex == 4, "Unknown account preferences were guessed");
        } finally { preferences.edit().clear().commit(); }
    }
}
