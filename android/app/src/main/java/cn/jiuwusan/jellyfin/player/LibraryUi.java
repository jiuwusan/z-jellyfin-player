package cn.jiuwusan.jellyfin.player;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.List;

final class LibraryUi {
    static final int BACKGROUND = 0xff101010;
    static final int PANEL = 0xff202020;
    static final int SURFACE = 0xff181818;
    static final int INPUT = 0xff292929;
    static final int LINE = 0x23ffffff;
    static final int TEXT = Color.WHITE;
    static final int MUTED = 0xffd2d2d2;
    static final int ACCENT = 0xff00a4dc;
    static final int ACCENT_LIGHT = 0xff70d5f5;
    static final int ON_ACCENT = 0xff061820;
    static final int SELECTED = 0x2500a4dc;
    static final int ERROR = 0xffffabad;
    static final int RADIUS_SMALL = 4;
    static final int RADIUS_CONTROL = 6;
    static final int RADIUS_PANEL = 8;

    private final Context context;
    private final Handler main;
    private ArtworkLoader artwork;
    private JellyfinApi connection;

    LibraryUi(Context context, Handler main) {
        this.context = context;
        this.main = main;
    }

    void useConnection(JellyfinApi client) {
        connection = client;
        if (artwork == null) artwork = new ArtworkLoader(context, main);
        else artwork.reset();
    }

    Context context() { return context; }

    int dp(int size) { return Math.round(context.getResources().getDisplayMetrics().density * size); }

    LinearLayout column() {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(size);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    ImageView brandMark() {
        ImageView mark = new ImageView(context);
        mark.setImageResource(R.drawable.brand_mark);
        mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return mark;
    }

    GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    void actionable(View view, Runnable action) {
        view.setFocusable(true);
        view.setForeground(new RippleDrawable(ColorStateList.valueOf(0x3059c7ed), null,
                rounded(Color.WHITE, RADIUS_CONTROL)));
        view.setOnClickListener(ignored -> action.run());
    }

    // Returning false on a tap preserves View's click, keyboard and accessibility handling.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    void scrollActionable(View view, Runnable action) {
        actionable(view, action);
        TapGesture gesture = new TapGesture(ViewConfiguration.get(context).getScaledTouchSlop());
        view.setOnTouchListener((target, event) -> {
            int type = event.getActionMasked();
            if (type == MotionEvent.ACTION_DOWN) {
                gesture.start(event.getX(), event.getY());
                return false;
            }
            if (type == MotionEvent.ACTION_CANCEL) {
                gesture.cancel();
                return false;
            }
            if (type == MotionEvent.ACTION_POINTER_DOWN || type == MotionEvent.ACTION_POINTER_UP) {
                gesture.cancel();
            } else if (type == MotionEvent.ACTION_MOVE || type == MotionEvent.ACTION_UP) {
                if (event.getPointerCount() != 1) gesture.cancel();
                for (int h = 0; h < event.getHistorySize(); h++) {
                    gesture.move(event.getHistoricalX(h), event.getHistoricalY(h));
                }
                gesture.move(event.getX(), event.getY());
            } else {
                return false;
            }
            if (!gesture.blocked()) return false;
            // Cancel View's pressed state and pending click, but leave parent scrolling intact.
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            try { target.onTouchEvent(cancel); }
            finally { cancel.recycle(); }
            return true;
        });
    }

    interface ItemAction { void open(JellyfinApi.Item item); }

    LinearLayout homeList(List<JellyfinApi.Item> items, boolean progress, int width, ItemAction details, ItemAction play) {
        LinearLayout list = column();
        for (int i = 0; i < items.size(); i++) {
            JellyfinApi.Item item = items.get(i);
            LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(-1, -2);
            if (i > 0) bounds.topMargin = dp(20);
            list.addView(card(item, true, progress, true, width, () -> details.open(item), () -> play.open(item)), bounds);
        }
        return list;
    }

