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
            String mime;
            switch (track.format) {
                case "srt": mime = MimeTypes.APPLICATION_SUBRIP; break;
                case "ass":
                case "ssa": mime = MimeTypes.TEXT_SSA; break;
                default: mime = MimeTypes.TEXT_VTT; break;
            }
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
                        selection == OFF || selection == AUTO && preferred < 0 && stream.hls);
        if (local != null) {
            parameters.setOverrideForType(local.override());
        } else if (preferred >= 0) {
            String id = subtitleId(preferred);
            for (LocalTrack option : supported(tracks, C.TRACK_TYPE_TEXT)) {
                String formatId = option.format.id;
                // Media3 prefixes a subtitle ID with its merged media period index.
                if (formatId != null && id.equals(formatId.substring(formatId.lastIndexOf(':') + 1))) {
                    parameters.setOverrideForType(option.override());
                    break;
                }
            }
        }
        player.setTrackSelectionParameters(parameters.build());
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
