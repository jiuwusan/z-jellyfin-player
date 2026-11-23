package cn.jiuwusan.jellyfin.player;

import android.graphics.Color;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import androidx.media3.common.text.Cue;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.SubtitleView;

/** Transparent text captions with a readable outline, without changing authored size or position. */
final class SubtitleStyle {
    static void apply(SubtitleView view) {
        if (view != null) view.setStyle(new CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT,
                Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null));
    }

    static Cue withoutBackground(Cue cue) {
        if (cue.text == null) return cue; // Bitmap subtitles keep their own pixels and rendering rules.
        Cue.Builder clean = cue.buildUpon().clearWindowColor();
        if (cue.text instanceof Spanned) {
            Spanned source = (Spanned) cue.text;
            BackgroundColorSpan[] backgrounds = source.getSpans(0, source.length(), BackgroundColorSpan.class);
            if (backgrounds.length > 0) {
                SpannableString text = new SpannableString(source);
                for (BackgroundColorSpan background : backgrounds) text.removeSpan(background);
                clean.setText(text);
            }
        }
        return clean.build();
    }
}
