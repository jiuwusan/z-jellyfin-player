package cn.jiuwusan.jellyfin.player;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Data-region loading widgets; never owns navigation, input or playback. */
final class LoadingUi {
    enum Kind { HOME, GRID, DETAIL, EPISODES, FOOTER }
    interface Gate { boolean active(); }

    static boolean animationsEnabled(Context context) {
        if (Build.VERSION.SDK_INT >= 26) return ValueAnimator.areAnimatorsEnabled();
        return Settings.Global.getFloat(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0;
    }

    static void ready(View view) {
        view.animate().cancel();
        view.setAlpha(1f);
        if (view.isAttachedToWindow() && animationsEnabled(view.getContext())) {
            view.setAlpha(.55f);
            view.animate().alpha(1f).setDuration(150).start();
        }
    }

    static LinearLayout line(LibraryUi style, String message, boolean spinning) {
        Context context = style.context();
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER);
        row.setMinimumHeight(style.dp(64));
        row.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        if (spinning) {
            ProgressBar spinner = new ProgressBar(context);
            spinner.setIndeterminateTintList(ColorStateList.valueOf(LibraryUi.ACCENT_LIGHT));
            LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(style.dp(23), style.dp(23));
            bounds.setMarginEnd(style.dp(12));
            row.addView(spinner, bounds);
        }
        TextView text = style.label(message, 17, LibraryUi.MUTED, false);
        text.setGravity(Gravity.CENTER);
        row.addView(text);
        return row;
    }

    static void empty(LinearLayout target, LibraryUi style, String title, String hint) {
        LinearLayout group = style.column();
        group.setMinimumHeight(style.dp(300));
        group.setGravity(Gravity.CENTER);
        group.setPadding(style.dp(20), style.dp(16), style.dp(20), style.dp(16));
        TextView heading = style.label(title, 24, LibraryUi.TEXT, true);
        heading.setGravity(Gravity.CENTER);
        heading.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        group.addView(heading);
        TextView detail = style.label(hint, 18, LibraryUi.MUTED, false);
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(0, style.dp(12), 0, 0);
        group.addView(detail);
        target.addView(group, new LinearLayout.LayoutParams(-1, -2));
    }

