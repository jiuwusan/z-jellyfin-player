package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;

final class SubtitleCacheCheck {
    private final Instrumentation test;
    SubtitleCacheCheck(Instrumentation test) { this.test = test; }
    private void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private Object call(Object cache, String name, Class<?>[] types, Object... args) throws Exception {
        java.lang.reflect.Method m = cache.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(cache, args);
    }
    private long generation(Object cache) throws Exception { return (long) call(cache, "generation", new Class<?>[]{}); }
    private void put(Object cache, String key, int bytes, long now) throws Exception {
        call(cache, "put", new Class<?>[]{String.class, byte[].class, long.class, long.class}, key, new byte[bytes], now, generation(cache));
    }
    private byte[] get(Object cache, String key, long now) throws Exception {
        return (byte[]) call(cache, "get", new Class<?>[]{String.class, long.class}, key, now);
    }
    void run() {
        Bundle result = new Bundle();
        try {
            Class<?> type = Class.forName("cn.jiuwusan.jellyfin.player.SubtitleCache");
            java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(int.class, int.class, long.class); constructor.setAccessible(true);
            Object cache = constructor.newInstance(6, 2, 10L);
            put(cache, "a", 3, 0); check(get(cache, "a", 9).length == 3, "Cache hit missing");
            check(get(cache, "a", 10) == null, "Cache TTL was extended by reads");
            put(cache, "a", 3, 20); put(cache, "b", 3, 20); get(cache, "a", 21); put(cache, "c", 3, 21);
            check(get(cache, "b", 21) == null && get(cache, "a", 21) != null, "LRU eviction incorrect");
            put(cache, "large", 7, 22); check(get(cache, "large", 22) == null, "Oversized file retained");
            call(cache, "clear", new Class<?>[]{}); put(cache, "a", 4, 30); put(cache, "b", 4, 30);
            check(get(cache, "a", 30) == null && get(cache, "b", 30) != null, "Byte budget not enforced");
            long old = generation(cache); call(cache, "remove", new Class<?>[]{String.class}, "b");
            call(cache, "put", new Class<?>[]{String.class, byte[].class, long.class, long.class}, "b", new byte[2], 31L, old);
            check(get(cache, "b", 31) == null, "Invalidated in-flight result repopulated cache");
            put(cache, "a", 2, 32); old = generation(cache); call(cache, "clear", new Class<?>[]{});
            call(cache, "put", new Class<?>[]{String.class, byte[].class, long.class, long.class}, "a", new byte[2], 33L, old);
            check(get(cache, "a", 33) == null, "Previous login repopulated cache");
            Object other = constructor.newInstance(6, 2, 10L); put(cache, "a", 2, 34);
            check(get(other, "a", 34) == null, "Cache leaked between connections");
            downloadReuse();
            try (SubtitleHandlingCheck.Fixture fixture = new SubtitleHandlingCheck.Fixture()) {
                JellyfinApi api = new JellyfinApi(new ConnectionSettings("http://fixture.invalid", "127.0.0.1", fixture.server.getLocalPort(), "", "", "fixture", "fixture", 1920, 4_000_000), "cache-check");
                put(api.subtitleCache, "a", 2, 0); long beforeLogin = api.subtitleCache.generation();
                api.login();
                check(api.subtitleCache.get("a", 1) == null && api.subtitleCache.generation() != beforeLogin, "Login did not clear prior subtitle cache");
            }
            result.putString("result", "ALL_PASS: TTL / LRU / byte budget / oversized / invalidation / generation / connection isolation");
            test.finish(Activity.RESULT_OK, result);
        } catch (Throwable e) { result.putString("failure", android.util.Log.getStackTraceString(e)); test.finish(Activity.RESULT_CANCELED, result); }
    }

    private void downloadReuse() throws Exception {
        android.net.Uri uri = android.net.Uri.parse("http://fixture.invalid/subtitle.srt");
        final int[] opens = {0};
        final boolean[] noStore = {false}, fail = {false};
        androidx.media3.datasource.DataSource.Factory upstream = () -> new androidx.media3.datasource.DataSource() {
            int offset;
            @Override public void addTransferListener(androidx.media3.datasource.TransferListener listener) { }
            @Override public long open(androidx.media3.datasource.DataSpec spec) throws java.io.IOException {
                opens[0]++; offset = 0; if (fail[0]) throw new java.io.IOException("fixture offline"); return 3;
            }
            @Override public int read(byte[] b, int start, int length) {
                if (offset == 3) return -1;
                int n = Math.min(length, 3 - offset); for (int i = 0; i < n; i++) b[start + i] = (byte) ('a' + offset++); return n;
            }
            @Override public java.util.Map<String, java.util.List<String>> getResponseHeaders() {
                return noStore[0] ? java.util.Collections.singletonMap("Cache-Control", java.util.Collections.singletonList("private, no-store")) : java.util.Collections.emptyMap();
            }
            @Override public android.net.Uri getUri() { return uri; }
            @Override public void close() { }
        };
        SubtitleCache cache = new SubtitleCache();
        androidx.media3.datasource.DataSpec spec = new androidx.media3.datasource.DataSpec(uri);
        SubtitleDataSource first = new SubtitleDataSource(upstream.createDataSource(), uri, cache);
        first.open(spec); byte[] bytes = new byte[4]; check(first.read(bytes, 0, 1) == 1 && bytes[0] == 'a', "Initial read failed"); first.close();
        SubtitleDataSource second = new SubtitleDataSource(upstream.createDataSource(), uri, cache);
        second.open(spec.subrange(1)); check(second.read(bytes, 0, 4) == 2 && bytes[0] == 'b' && bytes[1] == 'c', "Cached seek shared old read position"); second.close();
        check(opens[0] == 1, "Repeated subtitle was downloaded again");
        cache.clear(); noStore[0] = true;
        for (int i = 0; i < 2; i++) { SubtitleDataSource data = new SubtitleDataSource(upstream.createDataSource(), uri, cache); data.open(spec); data.close(); }
        check(opens[0] == 3 && cache.get(uri.toString(), android.os.SystemClock.elapsedRealtime()) == null, "no-store response was cached");
        noStore[0] = false; fail[0] = true;
        SubtitleDataSource broken = new SubtitleDataSource(upstream.createDataSource(), uri, cache);
        boolean failed = false;
        try { broken.open(spec); } catch (java.io.IOException expected) { failed = true; } finally { broken.close(); }
        check(failed && opens[0] == 5 && cache.get(uri.toString(), android.os.SystemClock.elapsedRealtime()) == null, "Failed download entered cache or exceeded retry budget");
    }
}
