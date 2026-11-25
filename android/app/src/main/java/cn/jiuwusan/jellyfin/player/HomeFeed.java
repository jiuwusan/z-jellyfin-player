package cn.jiuwusan.jellyfin.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One complete home snapshot; failed queries never masquerade as empty history. */
final class HomeFeed {
    enum Section { RESUME, NEXT, LATEST }
    interface Source<T> {
        List<T> resume() throws Exception;
        List<T> next() throws Exception;
        List<T> latest() throws Exception;
    }
    static final class Result<T> {
        final List<T> resumeItems, nextItems, latestItems;
        final Section primary;
        // The primary elder feed is also useful to existing browse/return checks.
        final boolean resume;
        final List<T> items;
        Result(List<T> resumeItems,List<T> nextItems,List<T> latestItems) {
            this.resumeItems=copy(resumeItems);this.nextItems=copy(nextItems);this.latestItems=copy(latestItems);
            primary=!this.resumeItems.isEmpty()?Section.RESUME:!this.nextItems.isEmpty()?Section.NEXT:Section.LATEST;
            resume=primary==Section.RESUME;items=list(primary);
        }
        List<T> list(Section section) {
            switch(section){case RESUME:return resumeItems;case NEXT:return nextItems;default:return latestItems;}
        }
        private static <T> List<T> copy(List<T> items){return Collections.unmodifiableList(new ArrayList<>(items));}
    }
    static <T> Result<T> load(Source<T> source) throws Exception {
        return new Result<>(source.resume(),source.next(),source.latest());
    }
}
