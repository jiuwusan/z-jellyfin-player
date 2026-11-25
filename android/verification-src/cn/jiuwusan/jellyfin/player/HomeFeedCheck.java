package cn.jiuwusan.jellyfin.player;
import java.util.*;
public final class HomeFeedCheck {
    static final class Source implements HomeFeed.Source<String> {
        List<String> resume=Collections.emptyList(), next=Collections.emptyList(), latest=Collections.emptyList();
        String failure=""; int resumeCalls,nextCalls,latestCalls;
        public List<String> resume() throws Exception {resumeCalls++;if(failure.equals("resume"))throw new Exception(failure);return resume;}
        public List<String> next() throws Exception {nextCalls++;if(failure.equals("next"))throw new Exception(failure);return next;}
        public List<String> latest() throws Exception {latestCalls++;if(failure.equals("latest"))throw new Exception(failure);return latest;}
    }
    public static void main(String[] args) throws Exception {
        Source source=new Source(); source.next=Arrays.asList("next-episode");source.latest=Arrays.asList("movie");
        HomeFeed.Result<String> result=HomeFeed.load(source);
        check(!result.resume&&result.items.equals(source.next),"Empty resume must prefer Next Up over latest");
        source.resume=Arrays.asList("resumable");result=HomeFeed.load(source);
        check(result.resume&&result.items.equals(source.resume),"Resume must have first priority");
        check(source.nextCalls==2&&source.latestCalls==2,"Shared snapshot must fetch all sections for standard mode");
        source.resume=Collections.emptyList();source.next=Collections.emptyList();result=HomeFeed.load(source);
        check(!result.resume&&result.items.equals(source.latest),"Latest must be the third fallback");
        source.latest=Collections.emptyList();check(HomeFeed.load(source).items.isEmpty(),"Only all-empty is empty state");
        for(String section:Arrays.asList("resume","next","latest")){
            source.failure=section;
            try{HomeFeed.load(source);throw new AssertionError("Network failure must propagate: "+section);}
            catch(Exception expected){check(section.equals(expected.getMessage()),"Failure source changed");}
        }
        System.out.println("HomeFeedCheck passed: shared snapshot / resume-next-latest / all empty / each failure propagates");
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
