package cn.jiuwusan.jellyfin.player;

import java.util.LinkedHashMap;
import java.util.Map;

/** Main-thread session data only. Never stores tokens, connection settings or Views. */
final class BrowseMemory {
    private static final class Entry {
        final Object value;
        final long at;
        Entry(Object value, long at) { this.value = value; this.at = at; }
    }
    private final int limit;
    private final LinkedHashMap<String, Entry> data = new LinkedHashMap<>(16, .75f, true);
    private final LinkedHashMap<String, Integer> positions = new LinkedHashMap<>(16, .75f, true);
    static final class Anchor {
        final String id;
        final boolean large;
        Anchor(String id, boolean large) { this.id=id; this.large=large; }
    }
    private final LinkedHashMap<String, Anchor> anchors = new LinkedHashMap<>(16,.75f,true);
    BrowseMemory(int limit) { this.limit = Math.max(1, limit); }
    @SuppressWarnings("unchecked") <T> T get(String key) {
        Entry entry = data.get(key);
        return entry == null ? null : (T) entry.value;
    }
    void put(String key, Object value, long at) {
        data.put(key, new Entry(value, at));
        trim(data);
    }
    boolean stale(String key, long now, long ttl) {
        Entry entry = data.get(key);
        return entry == null || now - entry.at >= ttl;
    }
    void position(String key, int y) { positions.put(key, Math.max(0, y)); trim(positions); anchors.remove(key); }
    void position(String key, int y, String id, boolean large) {
        position(key,y);
        if(id!=null) { anchors.put(key,new Anchor(id,large)); trim(anchors); }
    }
    Anchor anchor(String key) { return anchors.get(key); }
    void remove(String key) { data.remove(key); }
    int position(String key) { Integer value = positions.get(key); return value == null ? 0 : value; }
    private void trim(Map<?, ?> map) {
        while (map.size() > limit) map.remove(map.keySet().iterator().next());
    }
    void clear() { data.clear(); positions.clear(); anchors.clear(); }
}
