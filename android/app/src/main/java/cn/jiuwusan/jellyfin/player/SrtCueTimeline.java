package cn.jiuwusan.jellyfin.player;

import android.text.SpannableStringBuilder;
import androidx.media3.common.C;
import androidx.media3.common.text.Cue;
import androidx.media3.extractor.text.CuesWithTiming;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Complete standalone SRT -> independently seekable, non-overlapping display snapshots. */
final class SrtCueTimeline {
    static final int MAX_PARTS = 20_000;
    private static final int MAX_ACTIVE_CUES = 128, MAX_REFERENCES = 200_000;
    private static final long MAX_TEXT_CHARS = 8_000_000;

    static List<CuesWithTiming> normalize(List<CuesWithTiming> input) {
        if (input.size() > MAX_PARTS) throw new IllegalArgumentException("Too many SRT entries");
        TreeMap<Long, Change> events = new TreeMap<>();
        int id = 0;
        for (CuesWithTiming part : input) {
            if (part.startTimeUs == C.TIME_UNSET || part.durationUs == C.TIME_UNSET || part.durationUs < 0)
                throw new IllegalArgumentException("SRT timing is not finite");
            if (part.cues.isEmpty() || part.durationUs == 0) continue;
            if (part.endTimeUs <= part.startTimeUs) throw new IllegalArgumentException("SRT timing overflow");
            Entry entry = new Entry(id++, part);
            events.computeIfAbsent(part.startTimeUs, key -> new Change()).start.add(entry);
            events.computeIfAbsent(part.endTimeUs, key -> new Change()).end.add(entry);
        }
        TreeSet<Entry> active = new TreeSet<>(Comparator.comparingLong((Entry e) -> e.part.startTimeUs)
                .thenComparing(Comparator.comparingLong((Entry e) -> e.part.durationUs).reversed()).thenComparingInt(e -> e.id));
        List<CuesWithTiming> result = new ArrayList<>();
        long previous = events.isEmpty() ? 0 : events.firstKey(), characters = 0;
        int activeCount = 0, references = 0;
        for (Map.Entry<Long, Change> event : events.entrySet()) {
            long now = event.getKey();
            if (now > previous && !active.isEmpty()) {
                List<Cue> cues = new ArrayList<>();
                for (Entry entry : active) for (Cue cue : entry.part.cues) {
                    cues.add(cue); characters += cue.text == null ? 0 : cue.text.length();
                }
                references += cues.size();
                if (references > MAX_REFERENCES || characters > MAX_TEXT_CHARS)
                    throw new IllegalArgumentException("SRT snapshot budget exceeded");
                result.add(new CuesWithTiming(joinDefaultLines(cues), previous, now - previous));
            }
            // Half-open intervals: expired cues leave before new cues enter at the same timestamp.
            for (Entry entry : event.getValue().end) { active.remove(entry); activeCount -= entry.part.cues.size(); }
            for (Entry entry : event.getValue().start) { active.add(entry); activeCount += entry.part.cues.size(); }
            if (activeCount > MAX_ACTIVE_CUES) throw new IllegalArgumentException("Too many simultaneous SRT cues");
            previous = now;
        }
        return result;
    }

    private static boolean defaultLayout(Cue cue) {
        return cue.text != null && cue.line == Cue.DIMEN_UNSET && cue.position == Cue.DIMEN_UNSET
                && cue.size == Cue.DIMEN_UNSET && !cue.windowColorSet;
    }

    private static List<Cue> joinDefaultLines(List<Cue> cues) {
        int count = 0;
        SpannableStringBuilder text = new SpannableStringBuilder();
        for (Cue cue : cues) if (defaultLayout(cue)) {
            if (count++ > 0) text.append('\n');
            text.append(cue.text);
        }
        if (count < 2) return cues;
        List<Cue> result = new ArrayList<>();
        boolean joined = false;
        for (Cue cue : cues) {
            if (!defaultLayout(cue)) result.add(cue);
            else if (!joined) { result.add(cue.buildUpon().setText(text).build()); joined = true; }
        }
        return result;
    }

    private static final class Change {
        final List<Entry> start = new ArrayList<>(), end = new ArrayList<>();
    }
    private static final class Entry {
        final int id; final CuesWithTiming part;
        Entry(int id, CuesWithTiming part) { this.id = id; this.part = part; }
    }
}
