package cn.jiuwusan.jellyfin.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A complete, server-ordered snapshot of a collection's direct movie members. */
final class CollectionPlayback {
    final List<JellyfinApi.Item> items;

    CollectionPlayback(List<JellyfinApi.Item> members) {
        List<JellyfinApi.Item> movies = new ArrayList<>();
        for (JellyfinApi.Item item : members) if ("Movie".equals(item.type)) movies.add(item);
        items = Collections.unmodifiableList(movies);
    }

    static CollectionPlayback load(JellyfinApi api, JellyfinApi.Item collection) throws Exception {
        if (!collection.collection) throw new IllegalArgumentException("不是电影合集");
        List<JellyfinApi.Item> members = new ArrayList<>();
        int offset = 0;
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
            JellyfinApi.LibraryItemsPage page = api.libraryItems(collection, false, offset);
            members.addAll(page.items);
            offset += page.items.size();
            if (!page.more) break;
            if (page.items.isEmpty()) throw new java.io.IOException("合集成员分页未完成");
        }
        return new CollectionPlayback(members);
    }

    JellyfinApi.Item start() {
        for (JellyfinApi.Item item : items) if (!item.played) return item;
        return items.isEmpty() ? null : items.get(0);
    }

    boolean contains(JellyfinApi.Item item) { return index(item) >= 0; }
    JellyfinApi.Item previous(JellyfinApi.Item item) {
        int index = index(item);
        return index > 0 ? items.get(index - 1) : null;
    }
    JellyfinApi.Item next(JellyfinApi.Item item) {
        int index = index(item);
        return index >= 0 && index + 1 < items.size() ? items.get(index + 1) : null;
    }
    private int index(JellyfinApi.Item item) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).id.equals(item.id)) return i;
        return -1;
    }
}
