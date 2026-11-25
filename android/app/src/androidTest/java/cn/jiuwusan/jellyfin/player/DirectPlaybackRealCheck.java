package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import java.net.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Uses existing device credentials in memory; never changes the saved subscription or services. */
final class DirectPlaybackRealCheck {
    private final Instrumentation test;
    private final String endpoint;
    private Activity activity;
    DirectPlaybackRealCheck(Instrumentation test, String endpoint) { this.test = test; this.endpoint = endpoint; }
    private Object field(Object object, String name) { return RecycledListDriver.field(object, name); }
    private void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
    private void ui(Runnable work) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        test.runOnMainSync(() -> { try { work.run(); } catch (Throwable e) { error.set(e); } });
        if (error.get() != null) throw new AssertionError(error.get());
    }
    private void invoke(String name, Class<?>[] types, Object... args) {
        try { java.lang.reflect.Method m = MainActivity.class.getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(activity, args); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private interface Ready { boolean get(); }
    private void waitFor(Ready condition, String why) {
        boolean[] ready = {false};
        for (int i = 0; i < 600 && !ready[0]; i++) { ui(() -> ready[0] = condition.get()); SystemClock.sleep(100); }
        check(ready[0], why);
    }
    void run() {
        Bundle out = new Bundle();
        try {
            check(endpoint != null && endpoint.startsWith("https://"), "HTTPS endpoint required");
            try {
                SubscriptionSettings cached = SubscriptionSettings.load(test.getTargetContext());
                out.putString("existingCache", cached == null ? "absent" : cached.blocked ? "blocked" : "readable");
            } catch (Exception e) {
                out.putString("existingCache", "unreadable: " + e.getClass().getSimpleName());
                throw new AssertionError("Existing subscription cache cannot be read");
            }
            activity = test.startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(test.getTargetContext().getPackageName(), MainActivity.class.getName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            waitFor(() -> activity.findViewById(R.id.home_refresh) != null && field(activity, "settings") != null, "Saved account home unavailable");
            SubscriptionSettings saved = ((SubscriptionController)field(activity, "subscriptionController")).state();
            String link = saved.link;
            long revision = saved.configuration.revision;
            ConnectionSettings previous = (ConnectionSettings)field(activity, "settings");
            ConnectionSettings direct = new ConnectionSettings(endpoint, "", 0, "", "", previous.user, previous.password,
                    previous.resolution, previous.bitrate, previous.audioLanguage, previous.subtitleLanguage);
            ui(() -> invoke("connect", new Class<?>[]{ConnectionSettings.class}, direct));
            waitFor(() -> field(activity, "settings") == direct && activity.findViewById(R.id.home_refresh) != null, "Direct login/home failed");
            JellyfinApi api = (JellyfinApi)field(activity, "api");
            check(Proxy.NO_PROXY.equals(api.client().proxy()), "Runtime client is not direct");
            AtomicInteger apiRequests = new AtomicInteger(), images = new AtomicInteger(), hls = new AtomicInteger(), segments = new AtomicInteger();
            java.lang.reflect.Field http = JellyfinApi.class.getDeclaredField("http"); http.setAccessible(true);
            http.set(api, api.client().newBuilder().addNetworkInterceptor(chain -> {
                if (chain.connection() == null || chain.connection().route().proxy().type() != Proxy.Type.DIRECT)
                    throw new java.io.IOException("Unexpected proxy route");
                if (chain.request().header("Proxy-Authorization") != null) throw new java.io.IOException("Unexpected proxy authorization");
                String path = chain.request().url().encodedPath();
                if (path.contains("/Images/")) images.incrementAndGet();
                else if (path.endsWith(".m3u8")) hls.incrementAndGet();
                else if (path.endsWith(".ts") || path.endsWith(".mp4") || path.endsWith(".m4s")) segments.incrementAndGet();
                else apiRequests.incrementAndGet();
                return chain.proceed(chain.request());
            }).build());
            List<JellyfinApi.Item> items = api.resumeItems();
            if (items.isEmpty()) items = api.latestItems();
            JellyfinApi.Item chosen = null;
            for (JellyfinApi.Item item : items) if ("Movie".equals(item.type) || "Episode".equals(item.type)) { chosen = item; break; }
            check(chosen != null, "No playable saved-account media");
            JellyfinApi.Item item = api.details(chosen.id);
            check(api.artwork(item, true, "", false) != null, "Direct artwork unavailable");
            AtomicReference<String> decoder = new AtomicReference<>();
            AtomicReference<ExoPlayer> observed = new AtomicReference<>();
            ui(() -> invoke("playbackPage", new Class<?>[]{JellyfinApi.Item.class, Long.class, Runnable.class}, item, null,
                    (Runnable)() -> invoke("homePage", new Class<?>[]{})));
            waitFor(() -> {
                ExoPlayer player = (ExoPlayer)field(activity, "player");
                if (player != null && player != observed.get()) {
                    observed.set(player);
                    player.addAnalyticsListener(new AnalyticsListener() {
                        @Override public void onVideoDecoderInitialized(EventTime time, String name, long at, long duration) { decoder.set(name); }
                    });
                }
                return player != null && player.isPlaying() && activity.findViewById(R.id.playback_preparation_cover).getVisibility() == View.GONE;
            }, "Direct playback did not render");
            SystemClock.sleep(1500);
            ui(() -> {
                ExoPlayer player = (ExoPlayer)field(activity, "player");
                Format format = player.getVideoFormat();
                check(format != null && format.width <= 1920 && format.height <= 1080, "Resolution cap exceeded");
                out.putString("video", format.sampleMimeType + " " + format.width + "x" + format.height);
                player.seekTo(Math.min(player.getCurrentPosition() + 15_000, Math.max(0, player.getDuration() - 60_000)));
            });
            waitFor(() -> ((ExoPlayer)field(activity, "player")).isPlaying(), "Direct seek failed");
            check(decoder.get() != null && !decoder.get().startsWith("c2.android.") && !decoder.get().startsWith("OMX.google."), "Hardware decoder not captured");
            out.putString("decoder", decoder.get());
            ui(() -> activity.onBackPressed());
            waitFor(() -> activity.findViewById(R.id.home_refresh) != null && field(activity, "player") == null, "Playback return failed");
            SubscriptionSettings after = ((SubscriptionController)field(activity, "subscriptionController")).state();
            check(after.link.equals(link) && after.configuration.revision == revision, "Saved subscription changed");
            check(apiRequests.get() > 0 && images.get() > 0 && (hls.get() > 0 || segments.get() > 0), "Missing actual direct media requests");
            out.putInt("apiRequests", apiRequests.get()); out.putInt("images", images.get()); out.putInt("hls", hls.get()); out.putInt("segments", segments.get());
            out.putString("result", "ALL_PASS: saved credentials / direct HTTPS login+home+image+media / no proxy headers / hardware frame / seek / return / subscription unchanged");
            test.finish(Activity.RESULT_OK, out);
        } catch (Throwable e) {
            // Real-service failures may contain endpoints; report only type and local assertion text.
            out.putString("failure", e instanceof AssertionError && e.getCause() == null ? e.getMessage() : e.getClass().getSimpleName());
            test.finish(Activity.RESULT_CANCELED, out);
        }
    }
}
