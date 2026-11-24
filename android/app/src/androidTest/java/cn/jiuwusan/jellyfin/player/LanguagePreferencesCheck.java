package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Spinner;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Loopback negotiation and settings UI; never saves real connection settings. */
final class LanguagePreferencesCheck {
    private final Instrumentation test;
    LanguagePreferencesCheck(Instrumentation test) { this.test = test; }
    private void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private String language(ConnectionSettings settings, String field) throws Exception {
        Field f = ConnectionSettings.class.getDeclaredField(field); f.setAccessible(true); return (String) f.get(settings);
    }
    private ConnectionSettings preferences(ConnectionSettings base, String audio, String subtitles) throws Exception {
        java.lang.reflect.Constructor<ConnectionSettings> ctor = ConnectionSettings.class.getDeclaredConstructor(String.class,
                String.class, int.class, String.class, String.class, String.class, String.class, int.class, int.class, String.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(base.server.toString(), base.host, base.port, "", "", "fixture", "fixture", 1920, 4_000_000, audio, subtitles);
    }
    private View find(View root, String description) {
        if (description.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View v = find(((ViewGroup) root).getChildAt(i), description); if (v != null) return v;
        }
        return null;
    }
    void run() {
        Bundle result = new Bundle();
        try (SubtitleHandlingCheck.Fixture fixture = new SubtitleHandlingCheck.Fixture()) {
            fixture.languageScenario = true;
            ConnectionSettings settings = new ConnectionSettings("http://fixture.invalid", "127.0.0.1", fixture.server.getLocalPort(),
                    "", "", "fixture", "fixture", 1920, 4_000_000);
            check("zho".equals(language(settings, "audioLanguage")) && "zho".equals(language(settings, "subtitleLanguage")), "Missing Chinese defaults");
            JellyfinApi.Item item = new JellyfinApi.Item(new JSONObject().put("Id", "film").put("Name", "语言测试").put("Type", "Movie"));
            PlaybackPolicy policy = PlaybackPolicy.fromSettings(1920, 4_000_000, 1080, 2400);
            JellyfinApi chinese = new JellyfinApi(settings, "language-check"); chinese.login();
            JellyfinApi.Stream selected = chinese.stream(item, policy);
            check(selected.audioIndex == 1 && selected.defaultSubtitleIndex == -1 && selected.burnedSubtitleIndex == -1,
                    "App overrode the server's audio choice or disabled-subtitle mode");
            check(fixture.configurationWrites == 0, "Login/playback wrote account preferences");
            JellyfinApi.Stream manual = chinese.stream(item, policy, "source", 2, 4);
            check(manual.audioIndex == 2 && manual.burnedSubtitleIndex == 4, "Manual audio/subtitle lost priority");
            java.lang.reflect.Method read = JellyfinApi.class.getDeclaredMethod("languagePreferences"); read.setAccessible(true);
            java.lang.reflect.Method write = JellyfinApi.class.getDeclaredMethod("saveLanguagePreferences", String.class, String.class); write.setAccessible(true);
            String[] serverLanguages = (String[]) read.invoke(chinese);
            check("eng".equals(serverLanguages[0]) && "eng".equals(serverLanguages[1]), "Did not read account preferences");
            write.invoke(chinese, "zho", "zho");
            check(fixture.configurationWrites == 1 && "zho".equals(fixture.configuration.getString("AudioLanguagePreference"))
                    && "zho".equals(fixture.configuration.getString("SubtitleLanguagePreference")), "Account languages not saved");
            check("None".equals(fixture.configuration.getString("SubtitleMode")) && fixture.configuration.getBoolean("RememberSubtitleSelections")
                    && fixture.configuration.getBoolean("PlayDefaultAudioTrack") && fixture.configuration.getInt("OtherSetting") == 42,
                    "Saving languages overwrote unrelated account settings");
            write.invoke(chinese, "zho", "zho");
            check(fixture.configurationWrites == 1, "Unchanged languages caused an unnecessary account write");
            fixture.failConfigurationRead = true;
            boolean failedRead = false;
            try { write.invoke(chinese, "eng", "eng"); }
            catch (java.lang.reflect.InvocationTargetException error) { failedRead = error.getCause() instanceof java.io.IOException; }
            check(failedRead && fixture.configurationWrites == 1, "Missing configuration caused a destructive write");
            fixture.failConfigurationRead = false;
            fixture.rejectConfigurationWrite = true;
            boolean failedWrite = false;
            try { write.invoke(chinese, "eng", "eng"); }
            catch (java.lang.reflect.InvocationTargetException error) { failedWrite = error.getCause() instanceof java.io.IOException; }
            check(failedWrite && fixture.configurationWrites == 1 && "zho".equals(fixture.configuration.getString("AudioLanguagePreference")),
                    "Rejected account update was treated as success");
            fixture.rejectConfigurationWrite = false;
            ConnectionSettings choice = preferences(settings, "eng", "jpn");
            java.lang.reflect.Method encode = ConnectionSettings.class.getDeclaredMethod("toJson"); encode.setAccessible(true);
            java.lang.reflect.Method decode = ConnectionSettings.class.getDeclaredMethod("fromJson", JSONObject.class); decode.setAccessible(true);
            JSONObject data = (JSONObject) encode.invoke(choice);
            ConnectionSettings roundTrip = (ConnectionSettings) decode.invoke(null, data);
            check("eng".equals(language(roundTrip, "audioLanguage")) && "jpn".equals(language(roundTrip, "subtitleLanguage")), "Language settings did not round trip");
            data.remove("audioLanguage"); data.remove("subtitleLanguage");
            ConnectionSettings legacy = (ConnectionSettings) decode.invoke(null, data);
            check("zho".equals(language(legacy, "audioLanguage")) && "zho".equals(language(legacy, "subtitleLanguage")), "Legacy settings lost Chinese defaults");
            Context isolated = new ContextWrapper(test.getTargetContext()) {
                @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                    return super.getSharedPreferences("language-preference-check", mode);
                }
            };
            choice.save(isolated);
            ConnectionSettings decrypted = ConnectionSettings.load(isolated);
            check("eng".equals(decrypted.audioLanguage) && "jpn".equals(decrypted.subtitleLanguage), "Encrypted language settings did not persist");
            isolated.getSharedPreferences("connection", 0).edit().clear().commit();
            Activity activity = test.startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName(test.getTargetContext().getPackageName(), MainActivity.class.getName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            AtomicReference<JellyfinApi> real = new AtomicReference<>();
            for (int i = 0; i < 300 && real.get() == null; i++) {
                test.runOnMainSync(() -> { try {
                    Field f = MainActivity.class.getDeclaredField("api"); f.setAccessible(true);
                    real.set((JellyfinApi) f.get(activity));
                } catch (Exception error) { throw new RuntimeException(error); } });
                SystemClock.sleep(100);
            }
            check(real.get() != null, "Device did not automatically log in");
            String[] actual = real.get().languagePreferences();
            String sealed = activity.getSharedPreferences("connection", 0).getString("sealed", null);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            test.runOnMainSync(() -> { try {
                java.lang.reflect.Method page = MainActivity.class.getDeclaredMethod("settingsPage", String.class); page.setAccessible(true); page.invoke(activity, "");
                View root = activity.getWindow().getDecorView();
                View playbackTab = find(root, "播放设置分类");
                check(playbackTab != null, "Playback settings tab missing");
                playbackTab.performClick();
                Spinner audio = (Spinner) find(root, "首选的音频语言"), subtitles = (Spinner) find(root, "字幕语言偏好");
                check(audio != null && subtitles != null, "Language controls missing");
            } catch (Throwable e) { failure.set(e); } });
            if (failure.get() != null) throw new AssertionError(failure.get());
            java.util.concurrent.atomic.AtomicBoolean loaded = new java.util.concurrent.atomic.AtomicBoolean();
            for (int i = 0; i < 300 && !loaded.get(); i++) {
                test.runOnMainSync(() -> {
                    View root = activity.getWindow().getDecorView();
                    Spinner audio = (Spinner) find(root, "首选的音频语言"), subtitles = (Spinner) find(root, "字幕语言偏好");
                    loaded.set(audio.isEnabled() && subtitles.isEnabled()
                            && audio.getSelectedItemPosition() == LanguagePreferences.optionIndex(actual[0])
                            && subtitles.getSelectedItemPosition() == LanguagePreferences.optionIndex(actual[1]));
                });
                SystemClock.sleep(100);
            }
            check(loaded.get(), "Settings UI did not load server preferences");
            check(sealed.equals(activity.getSharedPreferences("connection", 0).getString("sealed", null)), "Reading settings changed connection data");
            result.putString("result", "ALL_PASS: server default indices / disabled subtitles / manual override / account preference read-write / unrelated fields preserved / no automatic writes / encrypted cache and UI");
            test.finish(Activity.RESULT_OK, result);
        } catch (Throwable e) { result.putString("failure", android.util.Log.getStackTraceString(e)); test.finish(Activity.RESULT_CANCELED, result); }
        finally { test.getTargetContext().getSharedPreferences("language-preference-check", 0).edit().clear().commit(); }
    }
}
