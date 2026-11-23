package cn.jiuwusan.jellyfin.player;
import java.util.*;
public final class HomeFeedCheck {
    static final class Source implements HomeFeed.Source<String> {
        List<String> resume = Collections.emptyList(), latest = Collections.emptyList();
        int latestCalls; boolean fail, failLatest;
        public List<String> resume() throws Exception { if (fail) throw new Exception("offline"); return resume; }
        public List<String> latest() throws Exception { latestCalls++; if (failLatest) throw new Exception("latest offline"); return latest; }
    }
    public static void main(String[] args) throws Exception {
        Source source = new Source();
        source.resume = Arrays.asList("episode"); source.latest = Arrays.asList("movie");
        HomeFeed.Result<String> result = HomeFeed.load(source);
        check(result.resume && result.items.equals(source.resume) && source.latestCalls == 0);
        source.resume = Collections.emptyList(); result = HomeFeed.load(source);
        check(!result.resume && result.items.equals(source.latest) && source.latestCalls == 1);
        source.latest = Collections.emptyList(); result = HomeFeed.load(source);
        check(!result.resume && result.items.isEmpty());
        source.fail = true; int calls = source.latestCalls;
        try { HomeFeed.load(source); throw new AssertionError("Failure must propagate"); }
        catch (Exception expected) { check("offline".equals(expected.getMessage()) && calls == source.latestCalls); }
        source.fail = false; source.failLatest = true;
        try { HomeFeed.load(source); throw new AssertionError("Latest failure must propagate"); }
        catch (Exception expected) { check("latest offline".equals(expected.getMessage())); }
        System.out.println("HomeFeedCheck passed: resume / fallback / empty / resume error / latest error");
    }
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
}
