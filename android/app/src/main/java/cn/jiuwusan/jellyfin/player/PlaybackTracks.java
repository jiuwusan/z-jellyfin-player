package cn.jiuwusan.jellyfin.player;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import java.util.ArrayList;
import java.util.List;

@UnstableApi
final class PlaybackTracks {
    static final int AUTO = -2;
    static final int OFF = -1;

    static MediaItem mediaItem(JellyfinApi.Stream stream) {
        MediaItem.Builder item = new MediaItem.Builder().setUri(stream.url.toString());
        if (stream.hls) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        List<MediaItem.SubtitleConfiguration> subtitles = new ArrayList<>();
        for (JellyfinApi.MediaTrack track : stream.subtitles) {
            if (!"External".equals(track.deliveryMethod) || !track.available()) continue;
            String mime = subtitleMime(track.format);
            subtitles.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(track.subtitleUrl.toString()))
                    .setId(subtitleId(track.index)).setLabel(track.label).setLanguage(track.language)
                    .setMimeType(mime).build());
        }
        return item.setSubtitleConfigurations(subtitles).build();
    }

    static List<LocalTrack> supported(Tracks tracks, int type) {
        List<LocalTrack> result = new ArrayList<>();
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != type) continue;
            for (int i = 0; i < group.length; i++) {
                if (group.isTrackSupported(i)) result.add(new LocalTrack(group, i, result.size() + 1));
            }
        }
        return result;
    }

    static void select(Player player, LocalTrack track) {
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(track.group.getType(), false).setOverrideForType(track.override()).build());
    }

    static void setSubtitle(Player player, Tracks tracks, JellyfinApi.Stream stream, int selection, LocalTrack local) {
        int preferred = selection == AUTO ? stream.defaultSubtitleIndex : selection;
        TrackSelectionParameters.Builder parameters = player.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT,
                        stream.burnedSubtitleIndex >= 0 || selection == OFF || local == null && preferred < 0);
        if (local != null && stream.burnedSubtitleIndex < 0) {
            parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(local.override());
        } else if (preferred >= 0 && stream.burnedSubtitleIndex < 0) {
            JellyfinApi.MediaTrack selected = subtitleTrack(stream.subtitles, preferred);
            boolean matched = false;
            if (selected != null && selected.available()) {
                if ("Embed".equals(selected.deliveryMethod) && selected.embeddedStreamIndex < tracks.getGroups().size()) {
                    Tracks.Group group = tracks.getGroups().get(selected.embeddedStreamIndex);
                    if (group.getType() == C.TRACK_TYPE_TEXT) for (int i = 0; i < group.length; i++) {
                        if (group.isTrackSupported(i)) {
                            parameters.setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(), i));
                            matched = true;
                            break;
                        }
                    }
                } else if ("External".equals(selected.deliveryMethod)) {
                    String id = subtitleId(preferred);
                    for (LocalTrack option : supported(tracks, C.TRACK_TYPE_TEXT)) {
                        String formatId = option.format.id;
                        // Media3 prefixes a subtitle ID with its merged media period index.
                        if (formatId != null && id.equals(formatId.substring(formatId.lastIndexOf(':') + 1))) {
                            parameters.setOverrideForType(option.override());
                            matched = true;
                            break;
                        }
                    }
                }
            }
            parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, selected == null || !selected.available()
                    || !tracks.getGroups().isEmpty() && !matched);
        }
        player.setTrackSelectionParameters(parameters.build());
    }

    static String subtitleMime(String format) {
        switch (format) {
            case "srt": return MimeTypes.APPLICATION_SUBRIP;
            case "vtt": return MimeTypes.TEXT_VTT;
            case "ass":
            case "ssa": return MimeTypes.TEXT_SSA;
            case "ttml": return MimeTypes.APPLICATION_TTML;
            default: return null;
        }
    }

    static JellyfinApi.MediaTrack subtitleTrack(List<JellyfinApi.MediaTrack> tracks, int index) {
        for (JellyfinApi.MediaTrack track : tracks) if (track.index == index) return track;
        return null;
    }

    static int burnInIndex(List<JellyfinApi.MediaTrack> tracks, int defaultIndex, int selection) {
        int desired = selection == AUTO ? defaultIndex : selection;
        for (JellyfinApi.MediaTrack track : tracks) if (track.index == desired && track.burnIn) return desired;
        return -1;
    }

    private static String subtitleId(int index) {
        return "jellyfin-subtitle-" + index;
    }

    static final class LocalTrack {
        final Tracks.Group group;
        final int index;
        final Format format;
        final String label;

        LocalTrack(Tracks.Group group, int index, int number) {
            this.group = group;
            this.index = index;
            format = group.getTrackFormat(index);
            String name = format.label;
            if (name == null || name.isEmpty()) name = format.language;
            if (name == null || name.isEmpty()) name = group.getType() == C.TRACK_TYPE_AUDIO ? "音轨" : "内嵌字幕";
            String channels = format.channelCount > 0 ? " · " + format.channelCount + " 声道" : "";
            label = name + channels + "（" + number + "）";
        }

        boolean selected() {
            return group.isTrackSelected(index);
        }

        TrackSelectionOverride override() {
            return new TrackSelectionOverride(group.getMediaTrackGroup(), index);
        }
    }
}