    static void failure(LinearLayout target, LibraryUi style, String message, Runnable retry, boolean compact) {
        target.removeAllViews();
        LinearLayout group = style.column();
        group.setGravity(Gravity.CENTER);
        group.setMinimumHeight(style.dp(compact ? 64 : 240));
        group.setPadding(style.dp(compact ? 0 : 20), style.dp(8), style.dp(compact ? 0 : 20), style.dp(8));
        if (compact) group.setOrientation(LinearLayout.HORIZONTAL);
        else {
            TextView title = style.label("加载失败", 24, LibraryUi.TEXT, true);
            group.addView(title);
        }
        TextView label = style.label(message, compact ? 17 : 18, LibraryUi.MUTED, false);
        label.setGravity(Gravity.CENTER);
        label.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        group.addView(label, compact ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-1, -2));
        TextView button = style.label("重试", 20, LibraryUi.ACCENT_LIGHT, true);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(style.dp(compact ? 48 : 56));
        button.setPadding(style.dp(16), style.dp(8), style.dp(16), style.dp(8));
        style.scrollActionable(button, retry);
        group.addView(button);
        target.addView(group, new LinearLayout.LayoutParams(-1, -2));
    }

    static final class Pending {
        private final LinearLayout target, group;
        private final LibraryUi style;
        private final Handler handler;
        private final Gate gate;
        private final Kind kind;
        private final String knownTitle;
        private final LoadTiming timing = new LoadTiming(SystemClock.uptimeMillis());
        private boolean closed, shown;
        private final Runnable show = this::showSkeleton;
        private final Runnable slow = this::showSlow;

        Pending(LinearLayout target, LibraryUi style, Handler handler, Kind kind, String knownTitle, Gate gate) {
            this.target = target; this.style = style; this.handler = handler;
            this.kind = kind; this.knownTitle = knownTitle; this.gate = gate;
            group = style.column();
            group.setId(R.id.loading_placeholder);
            group.setVisibility(View.GONE);
            group.setContentDescription("正在加载内容");
            group.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            group.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) { }
                @Override public void onViewDetachedFromWindow(View view) { cancel(); }
            });
            target.addView(group, new LinearLayout.LayoutParams(-1, -2));
            if (Boolean.TRUE.equals(target.getTag(R.id.loading_handoff))) {
                target.setTag(R.id.loading_handoff, null);
                shown = true;
                group.setVisibility(View.VISIBLE);
                group.addView(new Skeleton(style.context(), style, kind, knownTitle));
            } else handler.postDelayed(show, LoadTiming.SKELETON_DELAY_MS);
            handler.postDelayed(slow, LoadTiming.SLOW_DELAY_MS);
        }

        private void showSkeleton() {
            if (closed || !gate.active() || !timing.skeleton(SystemClock.uptimeMillis())) return;
            shown = true;
            group.setVisibility(View.VISIBLE);
            if (kind == Kind.FOOTER) group.addView(line(style, "正在加载更多…", true));
            else group.addView(new Skeleton(style.context(), style, kind, knownTitle), new LinearLayout.LayoutParams(-1, -2));
        }

        private void showSlow() {
            if (closed || !gate.active() || !timing.slow(SystemClock.uptimeMillis())) return;
            if (kind == Kind.FOOTER) group.removeAllViews();
            group.addView(line(style, "加载较慢，请稍候…", kind == Kind.FOOTER));
        }

        boolean wasShown() { return shown; }
        private void cancel() {
            closed = true;
            timing.finish();
            handler.removeCallbacks(show);
            handler.removeCallbacks(slow);
        }
        void finish() { cancel(); target.removeView(group); }
    }

    static final class RefreshNotice {
        private final LibraryUi style;
        private final Handler handler;
        private final Gate gate;
        private final LinearLayout bar;
        private final TextView message, retry;
        private final Runnable slow = this::showSlow;
        private void showSlow() {
            if (!gate.active()) return;
            message.setText("刷新较慢，原有内容仍可浏览。");
            retry.setVisibility(View.GONE);
            bar.setVisibility(View.VISIBLE);
        }

        RefreshNotice(LinearLayout root, View refresh, LibraryUi style, Handler handler, Gate gate, Runnable reload) {
            this.style = style; this.handler = handler; this.gate = gate;
            bar = new LinearLayout(style.context());
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(style.dp(16), style.dp(6), style.dp(16), style.dp(6));
            bar.setBackgroundColor(LibraryUi.PANEL);
            bar.setVisibility(View.GONE);
            message = style.label("", 17, LibraryUi.TEXT, false);
            message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            bar.addView(message, new LinearLayout.LayoutParams(0, -2, 1));
            retry = style.label("重试", 20, LibraryUi.ACCENT_LIGHT, true);
            retry.setGravity(Gravity.CENTER); retry.setMinHeight(style.dp(48));
            retry.setPadding(style.dp(10), 0, style.dp(4), 0);
            style.actionable(retry, reload); bar.addView(retry);
            root.addView(bar, root.indexOfChild(refresh) + 1, new LinearLayout.LayoutParams(-1, -2));
            bar.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) { }
                @Override public void onViewDetachedFromWindow(View v) { handler.removeCallbacks(slow); }
            });
        }
        void start() { clear(); handler.postDelayed(slow, LoadTiming.SLOW_DELAY_MS); }
        void clear() { handler.removeCallbacks(slow); bar.setVisibility(View.GONE); }
        void failed(String reason) {
            handler.removeCallbacks(slow);
            message.setText("刷新失败，保留原有内容。" + reason);
            retry.setVisibility(View.VISIBLE); bar.setVisibility(View.VISIBLE);
        }
    }

    private static final class Skeleton extends View {
        private final LibraryUi style;
        private final Kind kind;
        private final String knownTitle;
        private final Paint shape = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ValueAnimator animator;
        private float sweep = -1;
        Skeleton(Context context, LibraryUi style, Kind kind, String knownTitle) {
            super(context); this.style = style; this.kind = kind; this.knownTitle = knownTitle;
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            setFocusable(false); setClickable(false);
        }
        private int d(int value) { return style.dp(value); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), height;
            if (kind == Kind.HOME) height = d(56) + 2 * (width * 9 / 16 + d(64)) + d(20);
            else if (kind == Kind.GRID) { int cols = width >= d(650) ? 3 : 2; int pw = (width - d(14) * (cols - 1)) / cols; height = 2 * (pw * 3 / 2 + d(64)) + d(26); }
            else if (kind == Kind.EPISODES) height = d(330);
            else height = d(heroHeight() + 148 + 180 + 330);
            setMeasuredDimension(width, height);
        }
        private int heroHeight() { return Math.max(185, Math.min(228, Math.round(getResources().getConfiguration().screenHeightDp * .27f))); }
        private void box(Canvas canvas, float x, float y, float w, float h, int radius) {
            canvas.drawRoundRect(x, y, x + Math.max(0,w), y + h, d(radius), d(radius), shape);
        }
        private void episode(Canvas canvas, int top, int width, int gutter) {
            box(canvas, gutter, top + d(28), d(96), d(54), 4);
            float left = gutter + d(110), available = width - left - gutter;
            box(canvas, left, top + d(27), available, d(23), 4);
            box(canvas, left, top + d(62), available * .65f, d(17), 4);
        }
        @Override protected void onDraw(Canvas canvas) {
            float width = getWidth();
            shape.setColor(0xff232323);
            shape.setShader(animator == null ? null : new LinearGradient(width * (sweep - .4f), 0, width * (sweep + .4f), 0,
                    new int[] {0xff232323, 0xff2a2a2a, 0xff232323}, null, Shader.TileMode.CLAMP));
            if (kind == Kind.HOME) {
                box(canvas, 0, 0, d(142), d(33), 4);
                float top = d(56);
                for (int i = 0; i < 2; i++) {
                    box(canvas, 0, top, width, width * 9 / 16, 6);
                    box(canvas, d(14), top + width * 9 / 16 + d(19), width * .73f, d(23), 4);
                    top += width * 9 / 16 + d(84);
                }
            } else if (kind == Kind.GRID) {
                int cols = width >= d(650) ? 3 : 2;
                float pw = (width - d(14) * (cols - 1)) / cols, ph = pw * 1.5f;
                for (int row = 0; row < 2; row++) for (int col = 0; col < cols; col++) {
                    float x = col * (pw + d(14)), y = row * (ph + d(90));
                    box(canvas, x, y, pw, ph, 6);
                    box(canvas, x, y + ph + d(12), pw * .84f, d(23), 4);
                    box(canvas, x, y + ph + d(45), pw * .55f, d(17), 4);
                }
            } else if (kind == Kind.EPISODES) {
                for (int i = 0; i < 3; i++) episode(canvas, d(i * 110), getWidth(), 0);
            } else {
                int hero = d(heroHeight()), poster = d(Math.max(94, Math.min(114, Math.round(getResources().getConfiguration().screenWidthDp * .27f))));
                box(canvas, 0, 0, width, hero, 0);
                box(canvas, d(20), hero - poster * .88f, poster, poster * 1.5f, 6);
                float left = d(38) + poster, available = width - left - d(20);
                if (knownTitle == null || knownTitle.isEmpty()) box(canvas, left, hero + d(16), available, d(32), 4);
                else {
                    text.setColor(LibraryUi.TEXT); text.setTextSize(30 * getResources().getDisplayMetrics().scaledDensity);
                    text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    String caption = TextUtils.ellipsize(knownTitle, new android.text.TextPaint(text), Math.max(1, available), TextUtils.TruncateAt.END).toString();
                    canvas.drawText(caption, left, hero + d(43), text);
                }
                box(canvas, left, hero + d(65), available * .7f, d(18), 4);
                box(canvas, d(20), hero + d(104), width - d(40), d(56), 6);
                for (int i = 0; i < 3; i++) box(canvas, d(20), hero + d(196 + i * 34), (width - d(40)) * (i == 2 ? .65f : 1), d(22), 4);
                for (int i = 0; i < 3; i++) episode(canvas, hero + d(328 + i * 110), getWidth(), d(20));
            }
        }
        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (animationsEnabled(getContext())) {
                animator = ValueAnimator.ofFloat(-1, 2); animator.setDuration(1500); animator.setRepeatCount(ValueAnimator.INFINITE);
                animator.addUpdateListener(a -> { sweep = (Float) a.getAnimatedValue(); invalidate(); }); animator.start();
            }
        }
        @Override protected void onDetachedFromWindow() { if (animator != null) { animator.cancel(); animator = null; } super.onDetachedFromWindow(); }
    }
}
