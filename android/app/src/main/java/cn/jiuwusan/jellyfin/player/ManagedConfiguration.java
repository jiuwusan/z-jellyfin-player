package cn.jiuwusan.jellyfin.player;

import org.json.JSONObject;

/** Strict remote boundary; only existing supported runtime settings may enter the app. */
final class ManagedConfiguration {
  final String subscriptionId, serverInstanceId;
  final long revision;
  final int refreshSeconds, minClient;
  final JSONObject raw;
  private final ConnectionSettings runtime;

  private ManagedConfiguration(
      JSONObject raw,
      String id,
      String instance,
      long revision,
      int refresh,
      int min,
      ConnectionSettings runtime) {
    this.raw = raw;
    subscriptionId = id;
    serverInstanceId = instance;
    this.revision = revision;
    refreshSeconds = refresh;
    minClient = min;
    this.runtime = runtime;
  }

  private static int integer(JSONObject value, String key) throws Exception {
    Object n = value.get(key);
    if (!(n instanceof Integer) && !(n instanceof Long))
      throw new IllegalArgumentException("配置字段类型无效");
    long number = ((Number) n).longValue();
    if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE)
      throw new IllegalArgumentException("配置数值超出范围");
    return (int) number;
  }

  private static String string(JSONObject value, String key, int max) throws Exception {
    Object text = value.get(key);
    if (!(text instanceof String) || ((String) text).length() > max)
      throw new IllegalArgumentException("配置文本字段无效");
    return (String) text;
  }

  static ManagedConfiguration parse(JSONObject value, int clientVersion) {
    try {
      if (integer(value, "schemaVersion") != 2)
        throw new IllegalArgumentException("不支持的订阅协议，请更新 App");
      String id = string(value, "subscriptionId", 64);
      String instance = string(value, "serverInstanceId", 32);
      if (!id.matches("[0-9a-f]{32}") || !instance.matches("[0-9a-f]{32}"))
        throw new IllegalArgumentException("订阅标识无效");
      Object rev = value.get("revision");
      if (!(rev instanceof Integer) && !(rev instanceof Long) || ((Number) rev).longValue() < 1)
        throw new IllegalArgumentException("配置版本无效");
      long revision = ((Number) rev).longValue();
      int refresh = integer(value, "refreshIntervalSeconds"),
          min = integer(value, "minClientVersionCode");
      if (refresh < 300 || refresh > 86400 || min < 1 || min > clientVersion)
        throw new IllegalArgumentException("配置不兼容，请更新 App");
      JSONObject connection = value.getJSONObject("connection"),
          proxy = connection.getJSONObject("proxy"),
          jellyfin = connection.getJSONObject("jellyfin"),
          policy = value.getJSONObject("playback");
      if (!"http".equals(string(proxy, "type", 16))) throw new IllegalArgumentException("不支持的代理协议");
      String host = string(proxy, "host", 253);
      if (host.trim().isEmpty() || host.matches(".*[\\s/@?#\\\\].*"))
        throw new IllegalArgumentException("代理地址无效");
      String resolution = string(policy, "resolution", 16);
      String[] names = {"auto", "screen", "360p", "480p", "720p", "1080p", "4k", "8k"};
      int width = Integer.MIN_VALUE;
      for (int i = 0; i < names.length; i++)
        if (names[i].equals(resolution)) width = PlaybackPolicy.WIDTHS[i];
      if (width == Integer.MIN_VALUE) throw new IllegalArgumentException("不支持的分辨率");
      int rate = integer(policy, "totalBitrateBps");
      boolean supported = false;
      for (int option : PlaybackPolicy.RATES) if (rate == option) supported = true;
      if (!supported) throw new IllegalArgumentException("不支持的码率");
      ConnectionSettings settings =
          new ConnectionSettings(
              string(jellyfin, "baseUrl", 2048),
              host,
              integer(proxy, "port"),
              string(proxy, "username", 256),
              string(proxy, "password", 1024),
              string(jellyfin, "username", 256),
              string(jellyfin, "password", 1024),
              width,
              rate);
      return new ManagedConfiguration(
          new JSONObject(value.toString()), id, instance, revision, refresh, min, settings);
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("订阅配置不完整或类型无效");
    }
  }

  ConnectionSettings settings(ConnectionSettings previous) {
    boolean sameUser =
        previous != null
            && previous.server.equals(runtime.server)
            && previous.user.equals(runtime.user);
    return new ConnectionSettings(
        runtime.server.toString(),
        runtime.host,
        runtime.port,
        runtime.proxyUser,
        runtime.proxyPassword,
        runtime.user,
        runtime.password,
        runtime.resolution,
        runtime.bitrate,
        sameUser ? previous.audioLanguage : LanguagePreferences.DEFAULT,
        sameUser ? previous.subtitleLanguage : LanguagePreferences.DEFAULT);
  }

  boolean sameSource(ManagedConfiguration other) {
    return other != null
        && serverInstanceId.equals(other.serverInstanceId)
        && subscriptionId.equals(other.subscriptionId);
  }

  boolean sameConnection(ManagedConfiguration other) {
    return other != null && sameConnection(runtime, other.runtime);
  }

  static boolean sameConnection(ConnectionSettings a, ConnectionSettings b) {
    return a != null
        && b != null
        && a.server.equals(b.server)
        && a.host.equals(b.host)
        && a.port == b.port
        && a.proxyUser.equals(b.proxyUser)
        && a.proxyPassword.equals(b.proxyPassword)
        && a.user.equals(b.user)
        && a.password.equals(b.password);
  }

  boolean sameContent(ManagedConfiguration other) {
    return sameConnection(other)
        && runtime.resolution == other.runtime.resolution
        && runtime.bitrate == other.runtime.bitrate
        && minClient == other.minClient
        && refreshSeconds == other.refreshSeconds;
  }
}
