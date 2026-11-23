package cn.jiuwusan.jellyfin.player;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Per-login memory only. Returned bytes are immutable to callers; never expose them to UI or disk. */
final class SubtitleCache {
    private final int maxBytes, maxEntries;
    private final long ttlMs;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(8, .75f, true);
    private int bytes;
    private long generation;
    SubtitleCache() { this(24 * 1024 * 1024, 8, 10 * 60_000L); }
    SubtitleCache(int maxBytes, int maxEntries, long ttlMs) {
        this.maxBytes = maxBytes; this.maxEntries = maxEntries; this.ttlMs = ttlMs;
    }
    synchronized long generation() { return generation; }
    synchronized byte[] get(String uri, long now) {
        Entry entry = entries.get(uri);
        if (entry == null) return null;
        if (now - entry.created >= ttlMs || now < entry.created) { removeEntry(uri); return null; }
        return entry.data;
    }
    synchronized void put(String uri, byte[] data, long now, long expectedGeneration) {
        if (generation != expectedGeneration || data.length == 0 || data.length > maxBytes) return;
        removeEntry(uri);
        entries.put(uri, new Entry(data, now)); bytes += data.length;
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while ((bytes > maxBytes || entries.size() > maxEntries) && iterator.hasNext()) {
            bytes -= iterator.next().getValue().data.length; iterator.remove();
        }
    }
    synchronized void remove(String uri) { generation++; removeEntry(uri); }
    synchronized void clear() { generation++; entries.clear(); bytes = 0; }
    private void removeEntry(String uri) { Entry removed = entries.remove(uri); if (removed != null) bytes -= removed.data.length; }
    private static final class Entry {
        final byte[] data; final long created;
        Entry(byte[] data, long created) { this.data = data; this.created = created; }
    }
}
