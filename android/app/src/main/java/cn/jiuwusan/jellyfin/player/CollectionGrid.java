package cn.jiuwusan.jellyfin.player;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Groups only the presentation of direct members; keeps server order within each media type. */
final class CollectionGrid {
    private static final String[] TYPES = {"Movie", "Series", "Episode", "Video", "BoxSet", ""};
    private static final String[] LABELS = {"电影", "剧集", "单集", "视频", "合集", "其他内容"};
    private final LinearLayout target;
    private final LibraryUi style;
    private final int width;
    private final LibraryUi.ItemAction open;
    private final Section[] sections = new Section[TYPES.length];

    CollectionGrid(LinearLayout target, LibraryUi style, int width, LibraryUi.ItemAction open) {
        this.target = target; this.style = style; this.width = width; this.open = open;
    }

    void append(List<JellyfinApi.Item> items, int offset, ScrollView scroll) {
        View anchor = offset > 0 ? firstVisible() : null;
        int oldTop = anchor == null ? 0 : top(anchor);
        if (offset == 0) {
            target.removeAllViews();
            for (int i = 0; i < sections.length; i++) sections[i] = null;
        }
        int[] starts = new int[sections.length];
        for (int i = 0; i < sections.length; i++) starts[i] = sections[i] == null ? 0 : sections[i].items.size();
        for (int i = offset; i < items.size(); i++) {
            JellyfinApi.Item item = items.get(i);
            int type = TYPES.length - 1;
            for (int j = 0; j < TYPES.length - 1; j++) if (TYPES[j].equals(item.type)) { type = j; break; }
            if (sections[type] == null) {
                Section section = new Section(type);
                int index = 0;
                for (int j = 0; j < type; j++) if (sections[j] != null) index++;
                sections[type] = section;
                target.addView(section.root, index);
            }
            sections[type].items.add(item);
        }
        int groups = 0;
        for (Section section : sections) if (section != null) groups++;
        for (int i = 0; i < sections.length; i++) if (sections[i] != null) {
            Section section = sections[i];
            section.title.setVisibility(groups == 1 && i == 0 ? View.GONE : View.VISIBLE);
            style.appendGrid(section.grid, section.items, width, starts[i], open);
        }
        // A later page may introduce a new section above the visible item. Preserve its screen position.
        if (anchor != null) target.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                target.getViewTreeObserver().removeOnPreDrawListener(this);
                if (anchor.isAttachedToWindow()) scroll.scrollBy(0, top(anchor) - oldTop);
                return true;
            }
        });
    }

    private int top(View view) { int[] point = new int[2]; view.getLocationOnScreen(point); return point[1]; }
    private View firstVisible() {
        Rect rect = new Rect();
        for (Section section : sections) if (section != null)
            for (int r = 0; r < section.grid.getChildCount(); r++) {
                LinearLayout row = (LinearLayout) section.grid.getChildAt(r);
                for (int c = 0; c < row.getChildCount(); c++) {
                    View card = row.getChildAt(c);
                    if (card.getGlobalVisibleRect(rect)) return card;
                }
            }
        return null;
    }
    private final class Section {
        final LinearLayout root = style.column(), grid = style.column();
        final TextView title;
        final List<JellyfinApi.Item> items = new ArrayList<>();
        Section(int type) {
            title = style.label(LABELS[type], 24, LibraryUi.TEXT, true);
            title.setPadding(0, style.dp(12), 0, style.dp(12));
            root.addView(title);
            root.addView(grid);
        }
    }
}
