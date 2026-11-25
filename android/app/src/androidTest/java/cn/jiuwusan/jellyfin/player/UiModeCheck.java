package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.TextView;
import java.util.concurrent.atomic.AtomicReference;

/** The display switch must be local and must not replace the active connection or browse data. */
final class UiModeCheck {
    private final Instrumentation test;
    private Activity activity;
    UiModeCheck(Instrumentation test) { this.test = test; }
    private void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private Object field(String name) { return RecycledListDriver.field(activity, name); }
    private void ui(Runnable work) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        test.runOnMainSync(() -> { try { work.run(); } catch (Throwable e) { error.set(e); } });
        if (error.get() != null) throw new AssertionError(error.get());
    }
    private void invoke(String name, Class<?>[] types, Object... args) {
        try { java.lang.reflect.Method method = MainActivity.class.getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(activity,args); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private View text(View view, String value) {
        if(view instanceof TextView && value.contentEquals(((TextView)view).getText())) return view;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) { View found=text(((ViewGroup)view).getChildAt(i),value); if(found!=null) return found; }
        return null;
    }
    private View root() { return activity.findViewById(android.R.id.content); }
    private void openPlaybackSettings() {
        invoke("settingsPage",new Class<?>[]{String.class},"");
        View tab=text(root(),"播放"); check(tab!=null,"Playback settings tab missing"); tab.performClick();
    }
    void run() {
        Bundle out=new Bundle();
        try {
            String scope="display-default-check-"+java.util.UUID.randomUUID();
            android.content.Context isolated=new android.content.ContextWrapper(test.getTargetContext()) {
                @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode) {
                    return super.getSharedPreferences(DisplayStyle.STORE.equals(name) ? scope : name,mode);
                }
            };
            try {
                check(!DisplayStyle.load(isolated).large,"Fresh display preferences did not default to standard UI");
                DisplayStyle.save(isolated,true);
                check(DisplayStyle.load(isolated).large,"Saved large preference was overridden by the default");
                DisplayStyle.save(isolated,false);
                check(!DisplayStyle.load(isolated).large,"Local standard preference did not persist");
            } finally {
                isolated.getSharedPreferences(DisplayStyle.STORE,0).edit().clear().commit();
                if(android.os.Build.VERSION.SDK_INT>=24) test.getTargetContext().deleteSharedPreferences(scope);
            }
            activity=test.startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(test.getTargetContext().getPackageName(),MainActivity.class.getName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            SystemClock.sleep(1500);
            ui(() -> {
                openPlaybackSettings();
                View setting=text(root(),"长辈模式");
                check(setting instanceof Switch,"Local large UI switch missing");
                Object connection=field("api"), settings=field("settings");
                String subscription=test.getTargetContext().getSharedPreferences(SubscriptionSettings.STORE,0).getString("sealed",null);
                BrowseMemory memory=(BrowseMemory)field("browseMemory");
                Object marker=new Object(); memory.put("ui-mode-check",marker,SystemClock.uptimeMillis());
                boolean previous=((Switch)setting).isChecked();
                ((Switch)setting).setChecked(!previous);
                check(field("api")==connection && field("settings")==settings,"Display switch replaced connection");
                check(memory.get("ui-mode-check")==marker,"Display switch discarded cached data");
                check(java.util.Objects.equals(subscription,test.getTargetContext().getSharedPreferences(SubscriptionSettings.STORE,0).getString("sealed",null)),"Display mode changed the saved subscription");
                check(text(root(),"长辈模式") instanceof Switch,"Switch disappeared while rebuilding settings");
                check(((Switch)text(root(),"长辈模式")).isChecked()!=previous,"Display mode not applied");
                ((Switch)text(root(),"长辈模式")).setChecked(previous);
                check(((Switch)text(root(),"长辈模式")).isChecked()==previous,"Original mode not restored");
                String status=(String)field("subscriptionMessage");
                try {
                    ((Switch)text(root(),"长辈模式")).setChecked(false);
                    java.lang.reflect.Field message=MainActivity.class.getDeclaredField("subscriptionMessage");message.setAccessible(true);
                    message.set(activity,"受控同步失败，请重试");
                    invoke("settingsPage",new Class<?>[]{String.class},"");
                    check(((TextView)field("subscriptionStatus")).getText().toString().contains("受控同步失败"),"Standard settings lost sync failure status");
                } catch(ReflectiveOperationException e) { throw new AssertionError(e); }
                finally {
                    try { java.lang.reflect.Field message=MainActivity.class.getDeclaredField("subscriptionMessage");message.setAccessible(true);message.set(activity,status); }
                    catch(ReflectiveOperationException e) { throw new AssertionError(e); }
                    ((Switch)text(root(),"长辈模式")).setChecked(previous);
                }
                memory.remove("ui-mode-check");
            });
            boolean previous=DisplayStyle.load(test.getTargetContext()).large;
            try {
                DisplayStyle.save(test.getTargetContext(),false);
                activity=test.startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(test.getTargetContext().getPackageName(),MainActivity.class.getName())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
                ui(() -> check(!((LibraryUi)field("libraryUi")).largeUi(),"Standard mode was not loaded on Activity startup"));
            } finally { DisplayStyle.save(test.getTargetContext(),previous); }
            out.putString("result","ALL_PASS: local display toggle / live settings rebuild / same connection+subscription / cached data retained / restart persistence / previous mode restored");
            test.finish(Activity.RESULT_OK,out);
        } catch(Throwable e) {
            out.putString("failure",android.util.Log.getStackTraceString(e)); test.finish(Activity.RESULT_CANCELED,out);
        }
    }
}
