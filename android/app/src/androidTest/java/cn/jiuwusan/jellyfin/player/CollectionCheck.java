package cn.jiuwusan.jellyfin.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;

/** Request checks use a loopback HTTP proxy; optional probe is read-only on the saved server. */
final class CollectionCheck {
    private final Instrumentation test;
    CollectionCheck(Instrumentation test) { this.test = test; }
    private void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
    private Object field(Object target, String name) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    void run(String suite) {
        if ("collections-real".equals(suite)) { new CollectionsUiCheck(test).run(); return; }
        if ("collections-grid".equals(suite)) { new CollectionGridCheck(test).run(); return; }
        Bundle result = new Bundle();
        try {
            if ("collections-probe".equals(suite)) probe(result);
            else queries(result);
            result.putString("result", "ALL_PASS"); test.finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            result.putString("failure", android.util.Log.getStackTraceString(e)); test.finish(Activity.RESULT_CANCELED, result);
        }
    }
    private void queries(Bundle result) throws Exception {
        try (ProxyProbe proxy = new ProxyProbe()) {
            JellyfinApi api = new JellyfinApi(new ConnectionSettings("http://fixture.invalid", "127.0.0.1", proxy.port(),
                    "", "", "fixture", "fixture", 1920, 4000000), "collection-check");
            api.login(); proxy.take();
            JellyfinApi.Item library = new JellyfinApi.Item(new JSONObject().put("Id", "library").put("Name", "合集库")
                    .put("Type", "CollectionFolder").put("CollectionType", "boxsets"));
            api.libraryItems(library, true, 0);
            HttpUrl query = proxy.take();
            check("BoxSet".equals(query.queryParameter("IncludeItemTypes")), "Collections library must query BoxSet");
            check("DateCreated,SortName".equals(query.queryParameter("SortBy")), "Collections catalog lost newest-first sort");
            JellyfinApi.Item box = new JellyfinApi.Item(new JSONObject().put("Id", "box").put("Name", "合集")
                    .put("Type", "BoxSet").put("IsFolder", false).put("ChildCount", 42));
            check((Boolean) field(box, "collection"), "BoxSet must be recognized without IsFolder");
            check(box.childCount == 42, "Collection lost server child count");
            JellyfinApi.Item unknown = new JellyfinApi.Item(new JSONObject().put("Id", "unknown").put("Name", "未知数量")
                    .put("Type", "BoxSet").put("ChildCount", -1));
            check(unknown.childCount == -1, "Unknown child count was displayed as zero");
            api.libraryItems(box, false, 36);
            query = proxy.take();
            check("box".equals(query.queryParameter("ParentId")), "Members lost parent scope");
            check(query.queryParameter("SortBy") == null && query.queryParameter("SortOrder") == null, "Members override server order");
            check("false".equals(query.queryParameter("Recursive")), "Members must be direct children");
            check("false".equals(query.queryParameter("CollapseBoxSetItems")), "Members may collapse again");
            check("36".equals(query.queryParameter("StartIndex")), "Member paging offset changed");
            api.libraryItems(null, true, 0);
            query = proxy.take();
            check("Movie,Series".equals(query.queryParameter("IncludeItemTypes")), "Ordinary catalog types changed");
            check(query.queryParameter("CollapseBoxSetItems") == null, "Catalog forces collection folding");
            check("Descending,Ascending".equals(query.queryParameter("SortOrder")), "Catalog sort changed");
            result.putString("queries", "BoxSet catalog / direct members / server order / paging / server folding");
            java.util.ArrayList<JSONObject> members = new java.util.ArrayList<>();
            for (int i = 0; i < 39; i++) members.add(new JSONObject().put("Id", "member-" + i).put("Name", "成员" + i)
                    .put("Type", i == 36 ? "Series" : "Movie")
                    .put("UserData", new JSONObject().put("Played", i < 36).put("PlaybackPositionTicks", i == 37 ? 250_000_000L : 0)));
            proxy.collectionRows = members;
            Class<?> queueClass = Class.forName(JellyfinApi.class.getPackage().getName() + ".CollectionPlayback");
            java.lang.reflect.Method load = queueClass.getDeclaredMethod("load", JellyfinApi.class, JellyfinApi.Item.class);
            Object queue = load.invoke(null, api, box);
            HttpUrl firstPage = proxy.take(), secondPage = proxy.take();
            check("0".equals(firstPage.queryParameter("StartIndex")) && "36".equals(secondPage.queryParameter("StartIndex")),
                    "Queue was limited to visible members or used filtered page offsets");
            check(firstPage.queryParameter("SortBy") == null && "false".equals(firstPage.queryParameter("Recursive")),
                    "Queue changed collection ordering or expanded nested content");
            java.lang.reflect.Method start = queueClass.getDeclaredMethod("start");
            java.lang.reflect.Method next = queueClass.getDeclaredMethod("next", JellyfinApi.Item.class);
            java.lang.reflect.Method previous = queueClass.getDeclaredMethod("previous", JellyfinApi.Item.class);
            JellyfinApi.Item selected = (JellyfinApi.Item) start.invoke(queue);
            check(selected.id.equals("member-37") && selected.resumeMs == 25_000, "Queue lost first-unfinished resume target");
            check(((JellyfinApi.Item) previous.invoke(queue, selected)).id.equals("member-35"), "Queue did not skip non-movie member");
            JellyfinApi.Item last = (JellyfinApi.Item) next.invoke(queue, selected);
            check(last.id.equals("member-38") && next.invoke(queue, last) == null, "Queue order or final boundary changed");
            java.util.List<JellyfinApi.Item> movies = (java.util.List<JellyfinApi.Item>) field(queue, "items");
            check(movies.size() == 38, "Queue included a series or omitted movie members");
            for (JellyfinApi.Item movie : movies) { movie.played = true; movie.resumeMs = 0; }
            check(((JellyfinApi.Item) start.invoke(queue)).id.equals("member-0"), "Completed collection did not restart at first movie");
            check(previous.invoke(queue, start.invoke(queue)) == null, "First movie has an invalid previous member");
            proxy.collectionRows = java.util.Collections.emptyList();
            Object empty = load.invoke(null, api, box); proxy.take();
            check(start.invoke(empty) == null, "Empty collection invented a playable target");
            proxy.collectionRows = members; proxy.failCollectionTail = true;
            boolean partialRejected = false;
            try { load.invoke(null, api, box); }
            catch (java.lang.reflect.InvocationTargetException expected) { partialRejected = true; }
            proxy.take(); proxy.take();
            check(partialRejected, "A failed trailing page silently started a partial queue");
            proxy.failCollectionTail = false;
            result.putString("playlist", "complete paging / server order / direct movies / resume / completed restart / boundaries / empty");
        }
    }
    private void probe(Bundle result) throws Exception {
        ConnectionSettings saved = ConnectionSettings.load(test.getTargetContext());
        check(saved != null, "No saved connection");
        JellyfinApi api = new JellyfinApi(saved, "collections-readonly-check"); api.login();
        String user = (String) field(api, "userId");
        StringBuilder report = new StringBuilder();
        for (JellyfinApi.Item library : api.libraries()) report.append(library.title).append(" (").append(library.collectionType).append(")\n");
        HttpUrl url = saved.server.newBuilder().addPathSegments("Items").addQueryParameter("userId", user)
                .addQueryParameter("Recursive", "true").addQueryParameter("IncludeItemTypes", "BoxSet")
                .addQueryParameter("Limit", "6").addQueryParameter("Fields", "ItemCounts").build();
        try (Response response = api.client().newCall(new Request.Builder().url(url).header("Authorization", api.authorization()).build()).execute()) {
            check(response.isSuccessful(), "Collection probe failed");
            JSONObject json = new JSONObject(response.body().string());
            report.append("collections=").append(json.optInt("TotalRecordCount", -1)).append('\n');
            for (int i = 0; i < json.getJSONArray("Items").length(); i++) {
                JellyfinApi.Item box = new JellyfinApi.Item(json.getJSONArray("Items").getJSONObject(i));
                report.append(box.title).append(" children=").append(json.getJSONArray("Items").getJSONObject(i).optInt("ChildCount", -1)).append('\n');
            }
        }
        result.putString("probe", report.toString());
        for (JellyfinApi.Item library : api.libraries()) {
            if (!"movies".equals(library.collectionType) && !"boxsets".equals(library.collectionType)) continue;
            HttpUrl scoped = url.newBuilder().addQueryParameter("ParentId", library.id).build();
            try (Response response = api.client().newCall(new Request.Builder().url(scoped).header("Authorization", api.authorization()).build()).execute()) {
                check(response.isSuccessful(), "Scoped collection probe failed");
                JSONObject json = new JSONObject(response.body().string());
                StringBuilder rows = new StringBuilder("total=" + json.optInt("TotalRecordCount", -1) + "\n");
                for (int i = 0; i < json.getJSONArray("Items").length(); i++) {
                    JSONObject row = json.getJSONArray("Items").getJSONObject(i);
                    rows.append(row.optString("Name")).append(" type=").append(row.optString("Type")).append('\n');
                }
                result.putString("scoped-" + library.title, rows.toString());
            }
            HttpUrl folders = saved.server.newBuilder().addPathSegments("Items").addQueryParameter("userId", user)
                    .addQueryParameter("ParentId", library.id).addQueryParameter("Recursive", "false")
                    .addQueryParameter("CollapseBoxSetItems", "false").addQueryParameter("Limit", "1000")
                    .addQueryParameter("Fields", "ItemCounts").build();
            try (Response response = api.client().newCall(new Request.Builder().url(folders).header("Authorization", api.authorization()).build()).execute()) {
                JSONObject json = new JSONObject(response.body().string());
                StringBuilder rows = new StringBuilder("total=" + json.optInt("TotalRecordCount", -1) + "\n");
                int count = 0;
                for (int i = 0; i < json.getJSONArray("Items").length(); i++) {
                    JSONObject row = json.getJSONArray("Items").getJSONObject(i);
                    if (row.optBoolean("IsFolder") && count++ < 30) rows.append(row.optString("Name")).append(" type=").append(row.optString("Type")).append('\n');
                }
                result.putString("direct-folders-" + library.title, rows.toString());
            }
            StringBuilder visibleFolders = new StringBuilder();
            int offset = 0;
            for (int n = 0; n < 40; n++) {
                JellyfinApi.LibraryItemsPage page = api.libraryItems(library, true, offset);
                for (JellyfinApi.Item item : page.items) if (item.folder) {
                    visibleFolders.append(item.title).append(" type=").append(item.type).append('\n');
                    if (item.title.startsWith("速度与激情") || item.title.startsWith("人类清除计划")) {
                        JellyfinApi.Item detail = api.details(item);
                        visibleFolders.append(" detailType=").append(detail.type).append("\n");
                        HttpUrl children = saved.server.newBuilder().addPathSegments("Items").addQueryParameter("userId", user)
                                .addQueryParameter("ParentId", item.id).addQueryParameter("Recursive", "false")
                                .addQueryParameter("CollapseBoxSetItems", "false").addQueryParameter("Limit", "100").build();
                        try (Response response = api.client().newCall(new Request.Builder().url(children).header("Authorization", api.authorization()).build()).execute()) {
                            JSONObject json = new JSONObject(response.body().string());
                            for (int i = 0; i < json.getJSONArray("Items").length(); i++) {
                                JSONObject row = json.getJSONArray("Items").getJSONObject(i);
                                visibleFolders.append(" member=").append(row.optString("Name")).append(" type=").append(row.optString("Type")).append('\n');
                            }
                        }
                    }
                }
                offset += page.items.size();
                if (!page.more) break;
            }
            result.putString("catalog-folders-" + library.title, visibleFolders.toString());
        }
        for (String term : new String[]{"速度与激情", "人类清除计划"}) {
            HttpUrl search = saved.server.newBuilder().addPathSegments("Items").addQueryParameter("userId", user)
                    .addQueryParameter("Recursive", "true").addQueryParameter("SearchTerm", term)
                    .addQueryParameter("Limit", "12").addQueryParameter("Fields", "ItemCounts").build();
            try (Response response = api.client().newCall(new Request.Builder().url(search).header("Authorization", api.authorization()).build()).execute()) {
                check(response.isSuccessful(), "Read-only type query failed");
                JSONObject json = new JSONObject(response.body().string());
                StringBuilder found = new StringBuilder();
                for (int i = 0; i < json.getJSONArray("Items").length(); i++) {
                    JSONObject row = json.getJSONArray("Items").getJSONObject(i);
                    found.append(row.optString("Name")).append(" type=").append(row.optString("Type"))
                            .append(" folder=").append(row.optBoolean("IsFolder")).append(" children=").append(row.optInt("ChildCount", -1)).append('\n');
                }
                result.putString(term, found.toString());
            }
        }
    }
    private static final class ProxyProbe implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"));
        final LinkedBlockingQueue<HttpUrl> requests = new LinkedBlockingQueue<>();
        volatile boolean running = true;
        volatile java.util.List<JSONObject> collectionRows;
        volatile boolean failCollectionTail;
        ProxyProbe() throws Exception {
            Thread thread = new Thread(() -> {
                while (running) try (Socket socket = server.accept()) {
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String line = input.readLine(); if (line == null) continue;
                    HttpUrl url = HttpUrl.parse(line.split(" ")[1]);
                    int length = 0;
                    while (!(line = input.readLine()).isEmpty()) if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))
                        length = Integer.parseInt(line.substring(15).trim());
                    for (int i = 0; i < length; i++) input.read();
                    requests.add(url);
                    String body = url.encodedPath().endsWith("AuthenticateByName")
                            ? "{\"AccessToken\":\"fixture\",\"User\":{\"Id\":\"fixture\"}}" : "{\"Items\":[],\"TotalRecordCount\":0}";
                    if (collectionRows != null && "box".equals(url.queryParameter("ParentId"))) {
                        int offset = Integer.parseInt(url.queryParameter("StartIndex"));
                        org.json.JSONArray rows = new org.json.JSONArray();
                        for (int i = offset; i < Math.min(offset + 36, collectionRows.size()); i++) rows.put(collectionRows.get(i));
                        body = new JSONObject().put("Items", rows).put("TotalRecordCount", collectionRows.size()).toString();
                        if (failCollectionTail && offset > 0) body = "{}";
                    }
                    byte[] data = body.getBytes(StandardCharsets.UTF_8);
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + data.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(data);
                } catch (Exception e) { if (running) throw new RuntimeException(e); }
            }, "collections-proxy-check"); thread.setDaemon(true); thread.start();
        }
        int port() { return server.getLocalPort(); }
        HttpUrl take() throws Exception { HttpUrl url = requests.poll(5, TimeUnit.SECONDS); if (url == null) throw new AssertionError("No request received"); return url; }
        @Override public void close() throws Exception { running = false; server.close(); }
    }
}
