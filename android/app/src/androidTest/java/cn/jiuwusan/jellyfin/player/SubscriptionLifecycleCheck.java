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
      ManagedConfiguration config = ManagedConfiguration.parse(SubscriptionCheck.config(1), 57);
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
                  new SubscriptionClient(57, fixture),
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
          JSONObject rebuiltJson =
              SubscriptionCheck.config(1)
                  .put("serverInstanceId", "22222222222222222222222222222222");
          ManagedConfiguration rebuilt = ManagedConfiguration.parse(rebuiltJson, 57);
          SubscriptionController.Prepared rebuiltCandidate =
              new SubscriptionController.Prepared(
                  new SubscriptionSettings(
                      SubscriptionCheck.link(), rebuilt, "", System.currentTimeMillis(), false),
                  new JellyfinApi(rebuilt.settings(null), "fixture-device"),
                  0);
          invoke(
              activity,
              "applySubscription",
              new Class<?>[] {SubscriptionController.Prepared.class},
              rebuiltCandidate);
          check(
              field(activity, "pendingSubscription") == rebuiltCandidate,
              "Revalidated API with unchanged credentials was discarded during recovery");
          JSONObject json = SubscriptionCheck.config(2);
          json.getJSONObject("connection")
              .getJSONObject("proxy")
              .put("host", "changed.example.com");
          ManagedConfiguration changed = ManagedConfiguration.parse(json, 57);
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
          set(activity, "settings", ManagedConfiguration.parse(lower, 57).settings(null));
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

  void runConnectionRecovery() {
    Bundle out = new Bundle();
    String scope = "startup-recovery-" + UUID.randomUUID();
    SubscriptionController[] current = new SubscriptionController[1];
    String saved = test.getTargetContext().getSharedPreferences(SubscriptionSettings.STORE, 0)
        .getString("sealed", null);
    java.util.concurrent.atomic.AtomicInteger retries = new java.util.concurrent.atomic.AtomicInteger();
    try {
      activity = test.startActivitySync(new Intent(Intent.ACTION_MAIN)
          .setClassName(test.getTargetContext().getPackageName(), MainActivity.class.getName())
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
      ui(() -> {
        ((SubscriptionController) field(activity, "subscriptionController")).close();
        ((java.util.concurrent.ExecutorService) field(activity, "worker")).shutdownNow();
        ((Handler) field(activity, "ui")).removeCallbacks((Runnable) field(activity, "subscriptionTick"));
        set(activity, "worker", new java.util.concurrent.AbstractExecutorService() {
          public void execute(Runnable work) { retries.incrementAndGet(); }
          public void shutdown() {}
          public java.util.List<Runnable> shutdownNow() { return java.util.Collections.emptyList(); }
          public boolean isShutdown() { return false; }
          public boolean isTerminated() { return false; }
          public boolean awaitTermination(long time, java.util.concurrent.TimeUnit unit) { return true; }
        });
      });
      for (int mode = 0; mode < 5; mode++) {
        final int scenario = mode;
        retries.set(0);
        java.util.concurrent.atomic.AtomicInteger settled = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        ManagedConfiguration config = ManagedConfiguration.parse(SubscriptionCheck.config(1), 57);
        new SubscriptionSettings(SubscriptionCheck.link(), config, "\"fixture\"",
            System.currentTimeMillis(), false).save(test.getTargetContext(), scope);
        OkHttpClient transport = new OkHttpClient.Builder().addInterceptor(chain -> {
          if (chain.request().url().encodedPath().endsWith("/feedback"))
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture").body(ResponseBody.create("{}", MediaType.get("application/json"))).build();
          requests.incrementAndGet();
          started.countDown();
          if (scenario == 4) try {
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("fixture wait");
          } catch (InterruptedException e) { throw new java.io.IOException(e); }
          if (scenario == 2) throw new java.io.IOException("controlled offline");
          String body;
          try {
            JSONObject next = SubscriptionCheck.config(2);
            next.getJSONObject("connection").getJSONObject("proxy").put("host", "changed.example.com");
            body = next.toString();
          } catch (Exception e) { throw new java.io.IOException(e); }
          return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
              .code(scenario == 1 ? 200 : scenario == 3 ? 410 : 304).message("fixture")
              .header("ETag", "\"fixture-next\"")
              .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
        }).build();
        ui(() -> {
          try {
            current[0] = new SubscriptionController(test.getTargetContext(), (Handler) field(activity, "ui"),
                "fixture-device", new SubscriptionController.Listener() {
                  public void status(String value) {}
                  public void prepared(SubscriptionController.Prepared value) {
                    invoke(activity, "applySubscription", new Class<?>[]{SubscriptionController.Prepared.class}, value);
                  }
                  public void blocked() { invoke(activity, "showBlockedSubscription", new Class<?>[0]); }
                  public void settled() {
                    invoke(activity, "finishConnectionRecovery", new Class<?>[0]);
                    settled.incrementAndGet();
                  }
                }, new SubscriptionClient(57, transport), settings -> null, scope);
            set(activity, "subscriptionController", current[0]);
            set(activity, "settings", config.settings(null));
            set(activity, "api", null);
            invoke(activity, "recoverInitialConnection", new Class<?>[0]);
          } catch (Exception e) { throw new AssertionError(e); }
        });
        if (scenario == 4) {
          check(started.await(5, java.util.concurrent.TimeUnit.SECONDS), "Recovery request did not start");
          ui(() -> invoke(activity, "settingsPage", new Class<?>[]{String.class}, ""));
          release.countDown();
        }
        for (int i = 0; i < 300 && settled.get() == 0; i++) SystemClock.sleep(30);
        check(settled.get() == 1, "Sync did not settle in scenario " + scenario);
        int expected = scenario == 3 || scenario == 4 ? 0 : 1;
        check(requests.get() == 1 && retries.get() == expected, "Unexpected fetch/retry count in scenario " + scenario);
        ui(() -> invoke(activity, "finishConnectionRecovery", new Class<?>[0]));
        check(retries.get() == expected, "Recovery completion retried twice");
        if (scenario == 1) ui(() -> check(
            ((ConnectionSettings) field(activity, "settings")).host.equals("changed.example.com"),
            "Recovery did not select new configuration"));
        current[0].close();
      }
      check(java.util.Objects.equals(saved, test.getTargetContext()
          .getSharedPreferences(SubscriptionSettings.STORE, 0).getString("sealed", null)),
          "Real subscription cache changed");
      out.putString("result", "ALL_PASS: 304/new configuration/offline/revoked/navigation/one retry/cache preserved");
      test.finish(Activity.RESULT_OK, out);
    } catch (Throwable e) {
      out.putString("failure", android.util.Log.getStackTraceString(e));
      test.finish(Activity.RESULT_CANCELED, out);
    } finally {
      if (current[0] != null) current[0].close();
      try { SubscriptionSettings.clear(test.getTargetContext(), scope); } catch (Exception ignored) {}
    }
  }
}
