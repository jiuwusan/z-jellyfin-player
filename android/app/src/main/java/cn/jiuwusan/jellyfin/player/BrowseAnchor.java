package cn.jiuwusan.jellyfin.player;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

/** Geometry is transient; only an item ID and density mode enter BrowseMemory. */
final class BrowseAnchor {
    static String firstVisible(ScrollView scroll) {
        Rect viewport=new Rect();
        return scroll.getGlobalVisibleRect(viewport) ? first(scroll,viewport) : null;
    }
    private static String first(View view, Rect viewport) {
        Object id=view.getTag(R.id.browse_item_id);
        if(id instanceof String && view.isShown()) {
            int[] point=new int[2]; view.getLocationOnScreen(point);
            if(point[1] < viewport.bottom && point[1]+view.getHeight() > viewport.top) return (String)id;
        }
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            String found=first(((ViewGroup)view).getChildAt(i),viewport); if(found!=null) return found;
        }
        return null;
    }
    private static View find(View view, String id) {
        if(id.equals(view.getTag(R.id.browse_item_id))) return view;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            View found=find(((ViewGroup)view).getChildAt(i),id); if(found!=null) return found;
        }
        return null;
    }
    static boolean restore(ScrollView scroll, String id) {
        View item=find(scroll,id); if(item==null) return false;
        int[] itemPosition=new int[2], scrollPosition=new int[2];
        item.getLocationOnScreen(itemPosition); scroll.getLocationOnScreen(scrollPosition);
        scroll.scrollTo(0,Math.max(0,scroll.getScrollY()+itemPosition[1]-scrollPosition[1]));
        return true;
    }
}
