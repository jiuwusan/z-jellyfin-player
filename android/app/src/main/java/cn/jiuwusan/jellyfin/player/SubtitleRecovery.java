package cn.jiuwusan.jellyfin.player;

import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.media3.datasource.HttpDataSource;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Per-title recovery budget. Only actual subtitle errors advance it, never absence of cues. */
final class SubtitleRecovery {
    enum Action { REFRESH, BURN, DISABLE }
    private final Set<Integer> refreshed = new HashSet<>(), burned = new HashSet<>();

    Action next(int index, IOException error) {
        int status = responseCode(error);
        if (index < 0 || status == 401 || status == 403 || status == 407 || burned.contains(index)) return Action.DISABLE;
        if ((status == 404 || status == 410 || error instanceof FileNotFoundException) && refreshed.add(index)) return Action.REFRESH;
        return burned.add(index) ? Action.BURN : Action.DISABLE;
    }
    void reset(int index) { refreshed.remove(index); burned.remove(index); }
    void clear() { refreshed.clear(); burned.clear(); }

    static long retryDelay(IOException error, int count) {
        int status = responseCode(error);
        boolean transientError = status < 0 || status == 408 || status == 429 || status >= 500 && status <= 599;
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof ParserException || cause instanceof FileNotFoundException
                    || cause instanceof IllegalArgumentException || cause instanceof UnsupportedOperationException) transientError = false;
        return transientError && count <= 1 ? 1000 : C.TIME_UNSET;
    }
    private static int responseCode(IOException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof HttpDataSource.InvalidResponseCodeException) return ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
            else if (cause instanceof JellyfinApi.HttpFailure) return ((JellyfinApi.HttpFailure) cause).status;
        return -1;
    }
}
