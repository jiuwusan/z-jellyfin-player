package cn.jiuwusan.jellyfin.player;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class LibraryUi {
    static final int BACKGROUND = 0xff101010;
    static final int PANEL = 0xff202020;
    static final int SURFACE = 0xff181818;
    static final int INPUT = 0xff292929;
    static final int LINE = 0x23ffffff;
    static final int TEXT = Color.WHITE;
    static final int MUTED = 0xffb8b8b8;
    static final int ACCENT = 0xff00a4dc;
    static final int ACCENT_LIGHT = 0xff59c7ed;
    static final int ON_ACCENT = 0xff061820;
    static final int SELECTED = 0x2500a4dc;
    static final int ERROR = 0xffffabad;
    static final int RADIUS_SMALL = 4;
    static final int RADIUS_CONTROL = 6;
    static final int RADIUS_PANEL = 8;

    private final Context context;
    private final Handler main;
    private final ExecutorService images = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private JellyfinApi connection;

    LibraryUi(Context context, Handler main) {
        this.context = context;
        this.main = main;
    }

    void useConnection(JellyfinApi client) {
        connection = client;
        cache.evictAll();
    }

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

    interface ItemAction { void open(JellyfinApi.Item item); }

    HorizontalScrollView rail(List<JellyfinApi.Item> items, boolean wide, boolean progress, int gutter,
                              ItemAction action) {
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout row = new LinearLayout(context);
        row.setPadding(gutter, dp(3), gutter, dp(6));
        int screenDp = context.getResources().getConfiguration().screenWidthDp;
        int width = progress ? Math.max(223, Math.min(282, Math.round(screenDp * 0.68f)))
                : wide ? Math.max(143, Math.min(178, Math.round(screenDp * 0.45f)))
                : Math.max(100, Math.min(117, Math.round(screenDp * 0.28f)));
        scroll.addView(row, new FrameLayout.LayoutParams(-2, -2));
        for (int i = 0; i < items.size(); i++) {
            JellyfinApi.Item item = items.get(i);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(width), -2);
            if (i > 0) params.setMarginStart(dp(12));
            row.addView(card(item, wide, progress, dp(width), () -> action.open(item)), params);
        }
        return scroll;
    }

    void grid(LinearLayout target, List<JellyfinApi.Item> items, int availableWidth, ItemAction action) {
        appendGrid(target, items, availableWidth, 0, action);
    }

    void appendGrid(LinearLayout target, List<JellyfinApi.Item> items, int availableWidth, int start, ItemAction action) {
        if (start >= items.size()) return;
        int gap = dp(12);
        int columns = availableWidth >= dp(500) ? 4 : 3;
        if (context.getResources().getConfiguration().screenWidthDp <= 355) columns = 2;
        int width = Math.max(1, (availableWidth - gap * (columns - 1)) / columns);
        LinearLayout row = start % columns == 0 ? null : (LinearLayout) target.getChildAt(target.getChildCount() - 1);
        for (int i = start; i < items.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(context);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                params.bottomMargin = dp(22);
                target.addView(row, params);
            }
            JellyfinApi.Item item = items.get(i);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, -2);
            if (i % columns > 0) params.setMarginStart(gap);
            row.addView(card(item, false, false, width, () -> action.open(item)), params);
        }
    }

    private View card(JellyfinApi.Item item, boolean wide, boolean progress, int width, Runnable action) {
        int radius = progress ? RADIUS_PANEL : RADIUS_CONTROL;
        LinearLayout tile = column();
        FrameLayout cover = picture(item, wide, progress ? 0 : radius);
        int imageWidth = width;
        if (progress) {
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
        if (progress) {
            copy = column();
            copy.setPadding(dp(11), dp(9), dp(11), dp(11));
            tile.addView(copy);
        }
        String name = item.seriesName.isEmpty() ? item.title : item.seriesName;
        TextView title = label(name, progress ? 16 : 14, TEXT, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        if (!progress) title.setPadding(0, dp(7), 0, 0);
        copy.addView(title);
        String episode = (item.season > 0 ? "第 " + item.season + " 季" : "")
                + (item.index > 0 ? (item.season > 0 ? " · " : "") + "第 " + item.index + " 集" : "");
        String episodeTitle = episodeTitle(item);
        String itemType = item.series ? "剧集" : "Movie".equals(item.type) ? "电影" : "";
        String year = item.year > 0 ? Integer.toString(item.year) : "";
        String subtitle = item.seriesName.isEmpty()
                ? itemType.isEmpty() ? year : year.isEmpty() ? itemType : itemType + " · " + year
                : progress || episodeTitle.isEmpty() ? episode
                : episode.isEmpty() ? episodeTitle : episode + " · " + episodeTitle;
        if (!subtitle.isEmpty()) {
            TextView info = label(subtitle, 12, MUTED, false);
            info.setMaxLines(2);
            info.setEllipsize(TextUtils.TruncateAt.END);
            copy.addView(info);
        }
        if (progress) {
            View shade = new View(context);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] { Color.TRANSPARENT, 0xe805090c }));
            cover.addView(shade, new FrameLayout.LayoutParams(-1, Math.round(imageHeight * 0.57f), Gravity.BOTTOM));
            if (item.durationMs > 0) positionBar(cover, item.resumeMs, item.durationMs);
            String watchTime = time(item.resumeMs) + (item.durationMs > 0 ? " / " + time(item.durationMs) : "");
            TextView elapsed = label(watchTime, 11, TEXT, false);
            elapsed.setSingleLine(true);
            elapsed.setEllipsize(TextUtils.TruncateAt.END);
            elapsed.setFontFeatureSettings("'tnum'");
            elapsed.setShadowLayer(dp(2), 0, dp(1), 0xcc000000);
            FrameLayout.LayoutParams timeBounds = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.START);
            timeBounds.setMarginStart(dp(10));
            timeBounds.setMarginEnd(dp(54));
            timeBounds.bottomMargin = dp(16);
            cover.addView(elapsed, timeBounds);
            addResumeIcon(cover);
            tile.setContentDescription("继续观看 " + name + (subtitle.isEmpty() ? "" : "，" + subtitle)
                    + "，已观看 " + watchTime);
        }
        actionable(tile, action);
        return tile;
    }

    static String episodeTitle(JellyfinApi.Item item) {
        if (item.index <= 0) return item.title;
        String prefix = "第\\s*" + item.index + "\\s*集(?:\\s*[·：:、.-]\\s*|\\s+|$)";
        return item.title.replaceFirst("^(?:" + prefix + ")+", "");
    }

    FrameLayout picture(JellyfinApi.Item item, boolean wide) {
        return picture(item, wide, RADIUS_SMALL);
    }

    private FrameLayout picture(JellyfinApi.Item item, boolean wide, int radius) {
        FrameLayout cover = new FrameLayout(context);
        cover.setBackground(rounded(PANEL, radius));
        cover.setClipToOutline(true);
        TextView placeholder = label(item.folder && !item.series ? "▤" : "▶", 23, MUTED, false);
        placeholder.setGravity(Gravity.CENTER);
        placeholder.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        cover.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
        ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        cover.addView(image, new FrameLayout.LayoutParams(-1, -1));
        JellyfinApi client = connection;
        if (client != null && (item.hasPoster || item.hasBackdrop && wide || !item.seriesId.isEmpty())) {
            String key = item.id + (wide ? ":wide" : ":portrait");
            Bitmap hit = cache.get(key);
            if (hit != null) {
                image.setImageBitmap(hit);
            } else {
                images.execute(() -> {
                    try {
                        Bitmap bitmap = client.artwork(item, wide);
                        if (bitmap != null) main.post(() -> {
                            if (connection != client) return;
                            cache.put(key, bitmap);
                            image.setImageBitmap(bitmap);
                        });
                    } catch (IOException ignored) {
                        // The placeholder remains usable on failed image requests.
                    }
                });
            }
        }
        return cover;
    }

    FrameLayout hero(JellyfinApi.Item item) {
        FrameLayout cover = picture(item, true, 0);
        cover.setForeground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { 0x00101010, 0x40101010, BACKGROUND }));
        return cover;
    }

    private void addResumeIcon(FrameLayout cover) {
        ImageView play = new ImageView(context);
        play.setImageResource(R.drawable.ic_quick_play);
        play.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        GradientDrawable badge = rounded(0x80101010, 17);
        badge.setStroke(dp(1), 0x38ffffff);
        play.setBackground(badge);
        FrameLayout.LayoutParams bounds = new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.BOTTOM | Gravity.END);
        bounds.setMarginEnd(dp(10));
        bounds.bottomMargin = dp(15);
        cover.addView(play, bounds);
    }

    private void positionBar(FrameLayout frame, long position, long duration) {
        FrameLayout.LayoutParams bounds = new FrameLayout.LayoutParams(-1, dp(4), Gravity.BOTTOM);
        bounds.setMargins(dp(10), 0, dp(10), dp(7));
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
        cache.evictAll();
        images.shutdownNow();
    }
}
