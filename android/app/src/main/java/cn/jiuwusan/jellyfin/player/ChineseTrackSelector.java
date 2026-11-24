package cn.jiuwusan.jellyfin.player;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** A narrow refinement of a server default, never a replacement for server language selection. */
final class ChineseTrackSelector {
    private static final Pattern MANDARIN = Pattern.compile("\\b(mandarin|cmn)\\b|国语|國語|普通话|普通話");
    private static final Pattern CANTONESE = Pattern.compile("\\b(cantonese|yue)\\b|粤语|粵語|广东话|廣東話|潮汕|闽南|閩南");
    private static final Pattern SIMPLE = Pattern.compile("\\b(chs|hans|simplified)\\b|简体|簡體|简中|簡中|简英|簡英");
    private static final Pattern TRADITIONAL = Pattern.compile("\\b(cht|hant|traditional)\\b|繁体|繁體|繁中|繁英");
    private static final Pattern SPECIAL = Pattern.compile("\\b(commentary|sdh|description|forced)\\b|评论|評論|听障|聽障|视障|視障|口述|强制|強制");
    private static final Pattern ENGLISH = Pattern.compile("\\b(eng|english)\\b|英文");

    static boolean chinese(String language) {
        String normalized = LanguagePreferences.normalize(language);
        return "zho".equals(normalized) || "cmn".equals(normalized) || "yue".equals(normalized);
    }

    static Integer select(List<JellyfinApi.MediaTrack> tracks, int current, boolean subtitle) {
        int count = 0, first = Integer.MAX_VALUE;
        JellyfinApi.MediaTrack selected = null;
        for (JellyfinApi.MediaTrack track : tracks) {
            if (!chinese(track.language)) continue;
            count++;
            first = Math.min(first, track.index);
            if (track.index == current) selected = track;
        }
        // An index later in this language group might be a historical manual choice.
        if (count < 2 || current != first || selected == null || special(selected)) return null;
        int currentScore = score(selected, subtitle), best = currentScore;
        Integer result = null;
        boolean tied = false;
        for (JellyfinApi.MediaTrack track : tracks) {
            if (!chinese(track.language) || special(track) || subtitle && !track.available()) continue;
            int value = score(track, subtitle);
            if (value > best) { best = value; result = track.index; tied = false; }
            else if (value == best && result != null && track.index != result) tied = true;
        }
        return tied ? null : result;
    }

    private static boolean special(JellyfinApi.MediaTrack track) {
        return track.forced || track.hearingImpaired || track.commentary
                || SPECIAL.matcher(track.title.toLowerCase(Locale.ROOT)).find();
    }

    private static int score(JellyfinApi.MediaTrack track, boolean subtitle) {
        String title = track.title.toLowerCase(Locale.ROOT);
        String language = track.language.toLowerCase(Locale.ROOT).replace('_', '-');
        boolean mandarin = "cmn".equals(language) || MANDARIN.matcher(title).find();
        boolean cantonese = "yue".equals(language) || CANTONESE.matcher(title).find();
        boolean simple = language.contains("hans") || SIMPLE.matcher(title).find();
        boolean traditional = language.contains("hant") || TRADITIONAL.matcher(title).find();
        if (mandarin && cantonese || simple && traditional || ENGLISH.matcher(title).find()) return 0;
        if (!subtitle) return mandarin ? 1 : 0;
        if (!simple) return 0;
        return title.contains("双语") || title.contains("雙語") || title.contains("简英")
                || title.contains("簡英") || title.contains("bilingual") ? 1 : 2;
    }

    private ChineseTrackSelector() {}
}
