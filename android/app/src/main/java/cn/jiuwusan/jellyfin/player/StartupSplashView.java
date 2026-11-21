package cn.jiuwusan.jellyfin.player;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

final class StartupSplashView extends FrameLayout {
    static final int INTRO_DURATION_MS = 460;

    private final LibraryUi style;
    private final LinearLayout identity;
    private final Paint lighting = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);

    StartupSplashView(Context context, LibraryUi style) {
        super(context);
        this.style = style;
        setBackgroundColor(LibraryUi.BACKGROUND);
        setWillNotDraw(false);

        identity = style.column();
        identity.setGravity(Gravity.CENTER);
        addView(identity, new LayoutParams(-2, -2, Gravity.CENTER));
        identity.setTranslationY(-style.dp(16));
        identity.addView(style.brandMark(), new LinearLayout.LayoutParams(style.dp(140), style.dp(140)));

        TextView name = style.label("Jellyfin", 43, LibraryUi.TEXT, true);
        name.setLetterSpacing(-0.06f);
        name.setIncludeFontPadding(false);
        LinearLayout.LayoutParams nameBounds = new LinearLayout.LayoutParams(-2, -2);
        nameBounds.topMargin = -style.dp(6);
        identity.addView(name, nameBounds);

        TextView subtitle = style.label("家庭影音", 14, 0xffbbcfdb, false);
        subtitle.setLetterSpacing(0.23f);
        LinearLayout.LayoutParams subtitleBounds = new LinearLayout.LayoutParams(-2, -2);
        subtitleBounds.topMargin = style.dp(10);
        identity.addView(subtitle, subtitleBounds);
    }

    int animateIdentity() {
        if (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) return 0;
        identity.setAlpha(0.2f);
        identity.setTranslationY(-style.dp(10));
        identity.animate().alpha(1).translationY(-style.dp(16)).setDuration(INTRO_DURATION_MS).start();
        return INTRO_DURATION_MS;
    }

    void showConnectionStatus(Runnable openSettings) {
        LinearLayout actions = style.column();
        actions.setGravity(Gravity.CENTER);
        TextView status = style.label("正在连接 Jellyfin…", 13, LibraryUi.MUTED, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        actions.addView(status);

        TextView settings = style.label("连接设置", 14, LibraryUi.ACCENT_LIGHT, true);
        settings.setGravity(Gravity.CENTER);
        settings.setMinHeight(style.dp(44));
        settings.setPadding(style.dp(16), 0, style.dp(16), 0);
        style.actionable(settings, openSettings);
        actions.addView(settings);
        LayoutParams position = new LayoutParams(-1, -2, Gravity.BOTTOM);
        position.bottomMargin = style.dp(24);
        addView(actions, position);
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        lighting.setShader(new RadialGradient(w * 0.5f, h * 0.41f, Math.max(1f, Math.max(w, h) * 0.62f),
                new int[] { 0xff102738, 0xff10191f, LibraryUi.BACKGROUND },
                new float[] { 0, 0.26f, 0.88f }, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0, 0, getWidth(), getHeight(), lighting);
        float x = getWidth() * 0.5f;
        float y = getHeight() * 0.5f - style.dp(30);
        float radius = Math.min(getWidth() * 0.41f, style.dp(155));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(style.dp(24));
        ring.setColor(0x0620bdf1);
        canvas.drawCircle(x, y, radius + style.dp(37), ring);
        ring.setStrokeWidth(style.dp(25));
        ring.setColor(0x0c20bdf1);
        canvas.drawCircle(x, y, radius + style.dp(12), ring);
        ring.setStrokeWidth(style.dp(1));
        ring.setColor(0x2720bdf1);
        canvas.drawCircle(x, y, radius, ring);
    }
}
