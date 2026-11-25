package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Controlled home layout, action, gesture, recycling and restoration checks; no saved account changes. */
final class HomeModesCheck {
    private final Instrumentation test;
    private Activity activity;private LibraryUi style;private LinearLayout body;private ScrollView scroll;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout refresh;private int refreshes;
    private final BrowseMemory memory=new BrowseMemory(40);
    private int detailClicks,playClicks,libraryClicks;private String selected="";
    HomeModesCheck(Instrumentation test){this.test=test;}
    private void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private void ui(Runnable action){AtomicReference<Throwable> e=new AtomicReference<>();test.runOnMainSync(()->{try{action.run();}catch(Throwable t){e.set(t);}});if(e.get()!=null)throw new AssertionError(e.get());}
    private void settle(){test.waitForIdleSync();SystemClock.sleep(350);test.waitForIdleSync();}
    private JellyfinApi.Item item(String id,String type,boolean episode)throws Exception{
        JSONObject entry=new JSONObject().put("Id",id).put("Name",episode?"单集介绍不应展示":"示例影片 "+id).put("Type",type).put("ProductionYear",2026).put("RunTimeTicks",3600_000L*10000);
        if(episode)entry.put("SeriesName","示例剧集").put("SeriesId","series").put("ParentIndexNumber",1).put("IndexNumber",2);
        return new JellyfinApi.Item(entry);
    }
    private List<View> all(View root,Class<?> type){List<View> found=new ArrayList<>();if(type.isInstance(root))found.add(root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)found.addAll(all(((ViewGroup)root).getChildAt(i),type));return found;}
    private List<HomeRail> rails(){List<HomeRail> result=new ArrayList<>();for(View v:all(body,HomeRail.class))result.add((HomeRail)v);return result;}
    private boolean label(String text){for(View v:all(body,TextView.class))if(text.contentEquals(((TextView)v).getText()))return true;return false;}
    private View play(View root){if(root.getContentDescription()!=null&&(root.getContentDescription().toString().startsWith("播放 ")||root.getContentDescription().toString().startsWith("继续观看 ")))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View p=play(((ViewGroup)root).getChildAt(i));if(p!=null)return p;}return null;}
    private View card(HomeRail rail){View holder=rail.getLayoutManager().findViewByPosition(((LinearLayoutManager)rail.getLayoutManager()).findFirstVisibleItemPosition());return ((ViewGroup)holder).getChildAt(0);}
    private void render(boolean elder,HomeFeed.Result<JellyfinApi.Item> data){style.setLargeUi(elder);HomeContent.render(body,style,data,activity.getResources().getDisplayMetrics().widthPixels-style.dp(40),memory,i->{detailClicks++;selected=i.id;},i->{playClicks++;selected=i.id;},()->libraryClicks++);}
    private void drag(HomeRail rail,float from,float to,float y){
        int[] railAt=new int[2],rootAt=new int[2];rail.getLocationOnScreen(railAt);refresh.getLocationOnScreen(rootAt);
        long at=SystemClock.uptimeMillis();for(int i=0;i<=8;i++){int action=i==0?MotionEvent.ACTION_DOWN:i==8?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE;
            MotionEvent event=MotionEvent.obtain(at,at+i*35,action,railAt[0]-rootAt[0]+from+(to-from)*i/8,railAt[1]-rootAt[1]+y,0);refresh.dispatchTouchEvent(event);event.recycle();}
        check(refreshes==0&&!refresh.isRefreshing(),"Horizontal drag triggered pull-to-refresh");
    }
    void run(){Bundle result=new Bundle();try{
        activity=test.startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(test.getTargetContext().getPackageName(),MainActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        JellyfinApi.Item episode=item("episode","Episode",true),movie=item("movie","Movie",false),series=item("series","Series",false),collection=item("collection","BoxSet",false);episode.resumeMs=600000;
        List<JellyfinApi.Item> large=new ArrayList<>();for(int i=0;i<100;i++)large.add(item("film-"+i,"Movie",false));
        HomeFeed.Result<JellyfinApi.Item> data=new HomeFeed.Result<>(Arrays.asList(episode,movie),Arrays.asList(episode,series),Arrays.asList(episode,movie,series,collection));
        ui(()->{
            try{java.lang.reflect.Field page=MainActivity.class.getDeclaredField("page");page.setAccessible(true);page.setInt(activity,page.getInt(activity)+1);}catch(Exception e){throw new AssertionError(e);}
            style=new LibraryUi(activity,new Handler(Looper.getMainLooper()));scroll=new ScrollView(activity);body=style.column();body.setPadding(style.dp(20),0,style.dp(20),0);scroll.addView(body);
            refresh=new androidx.swiperefreshlayout.widget.SwipeRefreshLayout(activity);refresh.addView(scroll);refresh.setOnRefreshListener(()->{refreshes++;refresh.setRefreshing(false);});
            activity.setContentView(refresh);render(false,data);
        });settle();
        ui(()->{
            check(rails().size()==3&&label("继续观看")&&label("接下来")&&label("最近添加"),"Standard mode must contain three rails");
            HomeRail resume=rails().get(0),next=rails().get(1),latest=rails().get(2);
            check(resume.cardWidth>next.cardWidth&&next.cardWidth>latest.cardWidth,"Card size hierarchy changed");
            check(play(card(resume))!=null&&play(card(next))!=null,"Resume/Next play entry missing");
            for(int i=0;i<latest.getChildCount();i++)check(play(latest.getChildAt(i))==null,"Latest card exposed a play entry");
            check(!label("单集介绍不应展示"),"Home displayed episode introduction");
            card(latest).performClick();check(detailClicks==1&&playClicks==0,"Latest body must only open detail");
            play(card(next)).performClick();check(playClicks==1,"Next play icon did not play");
            card(next).performClick();check(detailClicks==2&&playClicks==1,"Next body must only open detail");
            int calls=detailClicks+playClicks;drag(resume,style.dp(100),style.dp(10),style.dp(80));check(detailClicks+playClicks==calls,"Horizontal drag clicked a card");
        });settle();
        ui(()->{
            HomeRail rail=rails().get(0);((LinearLayoutManager)rail.getLayoutManager()).scrollToPositionWithOffset(0,0);
        });settle();
        ui(()->{
            HomeRail rail=rails().get(0);View icon=play(card(rail));int[] iconAt=new int[2],railAt=new int[2];icon.getLocationOnScreen(iconAt);rail.getLocationOnScreen(railAt);
            float x=iconAt[0]-railAt[0]+icon.getWidth()/2f,y=iconAt[1]-railAt[1]+icon.getHeight()/2f;
            int before=detailClicks+playClicks;drag(rail,x,x-style.dp(100),y);check(detailClicks+playClicks==before,"Drag starting on play icon fired playback");
            View spacer=new View(activity);body.addView(spacer,new LinearLayout.LayoutParams(-1,style.dp(700)));
        });settle();
        ui(()->{
            int before=detailClicks+playClicks;long at=SystemClock.uptimeMillis();
            for(int i=0;i<=8;i++){int action=i==0?MotionEvent.ACTION_DOWN:i==8?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE;
                MotionEvent e=MotionEvent.obtain(at,at+i*35,action,style.dp(140),style.dp(180-i*15),0);refresh.dispatchTouchEvent(e);e.recycle();}
            check(scroll.getScrollY()>0,"Vertical swipe over a horizontal rail did not reach the page");
            check(detailClicks+playClicks==before,"Vertical swipe fired card navigation");scroll.scrollTo(0,0);
        });
        ui(()->render(false,new HomeFeed.Result<>(Collections.emptyList(),Collections.emptyList(),large)));settle();
        ui(()->{HomeRail rail=rails().get(0);check(rail.getChildCount()<12,"Horizontal list rendered all cards");((LinearLayoutManager)rail.getLayoutManager()).scrollToPositionWithOffset(22,0);});settle();
        HomeRail.Position[] position={null};
        ui(()->{position[0]=memory.get("homeRail:LATEST");check(position[0]!=null,"Rail position was not remembered");render(false,new HomeFeed.Result<>(Collections.emptyList(),Collections.emptyList(),large));});settle();
        ui(()->{HomeRail rail=rails().get(0);int index=((LinearLayoutManager)rail.getLayoutManager()).findFirstVisibleItemPosition();check(large.get(index).id.equals(position[0].id),"Rail return lost media identity");
            render(true,data);check(rails().isEmpty()&&label("继续观看")&&!label("接下来")&&!label("最近添加"),"Elder resume priority failed");
            render(true,new HomeFeed.Result<>(Collections.emptyList(),Arrays.asList(episode),Arrays.asList(movie)));check(label("接下来")&&!label("最近添加"),"Elder Next fallback failed");
            render(true,new HomeFeed.Result<>(Collections.emptyList(),Collections.emptyList(),Arrays.asList(movie)));check(label("最近添加")&&play(body)==null,"Elder latest must not play directly");
            render(true,new HomeFeed.Result<>(Collections.emptyList(),Collections.emptyList(),Collections.emptyList()));check(label("暂无可观看的影片"),"All-empty state missing");
            for(View v:all(body,TextView.class))if("前往媒体库".contentEquals(((TextView)v).getText()))v.performClick();check(libraryClicks==1,"Empty library action failed");
            try {
                java.lang.reflect.Field api=MainActivity.class.getDeclaredField("api");api.setAccessible(true);Object saved=api.get(activity);api.set(activity,null);
                try {
                    java.lang.reflect.Method open=MainActivity.class.getDeclaredMethod("openHomeDetails",JellyfinApi.Item.class);open.setAccessible(true);open.invoke(activity,collection);
                    boolean routed=false;for(View v:all(activity.findViewById(android.R.id.content),TextView.class))if("合集详情".contentEquals(((TextView)v).getText()))routed=true;
                    check(routed,"Home BoxSet did not route to collection members page");
                    java.lang.reflect.Field page=MainActivity.class.getDeclaredField("page");page.setAccessible(true);page.setInt(activity,page.getInt(activity)+1);
                } finally {api.set(activity,saved);}
            }catch(ReflectiveOperationException e){throw new AssertionError(e);}
        });
        result.putString("result","ALL_PASS: standard rails / elder three-level fallback / no latest play / body versus icon actions / gesture suppression / bounded recycling / rail position restoration / empty action");test.finish(Activity.RESULT_OK,result);
    }catch(Throwable error){result.putString("failure",android.util.Log.getStackTraceString(error));test.finish(Activity.RESULT_CANCELED,result);}}
}
