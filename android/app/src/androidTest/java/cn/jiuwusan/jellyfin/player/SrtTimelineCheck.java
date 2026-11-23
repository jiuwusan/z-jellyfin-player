package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import androidx.media3.common.text.Cue;
import androidx.media3.extractor.text.CuesWithTiming;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class SrtTimelineCheck {
    private final Instrumentation test;
    SrtTimelineCheck(Instrumentation test) { this.test = test; }
    private void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private CuesWithTiming cue(String text, long startMs, long endMs) {
        return new CuesWithTiming(java.util.Collections.singletonList(new Cue.Builder().setText(text).build()), startMs * 1000, (endMs - startMs) * 1000);
    }
    private List<CuesWithTiming> normalize(List<CuesWithTiming> input) throws Exception {
        Class<?> type = Class.forName("cn.jiuwusan.jellyfin.player.SrtCueTimeline");
        java.lang.reflect.Method method = type.getDeclaredMethod("normalize", List.class); method.setAccessible(true);
        @SuppressWarnings("unchecked") List<CuesWithTiming> output = (List<CuesWithTiming>) method.invoke(null, input); return output;
    }
    private String textAt(List<CuesWithTiming> timeline, long ms) {
        List<String> output = new ArrayList<>();
        for (CuesWithTiming interval : timeline) if (interval.startTimeUs <= ms * 1000 && ms * 1000 < interval.endTimeUs)
            for (Cue cue : interval.cues) output.add(cue.text.toString());
        return String.join("\n", output);
    }
    void run() {
        Bundle result = new Bundle();
        try {
            List<CuesWithTiming> output = normalize(Arrays.asList(cue("C", 200, 450), cue("A", 100, 330), cue("B", 150, 550)));
            check(output.size() == 5, "Incorrect interval count");
            long last = Long.MIN_VALUE;
            for (CuesWithTiming part : output) { check(part.startTimeUs >= last && part.durationUs > 0, "Intervals overlap or are unordered"); last = part.endTimeUs; }
            check("A".equals(textAt(output, 120)), "First cue missing");
            check("A\nB\nC".equals(textAt(output, 250)), "Active overlapping cues lost or order changed");
            check("B\nC".equals(textAt(output, 330)), "End boundary includes expired cue");
            check("B".equals(textAt(output, 450)) && textAt(output, 550).isEmpty(), "End boundary or gap wrong");
            check("same\nsame".equals(textAt(normalize(Arrays.asList(cue("same", 0, 100), cue("same", 0, 100))), 50)), "Distinct identical cues were deduplicated");
            SpannableString styled = new SpannableString("colored");
            styled.setSpan(new ForegroundColorSpan(android.graphics.Color.YELLOW), 0, styled.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            Cue color = new Cue.Builder().setText(styled).build();
            List<CuesWithTiming> styledOutput = normalize(Arrays.asList(new CuesWithTiming(java.util.Collections.singletonList(color), 0, 100_000), cue("plain", 0, 100)));
            Spanned joined = (Spanned) styledOutput.get(0).cues.get(0).text;
            check(joined.getSpans(0, joined.length(), ForegroundColorSpan.class).length == 1 && styled.getSpans(0, styled.length(), ForegroundColorSpan.class).length == 1, "Style lost or input mutated");
            Cue positioned = new Cue.Builder().setText("positioned").setLine(.2f, Cue.LINE_TYPE_FRACTION).setPosition(.3f).build();
            List<CuesWithTiming> positions = normalize(Arrays.asList(new CuesWithTiming(java.util.Collections.singletonList(positioned), 0, 100_000), cue("plain", 0, 100)));
            check(positions.get(0).cues.size() == 2 && positions.get(0).cues.get(0) == positioned, "Positioned cue layout changed");
            check(normalize(java.util.Collections.singletonList(cue("zero", 10, 10))).isEmpty(), "Zero duration produced an interval");
            List<CuesWithTiming> crowded = new ArrayList<>(); for (int i = 0; i < 129; i++) crowded.add(cue("x", 0, 100));
            boolean rejected = false; try { normalize(crowded); } catch (java.lang.reflect.InvocationTargetException expected) { rejected = expected.getCause() instanceof IllegalArgumentException; }
            check(rejected, "Excessive overlap did not fail within budget");
            byte[] sample = ("1\n00:00:00,100 --> 00:00:00,330\nA\n\n2\n00:00:00,150 --> 00:00:00,550\nB\n\n3\n00:00:00,200 --> 00:00:00,450\nC\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            androidx.media3.common.Format format = new androidx.media3.common.Format.Builder().setSampleMimeType(androidx.media3.common.MimeTypes.APPLICATION_SUBRIP).build();
            androidx.media3.extractor.text.SubtitleParser parser = new SrtSubtitleParserFactory().create(format);
            List<CuesWithTiming> parsed = new ArrayList<>();
            parser.parse(sample, 0, sample.length, androidx.media3.extractor.text.SubtitleParser.OutputOptions.allCues(), parsed::add);
            check("A\nB\nC".equals(textAt(parsed, 250)) && parsed.size() == 6, "Factory parser did not emit snapshots");
            check(parsed.get(5).startTimeUs == 550_000 && parsed.get(5).cues.isEmpty(), "Final interval has no duration marker");
            List<CuesWithTiming> after = new ArrayList<>();
            parser.parse(sample, 0, sample.length, androidx.media3.extractor.text.SubtitleParser.OutputOptions.onlyCuesAfter(250_000), after::add);
            check(after.size() == 4 && after.get(0).startTimeUs == 200_000 && "A\nB\nC".equals(textAt(after, 250)), "Seek-start option lost active snapshots");
            List<CuesWithTiming> allAfterFirst = new ArrayList<>();
            parser.parse(sample, 0, sample.length, androidx.media3.extractor.text.SubtitleParser.OutputOptions.cuesAfterThenRemainingCuesBefore(250_000), allAfterFirst::add);
            check(allAfterFirst.size() == 6 && allAfterFirst.get(0).startTimeUs == 200_000 && allAfterFirst.get(5).startTimeUs == 150_000, "All-cue seek ordering changed");
            androidx.media3.common.Format assFormat = new androidx.media3.common.Format.Builder().setSampleMimeType(androidx.media3.common.MimeTypes.TEXT_SSA).build();
            check(new SrtSubtitleParserFactory().getCueReplacementBehavior(assFormat)
                    == new androidx.media3.extractor.text.DefaultSubtitleParserFactory().getCueReplacementBehavior(assFormat), "ASS semantics changed");
            List<CuesWithTiming> excessive = new ArrayList<>(); for (int i = 0; i <= 20_000; i++) excessive.add(cue("x", i, i + 1));
            rejected = false; try { normalize(excessive); } catch (java.lang.reflect.InvocationTargetException expected) { rejected = expected.getCause() instanceof IllegalArgumentException; }
            check(rejected, "Entry budget not enforced");
            try (SubtitleHandlingCheck.Fixture fixture = new SubtitleHandlingCheck.Fixture()) {
                JellyfinApi api = new JellyfinApi(new ConnectionSettings("http://fixture.invalid", "127.0.0.1", fixture.server.getLocalPort(), "", "", "fixture", "fixture", 1920, 4_000_000), "srt-seek-check"); api.login();
                JellyfinApi.Item item = new JellyfinApi.Item(new org.json.JSONObject().put("Id", "film").put("Name", "fixture").put("Type", "Movie"));
                api.stream(item, PlaybackPolicy.fromSettings(1920, 4_000_000, 1080, 2400));
                org.json.JSONArray profiles = fixture.last().getJSONObject("DeviceProfile").getJSONArray("SubtitleProfiles");
                boolean external = false, ttml = false;
                for (int i = 0; i < profiles.length(); i++) {
                    org.json.JSONObject profile = profiles.getJSONObject(i);
                    if ("srt".equals(profile.getString("Format")) || "subrip".equals(profile.getString("Format"))) {
                        check(!"Embed".equals(profile.getString("Method")), "Client still advertises native SRT Embed"); external |= "External".equals(profile.getString("Method"));
                    }
                    ttml |= "ttml".equals(profile.getString("Format")) && "Embed".equals(profile.getString("Method"));
                }
                check(external && ttml, "SRT external or other embedded format capability lost");
            }
            result.putString("result", "ALL_PASS: complete active intervals / half-open boundaries / unsorted input / duplicate identities / styles / position / zero duration / overlap budget"); test.finish(Activity.RESULT_OK, result);
        } catch (Throwable e) { result.putString("failure", android.util.Log.getStackTraceString(e)); test.finish(Activity.RESULT_CANCELED, result); }
    }
}
