package cn.jiuwusan.jellyfin.player;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.ProgressBar;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.text.Cue;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.DefaultTimeBar;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;
import androidx.media3.ui.TimeBar;
import java.util.ArrayList;
import java.util.List;

@UnstableApi
final class FullscreenPlaybackView extends FrameLayout {
    private static final int CONTROL_BACKGROUND = 0xcc11191d;
    private static final int CONTROL_BORDER = 0x42ffffff;
    private static final int LIGHT_TEXT = 0xffc9d8de;
    private static final int SELECTED_BACKGROUND = 0xff13333f;
    private static final int CHOICE_HEIGHT = 64;
    private static final long CONTROLS_TIMEOUT_MS = 5_000;

    private final LibraryUi style;
    private final PlayerView video;
    private final View touchSurface;
    private final LinearLayout header, bottom, trackPanel, trackList;
    private final ScrollView trackScroll;
    private final FrameLayout transportRow;
    private final LinearLayout counters;
    private final TextView title, subtitle, message, time, quality, play, rewind, forward;
    private final TextView previous, next, subtitles, audio, trackTitle;
    private final DefaultTimeBar timeline;
    private final FrameLayout preparationCover;
    private final LinearLayout preparationCenter, preparationError;
    private final ProgressBar preparationSpinner;
    private final TextView preparationSlow, preparationRetry, preparationReason;
    private boolean preparing;
    private boolean preparationFailed;
    private boolean desiredPrevious, desiredNext, desiredText, desiredAudio;
    private long preparationPosition, preparationDuration;
    private int preparationGeneration;
    private Runnable retryPreparation;
    private final Runnable revealSpinner = this::revealPreparationSpinner;
    private final Runnable revealSlow = this::revealPreparationSlow;
    private void revealPreparationSpinner() {
        if (preparing && !preparationFailed) preparationSpinner.setVisibility(VISIBLE);
    }
    private void revealPreparationSlow() {
        if (preparing && !preparationFailed) preparationSlow.setVisibility(VISIBLE);
    }
    private final int[] captionLocation = new int[2];
    private final int[] bottomLocation = new int[2];
    private Player player;
    private View activeTrackButton;
    private boolean controlsVisible = true;
    private boolean transportEnabled;
    private boolean scrubbing;
    private boolean touching;
    private boolean soughtToEnd;
    private int leftInset, topInset, rightInset, bottomInset;
    private long shownDuration = C.TIME_UNSET, shownPosition, shownBuffer;

