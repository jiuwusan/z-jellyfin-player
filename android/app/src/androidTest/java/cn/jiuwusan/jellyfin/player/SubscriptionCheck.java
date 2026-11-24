package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.util.Base64;
import java.net.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONObject;

final class SubscriptionCheck {
  private final Instrumentation test;

  SubscriptionCheck(Instrumentation test) {
    this.test = test;
  }

  private void check(boolean ok, String why) {
    if (!ok) throw new AssertionError(why);
  }

  static String link() {
    return "https://hub.example.com/prefix/api/v1/subscriptions/00000000000000000000000000000001/config#"
        + Base64.encodeToString(new byte[32], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
  }

  static JSONObject config(int revision) throws Exception {
    return new JSONObject(
        "{\"schemaVersion\":1,\"subscriptionId\":\"00000000000000000000000000000001\",\"revision\":"
            + revision
            + ",\"minClientVersionCode\":56,\"refreshIntervalSeconds\":900,\"connection\":{\"proxy\":{\"type\":\"http\",\"host\":\"proxy.example.com\",\"port\":8080,\"username\":\"fixture\",\"password\":\"fixture-proxy\"},\"jellyfin\":{\"baseUrl\":\"http://media.internal:8096/jellyfin\",\"username\":\"fixture\",\"password\":\"fixture-only\"}},\"playback\":{\"resolution\":\"1080p\",\"totalBitrateBps\":4000000}}");
  }

  void run() {
    Bundle out = new Bundle();
    String scope = "subscription-probe-" + UUID.randomUUID();
    try {
      SubscriptionClient.Link link = SubscriptionClient.Link.parse(link());
      check(!link.url.toString().contains("#"), "Secret remained in request URL");
      try {
        SubscriptionClient.Link.parse(link().replace("https:", "http:"));
        throw new AssertionError("HTTP subscription accepted");
      } catch (IllegalArgumentException expected) {
      }
      try {
        SubscriptionClient.Link.parse(
            link().replace("hub.example.com", "user:pass@hub.example.com"));
        throw new AssertionError("URL userinfo accepted");
      } catch (IllegalArgumentException expected) {
      }
      ManagedConfiguration config = ManagedConfiguration.parse(config(1), 56, link.id);
      ConnectionSettings runtime = config.settings(null);
      check(runtime.resolution == 1920 && runtime.bitrate == 4000000, "Quality mapping changed");
      JSONObject invalid = config(1);
      invalid.getJSONObject("playback").put("totalBitrateBps", 123);
      try {
        ManagedConfiguration.parse(invalid, 56, link.id);
        throw new AssertionError("Unsupported remote bitrate defaulted silently");
      } catch (IllegalArgumentException expected) {
      }
      AtomicInteger count = new AtomicInteger();
      OkHttpClient fixture =
          new OkHttpClient.Builder()
              .addInterceptor(chain -> {
                check(
                    chain.request().url().fragment() == null
                        && chain.request().header("Authorization").startsWith("Bearer "),
                    "Credential transport invalid");
                check(
                    chain.request().header("Proxy-Authorization") == null,
                    "Proxy credentials leaked to Hub");
                int n = count.incrementAndGet();
                String body;
                try {
                  body = config(1).toString();
                } catch (Exception e) {
                  throw new java.io.IOException(e);
                }
                return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(n == 1 ? 200 : n == 2 ? 304 : 410)
                    .message("fixture")
                    .header("ETag", "\"fixture-1\"")
                    .body(ResponseBody.create(body, MediaType.get("application/json")))
                    .build();
              })
              .build();
      SubscriptionClient client = new SubscriptionClient(56, fixture);
      check(client.directProxy() == Proxy.NO_PROXY, "Hub client depends on configured proxy");
      SubscriptionClient.Fetch fetched = client.fetch(link, null, "");
      check(fetched.configuration.revision == 1, "Config not parsed");
      check(
          client.fetch(link, fetched.configuration, fetched.etag).notModified, "304 not preserved");
      try {
        client.fetch(link, fetched.configuration, fetched.etag);
        throw new AssertionError("Disabled response accepted");
      } catch (SubscriptionClient.Revoked expected) {
      }
      SubscriptionSettings state =
          new SubscriptionSettings(link.raw, fetched.configuration, fetched.etag, 12345, false);
      state.save(test.getTargetContext(), scope);
      check(
          !test.getTargetContext()
              .getSharedPreferences(scope, 0)
              .getString("sealed", "")
              .contains("fixture"),
          "Unencrypted configuration stored");
      SubscriptionSettings loaded = SubscriptionSettings.load(test.getTargetContext(), scope);
      check(
          loaded.configuration.revision == 1 && loaded.link.equals(state.link),
          "Encrypted state roundtrip failed");
      out.putString(
          "result",
          "ALL_PASS: HTTPS-only / fragment bearer / direct bootstrap / strict schema / quality"
              + " mapping / 304 / revocation / isolated encrypted cache");
      test.finish(Activity.RESULT_OK, out);
    } catch (Throwable e) {
      out.putString("failure", android.util.Log.getStackTraceString(e));
      test.finish(Activity.RESULT_CANCELED, out);
    } finally {
      try {
        SubscriptionSettings.clear(test.getTargetContext(), scope);
      } catch (Exception ignored) {
      }
    }
  }
}
