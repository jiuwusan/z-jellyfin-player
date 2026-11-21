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
    static final int BACKGROUND = 0xff16121d;
    static final int PANEL = 0xff241c2e;
    static final int LINE = 0xff45364f;
    static final int TEXT = 0xfffaf3fc;
    static final int MUTED = 0xffd1c2d9;
    static final int ACCENT = 0xffe4c5ff;
    static final int ON_ACCENT = 0xff271731;
    static final int MINT = 0xffb5e7d7;

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

    GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    void actionable(View view, Runnable action) {
        view.setFocusable(true);
        view.setForeground(new RippleDrawable(ColorStateList.valueOf(0x30dfc0ff), null, rounded(Color.WHITE, 14)));
        view.setOnClickListener(ignored -> action.run());
    }

    interface ItemAction { void open(JellyfinApi.Item item); }

    HorizontalScrollView rail(List<JellyfinApi.Item> items, boolean wide, boolean progress, int gutter, ItemAction action) {
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout row = new LinearLayout(context);
        row.setPadding(gutter, dp(3), gutter, dp(6));
        int screenDp = context.getResources().getConfiguration().screenWidthDp;
        int width = wide ? Math.max(143, Math.min(169, Math.round(screenDp * 0.42f)))
                : Math.max(100, Math.min(118, Math.round(screenDp * 0.29f)));
        scroll.addView(row, new FrameLayout.LayoutParams(-2, -2));
        for (int i = 0; i < items.size(); i++) {
            JellyfinApi.Item item = items.get(i);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(width), -2);
            if (i > 0) params.setMarginStart(dp(12));
            row.addView(card(item, wide, progress, dp(width), 14, () -> action.open(item)), params);
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
                params.bottomMargin = dp(20);
                target.addView(row, params);
            }
            JellyfinApi.Item item = items.get(i);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, -2);
            if (i % columns > 0) params.setMarginStart(gap);
            row.addView(card(item, false, false, width, 12, () -> action.open(item)), params);
        }
    }

    private View card(JellyfinApi.Item item, boolean wide, boolean progress, int width, int radius, Runnable action) {
        LinearLayout tile = column();
        FrameLayout cover = picture(item, wide, radius);
        GradientDrawable outline = rounded(Color.TRANSPARENT, radius);
        outline.setStroke(dp(1), 0x19ffffff);
        cover.setForeground(outline);
        tile.addView(cover, new LinearLayout.LayoutParams(-1, width * (wide ? 9 : 24) / 16));
        String name = item.seriesName.isEmpty() ? item.title : item.seriesName;
        TextView title = label(name, 14, TEXT, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(1), dp(6), dp(1), 0);
        tile.addView(title);
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
            tile.addView(info);
        }
        if (progress) {
            addResumeMark(cover);
            if (item.durationMs > 0) positionBar(cover, item.resumeMs, item.durationMs);
            String watchTime = time(item.resumeMs) + (item.durationMs > 0 ? " / " + time(item.durationMs) : "");
            tile.addView(label(watchTime, 12, MUTED, false));
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
        return picture(item, wide, 14);
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
                new int[] { 0x0016121d, 0x2016121d, BACKGROUND }));
        return cover;
    }

    private void addResumeMark(FrameLayout cover) {
        View shade = new View(context);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { Color.TRANSPARENT, 0xe816121d }));
        cover.addView(shade, new FrameLayout.LayoutParams(-1, dp(46), Gravity.BOTTOM));
        TextView play = label("▶", 11, TEXT, false);
        play.setGravity(Gravity.CENTER);
        play.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        GradientDrawable badge = rounded(0xd916121d, 14);
        badge.setStroke(dp(1), 0x80ffffff);
        play.setBackground(badge);
        FrameLayout.LayoutParams bounds = new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.BOTTOM | Gravity.END);
        bounds.setMarginEnd(dp(9));
        bounds.bottomMargin = dp(16);
        cover.addView(play, bounds);
    }

    private void positionBar(FrameLayout frame, long position, long duration) {
        FrameLayout.LayoutParams bounds = new FrameLayout.LayoutParams(-1, dp(4), Gravity.BOTTOM);
        bounds.setMargins(dp(9), 0, dp(9), dp(7));
        frame.addView(watchProgress(position, duration), bounds);
    }

    ProgressBar watchProgress(long position, long duration) {
        ProgressBar bar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress((int) Math.min(100, Math.max(0, 100.0 * position / duration)));
        bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0xff776187));
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
