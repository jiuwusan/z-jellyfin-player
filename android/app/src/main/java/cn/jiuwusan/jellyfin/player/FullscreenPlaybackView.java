package cn.jiuwusan.jellyfin.player;

import android.content.Context;
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
    private static final int CHOICE_HEIGHT = 49;
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
        addView(video, new LayoutParams(-1, -1));

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
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout titleBlock = style.column();
        title = singleLine("", 22, LibraryUi.TEXT, true);
        subtitle = singleLine("", 12, LIGHT_TEXT, false);
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
        time = singleLine("0:00 / —", 12, LibraryUi.TEXT, false);
        time.setId(R.id.playback_elapsed);
        quality = singleLine(qualityLimit, 12, LIGHT_TEXT, false);
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
        play.setBackground(style.rounded(0xffecf8fc, 27));
        play.setClipToOutline(true);
        LinearLayout.LayoutParams playBounds = new LinearLayout.LayoutParams(dp(54), dp(54));
        playBounds.setMarginStart(dp(8));
        transport.addView(play, playBounds);
        forward = addControl(transport, "+15 秒", "前进 15 秒", () -> seekBy(15_000));
        next = addControl(transport, "下一集 ›", "下一集", nextEpisode);

        message = style.label("", 15, LibraryUi.TEXT, false);
        message.setGravity(Gravity.CENTER);
        message.setPadding(dp(16), dp(12), dp(16), dp(12));
        message.setBackground(style.rounded(0xc0202020, 12));
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LayoutParams messageBounds = new LayoutParams(-2, -2, Gravity.CENTER);
        messageBounds.setMargins(dp(24), 0, dp(24), 0);
        addView(message, messageBounds);

        trackPanel = style.column();
        trackPanel.setId(R.id.playback_track_panel);
        trackPanel.setBackground(outline(0xf5111a20, 0x96657785, 15));
        trackPanel.setPadding(dp(10), dp(5), dp(10), dp(10));
        trackPanel.setClickable(true);
        trackPanel.setVisibility(GONE);
        if (Build.VERSION.SDK_INT >= 28) trackPanel.setAccessibilityPaneTitle("播放轨道");
        addView(trackPanel, new LayoutParams(dp(274), -2, Gravity.TOP | Gravity.END));
        LinearLayout trackHeader = new LinearLayout(context);
        trackHeader.setGravity(Gravity.CENTER_VERTICAL);
        trackTitle = singleLine("", 17, LibraryUi.TEXT, true);
        trackTitle.setPadding(dp(10), 0, 0, 0);
        trackHeader.addView(trackTitle, new LinearLayout.LayoutParams(0, -2, 1));
        TextView closeChoices = control("×", "关闭菜单", this::dismissChoices);
        closeChoices.setTextSize(24);
        closeChoices.setBackgroundColor(Color.TRANSPARENT);
        trackHeader.addView(closeChoices, new LinearLayout.LayoutParams(dp(44), dp(44)));
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
        });
        SubtitleView captions = video.getSubtitleView();
        if (captions != null) captions.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> liftCaptions());
        updateActions(false, false, false, false, false);
        showMessage("正在向服务端请求视频…");
    }

    private TextView singleLine(String text, int size, int color, boolean bold) {
        TextView label = style.label(text, size, color, bold);
        label.setSingleLine();
        label.setEllipsize(TextUtils.TruncateAt.END);
        return label;
    }

    private TextView control(String label, String description, Runnable action) {
        TextView button = style.label(label, 13, LibraryUi.TEXT, true);
        button.setSingleLine();
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(dp(44));
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setContentDescription(description);
        button.setBackground(outline(CONTROL_BACKGROUND, CONTROL_BORDER, 12));
        style.actionable(button, () -> {
            action.run();
            restartControlsTimeout();
        });
        return button;
    }

    private TextView addControl(LinearLayout row, String label, String description, Runnable action) {
        TextView button = control(label, description, action);
        LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(-2, dp(44));
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
        enable(previous, canGoBack);
        enable(next, canGoNext);
        enable(subtitles, textAvailable);
        enable(audio, audioAvailable);
        transportEnabled = textAvailable;
        refreshControls();
    }

    private void enable(View button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.45f);
    }

    private boolean canSeek() {
        return transportEnabled && player != null && player.isCurrentMediaItemSeekable() && player.getDuration() > 0;
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
        if (!transportEnabled || player == null) return;
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
        enable(rewind, seekable);
        enable(forward, seekable);
        enable(play, transportEnabled && player != null);
        setPlayingIcon(player != null && player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED);
        if (scrubbing) return;
        long duration = player == null ? C.TIME_UNSET : player.getDuration();
        long position = player == null ? 0 : player.getCurrentPosition();
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
        refreshControls();
        restartControlsTimeout();
    }

    private boolean controlsMustStayVisible() {
        return player == null || !player.isPlaying() || message.getVisibility() == VISIBLE
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
        header.setPadding(gutter + leftInset, dp(14) + topInset, gutter + rightInset, dp(20));
        bottom.setPadding(gutter + leftInset, 0, gutter + rightInset, dp(14) + bottomInset);
        boolean compact = getWidth() - leftInset - rightInset < dp(545);
        transportRow.setMinimumHeight(dp(compact ? 78 : 54));
        LayoutParams counterBounds = (LayoutParams) counters.getLayoutParams();
        counterBounds.gravity = compact ? Gravity.TOP : Gravity.CENTER_VERTICAL;
        counters.setLayoutParams(counterBounds);
        LayoutParams panelBounds = (LayoutParams) trackPanel.getLayoutParams();
        panelBounds.topMargin = topInset + dp(66);
        panelBounds.setMarginEnd(gutter + rightInset);
        panelBounds.width = Math.max(dp(160), Math.min(dp(274), getWidth() - leftInset - rightInset - 2 * gutter));
        trackPanel.setLayoutParams(panelBounds);
        fitTrackList();
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
        captions.setFixedTextSize(TypedValue.COMPLEX_UNIT_PX, fullHeight * SubtitleView.DEFAULT_TEXT_SIZE_FRACTION);
        float defaultGap = fullHeight * SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION;
        captions.setBottomPaddingFraction(Math.max(0, defaultGap - inset) / (fullHeight - inset));
        updateCaptionCues();
    }

    private void updateCaptionCues() {
        SubtitleView captions = video.getSubtitleView();
        if (captions == null || player == null || !player.isCommandAvailable(Player.COMMAND_GET_TEXT)) return;
        List<Cue> original = player.getCurrentCues().cues;
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
        activeTrackButton.setBackground(outline(SELECTED_BACKGROUND, 0x8059c7ed, 12));
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
        row.setBackground(style.rounded(selected ? SELECTED_BACKGROUND : Color.TRANSPARENT, 9));
        LinearLayout copy = style.column();
        copy.addView(singleLine(lines[0], 14, selected ? 0xffa6e9ff : LibraryUi.TEXT, selected));
        if (lines.length > 1) copy.addView(singleLine(lines[1], 11, LIGHT_TEXT, false));
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
        activeTrackButton.setBackground(outline(CONTROL_BACKGROUND, CONTROL_BORDER, 12));
        activeTrackButton.requestFocus();
        activeTrackButton = null;
        restartControlsTimeout();
        return true;
    }
}
