package cn.jiuwusan.jellyfin.playbackprobe;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@UnstableApi
public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ConnectionSettings settings;
    private JellyfinApi api;
    private JellyfinApi.Stream stream;
    private ExoPlayer player;
    private TextView status;
    private int page;
    private String device;
    private Runnable backAction;
    private boolean playbackStarted;
    private String query = "魅影神捕";

    private final Runnable progress = new Runnable() {
        @Override public void run() {
            if (player == null) return;
            report("Progress");
            ui.postDelayed(this, 10_000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        device = getPreferences(0).getString("device", null);
        if (device == null) {
            device = UUID.randomUUID().toString();
            getPreferences(0).edit().putString("device", device).apply();
        }
        try {
            settings = ConnectionSettings.load(this);
            if (settings != null) connect(settings, false);
            else settingsPage("");
        } catch (Exception error) {
            settingsPage("无法读取已保存的配置，请重新填写。");
        }
    }

    private LinearLayout panel(String title, boolean scroll, Runnable back) {
        backAction = back;
        page++;
        LinearLayout root = column();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(16) + insets.getSystemWindowInsetLeft(), dp(10) + insets.getSystemWindowInsetTop(),
                    dp(16) + insets.getSystemWindowInsetRight(), dp(10) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();
        LinearLayout content = root;
        if (scroll) {
            ScrollView scroller = new ScrollView(this);
            content = column();
            scroller.addView(content);
            root.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        text(content, title).setTextSize(23);
        return content;
    }

    private void settingsPage(String message) {
        LinearLayout view = panel("连接设置", true, api == null ? null : this::homePage);
        ConnectionSettings old = settings;
        EditText server = field(view, "Jellyfin 服务器地址", old == null ? "" : old.server.toString(), false);
        EditText host = field(view, "HTTP 代理地址", old == null ? "" : old.host, false);
        EditText port = field(view, "代理端口", old == null ? "" : Integer.toString(old.port), false);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText proxyUser = field(view, "代理用户名", old == null ? "" : old.proxyUser, false);
        EditText proxyPassword = field(view, "代理密码", old == null ? "" : old.proxyPassword, true);
        EditText username = field(view, "Jellyfin 账号", old == null ? "" : old.user, false);
        EditText password = field(view, "Jellyfin 密码", old == null ? "" : old.password, true);
        status = text(view, message);
        button(view, "保存并连接", () -> {
            try {
                connect(new ConnectionSettings(value(server), value(host), Integer.parseInt(value(port)),
                        value(proxyUser), value(proxyPassword), value(username), value(password)), true);
            } catch (IllegalArgumentException error) {
                status.setText("请检查服务器地址、代理端口和账号。");
            }
        });
        if (api != null) button(view, "返回首页", this::homePage);
    }

    private void connect(ConnectionSettings candidate, boolean save) {
        LinearLayout view = panel("正在连接 Jellyfin", false, () -> settingsPage(""));
        int expected = page;
        status = text(view, "通过家庭 HTTP 代理连接。");
        button(view, "连接设置", () -> settingsPage(""));
        worker.execute(() -> {
            try {
                JellyfinApi connected = new JellyfinApi(candidate, device);
                connected.login();
                if (save) candidate.save(this);
                onPage(expected, () -> {
                    settings = candidate;
                    api = connected;
                    homePage();
                });
            } catch (Exception error) {
                onPage(expected, () -> {
                    settings = candidate;
                    settingsPage(message(error, "连接失败"));
                });
            }
        });
    }

    private void homePage() {
        stopPlayback();
        LinearLayout view = panel("媒体库", true, null);
        button(view, "搜索影片", this::searchPage);
        section(view, "继续观看");
        LinearLayout resume = column();
        view.addView(resume);
        section(view, "我的媒体库");
        LinearLayout libraries = column();
        view.addView(libraries);
        button(view, "连接设置", () -> settingsPage(""));
        fillHome(resume, true);
        fillHome(libraries, false);
    }

    private void fillHome(LinearLayout list, boolean resume) {
        int expected = page;
        TextView state = text(list, "正在加载…");
        JellyfinApi connection = api;
        worker.execute(() -> {
            try {
                List<JellyfinApi.Item> items = resume ? connection.resumeItems() : connection.libraries();
                onPage(expected, () -> {
                    list.removeView(state);
                    int count = 0;
                    for (JellyfinApi.Item item : items) {
                        if (resume && item.resumeMs <= 0) continue;
                        count++;
                        String title = resume ? displayTitle(item) + " · " + time(item.resumeMs) : item.title;
                        button(list, title, () -> {
                            if (resume) detailsPage(item, this::homePage);
                            else libraryPage(item, 0, this::homePage);
                        });
                    }
                    if (count == 0) text(list, resume ? "暂无未看完的影片。" : "当前账号没有媒体库。");
                });
            } catch (Exception error) {
                onPage(expected, () -> {
                    state.setText(message(error, resume ? "读取继续观看失败" : "读取媒体库失败"));
                    button(list, "重试", () -> {
                        list.removeAllViews();
                        fillHome(list, resume);
                    });
                });
            }
        });
    }

    private void libraryPage(JellyfinApi.Item library, int offset, Runnable back) {
        LinearLayout view = panel(library.title, true, back);
        button(view, "返回", back);
        int expected = page;
        TextView state = text(view, "正在加载…");
        JellyfinApi connection = api;
        worker.execute(() -> {
            try {
                List<JellyfinApi.Item> items = connection.children(library, offset);
                onPage(expected, () -> {
                    view.removeView(state);
                    if (offset > 0) button(view, "上一页", () -> libraryPage(library, Math.max(0, offset - 36), back));
                    for (JellyfinApi.Item item : items) {
                        button(view, displayTitle(item), () -> openItem(item, () -> libraryPage(library, offset, back)));
                    }
                    if (items.isEmpty()) text(view, "没有更多影片。");
                    if (items.size() == 36) button(view, "下一页", () -> libraryPage(library, offset + 36, back));
                });
            } catch (Exception error) {
                onPage(expected, () -> state.setText(message(error, "读取媒体库失败")));
            }
        });
    }

    private void openItem(JellyfinApi.Item item, Runnable back) {
        if (item.folder && !item.series) libraryPage(item, 0, back);
        else detailsPage(item, back);
    }

    private void detailsPage(JellyfinApi.Item initial, Runnable back) {
        stopPlayback();
        LinearLayout view = panel(displayTitle(initial), true, back);
        button(view, "返回", back);
        int expected = page;
        TextView state = text(view, "正在读取影片详情…");
        JellyfinApi connection = api;
        worker.execute(() -> {
            try {
                JellyfinApi.Item item = connection.details(initial);
                onPage(expected, () -> {
                    view.removeView(state);
                    if (item.year > 0 || item.durationMs > 0) {
                        text(view, (item.year > 0 ? item.year + " 年  " : "")
                                + (item.durationMs > 0 ? item.durationMs / 60_000 + " 分钟" : ""));
                    }
                    if (!item.overview.isEmpty()) text(view, item.overview);
                    Runnable returnToDetails = () -> detailsPage(item, back);
                    if (item.series) button(view, "选择剧集", () -> episodesPage(item, returnToDetails));
                    else {
                        if (item.resumeMs > 0) button(view, "继续观看 · " + time(item.resumeMs),
                                () -> playbackPage(item, item.resumeMs, returnToDetails));
                        button(view, item.resumeMs > 0 ? "从头播放" : "播放",
                                () -> playbackPage(item, 0, returnToDetails));
                    }
                });
                try {
                    Bitmap poster = connection.poster(item);
                    if (poster != null) onPage(expected, () -> {
                        ImageView cover = new ImageView(this);
                        cover.setAdjustViewBounds(true);
                        cover.setScaleType(ImageView.ScaleType.FIT_START);
                        view.addView(cover, 1, new LinearLayout.LayoutParams(dp(138), -2));
                        cover.setImageBitmap(poster);
                    });
                } catch (Exception ignored) {
                    // Metadata and playback remain available when a poster cannot be loaded.
                }
            } catch (Exception error) {
                onPage(expected, () -> state.setText(message(error, "读取影片详情失败")));
            }
        });
    }

    private void searchPage() {
        LinearLayout view = panel("搜索", true, this::homePage);
        EditText search = field(view, "搜索片名", query, false);
        status = text(view, "");
        button(view, "搜索", () -> { query = value(search).trim(); search(view); });
        button(view, "返回首页", this::homePage);
        search(view);
    }

    private void search(LinearLayout view) {
        int expected = ++page;
        while (view.getChildCount() > 5) view.removeViewAt(5);
        if (query.isEmpty()) { status.setText("请输入片名。"); return; }
        status.setText("正在搜索…");
        String term = query;
        JellyfinApi connection = api;
        worker.execute(() -> {
            try {
                List<JellyfinApi.Item> items = connection.search(term);
                onPage(expected, () -> {
                    status.setText(items.isEmpty() ? "没有找到影片。" : "搜索结果");
                    for (JellyfinApi.Item item : items) {
                        button(view, displayTitle(item), () -> openItem(item, this::searchPage));
                    }
                });
            } catch (Exception error) {
                onPage(expected, () -> status.setText(message(error, "搜索失败")));
            }
        });
    }

    private void episodesPage(JellyfinApi.Item series, Runnable back) {
        LinearLayout view = panel(series.title, true, back);
        int expected = page;
        status = text(view, "正在读取剧集…");
        button(view, "返回详情", back);
        JellyfinApi connection = api;
        worker.execute(() -> {
            try {
                List<JellyfinApi.Item> episodes = connection.episodes(series);
                onPage(expected, () -> {
                    status.setText(episodes.isEmpty() ? "暂无剧集。" : "选择剧集");
                    for (JellyfinApi.Item episode : episodes) {
                        button(view, episode.title, () -> detailsPage(episode, () -> episodesPage(series, back)));
                    }
                });
            } catch (Exception error) {
                onPage(expected, () -> status.setText(message(error, "读取剧集失败")));
            }
        });
    }

    private void playbackPage(JellyfinApi.Item item, long resumeMs, Runnable back) {
        Runnable close = () -> {
            stopPlayback();
            back.run();
        };
        LinearLayout view = panel(displayTitle(item), false, close);
        int expected = page;
        status = text(view, "正在向服务端请求视频…");
        button(view, "返回详情", close);
        PlayerView video = new PlayerView(this);
        view.addView(video, new LinearLayout.LayoutParams(-1, 0, 1));
        JellyfinApi connection = api;
        worker.execute(() -> {
            try {
                JellyfinApi.Stream resolved = connection.stream(item);
                onPage(expected, () -> startPlayback(resolved, video, resumeMs));
            } catch (Exception error) {
                onPage(expected, () -> status.setText(message(error, "无法获取视频")));
            }
        });
    }

    private void startPlayback(JellyfinApi.Stream resolved, PlayerView video, long resumeMs) {
        stream = resolved;
        playbackStarted = false;
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(false)
                .setMediaCodecSelector((mime, secure, tunneling) -> {
                    List<MediaCodecInfo> codecs = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling);
                    if (!MimeTypes.isVideo(mime)) return codecs;
                    List<MediaCodecInfo> hardware = new ArrayList<>();
                    for (MediaCodecInfo codec : codecs) if (codec.hardwareAccelerated) hardware.add(codec);
                    return hardware;
                });
        OkHttpDataSource.Factory data = new OkHttpDataSource.Factory(api.client())
                .setDefaultRequestProperties(Collections.singletonMap("Authorization", api.authorization()));
        player = new ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(data)).build();
        video.setPlayer(player);
        player.addAnalyticsListener(new AnalyticsListener() {
            private String decoder;
            private Format format;
            private boolean firstFrame;
            private void update() {
                if (stream != resolved) return;
                String line = (resolved.constrained ? "服务端转码 · " : "") + (format == null ? "等待画面" :
                        format.sampleMimeType + " · " + format.width + "×" + format.height +
                        (format.frameRate > 0 ? " · " + format.frameRate + " fps" : ""));
                status.setText(line + (decoder == null ? "" : "\n硬解 · " + decoder) +
                        (firstFrame ? " · 已出首帧" : ""));
            }
            @Override public void onVideoDecoderInitialized(EventTime time, String name, long at, long duration) {
                decoder = name;
                Log.i(JellyfinApi.TAG, "Hardware decoder=" + name);
                update();
            }
            @Override public void onVideoInputFormatChanged(EventTime time, Format input, DecoderReuseEvaluation reuse) {
                format = input;
                Log.i(JellyfinApi.TAG, "Input mime=" + input.sampleMimeType + " size=" + input.width + "x" + input.height
                        + " fps=" + input.frameRate + " averageBitrate=" + input.averageBitrate);
                update();
            }
            @Override public void onRenderedFirstFrame(EventTime time, Object output, long renderTimeMs) {
                if (!firstFrame && stream == resolved) {
                    firstFrame = true;
                    playbackStarted = true;
                    Log.i(JellyfinApi.TAG, "First frame rendered");
                    report("");
                    ui.postDelayed(progress, 10_000);
                }
                update();
            }
        });
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) {
                String code = error.getErrorCodeName();
                Log.w(JellyfinApi.TAG, "Playback error " + code);
                if (stream == resolved) status.setText("播放失败 · " + code);
            }
        });
        MediaItem.Builder item = new MediaItem.Builder().setUri(resolved.url.toString());
        if (resolved.hls) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaItem(item.build(), resumeMs);
        if (resumeMs > 0) Log.i(JellyfinApi.TAG, "Requested resume position ms=" + resumeMs);
        player.prepare();
        player.play();
    }

    private void report(String event) {
        JellyfinApi.Stream active = stream;
        JellyfinApi connection = api;
        if (active == null || player == null || connection == null || !playbackStarted) return;
        long position = player.getCurrentPosition();
        boolean paused = !player.getPlayWhenReady();
        worker.execute(() -> {
            try {
                connection.report(active, event, position, paused);
            } catch (Exception error) {
                Log.w(JellyfinApi.TAG, "Unable to report playback status");
            }
        });
    }

    private void stopPlayback() {
        ui.removeCallbacks(progress);
        if (player != null) {
            report("Stopped");
            playbackStarted = false;
            stream = null;
            player.release();
            player = null;
        }
    }

    @Override public void onBackPressed() {
        if (backAction != null) backAction.run();
        else super.onBackPressed();
    }

    @Override protected void onStop() {
        if (player != null) {
            player.pause();
            report("Progress");
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        page++;
        stopPlayback();
        worker.shutdown();
        super.onDestroy();
    }

    private void onPage(int expected, Runnable action) {
        ui.post(() -> { if (expected == page && !isFinishing() && !isDestroyed()) action.run(); });
    }

    private String message(Exception error, String fallback) {
        String detail = error.getMessage();
        if (detail != null && (detail.matches(".+失败，HTTP [0-9]{3}") || detail.startsWith("服务端") || detail.equals("没有可用片源"))) return detail;
        Log.w(JellyfinApi.TAG, fallback + " " + error.getClass().getSimpleName());
        return fallback + "。请检查连接设置。";
    }

    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    private String displayTitle(JellyfinApi.Item item) {
        return item.seriesName.isEmpty() ? item.title : item.seriesName + " · " + item.title;
    }
    private String time(long millis) {
        long seconds = millis / 1000;
        return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
    private void section(LinearLayout parent, String title) {
        text(parent, title).setTextSize(18);
    }
    private String value(EditText field) { return field.getText().toString(); }
    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private TextView text(LinearLayout parent, String text) {
        TextView field = new TextView(this);
        field.setText(text);
        field.setPadding(0, dp(6), 0, dp(6));
        parent.addView(field);
        return field;
    }
    private void button(LinearLayout parent, String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setOnClickListener(view -> action.run());
        parent.addView(button);
    }
    private EditText field(LinearLayout parent, String hint, String value, boolean secret) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setContentDescription(hint);
        field.setSingleLine(true);
        field.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT);
        field.setText(value);
        parent.addView(field, new LinearLayout.LayoutParams(-1, -2));
        return field;
    }
}
