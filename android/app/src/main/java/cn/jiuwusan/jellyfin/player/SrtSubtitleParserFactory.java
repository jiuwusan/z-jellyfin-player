package cn.jiuwusan.jellyfin.player;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Consumer;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.extractor.text.subrip.SubripParser;
import java.util.ArrayList;
import java.util.List;

/** Other subtitle formats retain Media3's parser and replacement semantics. */
final class SrtSubtitleParserFactory implements SubtitleParser.Factory {
    private final DefaultSubtitleParserFactory delegate = new DefaultSubtitleParserFactory();
    @Override public boolean supportsFormat(Format format) { return delegate.supportsFormat(format); }
    @Override public int getCueReplacementBehavior(Format format) {
        return MimeTypes.APPLICATION_SUBRIP.equals(format.sampleMimeType)
                ? Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE : delegate.getCueReplacementBehavior(format);
    }
    @Override public SubtitleParser create(Format format) {
        return MimeTypes.APPLICATION_SUBRIP.equals(format.sampleMimeType) ? new SnapshotParser() : delegate.create(format);
    }
    private static final class SnapshotParser implements SubtitleParser {
        private final SubripParser parser = new SubripParser();
        @Override public int getCueReplacementBehavior() { return Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE; }
        @Override public void reset() { parser.reset(); }
        @Override public void parse(byte[] data, int offset, int length, OutputOptions options, Consumer<CuesWithTiming> output) {
            List<CuesWithTiming> parsed = new ArrayList<>();
            parser.parse(data, offset, length, OutputOptions.allCues(), part -> {
                if (parsed.size() >= SrtCueTimeline.MAX_PARTS) throw new IllegalArgumentException("Too many SRT entries");
                parsed.add(part);
            });
            List<CuesWithTiming> timeline = SrtCueTimeline.normalize(parsed);
            if (!timeline.isEmpty()) {
                // Media3 1.8 estimates a standalone source's duration from the last sample START.
                // A terminal empty sample keeps the final display interval seekable until its real end.
                timeline.add(new CuesWithTiming(java.util.Collections.emptyList(), timeline.get(timeline.size() - 1).endTimeUs, 0));
            }
            List<CuesWithTiming> before = new ArrayList<>();
            for (CuesWithTiming part : timeline) {
                if (options.startTimeUs == C.TIME_UNSET || part.endTimeUs >= options.startTimeUs) output.accept(part);
                else if (options.outputAllCues) before.add(part);
            }
            for (CuesWithTiming part : before) output.accept(part);
        }
    }
}
