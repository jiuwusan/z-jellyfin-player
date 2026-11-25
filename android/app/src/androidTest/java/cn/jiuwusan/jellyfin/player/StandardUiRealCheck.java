package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.TextView;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.recyclerview.widget.GridLayoutManager;
import java.io.File;
import java.io.FileOutputStream;
import java.net.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Authorized Honor check, using only the existing managed HTTP proxy and account. */
final class StandardUiRealCheck {
    private final Instrumentation test;
    private Activity activity;
    StandardUiRealCheck(Instrumentation test) { this.test=test; }
    private void check(boolean ok,String why) { if(!ok) throw new AssertionError(why); }
    private Object field(Object object,String name) { return RecycledListDriver.field(object,name); }
    private void ui(Runnable work) {
        AtomicReference<Throwable> error=new AtomicReference<>();
        test.runOnMainSync(() -> { try { work.run(); } catch(Throwable e) { error.set(e); } });
        if(error.get()!=null) throw new AssertionError(error.get());
    }
    private void invoke(String name,Class<?>[] types,Object... args) {
        try { java.lang.reflect.Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(activity,args); }
        catch(Exception e) { throw new AssertionError(e); }
    }
    private interface Ready { boolean get(); }
    private void waitFor(Ready condition,String why) {
        boolean[] ready={false};
        for(int i=0;i<600&&!ready[0];i++){ui(() -> ready[0]=condition.get());SystemClock.sleep(100);}
        check(ready[0],why);
    }
    private View root(){return activity.findViewById(android.R.id.content);}
    private View text(View view,String value){
        if(view instanceof TextView && value.contentEquals(((TextView)view).getText()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=text(((ViewGroup)view).getChildAt(i),value);if(found!=null)return found;}
        return null;
    }
    private void mode(boolean large){
        invoke("settingsPage",new Class<?>[]{String.class},"");
        View tab=text(root(),"播放");check(tab!=null,"Playback tab missing");tab.performClick();
        Switch flag=activity.findViewById(R.id.large_ui_switch);check(flag!=null,"Display switch missing");flag.setChecked(large);
    }
    private void shot(String name)throws Exception{
        test.waitForIdleSync();SystemClock.sleep(400);
        File folder=new File(activity.getExternalFilesDir(null),"standard-ui");check(folder.isDirectory()||folder.mkdirs(),"Screenshot directory unavailable");
        Bitmap bitmap=test.getUiAutomation().takeScreenshot();check(bitmap!=null,"Screenshot unavailable");
        try(FileOutputStream stream=new FileOutputStream(new File(folder,name+".png"))){check(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream),"Screenshot write failed");}
        bitmap.recycle();
    }
    private RecycledMediaGrid grid(){return RecycledListDriver.find(root());}
    private HomeRail homeRail(View view,HomeFeed.Section section){
        if(view instanceof HomeRail&&((HomeRail)view).section==section)return (HomeRail)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){HomeRail rail=homeRail(((ViewGroup)view).getChildAt(i),section);if(rail!=null)return rail;}
        return null;
    }
    void screenshots(){
        Bundle result=new Bundle();
        try {
            activity=test.startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(test.getTargetContext().getPackageName(),MainActivity.class.getName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            waitFor(() -> field(activity,"api")!=null&&activity.findViewById(R.id.home_refresh)!=null,"Home not ready");
            check(!DisplayStyle.load(test.getTargetContext()).large,"Standard mode required");
            ui(() -> invoke("librariesPage",new Class<?>[]{}));
            waitFor(() -> grid()!=null&&field(activity,"savedCatalog")!=null&&Boolean.TRUE.equals(field(field(field(activity,"savedCatalog"),"selected"),"loaded")),"Library not ready");
            ui(() -> check(text(root(),"按添加时间")==null,"Sort caption still visible"));
            shot("library");
            ui(() -> {invoke("settingsPage",new Class<?>[]{String.class},"");text(root(),"订阅").performClick();});
            shot("subscription");
            ui(() -> invoke("homePage",new Class<?>[]{}));
            result.putString("result","ALL_PASS: library without sort caption / subscription screenshot uses unchanged visible settings");
            test.finish(Activity.RESULT_OK,result);
        }catch(Throwable e){result.putString("failure",e instanceof AssertionError&&e.getCause()==null?e.getMessage():e.getClass().getSimpleName());test.finish(Activity.RESULT_CANCELED,result);}
    }
    private void assertAnchorVisible(String key){
        RecycledMediaGrid list=grid();List<?> entries=(List<?>)field(list,"entries");
        for(int i=0;i<entries.size();i++)if(key.equals(field(entries.get(i),"key"))){
            View item=list.getLayoutManager().findViewByPosition(i);
            check(item!=null&&item.getGlobalVisibleRect(new Rect()),"Display switch lost the visible library item");return;
        }
        throw new AssertionError("Anchored library item disappeared");
    }
    void run(){
        Bundle out=new Bundle();
        try{
            activity=test.startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(test.getTargetContext().getPackageName(),MainActivity.class.getName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            waitFor(() -> field(activity,"api")!=null && ((BrowseMemory)field(activity,"browseMemory")).get("home")!=null && activity.findViewById(R.id.home_refresh)!=null,"Managed account home unavailable");
            JellyfinApi api=(JellyfinApi)field(activity,"api");ConnectionSettings managed=(ConnectionSettings)field(activity,"settings");
            SubscriptionSettings saved=((SubscriptionController)field(activity,"subscriptionController")).state();
            check(managed.hasProxy()&&api.client().proxy()!=null&&api.client().proxy().type()==Proxy.Type.HTTP,"Saved subscription is not using an HTTP proxy");
            check(managed.resolution==1920&&managed.bitrate==4_000_000,"Saved quality differs from required probe ceiling");
            AtomicInteger requests=new AtomicInteger(),images=new AtomicInteger(),hls=new AtomicInteger(),segments=new AtomicInteger(),listRequests=new AtomicInteger(),nextRequests=new AtomicInteger();
            java.lang.reflect.Field http=JellyfinApi.class.getDeclaredField("http");http.setAccessible(true);
            http.set(api,api.client().newBuilder().addNetworkInterceptor(chain -> {
                if(chain.connection()==null||chain.connection().route().proxy().type()!=Proxy.Type.HTTP)throw new java.io.IOException("Unexpected non-proxy route");
                String path=chain.request().url().encodedPath();
                if(path.contains("/Images/"))images.incrementAndGet();else if(path.endsWith(".m3u8"))hls.incrementAndGet();
                else if(path.endsWith(".ts")||path.endsWith(".m4s")||path.endsWith(".mp4"))segments.incrementAndGet();else requests.incrementAndGet();
                if(path.endsWith("/Items"))listRequests.incrementAndGet();
                if(path.endsWith("/Shows/NextUp")) {
                    if(!"false".equals(chain.request().url().queryParameter("enableResumable")))throw new java.io.IOException("NextUp resumable policy changed");
                    nextRequests.incrementAndGet();
                }
                return chain.proceed(chain.request());
            }).build());
            BrowseMemory memory=(BrowseMemory)field(activity,"browseMemory");
            ui(() -> { memory.remove("home");invoke("homePage",new Class<?>[]{}); });
            waitFor(() -> memory.get("home")!=null,"Fresh three-section home did not load");
            HomeFeed.Result<JellyfinApi.Item> home=memory.get("home");
            check(nextRequests.get()>0,"Next Up did not use the managed proxy client");
            out.putInt("resumeItems",home.resumeItems.size());out.putInt("nextItems",home.nextItems.size());out.putInt("latestItems",home.latestItems.size());
            ui(() -> { mode(true);invoke("homePage",new Class<?>[]{}); });
            test.waitForIdleSync();
            ui(() -> { ((android.widget.ScrollView)field(activity,"rememberedScroll")).scrollTo(0,0); mode(false);check(field(activity,"api")==api,"Display switch replaced API connection"); });
            waitFor(() -> activity.findViewById(R.id.large_ui_switch)!=null && !((Switch)activity.findViewById(R.id.large_ui_switch)).isChecked(),"Standard settings not shown");
            shot("settings");
            ui(() -> invoke("homePage",new Class<?>[]{}));test.waitForIdleSync();SystemClock.sleep(250);
            ui(() -> { View title=text(root(),"继续观看");if(title==null)title=text(root(),"最近添加");check(title!=null&&title.getGlobalVisibleRect(new Rect()),"Density switch at page top hid the home heading"); });
            shot("home");
            ui(() -> ((android.widget.ScrollView)field(activity,"rememberedScroll")).fullScroll(View.FOCUS_DOWN));
            shot("home-bottom");
            ui(() -> ((android.widget.ScrollView)field(activity,"rememberedScroll")).scrollTo(0,0));
            int latestEpisode=-1;
            for(int i=0;i<home.latestItems.size();i++)if("Episode".equals(home.latestItems.get(i).type)&&!home.latestItems.get(i).seriesId.isEmpty()){latestEpisode=i;break;}
            check(latestEpisode>=0,"Latest episode routing fixture unavailable");
            int episodeIndex=latestEpisode;JellyfinApi.Item latestItem=home.latestItems.get(episodeIndex);
            ui(() -> {HomeRail rail=homeRail(root(),HomeFeed.Section.LATEST);check(rail!=null,"Latest rail missing");
                ((androidx.recyclerview.widget.LinearLayoutManager)rail.getLayoutManager()).scrollToPositionWithOffset(episodeIndex,0);});
            test.waitForIdleSync();SystemClock.sleep(300);
            HomeRail.Position[] latestAnchor={null};
            ui(() -> {
                HomeRail rail=homeRail(root(),HomeFeed.Section.LATEST);View holder=rail.getLayoutManager().findViewByPosition(episodeIndex);
                check(holder!=null,"Latest episode card was not bound");latestAnchor[0]=memory.get("homeRail:LATEST");
                ((ViewGroup)holder).getChildAt(0).performClick();
            });
            waitFor(() -> memory.get("detail:"+latestItem.seriesId)!=null&&text(root(),"媒体详情")!=null,"Latest episode did not open its series detail");
            ui(() -> {check(field(activity,"player")==null,"Latest episode unexpectedly played");activity.onBackPressed();});
            waitFor(() -> homeRail(root(),HomeFeed.Section.LATEST)!=null,"Detail did not return to home");
            test.waitForIdleSync();SystemClock.sleep(300);
            ui(() -> {
                HomeRail.Position restored=memory.get("homeRail:LATEST");check(restored!=null&&latestAnchor[0]!=null&&restored.id.equals(latestAnchor[0].id),"Latest detail return lost horizontal position");
            });
            out.putString("latestEpisodeRoute","series-detail / no playback / horizontal anchor retained");
            ui(() -> invoke("librariesPage",new Class<?>[]{}));
            waitFor(() -> grid()!=null && field(activity,"savedCatalog")!=null && Boolean.TRUE.equals(field(field(field(activity,"savedCatalog"),"selected"),"loaded")),"Library not loaded");
            ui(() -> check(((GridLayoutManager)grid().getLayoutManager()).getSpanCount()==3,"Standard library is not three columns"));
            shot("library");
            ui(() -> ((GridLayoutManager)grid().getLayoutManager()).scrollToPositionWithOffset(12,0));test.waitForIdleSync();SystemClock.sleep(250);
            String[] anchor={null};ui(() -> anchor[0]=grid().anchor().key);
            int beforeLists=listRequests.get();
            ui(() -> {mode(true);invoke("librariesPage",new Class<?>[]{});});test.waitForIdleSync();SystemClock.sleep(300);
            ui(() -> {check(((GridLayoutManager)grid().getLayoutManager()).getSpanCount()==2,"Large library is not two columns");assertAnchorVisible(anchor[0]);});
            ui(() -> {mode(false);invoke("librariesPage",new Class<?>[]{});});test.waitForIdleSync();SystemClock.sleep(300);
            ui(() -> {assertAnchorVisible(anchor[0]);check(field(activity,"api")==api,"Mode round-trip replaced API");});
            check(listRequests.get()==beforeLists,"Mode round-trip downloaded library data again");
            List<JellyfinApi.Item> results=api.search("魅影神捕");JellyfinApi.Item series=null;
            for(JellyfinApi.Item item:results)if(item.series&&item.title.equals("魅影神捕")){series=item;break;}
            check(series!=null,"Playback fixture series unavailable");
            JellyfinApi.Item selected=series;
            ui(() -> invoke("detailsPage",new Class<?>[]{JellyfinApi.Item.class,Runnable.class},selected,(Runnable)() -> invoke("librariesPage",new Class<?>[]{})));
            waitFor(() -> root().findViewById(R.id.loading_placeholder)==null && text(root(),"魅影神捕")!=null,"Detail not ready");
            shot("detail");
            List<JellyfinApi.Item> episodes=api.episodes(series,null);check(!episodes.isEmpty(),"No episode available");
            JellyfinApi.Item episode=api.details(episodes.get(0).id);check(api.artwork(episode,true,"",false)!=null,"Proxy artwork failed");
            AtomicReference<String> decoder=new AtomicReference<>();AtomicReference<ExoPlayer> observed=new AtomicReference<>();
            long start=Math.min(Math.max(60_000,episode.resumeMs),Math.max(0,episode.durationMs-120_000));
            ui(() -> invoke("playbackPage",new Class<?>[]{JellyfinApi.Item.class,Long.class,Runnable.class},episode,Long.valueOf(start),(Runnable)() -> invoke("homePage",new Class<?>[]{})));
            waitFor(() -> {
                ExoPlayer player=(ExoPlayer)field(activity,"player");
                if(player!=null&&observed.get()!=player){observed.set(player);player.addAnalyticsListener(new AnalyticsListener(){
                    @Override public void onVideoDecoderInitialized(EventTime time,String name,long at,long duration){decoder.set(name);}
                });}
                return player!=null&&player.isPlaying()&&root().findViewById(R.id.playback_preparation_cover).getVisibility()==View.GONE;
            },"Proxy hardware playback did not render");
            SystemClock.sleep(1200);
            ui(() -> {
                ExoPlayer player=(ExoPlayer)field(activity,"player");Format format=player.getVideoFormat();
                check(format!=null&&"video/avc".equals(format.sampleMimeType)&&format.width<=1920&&format.height<=1080,"Playback quality/codec policy changed");
                out.putString("video",format.sampleMimeType+" "+format.width+"x"+format.height);
                player.pause();
                FullscreenPlaybackView screen=(FullscreenPlaybackView)field(field(activity,"playback"),"screen");
                View transport=(View)field(screen,"transportRow");
                check(Math.abs(transport.getLeft()+transport.getWidth()/2f-screen.getWidth()/2f)<=1&&Math.abs(transport.getTop()+transport.getHeight()/2f-screen.getHeight()/2f)<=1,"Transport is not centered");
            });
            check(decoder.get()!=null&&!decoder.get().startsWith("c2.android.")&&!decoder.get().startsWith("OMX.google."),"Hardware decoder not captured");
            shot("player");
            long[] target={0};
            ui(() -> {ExoPlayer player=(ExoPlayer)field(activity,"player");target[0]=Math.min(player.getCurrentPosition()+15_000,player.getDuration()-60_000);player.seekTo(target[0]);player.play();});
            waitFor(() -> {ExoPlayer player=(ExoPlayer)field(activity,"player");return player.isPlaying()&&player.getCurrentPosition()>=target[0]-1500;},"Seek did not resume");
            ui(() -> activity.onBackPressed());waitFor(() -> activity.findViewById(R.id.home_refresh)!=null&&field(activity,"player")==null,"Playback return failed");
            SubscriptionSettings after=((SubscriptionController)field(activity,"subscriptionController")).state();
            check(after.link.equals(saved.link)&&after.configuration.sameConnection(saved.configuration),"Managed connection changed");
            check(!DisplayStyle.load(test.getTargetContext()).large,"Standard UI preference was not saved");
            check(images.get()>0&&hls.get()>0&&segments.get()>0,"Actual proxy image/HLS/media requests were not captured");
            List<JellyfinApi.Item> movies=api.search("泰坦尼克号");
            JellyfinApi.Item movie=null;
            for(JellyfinApi.Item item:movies)if("Movie".equals(item.type)){movie=item;break;}
            check(movie!=null,"Movie detail screenshot fixture unavailable");
            JellyfinApi.Item selectedMovie=movie;
            ui(() -> invoke("detailsPage",new Class<?>[]{JellyfinApi.Item.class,Runnable.class},selectedMovie,(Runnable)() -> invoke("homePage",new Class<?>[]{})));
            waitFor(() -> root().findViewById(R.id.loading_placeholder)==null&&text(root(),selectedMovie.title)!=null,"Movie detail not ready");
            shot("movie");
            ui(() -> invoke("searchPage",new Class<?>[]{Runnable.class,List.class,int.class},(Runnable)() -> invoke("homePage",new Class<?>[]{}),null,0));
            shot("search-empty");
            ui(() -> {
                try{java.lang.reflect.Field q=MainActivity.class.getDeclaredField("query");q.setAccessible(true);q.set(activity,"魅影神捕");}
                catch(Exception e){throw new AssertionError(e);}
                invoke("searchPage",new Class<?>[]{Runnable.class,List.class,int.class},(Runnable)() -> invoke("homePage",new Class<?>[]{}),results,0);
            });
            shot("search-results");
            ui(() -> {invoke("settingsPage",new Class<?>[]{String.class},"");View tab=text(root(),"订阅");check(tab!=null,"Subscription tab missing");tab.performClick();});
            shot("subscription");
            ui(() -> invoke("homePage",new Class<?>[]{}));
            out.putString("decoder",decoder.get());out.putInt("apiRequests",requests.get());out.putInt("images",images.get());out.putInt("hls",hls.get());out.putInt("segments",segments.get());
            out.putString("result","ALL_PASS: standard UI / mode round-trip keeps library item+data+connection / managed HTTP proxy API+artwork+HLS / 1080p+4Mbps policy / hardware frame / seek+return / subscription unchanged");
            test.finish(Activity.RESULT_OK,out);
        }catch(Throwable e){out.putString("failure",e instanceof AssertionError&&e.getCause()==null?e.getMessage():e.getClass().getSimpleName());test.finish(Activity.RESULT_CANCELED,out);}
    }
}
