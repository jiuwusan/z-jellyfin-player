package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@UnstableApi
public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService browse = Executors.newFixedThreadPool(4);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LibraryUi libraryUi = new LibraryUi(this, ui);
    private ConnectionSettings settings;
    private ConnectionSettings draft;
    private JellyfinApi api;
    private JellyfinApi.Stream stream;
    private ExoPlayer player;
    private PlaybackPage playback;
    private boolean fullscreenPlayback;
    private int previousOrientation;
    private int previousSystemUi;
    private int previousBarsBehavior;
    private int previousCutoutMode;
    private int page;
    private int seasonRequest;
    private String device;
    private Runnable backAction;
    private boolean playbackStarted;
    private String query = "";
    private int searchRequest;
    private BrowseTab browseTab = BrowseTab.HOME;
    private static final String SETTINGS_CONFIRMATION = "jellyfin";

    private enum BrowseTab {
        HOME("⌂", "首页"), LIBRARY("▤", "媒体库"), SETTINGS("⚙", "设置");

        final String icon;
        final String title;

        BrowseTab(String icon, String title) {
            this.icon = icon;
            this.title = title;
        }
    }

    private final Runnable progress = new Runnable() {
        @Override public void run() {
            if (player == null) return;
            report("Progress");
            ui.postDelayed(this, 10_000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(LibraryUi.BACKGROUND);
        getWindow().setNavigationBarColor(LibraryUi.BACKGROUND);
        StartupSplashView splash = new StartupSplashView(this, libraryUi);
        setContentView(splash);
        long splashReadyAt = SystemClock.uptimeMillis() + splash.animateIdentity();
        int launchPage = ++page;
        device = getPreferences(0).getString("device", null);
        if (device == null) {
            device = UUID.randomUUID().toString();
            getPreferences(0).edit().putString("device", device).apply();
        }
        try {
            settings = ConnectionSettings.load(this);
            if (settings == null) {
                afterSplash(launchPage, splashReadyAt, () -> settingsPage(""));
            } else {
                ui.postDelayed(() -> onPage(launchPage, () -> splash.showConnectionStatus(() -> settingsPage(""))), 1600);
                connect(settings, false, splashReadyAt);
            }
        } catch (Exception error) {
            afterSplash(launchPage, splashReadyAt, () -> settingsPage("无法读取已保存的配置，请重新填写。"));
        }
    }

    private LinearLayout panel(String title, boolean scroll, Runnable back) {
        return panel(title, scroll, back, null);
    }

    private LinearLayout panel(String title, boolean scroll, Runnable back, BrowseTab nav) {
        backAction = back;
        page++;
        LinearLayout root = column();
        root.setBackgroundColor(LibraryUi.BACKGROUND);
        int inset = nav == null ? contentGutter() : 0;
        int vertical = nav == null ? dp(10) : 0;
        root.setPadding(inset, vertical, inset, vertical);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(inset + insets.getSystemWindowInsetLeft(), vertical + insets.getSystemWindowInsetTop(),
                    inset + insets.getSystemWindowInsetRight(), vertical + insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();
        if (nav != null) toolbar(root, title, back, nav != BrowseTab.SETTINGS, contentGutter());
        LinearLayout content = root;
        if (scroll) {
            ScrollView scroller = new ScrollView(this);
            scroller.setFillViewport(true);
            content = column();
            if (nav != null) content.setPadding(contentGutter(), dp(19), contentGutter(), dp(30));
            scroller.addView(content);
            root.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        if (nav == null && !title.isEmpty()) {
            TextView heading = libraryUi.label(title, 25, LibraryUi.TEXT, true);
            heading.setPadding(0, dp(6), 0, dp(12));
            content.addView(heading);
        } else if (nav != null) {
            bottomBar(root, nav);
        }
        return content;
    }

    private void toolbar(LinearLayout root, String title, Runnable back, boolean showTools, int gutter) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(gutter, 0, gutter, 0);
        row.setBackgroundColor(LibraryUi.PANEL);
        root.addView(row, new LinearLayout.LayoutParams(-1, dp(61)));
        if (back != null) toolbarButton(row, "‹", "返回", back);
        boolean home = getString(R.string.app_name).equals(title) && back == null;
        if (home) {
            LinearLayout.LayoutParams logoBounds = new LinearLayout.LayoutParams(dp(38), dp(38));
            logoBounds.setMarginStart(-dp(5));
            row.addView(libraryUi.brandMark(), logoBounds);
        }
        TextView heading = libraryUi.label(title, home ? 22 : 16, LibraryUi.TEXT, true);
        heading.setSingleLine();
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heading.setPadding(back != null || home ? dp(10) : 0, 0, dp(4), 0);
        row.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        if (showTools) {
            if (!"搜索".equals(title)) toolbarButton(row, "⌕", "搜索影片", this::searchPage);
            toolbarButton(row, "⚙", "连接设置", () -> settingsPage(""));
        }
    }

    private void toolbarButton(LinearLayout row, String icon, String description, Runnable action) {
        TextView button = libraryUi.label(icon, 23, LibraryUi.TEXT, true);
        button.setContentDescription(description);
        button.setGravity(Gravity.CENTER);
        GradientDrawable background = libraryUi.rounded(0x0cffffff, LibraryUi.RADIUS_CONTROL);
        background.setStroke(dp(1), 0x0fffffff);
        button.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(44), dp(44));
        if (row.getChildCount() != 0) params.setMarginStart(dp(8));
        row.addView(button, params);
        libraryUi.actionable(button, action);
    }

    private void bottomBar(LinearLayout root, BrowseTab selected) {
        View divider = new View(this);
        divider.setBackgroundColor(LibraryUi.LINE);
        root.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout row = new LinearLayout(this);
        row.setBackgroundColor(LibraryUi.PANEL);
        row.setPadding(dp(10), dp(6), dp(10), dp(7));
        root.addView(row, new LinearLayout.LayoutParams(-1, -2));
        for (BrowseTab tab : BrowseTab.values()) {
            boolean active = tab == selected;
            int color = active ? LibraryUi.ACCENT_LIGHT : LibraryUi.MUTED;
            LinearLayout target = column();
            target.setGravity(Gravity.CENTER);
            target.setMinimumHeight(dp(53));
            target.setContentDescription(tab.title);
            target.setSelected(active);
            row.addView(target, new LinearLayout.LayoutParams(0, -2, 1));
            TextView icon = libraryUi.label(tab.icon, 19, color, true);
            icon.setGravity(Gravity.CENTER);
            if (active) icon.setBackground(libraryUi.rounded(LibraryUi.SELECTED, LibraryUi.RADIUS_CONTROL));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            target.addView(icon, new LinearLayout.LayoutParams(dp(53), dp(28)));
            TextView label = libraryUi.label(tab.title, 12, color, active);
            label.setGravity(Gravity.CENTER);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-1, -2);
            labelParams.topMargin = dp(3);
            target.addView(label, labelParams);
            libraryUi.actionable(target, () -> {
                switch (tab) {
                    case HOME: homePage(); break;
                    case LIBRARY: librariesPage(); break;
                    case SETTINGS: if (selected != BrowseTab.SETTINGS) settingsPage(""); break;
                }
            });
        }
    }

    private void settingsPage(String message) {
        Runnable back = browseTab == BrowseTab.LIBRARY ? this::librariesPage : this::homePage;
        LinearLayout view = api == null ? panel("连接设置", true, null)
                : panel("连接设置", true, back, BrowseTab.SETTINGS);
        ConnectionSettings old = draft == null ? settings : draft;
        section(view, "家庭 HTTP 代理");
        LinearLayout address = new LinearLayout(this);
        view.addView(address);
        LinearLayout hostGroup = column();
        hostGroup.setPadding(0, 0, dp(10), 0);
        address.addView(hostGroup, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout portGroup = column();
        address.addView(portGroup, new LinearLayout.LayoutParams(dp(82), -2));
        EditText host = settingsField(hostGroup, "代理地址", old == null ? "" : old.host, false);
        EditText port = settingsField(portGroup, "端口", old == null ? "" : Integer.toString(old.port), false);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText proxyUser = settingsField(view, "代理用户名", old == null ? "" : old.proxyUser, false);
        EditText proxyPassword = settingsField(view, "代理密码", old == null ? "" : old.proxyPassword, true);
        settingsSection(view, "Jellyfin 授权");
        EditText server = settingsField(view, "服务器地址", old == null ? "" : old.server.toString(), false);
        EditText username = settingsField(view, "Jellyfin 账号", old == null ? "" : old.user, false);
        EditText password = settingsField(view, "Jellyfin 密码", old == null ? "" : old.password, true);
        settingsSection(view, "播放画质");
        LinearLayout qualities = new LinearLayout(this);
        if (getResources().getConfiguration().screenWidthDp <= 355) {
            qualities.setOrientation(LinearLayout.VERTICAL);
        }
        view.addView(qualities);
        Spinner resolution = qualityField(qualities, "分辨率上限", PlaybackPolicy.RESOLUTION_LABELS,
                PlaybackPolicy.optionIndex(PlaybackPolicy.WIDTHS, old == null ? PlaybackPolicy.WIDTH : old.resolution, PlaybackPolicy.WIDTH));
        Spinner bitrate = qualityField(qualities, "码率上限（音视频合计）", PlaybackPolicy.RATE_LABELS,
                PlaybackPolicy.optionIndex(PlaybackPolicy.RATES, old == null ? PlaybackPolicy.TOTAL_BPS : old.bitrate, PlaybackPolicy.TOTAL_BPS));
        TextView notice = text(view, message);
        notice.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        actionButton(view, "保存并连接", true, () -> {
            try {
                ConnectionSettings candidate = new ConnectionSettings(value(server), value(host), Integer.parseInt(value(port)),
                        value(proxyUser), value(proxyPassword), value(username), value(password),
                        PlaybackPolicy.WIDTHS[resolution.getSelectedItemPosition()], PlaybackPolicy.RATES[bitrate.getSelectedItemPosition()]);
                if (settings == null) connect(candidate, true);
                else confirmSettings(candidate);
            } catch (IllegalArgumentException error) {
                notice.setText("请检查服务器地址、代理端口和账号。");
                notice.setVisibility(View.VISIBLE);
            }
        });
    }

    private void settingsSection(LinearLayout view, String title) {
        View divider = new View(this);
        divider.setBackgroundColor(LibraryUi.LINE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.topMargin = dp(12);
        view.addView(divider, params);
        section(view, title);
    }

    private EditText settingsField(LinearLayout parent, String title, String value, boolean secret) {
        TextView label = libraryUi.label(title, 13, LibraryUi.MUTED, false);
        label.setPadding(0, dp(8), 0, 0);
        parent.addView(label);
        EditText input = field(parent, title, value, secret);
        input.setHint("");
        input.setId(View.generateViewId());
        label.setLabelFor(input.getId());
        input.setTextSize(15);
        input.setMinHeight(dp(44));
        input.setBackgroundTintList(new ColorStateList(
                new int[][] { { android.R.attr.state_focused }, {} },
                new int[] { LibraryUi.ACCENT, 0xff575757 }));
        return input;
    }

    private Spinner qualityField(LinearLayout row, String title, String[] options, int selection) {
        LinearLayout group = column();
        group.setPadding(0, 0, dp(6), 0);
        row.addView(group, row.getOrientation() == LinearLayout.VERTICAL
                ? new LinearLayout.LayoutParams(-1, -2)
                : new LinearLayout.LayoutParams(0, -2, 1));
        text(group, title).setTextSize(12);
        Spinner spinner = new Spinner(this, Spinner.MODE_DROPDOWN);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selection);
        spinner.setContentDescription(title);
        spinner.setBackgroundTintList(ColorStateList.valueOf(0xff575757));
        spinner.setPopupBackgroundDrawable(libraryUi.rounded(LibraryUi.PANEL, LibraryUi.RADIUS_PANEL));
        group.addView(spinner, new LinearLayout.LayoutParams(-1, dp(44)));
        return spinner;
    }

    private void confirmSettings(ConnectionSettings candidate) {
        LinearLayout body = column();
        body.setPadding(dp(21), dp(21), dp(21), dp(21));
        GradientDrawable backdrop = libraryUi.rounded(LibraryUi.PANEL, LibraryUi.RADIUS_PANEL);
        backdrop.setStroke(dp(1), 0xff454545);
        body.setBackground(backdrop);
        body.addView(libraryUi.label("确认保存设置", 20, LibraryUi.TEXT, true));
        EditText confirmation = settingsField(body, "确认密码", "", true);
        confirmation.setBackgroundTintList(null);
        confirmation.setBackground(inputOutline());
        confirmation.setPadding(dp(11), dp(8), dp(11), dp(8));
        TextView error = text(body, "");
        error.setTextColor(LibraryUi.ERROR);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        error.setVisibility(View.GONE);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(body).create();
        Runnable submit = () -> {
            if (!SETTINGS_CONFIRMATION.equals(value(confirmation))) {
                error.setText(value(confirmation).isEmpty() ? "请输入确认密码。" : "确认密码错误，请重新输入。");
                error.setVisibility(View.VISIBLE);
                confirmation.setActivated(true);
                confirmation.requestFocus();
                return;
            }
            dialog.dismiss();
            connect(candidate, true);
        };
        actionRow(body, "取消", dialog::dismiss, "确认保存", submit);
        confirmation.setImeOptions(EditorInfo.IME_ACTION_DONE);
        confirmation.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            submit.run();
            return true;
        });
        confirmation.requestFocus();
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            dialog.getWindow().setLayout(Math.min(dp(340), getResources().getDisplayMetrics().widthPixels - dp(36)), -2);
        }
    }

    private StateListDrawable inputOutline() {
        StateListDrawable background = new StateListDrawable();
        int[] states = { android.R.attr.state_activated, android.R.attr.state_focused };
        int[] colors = { LibraryUi.ERROR, LibraryUi.ACCENT_LIGHT, 0xff484848 };
        for (int i = 0; i < colors.length; i++) {
            GradientDrawable shape = libraryUi.rounded(LibraryUi.INPUT, LibraryUi.RADIUS_CONTROL);
            shape.setStroke(dp(1), colors[i]);
            background.addState(i < states.length ? new int[] { states[i] } : new int[0], shape);
        }
        return background;
    }

    private void connect(ConnectionSettings candidate, boolean save) {
        connect(candidate, save, 0);
    }

    private void connect(ConnectionSettings candidate, boolean save, long splashReadyAt) {
        if (splashReadyAt == 0) {
            LinearLayout view = panel("正在连接 Jellyfin", false, () -> settingsPage(""));
            text(view, "通过家庭 HTTP 代理连接。");
            button(view, "连接设置", () -> settingsPage(""));
        }
        int expected = page;
        worker.execute(() -> {
            try {
                JellyfinApi connected = new JellyfinApi(candidate, device);
                connected.login();
                if (save) candidate.save(this);
                afterSplash(expected, splashReadyAt, () -> {
                    settings = candidate;
                    draft = null;
                    api = connected;
                    libraryUi.useConnection(connected);
                    homePage();
                    if (save) Toast.makeText(this, "设置已保存，连接成功", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception error) {
                afterSplash(expected, splashReadyAt, () -> {
                    draft = candidate;
                    connectionFailed(candidate, save, error);
                });
            }
        });
    }

    private void afterSplash(int expected, long readyAt, Runnable action) {
        ui.postAtTime(() -> {
            if (expected == page && !isFinishing() && !isDestroyed()) action.run();
        }, Math.max(SystemClock.uptimeMillis(), readyAt));
    }

    private void connectionFailed(ConnectionSettings candidate, boolean save, Exception error) {
        LinearLayout view = panel("连接失败", false, () -> settingsPage(""));
        text(view, message(error, "连接失败"));
        button(view, "重试连接", () -> connect(candidate, save));
        button(view, "连接设置", () -> settingsPage(""));
    }

    private void homePage() {
        stopPlayback();
        browseTab = BrowseTab.HOME;
        LinearLayout view = panel(getString(R.string.app_name), true, null, browseTab);
        view.setPadding(0, dp(19), 0, dp(30));
        for (HomeShelf shelf : HomeShelf.values()) {
            LinearLayout group = column();
            LinearLayout.LayoutParams groupParams = new LinearLayout.LayoutParams(-1, -2);
            groupParams.bottomMargin = shelf == HomeShelf.LATEST ? 0 : dp(25);
            view.addView(group, groupParams);
            TextView heading = libraryUi.label(shelf.title, 20, LibraryUi.TEXT, true);
            heading.setPadding(contentGutter(), 0, contentGutter(), dp(10));
            group.addView(heading);
            LinearLayout list = column();
            group.addView(list);
            fillHome(group, list, shelf);
        }
    }

    private enum HomeShelf {
        RESUME("继续观看", true), NEXT("接下来", true), LATEST("最近添加", false);
        final String title;
        final boolean wide;
        HomeShelf(String title, boolean wide) { this.title = title; this.wide = wide; }
    }

    private void fillHome(LinearLayout group, LinearLayout list, HomeShelf shelf) {
        int expected = page;
        TextView state = text(list, "正在加载…");
        state.setPadding(contentGutter(), dp(6), contentGutter(), dp(6));
        JellyfinApi connection = api;
        browse.execute(() -> {
            try {
                List<JellyfinApi.Item> items;
                switch (shelf) {
                    case RESUME: items = connection.resumeItems(); break;
                    case NEXT: items = connection.nextItems(); break;
                    default: items = recentItems(connection.latestItems()); break;
                }
                for (java.util.Iterator<JellyfinApi.Item> iterator = items.iterator(); iterator.hasNext();) {
                    JellyfinApi.Item item = iterator.next();
                    if (shelf == HomeShelf.RESUME && item.resumeMs <= 0
                            || shelf == HomeShelf.NEXT && (item.played || item.resumeMs > 0)) {
                        iterator.remove();
                    }
                }
                onPage(expected, () -> {
                    if (items.isEmpty()) {
                        if (shelf == HomeShelf.NEXT || shelf == HomeShelf.LATEST) group.setVisibility(View.GONE);
                        else state.setText("暂无未看完的影片。");
                    } else {
                        list.removeView(state);
                        boolean resume = shelf == HomeShelf.RESUME;
                        LibraryUi.ItemAction action = resume
                                ? item -> playbackPage(item, item.resumeMs, this::homePage)
                                : item -> openItem(item, this::homePage);
                        list.addView(libraryUi.rail(items, shelf.wide, resume, contentGutter(), action));
                    }
                    Log.i(JellyfinApi.TAG, "Home shelf=" + shelf.name() + " items=" + items.size());
                });
            } catch (Exception error) {
                onPage(expected, () -> {
                    state.setText(message(error, "读取" + shelf.title + "失败"));
                    button(list, "重试", () -> {
                        list.removeAllViews();
                        fillHome(group, list, shelf);
                    });
                });
            }
        });
    }

    private List<JellyfinApi.Item> recentItems(List<JellyfinApi.Item> items) {
        Set<String> seen = new HashSet<>();
        List<JellyfinApi.Item> result = new ArrayList<>();
        for (JellyfinApi.Item item : items) {
            String key = item.seriesId.isEmpty() ? item.id : item.seriesId;
            if (seen.add(key)) result.add(item);
        }
        return result;
    }

    private void librariesPage() {
        librariesPage(null);
    }

    private void librariesPage(LibraryCatalog catalog) {
        browseTab = BrowseTab.LIBRARY;
        LinearLayout view = panel("媒体库", true, this::homePage, browseTab);
        if (catalog != null) {
            showCatalog(view, catalog);
            return;
        }
        TextView state = text(view, "正在加载…");
        int expected = page;
        JellyfinApi connection = api;
        browse.execute(() -> {
            try {
                LibraryCatalog loaded = new LibraryCatalog();
                for (JellyfinApi.Item item : connection.libraries()) {
                    if (!item.collectionType.matches("music|audiobooks|books|photos|playlists|livetv")) {
                        loaded.tabs.add(new LibraryLocation(item, true));
                    }
                }
                onPage(expected, () -> {
                    view.removeView(state);
                    showCatalog(view, loaded);
                });
            } catch (Exception error) {
                onPage(expected, () -> {
                    state.setText(message(error, "读取媒体库失败"));
                    button(view, "重试", this::librariesPage);
                });
            }
        });
    }

    private void showCatalog(LinearLayout view, LibraryCatalog catalog) {
        ScrollView content = (ScrollView) view.getParent();
        LinearLayout root = (LinearLayout) content.getParent();
        HorizontalScrollView tabs = new HorizontalScrollView(this);
        tabs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(contentGutter(), dp(19), contentGutter(), dp(12));
        tabs.addView(row);
        root.addView(tabs, root.indexOfChild(content), new LinearLayout.LayoutParams(-1, -2));
        view.setPadding(contentGutter(), 0, contentGutter(), dp(24));
        for (LibraryLocation location : catalog.tabs) {
            TextView chip = addLibraryTab(row, location.folder == null ? "全部" : location.folder.title,
                    location == catalog.selected);
            libraryUi.actionable(chip, () -> {
                if (location == catalog.selected) return;
                catalog.selected.scrollY = content.getScrollY();
                catalog.selected = location;
                catalog.tabScrollX = tabs.getScrollX();
                librariesPage(catalog);
            });
        }
        tabs.post(() -> tabs.scrollTo(catalog.tabScrollX, 0));
        showLibraryPage(view, catalog.selected, () -> {
            catalog.tabScrollX = tabs.getScrollX();
            librariesPage(catalog);
        });
    }

    private static final class LibraryCatalog {
        final List<LibraryLocation> tabs = new ArrayList<>();
        LibraryLocation selected = new LibraryLocation(null, true);
        int tabScrollX;

        LibraryCatalog() { tabs.add(selected); }
    }

    private static final class LibraryLocation {
        final JellyfinApi.Item folder;
        final boolean catalog;
        final List<JellyfinApi.Item> items = new ArrayList<>();
        int scrollY;
        boolean loaded;
        boolean more = true;
        LibraryLocation(JellyfinApi.Item folder, boolean catalog) {
            this.folder = folder;
            this.catalog = catalog;
        }
    }

    private void libraryPage(LibraryLocation location, Runnable back) {
        LinearLayout view = panel(location.folder.title, true, back, browseTab);
        showLibraryPage(view, location, () -> libraryPage(location, back));
    }

    private void showLibraryPage(LinearLayout view, LibraryLocation location, Runnable reopen) {
        new LibraryListing(view, location, reopen).show();
    }

    private final class LibraryListing {
        private final LinearLayout view;
        private final ScrollView scroll;
        private final LinearLayout grid = column();
        private final LinearLayout footer = column();
        private final LibraryLocation location;
        private final Runnable reopen;
        private final int expected = page;
        private final int gridWidth = screenWidth();
        private boolean loading;
        private boolean failed;

        LibraryListing(LinearLayout view, LibraryLocation location, Runnable reopen) {
            this.view = view;
            this.scroll = (ScrollView) view.getParent();
            this.location = location;
            this.reopen = reopen;
        }

        void show() {
            view.addView(grid);
            view.addView(footer);
            scroll.setOnScrollChangeListener((target, x, y, oldX, oldY) -> {
                if (y > oldY) loadNearEnd();
            });
            view.addOnLayoutChangeListener((target, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                    scroll.post(this::loadNearEnd));
            if (location.loaded) {
                appendItems(0);
                scroll.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() {
                        scroll.getViewTreeObserver().removeOnPreDrawListener(this);
                        scroll.scrollTo(0, location.scrollY);
                        return true;
                    }
                });
            } else {
                load();
            }
        }

        private void loadNearEnd() {
            int remaining = view.getBottom() - scroll.getScrollY() - scroll.getHeight();
            if (location.loaded && !failed && scroll.getHeight() > 0 && remaining <= dp(80)) load();
        }

        private void appendItems(int offset) {
            libraryUi.appendGrid(grid, location.items, gridWidth, offset, item -> {
                location.scrollY = scroll.getScrollY();
                openItem(item, reopen);
            });
            footer.removeAllViews();
            if (location.items.isEmpty()) text(footer, "这个媒体库暂无内容。");
        }

        private void load() {
            if (page != expected || loading || !location.more) return;
            loading = true;
            failed = false;
            int offset = location.items.size();
            footer.removeAllViews();
            TextView status = text(footer, offset == 0 ? "正在加载…" : "正在加载更多…");
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            JellyfinApi connection = api;
            browse.execute(() -> {
                try {
                    JellyfinApi.LibraryItemsPage next = connection.libraryItems(location.folder, location.catalog, offset);
                    onPage(expected, () -> {
                        location.items.addAll(next.items);
                        location.loaded = true;
                        location.more = next.more;
                        appendItems(offset);
                        loading = false;
                        Log.i(JellyfinApi.TAG, "Library page offset=" + offset + " items=" + next.items.size()
                                + " more=" + next.more);
                    });
                } catch (Exception error) {
                    onPage(expected, () -> {
                        loading = false;
                        failed = true;
                        status.setText(message(error, offset == 0 ? "读取媒体库失败" : "读取下一页失败"));
                        button(footer, "重试", this::load);
                    });
                }
            });
        }
    }

    private void openItem(JellyfinApi.Item item, Runnable back) {
        if (item.folder && !item.series) libraryPage(new LibraryLocation(item, false), back);
        else detailsPage(item, back);
    }

    private void detailsPage(JellyfinApi.Item initial, Runnable back) {
        detailsPage(initial, back, null);
    }

    private void detailsPage(JellyfinApi.Item initial, Runnable back, String seasonId) {
        stopPlayback();
        LinearLayout view = panel("媒体详情", true, back, browseTab);
        int expected = page;
        TextView state = text(view, "正在读取影片详情…");
        JellyfinApi connection = api;
        worker.execute(() -> {
            try {
                JellyfinApi.Item item = connection.details(initial);
                onPage(expected, () -> {
                    view.removeView(state);
                    showDetails(view, item, back, seasonId);
                });
            } catch (Exception error) {
                onPage(expected, () -> {
                    state.setText(message(error, "读取影片详情失败"));
                    button(view, "重试", () -> detailsPage(initial, back, seasonId));
                });
            }
        });
    }

    private void showDetails(LinearLayout pageView, JellyfinApi.Item item, Runnable back, String seasonId) {
        pageView.setPadding(0, 0, 0, dp(24));
        int screenDp = getResources().getConfiguration().screenWidthDp;
        int coverHeight = Math.max(218, Math.min(270, Math.round(screenDp * 0.66f)));
        pageView.addView(libraryUi.hero(item), new LinearLayout.LayoutParams(-1, dp(coverHeight)));
        LinearLayout view = column();
        view.setPadding(contentGutter(), 0, contentGutter(), 0);
        LinearLayout.LayoutParams body = new LinearLayout.LayoutParams(-1, -2);
        body.topMargin = -dp(44);
        pageView.addView(view, body);
        boolean episode = !item.seriesName.isEmpty();
        int titleSize = Math.max(30, Math.min(38, Math.round(screenDp * 0.08f)));
        TextView title = libraryUi.label(episode ? item.seriesName : item.title, titleSize, LibraryUi.TEXT, true);
        title.setIncludeFontPadding(false);
        view.addView(title, new LinearLayout.LayoutParams(-1, -2));
        List<String> metadata = new ArrayList<>();
        if (episode && item.season > 0) metadata.add("第 " + item.season + " 季");
        if (episode && item.index > 0) metadata.add("第 " + item.index + " 集");
        if (!episode && item.year > 0) metadata.add(Integer.toString(item.year));
        if (!episode && item.series) metadata.add("剧集");
        if ("Movie".equals(item.type)) metadata.add("电影");
        if (item.durationMs > 0) metadata.add(item.durationMs / 60_000 + " 分钟");
        if (!metadata.isEmpty()) text(view, TextUtils.join(" · ", metadata)).setTextSize(13);
        if (item.played) watched(view);
        if (episode) {
            String episodeTitle = LibraryUi.episodeTitle(item);
            view.addView(libraryUi.label(episodeTitle.isEmpty() ? item.title : episodeTitle, 18, LibraryUi.TEXT, true));
        }
        if (!item.series) {
            Runnable returnToDetails = () -> detailsPage(item, back);
            Runnable fromStart = () -> playbackPage(item, 0, returnToDetails);
            if (item.resumeMs > 0) {
                actionRow(view, "继续观看", () -> playbackPage(item, item.resumeMs, returnToDetails),
                        "从头播放", fromStart, true);
                if (item.durationMs > 0) {
                    LinearLayout.LayoutParams progress = new LinearLayout.LayoutParams(-1, dp(5));
                    progress.topMargin = dp(12);
                    view.addView(libraryUi.watchProgress(item.resumeMs, item.durationMs), progress);
                }
                text(view, "已观看 " + LibraryUi.time(item.resumeMs)
                        + (item.durationMs > 0 ? " / " + LibraryUi.time(item.durationMs) : ""));
            } else {
                LinearLayout.LayoutParams action = new LinearLayout.LayoutParams(-2, -2);
                action.topMargin = dp(10);
                view.addView(makeButton(item.played ? "重播" : "播放", true, fromStart), action);
            }
        }
        if (!item.overview.isEmpty()) {
            TextView overview = text(view, item.overview);
            overview.setTextSize(15);
            overview.setLineSpacing(dp(4), 1);
            overview.setPadding(0, dp(15), 0, dp(6));
        }
        if (item.series) showSeasons(view, item, back, seasonId);
    }

    private void searchPage() {
        Runnable back = browseTab == BrowseTab.LIBRARY ? this::librariesPage : this::homePage;
        searchPage(back, null, 0);
    }

    private void searchPage(Runnable back, List<JellyfinApi.Item> previous, int scrollY) {
        LinearLayout view = panel("搜索", true, back, browseTab);
        EditText search = field(view, "搜索片名", query, false);
        LinearLayout.LayoutParams searchBounds = new LinearLayout.LayoutParams(-1, -2);
        searchBounds.bottomMargin = dp(17);
        search.setLayoutParams(searchBounds);
        search.setMinHeight(dp(44));
        search.setTextSize(15);
        search.setPadding(dp(11), dp(8), dp(11), dp(8));
        search.setBackground(inputOutline());
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setSelection(search.length());
        TextView feedback = text(view, "");
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout results = column();
        view.addView(results);
        int expected = page;
        Runnable runSearch = () -> {
            if (page == expected) search(results, feedback, back);
        };
        Runnable submit = () -> {
            query = value(search).trim();
            ui.removeCallbacks(runSearch);
            hideKeyboard(search);
            runSearch.run();
        };
        search.setOnEditorActionListener((input, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            submit.run();
            return true;
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                query = value.toString().trim();
                searchRequest++;
                results.removeAllViews();
                feedback.setText(query.isEmpty() ? "请输入片名。" : "正在搜索…");
                feedback.setVisibility(View.VISIBLE);
                ui.removeCallbacks(runSearch);
                ui.postDelayed(runSearch, 350);
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        if (previous == null) runSearch.run();
        else showSearchResults(results, feedback, back, previous, scrollY);
    }

    private void search(LinearLayout view, TextView feedback, Runnable back) {
        int expected = page, request = ++searchRequest;
        view.removeAllViews();
        feedback.setVisibility(View.VISIBLE);
        if (query.isEmpty()) { feedback.setText("请输入片名。"); return; }
        feedback.setText("正在搜索…");
        String term = query;
        JellyfinApi connection = api;
        worker.execute(() -> {
            try {
                List<JellyfinApi.Item> items = connection.search(term);
                onPage(expected, () -> {
                    if (request == searchRequest) showSearchResults(view, feedback, back, items, 0);
                });
            } catch (Exception error) {
                onPage(expected, () -> {
                    if (request != searchRequest) return;
                    feedback.setText(message(error, "搜索失败"));
                    button(view, "重试", () -> search(view, feedback, back));
                });
            }
        });
    }

    private void showSearchResults(LinearLayout view, TextView feedback, Runnable back,
                                   List<JellyfinApi.Item> items, int scrollY) {
        feedback.setText(items.isEmpty() ? "没有找到匹配的影片。" : "");
        feedback.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        ScrollView scroll = (ScrollView) view.getParent().getParent();
        libraryUi.grid(view, items, screenWidth(), item -> {
            int previousY = scroll.getScrollY();
            hideKeyboard(view);
            openItem(item, () -> searchPage(back, items, previousY));
        });
        scroll.post(() -> scroll.scrollTo(0, scrollY));
    }

    private void hideKeyboard(View view) {
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    private void showSeasons(LinearLayout view, JellyfinApi.Item series, Runnable back, String selectedId) {
        LinearLayout picker = column();
        view.addView(picker);
        section(picker, "选集");
        fillSeasons(picker, series, back, selectedId);
    }

    private void fillSeasons(LinearLayout picker, JellyfinApi.Item series, Runnable back, String selectedId) {
        TextView loading = text(picker, "正在读取季列表…");
        int expected = page;
        JellyfinApi connection = api;
        browse.execute(() -> {
            try {
                List<JellyfinApi.Item> seasons = connection.seasons(series);
                onPage(expected, () -> {
                    picker.removeView(loading);
                    LinearLayout list = column();
                    JellyfinApi.Item selected = null;
                    for (JellyfinApi.Item season : seasons) {
                        if (selected == null || season.id.equals(selectedId)) selected = season;
                    }
                    if (seasons.size() > 1) {
                        HorizontalScrollView scroll = new HorizontalScrollView(this);
                        scroll.setHorizontalScrollBarEnabled(false);
                        LinearLayout row = new LinearLayout(this);
                        scroll.addView(row);
                        picker.addView(scroll);
                        for (JellyfinApi.Item season : seasons) {
                            TextView tab = addChip(row, season.title);
                            selectChip(tab, season == selected);
                            libraryUi.actionable(tab, () -> {
                                if (tab.isSelected()) return;
                                selectChipInRow(row, tab);
                                fillEpisodes(list, series, season, back);
                            });
                            if (season == selected) scroll.post(() -> scroll.smoothScrollTo(tab.getLeft(), 0));
                        }
                    }
                    picker.addView(list);
                    fillEpisodes(list, series, selected, back);
                });
            } catch (Exception error) {
                onPage(expected, () -> {
                    loading.setText(message(error, "读取季列表失败"));
                    button(picker, "重试", () -> {
                        picker.removeAllViews();
                        section(picker, "选集");
                        fillSeasons(picker, series, back, selectedId);
                    });
                });
            }
        });
    }

    private TextView addLibraryTab(LinearLayout row, String title, boolean selected) {
        TextView tab = libraryUi.label(title, 14, selected ? LibraryUi.ACCENT_LIGHT : LibraryUi.MUTED, selected);
        tab.setSingleLine();
        tab.setSelected(selected);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(dp(2), 0, dp(2), 0);
        tab.setMinWidth(dp(44));
        tab.setMinHeight(dp(44));
        if (selected) {
            GradientDrawable line = libraryUi.rounded(LibraryUi.ACCENT_LIGHT, 2);
            LayerDrawable marker = new LayerDrawable(new Drawable[] { line });
            marker.setLayerSize(0, dp(18), dp(2));
            marker.setLayerGravity(0, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            marker.setLayerInsetBottom(0, dp(3));
            tab.setBackground(marker);
        }
        int gap = Math.max(14, Math.min(23, Math.round(getResources().getConfiguration().screenWidthDp * 0.04f)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMarginEnd(dp(gap));
        row.addView(tab, params);
        return tab;
    }

    private TextView addChip(LinearLayout row, String title) {
        TextView tab = libraryUi.label(title, 13, LibraryUi.TEXT, true);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(dp(15), 0, dp(15), 0);
        tab.setSingleLine();
        tab.setMinHeight(dp(44));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMarginEnd(dp(8));
        row.addView(tab, params);
        return tab;
    }

    private void selectChipInRow(LinearLayout row, TextView selected) {
        for (int i = 0; i < row.getChildCount(); i++) {
            TextView child = (TextView) row.getChildAt(i);
            selectChip(child, child == selected);
        }
    }

    private void selectChip(TextView tab, boolean selected) {
        tab.setSelected(selected);
        tab.setTextColor(selected ? LibraryUi.ACCENT_LIGHT : LibraryUi.MUTED);
        tab.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        GradientDrawable background = libraryUi.rounded(selected ? LibraryUi.SELECTED : LibraryUi.SURFACE,
                LibraryUi.RADIUS_CONTROL);
        background.setStroke(dp(1), selected ? 0x9200a4dc : LibraryUi.LINE);
        tab.setBackground(background);
    }

    private void fillEpisodes(LinearLayout list, JellyfinApi.Item series, JellyfinApi.Item season, Runnable back) {
        list.removeAllViews();
        TextView loading = text(list, "正在读取剧集…");
        int expected = page, request = ++seasonRequest;
        JellyfinApi connection = api;
        browse.execute(() -> {
            try {
                List<JellyfinApi.Item> episodes = connection.episodes(series, season);
                onPage(expected, () -> {
                    if (request != seasonRequest) return;
                    list.removeView(loading);
                    if (episodes.isEmpty()) text(list, "暂无剧集。");
                    for (JellyfinApi.Item episode : episodes) {
                        episodeRow(list, episode, () -> detailsPage(episode,
                                () -> detailsPage(series, back, season == null ? null : season.id)));
                    }
                });
            } catch (Exception error) {
                onPage(expected, () -> {
                    if (request != seasonRequest) return;
                    loading.setText(message(error, "读取剧集失败"));
                    button(list, "重试", () -> fillEpisodes(list, series, season, back));
                });
            }
        });
    }

    private void episodeRow(LinearLayout list, JellyfinApi.Item episode, Runnable open) {
        View divider = new View(this);
        divider.setBackgroundColor(LibraryUi.LINE);
        list.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));
        row.setMinimumHeight(dp(68));
        list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        row.addView(libraryUi.picture(episode, true), new LinearLayout.LayoutParams(dp(90), dp(51)));
        LinearLayout body = column();
        body.setPadding(dp(10), 0, 0, 0);
        row.addView(body, new LinearLayout.LayoutParams(0, -2, 1));
        String episodeTitle = LibraryUi.episodeTitle(episode);
        String name = episode.index > 0
                ? "第 " + episode.index + " 集" + (episodeTitle.isEmpty() ? "" : " · " + episodeTitle)
                : episode.title;
        TextView episodeLabel = libraryUi.label(name, 14, LibraryUi.TEXT, true);
        episodeLabel.setMaxLines(2);
        episodeLabel.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(episodeLabel);
        String info = episode.durationMs > 0 ? episode.durationMs / 60_000 + " 分钟" : "";
        if (episode.resumeMs > 0) info += (info.isEmpty() ? "" : " · ") + "已看到 " + LibraryUi.time(episode.resumeMs);
        if (!info.isEmpty()) body.addView(libraryUi.label(info, 12, LibraryUi.MUTED, false));
        if (episode.played) watched(row);
        libraryUi.actionable(row, open);
    }

    private void watched(LinearLayout parent) {
        TextView badge = libraryUi.label("✓ 已看完", 11, LibraryUi.ACCENT_LIGHT, true);
        badge.setPadding(dp(8), dp(5), dp(8), dp(5));
        badge.setBackground(libraryUi.rounded(LibraryUi.SELECTED, LibraryUi.RADIUS_SMALL));
        parent.addView(badge, new LinearLayout.LayoutParams(-2, -2));
    }

    private void playbackPage(JellyfinApi.Item item, long resumeMs, Runnable back) {
        Runnable close = () -> {
            stopPlayback();
            back.run();
        };
        int expected = ++page;
        backAction = close;
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        PlaybackPolicy policy = PlaybackPolicy.fromSettings(settings.resolution, settings.bitrate,
                metrics.widthPixels, metrics.heightPixels);
        PlaybackPage current = new PlaybackPage(item, policy, close);
        playback = current;
        setContentView(current.screen);
        enterFullscreenPlayback();
        current.screen.requestApplyInsets();
        current.loadNeighbors();
        worker.execute(() -> {
            try {
                JellyfinApi.Stream resolved = current.connection.stream(item, policy);
                onPage(expected, () -> startPlayback(resolved, current, resumeMs, true, false, null));
            } catch (Exception error) {
                onPage(expected, () -> current.screen.showMessage(message(error, "无法获取视频")));
            }
        });
    }

    private final class PlaybackPage {
        final JellyfinApi connection = api;
        final PlaybackPolicy policy;
        final FullscreenPlaybackView screen;
        JellyfinApi.Item item, previous, next;
        PlaybackChange change;
        int subtitle = PlaybackTracks.AUTO;
        PlaybackTracks.LocalTrack localSubtitle;
        boolean foreground = true;
        boolean loggedSubtitle;
        boolean autoAdvancePending;

        PlaybackPage(JellyfinApi.Item item, PlaybackPolicy policy, Runnable close) {
            this.item = item;
            this.policy = policy;
            String resolution = PlaybackPolicy.RESOLUTION_LABELS[PlaybackPolicy.optionIndex(
                    PlaybackPolicy.WIDTHS, settings.resolution, PlaybackPolicy.WIDTH)];
            String bitrate = PlaybackPolicy.RATE_LABELS[PlaybackPolicy.optionIndex(
                    PlaybackPolicy.RATES, settings.bitrate, PlaybackPolicy.TOTAL_BPS)];
            screen = new FullscreenPlaybackView(MainActivity.this, libraryUi, resolution + " · " + bitrate, close,
                    () -> changeEpisode(previous), () -> changeEpisode(next), this::chooseSubtitle, this::chooseAudio);
            updateTitle();
            updateActions();
        }

        void updateTitle() {
            if (episodic() && !item.seriesName.isEmpty()) {
                String season = item.season > 0 ? "第 " + item.season + " 季 · " : "";
                String episode = item.index > 0 ? "第 " + item.index + " 集 · " : "";
                screen.setTitle(item.seriesName, season + episode + LibraryUi.episodeTitle(item));
            } else {
                String kind = "Movie".equals(item.type) ? "电影" : "视频";
                screen.setTitle(item.title, item.year > 0 ? item.year + " · " + kind : kind);
            }
        }

        boolean episodic() {
            return "Episode".equals(item.type) && !item.seriesId.isEmpty();
        }

        void loadNeighbors() {
            previous = null;
            next = null;
            updateActions();
            if (!episodic()) return;
            JellyfinApi.Item episode = item;
            browse.execute(() -> {
                try {
                    List<JellyfinApi.Item> items = connection.adjacentEpisodes(episode);
                    ui.post(() -> {
                        if (playback != this || item != episode) return;
                        for (int i = 0; i < items.size(); i++) {
                            if (!episode.id.equals(items.get(i).id)) continue;
                            if (i > 0) previous = items.get(i - 1);
                            if (i + 1 < items.size()) next = items.get(i + 1);
                            break;
                        }
                        updateActions();
                        advanceAfterEnd();
                    });
                } catch (Exception error) {
                    Log.w(JellyfinApi.TAG, "Unable to load adjacent episodes");
                }
            });
        }

        boolean ready() {
            return playback == this && change == null && player != null && stream != null && playbackStarted;
        }

        void updateActions() {
            boolean enabled = ready();
            boolean audio = enabled && (stream.hls ? !stream.audio.isEmpty()
                    : !PlaybackTracks.supported(player.getCurrentTracks(), C.TRACK_TYPE_AUDIO).isEmpty());
            screen.updateActions(episodic(), enabled && previous != null, enabled && next != null, enabled, audio);
        }

        void chooseSubtitle() {
            if (!ready()) return;
            JellyfinApi.Stream current = stream;
            List<String> labels = new ArrayList<>();
            labels.add("自动");
            labels.add("关闭");
            int selected = subtitle == PlaybackTracks.OFF ? 1 : 0;
            for (JellyfinApi.MediaTrack option : current.subtitles) {
                if (localSubtitle == null && subtitle == option.index) selected = labels.size();
                labels.add(option.label);
            }
            List<PlaybackTracks.LocalTrack> embedded = new ArrayList<>();
            if (current.subtitles.isEmpty()) {
                embedded = PlaybackTracks.supported(player.getCurrentTracks(), C.TRACK_TYPE_TEXT);
                for (PlaybackTracks.LocalTrack option : embedded) {
                    if (localSubtitle != null && localSubtitle.override().equals(option.override())) selected = labels.size();
                    labels.add(option.label);
                }
            }
            List<PlaybackTracks.LocalTrack> localOptions = embedded;
            screen.showChoices("字幕", labels.toArray(new String[0]), selected, which -> {
                if (!ready() || stream != current) return;
                localSubtitle = null;
                if (which == 0) subtitle = PlaybackTracks.AUTO;
                else if (which == 1) subtitle = PlaybackTracks.OFF;
                else if (which - 2 < current.subtitles.size()) subtitle = current.subtitles.get(which - 2).index;
                else {
                    subtitle = PlaybackTracks.AUTO;
                    localSubtitle = localOptions.get(which - 2 - current.subtitles.size());
                }
                loggedSubtitle = false;
                PlaybackTracks.setSubtitle(player, player.getCurrentTracks(), current, subtitle, localSubtitle);
                Log.i(JellyfinApi.TAG, "Subtitle selection index=" + subtitle + " local=" + (localSubtitle != null));
            });
        }

        void chooseAudio() {
            if (!ready()) return;
            JellyfinApi.Stream current = stream;
            if (current.hls) {
                String[] labels = new String[current.audio.size()];
                int selected = -1;
                for (int i = 0; i < labels.length; i++) {
                    JellyfinApi.MediaTrack option = current.audio.get(i);
                    labels[i] = option.label;
                    if (option.index == current.audioIndex) selected = i;
                }
                screen.showChoices("音轨", labels, selected, which -> {
                    if (!ready() || stream != current) return;
                    int index = current.audio.get(which).index;
                    if (index != current.audioIndex) switchTo(item, index);
                });
                return;
            }
            List<PlaybackTracks.LocalTrack> options = PlaybackTracks.supported(player.getCurrentTracks(), C.TRACK_TYPE_AUDIO);
            String[] labels = new String[options.size()];
            int selected = -1;
            for (int i = 0; i < labels.length; i++) {
                labels[i] = options.get(i).label;
                if (options.get(i).selected()) selected = i;
            }
            screen.showChoices("音轨", labels, selected, which -> {
                if (ready() && stream == current) PlaybackTracks.select(player, options.get(which));
            });
        }

        void changeEpisode(JellyfinApi.Item target) {
            if (target != null && ready()) switchTo(target, null);
        }

        void advanceAfterEnd() {
            if (!autoAdvancePending || next == null || !foreground || !ready()
                    || player.getPlaybackState() != Player.STATE_ENDED || !player.getPlayWhenReady()) return;
            Log.i(JellyfinApi.TAG, "Autoplay next season=" + next.season + " episode=" + next.index);
            changeEpisode(next);
        }

        void switchTo(JellyfinApi.Item target, Integer audioIndex) {
            boolean sameEpisode = target == item;
            PlaybackChange pending = new PlaybackChange(stream, target, player.getCurrentPosition(),
                    player.getPlayWhenReady(), player.getPlaybackState() == Player.STATE_ENDED,
                    subtitle, localSubtitle, player.getTrackSelectionParameters());
            autoAdvancePending = false;
            change = pending;
            player.pause();
            updateActions();
            screen.showMessage(sameEpisode ? "正在切换音轨…" : "正在切换剧集…");
            browse.execute(() -> {
                try {
                    JellyfinApi.Stream resolved = sameEpisode
                            ? connection.stream(target, policy, pending.previous.sourceId, audioIndex)
                            : connection.stream(target, policy);
                    ui.post(() -> {
                        if (playback != this || change != pending) return;
                        releasePlayer(false);
                        if (!sameEpisode) {
                            subtitle = pending.subtitle == PlaybackTracks.OFF ? PlaybackTracks.OFF : PlaybackTracks.AUTO;
                            localSubtitle = null;
                        }
                        long position = sameEpisode ? pending.position : target.resumeMs;
                        startPlayback(resolved, this, position, pending.playWhenReady, false, null);
                    });
                } catch (Exception error) {
                    ui.post(() -> {
                        if (playback == this && change == pending) recoverSwitch();
                    });
                }
            });
        }

        void recoverSwitch() {
            PlaybackChange pending = change;
            change = null;
            subtitle = pending.subtitle;
            localSubtitle = pending.localSubtitle;
            boolean resume = pending.playWhenReady && !pending.endedBeforeSwitch;
            if (stream == pending.previous) {
                player.setPlayWhenReady(resume && foreground);
                screen.showVideo();
                updateActions();
            } else {
                releasePlayer(false);
                startPlayback(pending.previous, this, pending.position, resume, true, pending.parameters);
            }
            Toast.makeText(MainActivity.this, "切换失败，已恢复原播放", Toast.LENGTH_SHORT).show();
        }

        void finishSwitch(long position) {
            if (change == null) return;
            PlaybackChange pending = change;
            change = null;
            enqueueReport(connection, pending.previous, "Stopped", pending.position, !pending.playWhenReady);
            if (pending.target != item) {
                item = pending.target;
                updateTitle();
                loadNeighbors();
            }
            Log.i(JellyfinApi.TAG, "Playback switch complete audioIndex=" + stream.audioIndex
                    + " positionMs=" + position);
        }
    }

    private static final class PlaybackChange {
        final JellyfinApi.Stream previous;
        final JellyfinApi.Item target;
        final long position;
        final boolean endedBeforeSwitch;
        final int subtitle;
        final PlaybackTracks.LocalTrack localSubtitle;
        final TrackSelectionParameters parameters;
        boolean playWhenReady;

        PlaybackChange(JellyfinApi.Stream previous, JellyfinApi.Item target, long position, boolean playWhenReady,
                       boolean endedBeforeSwitch, int subtitle, PlaybackTracks.LocalTrack localSubtitle,
                       TrackSelectionParameters parameters) {
            this.previous = previous;
            this.target = target;
            this.position = position;
            this.playWhenReady = playWhenReady;
            this.endedBeforeSwitch = endedBeforeSwitch;
            this.subtitle = subtitle;
            this.localSubtitle = localSubtitle;
            this.parameters = parameters;
        }
    }

    private void startPlayback(JellyfinApi.Stream resolved, PlaybackPage current, long resumeMs, boolean play,
                               boolean alreadyReported, TrackSelectionParameters restored) {
        FullscreenPlaybackView screen = current.screen;
        stream = resolved;
        playbackStarted = alreadyReported;
        current.loggedSubtitle = false;
        current.autoAdvancePending = false;
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(false)
                .setMediaCodecSelector((mime, secure, tunneling) -> {
                    List<MediaCodecInfo> codecs = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling);
                    if (!MimeTypes.isVideo(mime)) return codecs;
                    List<MediaCodecInfo> hardware = new ArrayList<>();
                    for (MediaCodecInfo codec : codecs) if (codec.hardwareAccelerated) hardware.add(codec);
                    return hardware;
                });
        OkHttpDataSource.Factory data = new OkHttpDataSource.Factory(current.connection.client())
                .setDefaultRequestProperties(Collections.singletonMap("Authorization", current.connection.authorization()));
        ExoPlayer active = new ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(data)).build();
        player = active;
        screen.setPlayer(active);
        if (restored != null) active.setTrackSelectionParameters(restored);
        active.addAnalyticsListener(new AnalyticsListener() {
            private boolean firstFrame;

            @Override public void onVideoDecoderInitialized(EventTime time, String name, long at, long duration) {
                Log.i(JellyfinApi.TAG, "Hardware decoder=" + name);
            }
            @Override public void onVideoInputFormatChanged(EventTime time, Format input, DecoderReuseEvaluation reuse) {
                Log.i(JellyfinApi.TAG, "Input mime=" + input.sampleMimeType + " size=" + input.width + "x" + input.height
                        + " fps=" + input.frameRate + " averageBitrate=" + input.averageBitrate);
            }
            @Override public void onRenderedFirstFrame(EventTime time, Object output, long renderTimeMs) {
                if (player != active || firstFrame) return;
                firstFrame = true;
                playbackStarted = true;
                current.finishSwitch(active.getCurrentPosition());
                screen.showVideo();
                current.updateActions();
                Log.i(JellyfinApi.TAG, "First frame rendered");
                report(alreadyReported ? "Progress" : "");
                ui.postDelayed(progress, 10_000);
                current.advanceAfterEnd();
            }
        });
        active.addListener(new Player.Listener() {
            private boolean userSought;

            @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition,
                                                          Player.PositionInfo newPosition, int reason) {
                if (player != active || reason != Player.DISCONTINUITY_REASON_SEEK) return;
                userSought = true;
                current.autoAdvancePending = false;
            }

            @Override public void onEvents(Player source, Player.Events events) {
                if (player != active || !events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)
                        || source.getPlaybackState() != Player.STATE_ENDED) return;
                if (alreadyReported && current.change == null) {
                    screen.showVideo();
                    current.updateActions();
                }
                report("Progress");
                long duration = source.getDuration();
                boolean startedAtEnd = !userSought && duration > 0 && resumeMs >= duration;
                current.autoAdvancePending = current.foreground && current.change == null
                        && source.getPlayWhenReady() && !screen.soughtToEnd() && !startedAtEnd;
                Log.i(JellyfinApi.TAG, "Playback ended autoAdvanceEligible=" + current.autoAdvancePending);
                current.advanceAfterEnd();
            }
            @Override public void onTracksChanged(Tracks tracks) {
                if (player != active) return;
                PlaybackTracks.setSubtitle(active, tracks, resolved, current.subtitle, current.localSubtitle);
                current.updateActions();
            }
            @Override public void onCues(CueGroup group) {
                if (player == active && !current.loggedSubtitle && !group.cues.isEmpty()) {
                    current.loggedSubtitle = true;
                    Log.i(JellyfinApi.TAG, "Subtitle rendered index=" + current.subtitle);
                }
            }
            @Override public void onPlayerError(PlaybackException error) {
                String code = error.getErrorCodeName();
                Log.w(JellyfinApi.TAG, "Playback error " + code);
                if (player != active) return;
                if (current.change != null) {
                    ui.post(() -> {
                        if (player == active && current.change != null) current.recoverSwitch();
                    });
                } else {
                    screen.showMessage("播放失败 · " + code);
                }
            }
        });
        active.setMediaItem(PlaybackTracks.mediaItem(resolved), resumeMs);
        PlaybackTracks.setSubtitle(active, Tracks.EMPTY, resolved, current.subtitle, current.localSubtitle);
        if (resumeMs > 0) Log.i(JellyfinApi.TAG, "Requested resume position ms=" + resumeMs);
        Log.i(JellyfinApi.TAG, "Starting playback audioIndex=" + resolved.audioIndex);
        active.setPlayWhenReady(play && current.foreground);
        active.prepare();
    }

    private void enterFullscreenPlayback() {
        fullscreenPlayback = true;
        previousOrientation = getRequestedOrientation();
        previousSystemUi = getWindow().getDecorView().getSystemUiVisibility();
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams window = getWindow().getAttributes();
            previousCutoutMode = window.layoutInDisplayCutoutMode;
            window.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(window);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            previousBarsBehavior = controller == null ? WindowInsetsController.BEHAVIOR_DEFAULT
                    : controller.getSystemBarsBehavior();
            getWindow().setDecorFitsSystemWindows(false);
        }
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        hidePlaybackSystemBars();
    }

    private void hidePlaybackSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
                return;
            }
        }
        getWindow().getDecorView().setSystemUiVisibility(previousSystemUi
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void exitFullscreenPlayback() {
        if (!fullscreenPlayback) return;
        fullscreenPlayback = false;
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams window = getWindow().getAttributes();
            window.layoutInDisplayCutoutMode = previousCutoutMode;
            getWindow().setAttributes(window);
        }
        getWindow().getDecorView().setSystemUiVisibility(previousSystemUi);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(true);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(previousBarsBehavior);
                controller.show(WindowInsets.Type.systemBars());
            }
        }
        if (!isFinishing()) setRequestedOrientation(previousOrientation);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && fullscreenPlayback) hidePlaybackSystemBars();
    }

    private void report(String event) {
        JellyfinApi.Stream active = stream;
        JellyfinApi connection = api;
        if (active == null || player == null || connection == null || !playbackStarted) return;
        long position = player.getCurrentPosition();
        boolean paused = !player.getPlayWhenReady();
        enqueueReport(connection, active, event, position, paused);
    }

    private void enqueueReport(JellyfinApi connection, JellyfinApi.Stream active, String event, long position,
                               boolean paused) {
        worker.execute(() -> {
            try {
                connection.report(active, event, position, paused);
            } catch (Exception error) {
                Log.w(JellyfinApi.TAG, "Unable to report playback status");
            }
        });
    }

    private void stopPlayback() {
        PlaybackPage current = playback;
        if (current != null && current.change != null && stream != current.change.previous) {
            PlaybackChange pending = current.change;
            enqueueReport(current.connection, pending.previous, "Stopped", pending.position, !pending.playWhenReady);
        }
        releasePlayer(true);
        playback = null;
        exitFullscreenPlayback();
    }

    private void releasePlayer(boolean reportStop) {
        ui.removeCallbacks(progress);
        if (player != null) {
            if (reportStop) report("Stopped");
            playbackStarted = false;
            stream = null;
            ExoPlayer active = player;
            player = null;
            if (playback != null) playback.screen.setPlayer(null);
            active.release();
        }
    }

    @Override public void onBackPressed() {
        if (playback != null && playback.screen.dismissChoices()) return;
        if (backAction != null) backAction.run();
        else super.onBackPressed();
    }

    @Override protected void onStop() {
        if (playback != null) {
            playback.foreground = false;
            playback.autoAdvancePending = false;
            if (playback.change != null) playback.change.playWhenReady = false;
        }
        if (player != null) {
            player.pause();
            report("Progress");
        }
        super.onStop();
    }

    @Override protected void onStart() {
        super.onStart();
        if (playback != null) playback.foreground = true;
    }

    @Override protected void onDestroy() {
        page++;
        stopPlayback();
        worker.shutdown();
        browse.shutdownNow();
        libraryUi.close();
        super.onDestroy();
    }

    private void onPage(int expected, Runnable action) {
        ui.post(() -> { if (expected == page && !isFinishing() && !isDestroyed()) action.run(); });
    }

    private String message(Exception error, String fallback) {
        String detail = error.getMessage();
        if (detail != null) {
            if (detail.equals("登录失败，HTTP 401") || detail.equals("登录失败，HTTP 403")) return "Jellyfin 登录失败，请检查账号和密码。";
            if (detail.matches("登录失败，HTTP (404|5[0-9]{2})")) return "无法访问 Jellyfin，请检查服务器地址和服务状态。";
            if (detail.matches(".+失败，HTTP [0-9]{3}") || detail.startsWith("服务端")
                    || detail.startsWith("HTTP 代理认证失败") || detail.equals("没有可用片源")) return detail;
        }
        if (error instanceof java.net.UnknownHostException || error instanceof java.net.ConnectException
                || error instanceof java.net.SocketTimeoutException) return "代理连接失败或超时，请检查网络和代理地址。";
        Log.w(JellyfinApi.TAG, fallback + " " + error.getClass().getSimpleName());
        return fallback + "。请检查连接设置。";
    }

    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    private int contentGutter() { return dp(getResources().getConfiguration().screenWidthDp <= 355 ? 16 : 20); }
    private int screenWidth() { return Math.max(1, getResources().getDisplayMetrics().widthPixels - 2 * contentGutter()); }
    private void section(LinearLayout parent, String title) {
        TextView heading = libraryUi.label(title, 19, LibraryUi.TEXT, true);
        heading.setPadding(0, dp(18), 0, dp(9));
        parent.addView(heading);
    }
    private String value(EditText field) { return field.getText().toString(); }
    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private TextView text(LinearLayout parent, String text) {
        TextView field = libraryUi.label(text, 14, LibraryUi.MUTED, false);
        field.setPadding(0, dp(6), 0, dp(6));
        parent.addView(field);
        return field;
    }
    private void button(LinearLayout parent, String title, Runnable action) {
        actionButton(parent, title, false, action);
    }
    private Button makeButton(String title, boolean primary, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextColor(primary ? LibraryUi.ON_ACCENT : LibraryUi.TEXT);
        GradientDrawable background = libraryUi.rounded(primary ? LibraryUi.ACCENT : LibraryUi.PANEL,
                LibraryUi.RADIUS_CONTROL);
        if (!primary) background.setStroke(dp(1), LibraryUi.LINE);
        button.setBackground(background);
        button.setTextSize(14);
        button.setMinHeight(dp(48));
        button.setPadding(dp(17), dp(5), dp(17), dp(5));
        libraryUi.actionable(button, action);
        return button;
    }
    private void actionButton(LinearLayout parent, String title, boolean primary, Runnable action) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(4);
        parent.addView(makeButton(title, primary, action), params);
    }
    private void actionRow(LinearLayout parent, String first, Runnable firstAction, String second, Runnable secondAction) {
        actionRow(parent, first, firstAction, second, secondAction, false);
    }
    private void actionRow(LinearLayout parent, String first, Runnable firstAction, String second, Runnable secondAction,
                           boolean firstPrimary) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(firstPrimary ? Gravity.START : Gravity.END);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.topMargin = dp(12);
        parent.addView(row, rowParams);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(-2, -2);
        left.setMarginEnd(dp(8));
        row.addView(makeButton(first, firstPrimary, firstAction), left);
        row.addView(makeButton(second, !firstPrimary, secondAction), new LinearLayout.LayoutParams(-2, -2));
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
