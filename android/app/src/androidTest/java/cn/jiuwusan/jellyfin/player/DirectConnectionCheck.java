package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;

/** No saved settings, real credentials or external services. */
final class DirectConnectionCheck {
    private final Instrumentation test;
    DirectConnectionCheck(Instrumentation test) { this.test = test; }
    private void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    void run() {
        Bundle out = new Bundle();
        ProxySelector previous = ProxySelector.getDefault();
        try (ServerSocket server = new ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            JSONObject raw = SubscriptionCheck.config(1);
            raw.put("minClientVersionCode", 72);
            JSONObject connection = raw.getJSONObject("connection");
            connection.remove("proxy");
            connection.getJSONObject("jellyfin").put("baseUrl", "http://127.0.0.1:" + server.getLocalPort() + "/jellyfin");
            ManagedConfiguration missing = ManagedConfiguration.parse(raw, 72);
            connection.put("proxy", JSONObject.NULL);
            ManagedConfiguration explicitNull = ManagedConfiguration.parse(raw, 72);
            check(missing.sameConnection(explicitNull), "Absent/null proxy differ");
            check(ConnectionSettings.fromJson(missing.settings(null).toJson()).host.isEmpty(), "Direct cached settings lost mode");
            for (Object invalid : new Object[]{new JSONObject(), "invalid", new JSONObject().put("type", "http").put("host", "").put("port", 8080).put("username", "").put("password", "")}) {
                connection.put("proxy", invalid);
                try { ManagedConfiguration.parse(raw, 72); throw new AssertionError("Invalid proxy became direct"); }
                catch (IllegalArgumentException expected) { }
            }
            connection.remove("proxy");
            try { ManagedConfiguration.parse(raw, 71); throw new AssertionError("Old client accepted direct snapshot"); }
            catch (IllegalArgumentException expected) { }
            ManagedConfiguration proxied = ManagedConfiguration.parse(SubscriptionCheck.config(1), 72);
            check(!missing.sameConnection(proxied), "Mode switch was treated as unchanged connection");
            ProxySelector.setDefault(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) { return Collections.singletonList(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 9))); }
                @Override public void connectFailed(URI uri, java.net.SocketAddress address, java.io.IOException error) { }
            });
            JellyfinApi api = new JellyfinApi(missing.settings(null), "direct-fixture");
            check(Proxy.NO_PROXY.equals(api.client().proxy()), "Direct client inherited system proxy");
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread responder = new Thread(() -> {
                try {
                    for (int i = 0; i < 4; i++) try (Socket socket = server.accept()) {
                        socket.setSoTimeout(3000);
                        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                        String first = reader.readLine();
                        check(first.startsWith("GET /jellyfin/"), "Base path lost");
                        String line;
                        while ((line = reader.readLine()) != null && !line.isEmpty())
                            check(!line.toLowerCase(java.util.Locale.ROOT).startsWith("proxy-authorization:"), "Direct request leaked proxy credentials");
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                } catch (Throwable e) { error.set(e); }
            });
            responder.start();
            for (String path : new String[]{"System/Info/Public", "Items/fixture/Images/Primary", "Videos/fixture/master.m3u8", "Videos/fixture/stream.srt"}) {
                try (Response response = api.client().newCall(new Request.Builder().url(missing.settings(null).server.resolve(path)).build()).execute()) {
                    check(response.isSuccessful(), "Direct request failed");
                }
            }
            responder.join(5000);
            check(!responder.isAlive() && error.get() == null, "Direct fixture failed: " + error.get());
            JellyfinApi proxyApi = new JellyfinApi(proxied.settings(null), "proxy-fixture");
            check(proxyApi.client().proxy().type() == Proxy.Type.HTTP, "Configured proxy ignored");
            out.putString("result", "ALL_PASS: absent/null direct / invalid proxy rejected / version gate / cached mode / system proxy bypass / API+image+HLS+subtitle routes / no proxy auth / proxy mode retained");
            test.finish(Activity.RESULT_OK, out);
        } catch (Throwable e) {
            out.putString("failure", android.util.Log.getStackTraceString(e)); test.finish(Activity.RESULT_CANCELED, out);
        } finally { ProxySelector.setDefault(previous); }
    }
}
