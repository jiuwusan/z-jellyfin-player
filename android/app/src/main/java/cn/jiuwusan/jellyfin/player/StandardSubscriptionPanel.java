package cn.jiuwusan.jellyfin.player;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Read-only subscription presentation. Actions stay in the existing Activity/controller. */
final class StandardSubscriptionPanel extends LinearLayout {
    final TextView status;
    StandardSubscriptionPanel(LibraryUi style, SubscriptionSettings active, ConnectionSettings managed,
                              Runnable copy, Runnable sync, Runnable edit, Runnable clear) {
        super(style.context()); setOrientation(VERTICAL);
        caption(style,"当前订阅");
        status=style.label(active.blocked ? "订阅已停用" : "已同步 · v"+active.configuration.revision,14,LibraryUi.TEXT,false);
        status.setPadding(0,0,0,style.dp(16));
        addView(status);
        LinearLayout link=new LinearLayout(getContext()); link.setGravity(Gravity.CENTER_VERTICAL);
        link.setBackground(style.rounded(0x08ffffff,LibraryUi.RADIUS_SMALL));
        link.setPadding(style.dp(10),style.dp(4),0,style.dp(4));
        TextView address=style.label(SubscriptionClient.Link.parse(active.link).url+"#********",12,LibraryUi.MUTED,false);
        address.setSingleLine(); address.setEllipsize(android.text.TextUtils.TruncateAt.END);
        link.addView(address,new LinearLayout.LayoutParams(0,-2,1));
        ImageView copyIcon=new ImageView(getContext()); copyIcon.setImageResource(R.drawable.ic_copy);
        copyIcon.setPadding(style.dp(12),style.dp(12),style.dp(12),style.dp(12));
        copyIcon.setContentDescription("复制订阅链接");
        link.addView(copyIcon,new LinearLayout.LayoutParams(style.dp(44),style.dp(44)));
        style.actionable(copyIcon,copy); addView(link,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout actions=new LinearLayout(getContext());
        LinearLayout.LayoutParams actionBounds=new LinearLayout.LayoutParams(-1,-2); actionBounds.topMargin=style.dp(12);
        addView(actions,actionBounds);
        action(style,actions,"立即同步",true,sync); action(style,actions,"更换订阅",false,edit);
        caption(style,"连接信息");
        row(style,"连接方式",managed.hasProxy() ? "HTTP 代理" : "直连");
        if(managed.hasProxy()) row(style,"代理地址",managed.host+":"+managed.port);
        row(style,"Jellyfin 服务",managed.server.toString()); row(style,"账号",managed.user);
        TextView note=style.label("连接与画质由订阅管理。更换或清除订阅需要确认密码。",12,LibraryUi.MUTED,false);
        note.setPadding(0,style.dp(16),0,style.dp(10)); note.setLineSpacing(0,1.3f); addView(note);
        TextView remove=style.label("清除订阅",12,LibraryUi.MUTED,false);
        remove.setMinHeight(style.dp(44)); remove.setGravity(Gravity.CENTER_VERTICAL); addView(remove);
        style.actionable(remove,clear);
    }
    private void caption(LibraryUi style,String text) {
        TextView caption=style.label(text,12,LibraryUi.MUTED,false);
        caption.setPadding(0,style.dp(20),0,style.dp(14)); addView(caption);
    }
    private void row(LibraryUi style,String label,String value) {
        LinearLayout row=new LinearLayout(getContext()); row.setGravity(Gravity.TOP); row.setPadding(0,style.dp(7),0,style.dp(7));
        row.addView(style.label(label,12,LibraryUi.MUTED,false),new LinearLayout.LayoutParams(style.dp(92),-2));
        TextView content=style.label(value,12,LibraryUi.MUTED,false); content.setGravity(Gravity.END);
        row.addView(content,new LinearLayout.LayoutParams(0,-2,1)); addView(row);
    }
    private void action(LibraryUi style,LinearLayout parent,String title,boolean primary,Runnable callback) {
        TextView button=style.label(title,13,primary ? LibraryUi.ACCENT_LIGHT : LibraryUi.MUTED,primary);
        button.setGravity(Gravity.CENTER); button.setMinHeight(style.dp(44)); button.setPadding(style.dp(12),0,style.dp(12),0);
        android.graphics.drawable.GradientDrawable background=style.rounded(primary ? 0x1800a4dc : 0x05ffffff,LibraryUi.RADIUS_SMALL);
        background.setStroke(style.dp(1),primary ? 0x4000a4dc : 0x20ffffff); button.setBackground(background);
        LinearLayout.LayoutParams bounds=new LinearLayout.LayoutParams(-2,-2); bounds.setMarginEnd(style.dp(9));
        parent.addView(button,bounds); style.actionable(button,callback);
    }
}
