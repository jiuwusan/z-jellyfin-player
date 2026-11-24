package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONObject;

final class SubscriptionControllerCheck {
  private final Instrumentation test;

  SubscriptionControllerCheck(Instrumentation test) {
    this.test = test;
  }

  private void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private void ui(Runnable work) {
    AtomicReference<Throwable> error = new AtomicReference<>();
    test.runOnMainSync(() -> {
      try {
        work.run();
      } catch (Throwable e) {
        error.set(e);
      }
    });
    if (error.get() != null) throw new AssertionError(error.get());
  }

  private void idle(SubscriptionController value) {
    boolean[] done = {false};
    for (int i = 0; i < 300 && !done[0]; i++) {
      ui(() -> done[0] = !value.busy());
      SystemClock.sleep(30);
    }
    check(done[0], "Subscription request did not finish");
  }

  void run() {
    Bundle out = new Bundle();
    String scope = "subscription-controller-" + UUID.randomUUID();
    SubscriptionController[] instance = new SubscriptionController[1];
    String oldLegacy =
        test.getTargetContext().getSharedPreferences("connection", 0).getString("sealed", null);
    try {
      AtomicInteger mode = new AtomicInteger(), validated = new AtomicInteger();
      CountDownLatch revocationStarted = new CountDownLatch(1),
          releaseRevocation = new CountDownLatch(1);
      AtomicReference<SubscriptionController.Prepared> pending = new AtomicReference<>();
      OkHttpClient transport =
          new OkHttpClient.Builder()
              .addInterceptor(chain -> {
                if (chain.request().url().encodedPath().endsWith("/feedback"))
                  return new Response.Builder()
                      .request(chain.request())
                      .protocol(Protocol.HTTP_1_1)
                      .code(200)
                      .message("fixture")
                      .body(ResponseBody.create("{}", MediaType.get("application/json")))
                      .build();
                int testMode = mode.get();
                if (testMode == 7) {
                  revocationStarted.countDown();
                  try {
                    if (!releaseRevocation.await(5, TimeUnit.SECONDS))
                      throw new java.io.IOException("Fixture timeout");
                  } catch (InterruptedException e) {
                    throw new java.io.IOException(e);
                  }
                }
                if (testMode == 2) throw new java.io.IOException("Controlled offline");
                JSONObject json;
                try {
                  json = SubscriptionCheck.config(testMode == 4 ? 2 : testMode == 6 ? 3 : 1);
                  if (testMode == 8 || testMode == 10)
                    json.put("serverInstanceId", "22222222222222222222222222222222")
                        .put("subscriptionId", "00000000000000000000000000000002");
                  if (testMode == 9)
                    json.put("serverInstanceId", "33333333333333333333333333333333");
                  if (testMode == 12)
                    json.put("serverInstanceId", "44444444444444444444444444444444");
                  if (testMode == 10)
                    json.getJSONObject("playback").put("totalBitrateBps", 3000000);
                  if (testMode == 3) json.getJSONObject("playback").put("totalBitrateBps", 123);
                  if (testMode == 4)
                    json.getJSONObject("playback")
                        .put("resolution", "720p")
                        .put("totalBitrateBps", 3000000);
                  if (testMode == 6)
                    json.getJSONObject("connection")
                        .getJSONObject("proxy")
                        .put("host", "changed.example.com");
                } catch (Exception e) {
                  throw new java.io.IOException(e);
                }
                return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(testMode == 1 ? 304 : testMode == 5 || testMode == 7 ? 410 : 200)
                    .message("fixture")
                    .header("ETag", "\"fixture\"")
                    .body(ResponseBody.create(json.toString(), MediaType.get("application/json")))
                    .build();
              })
              .build();
      ui(() -> {
        try {
          instance[0] =
              new SubscriptionController(
                  test.getTargetContext(),
                  new Handler(Looper.getMainLooper()),
                  "fixture-device",
                  new SubscriptionController.Listener() {
                    public void status(String value) {}

                    public void prepared(SubscriptionController.Prepared p) {
                      pending.set(p);
                    }

                    public void blocked() {}
                  },
                  new SubscriptionClient(57, transport),
                  settings -> {
                    validated.incrementAndGet();
                    if (mode.get() == 6 || mode.get() == 9)
                      throw new java.io.IOException("Controlled invalid connection");
                    return new JellyfinApi(settings, "fixture-device");
                  },
                  scope);
        } catch (Exception e) {
          throw new AssertionError(e);
        }
      });
      SubscriptionController controller = instance[0];
      ui(() -> controller.importLink(SubscriptionCheck.link()));
      idle(controller);
      check(pending.get() != null && validated.get() == 1, "Initial candidate not validated");
      ui(() -> check(controller.state() == null, "Uncommitted candidate replaced active state"));
      ui(() -> {
        try {
          controller.activate(pending.get());
        } catch (Exception e) {
          throw new AssertionError(e);
        }
      });
      mode.set(1);
      pending.set(null);
      ui(() -> controller.sync(true));
      idle(controller);
      check(
          pending.get() == null && validated.get() == 1,
          "304 revalidated or reapplied configuration");
      for (int failureMode : new int[] {2, 3}) {
        mode.set(failureMode);
        ui(() -> controller.sync(true));
        idle(controller);
        ui(() ->
            check(
                controller.state().configuration.revision == 1 && !controller.blocked(),
                "Failure destroyed last-good state"));
      }
      mode.set(4);
      ui(() -> controller.sync(true));
      idle(controller);
      check(pending.get() != null && validated.get() == 1, "Quality-only update reconnected");
      ui(() -> controller.pending(pending.get()));
      mode.set(2);
      ui(() -> controller.sync(true));
      idle(controller);
      ui(() ->
          check(
              controller.isCurrent(pending.get()),
              "Offline refresh invalidated waiting configuration"));
      ui(() ->
          check(
              controller.state().configuration.revision == 1,
              "Pending quality update applied prematurely"));
      ui(() -> {
        try {
          controller.activate(pending.get());
        } catch (Exception e) {
          throw new AssertionError(e);
        }
      });
      SubscriptionController.Prepared obsolete = pending.get();
      mode.set(6);
      pending.set(null);
      ui(() -> controller.sync(true));
      idle(controller);
      ui(() -> {
        check(
            controller.state().configuration.revision == 2,
            "Bad credentials replaced valid config");
        try {
          controller.activate(obsolete);
          throw new AssertionError("Obsolete candidate committed");
        } catch (IllegalStateException expected) {
        } catch (Exception e) {
          throw new AssertionError(e);
        }
      });
      mode.set(0);
      ui(() -> controller.sync(true));
      idle(controller);
      ui(() -> check(controller.state().configuration.revision == 2, "Lower revision accepted"));
      mode.set(4);
      ui(() -> controller.sync(true));
      idle(controller);
      ui(() -> controller.pending(pending.get()));
      mode.set(7);
      ui(() -> controller.sync(true));
      check(revocationStarted.await(5, TimeUnit.SECONDS), "Revocation fixture did not start");
      ui(() -> {
        try {
          controller.activate(pending.get());
        } catch (Exception e) {
          throw new AssertionError(e);
        }
      });
      releaseRevocation.countDown();
      boolean[] revoked = {false};
      for (int i = 0; i < 100 && !revoked[0]; i++) {
        ui(() -> revoked[0] = controller.blocked());
        SystemClock.sleep(30);
      }
      check(revoked[0], "Same-link revocation lost when waiting configuration activated");
      mode.set(4);
      ui(() -> controller.sync(true));
      idle(controller);
      ui(() -> {
        try {
          controller.activate(pending.get());
        } catch (Exception e) {
          throw new AssertionError(e);
        }
      });
      pending.set(null);
      mode.set(0);
      ui(() ->
          controller.importLink(
              SubscriptionCheck.link().replace("hub.example.com", "other.example.com")));
      idle(controller);
      ui(() ->
          check(
              pending.get() == null && controller.state().configuration.revision == 2,
              "Different URL bypassed same-source downgrade protection"));
      int beforeRecovery = validated.get();
      pending.set(null);
      mode.set(9);
      ui(() -> controller.sync(true));
      idle(controller);
      ui(() ->
          check(
              controller.state().configuration.revision == 2 && pending.get() == null,
              "Failed recovery replaced last-good configuration"));
      check(validated.get() == beforeRecovery + 1, "New instance skipped Jellyfin validation");
      mode.set(8);
      ui(() -> controller.sync(true));
      idle(controller);
      check(
          pending.get() != null
              && pending.get().api != null
              && validated.get() == beforeRecovery + 2,
          "Rebuilt service did not produce a validated candidate");
      ui(() -> {
        try {
          controller.activate(pending.get());
        } catch (Exception e) {
          throw new AssertionError(e);
        }
        check(
            controller.state().configuration.revision == 1
                && controller.state().link.equals(SubscriptionCheck.link()),
            "Recovery required a new link or rejected rebuilt revision 1");
      });
      pending.set(null);
      mode.set(10);
      ui(() -> controller.sync(true));
      idle(controller);
      ui(() ->
          check(
              pending.get() == null
                  && controller.state().configuration.settings(null).bitrate == 4000000,
              "Same-instance same-version content change was accepted"));
      mode.set(5);
      byte[] another = new byte[32];
      another[0] = 1;
      String alternate =
          SubscriptionCheck.link().substring(0, SubscriptionCheck.link().indexOf('#') + 1)
              + Base64.encodeToString(
                  another, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
      ui(() -> controller.importLink(alternate));
      idle(controller);
      ui(() ->
          check(!controller.blocked(), "Bad replacement link revoked the original subscription"));
      ui(() -> controller.sync(true));
      idle(controller);
      ui(() -> check(controller.blocked(), "Active revoked link remained usable"));
      check(
          SubscriptionSettings.load(test.getTargetContext(), scope).blocked,
          "Revocation state not persisted");
      pending.set(null);
      mode.set(12);
      ui(() -> controller.sync(true));
      idle(controller);
      check(
          pending.get() != null && pending.get().api != null,
          "Restored token could not recover blocked device");
      ui(() -> {
        try {
          controller.activate(pending.get());
        } catch (Exception e) {
          throw new AssertionError(e);
        }
        check(
            !controller.blocked() && controller.state().link.equals(SubscriptionCheck.link()),
            "Recovery required subscription re-entry after an unavailable token");
      });
      ui(() -> {
        try {
          controller.clear();
        } catch (Exception e) {
          throw new AssertionError(e);
        }
      });
      check(
          SubscriptionSettings.load(test.getTargetContext(), scope) == null,
          "Clear retained managed credentials");
      controller.close();
      test.getTargetContext()
          .getSharedPreferences(scope, 0)
          .edit()
          .putString("sealed", "damaged-cache")
          .commit();
      ui(() -> {
        try {
          instance[0] =
              new SubscriptionController(
                  test.getTargetContext(),
                  new Handler(Looper.getMainLooper()),
                  "fixture-device",
                  new SubscriptionController.Listener() {
                    public void status(String value) {}

                    public void prepared(SubscriptionController.Prepared p) {
                      pending.set(p);
                    }

                    public void blocked() {}
                  },
                  new SubscriptionClient(57, transport),
                  settings -> new JellyfinApi(settings, "fixture-device"),
                  scope);
          check(instance[0].state() == null, "Unreadable cache was treated as valid");
        } catch (Exception e) {
          throw new AssertionError("Unreadable cache prevented subscription recovery", e);
        }
      });
      check(
          java.util.Objects.equals(
              oldLegacy,
              test.getTargetContext()
                  .getSharedPreferences("connection", 0)
                  .getString("sealed", null)),
          "Legacy connection changed");
      out.putString(
          "result",
          "ALL_PASS: validated commit / 304 no reconnect / offline and invalid config fallback /"
              + " quality-only update / obsolete candidate rejected / invalid replacement isolation"
              + " / persisted revocation and clear / rebuilt instance recovery at revision 1"
              + " / failed recovery preserves cache / same-instance downgrade protection");
      test.finish(Activity.RESULT_OK, out);
    } catch (Throwable e) {
      out.putString("failure", android.util.Log.getStackTraceString(e));
      test.finish(Activity.RESULT_CANCELED, out);
    } finally {
      if (instance[0] != null) instance[0].close();
      try {
        SubscriptionSettings.clear(test.getTargetContext(), scope);
      } catch (Exception ignored) {
      }
    }
  }
}
