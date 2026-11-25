package cn.jiuwusan.jellyfin.player;

import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;
import java.util.List;

/** Test-only access to the logical list; offscreen cards intentionally have no View. */
final class RecycledListDriver {
    static RecycledMediaGrid find(View view) {
        if (view instanceof RecycledMediaGrid) return (RecycledMediaGrid)view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            RecycledMediaGrid found = find(((ViewGroup)view).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
    static Object field(Object object, String name) {
        try { java.lang.reflect.Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    static List<String> titles(RecycledMediaGrid list) {
        List<String> titles = new ArrayList<>();
        for (Object entry : (List<?>)field(list, "entries")) {
            JellyfinApi.Item item = (JellyfinApi.Item)field(entry, "item"); if (item != null) titles.add(item.title);
        }
        return titles;
    }
    static int position(RecycledMediaGrid list, String title) {
        List<?> entries = (List<?>)field(list, "entries");
        for (int i = 0; i < entries.size(); i++) {
            JellyfinApi.Item item = (JellyfinApi.Item)field(entries.get(i), "item");
            if (item != null && item.title.equals(title)) return i;
        }
        return -1;
    }
    static void same(RecycledMediaGrid list, RecycledMediaGrid.Anchor expected) {
        RecycledMediaGrid.Anchor actual = list.anchor();
        if (actual == null || !actual.key.equals(expected.key) || Math.abs(actual.offset - expected.offset) > 3)
            throw new AssertionError("List anchor changed after return");
    }
}
