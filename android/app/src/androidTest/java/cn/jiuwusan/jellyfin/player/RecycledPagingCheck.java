package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONObject;

/** Controlled pagination/refresh failures through the real Activity listing and API parser. */
final class RecycledPagingCheck {
    private final Instrumentation test;
    private Activity activity;
    private Object location;
    private final AtomicBoolean fail = new AtomicBoolean();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger latency = new AtomicInteger(400);
    private int generation;
    RecycledPagingCheck(Instrumentation test) { this.test = test; }
    private void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private void ui(Runnable work) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        test.runOnMainSync(() -> { try { work.run(); } catch (Throwable e) { error.set(e); } });
        if (error.get() != null) throw new AssertionError(error.get());
    }
    private interface Condition { boolean ready(); }
    private void waitFor(Condition condition, String why) {
        boolean[] ready = {false};
        for (int i = 0; i < 200 && !ready[0]; i++) { ui(() -> ready[0] = condition.ready()); SystemClock.sleep(50); }
        check(ready[0], why);
    }
    private RecycledMediaGrid grid() { return RecycledListDriver.find(activity.findViewById(android.R.id.content)); }
    private View text(View view, String title) {
        if (view instanceof TextView && title.contentEquals(((TextView)view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            View found = text(((ViewGroup)view).getChildAt(i), title); if (found != null) return found;
        }
        return null;
    }
    private void show() {
        try {
            java.lang.reflect.Method m = MainActivity.class.getDeclaredMethod("libraryPage", location.getClass(), Runnable.class);
            m.setAccessible(true); m.invoke(activity, location, (Runnable)() -> {});
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private int size() { return ((List<?>)RecycledListDriver.field(location, "items")).size(); }
    private void refresh() {
        SwipeRefreshLayout refresh = (SwipeRefreshLayout)grid().getParent();
        Object listener = RecycledListDriver.field(refresh, "mListener");
        ((SwipeRefreshLayout.OnRefreshListener)listener).onRefresh();
    }
    void run() {
        Bundle result = new Bundle();
        JellyfinApi fixture = null;
        try {
            activity = test.startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName(test.getTargetContext().getPackageName(), MainActivity.class.getName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            waitFor(() -> activity.findViewById(R.id.home_refresh) != null, "Existing account home not ready");
            fixture = new JellyfinApi(new ConnectionSettings("http://fixture.invalid", "127.0.0.1", 9, "", "", "fixture", "fixture", 1920, 4000000), "recycled-paging-check");
            java.lang.reflect.Field http = JellyfinApi.class.getDeclaredField("http"); http.setAccessible(true);
            http.set(fixture, fixture.client().newBuilder().addInterceptor(chain -> {
                calls.incrementAndGet();
                SystemClock.sleep(latency.get());
                if (fail.get()) throw new java.io.IOException("Controlled list failure");
                try {
                    int offset = Integer.parseInt(chain.request().url().queryParameter("StartIndex"));
                    check("DateCreated,SortName".equals(chain.request().url().queryParameter("SortBy")), "Sort query changed");
                    check("Movie".equals(chain.request().url().queryParameter("IncludeItemTypes")), "Library type query changed");
                    JSONArray rows = new JSONArray();
                    for (int i = offset; i < Math.min(offset + 36, 108); i++) rows.put(new JSONObject()
                            .put("Id", "fixture-" + generation + "-" + i).put("Name", "影片 " + i).put("Type", "Movie"));
                    return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                            .body(ResponseBody.create(new JSONObject().put("Items", rows).put("TotalRecordCount", 108).toString(),
                                    MediaType.get("application/json"))).build();
                } catch (org.json.JSONException e) { throw new java.io.IOException(e); }
            }).build());
            JellyfinApi controlled = fixture;
            ui(() -> {
                try {
                    java.lang.reflect.Field api = MainActivity.class.getDeclaredField("api"); api.setAccessible(true); api.set(activity, controlled);
                    Class<?> type = Class.forName(MainActivity.class.getName() + "$LibraryLocation");
                    java.lang.reflect.Constructor<?> ctor = type.getDeclaredConstructor(JellyfinApi.Item.class, boolean.class); ctor.setAccessible(true);
                    location = ctor.newInstance(new JellyfinApi.Item(new JSONObject().put("Id", "fixture-library")
                            .put("Name", "回收列表验收").put("Type", "CollectionFolder").put("CollectionType", "movies")), true);
                    show();
                } catch (Exception e) { throw new AssertionError(e); }
            });
            waitFor(() -> size() == 36, "First page did not load");
            latency.set(8000);
            ui(() -> grid().scrollToPosition(35));
            waitFor(() -> calls.get() >= 2, "Pagination request not started");
            // Rebinding a recycled footer must not finish the still-running request's timer.
            ui(() -> grid().getAdapter().notifyDataSetChanged());
            SystemClock.sleep(600);
            ui(() -> check(text(grid(), "正在加载更多…") != null, "Recycled footer cancelled active loading feedback"));
            waitFor(() -> text(grid(), "加载较慢，请稍候…") != null, "Recycled footer lost slow-loading feedback");
            waitFor(() -> size() == 72, "Automatic pagination did not load page two");
            latency.set(400);
            fail.set(true);
            ui(() -> grid().scrollToPosition(71));
            waitFor(() -> text(grid(), "重试") != null, "Pagination failure lacks retry");
            ui(() -> check(size() == 72, "Pagination failure removed existing items"));
            fail.set(false);
            ui(() -> text(grid(), "重试").performClick());
            waitFor(() -> size() == 108, "Pagination retry failed");
            ui(() -> grid().scrollToPosition(105)); test.waitForIdleSync(); SystemClock.sleep(150);
            RecycledMediaGrid.Anchor[] saved = new RecycledMediaGrid.Anchor[1];
            int requests = calls.get();
            ui(() -> { saved[0] = grid().anchor(); show(); });
            test.waitForIdleSync(); SystemClock.sleep(150);
            ui(() -> RecycledListDriver.same(grid(), saved[0]));
            check(calls.get() == requests, "Cached return reloaded data");
            fail.set(true); ui(this::refresh);
            waitFor(() -> text(activity.findViewById(android.R.id.content), "重试") != null, "Refresh failure notice missing");
            ui(() -> { check(size() == 108, "Refresh failure cleared old data"); RecycledListDriver.same(grid(), saved[0]); });
            fail.set(false); generation = 1; ui(this::refresh);
            waitFor(() -> size() == 36 && !((SwipeRefreshLayout)grid().getParent()).isRefreshing(), "Refresh success did not replace data");
            ui(() -> check(grid().anchor().key.contains("fixture-1-0"), "Refresh did not reset to updated first item"));
            result.putString("result", "ALL_PASS: typed/sorted queries / automatic paging / recycled footer normal+slow feedback / footer retry / cached return / refresh failure retains data+anchor / refresh success replaces+resets");
            result.putInt("requests", calls.get()); test.finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            result.putString("failure", android.util.Log.getStackTraceString(e)); test.finish(Activity.RESULT_CANCELED, result);
        }
    }
}