    void grid(LinearLayout target, List<JellyfinApi.Item> items, int availableWidth, ItemAction action) {
        appendGrid(target, items, availableWidth, 0, action);
    }

    void appendGrid(LinearLayout target, List<JellyfinApi.Item> items, int availableWidth, int start, ItemAction action) {
        if (start >= items.size()) return;
        int gap = dp(14);
        int columns = availableWidth >= dp(650) ? 3 : 2;
        if (context.getResources().getConfiguration().screenWidthDp <= 355) columns = 2;
        int width = Math.max(1, (availableWidth - gap * (columns - 1)) / columns);
        LinearLayout row = start % columns == 0 ? null : (LinearLayout) target.getChildAt(target.getChildCount() - 1);
        for (int i = start; i < items.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(context);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                params.bottomMargin = dp(26);
                target.addView(row, params);
            }
            JellyfinApi.Item item = items.get(i);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, -2);
            if (i % columns > 0) params.setMarginStart(gap);
            row.addView(card(item, false, false, false, width, () -> action.open(item), null), params);
        }
    }

    private View card(JellyfinApi.Item item, boolean wide, boolean progress, boolean homeCard,
                      int width, Runnable action, Runnable playback) {
        boolean resumeStyle = homeCard && wide;
        int radius = resumeStyle ? RADIUS_PANEL : RADIUS_CONTROL;
        LinearLayout tile = column();
        FrameLayout cover = picture(item, wide, resumeStyle ? 0 : radius, wide, false);
        int imageWidth = width;
        if (resumeStyle) {
            GradientDrawable surface = rounded(SURFACE, radius);
            surface.setStroke(dp(1), LINE);
            tile.setBackground(surface);
            tile.setClipToOutline(true);
            int border = dp(1);
            tile.setPadding(border, border, border, border);
            imageWidth -= 2 * border;
        } else {
            GradientDrawable outline = rounded(Color.TRANSPARENT, radius);
            outline.setStroke(dp(1), 0x18ffffff);
            cover.setForeground(outline);
        }
        int imageHeight = Math.max(1, imageWidth * (wide ? 9 : 24) / 16);
        tile.addView(cover, new LinearLayout.LayoutParams(-1, imageHeight));
        LinearLayout copy = tile;
        if (resumeStyle) {
            copy = column();
            copy.setPadding(dp(11), dp(9), dp(11), dp(11));
            tile.addView(copy);
        }
        String name = item.seriesName.isEmpty() ? item.title : item.seriesName;
        TextView title = label(name, resumeStyle ? 24 : 22, TEXT, true);
        if (resumeStyle) {
            android.text.SpannableString caption = new android.text.SpannableString(name
                    + (item.displayYear() > 0 ? "（" + item.displayYear() + "）" : ""));
            if (caption.length() > name.length()) {
                caption.setSpan(new android.text.style.RelativeSizeSpan(20f / 24f), name.length(), caption.length(), 0);
                caption.setSpan(new android.text.style.ForegroundColorSpan(MUTED), name.length(), caption.length(), 0);
                caption.setSpan(new android.text.style.StyleSpan(Typeface.NORMAL), name.length(), caption.length(), 0);
            }
            title.setText(caption);
            title.setSingleLine(true);
        }
        title.setMaxLines(resumeStyle ? 1 : 2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        if (!resumeStyle) title.setPadding(0, dp(7), 0, 0);
        copy.addView(title);
        boolean showSeason = item.season > 0 && (!homeCard || item.season != 1);
        String episode = (showSeason ? "第 " + item.season + " 季" : "")
                + (item.index > 0 ? (showSeason ? " · " : "") + "第 " + item.index + " 集" : "");
        String episodeTitle = homeCard ? "" : episodeTitle(item);
        String itemType = item.collection ? "合集" : item.series ? "剧集" : "Movie".equals(item.type) ? "电影" : "";
        String year = item.collection ? item.childCount >= 0 ? item.childCount + " 项" : ""
                : item.year > 0 ? Integer.toString(item.year) : "";
        String subtitle = item.seriesName.isEmpty()
                ? itemType.isEmpty() ? year : year.isEmpty() ? itemType : itemType + " · " + year
                : episodeTitle.isEmpty() ? episode
                : episode.isEmpty() ? episodeTitle : episode + " · " + episodeTitle;
        if (!resumeStyle && !subtitle.isEmpty()) {
            TextView info = label(subtitle, 17, MUTED, false);
            info.setMaxLines(2);
            info.setEllipsize(TextUtils.TruncateAt.END);
            copy.addView(info);
        }
        if (resumeStyle) {
            View shade = new View(context);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] { Color.TRANSPARENT, 0xe805090c }));
            cover.addView(shade, new FrameLayout.LayoutParams(-1, Math.round(imageHeight * 0.8f), Gravity.BOTTOM));
            if (progress && item.durationMs > 0) positionBar(cover, item.resumeMs, item.durationMs);
            String watchTime = time(item.resumeMs) + (item.durationMs > 0 ? " / " + time(item.durationMs) : "");
            if (progress) {
            TextView elapsed = label(watchTime, 17, TEXT, false);
            elapsed.setSingleLine(true);
            elapsed.setEllipsize(TextUtils.TruncateAt.END);
            elapsed.setFontFeatureSettings("'tnum'");
            elapsed.setShadowLayer(dp(2), 0, dp(1), 0xcc000000);
            FrameLayout.LayoutParams timeBounds = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.START);
            timeBounds.setMarginStart(dp(12));
            timeBounds.setMarginEnd(dp(68));
            timeBounds.bottomMargin = dp(20);
            cover.addView(elapsed, timeBounds);
            }
            if (!item.seriesName.isEmpty() && item.index > 0) {
                TextView episodeLabel = label((item.season > 1 ? "第" + item.season + "季 " : "")
                        + "第" + item.index + "集", 20, TEXT, true);
                episodeLabel.setSingleLine(true);
                episodeLabel.setEllipsize(TextUtils.TruncateAt.END);
                episodeLabel.setShadowLayer(dp(2), 0, dp(1), 0xcc000000);
                FrameLayout.LayoutParams episodeBounds = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.START);
                episodeBounds.setMargins(dp(12), 0, dp(68), dp(progress ? 54 : 22));
                cover.addView(episodeLabel, episodeBounds);
            }
            TextView type = label(item.series || !item.seriesName.isEmpty() ? "剧集" : "电影", 18, TEXT, false);
            type.setPadding(dp(9), dp(3), dp(9), dp(3));
            type.setBackground(rounded(0xb0101010, RADIUS_SMALL));
            FrameLayout.LayoutParams typeBounds = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            typeBounds.setMargins(dp(12), dp(12), dp(12), 0);
            cover.addView(type, typeBounds);
            addResumeIcon(cover, progress ? "继续观看 " + name : "播放 " + name, playback);
            tile.setContentDescription("查看详情 " + name
                    + (subtitle.isEmpty() ? "" : "，" + subtitle)
                    + (progress ? "，已观看 " + watchTime : ""));
        }
        scrollActionable(tile, action);
        return tile;
    }

    static String episodeTitle(JellyfinApi.Item item) {
        if (item.index <= 0) return item.title;
        String prefix = "第\\s*" + item.index + "\\s*集(?:\\s*[·：:、.-]\\s*|\\s+|$)";
        return item.title.replaceFirst("^(?:" + prefix + ")+", "");
    }

    FrameLayout picture(JellyfinApi.Item item, boolean wide) {
        return picture(item, wide, RADIUS_SMALL, false, false);
    }

    static String artworkKey(JellyfinApi.Item item, boolean wide, boolean preferSeriesBackdrop, boolean originalWidth) {
        String series = preferSeriesBackdrop ? item.seriesBackdropId : "";
        boolean backdrop = !series.isEmpty() || wide && item.hasBackdrop;
        String owner = !series.isEmpty() ? series : backdrop || item.hasPoster ? item.id : item.seriesId;
        String revision = !series.isEmpty() ? item.seriesBackdropTag : backdrop ? item.backdropTag : item.posterTag;
        return owner + (backdrop ? ":backdrop" : ":primary")
                + (originalWidth ? ":original:" + (wide ? "wide" : "portrait") : backdrop ? ":640" : ":420")
                + ":" + revision;
    }

    private FrameLayout picture(JellyfinApi.Item item, boolean wide, int radius,
                                boolean preferSeriesBackdrop, boolean originalWidth) {
        FrameLayout cover = new FrameLayout(context);
        cover.setBackground(rounded(PANEL, radius));
        cover.setClipToOutline(true);
        ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        cover.addView(image, new FrameLayout.LayoutParams(-1, -1));
        JellyfinApi client = connection;
        String seriesBackdropId = preferSeriesBackdrop ? item.seriesBackdropId : "";
        if (client != null && (item.hasPoster || item.hasBackdrop && wide || !item.seriesId.isEmpty())) {
            String cacheKey = artworkKey(item, wide, preferSeriesBackdrop, originalWidth);
            if (seriesBackdropId.isEmpty()) {
                artwork.bind(image, cacheKey, originalWidth, (width, height) ->
                        client.artwork(item, wide, "", originalWidth, width, height));
            } else {
                // Shared series artwork and an episode-specific fallback must never share a cache entry.
                artwork.bind(image, cacheKey, originalWidth, (width, height) ->
                                client.backdrop(seriesBackdropId, originalWidth, width, height),
                        artworkKey(item, wide, false, originalWidth), (width, height) ->
                                client.artwork(item, wide, "", originalWidth, width, height));
            }
        }
        return cover;
    }

    FrameLayout hero(JellyfinApi.Item item) {
        FrameLayout cover = picture(item, true, 0, false, true);
        cover.setForeground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { 0x00202020, 0x40101010, PANEL }));
        return cover;
    }

    FrameLayout playbackBackdrop(JellyfinApi.Item item) {
        return picture(item, true, 0, true, false);
    }

    FrameLayout detailPoster(JellyfinApi.Item item) {
        FrameLayout poster = picture(item, false, RADIUS_CONTROL, false, true);
        poster.setElevation(dp(5));
        return poster;
    }

    private void addResumeIcon(FrameLayout cover, String description, Runnable action) {
        FrameLayout hit = new FrameLayout(context);
        hit.setContentDescription(description);
        ImageView play = new ImageView(context);
        play.setImageResource(R.drawable.ic_quick_play);
        play.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        GradientDrawable badge = rounded(0x80101010, 22);
        badge.setStroke(dp(1), 0x38ffffff);
        play.setBackground(badge);
        hit.addView(play, new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER));
        FrameLayout.LayoutParams bounds = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM | Gravity.END);
        bounds.setMarginEnd(dp(6));
        bounds.bottomMargin = dp(14);
        cover.addView(hit, bounds);
        scrollActionable(hit, action);
    }

    private void positionBar(FrameLayout frame, long position, long duration) {
        FrameLayout.LayoutParams bounds = new FrameLayout.LayoutParams(-1, dp(6), Gravity.BOTTOM);
        bounds.setMargins(dp(12), 0, dp(12), dp(9));
        ProgressBar bar = watchProgress(position, duration);
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0x60ffffff));
        frame.addView(bar, bounds);
    }

    ProgressBar watchProgress(long position, long duration) {
        ProgressBar bar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress((int) Math.min(100, Math.max(0, 100.0 * position / duration)));
        bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0xff5a5a5a));
        bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return bar;
    }

    static String time(long milliseconds) {
        long seconds = milliseconds / 1000;
        long minutes = seconds / 60;
        if (minutes >= 60) return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", minutes / 60, minutes % 60, seconds % 60);
        return String.format(java.util.Locale.ROOT, "%d:%02d", minutes, seconds % 60);
    }

    void close() {
        connection = null;
        if (artwork != null) artwork.close();
    }
}
