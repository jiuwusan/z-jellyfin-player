package cn.jiuwusan.jellyfin.player;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Both local display modes share data and actions; only section composition differs. */
final class HomeContent {
    static String title(HomeFeed.Section section){
        switch(section){case RESUME:return "继续观看";case NEXT:return "接下来";default:return "最近添加";}
    }
    static void render(LinearLayout target,LibraryUi style,HomeFeed.Result<JellyfinApi.Item> data,int width,
                       BrowseMemory memory,LibraryUi.ItemAction details,LibraryUi.ItemAction play,Runnable library){
        target.removeAllViews();
        boolean shown=false;
        for(HomeFeed.Section section:HomeFeed.Section.values()){
            java.util.List<JellyfinApi.Item> items=data.list(section);
            if(items.isEmpty()||style.largeUi()&&section!=data.primary)continue;
            TextView heading=style.label(title(section),style.size(26,22),LibraryUi.TEXT,true);
            heading.setPadding(0,shown?style.dp(28):0,0,style.dp(16));target.addView(heading);
            if(style.largeUi()){
                LinearLayout stack=style.column();
                for(JellyfinApi.Item item:items){
                    LinearLayout.LayoutParams bounds=new LinearLayout.LayoutParams(-1,-2);
                    if(stack.getChildCount()>0)bounds.topMargin=style.dp(20);
                    stack.addView(style.feedCard(item,section,width,true,details,play),bounds);
                }
                target.addView(stack);
            }else target.addView(new HomeRail(style,items,section,width,memory,details,play),new LinearLayout.LayoutParams(-1,-2));
            shown=true;
        }
        if(!shown){
            LoadingUi.empty(target,style,"暂无可观看的影片","继续观看、接下来和最近添加均为空。");
            TextView button=style.label("前往媒体库",style.size(22,14),LibraryUi.ACCENT_LIGHT,true);
            button.setMinHeight(style.dp(56));style.scrollActionable(button,library);target.addView(button);
        }
    }
}
