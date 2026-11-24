package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONObject;

/**
 * Controlled session-lifetime checks; isolated preferences, no real playback or configuration
 * writes.
 */
final class SubscriptionLifecycleCheck {
  private final Instrumentation test;
  private Activity activity;

  SubscriptionLifecycleCheck(Instrumentation test) {
    this.test = test;
  }

  private void check(boolean ok, String reason) {
    if (!ok) throw new AssertionError(reason);
  }

  private Object field(Object object, String name) {
    try {
      java.lang.reflect.Field f = object.getClass().getDeclaredField(name);
      f.setAccessible(true);
      return f.get(object);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private void set(Object object, String name, Object value) {
    try {
      java.lang.reflect.Field f = object.getClass().getDeclaredField(name);
      f.setAccessible(true);
      f.set(object, value);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private Object invoke(Object object, String name, Class<?>[] types, Object... args) {
    try {
      java.lang.reflect.Method m = object.getClass().getDeclaredMethod(name, types);
      m.setAccessible(true);
      return m.invoke(object, args);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private void ui(Runnable work) {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    test.runOnMainSync(() -> {
      try {
        work.run();
      } catch (Throwable e) {
        failure.set(e);
      }
    });
    if (failure.get() != null) throw new AssertionError(failure.get());
  }

  void run() {
    Bundle out = new Bundle();
    String scope = "subscription-lifecycle-" + UUID.randomUUID();
    Object[] previous = new Object[3];
    SubscriptionController[] controller = new SubscriptionController[1];
    String saved =
        test.getTargetContext().getSharedPreferences("connection", 0).getString("sealed", null);
    try {
      activity =
          test.startActivitySync(
              new Intent(Intent.ACTION_MAIN)
                  .setClassName(
                      test.getTargetContext().getPackageName(), MainActivity.class.getName())
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
      ui(() -> {
        SubscriptionController active =
            (SubscriptionController) field(activity, "subscriptionController");
        if (active.state() == null) {
          check(
              field(activity, "settings") == null && field(activity, "api") == null,
              "Legacy configuration bypassed subscription setup");
        }
        for (java.lang.reflect.Method method : MainActivity.class.getDeclaredMethods())
          check(
              !method.getName().equals("manualSettingsPage"), "Legacy settings entry still exists");
      });
      for (int i = 0; i < 300; i++) {
        boolean[] ready = {false};
        ui(() -> ready[0] = field(activity, "subscriptionStatus") != null);
        if (ready[0]) break;
        SystemClock.sleep(100);
      }
      ManagedConfiguration config =
          ManagedConfiguration.parse(
              SubscriptionCheck.config(1), 56, "00000000000000000000000000000001");
      new SubscriptionSettings(
              SubscriptionCheck.link(), config, "", System.currentTimeMillis(), true)
          .save(test.getTargetContext(), scope);
      OkHttpClient fixture =
          new OkHttpClient.Builder()
              .addInterceptor(chain ->
                  new Response.Builder()
                      .request(chain.request())
                      .protocol(Protocol.HTTP_1_1)
                      .code(503)
                      .message("fixture")
                      .body(ResponseBody.create("{}", MediaType.get("application/json")))
                      .build())
              .build();
      ui(() -> {
        try {
          previous[0] = field(activity, "subscriptionController");
          previous[1] = field(activity, "api");
          previous[2] = field(activity, "settings");
          Handler handler = (Handler) field(activity, "ui");
          handler.removeCallbacks((Runnable) field(activity, "subscriptionTick"));
          controller[0] =
              new SubscriptionController(
                  test.getTargetContext(),
                  handler,
                  "fixture-device",
                  new SubscriptionController.Listener() {
                    public void status(String v) {}

                    public void prepared(SubscriptionController.Prepared p) {}

                    public void blocked() {}
                  },
                  new SubscriptionClient(56, fixture),
                  settings -> new JellyfinApi(settings, "fixture-device"),
                  scope);
          set(activity, "subscriptionController", controller[0]);
          set(activity, "settings", config.settings(null));
          set(activity, "api", new JellyfinApi(config.settings(null), "fixture-device"));
          JellyfinApi.Item current =
              new JellyfinApi.Item(
                  new JSONObject()
                      .put("Id", "fixture-episode-1")
                      .put("Name", "Fixture")
                      .put("Type", "Episode")
                      .put("SeriesId", "fixture-series")
                      .put("IndexNumber", 1));
          JellyfinApi.Item next =
              new JellyfinApi.Item(
                  new JSONObject()
                      .put("Id", "fixture-episode-2")
                      .put("Name", "Fixture")
                      .put("Type", "Episode")
                      .put("SeriesId", "fixture-series")
                      .put("IndexNumber", 2));
          Class<?> pageType = Class.forName(MainActivity.class.getName() + "$PlaybackPage");
          java.lang.reflect.Constructor<?> ctor =
              pageType.getDeclaredConstructor(
                  MainActivity.class, JellyfinApi.Item.class, PlaybackPolicy.class, Runnable.class);
          ctor.setAccessible(true);
          Object page =
              ctor.newInstance(
                  activity,
                  current,
                  PlaybackPolicy.fromSettings(1920, 4000000, 1080, 1920),
                  (Runnable) () -> {});
          set(activity, "playback", page);
          // A blocked transition must return before dereferencing the absent player or requesting a
          // stream.
          invoke(
              page, "switchTo", new Class<?>[] {JellyfinApi.Item.class, Integer.class}, next, null);
          check(field(page, "change") == null, "Revoked subscription started episode switch");
          set(
              controller[0],
              "state",
              new SubscriptionSettings(
                  SubscriptionCheck.link(), config, "", System.currentTimeMillis(), false));
          JSONObject json = SubscriptionCheck.config(2);
          json.getJSONObject("connection")
              .getJSONObject("proxy")
              .put("host", "changed.example.com");
          ManagedConfiguration changed =
              ManagedConfiguration.parse(json, 56, config.subscriptionId);
          SubscriptionController.Prepared prepared =
              new SubscriptionController.Prepared(
                  new SubscriptionSettings(
                      SubscriptionCheck.link(), changed, "", System.currentTimeMillis(), false),
                  new JellyfinApi(changed.settings(null), "fixture-device"),
                  0);
          invoke(
              activity,
              "applySubscription",
              new Class<?>[] {SubscriptionController.Prepared.class},
              prepared);
          check(
              field(activity, "pendingSubscription") == prepared,
              "Connection change interrupted initial playback preparation");
          check(
              controller[0].state().configuration.revision == 1,
              "Pending config was committed before playback ended");
          JSONObject lower = SubscriptionCheck.config(2);
          lower.getJSONObject("playback").put("resolution", "720p").put("totalBitrateBps", 3000000);
          set(
              activity,
              "settings",
              ManagedConfiguration.parse(lower, 56, config.subscriptionId).settings(null));
          PlaybackPolicy future =
              (PlaybackPolicy)
                  invoke(page, "policyFor", new Class<?>[] {JellyfinApi.Item.class}, next);
          PlaybackPolicy same =
              (PlaybackPolicy)
                  invoke(page, "policyFor", new Class<?>[] {JellyfinApi.Item.class}, current);
          check(
              future.maxWidth == 1280
                  && future.totalBitrate == 3000000
                  && same.totalBitrate == 4000000,
              "Quality was not scoped to next episode");
        } catch (Exception e) {
          throw new AssertionError(e);
        }
      });
      check(
          java.util.Objects.equals(
              saved,
              test.getTargetContext()
                  .getSharedPreferences("connection", 0)
                  .getString("sealed", null)),
          "Legacy connection changed");
      out.putString(
          "result",
          "ALL_PASS: revoked episode transition blocked / connection update deferred during"
              + " preparation / next-episode quality / unchanged legacy configuration");
      test.finish(Activity.RESULT_OK, out);
    } catch (Throwable e) {
      out.putString("failure", android.util.Log.getStackTraceString(e));
      test.finish(Activity.RESULT_CANCELED, out);
    } finally {
      if (activity != null)
        ui(() -> {
          set(activity, "playback", null);
          set(activity, "pendingSubscription", null);
          set(activity, "subscriptionController", previous[0]);
          set(activity, "api", previous[1]);
          set(activity, "settings", previous[2]);
        });
      if (controller[0] != null) controller[0].close();
      try {
        SubscriptionSettings.clear(test.getTargetContext(), scope);
      } catch (Exception ignored) {
      }
    }
  }
}
