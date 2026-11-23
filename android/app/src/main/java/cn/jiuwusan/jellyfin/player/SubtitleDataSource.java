package cn.jiuwusan.jellyfin.player;

import android.net.Uri;
import android.os.SystemClock;
import androidx.media3.common.C;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Downloads the selected standalone text before extraction, so a retry cannot reset its timeline. */
final class SubtitleDataSource implements DataSource {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final DataSource upstream;
    private final Uri subtitle;
    private final SubtitleCache cache;
    private ByteArrayDataSource buffered;
    private Map<String, List<String>> headers = Collections.emptyMap();
    SubtitleDataSource(DataSource upstream, Uri subtitle) { this(upstream, subtitle, null); }
    SubtitleDataSource(DataSource upstream, Uri subtitle, SubtitleCache cache) {
        this.upstream = upstream; this.subtitle = subtitle; this.cache = cache;
    }
    @Override public void addTransferListener(TransferListener listener) { upstream.addTransferListener(listener); }
    @Override public long open(DataSpec spec) throws IOException {
        if (subtitle == null || !subtitle.equals(spec.uri)) return upstream.open(spec);
        String key = spec.uri.toString();
        long generation = cache == null ? -1 : cache.generation();
        byte[] cached = cache == null ? null : cache.get(key, SystemClock.elapsedRealtime());
        if (cached != null) { buffered = new ByteArrayDataSource(cached); return buffered.open(spec); }
        for (int attempt = 1; ; attempt++) {
            try {
                upstream.open(spec.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET).build());
                headers = upstream.getResponseHeaders();
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] chunk = new byte[16 * 1024];
                int count;
                while ((count = upstream.read(chunk, 0, chunk.length)) != C.RESULT_END_OF_INPUT) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                    if (output.size() + count > MAX_BYTES)
                        throw new IOException(new UnsupportedOperationException("Standalone subtitle exceeds memory budget"));
                    output.write(chunk, 0, count);
                }
                byte[] complete = output.toByteArray();
                boolean noStore = false;
                for (Map.Entry<String, List<String>> header : headers.entrySet())
                    if ("Cache-Control".equalsIgnoreCase(header.getKey())) for (String value : header.getValue())
                        if (value.toLowerCase(java.util.Locale.ROOT).contains("no-store")) noStore = true;
                if (cache != null && !noStore) cache.put(key, complete, SystemClock.elapsedRealtime(), generation);
                buffered = new ByteArrayDataSource(complete);
                return buffered.open(spec);
            } catch (IOException error) {
                long delay = SubtitleRecovery.retryDelay(error, attempt);
                if (error instanceof InterruptedIOException && Thread.currentThread().isInterrupted() || delay == C.TIME_UNSET) throw error;
                try { Thread.sleep(delay); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException();
                }
            } finally { upstream.close(); }
        }
    }
    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        return buffered == null ? upstream.read(bytes, offset, length) : buffered.read(bytes, offset, length);
    }
    @Override public Uri getUri() { return buffered == null ? upstream.getUri() : buffered.getUri(); }
    @Override public Map<String, List<String>> getResponseHeaders() { return buffered == null ? upstream.getResponseHeaders() : headers; }
    @Override public void close() throws IOException {
        if (buffered != null) { buffered.close(); buffered = null; }
        upstream.close();
    }
}
