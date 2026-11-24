package cn.jiuwusan.jellyfin.player;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** One continuous vector outline avoids a seam between the pointer and the bubble. */
final class TrackBubbleDrawable extends Drawable {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float density;
    private float anchor;

    TrackBubbleDrawable(float density) {
        this.density = density;
        fill.setColor(0xf5111a20);
        border.setColor(0x96657785);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(density);
    }

    void setAnchor(float value) {
        if (Math.abs(anchor - value) < .5f) return;
        anchor = value;
        invalidateSelf();
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float inset = density / 2;
        float left = bounds.left + inset, right = bounds.right - inset;
        float tip = bounds.top + inset, top = tip + 10 * density;
        float bottom = bounds.bottom - inset, radius = 6 * density, half = 9 * density;
        if (right - left < 2 * (radius + half) || bottom - top < 2 * radius) return;
        float x = Math.max(left + radius + half, Math.min(right - radius - half, bounds.left + anchor));
        path.rewind();
        path.moveTo(left + radius, top);
        path.lineTo(x - half, top);
        path.cubicTo(x - half / 2, top, x - 2 * density, tip, x, tip);
        path.cubicTo(x + 2 * density, tip, x + half / 2, top, x + half, top);
        path.lineTo(right - radius, top);
        path.quadTo(right, top, right, top + radius);
        path.lineTo(right, bottom - radius);
        path.quadTo(right, bottom, right - radius, bottom);
        path.lineTo(left + radius, bottom);
        path.quadTo(left, bottom, left, bottom - radius);
        path.lineTo(left, top + radius);
        path.quadTo(left, top, left + radius, top);
        path.close();
        canvas.drawPath(path, fill);
        canvas.drawPath(path, border);
    }

    @Override public void setAlpha(int alpha) { fill.setAlpha(alpha); border.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { fill.setColorFilter(filter); border.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
