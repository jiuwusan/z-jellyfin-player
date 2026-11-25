package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.*;
import org.json.*;

/** Captures actual image requests in a local interceptor; no real network or account writes. */
final class LatestPosterCheck {
    private final Instrumentation test;private Activity activity;private LibraryUi style;private LinearLayout root;private ImageView current;
    private final List<String> paths=new CopyOnWriteArrayList<>();private boolean missing;
    LatestPosterCheck(Instrumentation test){this.test=test;}
    private void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private void ui(Runnable work){AtomicReference<Throwable> error=new AtomicReference<>();test.runOnMainSync(()->{try{work.run();}catch(Throwable e){error.set(e);}});if(error.get()!=null)throw new AssertionError(error.get());}
    private JellyfinApi.Item item(String id,String type,String series)throws Exception {
        JSONObject json=new JSONObject().put("Id",id).put("Name","Fixture").put("Type",type).put("ImageTags",new JSONObject().put("Primary","own-"+id));
        if(!series.isEmpty())json.put("SeriesId",series).put("SeriesName","Series").put("SeriesPrimaryImageTag","series-poster").put("IndexNumber",1).put("ParentIndexNumber",1).put("ParentBackdropItemId",series).put("ParentBackdropImageTags",new JSONArray().put("series-backdrop"));
        return new JellyfinApi.Item(json);
    }
    private ImageView image(View view){if(view instanceof ImageView)return (ImageView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){ImageView found=image(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}return null;}
    private void show(JellyfinApi.Item item,HomeFeed.Section section,boolean elder){
        ui(()->{style.releasePictures(root);root.removeAllViews();style.setLargeUi(elder);View card=style.feedCard(item,section,style.dp(180),elder,i->{},i->{});root.addView(card,new LinearLayout.LayoutParams(style.dp(180),-2));current=image(card);});
        boolean[] ready={false};for(int i=0;i<120&&!ready[0];i++){ui(()->ready[0]=current.getDrawable() instanceof BitmapDrawable);SystemClock.sleep(40);}check(ready[0],"Poster did not load");
    }
    private void last(String expected){check(!paths.isEmpty()&&paths.get(paths.size()-1).equals(expected),"Unexpected poster route; expected "+expected+", got "+paths);}
    void run(){Bundle out=new Bundle();try{
        activity=test.startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(test.getTargetContext().getPackageName(),MainActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        Bitmap bitmap=Bitmap.createBitmap(120,180,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.GREEN);ByteArrayOutputStream bytes=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes);bitmap.recycle();byte[] png=bytes.toByteArray();
        JellyfinApi api=new JellyfinApi(new ConnectionSettings("http://fixture.invalid","127.0.0.1",9,"","","fixture","fixture",1920,4_000_000),"poster-check");
        java.lang.reflect.Field http=JellyfinApi.class.getDeclaredField("http");http.setAccessible(true);
        http.set(api,api.client().newBuilder().addInterceptor(chain->{
            String path=chain.request().url().encodedPath();paths.add(path);
            if(!path.contains("/Images/"))throw new java.io.IOException("Unexpected metadata query");
            boolean absent=missing&&path.equals("/Items/missing-series/Images/Primary");
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(absent?404:200).message("fixture").body(ResponseBody.create(absent?new byte[0]:png,MediaType.get("image/png"))).build();
        }).build());
        ui(()->{try{java.lang.reflect.Field page=MainActivity.class.getDeclaredField("page");page.setAccessible(true);page.setInt(activity,page.getInt(activity)+1);}catch(Exception e){throw new AssertionError(e);}
            style=new LibraryUi(activity,new Handler(Looper.getMainLooper()));style.useConnection(api);root=style.column();activity.setContentView(root);});
        show(item("episode-a","Episode","series-a"),HomeFeed.Section.LATEST,false);last("/Items/series-a/Images/Primary");check(paths.size()==1,"Episode poster added a metadata request");
        int before=paths.size();show(item("episode-b","Episode","series-a"),HomeFeed.Section.LATEST,false);check(paths.size()==before,"Same series episodes did not share the poster cache");
        show(item("movie","Movie",""),HomeFeed.Section.LATEST,false);last("/Items/movie/Images/Primary");
        show(item("show","Series",""),HomeFeed.Section.LATEST,false);last("/Items/show/Images/Primary");
        show(item("orphan","Episode",""),HomeFeed.Section.LATEST,false);last("/Items/orphan/Images/Primary");
        missing=true;before=paths.size();show(item("fallback-a","Episode","missing-series"),HomeFeed.Section.LATEST,false);last("/Items/fallback-a/Images/Primary");check(paths.size()==before+2,"Missing series poster fallback changed");
        show(item("fallback-b","Episode","missing-series"),HomeFeed.Section.LATEST,false);last("/Items/fallback-b/Images/Primary");
        show(item("next","Episode","series-a"),HomeFeed.Section.NEXT,false);last("/Items/series-a/Images/Backdrop/0");
        before=paths.size();show(item("elder","Episode","series-a"),HomeFeed.Section.LATEST,true);check(paths.size()==before,"Elder latest stopped reusing series backdrop");
        for(String path:paths)check(path.contains("/Images/"),"Metadata query was added");
        out.putString("result","ALL_PASS: series poster from list ID / movie+series own poster / shared cache / missing poster and missing ID fallback / Next+elder backdrop unchanged / no metadata queries");test.finish(Activity.RESULT_OK,out);
    }catch(Throwable error){out.putString("failure",android.util.Log.getStackTraceString(error));test.finish(Activity.RESULT_CANCELED,out);}}
}
