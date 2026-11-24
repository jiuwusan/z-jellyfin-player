package cn.jiuwusan.jellyfin.player;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

final class SubscriptionSettings {
  static final String STORE = "managed-subscription";
  final String link, etag;
  final ManagedConfiguration configuration;
  final long checkedAt;
  final boolean blocked;

  SubscriptionSettings(
      String link, ManagedConfiguration config, String etag, long checkedAt, boolean blocked) {
    this.link = link;
    configuration = config;
    this.etag = etag;
    this.checkedAt = checkedAt;
    this.blocked = blocked;
  }

  void save(Context context) throws Exception {
    save(context, STORE);
  }

  void save(Context context, String scope) throws Exception {
    JSONObject json =
        new JSONObject()
            .put("link", link)
            .put("configuration", configuration.raw)
            .put("etag", etag)
            .put("checkedAt", checkedAt)
            .put("blocked", blocked);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key(scope, true));
    cipher.updateAAD(scope.getBytes(StandardCharsets.UTF_8));
    String sealed =
        Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
            + "."
            + Base64.encodeToString(
                cipher.doFinal(json.toString().getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    if (!context.getSharedPreferences(scope, 0).edit().putString("sealed", sealed).commit())
      throw new IllegalStateException("无法保存订阅配置");
  }

  static SubscriptionSettings load(Context context) throws Exception {
    return load(context, STORE);
  }

  static SubscriptionSettings load(Context context, String scope) throws Exception {
    String sealed = context.getSharedPreferences(scope, 0).getString("sealed", null);
    if (sealed == null) return null;
    String[] pieces = sealed.split("\\.", 2);
    if (pieces.length != 2) throw new IllegalStateException("订阅缓存格式无效");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        key(scope, false),
        new GCMParameterSpec(128, Base64.decode(pieces[0], Base64.DEFAULT)));
    cipher.updateAAD(scope.getBytes(StandardCharsets.UTF_8));
    JSONObject json =
        new JSONObject(
            new String(
                cipher.doFinal(Base64.decode(pieces[1], Base64.DEFAULT)), StandardCharsets.UTF_8));
    SubscriptionClient.Link link = SubscriptionClient.Link.parse(json.getString("link"));
    return new SubscriptionSettings(
        link.raw,
        ManagedConfiguration.parse(
            json.getJSONObject("configuration"), SubscriptionClient.appVersion(context), link.id),
        json.optString("etag", ""),
        json.optLong("checkedAt", 0),
        json.optBoolean("blocked", false));
  }

  static void clear(Context context, String scope) throws Exception {
    if (!context.getSharedPreferences(scope, 0).edit().clear().commit())
      throw new IllegalStateException("无法清除订阅");
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    String alias = "jellyfin-managed-" + scope;
    if (store.containsAlias(alias)) store.deleteEntry(alias);
  }

  private static SecretKey key(String scope, boolean create) throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    String alias = "jellyfin-managed-" + scope;
    SecretKey existing = (SecretKey) store.getKey(alias, null);
    if (existing != null) return existing;
    if (!create) throw new IllegalStateException("订阅缓存密钥不可用");
    KeyGenerator generator =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
    generator.init(
        new KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setKeySize(256)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build());
    return generator.generateKey();
  }
}
