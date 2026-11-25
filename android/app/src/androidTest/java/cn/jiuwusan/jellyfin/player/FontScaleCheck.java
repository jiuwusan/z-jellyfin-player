package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/** Real Android resource/font conversions under isolated font scales; never changes phone settings. */
final class FontScaleCheck {
    private final Instrumentation test;
    FontScaleCheck(Instrumentation test){this.test=test;}
    private void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private void same(float a,float b,String why){check(Math.abs(a-b)<.1f,why+": "+a+" vs "+b);}
    private void ui(Runnable work){AtomicReference<Throwable> error=new AtomicReference<>();test.runOnMainSync(()->{try{work.run();}catch(Throwable e){error.set(e);}});if(error.get()!=null)throw new AssertionError(error.get());}
    void run(){Bundle result=new Bundle();try{
        Method policy;
        try{policy=Class.forName("cn.jiuwusan.jellyfin.player.AppDisplayContext").getDeclaredMethod("fontOverride");policy.setAccessible(true);}
        catch(ReflectiveOperationException missing){throw new AssertionError("App font scale policy is missing");}
        Context base=test.getTargetContext();Configuration normal=new Configuration();normal.fontScale=1f;
        Context baseline=base.createConfigurationContext(normal);
        Configuration override=(Configuration)policy.invoke(null);
        same(override.fontScale,1f,"Font policy must explicitly override system fontScale");
        check(override.orientation==Configuration.ORIENTATION_UNDEFINED&&override.densityDpi==Configuration.DENSITY_DPI_UNDEFINED&&override.screenWidthDp==Configuration.SCREEN_WIDTH_DP_UNDEFINED&&override.screenHeightDp==Configuration.SCREEN_HEIGHT_DP_UNDEFINED,"Font policy must not pin screen fields");
        for(float scale:new float[]{.85f,1f,1.3f,1.6f,2f}){
            // Use Activity's native ContextThemeWrapper override mechanism. Real rotation is checked below.
            Configuration simulated=new Configuration();simulated.fontScale=scale;
            Context system=base.createConfigurationContext(simulated);
            android.view.ContextThemeWrapper fixed=new android.view.ContextThemeWrapper(system,cn.jiuwusan.jellyfin.player.R.style.Theme_Jellyfin);
            fixed.applyOverrideConfiguration(override);
            same(fixed.getResources().getConfiguration().fontScale,1f,"App followed system font scale");
            Configuration actual=fixed.getResources().getConfiguration();
            Configuration inherited=system.getResources().getConfiguration();
            check(actual.orientation==inherited.orientation&&actual.screenWidthDp==inherited.screenWidthDp&&actual.screenHeightDp==inherited.screenHeightDp,
                    "Font override replaced screen geometry: expected "+inherited.orientation+"/"+inherited.screenWidthDp+"x"+inherited.screenHeightDp+", actual "+actual.orientation+"/"+actual.screenWidthDp+"x"+actual.screenHeightDp);
            check(actual.densityDpi==system.getResources().getConfiguration().densityDpi,"Font override changed display density");
            same(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,24,fixed.getResources().getDisplayMetrics()),TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,24,system.getResources().getDisplayMetrics()),"dp size changed");
            if(scale==2f)check(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,28,system.getResources().getDisplayMetrics())>TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,28,baseline.getResources().getDisplayMetrics()),"Large-font simulation was ineffective");
            ui(()->{
                for(int sp:new int[]{12,14,24,28,48}){
                    TextView label=new TextView(fixed);label.setTextSize(sp);
                    same(label.getTextSize(),TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,sp,baseline.getResources().getDisplayMetrics()),"Text size changed with system scale");
                }
                EditText input=new EditText(fixed);input.setTextSize(14);
                same(input.getTextSize(),TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,14,baseline.getResources().getDisplayMetrics()),"Input followed system font");
                Spinner spinner=new Spinner(fixed,Spinner.MODE_DROPDOWN);
                same(spinner.getPopupContext().getResources().getConfiguration().fontScale,1f,"Spinner popup followed system font");
                AlertDialog dialog=new AlertDialog.Builder(fixed).setTitle("确认").setMessage("示例").create();
                same(dialog.getContext().getResources().getConfiguration().fontScale,1f,"Dialog followed system font");
                LibraryUi style=new LibraryUi(fixed,new Handler(Looper.getMainLooper()));style.setLargeUi(false);float standard=style.label("示例",style.size(24,14),LibraryUi.TEXT,false).getTextSize();
                style.setLargeUi(true);float elder=style.label("示例",style.size(24,14),LibraryUi.TEXT,false).getTextSize();
                check(elder>standard,"App display modes stopped controlling text size");
            });
        }
        Activity activity=test.startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(base.getPackageName(),MainActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        ui(()->same(activity.getResources().getConfiguration().fontScale,1f,"MainActivity did not apply fixed fonts"));
        int original=activity.getRequestedOrientation();
        try {
            for(int orientation:new int[]{android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}){
                ui(()->activity.setRequestedOrientation(orientation));
                int expected=orientation==android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE?Configuration.ORIENTATION_LANDSCAPE:Configuration.ORIENTATION_PORTRAIT;
                boolean[] ready={false};for(int i=0;i<100&&!ready[0];i++){ui(()->ready[0]=activity.getResources().getConfiguration().orientation==expected);android.os.SystemClock.sleep(60);}
                check(ready[0],"Font wrapper froze orientation changes");
                ui(()->same(activity.getResources().getConfiguration().fontScale,1f,"Font scale changed after rotation"));
            }
        }finally{ui(()->activity.setRequestedOrientation(original));}
        result.putString("result","ALL_PASS: isolated scales 0.85/1/1.3/1.6/2 / SP 12-48 / input+popup+dialog / standard versus elder / density+geometry preserved / Activity rotates landscape+portrait / no system changes");test.finish(Activity.RESULT_OK,result);
    }catch(Throwable error){result.putString("failure",android.util.Log.getStackTraceString(error));test.finish(Activity.RESULT_CANCELED,result);}}
}
