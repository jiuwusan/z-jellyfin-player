package cn.jiuwusan.jellyfin.player;

import java.util.List;

/** Selects a single home section; transport errors must not be treated as empty history. */
final class HomeFeed {
    interface Source<T> {
        List<T> resume() throws Exception;
        List<T> latest() throws Exception;
    }
    static final class Result<T> {
        final boolean resume;
        final List<T> items;
        Result(boolean resume, List<T> items) { this.resume = resume; this.items = items; }
    }
    static <T> Result<T> load(Source<T> source) throws Exception {
        List<T> items = source.resume();
        return items.isEmpty() ? new Result<>(false, source.latest()) : new Result<>(true, items);
    }
}
