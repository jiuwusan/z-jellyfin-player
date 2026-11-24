package cn.jiuwusan.jellyfin.player;

import android.content.Context;
import android.os.Handler;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Main-thread coordinator. Candidate download/login runs separately from media/reporting work. */
final class SubscriptionController {
  interface Listener {
    void status(String text);

    void prepared(Prepared value);

    void blocked();
  }

  interface Validator {
    JellyfinApi validate(ConnectionSettings settings) throws Exception;
  }

  static final class Prepared {
    final SubscriptionSettings state;
    final JellyfinApi api;
    final long request;

    Prepared(SubscriptionSettings state, JellyfinApi api, long request) {
      this.state = state;
      this.api = api;
      this.request = request;
    }
  }

  private final Context context;
  private final Handler main;
  private final Listener listener;
  private final SubscriptionClient client;
  private final Validator validator;
  private final String device, scope;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private SubscriptionSettings state;
  private Prepared retained;
  private long epoch, nextAttempt;
  private int failures;
  private boolean busy, closed, unreadableCache;

  SubscriptionController(Context context, Handler main, String device, Listener listener)
      throws Exception {
    this(
        context,
        main,
        device,
        listener,
        new SubscriptionClient(SubscriptionClient.appVersion(context)),
        settings -> {
          JellyfinApi api = new JellyfinApi(settings, device);
          api.login();
          return api;
        },
        SubscriptionSettings.STORE);
  }

  SubscriptionController(
      Context context,
      Handler main,
      String device,
      Listener listener,
      SubscriptionClient client,
      Validator validator,
      String scope)
      throws Exception {
    this.context = context;
    this.main = main;
    this.device = device;
    this.listener = listener;
    this.client = client;
    this.validator = validator;
    this.scope = scope;
    try {
      state = SubscriptionSettings.load(context, scope);
    } catch (Exception ignored) {
      unreadableCache = true;
      listener.status("订阅缓存无法读取，请重新导入订阅；不会恢复旧账号配置");
    }
  }

  SubscriptionSettings state() {
    return state;
  }

  boolean hasUnreadableCache() {
    return unreadableCache;
  }

  boolean busy() {
    return busy;
  }

  boolean blocked() {
    return state != null && state.blocked;
  }

  void importLink(String raw) {
    SubscriptionClient.Link link = SubscriptionClient.Link.parse(raw);
    retained = null;
    start(link, true);
  }

  void sync(boolean force) {
    if (state == null || busy || closed) return;
    long now = System.currentTimeMillis();
    if (!force
        && (now < nextAttempt
            || now - state.checkedAt < state.configuration.refreshSeconds * 1000L
                && !state.blocked)) return;
    start(SubscriptionClient.Link.parse(state.link), false);
  }

