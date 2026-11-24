package cn.jiuwusan.jellyfin.player;

import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** Only opaque hashes and timestamps; no addresses, credentials, indices or media titles. */
final class ManualTrackChoices {
    private final SharedPreferences preferences;
    ManualTrackChoices(SharedPreferences preferences) { this.preferences = preferences; }

    private String key(String server, String user, String item, String source, boolean subtitle) {
        try {
            // Length prefixes avoid ambiguous concatenation of untrusted identifiers.
            StringBuilder value = new StringBuilder();
            for (String part : new String[]{server, user, item, source, subtitle ? "Subtitle" : "Audio"})
                value.append(part.length()).append(':').append(part);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder();
            for (byte b : digest) hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return hash.toString();
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    synchronized boolean contains(String server, String user, String item, String source, boolean subtitle) {
        return preferences.contains(key(server, user, item, source, subtitle));
    }

    synchronized void set(String server, String user, String item, String source, boolean subtitle, boolean manual) {
        String key = key(server, user, item, source, subtitle);
        SharedPreferences.Editor edit = preferences.edit();
        if (manual) {
            Map<String, ?> all = preferences.getAll();
            if (all.size() >= 2048 && !all.containsKey(key)) {
                String oldest = null; long time = Long.MAX_VALUE;
                for (Map.Entry<String, ?> entry : all.entrySet()) if (entry.getValue() instanceof Long
                        && (Long) entry.getValue() < time) { oldest = entry.getKey(); time = (Long) entry.getValue(); }
                if (oldest != null) edit.remove(oldest);
            }
            edit.putLong(key, System.currentTimeMillis());
        } else edit.remove(key);
        edit.apply();
    }
}
