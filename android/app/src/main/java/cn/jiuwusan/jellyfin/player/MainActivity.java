package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
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
import android.os.PersistableBundle;
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
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.media3.common.C;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
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
import androidx.media3.exoplayer.ExoPlaybackException;
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
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@UnstableApi
public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService browse = Executors.newFixedThreadPool(4);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LibraryUi libraryUi = new LibraryUi(this, ui);
    private ConnectionSettings settings;
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
    private SubscriptionController subscriptionController;
    private SubscriptionController.Prepared pendingSubscription;
    private Runnable connectionRecovery;
    private TextView subscriptionStatus, managedQuality;
    private boolean closing;
    private String subscriptionMessage = "";
    private final Runnable subscriptionTick = new Runnable() {
        @Override public void run() {
            if (closing || subscriptionController == null) return;
            subscriptionController.sync(false);
            ui.postDelayed(this, 30_000);
        }
    };
    private final BrowseMemory browseMemory = new BrowseMemory(40);
    private LibraryCatalog savedCatalog;
    private ScrollView rememberedScroll;
    private RecycledMediaGrid rememberedGrid;
    private String rememberedKey;
    private LibraryLocation rememberedLibrary;
    private boolean homeDirty;
    private boolean playbackSettingsSelected;
    private int browsingWidthPixels;
    private int browsingHeightDp;
    private static final class LocalProgress {
        final JellyfinApi.Item item;
        final long position;
        final boolean ended;
        boolean reported;
        LocalProgress(JellyfinApi.Item item, long position, boolean ended) {
            this.item = item; this.position = position; this.ended = ended;
        }
    }
    private final java.util.LinkedHashMap<String, LocalProgress> localProgress = new java.util.LinkedHashMap<>();
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
        libraryUi.setLargeUi(DisplayStyle.load(this).large);
        browsingWidthPixels = getResources().getDisplayMetrics().widthPixels;
        browsingHeightDp = getResources().getConfiguration().screenHeightDp;
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
            subscriptionController = new SubscriptionController(this, ui, device, new SubscriptionController.Listener() {
                @Override public void status(String message) {
                    subscriptionMessage = message;
                    if (subscriptionStatus != null && subscriptionStatus.isAttachedToWindow()) subscriptionStatus.setText(message);
                }
                @Override public void prepared(SubscriptionController.Prepared prepared) { applySubscription(prepared); }
                @Override public void settled() { finishConnectionRecovery(); }
                @Override public void blocked() {
                    pendingSubscription = null;
                    if (playback == null) showBlockedSubscription();
                    else { playback.autoAdvancePending = false; playback.updateActions(); }
                }
            });
            SubscriptionSettings subscribed = subscriptionController.state();
            settings = subscribed == null || subscribed.blocked ? null : subscribed.configuration.settings(null);
            if (settings == null) {
                afterSplash(launchPage, splashReadyAt, () -> settingsPage(""));
            } else {
                ui.postDelayed(() -> onPage(launchPage, () -> splash.showConnectionStatus(() -> settingsPage(""))), 1600);
                connect(settings, splashReadyAt, true);
            }
        } catch (Exception error) {
            afterSplash(launchPage, splashReadyAt, () -> settingsPage("无法读取已保存的配置，请重新填写。"));
        }
    }

    private LinearLayout panel(String title, boolean scroll, Runnable back) {
        return panel(title, scroll, back, null);
    }

    private LinearLayout panel(String title, boolean scroll, Runnable back, BrowseTab nav) {
        rememberBrowsePosition();
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
            TextView heading = libraryUi.label(title, size(25,22), LibraryUi.TEXT, true);
            heading.setPadding(0, dp(6), 0, dp(12));
            content.addView(heading);
        } else if (nav != null) {
            bottomBar(root, nav);
        }
        return content;
    }

    private void toolbar(LinearLayout root, String title, Runnable back, boolean showTools, int gutter) {
        boolean library = "媒体库".equals(title);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(gutter, 0, gutter, 0);
        row.setBackgroundColor(LibraryUi.BACKGROUND);
        root.addView(row, new LinearLayout.LayoutParams(-1, dp(size(76,56))));
        if (back != null) toolbarButton(row, "‹", "返回", library, back);
        boolean home = getString(R.string.app_name).equals(title) && back == null;
        if (home) {
            LinearLayout.LayoutParams logoBounds = new LinearLayout.LayoutParams(dp(size(38,26)), dp(size(38,26)));
            logoBounds.setMarginStart(libraryUi.largeUi() ? -dp(5) : 0);
            row.addView(libraryUi.brandMark(), logoBounds);
        }
        TextView heading = libraryUi.label(title, size(24,home ? 20 : 17), LibraryUi.TEXT, true);
        heading.setSingleLine();
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heading.setPadding(back != null || home ? dp(10) : 0, 0, dp(4), 0);
        row.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        if (showTools) {
            if (!"搜索".equals(title)) toolbarButton(row, "⌕", "搜索影片", library, this::searchPage);
            toolbarButton(row, "⚙", "连接设置", library, () -> settingsPage(""));
        }
    }

    private void toolbarButton(LinearLayout row, String icon, String description, boolean flat, Runnable action) {
        LinearLayout button = column();
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        android.widget.ImageView image = new android.widget.ImageView(this);
        image.setImageResource("‹".equals(icon) ? R.drawable.ic_back : "⌕".equals(icon) ? R.drawable.ic_search : R.drawable.ic_settings);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        button.addView(image, new LinearLayout.LayoutParams(dp(size(26,22)), dp(size(26,22))));
        TextView caption = libraryUi.label("‹".equals(icon) ? "返回" : "⌕".equals(icon) ? "搜索" : "设置", size(17,12), LibraryUi.TEXT, false);
        caption.setGravity(Gravity.CENTER);
        caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (libraryUi.largeUi()) button.addView(caption);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(size(52,44)), dp(size(60,44)));
        if (row.getChildCount() != 0) params.setMarginStart(dp(4));
        row.addView(button, params);
        libraryUi.actionable(button, action);
    }

    private void bottomBar(LinearLayout root, BrowseTab selected) {
        View divider = new View(this);
        divider.setBackgroundColor(LibraryUi.LINE);
        root.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout row = new LinearLayout(this);
        row.setBackgroundColor(libraryUi.largeUi() ? LibraryUi.PANEL : 0xff151619);
        row.setPadding(dp(10), dp(6), dp(10), dp(7));
        root.addView(row, new LinearLayout.LayoutParams(-1, -2));
        for (BrowseTab tab : BrowseTab.values()) {
            boolean active = tab == selected;
            int color = active ? LibraryUi.ACCENT_LIGHT : LibraryUi.MUTED;
            LinearLayout target = column();
            target.setGravity(Gravity.CENTER);
            target.setMinimumHeight(dp(size(68,48)));
            target.setContentDescription(tab.title);
            target.setSelected(active);
            row.addView(target, new LinearLayout.LayoutParams(0, -2, 1));
            if(!libraryUi.largeUi()) {
                View marker=new View(this);
                marker.setBackgroundColor(LibraryUi.ACCENT);
                marker.setVisibility(active ? View.VISIBLE : View.INVISIBLE);
                LinearLayout.LayoutParams markerBounds=new LinearLayout.LayoutParams(dp(20),dp(2));
                markerBounds.bottomMargin=dp(4);
                target.addView(marker,markerBounds);
            }
            android.widget.ImageView icon = new android.widget.ImageView(this);
            icon.setImageResource(tab == BrowseTab.HOME ? R.drawable.ic_home : tab == BrowseTab.LIBRARY ? R.drawable.ic_library : R.drawable.ic_settings);
            icon.setImageTintList(ColorStateList.valueOf(color));
            icon.setPadding(dp(size(14,0)), dp(size(3,0)), dp(size(14,0)), dp(size(3,0)));
            if (active && libraryUi.largeUi()) icon.setBackground(libraryUi.rounded(LibraryUi.SELECTED, LibraryUi.RADIUS_CONTROL));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            target.addView(icon, new LinearLayout.LayoutParams(dp(size(56,24)), dp(size(32,24))));
            TextView label = libraryUi.label(tab.title, size(20,11), color, active);
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
        Runnable back = api == null ? null : browseTab == BrowseTab.LIBRARY ? this::librariesPage : this::homePage;
        LinearLayout content = api == null ? panel("订阅设置", true, null)
                : panel("设置", true, back, BrowseTab.SETTINGS);
        ScrollView scroll = (ScrollView) content.getParent();
        LinearLayout tabs = new LinearLayout(this);
        if (api == null) content.addView(tabs);
        else {
            LinearLayout root = (LinearLayout) scroll.getParent();
            tabs.setPadding(contentGutter(), 0, contentGutter(), 0);
            root.addView(tabs, root.indexOfChild(scroll), new LinearLayout.LayoutParams(-1, -2));
            content.setPadding(contentGutter(), dp(6), contentGutter(), dp(30));
        }
        TextView subscriptionTab = addLibraryTab(tabs, "订阅", true);
        TextView playbackTab = addLibraryTab(tabs, "播放", false);
        subscriptionTab.setContentDescription("订阅设置分类");
        playbackTab.setContentDescription("播放设置分类");
        Drawable selectedMarker = subscriptionTab.getBackground();
        LinearLayout view = column(), playbackSettings = column();
        content.addView(view); content.addView(playbackSettings);
        playbackSettings.setVisibility(View.GONE);
        View.OnClickListener selectTab = selected -> {
            boolean playbackSelected = selected == playbackTab;
            if (playbackTab.isSelected() == playbackSelected) return;
            View focus = getCurrentFocus();
            if (focus != null) { hideKeyboard(focus); focus.clearFocus(); }
            subscriptionTab.setSelected(!playbackSelected); playbackTab.setSelected(playbackSelected);
            subscriptionTab.setTextColor(playbackSelected ? LibraryUi.MUTED : LibraryUi.ACCENT_LIGHT);
            playbackTab.setTextColor(playbackSelected ? LibraryUi.ACCENT_LIGHT : LibraryUi.MUTED);
            subscriptionTab.setTypeface(null, playbackSelected ? Typeface.NORMAL : Typeface.BOLD);
            playbackTab.setTypeface(null, playbackSelected ? Typeface.BOLD : Typeface.NORMAL);
            subscriptionTab.setBackground(playbackSelected ? null : selectedMarker);
            playbackTab.setBackground(playbackSelected ? selectedMarker : null);
            view.setVisibility(playbackSelected ? View.GONE : View.VISIBLE);
            playbackSettings.setVisibility(playbackSelected ? View.VISIBLE : View.GONE);
            playbackSettingsSelected = playbackSelected;
            if (playbackSelected && playbackSettings.getChildCount() == 0) {
                displaySettings(playbackSettings);
                if (api != null) subscriptionLanguages(playbackSettings);
                else text(playbackSettings, "连接订阅后可设置音频和字幕语言偏好。");
            }
            scroll.scrollTo(0, 0);
        };
        libraryUi.actionable(subscriptionTab, () -> selectTab.onClick(subscriptionTab));
        libraryUi.actionable(playbackTab, () -> selectTab.onClick(playbackTab));
        SubscriptionSettings active = subscriptionController == null ? null : subscriptionController.state();
        if(!libraryUi.largeUi() && active!=null) {
            StandardSubscriptionPanel standard=new StandardSubscriptionPanel(libraryUi,active,active.configuration.settings(settings),
                    this::copySubscriptionLink,() -> subscriptionController.sync(true),this::editSubscription,
                    () -> confirmSubscription(this::clearCurrentSubscription));
            view.addView(standard); subscriptionStatus=standard.status;
            String currentStatus=message.isEmpty() ? subscriptionMessage : message;
            if(!currentStatus.isEmpty()) subscriptionStatus.setText(currentStatus);
            if(playbackSettingsSelected) selectTab.onClick(playbackTab);
            return;
        }
        section(view, "家庭影音订阅");
        String displayedLink = active == null ? "" : SubscriptionClient.Link.parse(active.link).url + "#********";
        EditText link = settingsField(view, "订阅链接", displayedLink, false);
        link.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        link.setSelectAllOnFocus(true);
        link.setHint("粘贴完整 HTTPS 订阅链接");
        subscriptionStatus = text(view, message.isEmpty() ? subscriptionMessage.isEmpty()
                ? active == null ? "首次填写链接后，自动获取代理、授权和画质。" : "已配置订阅 v" + active.configuration.revision
                : subscriptionMessage : message);
        subscriptionStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        if (active != null) {
            text(view, "配置来源：" + SubscriptionClient.Link.parse(active.link).url.host());
            text(view, "上次同步：" + android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", active.checkedAt));
            ConnectionSettings managed = active.configuration.settings(settings);
            text(view, managed.hasProxy() ? "代理地址：" + managed.host + ":" + managed.port : "连接方式：直连");
            text(view, "Jellyfin 服务地址：" + managed.server);
            text(view, "Jellyfin 账号：" + managed.user);
            managedQuality = text(view, "画质由服务端管理："
                    + PlaybackPolicy.RESOLUTION_LABELS[PlaybackPolicy.optionIndex(PlaybackPolicy.WIDTHS, managed.resolution, 1920)]
                    + " / " + PlaybackPolicy.RATE_LABELS[PlaybackPolicy.optionIndex(PlaybackPolicy.RATES, managed.bitrate, 4_000_000)]);
        }
        actionButton(view, "保存并连接", true, () -> {
            String entered = value(link);
            String proposed = active != null && entered.equals(displayedLink) ? active.link : entered;
            try { SubscriptionClient.Link.parse(proposed); }
            catch (Exception error) { subscriptionStatus.setText("请填写完整有效的 HTTPS 订阅链接"); return; }
            confirmSubscription(() -> {
                if (subscriptionController == null) { subscriptionStatus.setText("订阅存储不可用，请重开 App 后重试"); return; }
                pendingSubscription = null;
                subscriptionController.importLink(proposed);
            });
        });
        if (active != null) {
            actionButton(view, "复制订阅链接", false, () -> {
                SubscriptionSettings current = subscriptionController == null ? null : subscriptionController.state();
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (current == null || clipboard == null) {
                    Toast.makeText(this, "暂无可复制的订阅链接", Toast.LENGTH_SHORT).show(); return;
                }
                try {
                    ClipData clip = ClipData.newPlainText("订阅链接", current.link);
                    if (Build.VERSION.SDK_INT >= 33) {
                        PersistableBundle extras = new PersistableBundle();
                        extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
                        clip.getDescription().setExtras(extras);
                    }
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(this, "订阅链接已复制", Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {
                    Toast.makeText(this, "复制失败，请重试", Toast.LENGTH_SHORT).show();
                }
            });
            actionButton(view, "立即同步", false, () -> subscriptionController.sync(true));
            actionButton(view, "清除订阅", false, () -> confirmSubscription(() -> {
                try {
                    pendingSubscription = null;
                    if (!getSharedPreferences("connection", 0).edit().clear().commit())
                        throw new IllegalStateException("无法清除旧配置");
                    subscriptionController.clear();
                    stopPlayback(); api = null; settings = null;
                    browseMemory.clear(); savedCatalog = null; localProgress.clear();
                    libraryUi.useConnection(null);
                    subscriptionMessage = "订阅已清除，请重新填写链接";
                    settingsPage(subscriptionMessage);
                } catch (Exception error) { subscriptionStatus.setText("清除失败，请重试"); }
            }));
        }
        text(view, "更换或清除订阅需要确认密码；普通同步不会修改音轨或字幕偏好。");
        if (playbackSettingsSelected) selectTab.onClick(playbackTab);
    }

    private int size(int large, int standard) { return libraryUi.size(large,standard); }

    private void copySubscriptionLink() {
        SubscriptionSettings current=subscriptionController==null ? null : subscriptionController.state();
        ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if(current==null || clipboard==null) { Toast.makeText(this,"暂无可复制的订阅链接",Toast.LENGTH_SHORT).show(); return; }
        ClipData clip=ClipData.newPlainText("订阅链接",current.link);
        if(Build.VERSION.SDK_INT>=33) { PersistableBundle extras=new PersistableBundle(); extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE,true); clip.getDescription().setExtras(extras); }
        clipboard.setPrimaryClip(clip); Toast.makeText(this,"订阅链接已复制",Toast.LENGTH_SHORT).show();
    }

    private void editSubscription() {
        LinearLayout body=column(); body.setPadding(dp(22),dp(8),dp(22),0);
        EditText input=settingsField(body,"订阅链接","",false);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("粘贴完整 HTTPS 订阅链接");
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("更换订阅").setView(body)
                .setNegativeButton("取消",null).setPositiveButton("保存并连接",null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String proposed=value(input);
            try { SubscriptionClient.Link.parse(proposed); }
            catch(Exception error) { input.setError("请填写完整有效的 HTTPS 订阅链接"); return; }
            dialog.dismiss();
            confirmSubscription(() -> { pendingSubscription=null; subscriptionController.importLink(proposed); });
        }));
        dialog.show();
    }

    private void clearCurrentSubscription() {
        try {
            pendingSubscription=null;
            if(!getSharedPreferences("connection",0).edit().clear().commit()) throw new IllegalStateException();
            subscriptionController.clear(); stopPlayback(); api=null; settings=null;
            browseMemory.clear(); savedCatalog=null; localProgress.clear(); libraryUi.useConnection(null);
            subscriptionMessage="订阅已清除，请重新填写链接"; settingsPage(subscriptionMessage);
        } catch(Exception error) { subscriptionStatus.setText("清除失败，请重试"); }
    }

    private void displaySettings(LinearLayout view) {
        settingsSection(view,"界面显示");
        Switch mode=new Switch(this);
        mode.setId(R.id.large_ui_switch);
        mode.setText("长辈模式");
        mode.setContentDescription("长辈模式");
        mode.setTextSize(size(20,14)); mode.setTextColor(LibraryUi.TEXT);
        mode.setMinHeight(dp(size(56,48)));
        mode.setPadding(0,dp(8),0,dp(8));
        mode.setChecked(libraryUi.largeUi());
        mode.setThumbTintList(new ColorStateList(new int[][] {new int[] {android.R.attr.state_checked},new int[] {}},new int[] {LibraryUi.ACCENT_LIGHT,LibraryUi.MUTED}));
        view.addView(mode,new LinearLayout.LayoutParams(-1,-2));
        text(view,"开启后放大文字、按钮和卡片；仅保存在本机。");
        mode.setOnCheckedChangeListener((button,large) -> {
            if(large==libraryUi.largeUi()) return;
            try {
                DisplayStyle.save(this,large);
                rememberBrowsePosition();
                libraryUi.setLargeUi(large);
                settingsPage("");
            } catch(Exception error) {
                button.setChecked(libraryUi.largeUi());
                Toast.makeText(this,"显示偏好保存失败，请重试",Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void subscriptionLanguages(LinearLayout view) {
        if(!libraryUi.largeUi() && subscriptionController!=null && subscriptionController.state()!=null) {
            settingsSection(view,"播放画质");
            ConnectionSettings managed=subscriptionController.state().configuration.settings(settings);
            managedQuality=text(view,PlaybackPolicy.RESOLUTION_LABELS[PlaybackPolicy.optionIndex(PlaybackPolicy.WIDTHS,managed.resolution,1920)]
                    +" / "+PlaybackPolicy.RATE_LABELS[PlaybackPolicy.optionIndex(PlaybackPolicy.RATES,managed.bitrate,4_000_000)]);
            text(view,"画质由订阅管理，码率为音视频合计上限。").setTextSize(12);
        }
        settingsSection(view, "播放偏好");
        JellyfinApi connection = api;
        int expected = page;
        Spinner audio = qualityField(view, "首选的音频语言", LanguagePreferences.LABELS,
                LanguagePreferences.optionIndex(settings == null ? LanguagePreferences.DEFAULT : settings.audioLanguage));
        Spinner subtitles = qualityField(view, "字幕语言偏好", LanguagePreferences.LABELS,
                LanguagePreferences.optionIndex(settings == null ? LanguagePreferences.DEFAULT : settings.subtitleLanguage));
        Switch defaultAudio = new Switch(this);
        defaultAudio.setText("优先播放默认音轨");
        defaultAudio.setContentDescription("优先播放默认音轨");
        defaultAudio.setTextSize(size(20,14)); defaultAudio.setTextColor(LibraryUi.TEXT);
        defaultAudio.setPadding(0, dp(12), 0, dp(12));
        defaultAudio.setMinHeight(dp(size(56,44)));
        defaultAudio.setThumbTintList(new ColorStateList(new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                new int[] {LibraryUi.ACCENT_LIGHT, LibraryUi.MUTED}));
        view.addView(defaultAudio, new LinearLayout.LayoutParams(-1, -2));
        text(view, "开启时优先使用文件标记的默认音轨；关闭后按首选语言选择。已记忆的手动选择仍可能优先。");
        audio.setEnabled(false); subtitles.setEnabled(false); defaultAudio.setEnabled(false);
        boolean[] ready = {false};
        TextView status = text(view, "正在读取 Jellyfin 账号偏好…");
        worker.execute(() -> {
            try {
                org.json.JSONObject preferences = connection.playbackPreferences();
                onPage(expected, () -> {
                    if (api != connection) return;
                    audio.setSelection(LanguagePreferences.optionIndex(preferences.optString("AudioLanguagePreference", "")));
                    subtitles.setSelection(LanguagePreferences.optionIndex(preferences.optString("SubtitleLanguagePreference", "")));
                    defaultAudio.setChecked(preferences.optBoolean("PlayDefaultAudioTrack"));
                    audio.setEnabled(true); subtitles.setEnabled(true); defaultAudio.setEnabled(true); ready[0] = true;
                    status.setText("偏好保存在当前 Jellyfin 账号，Hub 不覆盖此设置。");
                });
            } catch (Exception ignored) { onPage(expected, () -> status.setText("暂时无法读取播放偏好，请稍后重新进入设置。")); }
        });
        actionButton(view, "保存播放偏好", false, () -> {
            if (!ready[0] || api != connection) return;
            String first = LanguagePreferences.CODES[audio.getSelectedItemPosition()];
            String second = LanguagePreferences.CODES[subtitles.getSelectedItemPosition()];
            boolean preferDefault = defaultAudio.isChecked();
            ready[0] = false;
            audio.setEnabled(false); subtitles.setEnabled(false); defaultAudio.setEnabled(false);
            status.setText("正在保存播放偏好…");
            worker.execute(() -> {
                try {
                    connection.savePlaybackPreferences(first, second, preferDefault);
                    onPage(expected, () -> { if (api == connection) status.setText("播放偏好已保存，下次播放生效"); });
                } catch (Exception ignored) { onPage(expected, () -> status.setText("保存失败，请稍后重试")); }
                finally { onPage(expected, () -> {
                    if (api != connection) return;
                    audio.setEnabled(true); subtitles.setEnabled(true); defaultAudio.setEnabled(true); ready[0] = true;
                }); }
            });
        });
    }

    private void confirmSubscription(Runnable accepted) {
        LinearLayout body = column(); body.setPadding(dp(20), dp(18), dp(20), dp(18));
        body.setBackground(libraryUi.rounded(LibraryUi.PANEL, LibraryUi.RADIUS_PANEL));
        text(body, "输入确认密码"); EditText password = settingsField(body, "确认密码", "", true);
        TextView error = text(body, "");
        AlertDialog dialog = new AlertDialog.Builder(this).setView(body).create();
        actionRow(body, "取消", dialog::dismiss, "确认", () -> {
            if (!SETTINGS_CONFIRMATION.equals(value(password))) { error.setText("确认密码错误，请重新输入"); return; }
            dialog.dismiss(); hideKeyboard(password); accepted.run();
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(Math.min(dp(340), getResources().getDisplayMetrics().widthPixels - dp(36)), -2);
        }
        password.requestFocus();
    }

    private void applySubscription(SubscriptionController.Prepared prepared) {
        if (closing || subscriptionController == null || !subscriptionController.isCurrent(prepared)) return;
        ConnectionSettings next = prepared.state.configuration.settings(settings);
        boolean changed = prepared.api != null || !ManagedConfiguration.sameConnection(settings, next);
        if (changed && playback != null) {
            pendingSubscription = prepared; subscriptionController.pending(prepared); return;
        }
        try {
            subscriptionController.activate(prepared);
            settings = next; pendingSubscription = null;
            if (!changed && api != null) return;
            if (prepared.api == null) { connect(next); return; }
            rememberBrowsePosition(); browseMemory.clear(); savedCatalog = null; localProgress.clear(); homeDirty = false;
            api = prepared.api; libraryUi.useConnection(api); homePage();
        } catch (Exception ignored) {
            subscriptionMessage = "订阅应用失败，原有配置已保留";
            if (subscriptionStatus != null && subscriptionStatus.isAttachedToWindow()) subscriptionStatus.setText(subscriptionMessage);
        }
    }

    private void showBlockedSubscription() {
        if (closing || playback != null) return;
        api = null; settings = null;
        rememberBrowsePosition(); browseMemory.clear(); savedCatalog = null; localProgress.clear();
        libraryUi.useConnection(null);
        settingsPage(subscriptionMessage);
    }

    private void settingsSection(LinearLayout view, String title) {
        View divider = new View(this);
        divider.setBackgroundColor(LibraryUi.LINE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.topMargin = dp(12);
        view.addView(divider, params);
        TextView label=libraryUi.label(title,size(26,12),libraryUi.largeUi() ? LibraryUi.TEXT : LibraryUi.MUTED,libraryUi.largeUi());
        label.setPadding(0,dp(size(18,20)),0,dp(size(9,12)));
        view.addView(label);
    }

    private EditText settingsField(LinearLayout parent, String title, String value, boolean secret) {
        TextView label = libraryUi.label(title, size(20,14), LibraryUi.MUTED, false);
        label.setPadding(0, dp(8), 0, 0);
        parent.addView(label);
        EditText input = field(parent, title, value, secret);
        input.setHint("");
        input.setId(View.generateViewId());
        label.setLabelFor(input.getId());
        input.setTextSize(size(22,14));
        input.setMinHeight(dp(size(58,44)));
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
        if(libraryUi.largeUi()) text(group, title).setTextSize(20);
        else {
            group.setOrientation(LinearLayout.HORIZONTAL); group.setGravity(Gravity.CENTER_VERTICAL); group.setMinimumHeight(dp(58));
            group.addView(libraryUi.label(title,14,LibraryUi.TEXT,false),new LinearLayout.LayoutParams(0,-2,1));
        }
        Spinner spinner = new Spinner(this, Spinner.MODE_DROPDOWN);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item,
                new ArrayList<>(java.util.Arrays.asList(options))) {
            private View readable(View view) {
                TextView label = (TextView) view;
                label.setTextSize(size(22,14));
                label.setTextColor(LibraryUi.TEXT);
                label.setMinHeight(dp(size(58,44)));
                label.setGravity(Gravity.CENTER_VERTICAL);
                return view;
            }
            @Override public View getView(int position, View reuse, android.view.ViewGroup parent) {
                return readable(super.getView(position, reuse, parent));
            }
            @Override public View getDropDownView(int position, View reuse, android.view.ViewGroup parent) {
                return readable(super.getDropDownView(position, reuse, parent));
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selection);
        spinner.setContentDescription(title);
        spinner.setBackgroundTintList(ColorStateList.valueOf(0xff575757));
        spinner.setPopupBackgroundDrawable(libraryUi.rounded(LibraryUi.PANEL, LibraryUi.RADIUS_PANEL));
        if(!libraryUi.largeUi()) spinner.setDropDownWidth(dp(220));
        group.addView(spinner, new LinearLayout.LayoutParams(libraryUi.largeUi() ? -1 : dp(150), dp(size(58,48))));
        return spinner;
    }

    @SuppressWarnings("unchecked")
    private String[] languageOptions(Spinner spinner, String language) {
        String normalized = LanguagePreferences.normalize(language);
        String[] codes = LanguagePreferences.CODES;
        String[] labels = LanguagePreferences.LABELS;
        int selected = -1;
        for (int i = 0; i < codes.length; i++) if (codes[i].equals(normalized)) { selected = i; break; }
        if (selected < 0) {
            selected = codes.length;
            codes = java.util.Arrays.copyOf(codes, selected + 1);
            labels = java.util.Arrays.copyOf(labels, selected + 1);
            codes[selected] = language;
            labels[selected] = language + "（账号设置）";
        }
        ArrayAdapter<String> adapter = (ArrayAdapter<String>) spinner.getAdapter();
        adapter.clear();
        adapter.addAll(labels);
        spinner.setSelection(selected);
        return codes;
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

    private void connect(ConnectionSettings candidate) {
        connect(candidate, 0);
    }

    private void connect(ConnectionSettings candidate, long splashReadyAt) {
        connect(candidate, splashReadyAt, false);
    }

    private void connect(ConnectionSettings candidate, long splashReadyAt, boolean recoverOnFailure) {
        if (splashReadyAt == 0) {
            LinearLayout view = panel("正在连接 Jellyfin", false, () -> settingsPage(""));
            text(view, candidate.hasProxy() ? "通过家庭 HTTP 代理连接。" : "正在直连 Jellyfin。");
            button(view, "连接设置", () -> settingsPage(""));
        }
        int expected = page;
        worker.execute(() -> {
            try {
                JellyfinApi connected = new JellyfinApi(candidate, device);
                connected.useManualChoices(new ManualTrackChoices(getSharedPreferences("manual-track-choices", MODE_PRIVATE)));
                connected.login();
                afterSplash(expected, splashReadyAt, () -> {
                    settings = candidate;

                    rememberBrowsePosition();
                    browseMemory.clear();
                    savedCatalog = null;
                    localProgress.clear();
                    homeDirty = false;
                    api = connected;
                    libraryUi.useConnection(connected);
                    homePage();
                });
            } catch (Exception error) {
                afterSplash(expected, splashReadyAt, () -> {
                    if (recoverOnFailure && subscriptionController != null
                            && subscriptionController.state() != null && !subscriptionController.blocked()) {
                        recoverInitialConnection();
                    } else connectionFailed(candidate, error);
                });
            }
        });
    }

    private void afterSplash(int expected, long readyAt, Runnable action) {
        ui.postAtTime(() -> {
            if (expected == page && !isFinishing() && !isDestroyed()) action.run();
        }, Math.max(SystemClock.uptimeMillis(), readyAt));
    }

    private void connectionFailed(ConnectionSettings candidate, Exception error) {
        LinearLayout view = panel("连接失败", false, () -> settingsPage(""));
        text(view, message(error, "连接失败"));
        button(view, "重试连接", () -> connect(candidate));
        button(view, "连接设置", () -> settingsPage(""));
    }

    private void recoverInitialConnection() {
        LinearLayout view = panel("正在更新连接配置", false, () -> settingsPage(""));
        text(view, "首次连接失败，正在同步订阅后重试一次。");
        button(view, "连接设置", () -> settingsPage(""));
        int expected = page;
        connectionRecovery = () -> {
            if (closing || page != expected || api != null || subscriptionController == null) return;
            SubscriptionSettings latest = subscriptionController.state();
            if (latest != null && !latest.blocked) connect(latest.configuration.settings(settings));
        };
        // If a foreground refresh is already running, consume its outcome instead
        // of starting a second request. Retries use connect's non-recovery overload.
        subscriptionController.sync(true);
    }

    private void finishConnectionRecovery() {
        Runnable retry = connectionRecovery;
        connectionRecovery = null;
        if (retry != null) retry.run();
    }

    private boolean homePlayLoading;

    private void rememberBrowsePosition() {
        if (rememberedGrid != null && rememberedLibrary != null) rememberedLibrary.anchor = rememberedGrid.anchor();
        rememberedGrid = null;
        if (rememberedScroll != null) {
            if (rememberedKey != null) browseMemory.position(rememberedKey, rememberedScroll.getScrollY(),
                    rememberedScroll.getScrollY()==0 ? null : BrowseAnchor.firstVisible(rememberedScroll),libraryUi.largeUi());
            if (rememberedLibrary != null) rememberedLibrary.scrollY = rememberedScroll.getScrollY();
        }
        rememberedScroll = null;
        rememberedKey = null;
        rememberedLibrary = null;
    }

    private void trackBrowsePosition(LinearLayout view, String key) {
        rememberedScroll = (ScrollView) view.getParent();
        rememberedKey = key;
    }

    private void restoreBrowsePosition() {
        ScrollView scroll = rememberedScroll;
        String key = rememberedKey;
        int expected = page;
        BrowseMemory.Anchor anchor = key == null ? null : browseMemory.anchor(key);
        if(scroll != null && anchor != null && anchor.large != libraryUi.largeUi()) {
            scroll.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    scroll.getViewTreeObserver().removeOnPreDrawListener(this);
                    if(page==expected && !BrowseAnchor.restore(scroll,anchor.id)) scroll.scrollTo(0,browseMemory.position(key));
                    return true;
                }
            });
            scroll.requestLayout();
            return;
        }
        if (scroll != null && key != null) scroll.post(() -> {
            if (page == expected) scroll.scrollTo(0, browseMemory.position(key));
        });
    }

    private JellyfinApi.Item withLocalProgress(JellyfinApi.Item item) {
        LocalProgress progress = localProgress.get(item.id);
        if (progress != null) {
            item.resumeMs = progress.ended ? 0 : progress.position;
            item.played = progress.ended;
        }
        return item;
    }

    private void rememberPlaybackProgress() {
        if (playback == null || player == null || !playbackStarted) return;
        // During a failed replacement startup, item can still name the previous episode.
        if (stream == null || !stream.itemId.equals(playback.item.id)) return;
        long position = player.getCurrentPosition();
        boolean ended = player.getPlaybackState() == Player.STATE_ENDED;
        LocalProgress progress = new LocalProgress(playback.item, position, ended);
        localProgress.remove(playback.item.id);
        localProgress.put(playback.item.id, progress);
        while (localProgress.size() > 64) localProgress.remove(localProgress.keySet().iterator().next());
        withLocalProgress(playback.item);
        if (!playback.item.seriesId.isEmpty()) {
            String key = "target:" + playback.item.seriesId;
            if (ended) browseMemory.remove(key);
            else browseMemory.put(key, playback.item, SystemClock.uptimeMillis());
        }
        homeDirty = true;
    }

    private HomeFeed.Result<JellyfinApi.Item> localHome(HomeFeed.Result<JellyfinApi.Item> cached) {
        List<JellyfinApi.Item> items = new ArrayList<>();
        for (JellyfinApi.Item item : cached.items) {
            withLocalProgress(item);
            if (!cached.resume || item.resumeMs > 0 && !item.played) items.add(item);
        }
        for (LocalProgress progress : localProgress.values()) {
            if (progress.ended || progress.position <= 0) continue;
            if (!cached.resume) items.clear();
            items.removeIf(item -> item.id.equals(progress.item.id));
            items.add(0, withLocalProgress(progress.item));
            cached = new HomeFeed.Result<>(true, items);
        }
        return new HomeFeed.Result<>(cached.resume, items);
    }

    private void homePage() {
        homePlayLoading = false;
        stopPlayback();
        browseTab = BrowseTab.HOME;
        LinearLayout view = panel(getString(R.string.app_name), true, null, browseTab);
        view.setPadding(contentGutter(), dp(22), contentGutter(), dp(32));
        trackBrowsePosition(view, "home");
        HomeListing listing = new HomeListing(view);
        HomeFeed.Result<JellyfinApi.Item> cached = browseMemory.get("home");
        if (cached != null) {
            listing.render(localHome(cached));
            listing.loaded = true;
            restoreBrowsePosition();
            if (homeDirty || browseMemory.stale("home", SystemClock.uptimeMillis(), 120_000)) listing.load(false);
        } else listing.load(false);
    }

    private final class HomeListing {
        private final LinearLayout view;
        private final ScrollView scroll;
        private final SwipeRefreshLayout refresh;
        private final int expected = page;
        private boolean loading, loaded;
        private String renderedContent = "";
        private final LoadingUi.RefreshNotice notice;

        HomeListing(LinearLayout view) {
            this.view = view;
            scroll = (ScrollView) view.getParent();
            LinearLayout root = (LinearLayout) scroll.getParent();
            int index = root.indexOfChild(scroll);
            android.view.ViewGroup.LayoutParams bounds = scroll.getLayoutParams();
            root.removeView(scroll);
            refresh = new SwipeRefreshLayout(MainActivity.this);
            refresh.setId(R.id.home_refresh);
            refresh.setColorSchemeColors(LibraryUi.ACCENT_LIGHT);
            refresh.setProgressBackgroundColorSchemeColor(LibraryUi.PANEL);
            refresh.addView(scroll, new android.view.ViewGroup.LayoutParams(-1, -1));
            root.addView(refresh, index, bounds);
            notice = new LoadingUi.RefreshNotice(root, refresh, libraryUi, ui,
                    () -> page == expected && loading, () -> load(true));
            refresh.setOnChildScrollUpCallback((parent, child) ->
                    loading || homePlayLoading || scroll.canScrollVertically(-1));
            refresh.setOnRefreshListener(() -> load(true));
        }

        void load(boolean requestedRefresh) {
            if (page != expected || loading) return;
            loading = true;
            if (!loaded) view.removeAllViews();
            boolean first = !loaded;
            LoadingUi.Pending pending = first ? new LoadingUi.Pending(view, libraryUi, ui, LoadingUi.Kind.HOME, null,
                    () -> page == expected && loading) : null;
            if (requestedRefresh) { refresh.setRefreshing(true); notice.start(); }
            JellyfinApi connection = api;
            // Queue after playback's Stopped report so an old server position cannot win the race.
            worker.execute(() -> {
                try {
                    HomeFeed.Result<JellyfinApi.Item> result = HomeFeed.load(new HomeFeed.Source<JellyfinApi.Item>() {
                        @Override public List<JellyfinApi.Item> resume() throws Exception {
                            List<JellyfinApi.Item> items = connection.resumeItems();
                            for (java.util.Iterator<JellyfinApi.Item> it = items.iterator(); it.hasNext();) {
                                JellyfinApi.Item item = it.next();
                                if (item.resumeMs <= 0 || item.played
                                        || item.durationMs > 0 && item.resumeMs >= item.durationMs) it.remove();
                            }
                            return items;
                        }
                        @Override public List<JellyfinApi.Item> latest() throws Exception {
                            return recentItems(connection.latestItems());
                        }
                    });
                    onPage(expected, () -> {
                        if (pending != null) pending.finish();
                        int oldY = scroll.getScrollY();
                        // Only a successful Stopped report allows the server result to replace our local progress.
                        localProgress.values().removeIf(progress -> progress.reported);
                        browseMemory.put("home", result, SystemClock.uptimeMillis());
                        homeDirty = false;
                        render(localHome(result));
                        loaded = true;
                        loading = false;
                        refresh.setRefreshing(false);
                        notice.clear();
                        if (first) LoadingUi.ready(view);
                        scroll.post(() -> { if (page == expected) scroll.scrollTo(0, requestedRefresh ? 0 : oldY); });
                        Log.i(JellyfinApi.TAG, "Home section=" + (result.resume ? "RESUME" : "LATEST")
                                + " items=" + result.items.size() + " refresh=" + requestedRefresh);
                    });
                } catch (Exception error) {
                    onPage(expected, () -> {
                        loading = false;
                        refresh.setRefreshing(false);
                        if (pending != null) pending.finish();
                        if (loaded && requestedRefresh) notice.failed(message(error, "请检查网络后重试。"));
                        else if (loaded) Toast.makeText(MainActivity.this, "同步失败，保留已有内容", Toast.LENGTH_SHORT).show();
                        else LoadingUi.failure(view, libraryUi, message(error, "读取首页失败"), () -> load(false), false);
                    });
                }
            });
        }

        void render(HomeFeed.Result<JellyfinApi.Item> result) {
            StringBuilder content = new StringBuilder(Boolean.toString(result.resume));
            for (JellyfinApi.Item item : result.items) content.append('|').append(item.id).append(':')
                    .append(item.resumeMs).append(':').append(item.played).append(':').append(item.title);
            if (renderedContent.equals(content.toString())) return;
            renderedContent = content.toString();
            view.removeAllViews();
            TextView heading = libraryUi.label(result.resume ? "继续观看" : "最近添加", size(26,22), LibraryUi.TEXT, true);
            heading.setPadding(0, 0, 0, dp(16));
            if(libraryUi.largeUi()) view.addView(heading);
            else {
                LinearLayout headingRow=new LinearLayout(MainActivity.this); headingRow.setGravity(Gravity.CENTER_VERTICAL);
                headingRow.setPadding(0,0,0,dp(17)); heading.setPadding(0,0,0,0);
                headingRow.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
                headingRow.addView(libraryUi.label(result.items.size()+" 项",12,LibraryUi.MUTED,false));
                view.addView(headingRow);
            }
            if (result.items.isEmpty()) LoadingUi.empty(view, libraryUi, "暂无可播放的影片", "续播记录和最近添加均为空。");
            else view.addView(libraryUi.homeList(result.items, result.resume, screenWidth(),
                    item -> openHomeDetails(item), item -> playHomeItem(item)));
        }
    }

    private void openHomeDetails(JellyfinApi.Item item) {
        if ("Episode".equals(item.type) && item.seriesId.isEmpty()) {
            android.widget.Toast.makeText(this, "无法读取所属剧集，请刷新后重试。", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        detailsPage(item, this::homePage);
    }

    private void playHomeItem(JellyfinApi.Item item) {
        if (subscriptionController != null && subscriptionController.blocked()) { showBlockedSubscription(); return; }
        if (!item.series) {
            playbackPage(item, item.resumeMs, this::homePage);
            return;
        }
        if (homePlayLoading) return;
        homePlayLoading = true;
        int expected = page;
        JellyfinApi connection = api;
        browse.execute(() -> {
            try {
                JellyfinApi.Item target = connection.seriesPlaybackTarget(item);
                onPage(expected, () -> {
                    homePlayLoading = false;
                    if (target == null) android.widget.Toast.makeText(this, "暂无可播放的单集。", android.widget.Toast.LENGTH_SHORT).show();
                    else playbackPage(target, target.resumeMs, this::homePage);
                });
            } catch (Exception error) {
                onPage(expected, () -> {
                    homePlayLoading = false;
                    android.widget.Toast.makeText(this, message(error, "读取播放单集失败"), android.widget.Toast.LENGTH_LONG).show();
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
        librariesPage(savedCatalog);
    }

    private void librariesPage(LibraryCatalog catalog) {
        browseTab = BrowseTab.LIBRARY;
        LinearLayout view = panel("媒体库", true, this::homePage, browseTab);
        if (catalog != null) {
            showCatalog(view, catalog);
            return;
        }
        int expected = page;
        LoadingUi.Pending pending = new LoadingUi.Pending(view, libraryUi, ui, LoadingUi.Kind.GRID, null, () -> page == expected);
        JellyfinApi connection = api;
        browse.execute(() -> {
            try {
                LibraryCatalog loaded = new LibraryCatalog();
                for (JellyfinApi.Item item : connection.libraries()) {
                    if (!item.collectionType.matches("music|audiobooks|books|photos|playlists|livetv")) {
                        loaded.tabs.add(new LibraryLocation(item, true));
                    }
                }
                ui.post(() -> {
                    if (api != connection) return;
                    if (savedCatalog == null) savedCatalog = loaded;
                    if (page != expected) return;
                    if (pending.wasShown()) view.setTag(R.id.loading_handoff, true);
                    pending.finish();
                    showCatalog(view, savedCatalog);
                });
            } catch (Exception error) {
                onPage(expected, () -> {
                    pending.finish();
                    LoadingUi.failure(view, libraryUi, message(error, "读取媒体库失败"), this::librariesPage, false);
                });
            }
        });
    }

    private void showCatalog(LinearLayout view, LibraryCatalog catalog) {
        if (catalog.tabs.isEmpty()) {
            LoadingUi.empty(view, libraryUi, "暂无可见媒体库", "请确认当前账号的媒体库访问权限。");
            return;
        }
        if (catalog.selected == null) catalog.selected = catalog.tabs.get(0);
        catalog.selected.lastAccess = SystemClock.uptimeMillis();
        // Keep the current listing intact; bound only inactive tab data.
        List<LibraryLocation> inactive = new ArrayList<>(catalog.tabs);
        inactive.remove(catalog.selected);
        Collections.sort(inactive, (left, right) -> Long.compare(left.lastAccess, right.lastAccess));
        int retained = 0;
        for (LibraryLocation location : inactive) retained += location.items.size();
        for (int i = 0; i < inactive.size(); i++) {
            LibraryLocation location = inactive.get(i);
            if (retained <= 1500 && inactive.size() - i <= 3) break;
            retained -= location.items.size();
            location.items.clear(); location.loaded = false; location.more = true;
        }
        ScrollView content = (ScrollView) view.getParent();
        LinearLayout root = (LinearLayout) content.getParent();
        HorizontalScrollView tabs = new HorizontalScrollView(this);
        tabs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(contentGutter(), 0, contentGutter(), dp(size(16,12)));
        tabs.addView(row);
        root.addView(tabs, root.indexOfChild(content), new LinearLayout.LayoutParams(-1, -2));
        view.setPadding(contentGutter(), 0, contentGutter(), dp(24));
        for (LibraryLocation location : catalog.tabs) {
            TextView chip = addLibraryTab(row, location.folder.title,
                    location == catalog.selected);
            libraryUi.actionable(chip, () -> {
                if (location == catalog.selected) return;
                if (rememberedGrid != null) catalog.selected.anchor = rememberedGrid.anchor();
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
        LibraryLocation selected;
        int tabScrollX;

    }

    private static final class LibraryLocation {
        final JellyfinApi.Item folder;
        final boolean catalog;
        JellyfinApi.Item details;
        final List<JellyfinApi.Item> items = new ArrayList<>();
        int scrollY;
        RecycledMediaGrid.Anchor anchor;
        long lastAccess;
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

    private void collectionPage(LibraryLocation location, Runnable back) {
        stopPlayback();
        LinearLayout view = panel("合集详情", true, back, browseTab);
        int expected = page;
        view.setPadding(0, 0, 0, dp(24));
        Runnable render = () -> {
            LinearLayout playbackArea = showDetails(view, location.details, back, null);
            TextView play = detailPlaybackButton("播放", true, null);
            play.setContentDescription("播放合集");
            LinearLayout.LayoutParams actionBounds = new LinearLayout.LayoutParams(-1, -2);
            actionBounds.topMargin = dp(12);
            playbackArea.addView(play, actionBounds);
            libraryUi.actionable(play, () -> startCollection(play, location, back));
            showLibraryPage(view, location, () -> collectionPage(location, back));
        };
        if (location.details != null) { render.run(); return; }
        JellyfinApi connection = api;
        LoadingUi.Pending pending = new LoadingUi.Pending(view, libraryUi, ui, LoadingUi.Kind.DETAIL,
                location.folder.title, () -> page == expected);
        browse.execute(() -> {
            try {
                JellyfinApi.Item details = connection.details(location.folder);
                onPage(expected, () -> {
                    pending.finish();
                    location.details = details;
                    render.run();
                    LoadingUi.ready(view);
                });
            } catch (Exception error) {
                onPage(expected, () -> {
                    pending.finish();
                    LoadingUi.failure(view, libraryUi, message(error, "读取合集失败"), () -> collectionPage(location, back), false);
                });
            }
        });
    }

    private void startCollection(TextView button, LibraryLocation location, Runnable back) {
        if (!button.isEnabled()) return;
        JellyfinApi connection = api;
        int expected = page;
        button.setEnabled(false);
        button.setText("正在准备…");
        browse.execute(() -> {
            try {
                CollectionPlayback queue = CollectionPlayback.load(connection, location.folder);
                onPage(expected, () -> {
                    if (api != connection) return;
                    button.setEnabled(true); button.setText("播放");
                    // Collection entry follows the fresh server watched state, including near-end completion.
                    JellyfinApi.Item target = queue.start();
                    if (target == null) {
                        Toast.makeText(this, "此合集中暂无可播放的电影", Toast.LENGTH_SHORT).show(); return;
                    }
                    playbackPage(target, null, () -> collectionPage(location, back), queue);
                });
            } catch (Exception error) {
                onPage(expected, () -> {
                    if (api != connection) return;
                    button.setEnabled(true); button.setText("播放");
                    Toast.makeText(this, "暂时无法读取合集，请重试", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showLibraryPage(LinearLayout view, LibraryLocation location, Runnable reopen) {
        new LibraryListing(view, location, reopen).show();
    }

    private final class LibraryListing {
        private final RecycledMediaGrid recycler;
        private final SwipeRefreshLayout refresh;
        private final LinearLayout grid = column();
        private final LinearLayout footer = column();
        private final LibraryLocation location;
        private final Runnable reopen;
        private final int expected = page;
        private final LoadingUi.RefreshNotice notice;
        private final boolean collection;
        private boolean loading;
        private boolean failed;

        LibraryListing(LinearLayout view, LibraryLocation location, Runnable reopen) {
            this.location = location;
            this.reopen = reopen;
            collection = location.folder != null && location.folder.collection && !location.catalog;
            if (Boolean.TRUE.equals(view.getTag(R.id.loading_handoff))) {
                grid.setTag(R.id.loading_handoff, true);
                view.setTag(R.id.loading_handoff, null);
            }
            ScrollView oldScroll = (ScrollView) view.getParent();
            LinearLayout root = (LinearLayout) oldScroll.getParent();
            int index = root.indexOfChild(oldScroll);
            android.view.ViewGroup.LayoutParams bounds = oldScroll.getLayoutParams();
            root.removeView(oldScroll);
            oldScroll.removeView(view);
            // A collection's existing detail composition becomes a full-span recycled header.
            view.setPadding(0, 0, 0, 0);
            recycler = new RecycledMediaGrid(libraryUi, screenWidth(), contentGutter(),
                    collection ? view : null, grid, footer, this::open);
            refresh = new SwipeRefreshLayout(MainActivity.this);
            refresh.setColorSchemeColors(LibraryUi.ACCENT_LIGHT);
            refresh.setProgressBackgroundColorSchemeColor(LibraryUi.PANEL);
            refresh.addView(recycler, new android.view.ViewGroup.LayoutParams(-1, -1));
            root.addView(refresh, index, bounds);
            notice = new LoadingUi.RefreshNotice(root, refresh, libraryUi, ui,
                    () -> page == expected && loading, () -> load(true));
            footer.setMinimumHeight(dp(64));
            refresh.setOnChildScrollUpCallback((parent, child) -> loading || recycler.canScrollVertically(-1));
            refresh.setOnRefreshListener(() -> load(true));
        }

        void show() {
            rememberedGrid = recycler;
            rememberedLibrary = location;
            recycler.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                @Override public void onScrolled(androidx.recyclerview.widget.RecyclerView view, int dx, int dy) {
                    if (dy > 0) loadNearEnd();
                }
            });
            recycler.addOnLayoutChangeListener((target, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                    recycler.post(this::loadNearEnd));
            if (location.loaded) {
                appendItems(false);
                recycler.restore(location.anchor);
            } else {
                recycler.display(location.items, collection, false);
                load();
            }
        }

        private void loadNearEnd() {
            if (page == expected && location.loaded && !failed && recycler.nearEnd()) load();
        }

        private void appendItems(boolean preserve) {
            for (JellyfinApi.Item item : location.items) withLocalProgress(item);
            footer.removeAllViews();
            grid.removeAllViews();
            if (location.items.isEmpty()) LoadingUi.empty(grid, libraryUi,
                    collection ? "这个合集暂无内容" : "这个媒体库暂无内容",
                    collection ? "可以返回查看其他影片。" : "可以切换其他媒体库查看。");
            else if (!location.more) footer.addView(LoadingUi.line(libraryUi, "已显示全部", false));
            recycler.display(location.items, collection, preserve);
        }

        private void open(JellyfinApi.Item item) {
            location.anchor = recycler.anchor();
            openItem(item, reopen);
        }

        private void load() { load(false); }

        private void load(boolean replace) {
            if (page != expected || loading || !replace && !location.more) return;
            loading = true;
            failed = false;
            int offset = replace ? 0 : location.items.size();
            footer.removeAllViews();
            boolean first = !location.loaded;
            if (first) grid.removeAllViews();
            LoadingUi.Pending pending = first ? new LoadingUi.Pending(grid, libraryUi, ui, LoadingUi.Kind.GRID, null,
                    () -> page == expected && loading, true) : !replace ? new LoadingUi.Pending(footer, libraryUi, ui, LoadingUi.Kind.FOOTER, null,
                    () -> page == expected && loading, true) : null;
            if (replace) { refresh.setRefreshing(true); notice.start(); } else notice.clear();
            JellyfinApi connection = api;
            browse.execute(() -> {
                try {
                    JellyfinApi.LibraryItemsPage next = connection.libraryItems(location.folder, location.catalog, offset);
                    onPage(expected, () -> {
                        if (pending != null) pending.finish();
                        if (replace) {
                            location.items.clear();
                            location.scrollY = 0;
                            location.anchor = null;
                        }
                        location.items.addAll(next.items);
                        location.loaded = true;
                        location.more = next.more;
                        appendItems(!replace);
                        if (replace) recycler.scrollToPosition(0);
                        loading = false;
                        refresh.setRefreshing(false);
                        notice.clear();
                        if (first) LoadingUi.ready(grid);
                        recycler.post(this::loadNearEnd);
                        Log.i(JellyfinApi.TAG, "Library page offset=" + offset + " items=" + next.items.size()
                                + " more=" + next.more + " refresh=" + replace);
                    });
                } catch (Exception error) {
                    onPage(expected, () -> {
                        loading = false;
                        refresh.setRefreshing(false);
                        failed = true;
                        if (pending != null) pending.finish();
                        if (replace && location.loaded) {
                            notice.failed(message(error, "请检查网络后重试。"));
                            if (!location.more) footer.addView(LoadingUi.line(libraryUi, "已显示全部", false));
                        } else LoadingUi.failure(first ? grid : footer, libraryUi,
                                message(error, first ? "读取媒体库失败" : "读取下一页失败"), () -> load(replace), !first);
                    });
                }
            });
        }
    }
    private void openItem(JellyfinApi.Item item, Runnable back) {
        if (item.collection) collectionPage(new LibraryLocation(item, false), back);
        else if ("Episode".equals(item.type)) playbackPage(item, null, back);
        else if (item.folder && !item.series) libraryPage(new LibraryLocation(item, false), back);
        else detailsPage(item, back);
    }

    private void detailsPage(JellyfinApi.Item initial, Runnable back) {
        detailsPage(initial, back, null);
    }

    private void detailsPage(JellyfinApi.Item initial, Runnable back, String seasonId) {
        stopPlayback();
        LinearLayout view = panel("媒体详情", true, back, browseTab);
        int expected = page;
        view.setPadding(0, 0, 0, dp(24));
        String id = "Episode".equals(initial.type) ? initial.seriesId : initial.id;
        String key = "detail:" + id;
        String restoredSeason = seasonId == null ? browseMemory.get("selectedSeason:" + id) : seasonId;
        trackBrowsePosition(view, key);
        JellyfinApi.Item cached = browseMemory.get(key);
        if (cached != null) {
            showDetails(view, withLocalProgress(cached), back, restoredSeason);
            restoreBrowsePosition();
            if (browseMemory.stale(key, SystemClock.uptimeMillis(), 600_000)) {
                JellyfinApi connection = api;
                worker.execute(() -> {
                    try {
                        JellyfinApi.Item updated = connection.details(id);
                        ui.post(() -> {
                            if (api == connection) browseMemory.put(key, withLocalProgress(updated), SystemClock.uptimeMillis());
                        });
                    } catch (Exception ignored) { /* Keep readable cached details on failure. */ }
                });
            }
            return;
        }
        String title = "Episode".equals(initial.type) ? initial.seriesName : initial.title;
        LoadingUi.Pending pending = new LoadingUi.Pending(view, libraryUi, ui, LoadingUi.Kind.DETAIL, title, () -> page == expected);
        JellyfinApi connection = api;
        worker.execute(() -> {
            try {
                JellyfinApi.Item item = connection.details("Episode".equals(initial.type) ? initial.seriesId : initial.id);
                ui.post(() -> {
                    if (api != connection) return;
                    browseMemory.put(key, item, SystemClock.uptimeMillis());
                    if (page != expected) return;
                    pending.finish();
                    showDetails(view, withLocalProgress(item), back, restoredSeason);
                    LoadingUi.ready(view);
                    restoreBrowsePosition();
                });
            } catch (Exception error) {
                onPage(expected, () -> {
                    pending.finish();
                    LoadingUi.failure(view, libraryUi, message(error, "读取影片详情失败"), () -> detailsPage(initial, back, seasonId), false);
                });
            }
        });
    }

    private LinearLayout showDetails(LinearLayout pageView, JellyfinApi.Item item, Runnable back, String seasonId) {
        pageView.setPadding(0, 0, 0, dp(24));
        int screenDp = Math.round(browsingWidthPixels / getResources().getDisplayMetrics().density);
        int coverHeight = size(Math.max(185, Math.min(228,
                Math.round(browsingHeightDp * 0.27f))),246);
        int posterWidth = size(Math.max(94, Math.min(114, Math.round(screenDp * 0.27f))),94);
        int posterHeight = posterWidth * 3 / 2;
        int overlap = Math.round(posterWidth * 0.88f);
        pageView.addView(libraryUi.hero(item), new LinearLayout.LayoutParams(-1, dp(coverHeight)));
        LinearLayout ribbon = column();
        ribbon.setBackgroundColor(libraryUi.largeUi() ? LibraryUi.PANEL : Color.TRANSPARENT);
        ribbon.setPadding(contentGutter(), 0, contentGutter(), dp(size(18,0)));
        LinearLayout.LayoutParams ribbonBounds=new LinearLayout.LayoutParams(-1,-2);
        if(!libraryUi.largeUi()) ribbonBounds.topMargin=-dp(58);
        pageView.addView(ribbon,ribbonBounds);
        LinearLayout identity = new LinearLayout(this);
        identity.setClipChildren(false);
        identity.setClipToPadding(false);
        ribbon.setClipChildren(false);
        ribbon.setClipToPadding(false);
        pageView.setClipChildren(false);
        ribbon.addView(identity);
        FrameLayout posterSlot = new FrameLayout(this);
        posterSlot.setClipChildren(false);
        posterSlot.setClipToPadding(false);
        LinearLayout.LayoutParams posterSlotBounds = new LinearLayout.LayoutParams(dp(posterWidth), dp(libraryUi.largeUi() ? posterHeight-overlap : posterHeight));
        posterSlotBounds.gravity = Gravity.BOTTOM;
        identity.addView(posterSlot, posterSlotBounds);
        FrameLayout.LayoutParams posterBounds = new FrameLayout.LayoutParams(-1, dp(posterHeight), Gravity.BOTTOM);
        posterSlot.addView(libraryUi.detailPoster(item), posterBounds);
        LinearLayout heading = column();
        heading.setGravity(libraryUi.largeUi() ? Gravity.CENTER : Gravity.START);
        heading.setPadding(0, dp(size(10,0)), 0, dp(size(10,0)));
        LinearLayout.LayoutParams headingBounds = new LinearLayout.LayoutParams(0, -2, 1);
        headingBounds.setMarginStart(dp(size(14,17)));
        identity.setGravity(libraryUi.largeUi() ? Gravity.CENTER_VERTICAL : Gravity.BOTTOM);
        identity.addView(heading, headingBounds);
        LinearLayout view = column();
        view.setPadding(contentGutter(), dp(20), contentGutter(), 0);
        LinearLayout.LayoutParams body = new LinearLayout.LayoutParams(-1, -2);
        pageView.addView(view, body);
        int titleSize = size(30,23);
        TextView title = libraryUi.label(item.title, titleSize, LibraryUi.TEXT, true);
        if(!libraryUi.largeUi()) { title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END); }
        title.setIncludeFontPadding(false);
        title.setGravity(libraryUi.largeUi() ? Gravity.CENTER : Gravity.START);
        heading.addView(title, new LinearLayout.LayoutParams(-1, -2));
        List<String> metadata = new ArrayList<>();
        if (!item.collection && item.year > 0) metadata.add(Integer.toString(item.year));
        if (item.collection) {
            metadata.add("合集");
            if (item.childCount >= 0) metadata.add(item.childCount + " 项");
        }
        if (item.series) metadata.add("剧集");
        if ("Movie".equals(item.type)) metadata.add("电影");
        if (!item.collection && item.durationMs > 0) metadata.add(item.durationMs / 60_000 + " 分钟");
        if (!metadata.isEmpty()) {
            TextView info = libraryUi.label(TextUtils.join(" · ", metadata), size(18,12), LibraryUi.MUTED, false);
            info.setGravity(libraryUi.largeUi() ? Gravity.CENTER : Gravity.START);
            info.setPadding(0, dp(7), 0, 0);
            heading.addView(info);
        }
        if (!item.collection && item.played) watched(heading);
        LinearLayout playbackArea = column();
        if (item.collection && libraryUi.largeUi()) playbackArea.setPadding(dp(posterWidth + 14), 0, 0, 0);
        (libraryUi.largeUi() ? ribbon : heading).addView(playbackArea, new LinearLayout.LayoutParams(-1, -2));
        if (!item.series && !item.collection) {
            Runnable returnToDetails = () -> detailsPage(item, back);
            detailPlaybackActions(playbackArea, item, returnToDetails);
            if (item.resumeMs > 0) {
                if (item.durationMs > 0) {
                    LinearLayout.LayoutParams progress = new LinearLayout.LayoutParams(-1, dp(size(5,3)));
                    progress.topMargin = dp(size(12,20));
                    ribbon.addView(libraryUi.watchProgress(item.resumeMs, item.durationMs), progress);
                }
                text(ribbon, "已观看 " + LibraryUi.time(item.resumeMs)
                        + (item.durationMs > 0 ? " / " + LibraryUi.time(item.durationMs) : ""));
            }
        }
        if (!item.overview.isEmpty()) {
            TextView overview = text(view, item.overview);
            overview.setTextSize(size(20,14));
            overview.setLineSpacing(0, 1.65f);
            overview.setPadding(0, 0, 0, dp(16));
        }
        if (item.series) showSeasons(view, playbackArea, item, back, seasonId);
        return playbackArea;
    }

    private void detailPlaybackActions(LinearLayout heading, JellyfinApi.Item item, Runnable returnToDetails) {
            Runnable fromStart = () -> playbackPage(item, 0L, returnToDetails);
            if (item.resumeMs > 0) {
                LinearLayout actions = new LinearLayout(this) {
                    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                        int desiredWidth = 0;
                        for (int i = 0; i < getChildCount(); i++) {
                            View child = getChildAt(i);
                            child.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                            desiredWidth += child.getMeasuredWidth();
                        }
                        boolean wrap = View.MeasureSpec.getMode(widthMeasureSpec) != View.MeasureSpec.UNSPECIFIED
                                && desiredWidth + dp(6) > View.MeasureSpec.getSize(widthMeasureSpec);
                        setOrientation(wrap ? VERTICAL : HORIZONTAL);
                        for (int i = 0; i < getChildCount(); i++) {
                            LinearLayout.LayoutParams bounds = (LinearLayout.LayoutParams) getChildAt(i).getLayoutParams();
                            int gap = !wrap && i > 0 ? dp(6) : 0;
                            bounds.leftMargin = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? 0 : gap;
                            bounds.rightMargin = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? gap : 0;
                            bounds.topMargin = wrap && i > 0 ? dp(6) : 0;
                            bounds.width = wrap ? -1 : i == 0 ? 0 : -2;
                            bounds.weight = !wrap && i == 0 ? 1 : 0;
                        }
                        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                    }
                };
                actions.setGravity(libraryUi.largeUi() ? Gravity.CENTER : Gravity.START);
                LinearLayout.LayoutParams actionBounds = new LinearLayout.LayoutParams(-1, -2);
                actionBounds.topMargin = dp(12);
                heading.addView(actions, actionBounds);
                actions.addView(detailPlaybackButton("继续观看", true,
                        () -> playbackPage(item, item.resumeMs, returnToDetails)));
                actions.addView(detailPlaybackButton("从头播放", false, fromStart));
            } else {
                LinearLayout.LayoutParams actionBounds = new LinearLayout.LayoutParams(-1, -2);
                actionBounds.gravity = libraryUi.largeUi() ? Gravity.CENTER_HORIZONTAL : Gravity.START;
                actionBounds.topMargin = dp(12);
                heading.addView(detailPlaybackButton(item.played ? "重播" : "播放", true, fromStart), actionBounds);
            }
    }

    private TextView detailPlaybackButton(String label, boolean showIcon, Runnable action) {
        TextView button = libraryUi.label(label, showIcon ? size(21,13) : size(18,12),
                showIcon ? LibraryUi.ACCENT_LIGHT : LibraryUi.TEXT, showIcon);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(size(56,44)));
        button.setPadding(dp(showIcon ? 14 : 10), 0, dp(showIcon ? 14 : 10), 0);
        android.graphics.drawable.GradientDrawable background = libraryUi.rounded(showIcon ? 0x1070d5f5 : 0x0affffff, LibraryUi.RADIUS_CONTROL);
        background.setStroke(dp(1), showIcon ? 0x8070d5f5 : 0x3dffffff);
        button.setBackground(background);
        android.graphics.drawable.Drawable icon = getDrawable(R.drawable.ic_quick_play).mutate();
        icon.setTint(LibraryUi.ACCENT_LIGHT);
        icon.setBounds(0, 0, dp(size(26,18)), dp(size(26,18)));
        button.setCompoundDrawablesRelative(showIcon ? icon : null, null, null, null);
        button.setCompoundDrawablePadding(dp(6));
        if (action != null) libraryUi.actionable(button, action);
        return button;
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
        search.setTextSize(size(22,14));
        search.setPadding(dp(11), dp(8), dp(11), dp(8));
        search.setBackground(inputOutline());
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setSelection(search.length());
        // Keep the query outside the results scroller so loading and scrolling cannot hide it.
        ScrollView resultsScroll = (ScrollView) view.getParent();
        LinearLayout searchRoot = (LinearLayout) resultsScroll.getParent();
        LinearLayout searchHeader = column();
        searchHeader.setPadding(contentGutter(), dp(19), contentGutter(), dp(16));
        view.removeView(search);
        searchHeader.addView(search, new LinearLayout.LayoutParams(-1, -2));
        searchRoot.addView(searchHeader, searchRoot.indexOfChild(resultsScroll), new LinearLayout.LayoutParams(-1, -2));
        view.setPadding(contentGutter(), 0, contentGutter(), dp(30));
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
                feedback.setText(query.isEmpty() ? "请输入片名。" : "");
                feedback.setVisibility(query.isEmpty() ? View.VISIBLE : View.GONE);
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
        feedback.setVisibility(View.GONE);
        LoadingUi.Pending pending = new LoadingUi.Pending(view, libraryUi, ui, LoadingUi.Kind.GRID, null,
                () -> page == expected && request == searchRequest);
        String term = query;
        JellyfinApi connection = api;
        worker.execute(() -> {
            try {
                List<JellyfinApi.Item> items = connection.search(term);
                onPage(expected, () -> {
                    if (request != searchRequest) return;
                    pending.finish();
                    showSearchResults(view, feedback, back, items, 0);
                    LoadingUi.ready(view);
                });
            } catch (Exception error) {
                onPage(expected, () -> {
                    if (request != searchRequest) return;
                    pending.finish();
                    feedback.setVisibility(View.GONE);
                    LoadingUi.failure(view, libraryUi, message(error, "搜索失败"), () -> search(view, feedback, back), false);
                });
            }
        });
    }

    private void showSearchResults(LinearLayout view, TextView feedback, Runnable back,
                                   List<JellyfinApi.Item> items, int scrollY) {
        feedback.setText(items.isEmpty() ? "没有找到匹配的影片。" : "");
        feedback.setVisibility(View.GONE);
        if (items.isEmpty()) LoadingUi.empty(view, libraryUi, "没有找到匹配的影片", "试试其他片名或关键词。");
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

    private void showSeasons(LinearLayout view, LinearLayout heading, JellyfinApi.Item series, Runnable back, String selectedId) {
        LinearLayout picker = column();
        view.addView(picker);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        picker.addView(header);
        TextView selectionTitle = libraryUi.label("选集", size(26,22), LibraryUi.TEXT, true);
        selectionTitle.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(selectionTitle, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout playbackActions = column();
        heading.addView(playbackActions, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout seasons = column();
        seasons.setTag(selectedId);
        picker.addView(seasons);
        int expected = page;
        JellyfinApi connection = api;
        String targetKey = "target:" + series.id;
        JellyfinApi.Item cachedTarget = browseMemory.get(targetKey);
        if (cachedTarget != null) {
            detailPlaybackActions(playbackActions, withLocalProgress(cachedTarget),
                    () -> detailsPage(series, back, (String) seasons.getTag()));
        } else
        worker.execute(() -> {
            try {
                List<JellyfinApi.Item> candidates = connection.episodes(series, null);
                onPage(expected, () -> {
                    JellyfinApi.Item chosen = null;
                    for (JellyfinApi.Item candidate : candidates) {
                        withLocalProgress(candidate);
                        if (candidate.resumeMs > 0) { chosen = candidate; break; }
                        if (chosen == null && !candidate.played) chosen = candidate;
                    }
                    if (chosen == null && !candidates.isEmpty()) chosen = candidates.get(0);
                    if (chosen == null) return;
                    browseMemory.put(targetKey, chosen, SystemClock.uptimeMillis());
                    detailPlaybackActions(playbackActions, withLocalProgress(chosen),
                            () -> detailsPage(series, back, (String) seasons.getTag()));
                });
            } catch (Exception error) {
                Log.w(JellyfinApi.TAG, "Unable to load series playback target");
            }
        });
        fillSeasons(seasons, series, back, selectedId);
    }

    private void fillSeasons(LinearLayout picker, JellyfinApi.Item series, Runnable back, String selectedId) {
        int expected = page;
        String key = "seasons:" + series.id;
        List<JellyfinApi.Item> cached = browseMemory.get(key);
        if (cached != null) { renderSeasons(picker, series, back, selectedId, cached, false); return; }
        LoadingUi.Pending pending = new LoadingUi.Pending(picker, libraryUi, ui, LoadingUi.Kind.EPISODES, null, () -> page == expected);
        JellyfinApi connection = api;
        browse.execute(() -> {
            try {
                List<JellyfinApi.Item> seasons = connection.seasons(series);
                ui.post(() -> {
                    if (api != connection) return;
                    browseMemory.put(key, seasons, SystemClock.uptimeMillis());
                    if (page != expected) return;
                    boolean handoff = pending.wasShown();
                    pending.finish();
                    renderSeasons(picker, series, back, selectedId, seasons, handoff);
                });
            } catch (Exception error) {
                onPage(expected, () -> {
                    pending.finish();
                    LoadingUi.failure(picker, libraryUi, message(error, "读取季列表失败"), () -> {
                        picker.removeAllViews();
                        fillSeasons(picker, series, back, selectedId);
                    }, false);
                });
            }
        });
    }

    private void renderSeasons(LinearLayout picker, JellyfinApi.Item series, Runnable back, String selectedId,
                               List<JellyfinApi.Item> seasons, boolean handoff) {
        LinearLayout list = column();
        if (handoff) list.setTag(R.id.loading_handoff, true);
        JellyfinApi.Item selected = null;
        for (JellyfinApi.Item season : seasons) if (selected == null || season.id.equals(selectedId)) selected = season;
        picker.setTag(selected == null ? null : selected.id);
        if (selected != null) browseMemory.put("selectedSeason:" + series.id, selected.id, SystemClock.uptimeMillis());
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
                    picker.setTag(season.id);
                    browseMemory.put("selectedSeason:" + series.id, season.id, SystemClock.uptimeMillis());
                    // A new season starts at its heading; restoring a previous page must not override it.
                    browseMemory.position("detail:" + series.id, rememberedScroll == null ? 0 : rememberedScroll.getScrollY());
                    fillEpisodes(list, series, season, back);
                });
                if (season == selected) scroll.post(() -> scroll.smoothScrollTo(tab.getLeft(), 0));
            }
        }
        picker.addView(list);
        fillEpisodes(list, series, selected, back);
    }

    private TextView addLibraryTab(LinearLayout row, String title, boolean selected) {
        TextView tab = libraryUi.label(title, 20, selected ? LibraryUi.ACCENT_LIGHT : LibraryUi.MUTED, selected);
        tab.setSingleLine();
        tab.setSelected(selected);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(dp(2), 0, dp(2), 0);
        tab.setMinWidth(dp(44));
        tab.setMinHeight(dp(size(58,44)));
        if (selected) {
            GradientDrawable line = libraryUi.rounded(LibraryUi.ACCENT_LIGHT, 2);
            LayerDrawable marker = new LayerDrawable(new Drawable[] { line });
            marker.setLayerSize(0, dp(28), dp(3));
            marker.setLayerGravity(0, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            marker.setLayerInsetBottom(0, dp(5));
            tab.setBackground(marker);
        }
        int gap = Math.max(14, Math.min(23, Math.round(getResources().getConfiguration().screenWidthDp * 0.04f)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMarginEnd(dp(gap));
        row.addView(tab, params);
        return tab;
    }

    private TextView addChip(LinearLayout row, String title) {
        TextView tab = libraryUi.label(title, size(20,14), LibraryUi.TEXT, true);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(dp(15), 0, dp(15), 0);
        tab.setSingleLine();
        tab.setMinHeight(dp(size(58,44)));
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
        int expected = page, request = ++seasonRequest;
        String key = "episodes:" + series.id + ":" + (season == null ? "" : season.id);
        List<JellyfinApi.Item> cached = browseMemory.get(key);
        if (cached != null) { renderEpisodes(list, series, season, back, cached); return; }
        LoadingUi.Pending pending = new LoadingUi.Pending(list, libraryUi, ui, LoadingUi.Kind.EPISODES, null,
                () -> page == expected && request == seasonRequest);
        JellyfinApi connection = api;
        browse.execute(() -> {
            try {
                List<JellyfinApi.Item> episodes = connection.episodes(series, season);
                ui.post(() -> {
                    if (api != connection) return;
                    browseMemory.put(key, episodes, SystemClock.uptimeMillis());
                    if (page != expected) return;
                    if (request != seasonRequest) return;
                    pending.finish();
                    renderEpisodes(list, series, season, back, episodes);
                });
            } catch (Exception error) {
                onPage(expected, () -> {
                    if (request != seasonRequest) return;
                    pending.finish();
                    LoadingUi.failure(list, libraryUi, message(error, "读取剧集失败"), () -> fillEpisodes(list, series, season, back), false);
                });
            }
        });
    }

    private void renderEpisodes(LinearLayout list, JellyfinApi.Item series, JellyfinApi.Item season,
                                Runnable back, List<JellyfinApi.Item> episodes) {
        if (episodes.isEmpty()) LoadingUi.empty(list, libraryUi, "本季暂无可播放单集", "暂无可用的播放内容。");
        for (JellyfinApi.Item episode : episodes) {
            withLocalProgress(episode);
            episodeRow(list, episode, () -> openItem(episode,
                    () -> detailsPage(series, back, season == null ? null : season.id)));
        }
        restoreBrowsePosition();
    }

    private void episodeRow(LinearLayout list, JellyfinApi.Item episode, Runnable open) {
        View divider = new View(this);
        divider.setBackgroundColor(LibraryUi.LINE);
        list.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout row = new LinearLayout(this);
        row.setTag(R.id.browse_item_id,episode.id);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));
        row.setMinimumHeight(dp(size(110,80)));
        list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        row.addView(libraryUi.picture(episode, true), new LinearLayout.LayoutParams(dp(96), dp(54)));
        LinearLayout body = column();
        body.setPadding(dp(10), 0, 0, 0);
        row.addView(body, new LinearLayout.LayoutParams(0, -2, 1));
        String episodeTitle = LibraryUi.episodeTitle(episode);
        String name = episode.index > 0
                ? "第 " + episode.index + " 集" + (episodeTitle.isEmpty() ? "" : " · " + episodeTitle)
                : episode.title;
        TextView episodeLabel = libraryUi.label(name, size(21,14), LibraryUi.TEXT, true);
        episodeLabel.setMaxLines(2);
        episodeLabel.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(episodeLabel);
        String info = episode.durationMs > 0 ? episode.durationMs / 60_000 + " 分钟" : "";
        if (episode.resumeMs > 0) info += (info.isEmpty() ? "" : " · ") + "已看到 " + LibraryUi.time(episode.resumeMs);
        if (!info.isEmpty()) body.addView(libraryUi.label(info, size(17,12), LibraryUi.MUTED, false));
        if (episode.played) watched(row);
        libraryUi.actionable(row, open);
    }

    private void watched(LinearLayout parent) {
        TextView badge = libraryUi.label("✓ 已看完", size(16,11), LibraryUi.ACCENT_LIGHT, true);
        badge.setPadding(dp(8), dp(5), dp(8), dp(5));
        badge.setBackground(libraryUi.rounded(LibraryUi.SELECTED, LibraryUi.RADIUS_SMALL));
        parent.addView(badge, new LinearLayout.LayoutParams(-2, -2));
    }

    private void playbackPage(JellyfinApi.Item item, Long startPosition, Runnable back) {
        playbackPage(item, startPosition, back, null);
    }

    private void playbackPage(JellyfinApi.Item item, Long startPosition, Runnable back, CollectionPlayback collectionQueue) {
        if (subscriptionController != null && subscriptionController.blocked()) { showBlockedSubscription(); return; }
        rememberBrowsePosition();
        Runnable close = () -> {
            stopPlayback();
            back.run();
        };
        ++page;
        backAction = close;
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        PlaybackPolicy policy = PlaybackPolicy.fromSettings(settings.resolution, settings.bitrate,
                metrics.widthPixels, metrics.heightPixels);
        PlaybackPage current = new PlaybackPage(item, policy, close);
        current.collectionQueue = collectionQueue;
        current.screen.setCollectionNavigation(collectionQueue != null);
        playback = current;
        setContentView(current.screen);
        enterFullscreenPlayback();
        current.screen.requestApplyInsets();
        current.loadNeighbors();
        current.requestInitial(startPosition == null ? item.resumeMs : startPosition, startPosition == null);
    }

    private final class PlaybackPage {
        final JellyfinApi connection = api;
        PlaybackPolicy policy;
        String qualityText;
        final FullscreenPlaybackView screen;
        final Runnable close;
        JellyfinApi.Item item, previous, next;
        CollectionPlayback collectionQueue;
        PlaybackChange change;
        int subtitle = PlaybackTracks.AUTO;
        PlaybackTracks.LocalTrack localSubtitle;
        PlaybackTrackPreference preferredAudio, preferredSubtitle;
        boolean foreground = true;
        boolean loggedSubtitle;
        boolean autoAdvancePending;
        boolean neighborsResolved, neighborsLoading, endReturnQueued;
        boolean preparationFailed;
        final SubtitleRecovery subtitleRecovery = new SubtitleRecovery();
        final Set<Integer> failedSubtitles = new HashSet<>();
        JellyfinApi.Stream recoveryOriginal;
        int preparationRequest;
        long retryPosition;
        String retrySource;
        Integer retryAudio;
        TrackSelectionParameters retryParameters;

        PlaybackPage(JellyfinApi.Item item, PlaybackPolicy policy, Runnable close) {
            this.item = item;
            this.policy = policy;
            this.close = close;
            String resolution = PlaybackPolicy.RESOLUTION_LABELS[PlaybackPolicy.optionIndex(
                    PlaybackPolicy.WIDTHS, settings.resolution, PlaybackPolicy.WIDTH)];
            String bitrate = PlaybackPolicy.RATE_LABELS[PlaybackPolicy.optionIndex(
                    PlaybackPolicy.RATES, settings.bitrate, PlaybackPolicy.TOTAL_BPS)];
            qualityText = resolution + " · " + bitrate;
            screen = new FullscreenPlaybackView(MainActivity.this, libraryUi, qualityText, close,
                    () -> changeEpisode(previous), () -> changeEpisode(next), this::chooseSubtitle, this::chooseAudio);
            updateTitle();
            updateActions();
        }

        void requestInitial(long position, boolean refreshPosition) {
            if (subscriptionController != null && subscriptionController.blocked()) {
                preparationFailed = true;
                screen.showPreparationFailure("订阅已失效，请返回设置更新订阅。");
                updateActions(); return;
            }
            final int request = ++preparationRequest;
            final int expected = page;
            preparationFailed = false;
            autoAdvancePending = false;
            retryPosition = position;
            releasePlayer(true);
            screen.beginPreparation(libraryUi.playbackBackdrop(item), position, item.durationMs,
                    () -> requestInitial(retryPosition, false));
            updateActions();
            worker.execute(() -> {
                long resume = position;
                try {
                    if (refreshPosition) {
                        try { resume = connection.details(item).resumeMs; }
                        catch (Exception ignored) {
                            Log.w(JellyfinApi.TAG, "Using saved card position because progress refresh failed");
                        }
                    }
                    long resolvedPosition = resume;
                    onPage(expected, () -> {
                        if (playback != this || preparationRequest != request) return;
                        retryPosition = resolvedPosition;
                        screen.setPreparationPosition(resolvedPosition);
                    });
                    JellyfinApi.Stream resolved = connection.stream(item, policy, retrySource, retryAudio, subtitle);
                    onPage(expected, () -> {
                        if (playback != this || preparationRequest != request) return;
                        if (subscriptionController != null && subscriptionController.blocked()) {
                            preparationFailed = true;
                            screen.showPreparationFailure("订阅已失效，请返回设置更新订阅。");
                            updateActions(); return;
                        }
                        startPlayback(resolved, this, resolvedPosition, true, false, retryParameters);
                    });
                } catch (Exception error) {
                    onPage(expected, () -> {
                        if (playback != this || preparationRequest != request) return;
                        preparationFailed = true;
                        Log.w(JellyfinApi.TAG, "Playback preparation request failed type=" + error.getClass().getSimpleName());
                        screen.showPreparationFailure("暂时无法获取视频，请重试。");
                        updateActions();
                    });
                }
            });
        }

        void failPreparation(ExoPlayer active, JellyfinApi.Stream resolved) {
            preparationFailed = true;
            autoAdvancePending = false;
            retryPosition = active.getCurrentPosition();
            retrySource = resolved.sourceId;
            retryAudio = resolved.audioIndex >= 0 ? resolved.audioIndex : null;
            retryParameters = active.getTrackSelectionParameters();
            screen.setPreparationPosition(retryPosition);
            screen.showPreparationFailure("暂时无法播放，请重试。");
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
            if (collectionQueue != null) {
                neighborsLoading = false;
                neighborsResolved = collectionQueue.contains(item);
                previous = collectionQueue.previous(item);
                next = collectionQueue.next(item);
                updateActions();
                advanceAfterEnd();
                return;
            }
            neighborsResolved = "Movie".equals(item.type);
            neighborsLoading = episodic();
            updateActions();
            if (!episodic()) return;
            JellyfinApi.Item episode = item;
            browse.execute(() -> {
                try {
                    List<JellyfinApi.Item> items = connection.adjacentEpisodes(episode);
                    ui.post(() -> {
                        if (playback != this || item != episode) return;
                        neighborsLoading = false;
                        for (int i = 0; i < items.size(); i++) {
                            if (!episode.id.equals(items.get(i).id)) continue;
                            neighborsResolved = true;
                            if (i > 0) previous = items.get(i - 1);
                            if (i + 1 < items.size()) next = items.get(i + 1);
                            break;
                        }
                        updateActions();
                        advanceAfterEnd();
                    });
                } catch (Exception error) {
                    ui.post(() -> { if (playback == this && item == episode) neighborsLoading = false; });
                    Log.w(JellyfinApi.TAG, "Unable to load adjacent episodes");
                }
            });
        }

        boolean ready() {
            return playback == this && !preparationFailed && change == null && player != null && stream != null && playbackStarted;
        }

        void updateActions() {
            boolean enabled = ready();
            boolean audio = enabled && (stream.hls ? !stream.audio.isEmpty()
                    : !PlaybackTracks.supported(player.getCurrentTracks(), C.TRACK_TYPE_AUDIO).isEmpty());
            boolean canChangeEpisode = subscriptionController == null || !subscriptionController.blocked();
            screen.updateActions(episodic() || collectionQueue != null, enabled && canChangeEpisode && previous != null,
                    enabled && canChangeEpisode && next != null, enabled, audio);
        }

        void chooseSubtitle() {
            if (!ready()) return;
            JellyfinApi.Stream current = stream;
            List<String> labels = new ArrayList<>();
            // AUTO remains an internal policy; the menu shows the resolved track rather than the policy.
            int actual = subtitle == PlaybackTracks.OFF ? PlaybackTracks.OFF
                    : current.burnedSubtitleIndex >= 0 ? current.burnedSubtitleIndex
                    : subtitle == PlaybackTracks.AUTO ? current.defaultSubtitleIndex : subtitle;
            int selected = -1;
            for (JellyfinApi.MediaTrack option : current.subtitles) {
                if (localSubtitle == null && actual == option.index) selected = labels.size();
                labels.add(option.label + (failedSubtitles.contains(option.index) ? " · 点击重试" : option.available() ? "" : " · 暂不可用"));
            }
            List<PlaybackTracks.LocalTrack> embedded = new ArrayList<>();
            if (current.subtitles.isEmpty()) {
                embedded = PlaybackTracks.supported(player.getCurrentTracks(), C.TRACK_TYPE_TEXT);
                for (PlaybackTracks.LocalTrack option : embedded) {
                    if (subtitle != PlaybackTracks.OFF && (localSubtitle != null
                            ? localSubtitle.override().equals(option.override()) : option.selected())) selected = labels.size();
                    labels.add(option.label);
                }
            }
            int offChoice = labels.size();
            if (selected < 0 && localSubtitle == null && actual < 0) selected = offChoice;
            labels.add("关闭字幕");
            List<PlaybackTracks.LocalTrack> localOptions = embedded;
            screen.showChoices("字幕", labels.toArray(new String[0]), selected, which -> {
                if (!ready() || stream != current) return;
                int requested;
                PlaybackTracks.LocalTrack local = null;
                if (which == offChoice) requested = PlaybackTracks.OFF;
                else if (which < current.subtitles.size()) requested = current.subtitles.get(which).index;
                else {
                    requested = PlaybackTracks.AUTO;
                    local = localOptions.get(which - current.subtitles.size());
                }
                selectSubtitle(requested, local);
            });
        }

        void selectSubtitle(int requested, PlaybackTracks.LocalTrack local) {
            if (!ready()) return;
            if (local == null && requested >= 0) {
                JellyfinApi.MediaTrack option = PlaybackTracks.subtitleTrack(stream.subtitles, requested);
                if (option == null || !option.available()) {
                    Toast.makeText(MainActivity.this, "该字幕暂无法加载，请选择其他字幕。", Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            int desired = requested == PlaybackTracks.AUTO ? stream.defaultSubtitleIndex : requested;
            boolean retryFailed = desired >= 0 && failedSubtitles.remove(desired);
            if (retryFailed) {
                invalidateSubtitle(stream, desired);
                subtitleRecovery.reset(desired);
                recoveryOriginal = null;
                switchTo(item, null, requested, local);
                rememberSubtitleChoice(requested, local);
                return;
            }
            int burned = local == null ? PlaybackTracks.burnInIndex(stream.subtitles, stream.defaultSubtitleIndex, requested) : -1;
            if (burned != stream.burnedSubtitleIndex) {
                switchTo(item, null, requested, local);
                rememberSubtitleChoice(requested, local);
                return;
            }
            if (PlaybackTracks.externalSubtitleIndex(stream, requested) != PlaybackTracks.externalSubtitleIndex(stream, subtitle)) {
                switchTo(item, null, requested, local, -1, null, true);
                rememberSubtitleChoice(requested, local);
                return;
            }
            subtitle = requested;
            localSubtitle = local;
            loggedSubtitle = false;
            PlaybackTracks.setSubtitle(player, player.getCurrentTracks(), stream, subtitle, localSubtitle);
            rememberSubtitleChoice(requested, local);
            updateReportedSubtitle(stream);
            report("Progress");
            Log.i(JellyfinApi.TAG, "Subtitle selection index=" + subtitle + " local=" + (localSubtitle != null));
        }

        void rememberSubtitleChoice(int requested, PlaybackTracks.LocalTrack local) {
            PlaybackTrackPreference choice = local == null && requested >= 0
                    ? PlaybackTrackPreference.of(PlaybackTracks.subtitleTrack(stream.subtitles, requested)) : null;
            if (change != null) {
                change.updateSubtitlePreference = true; change.subtitlePreference = choice;
                change.manualSubtitle = local != null || requested != PlaybackTracks.AUTO;
            } else {
                preferredSubtitle = choice;
                connection.rememberTrack(stream, true, local != null || requested != PlaybackTracks.AUTO);
            }
        }

        void updateReportedSubtitle(JellyfinApi.Stream value) {
            value.reportSubtitleIndex = localSubtitle != null ? null : subtitle == PlaybackTracks.OFF ? -1
                    : value.burnedSubtitleIndex >= 0 ? value.burnedSubtitleIndex
                    : subtitle == PlaybackTracks.AUTO ? value.defaultSubtitleIndex : subtitle;
        }

        void subtitleFailed(ExoPlayer active, JellyfinApi.Stream failed, int index, IOException error) {
            if (playback != this || player != active || stream != failed || preparationFailed || index < 0) return;
            invalidateSubtitle(failed, index);
            PlaybackChange pending = change;
            if (pending != null) {
                if (pending.recoveryAction == null) { recoverSwitch(); return; }
                if (!pending.replaced) return; // Duplicate terminal callback from the old player while negotiating.
                if (pending.recoveryAction == SubtitleRecovery.Action.DISABLE) {
                    change = null;
                    failPreparation(active, failed);
                    return;
                }
                if (pending.previous != failed) enqueueReport(connection, pending.previous, "Stopped", pending.position, !pending.playWhenReady);
                change = null;
            } else {
                int selected = subtitle == PlaybackTracks.AUTO ? failed.defaultSubtitleIndex : subtitle;
                if (subtitle == PlaybackTracks.OFF || index != selected) return;
                recoveryOriginal = failed;
            }
            boolean play = pending == null ? active.getPlayWhenReady() : pending.playWhenReady;
            long position = pending == null ? active.getCurrentPosition() : pending.position;
            SubtitleRecovery.Action action = subtitleRecovery.next(index, error);
            failedSubtitles.add(index);
            int choice = action == SubtitleRecovery.Action.DISABLE ? PlaybackTracks.OFF : subtitle;
            switchTo(item, null, choice, null, action == SubtitleRecovery.Action.BURN ? index : -1, action, false);
            change.position = Math.max(0, position);
            change.playWhenReady = play && foreground;
            change.recoveryIndex = index;
            if (action == SubtitleRecovery.Action.DISABLE)
                Toast.makeText(MainActivity.this, "字幕加载失败，已关闭；可在字幕菜单重新选择重试。", Toast.LENGTH_LONG).show();
            Log.i(JellyfinApi.TAG, "Subtitle recovery index=" + index + " action=" + action);
        }

        void invalidateSubtitle(JellyfinApi.Stream failed, int index) {
            JellyfinApi.MediaTrack track = PlaybackTracks.subtitleTrack(failed.subtitles, index);
            if (track != null && track.subtitleUrl != null) connection.subtitleCache.remove(track.subtitleUrl.toString());
        }

        void failedSubtitleSwitch(PlaybackChange pending, Exception error) {
            if (change != pending) return;
            if (pending.recoveryAction == SubtitleRecovery.Action.DISABLE) {
                change = null;
                failPreparation(player, stream);
                return;
            }
            // No new player was installed if negotiation failed. Allow the bounded next recovery stage.
            pending.replaced = true;
            subtitleFailed(player, stream, pending.recoveryIndex, error instanceof IOException ? (IOException) error : new IOException(error));
        }

        void chooseAudio() {
            if (!ready()) return;
            JellyfinApi.Stream current = stream;
            // Stable server audio indices survive switching between direct playback and subtitle burn-in.
            if (!current.audio.isEmpty()) {
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
                    PlaybackTrackPreference choice = PlaybackTrackPreference.of(current.audio.get(which));
                    if (index != current.audioIndex) {
                        switchTo(item, index);
                        if (change != null) change.audioPreference = choice;
                    } else {
                        preferredAudio = choice;
                        connection.rememberTrack(current, false, true);
                    }
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
                if (ready() && stream == current) {
                    PlaybackTracks.select(player, options.get(which));
                    current.reportAudioIndex = null; preferredAudio = null;
                    connection.rememberTrack(current, false, true);
                    report("Progress");
                }
            });
        }

        void changeEpisode(JellyfinApi.Item target) {
            if (target != null && ready()) switchTo(target, null);
        }

        void advanceAfterEnd() {
            if (!autoAdvancePending || !foreground || !ready()
                    || player.getPlaybackState() != Player.STATE_ENDED || !player.getPlayWhenReady()) return;
            if (next == null) {
                if (!neighborsResolved || endReturnQueued || !(episodic() || "Movie".equals(item.type))) return;
                ExoPlayer endedPlayer = player;
                endReturnQueued = true;
                // Return outside the player event callback; an intervening seek/switch must cancel it.
                ui.post(() -> {
                    endReturnQueued = false;
                    if (playback != this || player != endedPlayer || !autoAdvancePending || !foreground || !ready()
                            || next != null || !neighborsResolved || screen.soughtToEnd()
                            || endedPlayer.getPlaybackState() != Player.STATE_ENDED || !endedPlayer.getPlayWhenReady()) return;
                    autoAdvancePending = false;
                    Log.i(JellyfinApi.TAG, "Playback naturally completed returning to browse");
                    close.run();
                });
                return;
            }
            Log.i(JellyfinApi.TAG, "Autoplay next collection=" + (collectionQueue != null)
                    + " season=" + next.season + " episode=" + next.index);
            changeEpisode(next);
        }

        void onPlaybackEnded(ExoPlayer source, long initialPosition, boolean userSought) {
            if (playback != this || player != source || source.getPlaybackState() != Player.STATE_ENDED) return;
            report("Progress");
            long duration = source.getDuration();
            boolean startedAtEnd = !userSought && duration > 0 && initialPosition >= duration;
            autoAdvancePending = foreground && !preparationFailed && change == null && source.getPlayWhenReady()
                    && !screen.soughtToEnd() && !startedAtEnd;
            Log.i(JellyfinApi.TAG, "Playback ended autoAdvanceEligible=" + autoAdvancePending);
            advanceAfterEnd();
        }

        void switchTo(JellyfinApi.Item target, Integer audioIndex) {
            switchTo(target, audioIndex, subtitle, localSubtitle);
        }

        void switchTo(JellyfinApi.Item target, Integer audioIndex, int requestedSubtitle, PlaybackTracks.LocalTrack requestedLocal) {
            switchTo(target, audioIndex, requestedSubtitle, requestedLocal, -1, null, false);
        }

        PlaybackPolicy policyFor(JellyfinApi.Item target) {
            if (target == item || settings == null) return policy;
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            return PlaybackPolicy.fromSettings(settings.resolution, settings.bitrate, metrics.widthPixels, metrics.heightPixels);
        }

        void switchTo(JellyfinApi.Item target, Integer audioIndex, int requestedSubtitle, PlaybackTracks.LocalTrack requestedLocal,
                      int forceSubtitleIndex, SubtitleRecovery.Action recoveryAction, boolean reuseStream) {
            boolean sameEpisode = target == item;
            boolean sameSeries = "Episode".equals(item.type) && "Episode".equals(target.type)
                    && !item.seriesId.isEmpty() && item.seriesId.equals(target.seriesId);
            PlaybackTrackPreference nextAudio = sameSeries ? preferredAudio : null;
            PlaybackTrackPreference nextSubtitle = sameSeries ? preferredSubtitle : null;
            if (!sameEpisode && subscriptionController != null && subscriptionController.blocked()) {
                autoAdvancePending = false;
                Toast.makeText(MainActivity.this, "订阅已失效，请在当前集结束后更新订阅", Toast.LENGTH_SHORT).show();
                updateActions(); return;
            }
            PlaybackPolicy targetPolicy = policyFor(target);
            String targetQuality = sameEpisode ? qualityText : PlaybackPolicy.RESOLUTION_LABELS[PlaybackPolicy.optionIndex(
                    PlaybackPolicy.WIDTHS, settings.resolution, PlaybackPolicy.WIDTH)] + " · "
                    + PlaybackPolicy.RATE_LABELS[PlaybackPolicy.optionIndex(PlaybackPolicy.RATES, settings.bitrate, PlaybackPolicy.TOTAL_BPS)];
            PlaybackChange pending = new PlaybackChange(stream, target, player.getCurrentPosition(),
                    player.getPlayWhenReady(), player.getPlaybackState() == Player.STATE_ENDED,
                    subtitle, localSubtitle, player.getTrackSelectionParameters());
            pending.previousPolicy = policy;
            pending.previousQuality = qualityText;
            autoAdvancePending = false;
            change = pending;
            pending.recoveryAction = recoveryAction;
            pending.wasReported = playbackStarted;
            player.pause();
            updateActions();
            screen.showMessage(recoveryAction == SubtitleRecovery.Action.REFRESH ? "正在重新加载字幕…"
                    : recoveryAction == SubtitleRecovery.Action.BURN ? "正在切换服务端字幕…"
                    : recoveryAction == SubtitleRecovery.Action.DISABLE ? "正在关闭不可用字幕…" : !sameEpisode ? "正在切换剧集…"
                    : requestedSubtitle != subtitle || requestedLocal != localSubtitle ? "正在切换字幕…" : "正在切换音轨…");
            int targetSubtitle = sameEpisode ? requestedSubtitle
                    : pending.subtitle == PlaybackTracks.OFF ? PlaybackTracks.OFF : PlaybackTracks.AUTO;
            Integer targetAudio = sameEpisode ? audioIndex != null ? audioIndex
                    : pending.previous.audioIndex >= 0 ? pending.previous.audioIndex : null : null;
            browse.execute(() -> {
                try {
                    JellyfinApi.Stream resolved = recoveryAction == SubtitleRecovery.Action.DISABLE ? recoveryOriginal
                            : reuseStream ? pending.previous : sameEpisode
                            ? connection.stream(target, targetPolicy, pending.previous.sourceId, targetAudio, targetSubtitle, forceSubtitleIndex)
                            : connection.streamNextEpisode(target, targetPolicy, nextAudio, nextSubtitle, targetSubtitle);
                    ui.post(() -> {
                        if (playback != this || change != pending) return;
                        if (!sameEpisode && subscriptionController != null && subscriptionController.blocked()) {
                            recoverSwitch(); autoAdvancePending = false; updateActions(); return;
                        }
                        releasePlayer(false);
                        pending.replaced = true;
                        policy = targetPolicy; qualityText = targetQuality; screen.setQualityLimit(qualityText);
                        subtitle = sameEpisode ? targetSubtitle : resolved.subtitleSelection;
                        localSubtitle = sameEpisode ? requestedLocal : null;
                        long position = sameEpisode ? pending.position : target.resumeMs;
                        startPlayback(resolved, this, position, pending.playWhenReady,
                                resolved == pending.previous && pending.wasReported, null);
                    });
                } catch (Exception error) {
                    ui.post(() -> {
                        if (playback == this && change == pending) {
                            if (pending.recoveryAction != null) failedSubtitleSwitch(pending, error);
                            else recoverSwitch();
                        }
                    });
                }
            });
        }

        void recoverSwitch() {
            PlaybackChange pending = change;
            change = null;
            if (pending.previousPolicy != null) {
                policy = pending.previousPolicy; qualityText = pending.previousQuality; screen.setQualityLimit(qualityText);
            }
            subtitle = pending.subtitle;
            localSubtitle = pending.localSubtitle;
            boolean resume = pending.playWhenReady && !pending.endedBeforeSwitch;
            if (!pending.replaced) {
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
            if (pending.audioPreference != null) preferredAudio = pending.audioPreference;
            if (pending.updateSubtitlePreference) preferredSubtitle = pending.subtitlePreference;
            if (pending.audioPreference != null) connection.rememberTrack(stream, false, true);
            if (pending.updateSubtitlePreference) connection.rememberTrack(stream, true, pending.manualSubtitle);
            if (stream != pending.previous) enqueueReport(connection, pending.previous, "Stopped", pending.position, !pending.playWhenReady);
            if (pending.target != item) {
                if (item.seriesId.isEmpty() || !item.seriesId.equals(pending.target.seriesId)) {
                    preferredAudio = null; preferredSubtitle = null;
                }
                subtitleRecovery.clear();
                failedSubtitles.clear();
                recoveryOriginal = null;
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
        long position;
        final boolean endedBeforeSwitch;
        final int subtitle;
        final PlaybackTracks.LocalTrack localSubtitle;
        final TrackSelectionParameters parameters;
        boolean playWhenReady;
        boolean replaced, wasReported;
        SubtitleRecovery.Action recoveryAction;
        int recoveryIndex = -1;
        PlaybackPolicy previousPolicy;
        String previousQuality;
        PlaybackTrackPreference audioPreference, subtitlePreference;
        boolean updateSubtitlePreference;
        boolean manualSubtitle;

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
        current.updateReportedSubtitle(resolved);
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
        int externalIndex = PlaybackTracks.externalSubtitleIndex(resolved, current.subtitle);
        JellyfinApi.MediaTrack external = PlaybackTracks.subtitleTrack(resolved.subtitles, externalIndex);
        android.net.Uri subtitleUri = external == null ? null : android.net.Uri.parse(external.subtitleUrl.toString());
        AtomicReference<ExoPlayer> playerReference = new AtomicReference<>();
        AtomicReference<IOException> subtitleLoadError = new AtomicReference<>();
        SubtitleLoadPolicy loadPolicy = new SubtitleLoadPolicy(subtitleUri, error -> {
            subtitleLoadError.set(error);
            ui.post(() -> current.subtitleFailed(playerReference.get(), resolved, externalIndex, error));
        });
        ExoPlayer active = new ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(() -> new SubtitleDataSource(data.createDataSource(), subtitleUri, current.connection.subtitleCache))
                        .setSubtitleParserFactory(new SrtSubtitleParserFactory()).setLoadErrorHandlingPolicy(loadPolicy)).build();
        playerReference.set(active);
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
                if (player != active || playback != current || current.preparationFailed || firstFrame) return;
                firstFrame = true;
                playbackStarted = true;
                current.finishSwitch(active.getCurrentPosition());
                screen.showVideo();
                current.updateActions();
                Log.i(JellyfinApi.TAG, "First frame rendered");
                report(alreadyReported ? "Progress" : "");
                // Jellyfin persists track choices on Progress, not Stopped.
                // Do not lose a successful choice when the user leaves before the 10s tick.
                if (!alreadyReported) report("Progress");
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
                current.onPlaybackEnded(active, resumeMs, userSought);
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
                if (subtitleLoadError.get() != null) {
                    current.subtitleFailed(active, resolved, externalIndex, subtitleLoadError.get());
                    return;
                }
                if (error instanceof ExoPlaybackException && resolved.burnedSubtitleIndex < 0) {
                    Format failedFormat = ((ExoPlaybackException) error).rendererFormat;
                    if (failedFormat != null && MimeTypes.isText(failedFormat.sampleMimeType)) {
                        int index = current.subtitle == PlaybackTracks.AUTO ? resolved.defaultSubtitleIndex : current.subtitle;
                        current.subtitleFailed(active, resolved, index, new IOException(error));
                        return;
                    }
                }
                if (current.change != null && current.change.recoveryAction != null) {
                    if (current.change.recoveryAction == SubtitleRecovery.Action.BURN) {
                        current.subtitleFailed(active, resolved, current.change.recoveryIndex, new IOException(error));
                    } else {
                        PlaybackChange pending = current.change;
                        current.change = null;
                        if (pending.previous != resolved) enqueueReport(current.connection, pending.previous, "Stopped", pending.position, !pending.playWhenReady);
                        current.failPreparation(active, resolved);
                    }
                    return;
                }
                if (current.change != null) {
                    ui.post(() -> {
                        if (player == active && current.change != null) current.recoverSwitch();
                    });
                } else {
                    current.failPreparation(active, resolved);
                }
            }
        });
        active.setMediaItem(PlaybackTracks.mediaItem(resolved, current.subtitle), resumeMs);
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
        LocalProgress stopped = "Stopped".equals(event) ? localProgress.get(active.itemId) : null;
        Integer audioIndex = active.reportAudioIndex, subtitleIndex = active.reportSubtitleIndex;
        worker.execute(() -> {
            try {
                connection.report(active, event, position, paused, audioIndex, subtitleIndex);
                if (stopped != null) ui.post(() -> {
                    if (api == connection && localProgress.get(active.itemId) == stopped
                            && Math.abs(stopped.position - position) < 1500) stopped.reported = true;
                });
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
        if (!closing) ui.post(() -> {
            if (closing || subscriptionController == null || playback != null) return;
            if (subscriptionController.blocked()) showBlockedSubscription();
            else if (pendingSubscription != null) applySubscription(pendingSubscription);
        });
    }

    private void releasePlayer(boolean reportStop) {
        ui.removeCallbacks(progress);
        if (player != null) {
            rememberPlaybackProgress();
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
        ui.removeCallbacks(subscriptionTick);
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
        ui.removeCallbacks(subscriptionTick);
        ui.postDelayed(subscriptionTick, 3000);
        if (playback != null) playback.foreground = true;
    }

    @Override protected void onDestroy() {
        closing = true;
        ui.removeCallbacks(subscriptionTick);
        if (subscriptionController != null) subscriptionController.close();
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
                || error instanceof java.net.SocketTimeoutException) return "连接失败或超时，请检查网络、服务地址和连接配置。";
        Log.w(JellyfinApi.TAG, fallback + " " + error.getClass().getSimpleName());
        return fallback + "。请检查连接设置。";
    }

    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    private int contentGutter() { return dp(getResources().getConfiguration().screenWidthDp <= 355 ? 16 : 20); }
    private int screenWidth() {
        // Cached pages render immediately, before the asynchronous landscape -> portrait change finishes.
        if (getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
            browsingWidthPixels = getResources().getDisplayMetrics().widthPixels;
            browsingHeightDp = getResources().getConfiguration().screenHeightDp;
        }
        return Math.max(1, browsingWidthPixels - 2 * contentGutter());
    }
    private void section(LinearLayout parent, String title) {
        TextView heading = libraryUi.label(title, size(26,22), LibraryUi.TEXT, true);
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
        TextView field = libraryUi.label(text, size(18,14), LibraryUi.MUTED, false);
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
        button.setTextSize(size(20,14));
        button.setMinHeight(dp(size(56,44)));
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
        field.setTextSize(size(22,14));
        field.setMinHeight(dp(size(58,44)));
        field.setHint(hint);
        field.setContentDescription(hint);
        field.setSingleLine(true);
        field.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT);
        field.setText(value);
        parent.addView(field, new LinearLayout.LayoutParams(-1, -2));
        return field;
    }
}
