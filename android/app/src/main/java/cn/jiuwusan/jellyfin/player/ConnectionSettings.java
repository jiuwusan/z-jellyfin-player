package cn.jiuwusan.jellyfin.player;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import okhttp3.HttpUrl;

final class ConnectionSettings {
    private static final String ALIAS = "jellyfin-probe-settings";
    final HttpUrl server;
    final String host;
    final int port;
    final String proxyUser;
    final String proxyPassword;
    final String user;
    final String password;
    final int resolution, bitrate;
    final String audioLanguage, subtitleLanguage;

    ConnectionSettings(String server, String host, int port, String proxyUser, String proxyPassword,
                       String user, String password, int resolution, int bitrate) {
        this(server, host, port, proxyUser, proxyPassword, user, password, resolution, bitrate,
                LanguagePreferences.DEFAULT, LanguagePreferences.DEFAULT);
    }

    ConnectionSettings(String server, String host, int port, String proxyUser, String proxyPassword,
                       String user, String password, int resolution, int bitrate, String audioLanguage, String subtitleLanguage) {
        this.server = HttpUrl.parse(server.trim().replaceAll("/+$", "") + "/");
        if (this.server == null || !this.server.username().isEmpty() || !this.server.password().isEmpty()
                || this.server.query() != null || this.server.fragment() != null) {
            throw new IllegalArgumentException("服务器地址无效");
        }
        this.host = host.trim();
        boolean direct = this.host.isEmpty() && port == 0 && proxyUser.trim().isEmpty() && proxyPassword.isEmpty();
        if ((!direct && (this.host.isEmpty() || port < 1 || port > 65535)) || user.trim().isEmpty() || password.isEmpty()) {
            throw new IllegalArgumentException("检查代理地址、端口和 Jellyfin 账号");
        }
        this.port = port;
        this.proxyUser = proxyUser.trim();
        this.proxyPassword = proxyPassword;
        this.user = user.trim();
        this.password = password;
        this.resolution = PlaybackPolicy.WIDTHS[PlaybackPolicy.optionIndex(PlaybackPolicy.WIDTHS, resolution, PlaybackPolicy.WIDTH)];
        this.bitrate = PlaybackPolicy.RATES[PlaybackPolicy.optionIndex(PlaybackPolicy.RATES, bitrate, PlaybackPolicy.TOTAL_BPS)];
        this.audioLanguage = audioLanguage == null ? LanguagePreferences.DEFAULT : audioLanguage.trim();
        this.subtitleLanguage = subtitleLanguage == null ? LanguagePreferences.DEFAULT : subtitleLanguage.trim();
    }

    boolean hasProxy() { return !host.isEmpty(); }

    void save(Context ctx) throws Exception {
        JSONObject value = toJson();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        String encoded = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "."
                + Base64.encodeToString(cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        if (!ctx.getSharedPreferences("connection", 0).edit().putString("sealed", encoded).commit()) {
            throw new IllegalStateException("保存配置失败");
        }
    }

    static ConnectionSettings load(Context ctx) throws Exception {
        String sealed = ctx.getSharedPreferences("connection", 0).getString("sealed", null);
        if (sealed == null) return null;
        String[] values = sealed.split("\\.", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false),
                new GCMParameterSpec(128, Base64.decode(values[0], Base64.DEFAULT)));
        JSONObject data = new JSONObject(new String(cipher.doFinal(
                Base64.decode(values[1], Base64.DEFAULT)), StandardCharsets.UTF_8));
        return fromJson(data);
    }

    JSONObject toJson() throws Exception {
        return new JSONObject().put("server", server.toString()).put("host", host)
                .put("port", port).put("proxyUser", proxyUser).put("proxyPassword", proxyPassword)
                .put("user", user).put("password", password).put("resolution", resolution).put("bitrate", bitrate)
                .put("audioLanguage", audioLanguage).put("subtitleLanguage", subtitleLanguage);
    }

    static ConnectionSettings fromJson(JSONObject data) throws Exception {
        return new ConnectionSettings(data.getString("server"), data.getString("host"), data.getInt("port"),
                data.getString("proxyUser"), data.getString("proxyPassword"), data.getString("user"), data.getString("password"),
                data.optInt("resolution", PlaybackPolicy.WIDTH), data.optInt("bitrate", PlaybackPolicy.TOTAL_BPS),
                data.optString("audioLanguage", LanguagePreferences.DEFAULT), data.optString("subtitleLanguage", LanguagePreferences.DEFAULT));
    }

    private static SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey existing = (SecretKey) store.getKey(ALIAS, null);
        if (existing != null) return existing;
        if (!create) throw new IllegalStateException("无法读取已保存的配置");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
