package cn.jiuwusan.jellyfin.player;

import android.content.Context;
import android.os.Build;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;

final class SubscriptionClient {
  static final class Link {
    final String raw, id, secret;
    final HttpUrl url;

    private Link(String raw, String id, String secret, HttpUrl url) {
      this.raw = raw;
      this.id = id;
      this.secret = secret;
      this.url = url;
    }

    static Link parse(String raw) {
      HttpUrl parsed = HttpUrl.parse(raw.trim());
      if (parsed == null
          || !"https".equals(parsed.scheme())
          || !parsed.username().isEmpty()
          || !parsed.password().isEmpty()
          || parsed.query() != null)
        throw new IllegalArgumentException("订阅链接必须为 HTTPS，且不能包含账号或查询参数");
      String secret = parsed.fragment();
      java.util.List<String> segments = parsed.pathSegments();
      int n = segments.size();
      if (n < 5
          || !"config".equals(segments.get(n - 1))
          || !segments.get(n - 2).matches("[0-9a-f]{32}")
          || !"subscriptions".equals(segments.get(n - 3))
          || !"v1".equals(segments.get(n - 4))
          || !"api".equals(segments.get(n - 5))
          || secret == null
          || !secret.matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("订阅链接格式无效");
      byte[] decoded;
      try {
        decoded = Base64.decode(secret, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
      } catch (Exception e) {
        throw new IllegalArgumentException("订阅令牌无效");
      }
      if (decoded.length != 32
          || !Base64.encodeToString(decoded, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP)
              .equals(secret)) throw new IllegalArgumentException("订阅令牌无效");
      return new Link(
          parsed.toString(),
          segments.get(n - 2),
          secret,
          parsed.newBuilder().fragment(null).build());
    }

    @Override
    public String toString() {
      return "SubscriptionLink[redacted]";
    }
  }

  static final class Revoked extends IOException {
    Revoked() {
      super("订阅已停用或链接已失效，请更新订阅");
    }
  }

  static final class Unsupported extends IOException {
    Unsupported() {
      super("订阅配置不兼容，请更新 App");
    }
  }

  static final class Fetch {
    final ManagedConfiguration configuration;
    final String etag;
    final boolean notModified;

    Fetch(ManagedConfiguration c, String etag, boolean same) {
      configuration = c;
      this.etag = etag;
      notModified = same;
    }
  }

  private final int version;
  private final OkHttpClient http;

  SubscriptionClient(int version) {
    this(version, new OkHttpClient());
  }

  SubscriptionClient(int version, OkHttpClient base) {
    this.version = version;
    http =
        base.newBuilder()
            .proxy(Proxy.NO_PROXY)
            .proxyAuthenticator(okhttp3.Authenticator.NONE)
            .authenticator(okhttp3.Authenticator.NONE)
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .cache(null)
            .build();
  }

  Proxy directProxy() {
    return http.proxy();
  }

  static int appVersion(Context context) {
    try {
      android.content.pm.PackageInfo info =
          context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
      return Build.VERSION.SDK_INT >= 28 ? (int) info.getLongVersionCode() : info.versionCode;
    } catch (Exception e) {
      return 0;
    }
  }

  Fetch fetch(Link link, ManagedConfiguration current, String etag) throws IOException {
    Request.Builder request =
        new Request.Builder()
            .url(link.url)
            .header("Authorization", "Bearer " + link.secret)
            .header("Accept", "application/json")
            .header("X-Client-Version-Code", Integer.toString(version));
    if (current != null && etag != null && !etag.isEmpty()) request.header("If-None-Match", etag);
    try (Response response = http.newCall(request.build()).execute()) {
      if (response.code() == 401 || response.code() == 403 || response.code() == 410)
        throw new Revoked();
      if (response.code() == 426) throw new Unsupported();
      if (response.code() == 304) {
        if (current == null) throw new IOException("订阅缺少本地配置");
        return new Fetch(current, etag, true);
      }
      if (response.code() != 200 || response.body() == null) throw new IOException("订阅服务暂时不可用");
      if (response.body().contentLength() > 65536) throw new IOException("订阅响应过大");
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (InputStream in = response.body().byteStream()) {
        byte[] block = new byte[4096];
        int read;
        while ((read = in.read(block)) != -1) {
          if (bytes.size() + read > 65536) throw new IOException("订阅响应过大");
          bytes.write(block, 0, read);
        }
      }
      ManagedConfiguration value;
      try {
        value =
            ManagedConfiguration.parse(
                new JSONObject(bytes.toString(StandardCharsets.UTF_8.name())), version, link.id);
      } catch (Exception e) {
        throw new Unsupported();
      }
      String received = response.header("ETag", "");
      if (received.length() > 256) throw new Unsupported();
      return new Fetch(value, received, false);
    }
  }

  void feedback(Link link, String device, long revision, String status, String code) {
    try {
      JSONObject value =
          new JSONObject()
              .put("deviceId", device)
              .put("platform", "android")
              .put("versionCode", version)
              .put("revision", revision)
              .put("status", status)
              .put("errorCode", code);
      java.util.List<String> path = link.url.pathSegments();
      HttpUrl endpoint = link.url.newBuilder().setPathSegment(path.size() - 1, "feedback").build();
      Request request =
          new Request.Builder()
              .url(endpoint)
              .header("Authorization", "Bearer " + link.secret)
              .post(RequestBody.create(value.toString(), MediaType.get("application/json")))
              .build();
      try (Response ignored = http.newCall(request).execute()) {}
    } catch (Exception ignored) {
      /* Feedback must not affect playback or expose configuration. */
    }
  }
}
