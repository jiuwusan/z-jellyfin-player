package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** UI-only transport and timeout checks; no media or account writes. */
final class FullscreenControlsCheck {
    private final Instrumentation test;
    private Activity activity;
    private FullscreenPlaybackView screen;
    private ExoPlayer delegate;
    private boolean playing = true, buffering;
    private long position = 60_000;
    private int captionPadding;
    private final AtomicInteger previous = new AtomicInteger(), next = new AtomicInteger(), selected = new AtomicInteger(-1);
    FullscreenControlsCheck(Instrumentation test) { this.test = test; }
    private void check(boolean value, String why) { if (!value) throw new AssertionError(why); }
    private Object get(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    private View view(String name) throws Exception { return (View) get(screen, name); }
    private void call(String name) throws Exception {
        Method m = screen.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(screen);
    }
    private void ui(Work work) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        test.runOnMainSync(() -> { try { work.run(); } catch (Throwable e) { failure.set(e); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    interface Work { void run() throws Exception; }
    private void assertLayout() throws Exception {
        View central = view("transportRow"), bottom = view("bottom");
        check(central.getParent() == screen, "Transport controls are still inside bottom toolbar");
        check(Math.abs(central.getY() + central.getHeight() / 2f - screen.getHeight() / 2f) <= 1,
                "Central action row is not vertically centered");
        int[] playPoint = new int[2], rootPoint = new int[2];
        view("play").getLocationOnScreen(playPoint); screen.getLocationOnScreen(rootPoint);
        check(Math.abs(playPoint[0] + view("play").getWidth()/2f - rootPoint[0] - screen.getWidth()/2f) <= 1,
                "Play button is not horizontally centered");
        check(central.getBottom() < bottom.getTop(), "Central controls overlap bottom timeline");
        String[] names = {"previous", "rewind", "play", "forward", "next"};
        int last = 0;
        for (String name : names) {
            View b = view(name); int[] xy = new int[2]; b.getLocationOnScreen(xy);
            check(b.isShown() && xy[0] >= last, "Five transport actions are out of order or overlap: " + name);
            check(b.getHeight() >= screen.getResources().getDisplayMetrics().density * 56, "Touch area too short: " + name);
            last = xy[0] + b.getWidth();
            if (!"play".equals(name)) check(((TextView)b).getCompoundDrawables()[1] != null, "Missing vector icon: " + name);
        }
        check(view("play").getWidth() > view("previous").getWidth(), "Main play button is not visually dominant");
    }
    void run() {
        Bundle result = new Bundle();
        try {
            activity = test.startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName(test.getTargetContext().getPackageName(), MainActivity.class.getName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            ui(() -> {
                Field page = MainActivity.class.getDeclaredField("page");page.setAccessible(true);page.setInt(activity,page.getInt(activity)+1);
                screen = new FullscreenPlaybackView(activity, new LibraryUi(activity,new Handler(Looper.getMainLooper())),
                        "1080p · 4 Mbps", () -> {}, previous::incrementAndGet, next::incrementAndGet, () -> {}, () -> {});
                screen.setTitle("魅影神捕", "第2集 · 月下疑踪");
                Method fullscreen = MainActivity.class.getDeclaredMethod("enterFullscreenPlayback");fullscreen.setAccessible(true);fullscreen.invoke(activity);
                activity.setContentView(screen);
                float density=activity.getResources().getDisplayMetrics().density;
                screen.setLayoutParams(new FrameLayout.LayoutParams(Math.round(640*density),Math.round(360*density),android.view.Gravity.CENTER));
                delegate = new ExoPlayer.Builder(activity).build();
                screen.setPlayer(new ForwardingPlayer(delegate) {
                    @Override public boolean isPlaying() { return playing && !buffering; }
                    @Override public boolean getPlayWhenReady() { return playing; }
                    @Override public int getPlaybackState() { return buffering ? Player.STATE_BUFFERING : Player.STATE_READY; }
                    @Override public long getDuration() { return 120_000; }
                    @Override public long getCurrentPosition() { return position; }
                    @Override public boolean isCurrentMediaItemSeekable() { return true; }
                    @Override public void seekTo(long p) { position=p; }
                    @Override public void play() { playing=true; }
                    @Override public void pause() { playing=false; }
                });
                screen.updateActions(true,true,true,true,true);
            });
            test.waitForIdleSync();
            ui(() -> {
                assertLayout();
                call("liftCaptions");
                check(((androidx.media3.ui.PlayerView)get(screen,"video")).getSubtitleView().getPaddingBottom()==0,
                        "Caption still reserves footer space instead of using the normal video bottom");
                view("rewind").performClick();check(position==55_000,"Rewind callback changed");
                view("forward").performClick();check(position==70_000,"Forward callback changed");
                view("previous").performClick();view("next").performClick();
                check(previous.get()==1&&next.get()==1,"Neighbor callbacks changed");
                screen.setCollectionNavigation(true);
                check("上一部".contentEquals(view("previous").getContentDescription())&&"下一部".contentEquals(view("next").getContentDescription()),"Collection labels lost");
                screen.updateActions(false,false,false,true,true);
                check(view("previous").getVisibility()==View.GONE&&view("next").getVisibility()==View.GONE,"Movie has episode controls");
                screen.updateActions(true,true,true,true,true);
                call("liftCaptions");
                captionPadding=((androidx.media3.ui.PlayerView)get(screen,"video")).getSubtitleView().getPaddingBottom();
                call("restartControlsTimeout");
            });
            SystemClock.sleep(5300);
            ui(() -> {
                check(!view("header").isShown()&&!view("bottom").isShown()
                        &&!view("transportRow").isShown(),"All control zones did not auto-hide");
                call("liftCaptions");
                check(((androidx.media3.ui.PlayerView)get(screen,"video")).getSubtitleView().getPaddingBottom()==captionPadding,
                        "Subtitle position moved when controls hid");
                view("touchSurface").performClick();check(view("transportRow").isShown(),"Tap did not restore center controls");
                view("play").performClick();check(!playing,"Pause failed");
            });
            SystemClock.sleep(5300);
            ui(() -> {
                check(view("transportRow").isShown(),"Paused controls disappeared");
                playing=true;buffering=true;call("restartRefresh");call("restartControlsTimeout");
            });
            SystemClock.sleep(5300);
            ui(() -> {
                check(view("transportRow").isShown(),"Buffering controls disappeared");
                view("play").performClick();check(!playing,"Cannot pause buffering playback");
                buffering=false;playing=true;
                screen.showChoices("字幕",new String[]{"简体中文","繁体中文","关闭字幕"},0,selected::set);
            });
            SystemClock.sleep(5300);
            ui(() -> {
                check(view("trackPanel").isShown()&&view("transportRow").isShown(),"Menu lost visibility");
                android.view.ViewGroup list=(android.view.ViewGroup)get(screen,"trackList");list.getChildAt(2).performClick();
                check(selected.get()==2&&!view("trackPanel").isShown(),"Subtitle selection callback or dismissal changed");
                screen.showChoices("音轨",new String[]{"国语","粤语"},0,selected::set);
                ((android.view.ViewGroup)get(screen,"trackList")).getChildAt(1).performClick();
                check(selected.get()==1,"Audio selection callback changed");
            });
            result.putString("result","ALL_PASS: center five actions / vector icons / tap areas / seek / neighbors / collection+movie / five-second hide / pause / buffer / subtitle+audio menus");
            test.finish(Activity.RESULT_OK,result);
        } catch(Throwable e) {result.putString("failure",android.util.Log.getStackTraceString(e));test.finish(Activity.RESULT_CANCELED,result);}
        finally {if(activity!=null)ui(() -> {if(screen!=null)screen.setPlayer(null);if(delegate!=null)delegate.release();activity.finish();});}
    }
}
