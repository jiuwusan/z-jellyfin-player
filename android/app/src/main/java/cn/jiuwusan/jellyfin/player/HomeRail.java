package cn.jiuwusan.jellyfin.player;

import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSnapHelper;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

/** Bounded horizontal cards. Session memory holds only item identity and offset, never Views. */
final class HomeRail extends RecyclerView {
    static final class Position {
        final String id; final int offset;
        Position(String id,int offset){this.id=id;this.offset=offset;}
    }
    final HomeFeed.Section section;
    final int cardWidth;
    private final LibraryUi style;
    private final List<JellyfinApi.Item> items;
    private final LinearLayoutManager layout;
    private final BrowseMemory memory;
    private final String memoryKey;
    private float downX,downY;
    private boolean directionChosen;
    HomeRail(LibraryUi style,List<JellyfinApi.Item> items,HomeFeed.Section section,int availableWidth,
             BrowseMemory memory,LibraryUi.ItemAction details,LibraryUi.ItemAction play){
        super(style.context());this.style=style;this.items=items;this.section=section;this.memory=memory;
        memoryKey="homeRail:"+section.name();
        cardWidth=Math.max(1,Math.round(availableWidth*(section==HomeFeed.Section.RESUME?.86f:section==HomeFeed.Section.NEXT?.53f:.30f)));
        layout=new LinearLayoutManager(getContext(),HORIZONTAL,false);setLayoutManager(layout);
        setHorizontalScrollBarEnabled(false);setItemAnimator(null);setItemViewCacheSize(2);setNestedScrollingEnabled(false);
        setContentDescription(HomeContent.title(section)+"，横向滑动浏览");
        addItemDecoration(new ItemDecoration(){@Override public void getItemOffsets(Rect out,View view,RecyclerView parent,State state){
            if(parent.getChildAdapterPosition(view)<items.size()-1)out.right=style.dp(12);
        }});
        setAdapter(new Adapter<Holder>(){
            @Override public Holder onCreateViewHolder(ViewGroup parent,int type){
                FrameLayout box=new FrameLayout(getContext());box.setLayoutParams(new RecyclerView.LayoutParams(cardWidth,ViewGroup.LayoutParams.WRAP_CONTENT));return new Holder(box);
            }
            @Override public void onBindViewHolder(Holder holder,int index){
                clear(holder);JellyfinApi.Item item=items.get(index);
                holder.box.addView(style.feedCard(item,section,cardWidth,false,details,play),new FrameLayout.LayoutParams(-1,-2));
            }
            @Override public int getItemCount(){return items.size();}
            @Override public void onViewRecycled(Holder holder){clear(holder);}
            private void clear(Holder holder){style.releasePictures(holder.box);holder.box.removeAllViews();}
        });
        Position previous=memory.get(memoryKey);
        if(previous!=null)for(int i=0;i<items.size();i++)if(items.get(i).id.equals(previous.id)){layout.scrollToPositionWithOffset(i,previous.offset);break;}
        addOnScrollListener(new OnScrollListener(){@Override public void onScrolled(RecyclerView view,int dx,int dy){remember();}});
        new LinearSnapHelper(){
            @Override public int[] calculateDistanceToFinalSnap(LayoutManager manager,View target){return new int[]{manager.getDecoratedLeft(target),0};}
            @Override public View findSnapView(LayoutManager manager){
                View nearest=null;int distance=Integer.MAX_VALUE;
                for(int i=0;i<manager.getChildCount();i++){View child=manager.getChildAt(i);int d=Math.abs(manager.getDecoratedLeft(child));if(d<distance){nearest=child;distance=d;}}
                return nearest;
            }
        }.attachToRecyclerView(this);
    }
    private void remember(){
        int first=layout.findFirstVisibleItemPosition();View child=layout.findViewByPosition(first);
        if(first>=0&&first<items.size()&&child!=null)memory.put(memoryKey,new Position(items.get(first).id,layout.getDecoratedLeft(child)),SystemClock.uptimeMillis());
    }
    @Override protected void onDetachedFromWindow(){remember();super.onDetachedFromWindow();}
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){downX=event.getX();downY=event.getY();directionChosen=false;getParent().requestDisallowInterceptTouchEvent(true);}
        else if(action==MotionEvent.ACTION_MOVE&&!directionChosen){
            float x=Math.abs(event.getX()-downX),y=Math.abs(event.getY()-downY);
            if(Math.max(x,y)>ViewConfiguration.get(getContext()).getScaledTouchSlop()){
                directionChosen=true;getParent().requestDisallowInterceptTouchEvent(x>=y);
            }
        }
        boolean handled=super.dispatchTouchEvent(event);
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)getParent().requestDisallowInterceptTouchEvent(false);
        return handled;
    }
    private static final class Holder extends ViewHolder {final FrameLayout box;Holder(FrameLayout box){super(box);this.box=box;}}
}
