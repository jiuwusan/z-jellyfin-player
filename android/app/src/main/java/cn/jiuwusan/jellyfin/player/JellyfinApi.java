package cn.jiuwusan.jellyfin.player;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

final class JellyfinApi {
    static final String TAG = "JellyfinProbe";
    private static final int LIBRARY_PAGE_SIZE = 36;
    private final ConnectionSettings settings;
    private final OkHttpClient http;
    private final String device;
    private String token;
    private String userId;

    JellyfinApi(ConnectionSettings settings, String device) {
        this.settings = settings;
        this.device = device;
        Proxy proxy = new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(settings.host, settings.port));
        String proxyAuth = settings.proxyUser.isEmpty() ? null :
                Credentials.basic(settings.proxyUser, settings.proxyPassword, StandardCharsets.UTF_8);
        http = new OkHttpClient.Builder().proxy(proxy).followRedirects(false).followSslRedirects(false)
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
                .proxyAuthenticator((route, response) -> {
                    if (proxyAuth == null || response.request().header("Proxy-Authorization") != null) return null;
                    return response.request().newBuilder().header("Proxy-Authorization", proxyAuth).build();
                })
                .addInterceptor(chain -> {
                    Request request = chain.request();
                    if (!sameServer(request.url())) throw new IOException("媒体地址不属于当前 Jellyfin 服务器");
                    if ("http".equals(request.url().scheme()) && proxyAuth != null) {
                        request = request.newBuilder().header("Proxy-Authorization", proxyAuth).build();
                    }
                    return chain.proceed(request);
                }).build();
    }

    void login() throws Exception {
        JSONObject login = json("登录", url("Users/AuthenticateByName").build(),
                new JSONObject().put("Username", settings.user).put("Pw", settings.password));
        token = login.getString("AccessToken");
        userId = login.getJSONObject("User").getString("Id");
        Log.i(TAG, "Login successful");
    }

    List<Item> search(String query) throws Exception {
        JSONArray items = json("搜索", url("Items").addQueryParameter("userId", userId)
                .addQueryParameter("Recursive", "true").addQueryParameter("IncludeItemTypes", "Movie,Episode,Video,Series")
                .addQueryParameter("SearchTerm", query).addQueryParameter("Limit", "30").build(), null).getJSONArray("Items");
        return entries(items);
    }

    List<Item> libraries() throws Exception {
        return entries(json("读取媒体库", url("UserViews").addQueryParameter("userId", userId)
                .build(), null).getJSONArray("Items"));
    }

    private final java.util.Map<String, Integer> seriesYears = new java.util.concurrent.ConcurrentHashMap<>();

    List<Item> resumeItems() throws Exception {
        List<Item> items = entries(json("读取继续观看", url("Users/" + userId + "/Items/Resume")
                .addQueryParameter("MediaTypes", "Video").addQueryParameter("Limit", "12")
                .addQueryParameter("EnableUserData", "true").build(), null).getJSONArray("Items"));
        return displayYears(items);
    }

    private List<Item> displayYears(List<Item> items) {
        for (Item item : items) {
            if (item.seriesId.isEmpty()) continue;
            Integer year = seriesYears.get(item.seriesId);
            if (year == null) {
                try {
                    year = json("读取剧集年份", url("Items/" + item.seriesId)
                            .addQueryParameter("userId", userId).build(), null).optInt("ProductionYear");
                    seriesYears.put(item.seriesId, year);
                } catch (Exception ignored) { continue; }
            }
            item.seriesYear = year;
        }
        return items;
    }

    List<Item> nextItems() throws Exception {
        return entries(json("读取接下来", url("Shows/NextUp").addQueryParameter("userId", userId)
                .addQueryParameter("enableResumable", "false").addQueryParameter("enableUserData", "true")
                .addQueryParameter("limit", "16").build(), null).getJSONArray("Items"));
    }

    List<Item> latestItems() throws Exception {
        return displayYears(entries(new JSONArray(response("读取最近添加", url("Items/Latest").addQueryParameter("userId", userId)
                .addQueryParameter("IncludeItemTypes", "Movie,Episode,Video,Series")
                .addQueryParameter("groupItems", "true").addQueryParameter("limit", "12").build(), null))));
    }

    LibraryItemsPage libraryItems(Item parent, boolean catalog, int offset) throws Exception {
        HttpUrl.Builder request = url("Items").addQueryParameter("userId", userId)
                .addQueryParameter("StartIndex", Integer.toString(offset))
                .addQueryParameter("Limit", Integer.toString(LIBRARY_PAGE_SIZE));
        if (parent != null) request.addQueryParameter("ParentId", parent.id);
        if (parent != null && parent.collection && !catalog) {
            // A collection owns its member ordering. Never recursively unfold or collapse its children.
            request.addQueryParameter("Recursive", "false").addQueryParameter("CollapseBoxSetItems", "false");
        } else {
            request.addQueryParameter("SortBy", "DateCreated,SortName")
                    .addQueryParameter("SortOrder", "Descending,Ascending");
        }
        if (catalog) {
            String types = parent == null ? "Movie,Series"
                    : "tvshows".equals(parent.collectionType) ? "Series"
                    : "movies".equals(parent.collectionType) ? "Movie"
                    : "boxsets".equals(parent.collectionType) ? "BoxSet" : "Movie,Series,Video";
            request.addQueryParameter("Recursive", "true")
                    .addQueryParameter("IncludeItemTypes", types)
                    .addQueryParameter("Fields", "ItemCounts");
        }
        JSONObject result = json("读取媒体库", request.build(), null);
        List<Item> items = entries(result.getJSONArray("Items"));
        int total = result.optInt("TotalRecordCount", -1);
        boolean more = !items.isEmpty()
                && (total >= 0 ? offset + items.size() < total : items.size() == LIBRARY_PAGE_SIZE);
        return new LibraryItemsPage(items, more);
    }

    static final class LibraryItemsPage {
        final List<Item> items;
        final boolean more;

        LibraryItemsPage(List<Item> items, boolean more) {
            this.items = items;
            this.more = more;
        }
    }

    Item details(Item item) throws Exception {
        return details(item.id);
    }

    Item details(String id) throws Exception {
        return new Item(json("读取影片详情", url("Items/" + id)
                .addQueryParameter("userId", userId).build(), null));
    }

    Item seriesPlaybackTarget(Item series) throws Exception {
        List<Item> episodes = episodes(series, null);
        Item target = null;
        for (Item episode : episodes) {
            if (episode.resumeMs > 0) return episode;
            if (target == null && !episode.played) target = episode;
        }
        return target != null ? target : episodes.isEmpty() ? null : episodes.get(0);
    }

    Bitmap artwork(Item item, boolean wide, String seriesBackdropId, boolean originalWidth) throws IOException {
        int backdropWidth = originalWidth ? 0 : 640;
        int posterWidth = originalWidth ? 0 : 420;
        if (!seriesBackdropId.isEmpty()) {
            try {
                Bitmap backdrop = image(seriesBackdropId, "Backdrop/0", backdropWidth);
                if (backdrop != null) return backdrop;
            } catch (IOException ignored) {
                // The episode's own artwork may still be available.
            }
        }
        if (wide && item.hasBackdrop) {
            Bitmap backdrop = image(item.id, "Backdrop/0", backdropWidth);
            if (backdrop != null) return backdrop;
        }
        if (item.hasPoster) {
            Bitmap poster = image(item.id, "Primary", posterWidth);
            if (poster != null) return poster;
        }
        return item.seriesId.isEmpty() ? null : image(item.seriesId, "Primary", posterWidth);
    }

    private Bitmap image(String id, String type, int width) throws IOException {
        HttpUrl.Builder imageUrl = url("Items/" + id + "/Images/" + type).addQueryParameter("quality", "80");
        if (width > 0) imageUrl.addQueryParameter("maxWidth", Integer.toString(width));
        Request request = new Request.Builder().url(imageUrl.build())
                .header("Authorization", authorization()).build();
        Call call = http.newCall(request);
        call.timeout().timeout(8, TimeUnit.SECONDS);
        try (Response response = call.execute()) {
            return response.isSuccessful() && response.body() != null
                    ? BitmapFactory.decodeStream(response.body().byteStream()) : null;
        }
    }

    List<Item> seasons(Item show) throws Exception {
        return entries(json("读取季列表", url("Shows/" + show.id + "/Seasons")
                .addQueryParameter("userId", userId).build(), null).getJSONArray("Items"));
    }

    List<Item> episodes(Item show, Item season) throws Exception {
        HttpUrl.Builder request = url("Shows/" + show.id + "/Episodes")
                .addQueryParameter("userId", userId).addQueryParameter("EnableUserData", "true");
        if (season != null) request.addQueryParameter("SeasonId", season.id);
        JSONArray items = json("读取剧集", request.build(), null).getJSONArray("Items");
        return entries(items);
    }

    List<Item> adjacentEpisodes(Item episode) throws Exception {
        return entries(json("读取相邻剧集", url("Shows/" + episode.seriesId + "/Episodes")
                .addQueryParameter("userId", userId).addQueryParameter("EnableUserData", "true")
                .addQueryParameter("IsMissing", "false").addQueryParameter("AdjacentTo", episode.id)
                .build(), null).getJSONArray("Items"));
    }

    private static List<Item> entries(JSONArray items) throws Exception {
        List<Item> results = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            results.add(new Item(items.getJSONObject(i)));
        }
        return results;
    }

    Stream stream(Item item, PlaybackPolicy policy) throws Exception {
        return stream(item, policy, null, null);
    }

    Stream stream(Item item, PlaybackPolicy policy, String requestedSource, Integer audioIndex) throws Exception {
        JSONObject initial = playbackInfo(item, requestedSource, false, false, audioIndex, policy);
        JSONArray sources = initial.optJSONArray("MediaSources");
        if (sources == null || sources.length() == 0) throw new IOException("没有可用片源");
        JSONObject source = requestedSource == null ? sources.getJSONObject(0) : findSource(initial, requestedSource);
        String sourceId = source.getString("Id");
        JSONObject video = firstVideo(source);
        int width = video.optInt("Width");
        int height = video.optInt("Height");
        boolean constrained = policy.requiresTranscode(width, height, source.optLong("Bitrate", video.optLong("BitRate")));
        int defaultSubtitle = source.optInt("DefaultSubtitleStreamIndex", -1);
        boolean hls = audioIndex != null || constrained
                || !source.optBoolean("SupportsDirectPlay") && !source.optBoolean("SupportsDirectStream");
        JSONObject info = hls ? playbackInfo(item, sourceId, constrained || audioIndex != null, true, audioIndex, policy) : initial;
        if (hls) {
            source = findSource(info, sourceId);
        }
        String session = info.getString("PlaySessionId");
        HttpUrl streamUrl;
        if (hls) {
            String path = source.optString("TranscodingUrl");
            if (path.isEmpty() || !source.optBoolean("SupportsTranscoding")) throw new IOException("服务端没有提供转码流");
            // A server with a base path may return a relative API path or an absolute path.
            HttpUrl resolved = resolveServerUrl(path);
            if (resolved == null || !resolved.encodedPath().endsWith(".m3u8")) {
                throw new IOException("服务端没有提供可用的 HLS 地址");
            }
            HttpUrl.Builder request = resolved.newBuilder();
            if (audioIndex != null) {
                for (String key : resolved.queryParameterNames()) {
                    if ("AudioStreamIndex".equalsIgnoreCase(key)) request.removeAllQueryParameters(key);
                }
                request.setQueryParameter("AudioStreamIndex", audioIndex.toString());
            }
            if (policy.maxWidth > 0) {
                request.removeAllQueryParameters("Width").removeAllQueryParameters("Height")
                        .setQueryParameter("MaxWidth", Integer.toString(policy.maxWidth))
                        .setQueryParameter("MaxHeight", Integer.toString(policy.maxHeight));
            }
            if (policy.totalBitrate > 0) {
                int audio = policy.audioBitrate();
                request.setQueryParameter("VideoBitRate", Integer.toString(policy.totalBitrate - audio))
                        .setQueryParameter("AudioBitRate", Integer.toString(audio))
                        .setQueryParameter("AudioCodec", "aac").setQueryParameter("AllowAudioStreamCopy", "false");
            }
            if (constrained || policy.totalBitrate > 0) {
                request.setQueryParameter("VideoCodec", "h264")
                        .setQueryParameter("MaxFramerate", "60").setQueryParameter("MaxVideoBitDepth", "8")
                        .setQueryParameter("AllowVideoStreamCopy", "false")
                        .setQueryParameter("EnableAutoStreamCopy", "false");
            }
            streamUrl = request.build();
        } else {
            streamUrl = url("Videos/" + item.id + "/stream")
                    .addQueryParameter("Static", Boolean.toString(source.optBoolean("SupportsDirectPlay")))
                    .addQueryParameter("MediaSourceId", sourceId).addQueryParameter("PlaySessionId", session)
                    .addQueryParameter("DeviceId", device).build();
        }
        Log.i(TAG, "Playback source=" + width + "x" + height + " constrained=" + constrained
                + " HLS=" + hls + " maxWidth=" + policy.maxWidth + " maxHeight=" + policy.maxHeight
                + " totalBudget=" + policy.totalBitrate);
        int resolvedAudio = source.optInt("DefaultAudioStreamIndex", -1);
        if (hls) {
            for (String key : streamUrl.queryParameterNames()) {
                if ("AudioStreamIndex".equalsIgnoreCase(key)) {
                    try {
                        resolvedAudio = Integer.parseInt(streamUrl.queryParameter(key));
                    } catch (NumberFormatException ignored) {
                        // The server's default remains usable when the URL has no valid index.
                    }
                    break;
                }
            }
        }
        return new Stream(item.id, sourceId, session, streamUrl, hls, constrained, resolvedAudio, defaultSubtitle,
                mediaTracks(item.id, source, "Audio"), mediaTracks(item.id, source, "Subtitle"));
    }

    private HttpUrl resolveServerUrl(String path) {
        HttpUrl resolved = path.startsWith("/") && !path.startsWith("//")
                && !path.startsWith(settings.server.encodedPath()) ? settings.server.resolve(path.substring(1))
                : settings.server.resolve(path);
        return resolved != null && sameServer(resolved) ? resolved : null;
    }

    private List<MediaTrack> mediaTracks(String itemId, JSONObject source, String type) {
        List<MediaTrack> result = new ArrayList<>();
        JSONArray tracks = source.optJSONArray("MediaStreams");
        for (int i = 0; tracks != null && i < tracks.length(); i++) {
            JSONObject track = tracks.optJSONObject(i);
            if (track == null || !type.equals(track.optString("Type"))) continue;
            int index = track.optInt("Index", -1);
            if (index < 0) continue;
            String language = track.optString("Language", "");
            String title = track.optString("DisplayTitle", track.optString("Title", ""));
            String codec = track.optString("Codec", "").toLowerCase(Locale.ROOT);
            HttpUrl uri = null;
            String format = codec;
            boolean external = track.optBoolean("IsExternal");
            if ("Subtitle".equals(type)) {
                if ("subrip".equals(format)) format = "srt";
                if ("webvtt".equals(format)) format = "vtt";
                boolean nativeText = "srt".equals(format) || "vtt".equals(format)
                        || "ass".equals(format) || "ssa".equals(format);
                if (!nativeText && !track.optBoolean("IsTextSubtitleStream")) continue;
                if (!nativeText) format = "vtt";
                String delivery = track.optString("DeliveryUrl", "");
                HttpUrl delivered = delivery.isEmpty() ? null : resolveServerUrl(delivery);
                if (delivered != null && delivered.encodedPath().endsWith("/Stream." + format)
                        && delivered.queryParameter("startPositionTicks") == null) {
                    HttpUrl.Builder subtitleUrl = delivered.newBuilder();
                    for (String key : delivered.queryParameterNames()) {
                        if ("api_key".equalsIgnoreCase(key) || "apiKey".equalsIgnoreCase(key)) {
                            subtitleUrl.removeAllQueryParameters(key);
                        }
                    }
                    uri = subtitleUrl.build();
                } else {
                    uri = url("Videos").addPathSegment(itemId).addPathSegment(source.optString("Id"))
                            .addPathSegment("Subtitles").addPathSegment(Integer.toString(index))
                            .addPathSegment("Stream." + format).build();
                }
            }
            String label = title.isEmpty() ? language : title;
            if (label.isEmpty()) label = (uri == null ? "音轨 " : "字幕 ") + index;
            if (uri != null) label += external ? " · 外挂" : " · 内嵌";
            result.add(new MediaTrack(index, label, language, format, uri));
        }
        return result;
    }

    void report(Stream stream, String event, long positionMs, boolean paused) throws Exception {
        String route = event.isEmpty() ? "Sessions/Playing" : "Sessions/Playing/" + event;
        JSONObject body = new JSONObject().put("ItemId", stream.itemId).put("MediaSourceId", stream.sourceId)
                .put("PlaySessionId", stream.session).put("PositionTicks", Math.max(0, positionMs) * 10_000)
                .put("IsPaused", paused).put("CanSeek", true)
                .put("PlayMethod", stream.hls ? "Transcode" : "DirectPlay");
        json("播放状态", url(route).build(), body);
    }

    private static JSONObject firstVideo(JSONObject source) throws Exception {
        JSONArray streams = source.optJSONArray("MediaStreams");
        for (int i = 0; streams != null && i < streams.length(); i++) {
            JSONObject stream = streams.getJSONObject(i);
            if ("Video".equals(stream.optString("Type"))) return stream;
        }
        return new JSONObject();
    }

    private static JSONObject findSource(JSONObject info, String id) throws Exception {
        JSONArray sources = info.getJSONArray("MediaSources");
        for (int i = 0; i < sources.length(); i++) {
            JSONObject source = sources.getJSONObject(i);
            if (id.equals(source.optString("Id"))) return source;
        }
        throw new IOException("服务端未为选中的片源返回转码信息");
    }

    private JSONObject playbackInfo(Item item, String source, boolean constrained, boolean clientSubtitles,
                                    Integer audioIndex, PlaybackPolicy policy) throws Exception {
        JSONObject body = new JSONObject().put("UserId", userId).put("DeviceProfile", profile());
        if (policy.totalBitrate > 0) body.put("MaxStreamingBitrate", policy.totalBitrate);
        if (source != null) body.put("MediaSourceId", source);
        if (audioIndex != null) body.put("AudioStreamIndex", audioIndex);
        if (clientSubtitles) body.put("SubtitleStreamIndex", -1);
        if (constrained) {
            body.put("EnableDirectPlay", false).put("EnableDirectStream", false)
                    .put("EnableTranscoding", true).put("AllowVideoStreamCopy", false).put("AllowAudioStreamCopy", false);
        }
        return json("播放协商", url("Items/" + item.id + "/PlaybackInfo").build(), body);
    }

    private static JSONObject profile() throws Exception {
        JSONObject direct = new JSONObject().put("Type", "Video").put("Container", "mp4,mkv")
                .put("VideoCodec", "h264,hevc").put("AudioCodec", "aac,mp3,ac3,eac3,opus");
        JSONObject transcode = new JSONObject().put("Type", "Video").put("Context", "Streaming")
                .put("Protocol", "hls").put("Container", "ts").put("VideoCodec", "h264").put("AudioCodec", "aac");
        JSONArray subtitles = new JSONArray();
        for (String format : new String[] { "srt", "subrip", "vtt", "webvtt", "ass", "ssa" }) {
            subtitles.put(new JSONObject().put("Format", format).put("Method", "External"));
        }
        return new JSONObject().put("Name", "Jellyfin Playback Probe")
                .put("MaxStreamingBitrate", 120_000_000).put("MaxStaticBitrate", 120_000_000)
                .put("DirectPlayProfiles", new JSONArray().put(direct))
                .put("TranscodingProfiles", new JSONArray().put(transcode)).put("SubtitleProfiles", subtitles);
    }

    OkHttpClient client() { return http; }

    String authorization() {
        return "MediaBrowser Client=\"Jellyfin Playback Probe\", Device=\"Android\", DeviceId=\"" + device
                + "\", Version=\"0.3.0\"" + (token == null ? "" : ", Token=\"" + token + "\"");
    }

    private HttpUrl.Builder url(String path) { return settings.server.newBuilder().addPathSegments(path); }

    private boolean sameServer(HttpUrl url) {
        return url.scheme().equals(settings.server.scheme()) && url.host().equals(settings.server.host())
                && url.port() == settings.server.port() && url.encodedPath().startsWith(settings.server.encodedPath());
    }

    private JSONObject json(String operation, HttpUrl url, JSONObject body) throws Exception {
        String text = response(operation, url, body);
        return text.isEmpty() ? new JSONObject() : new JSONObject(text);
    }

    private String response(String operation, HttpUrl url, JSONObject body) throws IOException {
        Request.Builder request = new Request.Builder().url(url).header("Authorization", authorization())
                .header("Accept", "application/json");
        if (body != null) request.post(RequestBody.create(body.toString(), MediaType.get("application/json")));
        try (Response response = http.newCall(request.build()).execute()) {
            if (response.code() == 407) throw new IOException("HTTP 代理认证失败，请检查代理账号和密码。");
            if (!response.isSuccessful()) throw new IOException(operation + "失败，HTTP " + response.code());
            return response.body() == null ? "" : response.body().string();
        }
    }

    static final class Item {
        final String id, title, seriesName, seriesId, seriesBackdropId, overview, type, collectionType;
        final boolean series, collection, folder, hasPoster, hasBackdrop, played;
        final long durationMs, resumeMs;
        final int year, index, season, childCount;
        int seriesYear;

        int displayYear() { return seriesId.isEmpty() ? year : seriesYear; }

        Item(JSONObject entry) throws Exception {
            id = entry.getString("Id");
            type = entry.optString("Type");
            String name = entry.getString("Name");
            index = entry.optInt("IndexNumber");
            season = entry.optInt("ParentIndexNumber");
            title = "Episode".equals(type) && index > 0 ? "第 " + index + " 集 · " + name : name;
            series = "Series".equals(type);
            collection = "BoxSet".equals(type);
            folder = collection || entry.optBoolean("IsFolder") || "CollectionFolder".equals(type) || "UserView".equals(type);
            childCount = Math.max(-1, entry.optInt("ChildCount", -1));
            seriesName = entry.optString("SeriesName", "");
            seriesId = entry.optString("SeriesId", "");
            collectionType = entry.optString("CollectionType", "");
            overview = entry.optString("Overview", "");
            year = entry.optInt("ProductionYear");
            durationMs = Math.max(0, entry.optLong("RunTimeTicks")) / 10_000;
            JSONObject data = entry.optJSONObject("UserData");
            played = data != null && data.optBoolean("Played");
            long position = data == null || played ? 0 : data.optLong("PlaybackPositionTicks");
            resumeMs = Math.max(0, position) / 10_000;
            JSONObject tags = entry.optJSONObject("ImageTags");
            hasPoster = tags != null && tags.has("Primary");
            JSONArray backdrops = entry.optJSONArray("BackdropImageTags");
            hasBackdrop = backdrops != null && backdrops.length() > 0;
            JSONArray parentBackdrops = entry.optJSONArray("ParentBackdropImageTags");
            String parentBackdropId = entry.optString("ParentBackdropItemId", "");
            seriesBackdropId = "Episode".equals(type) && !seriesId.isEmpty()
                    && seriesId.equalsIgnoreCase(parentBackdropId)
                    && parentBackdrops != null && parentBackdrops.length() > 0 ? seriesId : "";
        }
    }

    static final class Stream {
        final String itemId, sourceId, session;
        final HttpUrl url;
        final boolean hls, constrained;
        final int audioIndex, defaultSubtitleIndex;
        final List<MediaTrack> audio, subtitles;
        Stream(String itemId, String sourceId, String session, HttpUrl url, boolean hls, boolean constrained,
               int audioIndex, int defaultSubtitleIndex, List<MediaTrack> audio, List<MediaTrack> subtitles) {
            this.itemId = itemId; this.sourceId = sourceId; this.session = session;
            this.url = url; this.hls = hls; this.constrained = constrained;
            this.audioIndex = audioIndex; this.defaultSubtitleIndex = defaultSubtitleIndex;
            this.audio = audio; this.subtitles = subtitles;
        }
    }

    static final class MediaTrack {
        final int index;
        final String label, language, format;
        final HttpUrl subtitleUrl;

        MediaTrack(int index, String label, String language, String format, HttpUrl subtitleUrl) {
            this.index = index;
            this.label = label;
            this.language = language;
            this.format = format;
            this.subtitleUrl = subtitleUrl;
        }
    }
}
