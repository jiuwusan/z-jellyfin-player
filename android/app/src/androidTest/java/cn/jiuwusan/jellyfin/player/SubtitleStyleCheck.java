package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import androidx.media3.common.text.Cue;
import androidx.media3.ui.SubtitleView;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

/** Checks the actual caption drawing, plus preservation of authored text properties. */
final class SubtitleStyleCheck {
    private final Instrumentation test;
    SubtitleStyleCheck(Instrumentation test) { this.test = test; }
    private void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private Object call(Class<?> type, String name, Class<?>[] types, Object... args) throws Exception {
        java.lang.reflect.Method method = type.getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(null, args);
    }
    private Bitmap draw(Activity activity, Cue cue, Class<?> styles, boolean apply) throws Exception {
        SubtitleView view = new SubtitleView(activity);
        view.setBackgroundColor(0xff206080);
        view.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 32);
        if (apply) call(styles, "apply", new Class<?>[]{SubtitleView.class}, view);
        view.setCues(Collections.singletonList(cue));
        view.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 640, 360);
        Bitmap image = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888); view.draw(new Canvas(image)); return image;
    }
    private int pixels(Bitmap image, int color) {
        int total = 0; for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) if (image.getPixel(x, y) == color) total++; return total;
    }
    void run() {
        Bundle result = new Bundle();
        try {
            Class<?> styles = Class.forName("cn.jiuwusan.jellyfin.player.SubtitleStyle");
            Activity activity = test.startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(test.getTargetContext().getPackageName(), MainActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            AtomicReference<Throwable> error = new AtomicReference<>();
            test.runOnMainSync(() -> {
                try {
                    SpannableString text = new SpannableString("TEST    TEXT");
                    text.setSpan(new BackgroundColorSpan(Color.BLACK), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    text.setSpan(new ForegroundColorSpan(Color.YELLOW), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    text.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    Cue original = new Cue.Builder().setText(text).setWindowColor(Color.BLACK).setPosition(.5f)
                            .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE).setLine(.5f, Cue.LINE_TYPE_FRACTION).setSize(.8f)
                            .setTextSize(32, Cue.TEXT_SIZE_TYPE_ABSOLUTE).build();
                    Cue clean = (Cue) call(styles, "withoutBackground", new Class<?>[]{Cue.class}, original);
                    check(!clean.windowColorSet, "Cue window remains opaque");
                    Spanned spans = (Spanned) clean.text;
                    check(spans.getSpans(0, spans.length(), BackgroundColorSpan.class).length == 0, "Subtitle file background retained");
                    check(spans.getSpans(0, spans.length(), ForegroundColorSpan.class).length == 1 && spans.getSpans(0, spans.length(), StyleSpan.class).length == 1, "Authored color or font weight lost");
                    check(clean.line == original.line && clean.position == original.position && clean.textSize == original.textSize && clean.textSizeType == original.textSizeType, "Position or font size changed");
                    check(original.windowColorSet && text.getSpans(0, text.length(), BackgroundColorSpan.class).length == 1, "Original cue mutated");
                    Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
                    Cue picture = new Cue.Builder().setBitmap(bitmap).build();
                    check(call(styles, "withoutBackground", new Class<?>[]{Cue.class}, picture) == picture, "Bitmap subtitle was changed"); bitmap.recycle();
                    Bitmap before = draw(activity, original, styles, false), after = draw(activity, clean, styles, true);
                    int oldBlack = pixels(before, Color.BLACK), newBlack = pixels(after, Color.BLACK);
                    check(oldBlack > 5000 && newBlack > 0 && newBlack < oldBlack / 3, "Caption background box not removed, or outline missing");
                    check(pixels(after, Color.YELLOW) > 20 && pixels(after, Color.WHITE) > 20, "Text color or default white text missing");
                    Bitmap comparison = Bitmap.createBitmap(640, 720, Bitmap.Config.ARGB_8888); Canvas canvas = new Canvas(comparison);
                    canvas.drawBitmap(before, 0, 0, null); canvas.drawBitmap(after, 0, 360, null);
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(activity.getExternalFilesDir(null), "subtitle-style-comparison.png"))) { comparison.compress(Bitmap.CompressFormat.PNG, 100, out); }
                    before.recycle(); after.recycle(); comparison.recycle();
                } catch (Throwable e) { error.set(e); }
            });
            if (error.get() != null) throw new AssertionError(error.get());
            result.putString("result", "ALL_PASS: transparent default/file/window backgrounds / black outline / preserved color-weight-size-position / immutable input / bitmap unchanged / rendered pixels");
            test.finish(Activity.RESULT_OK, result);
        } catch (Throwable e) { result.putString("failure", android.util.Log.getStackTraceString(e)); test.finish(Activity.RESULT_CANCELED, result); }
    }
}