  private void start(SubscriptionClient.Link link, boolean importing) {
    if (closed) return;
    final long request = ++epoch;
    busy = true;
    listener.status("正在同步订阅…");
    SubscriptionSettings previous = state;
    Prepared waiting = retained;
    executor.execute(() -> {
      try {
        boolean same = previous != null && previous.link.equals(link.raw);
        SubscriptionClient.Fetch fetched =
            client.fetch(
                link,
                same ? previous.configuration : null,
                same && !previous.blocked ? previous.etag : "");
        if (same
            && (fetched.configuration.revision < previous.configuration.revision
                || fetched.configuration.revision == previous.configuration.revision
                    && !fetched.configuration.sameContent(previous.configuration)))
          throw new SubscriptionClient.Unsupported();
        if (same
            && waiting != null
            && !fetched.notModified
            && (fetched.configuration.revision < waiting.state.configuration.revision
                || fetched.configuration.revision == waiting.state.configuration.revision
                    && !fetched.configuration.sameContent(waiting.state.configuration)))
          throw new SubscriptionClient.Unsupported();
        if (fetched.notModified) {
          SubscriptionSettings checked =
              new SubscriptionSettings(
                  link.raw,
                  previous.configuration,
                  fetched.etag,
                  System.currentTimeMillis(),
                  false);
          main.post(() -> {
            if (!active(request)) return;
            try {
              checked.save(context, scope);
              state = checked;
              success();
              listener.status("订阅已是最新版本");
            } catch (Exception e) {
              failed(request, "无法保存订阅状态");
            }
          });
          return;
        }
        ConnectionSettings candidate =
            fetched.configuration.settings(
                previous == null ? null : previous.configuration.settings(null));
        JellyfinApi verified =
            same && fetched.configuration.sameConnection(previous.configuration)
                ? null
                : validator.validate(candidate);
        SubscriptionSettings next =
            new SubscriptionSettings(
                link.raw, fetched.configuration, fetched.etag, System.currentTimeMillis(), false);
        main.post(() -> {
          if (!active(request)) return;
          busy = false;
          retained = null;
          listener.prepared(new Prepared(next, verified, request));
        });
      } catch (SubscriptionClient.Revoked e) {
        main.post(() -> {
          boolean sameActiveLink = state != null && state.link.equals(link.raw);
          if (closed || !active(request) && !sameActiveLink) return;
          if (active(request)) busy = false;
          if (sameActiveLink) {
            retained = null;
            try {
              state =
                  new SubscriptionSettings(
                      state.link, state.configuration, state.etag, state.checkedAt, true);
              state.save(context, scope);
            } catch (Exception ignored) {
            }
            nextAttempt = System.currentTimeMillis() + 900_000;
            listener.status("订阅已停用或链接失效，请更新订阅");
            listener.blocked();
          } else failed(request, "订阅链接无效，原有配置已保留");
        });
      } catch (SubscriptionClient.Unsupported e) {
        main.post(() -> failed(request, "订阅配置不兼容，请更新 App；已有配置已保留"));
      } catch (Exception e) {
        main.post(() -> failed(request, "订阅或 Jellyfin 连接失败，已有配置已保留，请稍后重试"));
      }
    });
  }

  private boolean active(long request) {
    return !closed && request == epoch;
  }

  private void failed(long request, String message) {
    if (!active(request)) return;
    busy = false;
    failures = Math.min(8, failures + 1);
    nextAttempt = System.currentTimeMillis() + Math.min(900_000, 5000L * (1L << failures));
    listener.status(message);
  }

  private void success() {
    busy = false;
    failures = 0;
    nextAttempt =
        System.currentTimeMillis()
            + (state == null
                ? 0
                : (long) (state.configuration.refreshSeconds * 1000L * (.9 + Math.random() * .2)));
  }

  boolean isCurrent(Prepared prepared) {
    return !closed && (prepared.request == epoch || retained == prepared);
  }

  void activate(Prepared prepared) throws Exception {
    if (!isCurrent(prepared)) throw new IllegalStateException("候选配置已失效");
    prepared.state.save(context, scope);
    state = prepared.state;
    epoch++;
    retained = null;
    unreadableCache = false;
    success();
    listener.status("订阅 v" + state.configuration.revision + " 已应用");
    feedback(state, "applied", "");
  }

  void pending(Prepared prepared) {
    if (!isCurrent(prepared)) return;
    retained = prepared;
    listener.status("新配置已验证，将在退出播放后应用");
    nextAttempt = System.currentTimeMillis() + 900_000;
    feedback(prepared.state, "pending", "");
  }

  private void feedback(SubscriptionSettings value, String status, String code) {
    executor.execute(() ->
        client.feedback(
            SubscriptionClient.Link.parse(value.link),
            device,
            value.configuration.revision,
            status,
            code));
  }

  void clear() throws Exception {
    epoch++;
    retained = null;
    busy = false;
    SubscriptionSettings.clear(context, scope);
    state = null;
    unreadableCache = false;
    nextAttempt = 0;
  }

  void close() {
    closed = true;
    retained = null;
    epoch++;
    executor.shutdownNow();
  }
}
