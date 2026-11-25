package cn.jiuwusan.jellyfin.player;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

/** One scrolling surface, including a collection's detail header and section headings. */
final class RecycledMediaGrid extends RecyclerView {
    private static final String[] TYPES = {"Movie", "Series", "Episode", "Video", "BoxSet", ""};
    private static final String[] LABELS = {"电影", "剧集", "单集", "视频", "合集", "其他内容"};
    private final LibraryUi style;
    private final GridLayoutManager layout;
    private final List<Entry> entries = new ArrayList<>();
    private final MediaAdapter cards = new MediaAdapter();
    private final int columns, cardWidth, gutter, gap;
    private final LibraryUi.ItemAction open;
    private final View header;
    private final LinearLayout status, footer;

    static final class Anchor {
        final String key;
        final int offset;
        Anchor(String key, int offset) { this.key = key; this.offset = offset; }
    }

    RecycledMediaGrid(LibraryUi style, int availableWidth, int gutter, View header,
                      LinearLayout status, LinearLayout footer, LibraryUi.ItemAction open) {
        super(style.context());
        this.style = style; this.gutter = gutter; this.header = header;
        this.status = status; this.footer = footer; this.open = open;
        gap = style.dp(14);
        columns = availableWidth >= style.dp(650) ? 3 : 2;
        cardWidth = Math.max(1, (availableWidth - gap * (columns - 1)) / columns);
        layout = new GridLayoutManager(style.context(), columns);
        layout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) { return entries.get(position).item == null ? columns : 1; }
        });
        setLayoutManager(layout);
        setItemAnimator(null);
        setItemViewCacheSize(columns * 2);
        setClipToPadding(false);
        setPadding(0, 0, 0, style.dp(24));
        addItemDecoration(new ItemDecoration() {
            @Override public void getItemOffsets(Rect out, View view, RecyclerView parent, State state) {
                int position = parent.getChildAdapterPosition(view);
                if (position == NO_POSITION || position >= entries.size()) return;
                Entry entry = entries.get(position);
                if (entry.item == null) {
                    if (entry.kind != 1) { out.left = gutter; out.right = gutter; }
                    return;
                }
                int column = ((GridLayoutManager.LayoutParams) view.getLayoutParams()).getSpanIndex();
                out.left = gutter + column * (gap - 2 * gutter) / columns;
                out.right = gutter + (columns - 1 - column) * (gap - 2 * gutter) / columns;
                out.bottom = style.dp(26);
            }
        });
        setAdapter(cards);
    }

    void display(List<JellyfinApi.Item> items, boolean grouped, boolean preserve) {
        Anchor anchor = preserve ? anchor() : null;
        entries.clear();
        if (header != null) entries.add(new Entry("header", 1, header, null));
        if (items.isEmpty()) entries.add(new Entry("status", 2, status, null));
        else if (!grouped) for (int i = 0; i < items.size(); i++) addItem(items.get(i), i);
        else {
            List<List<JellyfinApi.Item>> groups = new ArrayList<>();
            for (int i = 0; i < TYPES.length; i++) groups.add(new ArrayList<>());
            for (JellyfinApi.Item item : items) {
                int index = TYPES.length - 1;
                for (int i = 0; i < TYPES.length - 1; i++) if (TYPES[i].equals(item.type)) { index = i; break; }
                groups.get(index).add(item);
            }
            int count = 0;
            for (List<JellyfinApi.Item> group : groups) if (!group.isEmpty()) count++;
            for (int i = 0; i < groups.size(); i++) {
                if (groups.get(i).isEmpty()) continue;
                if (count != 1 || i != 0) {
                    View title = style.label(LABELS[i], 24, LibraryUi.TEXT, true);
                    title.setPadding(0, style.dp(12), 0, style.dp(12));
                    entries.add(new Entry("group:" + i, 3, title, null));
                }
                int occurrence = 0;
                for (JellyfinApi.Item item : groups.get(i)) addItem(item, occurrence++);
            }
        }
        entries.add(new Entry("footer", 4, footer, null));
        cards.notifyDataSetChanged();
        restore(anchor);
    }

    private void addItem(JellyfinApi.Item item, int occurrence) {
        // Position disambiguates duplicate IDs without changing Jellyfin's grouping result.
        entries.add(new Entry("item:" + item.type + ":" + item.id + ":" + occurrence, 0, null, item));
    }
    Anchor anchor() {
        int position = layout.findFirstVisibleItemPosition();
        if (position < 0 || position >= entries.size()) return null;
        View child = layout.findViewByPosition(position);
        return child == null ? null : new Anchor(entries.get(position).key, layout.getDecoratedTop(child) - getPaddingTop());
    }
    void restore(Anchor anchor) {
        if (anchor == null) return;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).key.equals(anchor.key)) {
            layout.scrollToPositionWithOffset(i, anchor.offset); return;
        }
    }
    boolean nearEnd() {
        return getHeight() > 0 && layout.findLastVisibleItemPosition() >= entries.size() - columns * 2 - 1;
    }
    private static final class Entry {
        final String key;
        final int kind;
        final View view;
        final JellyfinApi.Item item;
        Entry(String key, int kind, View view, JellyfinApi.Item item) {
            this.key = key; this.kind = kind; this.view = view; this.item = item;
        }
    }
    private final class Holder extends RecyclerView.ViewHolder {
        final LibraryUi.PosterCard poster;
        Holder(int kind) {
            this(kind == 0 ? style.new PosterCard(cardWidth) : null);
        }
        Holder(LibraryUi.PosterCard poster) {
            super(poster == null ? new FrameLayout(style.context()) : poster.view);
            this.poster = poster;
            itemView.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
        }
    }
    private final class MediaAdapter extends RecyclerView.Adapter<Holder> {
        @Override public int getItemCount() { return entries.size(); }
        @Override public int getItemViewType(int position) { return entries.get(position).kind; }
        @Override public Holder onCreateViewHolder(ViewGroup parent, int kind) { return new Holder(kind); }
        @Override public void onBindViewHolder(Holder holder, int position) {
            Entry entry = entries.get(position);
            if (holder.poster != null) holder.poster.bind(entry.item, () -> open.open(entry.item));
            else {
                FrameLayout box = (FrameLayout) holder.itemView;
                box.removeAllViews();
                if (entry.view.getParent() instanceof ViewGroup) ((ViewGroup) entry.view.getParent()).removeView(entry.view);
                box.addView(entry.view, new FrameLayout.LayoutParams(-1, -2));
            }
        }
        @Override public void onViewRecycled(Holder holder) {
            if (holder.poster != null) holder.poster.unbind();
            else ((FrameLayout) holder.itemView).removeAllViews();
        }
    }
}
