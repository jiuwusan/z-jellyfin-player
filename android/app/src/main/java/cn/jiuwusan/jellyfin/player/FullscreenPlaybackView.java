package cn.jiuwusan.jellyfin.player;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

@UnstableApi
final class FullscreenPlaybackView extends FrameLayout {
    final PlayerView video;
    private final LinearLayout top;
    private final TextView message;
    private boolean controlsVisible;

    FullscreenPlaybackView(Context context, LibraryUi style, String title, Runnable close) {
        super(context);
        setBackgroundColor(Color.BLACK);

        video = new PlayerView(context);
        video.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        video.setKeepScreenOn(true);
        addView(video, new LayoutParams(-1, -1));

        message = style.label("", 15, LibraryUi.TEXT, false);
        message.setGravity(Gravity.CENTER);
        message.setPadding(style.dp(16), style.dp(12), style.dp(16), style.dp(12));
        message.setBackground(style.rounded(0xc0202020, 12));
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LayoutParams messageBounds = new LayoutParams(-2, -2, Gravity.CENTER);
        messageBounds.setMargins(style.dp(24), 0, style.dp(24), 0);
        addView(message, messageBounds);

        top = new LinearLayout(context);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { 0xb0000000, Color.TRANSPARENT }));
        int gutter = style.dp(16);
        int vertical = style.dp(8);
        top.setPadding(gutter, vertical, gutter, vertical);
        addView(top, new LayoutParams(-1, -2, Gravity.TOP));

        TextView back = style.label("‹", 28, LibraryUi.TEXT, true);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("返回");
        back.setBackground(style.rounded(0x99202020, 12));
        top.addView(back, new LinearLayout.LayoutParams(style.dp(44), style.dp(44)));
        style.actionable(back, close);

        TextView heading = style.label(title, 16, LibraryUi.TEXT, true);
        heading.setSingleLine();
        heading.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleBounds = new LinearLayout.LayoutParams(0, -2, 1);
        titleBounds.setMarginStart(style.dp(12));
        top.addView(heading, titleBounds);

        setOnApplyWindowInsetsListener((view, insets) -> {
            DisplayCutout cutout = Build.VERSION.SDK_INT >= 28 ? insets.getDisplayCutout() : null;
            if (cutout != null) {
                top.setPadding(gutter + cutout.getSafeInsetLeft(), vertical + cutout.getSafeInsetTop(),
                        gutter + cutout.getSafeInsetRight(), vertical);
            } else {
                top.setPadding(gutter, vertical, gutter, vertical);
            }
            return insets;
        });
        video.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> {
            controlsVisible = visibility == View.VISIBLE;
            updateTop();
        });
        showMessage("正在向服务端请求视频…");
    }

    void showMessage(String text) {
        message.setText(text);
        message.setVisibility(View.VISIBLE);
        updateTop();
    }

    void showVideo() {
        message.setVisibility(View.GONE);
        updateTop();
    }

    private void updateTop() {
        top.setVisibility(controlsVisible || message.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
    }
}
