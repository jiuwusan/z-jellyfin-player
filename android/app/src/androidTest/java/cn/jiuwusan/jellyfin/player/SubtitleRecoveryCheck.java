package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.net.Uri;
import android.os.Bundle;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.ParserException;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pure recovery budgets and loopback API contract, without real account writes. */
final class SubtitleRecoveryCheck {
    private final Instrumentation test;
    SubtitleRecoveryCheck(Instrumentation test) { this.test = test; }
    private void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private IOException http(int status) {
        return new HttpDataSource.InvalidResponseCodeException(status, "fixture", null, java.util.Collections.emptyMap(),
                new DataSpec(Uri.parse("http://fixture.invalid/subtitle.srt")), new byte[0]);
    }
    private Object invoke(Object target, String name, Class<?>[] types, Object... arguments) throws Exception {
        Class<?> type = target instanceof Class ? (Class<?>) target : target.getClass();
        java.lang.reflect.Method method = type.getDeclaredMethod(name, types); method.setAccessible(true);
        return method.invoke(target instanceof Class ? null : target, arguments);
    }
    void run() {
        Bundle result = new Bundle();
        try (SubtitleHandlingCheck.Fixture fixture = new SubtitleHandlingCheck.Fixture()) {
            Class<?> recoveryType = Class.forName("cn.jiuwusan.jellyfin.player.SubtitleRecovery");
            java.lang.reflect.Constructor<?> constructor = recoveryType.getDeclaredConstructor(); constructor.setAccessible(true);
            Object recovery = constructor.newInstance();
            Class<?>[] failureTypes = {int.class, IOException.class};
            check("REFRESH".equals(invoke(recovery, "next", failureTypes, 4, http(404)).toString()), "Stale address did not refresh first");
            check("BURN".equals(invoke(recovery, "next", failureTypes, 4, http(410)).toString()), "Repeated stale address did not burn");
            check("DISABLE".equals(invoke(recovery, "next", failureTypes, 4, http(404)).toString()), "Recovery loop was not bounded");
            IOException malformed = ParserException.createForMalformedContainer("fixture subtitle", null);
            check("BURN".equals(invoke(recovery, "next", failureTypes, 5, malformed).toString()), "Parser failure did not burn directly");
            for (int status : new int[]{401, 403, 407}) check("DISABLE".equals(invoke(recovery, "next", failureTypes, status, http(status)).toString()), "Auth failure attempted recovery");
            check("DISABLE".equals(invoke(recovery, "next", failureTypes, 4, new JellyfinApi.HttpFailure("fixture", 401)).toString()), "Refresh API authentication failure attempted burn");
            check(((Long) invoke(recoveryType, "retryDelay", new Class<?>[]{IOException.class, int.class}, http(503), 1)) == 1000L, "Transient error was not retried");
            check(((Long) invoke(recoveryType, "retryDelay", new Class<?>[]{IOException.class, int.class}, http(503), 2)) == C.TIME_UNSET, "Transient error retry unbounded");
            check(((Long) invoke(recoveryType, "retryDelay", new Class<?>[]{IOException.class, int.class}, malformed, 1)) == C.TIME_UNSET, "Parser error retried as network");
            invoke(recovery, "reset", new Class<?>[]{int.class}, 4);
            check("REFRESH".equals(invoke(recovery, "next", failureTypes, 4, http(404)).toString()), "Explicit retry could not reset budget");
            JellyfinApi api = new JellyfinApi(new ConnectionSettings("http://fixture.invalid", "127.0.0.1", fixture.server.getLocalPort(),
                    "", "", "fixture", "fixture", 1920, 4_000_000), "recovery-check"); api.login();
            JellyfinApi.Item item = new JellyfinApi.Item(new JSONObject().put("Id", "film").put("Type", "Movie").put("Name", "恢复检查"));
            PlaybackPolicy policy = PlaybackPolicy.fromSettings(1920, 4_000_000, 1080, 2400);
            JellyfinApi.Stream forced = (JellyfinApi.Stream) invoke(api, "stream", new Class<?>[]{JellyfinApi.Item.class, PlaybackPolicy.class,
                    String.class, Integer.class, int.class, int.class}, item, policy, "source", 2, 5, 5);
            check(forced.burnedSubtitleIndex == 5 && forced.audioIndex == 2 && "Encode".equals(forced.url.queryParameter("SubtitleMethod")), "Forced text burn lost subtitle/audio");
            check(!fixture.last().optBoolean("AllowVideoStreamCopy", true) && fixture.last().getInt("SubtitleStreamIndex") == 5, "Forced burn did not encode requested track");
            JSONArray profiles = fixture.last().getJSONObject("DeviceProfile").getJSONArray("SubtitleProfiles");
            for (int i = 0; i < profiles.length(); i++) check("Encode".equals(profiles.getJSONObject(i).getString("Method")), "Burn recovery still offered local text delivery");
            fixture.subtitleOverrides = new JSONArray()
                    .put(new JSONObject().put("Type", "Subtitle").put("Index", 4).put("Codec", "subrip").put("DeliveryMethod", "External").put("DeliveryUrl", "/subtitle-4.srt"))
                    .put(new JSONObject().put("Type", "Subtitle").put("Index", 5).put("Codec", "subrip").put("DeliveryMethod", "External").put("DeliveryUrl", "/subtitle-5.srt"));
            JellyfinApi.Stream external = api.stream(item, policy);
            MediaItem selected = (MediaItem) invoke(PlaybackTracks.class, "mediaItem", new Class<?>[]{JellyfinApi.Stream.class, int.class}, external, 5);
            check(selected.localConfiguration.subtitleConfigurations.size() == 1 && selected.localConfiguration.subtitleConfigurations.get(0).uri.getPath().endsWith("subtitle-5.srt"), "Unselected subtitle was still attached");
            MediaItem off = (MediaItem) invoke(PlaybackTracks.class, "mediaItem", new Class<?>[]{JellyfinApi.Stream.class, int.class}, external, PlaybackTracks.OFF);
            check(off.localConfiguration.subtitleConfigurations.isEmpty(), "Off still loads external subtitle files");
            partialDownload();
            result.putString("result", "ALL_PASS: recovery budgets / stale refresh / auth stop / network retry / parser burn / explicit retry / force encode / only selected subtitle / off");
            test.finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("failure", android.util.Log.getStackTraceString(error)); test.finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void partialDownload() throws Exception {
        Uri uri = Uri.parse("http://fixture.invalid/text.srt");
        final int[] opens = {0}, offset = {0};
        androidx.media3.datasource.DataSource source = new androidx.media3.datasource.DataSource() {
            @Override public void addTransferListener(androidx.media3.datasource.TransferListener listener) { }
            @Override public long open(DataSpec spec) {
                check(spec.position == 0, "Partial subtitle retry did not restart from zero");
                opens[0]++; offset[0] = 0; return C.LENGTH_UNSET;
            }
            @Override public int read(byte[] bytes, int start, int length) throws IOException {
                if (offset[0] == 3) {
                    if (opens[0] == 1) throw new IOException("fixture connection lost after prefix");
                    return C.RESULT_END_OF_INPUT;
                }
                int count = Math.min(length, 3 - offset[0]);
                for (int i = 0; i < count; i++) bytes[start + i] = (byte) ('a' + offset[0]++);
                return count;
            }
            @Override public Uri getUri() { return uri; }
            @Override public void close() { }
        };
        SubtitleDataSource buffered = new SubtitleDataSource(source, uri);
        check(buffered.open(new DataSpec(uri)) == 3, "Complete subtitle length was incorrect");
        byte[] bytes = new byte[9];
        int count = buffered.read(bytes, 0, bytes.length);
        check(count == 3 && "abc".equals(new String(bytes, 0, count, java.nio.charset.StandardCharsets.UTF_8))
                && opens[0] == 2, "Partial prefix leaked into retry result");
        check(buffered.read(bytes, 0, bytes.length) == C.RESULT_END_OF_INPUT, "Subtitle was duplicated");
        buffered.close();
    }
}