    private final Player.Listener playerEvents = new Player.Listener() {
        @Override public void onEvents(Player source, Player.Events events) {
            restartRefresh();
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) restartControlsTimeout();
            if (events.contains(Player.EVENT_CUES)) updateCaptionCues();
        }
    };
    private final Runnable hideControls = () -> {
        if (!controlsMustStayVisible() && !touching) setControlsVisible(false);
    };
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            refreshControls();
            if (canRefresh()) postDelayed(this, 250);
        }
    };

    FullscreenPlaybackView(Context context, LibraryUi style, String qualityLimit, Runnable close,
                           Runnable previousEpisode, Runnable nextEpisode, Runnable subtitleMenu, Runnable audioMenu) {
        super(context);
        this.style = style;
        setBackgroundColor(0xff020304);

        video = new PlayerView(context);
        video.setUseController(false);
        video.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        video.setKeepScreenOn(true);
        SubtitleStyle.apply(video.getSubtitleView());
        addView(video, new LayoutParams(-1, -1));

        preparationCover = new FrameLayout(context);
        preparationCover.setId(R.id.playback_preparation_cover);
        preparationCover.setVisibility(GONE);
        addView(preparationCover, new LayoutParams(-1, -1));

        touchSurface = new View(context);
        touchSurface.setId(R.id.playback_controls_toggle);
        touchSurface.setFocusable(true);
        touchSurface.setOnClickListener(view -> {
            if (!dismissChoices() && !controlsMustStayVisible()) {
                setControlsVisible(!controlsVisible);
                restartControlsTimeout();
            }
        });
        addView(touchSurface, new LayoutParams(-1, -1));

        header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackground(shade(GradientDrawable.Orientation.TOP_BOTTOM, 0xe8020507, Color.TRANSPARENT));
        addView(header, new LayoutParams(-1, -2, Gravity.TOP));

        TextView back = control("‹", "返回", close);
        back.setTextSize(29);
        header.addView(back, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout titleBlock = style.column();
        title = singleLine("", 26, LibraryUi.TEXT, true);
        subtitle = singleLine("", 18, LIGHT_TEXT, false);
        titleBlock.addView(title);
        titleBlock.addView(subtitle);
        LinearLayout.LayoutParams titleBounds = new LinearLayout.LayoutParams(0, -2, 1);
        titleBounds.setMarginStart(dp(11));
        header.addView(titleBlock, titleBounds);
        subtitles = addControl(header, "CC  字幕", "字幕", subtitleMenu);
        audio = addControl(header, "♪  音轨", "音轨", audioMenu);

        bottom = style.column();
        bottom.setBackground(shade(GradientDrawable.Orientation.TOP_BOTTOM, Color.TRANSPARENT, 0xf5020507));
        addView(bottom, new LayoutParams(-1, -2, Gravity.BOTTOM));
        timeline = new DefaultTimeBar(context);
        timeline.setId(R.id.playback_timeline);
        timeline.setPlayedColor(LibraryUi.ACCENT_LIGHT);
        timeline.setScrubberColor(0xffe2f8ff);
        timeline.setBufferedColor(0x9affffff);
        timeline.setUnplayedColor(0x60ffffff);
        timeline.setKeyTimeIncrement(5_000);
        bottom.addView(timeline, new LinearLayout.LayoutParams(-1, dp(44)));
        bindScrubbing();

        transportRow = new FrameLayout(context);
        bottom.addView(transportRow, new LinearLayout.LayoutParams(-1, -2));
        counters = new LinearLayout(context);
        time = singleLine("0:00 / —", 20, LibraryUi.TEXT, false);
        time.setId(R.id.playback_elapsed);
        quality = singleLine(qualityLimit, 18, LIGHT_TEXT, false);
        quality.setGravity(Gravity.END);
        quality.setContentDescription("播放画质上限 " + qualityLimit);
        counters.addView(time, new LinearLayout.LayoutParams(0, -2, 1));
        counters.addView(quality, new LinearLayout.LayoutParams(0, -2, 1));
        transportRow.addView(counters, new LayoutParams(-1, -2, Gravity.CENTER_VERTICAL));

        LinearLayout transport = new LinearLayout(context);
        transport.setGravity(Gravity.CENTER_VERTICAL);
        transportRow.addView(transport, new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        previous = addControl(transport, "‹ 上一集", "上一集", previousEpisode);
        rewind = addControl(transport, "−5 秒", "后退 5 秒", () -> seekBy(-5_000));
        play = control("", "播放", this::togglePlaying);
        play.setId(R.id.playback_toggle);
        play.setTextColor(0xff0a2531);
        play.setTextSize(24);
        play.setBackground(style.rounded(0xffecf8fc, 33));
        play.setClipToOutline(true);
        LinearLayout.LayoutParams playBounds = new LinearLayout.LayoutParams(dp(66), dp(66));
        playBounds.setMarginStart(dp(8));
        transport.addView(play, playBounds);
        forward = addControl(transport, "+15 秒", "前进 15 秒", () -> seekBy(15_000));
        next = addControl(transport, "下一集 ›", "下一集", nextEpisode);

        message = style.label("", 20, LibraryUi.TEXT, false);
        message.setGravity(Gravity.CENTER);
        message.setPadding(dp(16), dp(12), dp(16), dp(12));
        message.setBackground(style.rounded(0xc0202020, LibraryUi.RADIUS_PANEL));
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LayoutParams messageBounds = new LayoutParams(-2, -2, Gravity.CENTER);
        messageBounds.setMargins(dp(24), 0, dp(24), 0);
        addView(message, messageBounds);

        preparationCenter = style.column();
        preparationCenter.setGravity(Gravity.CENTER);
        preparationCenter.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        preparationCenter.setVisibility(GONE);
        LayoutParams centerBounds = new LayoutParams(-1, -1);
        addView(preparationCenter, centerBounds);
        preparationSpinner = new ProgressBar(context);
        preparationSpinner.setId(R.id.playback_preparation_spinner);
        preparationSpinner.setIndeterminateTintList(ColorStateList.valueOf(LibraryUi.ACCENT_LIGHT));
        preparationSpinner.setContentDescription("正在准备视频，等待首帧");
        if (!LoadingUi.animationsEnabled(context)) {
            GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL);
            ring.setStroke(dp(3), LibraryUi.ACCENT_LIGHT);
            preparationSpinner.setIndeterminateDrawable(ring);
        }
        preparationCenter.addView(preparationSpinner, new LinearLayout.LayoutParams(dp(48), dp(48)));
        preparationSlow = style.label("视频准备较慢，请稍候", 22, LibraryUi.TEXT, false);
        preparationSlow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slowBounds = new LinearLayout.LayoutParams(-2, -2);
        slowBounds.topMargin = dp(14);
        preparationCenter.addView(preparationSlow, slowBounds);
        preparationError = style.column();
        preparationError.setGravity(Gravity.CENTER);
        ScrollView failureCopy = new ScrollView(context);
        failureCopy.setFillViewport(true);
        LinearLayout failureText = style.column();
        failureText.setGravity(Gravity.CENTER);
        failureCopy.addView(failureText, new ScrollView.LayoutParams(-1, -2));
        preparationError.addView(failureCopy, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView failedTitle = style.label("播放失败", 24, LibraryUi.TEXT, true);
        failedTitle.setGravity(Gravity.CENTER);
        failureText.addView(failedTitle);
        preparationReason = style.label("暂时无法播放，请重试。", 18, LIGHT_TEXT, false);
        preparationReason.setGravity(Gravity.CENTER);
        preparationReason.setMaxLines(2);
        preparationReason.setPadding(0, dp(4), 0, dp(8));
        failureText.addView(preparationReason);
        LinearLayout failures = new LinearLayout(context);
        failures.setGravity(Gravity.CENTER);
        preparationRetry = addControl(failures, "重试播放", "重试播放", this::retryPreparation);
        preparationRetry.setId(R.id.playback_preparation_retry);
        preparationRetry.setTextColor(LibraryUi.ACCENT_LIGHT);
        preparationRetry.setBackground(outline(SELECTED_BACKGROUND, 0x8059c7ed, LibraryUi.RADIUS_CONTROL));
        addControl(failures, "返回", "返回", close);
        preparationError.addView(failures);
        preparationCenter.addView(preparationError, new LinearLayout.LayoutParams(-1, -1));

        trackPanel = style.column();
        trackPanel.setId(R.id.playback_track_panel);
        trackPanel.setBackground(outline(0xf5111a20, 0x96657785, LibraryUi.RADIUS_PANEL));
        trackPanel.setPadding(dp(10), dp(5), dp(10), dp(10));
        trackPanel.setClickable(true);
        trackPanel.setVisibility(GONE);
        if (Build.VERSION.SDK_INT >= 28) trackPanel.setAccessibilityPaneTitle("播放轨道");
        addView(trackPanel, new LayoutParams(dp(360), -2, Gravity.TOP | Gravity.END));
        LinearLayout trackHeader = new LinearLayout(context);
        trackHeader.setGravity(Gravity.CENTER_VERTICAL);
        trackTitle = singleLine("", 24, LibraryUi.TEXT, true);
        trackTitle.setPadding(dp(10), 0, 0, 0);
        trackHeader.addView(trackTitle, new LinearLayout.LayoutParams(0, -2, 1));
        TextView closeChoices = control("×", "关闭菜单", this::dismissChoices);
        closeChoices.setTextSize(24);
        closeChoices.setBackgroundColor(Color.TRANSPARENT);
        trackHeader.addView(closeChoices, new LinearLayout.LayoutParams(dp(56), dp(56)));
        trackPanel.addView(trackHeader);
        trackScroll = new ScrollView(context);
        trackList = style.column();
        trackScroll.addView(trackList, new ScrollView.LayoutParams(-1, -2));
        trackPanel.addView(trackScroll, new LinearLayout.LayoutParams(-1, -2));

        setOnApplyWindowInsetsListener((view, insets) -> {
            DisplayCutout cutout = Build.VERSION.SDK_INT >= 28 ? insets.getDisplayCutout() : null;
            leftInset = cutout == null ? 0 : cutout.getSafeInsetLeft();
            topInset = cutout == null ? 0 : cutout.getSafeInsetTop();
            rightInset = cutout == null ? 0 : cutout.getSafeInsetRight();
            bottomInset = cutout == null ? 0 : cutout.getSafeInsetBottom();
            fitSafeArea();
            return insets;
        });
        bottom.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            liftCaptions();
            fitTrackList();
            fitPreparation();
        });
        header.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> fitPreparation());
        SubtitleView captions = video.getSubtitleView();
        if (captions != null) captions.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> liftCaptions());
        updateActions(false, false, false, false, false);
        message.setVisibility(GONE);
    }

    private TextView singleLine(String text, int size, int color, boolean bold) {
        TextView label = style.label(text, size, color, bold);
        label.setSingleLine();
        label.setEllipsize(TextUtils.TruncateAt.END);
        return label;
    }

    private TextView control(String label, String description, Runnable action) {
        TextView button = style.label(label, 20, LibraryUi.TEXT, true);
        button.setSingleLine();
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(dp(56));
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setContentDescription(description);
        button.setBackground(outline(CONTROL_BACKGROUND, CONTROL_BORDER, LibraryUi.RADIUS_CONTROL));
        style.actionable(button, () -> {
            action.run();
            restartControlsTimeout();
        });
        return button;
    }

    private TextView addControl(LinearLayout row, String label, String description, Runnable action) {
        TextView button = control(label, description, action);
        LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(-2, dp(56));
        if (row.getChildCount() > 0) bounds.setMarginStart(dp(6));
        row.addView(button, bounds);
        return button;
    }

    private GradientDrawable outline(int color, int border, int radius) {
        GradientDrawable drawable = style.rounded(color, radius);
        drawable.setStroke(dp(1), border);
        return drawable;
    }

    private GradientDrawable shade(GradientDrawable.Orientation direction, int from, int to) {
        return new GradientDrawable(direction, new int[] { from, to });
    }

    private int dp(int value) { return style.dp(value); }

    void setTitle(String name, String detail) {
        title.setText(name);
        subtitle.setText(detail);
        subtitle.setVisibility(detail.isEmpty() ? GONE : VISIBLE);
    }

    void setPlayer(Player nextPlayer) {
        if (player == nextPlayer) return;
        if (player != null) player.removeListener(playerEvents);
        player = nextPlayer;
        video.setPlayer(nextPlayer);
        if (player != null) player.addListener(playerEvents);
        updateCaptionCues();
        scrubbing = false;
        soughtToEnd = false;
        restartRefresh();
        restartControlsTimeout();
    }

    void updateActions(boolean episodic, boolean canGoBack, boolean canGoNext, boolean textAvailable,
                       boolean audioAvailable) {
        previous.setVisibility(episodic ? VISIBLE : GONE);
        next.setVisibility(episodic ? VISIBLE : GONE);
        desiredPrevious = canGoBack;
        desiredNext = canGoNext;
        desiredText = textAvailable;
        desiredAudio = audioAvailable;
        applyPreparationGate();
        transportEnabled = textAvailable;
        refreshControls();
    }

    private void applyPreparationGate() {
        enable(previous, !preparing && desiredPrevious);
        enable(next, !preparing && desiredNext);
        enable(subtitles, !preparing && desiredText);
        enable(audio, !preparing && desiredAudio);
    }

    private void retryPreparation() {
        if (!preparationFailed || retryPreparation == null) return;
        preparationFailed = false;
        preparationRetry.setEnabled(false);
        retryPreparation.run();
    }

    void beginPreparation(View artwork, long position, long duration, Runnable retry) {
        cancelPreparationTimers();
        preparationGeneration++;
        preparing = true;
        preparationFailed = false;
        retryPreparation = retry;
        preparationPosition = position;
        preparationDuration = duration;
        dismissChoices();
        message.setVisibility(GONE);
        preparationCover.animate().cancel();
        preparationCover.removeAllViews();
        if (artwork != null) preparationCover.addView(artwork, new LayoutParams(-1, -1));
        preparationCover.setBackground(shade(GradientDrawable.Orientation.TOP_BOTTOM, 0xff0a1218, 0xff020507));
        preparationCover.setForeground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x8502070c, 0xa002070c, 0xda02070c}));
        preparationCover.setAlpha(1f);
        preparationCover.setVisibility(VISIBLE);
        preparationSpinner.setVisibility(INVISIBLE);
        preparationSlow.setVisibility(GONE);
        preparationError.setVisibility(GONE);
        preparationCenter.setVisibility(VISIBLE);
        postDelayed(revealSpinner, 150);
        postDelayed(revealSlow, 6000);
        applyPreparationGate();
        setControlsVisible(true);
        fitSafeArea();
        restartControlsTimeout();
    }

    void setPreparationPosition(long position) {
        preparationPosition = position;
        refreshControls();
    }

    void showPreparationFailure(String reason) {
        cancelPreparationTimers();
        preparationGeneration++;
        preparing = true;
        preparationFailed = true;
        message.setVisibility(GONE);
        preparationCover.animate().cancel();
        preparationCover.setAlpha(1f);
        preparationCover.setVisibility(VISIBLE);
        preparationCenter.setVisibility(VISIBLE);
        preparationSpinner.setVisibility(GONE);
        preparationSlow.setVisibility(GONE);
        preparationReason.setText(reason);
        preparationError.setVisibility(VISIBLE);
        preparationRetry.setEnabled(true);
        applyPreparationGate();
        refreshControls();
        setControlsVisible(true);
        fitSafeArea();
        restartControlsTimeout();
    }

    private void cancelPreparationTimers() {
        removeCallbacks(revealSpinner);
        removeCallbacks(revealSlow);
    }

    private void enable(View button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.45f);
    }

    private boolean canSeek() {
        return !preparing && transportEnabled && player != null && player.isCurrentMediaItemSeekable() && player.getDuration() > 0;
    }

    private void bindScrubbing() {
        timeline.addListener(new TimeBar.OnScrubListener() {
            @Override public void onScrubStart(TimeBar bar, long position) {
                scrubbing = true;
                restartControlsTimeout();
                updateTime(position, player == null ? C.TIME_UNSET : player.getDuration());
            }

            @Override public void onScrubMove(TimeBar bar, long position) {
                updateTime(position, player == null ? C.TIME_UNSET : player.getDuration());
            }

            @Override public void onScrubStop(TimeBar bar, long position, boolean canceled) {
                scrubbing = false;
                if (!canceled && canSeek()) seekTo(position);
                refreshControls();
                restartControlsTimeout();
            }
        });
    }

    private void seekBy(long offset) {
        if (!canSeek()) return;
        seekTo(player.getCurrentPosition() + offset);
        refreshControls();
    }

    private void seekTo(long position) {
        long duration = player.getDuration();
        soughtToEnd = position >= duration;
        player.seekTo(Math.max(0, Math.min(duration, position)));
    }

    boolean soughtToEnd() {
        return soughtToEnd;
    }

    private void togglePlaying() {
        if (preparing || !transportEnabled || player == null) return;
        if (player.getPlaybackState() == Player.STATE_ENDED) {
            soughtToEnd = false;
            player.seekToDefaultPosition();
            player.play();
        } else if (player.getPlayWhenReady()) player.pause();
        else player.play();
    }

    private void setPlayingIcon(boolean playing) {
        String label = playing ? "暂停" : "播放";
        if (TextUtils.equals(play.getContentDescription(), label) && play.length() > 0) return;
        play.setText(playing ? "Ⅱ" : "▶");
        play.setContentDescription(label);
    }

    private void updateTime(long position, long duration) {
        String value = LibraryUi.time(position) + " / " + (duration > 0 ? LibraryUi.time(duration) : "—");
        if (!TextUtils.equals(time.getText(), value)) time.setText(value);
    }

    private void refreshControls() {
        boolean seekable = canSeek();
        if (timeline.isEnabled() != seekable) timeline.setEnabled(seekable);
        timeline.setAlpha(preparing ? .45f : 1f);
        counters.setAlpha(preparing ? .45f : 1f);
        enable(rewind, seekable);
        enable(forward, seekable);
        enable(play, !preparing && transportEnabled && player != null);
        setPlayingIcon(player != null && player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED);
        if (scrubbing) return;
        long duration = preparing ? preparationDuration : player == null ? C.TIME_UNSET : player.getDuration();
        long position = preparing ? preparationPosition : player == null ? 0 : player.getCurrentPosition();
        long buffer = player == null ? 0 : player.getBufferedPosition();
        if (duration != shownDuration) {
            shownDuration = duration;
            timeline.setDuration(duration);
        }
        if (position != shownPosition) {
            shownPosition = position;
            timeline.setPosition(position);
        }
        if (buffer != shownBuffer) {
            shownBuffer = buffer;
            timeline.setBufferedPosition(buffer);
        }
        updateTime(position, duration);
    }

    private boolean canRefresh() {
        return isAttachedToWindow() && controlsVisible && player != null && (player.isPlaying() || player.isLoading());
    }

    private void restartRefresh() {
        removeCallbacks(refresh);
        refreshControls();
        if (canRefresh()) postDelayed(refresh, 250);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        restartRefresh();
        restartControlsTimeout();
    }

    @Override protected void onDetachedFromWindow() {
        cancelPreparationTimers();
        preparationGeneration++;
        preparationCover.animate().cancel();
        retryPreparation = null;
        removeCallbacks(refresh);
        removeCallbacks(hideControls);
        touching = false;
        super.onDetachedFromWindow();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            touching = true;
            removeCallbacks(hideControls);
        }
        boolean handled = super.dispatchTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            touching = false;
            restartControlsTimeout();
        }
        return handled;
    }

    void showMessage(String text) {
        dismissChoices();
        message.setText(text);
        message.setVisibility(VISIBLE);
        setControlsVisible(true);
        restartControlsTimeout();
    }

    void showVideo() {
        message.setVisibility(GONE);
        cancelPreparationTimers();
        preparationCenter.setVisibility(GONE);
        int generation = ++preparationGeneration;
        Runnable handoff = () -> {
            if (generation != preparationGeneration) return;
            preparationCover.setVisibility(GONE);
            preparing = false;
            preparationFailed = false;
            fitSafeArea();
            applyPreparationGate();
            refreshControls();
            restartControlsTimeout();
        };
        preparationCover.animate().cancel();
        if (preparationCover.getVisibility() == VISIBLE && LoadingUi.animationsEnabled(getContext()))
            preparationCover.animate().alpha(0f).setDuration(150).withEndAction(handoff).start();
        else handoff.run();
    }

    private boolean controlsMustStayVisible() {
        return preparing || player == null || !player.isPlaying() || message.getVisibility() == VISIBLE
                || scrubbing || activeTrackButton != null;
    }

    private void restartControlsTimeout() {
        removeCallbacks(hideControls);
        if (!isAttachedToWindow()) return;
        if (controlsMustStayVisible()) {
            if (!controlsVisible) setControlsVisible(true);
        } else if (controlsVisible && !touching) {
            postDelayed(hideControls, CONTROLS_TIMEOUT_MS);
        }
        describeTouchSurface();
    }

    private void describeTouchSurface() {
        String description = "显示播放控制";
        if (controlsVisible) {
            if (activeTrackButton != null) description = "收起轨道菜单";
            else description = controlsMustStayVisible() ? "播放画面" : "隐藏播放控制";
        }
        if (!TextUtils.equals(touchSurface.getContentDescription(), description)) {
            touchSurface.setContentDescription(description);
        }
    }

    private void setControlsVisible(boolean visible) {
        controlsVisible = visible;
        header.setVisibility(visible ? VISIBLE : GONE);
        bottom.setVisibility(visible ? VISIBLE : GONE);
        describeTouchSurface();
        post(this::liftCaptions);
        restartRefresh();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        fitSafeArea();
    }

    private void fitSafeArea() {
        int gutter = dp(18);
        boolean shortScreen = preparing && getHeight() - topInset - bottomInset < dp(420);
        header.setPadding(gutter + leftInset, dp(14) + topInset, gutter + rightInset, dp(shortScreen ? 10 : 20));
        bottom.setPadding(gutter + leftInset, 0, gutter + rightInset, dp(shortScreen ? 10 : 14) + bottomInset);
        LinearLayout.LayoutParams timeBounds = (LinearLayout.LayoutParams) timeline.getLayoutParams();
        timeBounds.height = dp(shortScreen ? 24 : 44);
        timeline.setLayoutParams(timeBounds);
        fitPreparation();
        boolean compact = getWidth() - leftInset - rightInset < dp(545);
        transportRow.setMinimumHeight(dp(100));
        LayoutParams counterBounds = (LayoutParams) counters.getLayoutParams();
        counterBounds.gravity = Gravity.TOP;
        counters.setLayoutParams(counterBounds);
        LayoutParams panelBounds = (LayoutParams) trackPanel.getLayoutParams();
        panelBounds.topMargin = topInset + dp(86);
        panelBounds.setMarginEnd(gutter + rightInset);
        panelBounds.width = Math.max(dp(160), Math.min(dp(360), getWidth() - leftInset - rightInset - 2 * gutter));
        trackPanel.setLayoutParams(panelBounds);
        fitTrackList();
    }

    private void fitPreparation() {
        LayoutParams centerBounds = (LayoutParams) preparationCenter.getLayoutParams();
        int top = Math.max(dp(80) + topInset, header.getHeight()) + dp(4);
        int bottomMargin = Math.max(dp(128) + bottomInset, bottom.getHeight()) + dp(4);
        int left = dp(18) + leftInset, right = dp(18) + rightInset;
        if (centerBounds.topMargin == top && centerBounds.bottomMargin == bottomMargin
                && centerBounds.leftMargin == left && centerBounds.rightMargin == right) return;
        centerBounds.topMargin = top;
        centerBounds.bottomMargin = bottomMargin;
        centerBounds.leftMargin = left;
        centerBounds.rightMargin = right;
        preparationCenter.setLayoutParams(centerBounds);
    }

    private void liftCaptions() {
        SubtitleView captions = video.getSubtitleView();
        if (captions == null || captions.getHeight() == 0) return;
        int fullHeight = captions.getHeight();
        int inset = 0;
        if (controlsVisible && bottom.getHeight() > 0) {
            captions.getLocationInWindow(captionLocation);
            bottom.getLocationInWindow(bottomLocation);
            int overlap = captionLocation[1] + fullHeight - bottomLocation[1];
            inset = Math.min(fullHeight / 2, Math.max(0, overlap + dp(3)));
        }
        if (captions.getPaddingBottom() != inset) captions.setPadding(0, 0, 0, inset);
        captions.setFixedTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(fullHeight * SubtitleView.DEFAULT_TEXT_SIZE_FRACTION, android.util.TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 28, getResources().getDisplayMetrics())));
        float defaultGap = fullHeight * SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION;
        captions.setBottomPaddingFraction(Math.max(0, defaultGap - inset) / (fullHeight - inset));
        updateCaptionCues();
    }

    private void updateCaptionCues() {
        SubtitleView captions = video.getSubtitleView();
        if (captions == null || player == null || !player.isCommandAvailable(Player.COMMAND_GET_TEXT)) return;
        List<Cue> original = new ArrayList<>();
        for (Cue cue : player.getCurrentCues().cues) original.add(SubtitleStyle.withoutBackground(cue));
        int fullHeight = captions.getHeight();
        int availableHeight = fullHeight - captions.getPaddingBottom();
        if (original.isEmpty() || availableHeight <= 0 || availableHeight == fullHeight) {
            captions.setCues(original);
            return;
        }

        // Keep text sizes and authored positions in full video coordinates. Media3 fits overlapping
        // text into the remaining area above the toolbar.
        List<Cue> fitted = new ArrayList<>(original.size());
        for (Cue cue : original) {
            Cue.Builder adjusted = cue.buildUpon();
            if (cue.textSizeType == Cue.TEXT_SIZE_TYPE_FRACTIONAL
                    || cue.textSizeType == Cue.TEXT_SIZE_TYPE_FRACTIONAL_IGNORE_PADDING) {
                adjusted.setTextSize(cue.textSize * fullHeight, Cue.TEXT_SIZE_TYPE_ABSOLUTE);
            }
            if (cue.text != null && cue.line != Cue.DIMEN_UNSET && cue.lineType == Cue.LINE_TYPE_FRACTION) {
                adjusted.setLine(cue.line * fullHeight / availableHeight, Cue.LINE_TYPE_FRACTION);
            }
            fitted.add(adjusted.build());
        }
        captions.setCues(fitted);
    }

    interface Selection {
        void choose(int index);
    }

    void showChoices(String name, String[] choices, int selected, Selection selection) {
        View trigger = "字幕".equals(name) ? subtitles : audio;
        if (trigger == activeTrackButton) {
            dismissChoices();
            return;
        }
        dismissChoices();
        activeTrackButton = trigger;
        activeTrackButton.setBackground(outline(SELECTED_BACKGROUND, 0x8059c7ed, LibraryUi.RADIUS_CONTROL));
        trackTitle.setText(name);
        trackList.removeAllViews();
        View focus = null;
        for (int i = 0; i < choices.length; i++) {
            String value = choices[i];
            String[] lines = "字幕".equals(name) && i == 0
                    ? new String[] { value, "使用 Jellyfin 的字幕偏好" } : value.split(" (?:-|·) ", 2);
            LinearLayout option = addTrackChoice(lines, i == selected, i, selection);
            if (i == selected || focus == null) focus = option;
        }
        trackPanel.setVisibility(VISIBLE);
        restartControlsTimeout();
        fitTrackList();
        trackScroll.scrollTo(0, 0);
        if (focus != null) {
            View choice = focus;
            choice.requestFocus();
            trackScroll.post(() -> {
                if (activeTrackButton == trigger && choice.getParent() == trackList) {
                    int offset = choice.getBottom() - trackScroll.getHeight();
                    if (offset > 0) trackScroll.scrollTo(0, offset);
                }
            });
        }
    }

    private LinearLayout addTrackChoice(String[] lines, boolean selected, int index, Selection selection) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), 0, dp(8), 0);
        row.setSelected(selected);
        row.setBackground(style.rounded(selected ? SELECTED_BACKGROUND : Color.TRANSPARENT,
                LibraryUi.RADIUS_CONTROL));
        LinearLayout copy = style.column();
        copy.addView(singleLine(lines[0], 21, selected ? 0xffa6e9ff : LibraryUi.TEXT, selected));
        if (lines.length > 1) copy.addView(singleLine(lines[1], 17, LIGHT_TEXT, false));
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        if (selected) {
            TextView check = style.label("✓", 16, 0xffa6e9ff, true);
            check.setPadding(dp(7), 0, 0, 0);
            check.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(check);
        }
        style.actionable(row, () -> {
            dismissChoices();
            selection.choose(index);
        });
        trackList.addView(row, new LinearLayout.LayoutParams(-1, dp(CHOICE_HEIGHT)));
        return row;
    }

    private void fitTrackList() {
        if (trackPanel.getVisibility() != VISIBLE) return;
        int available = getHeight() - topInset - bottom.getHeight() - dp(129);
        LinearLayout.LayoutParams bounds = (LinearLayout.LayoutParams) trackScroll.getLayoutParams();
        int height = Math.min(dp(CHOICE_HEIGHT * trackList.getChildCount()),
                Math.max(dp(CHOICE_HEIGHT), available));
        if (bounds.height != height) {
            bounds.height = height;
            trackScroll.setLayoutParams(bounds);
        }
    }

    boolean dismissChoices() {
        if (activeTrackButton == null) return false;
        trackPanel.setVisibility(GONE);
        activeTrackButton.setBackground(outline(CONTROL_BACKGROUND, CONTROL_BORDER, LibraryUi.RADIUS_CONTROL));
        activeTrackButton.requestFocus();
        activeTrackButton = null;
        restartControlsTimeout();
        return true;
    }
}
