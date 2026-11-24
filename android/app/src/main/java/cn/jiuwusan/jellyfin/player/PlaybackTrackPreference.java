package cn.jiuwusan.jellyfin.player;

import java.util.List;
import java.util.Locale;

/** A manual choice's identity; a stream index is never portable across episodes. */
final class PlaybackTrackPreference {
    private final JellyfinApi.MediaTrack original;
    private PlaybackTrackPreference(JellyfinApi.MediaTrack original) { this.original = original; }

    static PlaybackTrackPreference of(JellyfinApi.MediaTrack track) {
        return track == null ? null : new PlaybackTrackPreference(track);
    }

    Integer match(List<JellyfinApi.MediaTrack> tracks, boolean subtitle) {
        String language = language(original.language), title = title(original.title);
        if (language.isEmpty() && title.isEmpty()) return null;
        Integer best = null;
        int bestScore = -1;
        boolean ambiguous = false;
        for (JellyfinApi.MediaTrack track : tracks) {
            if (subtitle && !track.available() || original.forced != track.forced
                    || original.hearingImpaired != track.hearingImpaired || original.commentary != track.commentary) continue;
            String candidateLanguage = language(track.language), candidateTitle = title(track.title);
            int score = 0;
            if (!language.isEmpty()) {
                if (candidateLanguage.isEmpty() || !language.split("-", 2)[0].equals(candidateLanguage.split("-", 2)[0])) continue;
                if (language.contains("-") && candidateLanguage.contains("-") && !language.equals(candidateLanguage)) continue;
                score += language.equals(candidateLanguage) ? 20 : 16;
            } else if (title.isEmpty() || !title.equals(candidateTitle)) continue;
            if (!title.isEmpty()) {
                if (!title.equals(candidateTitle)) continue;
                score += 32;
            }
            if (original.format.equalsIgnoreCase(track.format)) score += 2;
            if (score > bestScore) { bestScore = score; best = track.index; ambiguous = false; }
            else if (score == bestScore) ambiguous = true;
        }
        return ambiguous ? null : best;
    }

    private static String title(String value) { return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "); }
    private static String language(String value) {
        String[] parts = value.trim().toLowerCase(Locale.ROOT).replace('_', '-').split("-", 2);
        String base = LanguagePreferences.normalize(parts[0]);
        return base.isEmpty() ? "" : base + (parts.length == 2 ? "-" + parts[1] : "");
    }
}
