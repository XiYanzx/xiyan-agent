// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import io.xiyan.agent.account.SiwcClient;
import io.xiyan.agent.core.usage.QuotaSnapshot;

final class NavigationDrawer {
    private final MainActivity host;
    private final FrameLayout overlay;
    private final TextView accountLabel,usageLabel;
    private final LinearLayout draftRow;
    NavigationDrawer(MainActivity host,FrameLayout root) {
        this.host=host;Ui.Colors c=Ui.colors(host);overlay=new FrameLayout(host);overlay.setVisibility(View.GONE);
        View scrim=new View(host);scrim.setBackgroundColor(0x88000000);scrim.setOnClickListener(v->close());overlay.addView(scrim,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout panel=Ui.column(host);panel.setBackgroundColor(c.sidebar);panel.setPadding(Ui.dp(host,16),Ui.dp(host,14),Ui.dp(host,16),Ui.dp(host,16));panel.setClickable(true);
        LinearLayout brand=Ui.row(host);brand.addView(new AgentIconView(host,AgentIconView.Icon.ORBIT,c.accent),new LinearLayout.LayoutParams(Ui.dp(host,32),Ui.dp(host,32)));
        TextView name=Ui.heading(host,"Xiyan",22);LinearLayout.LayoutParams title=new LinearLayout.LayoutParams(0,-2,1);title.setMargins(Ui.dp(host,10),0,0,0);brand.addView(name,title);
        brand.addView(Ui.iconButton(host,AgentIconView.Icon.CLOSE,"关闭导航",c.muted,this::close),new LinearLayout.LayoutParams(Ui.dp(host,48),Ui.dp(host,48)));panel.addView(brand);
        ScrollView scroll=new ScrollView(host);LinearLayout links=Ui.column(host);
        Ui.section(host,links,"对话");
        Ui.listRow(host,links,AgentIconView.Icon.CHAT,c.accent,"当前对话","当前提问与回复",()->host.destination(MainActivity.Screen.CHAT));
        draftRow=Ui.listRow(host,links,AgentIconView.Icon.SAVE,c.secondary,"已保存草稿","保存在此设备",host::loadSavedDraft);
        Ui.section(host,links,"工作空间");
        Ui.listRow(host,links,AgentIconView.Icon.TASKS,c.accent,"任务","目标与执行进度",()->host.destination(MainActivity.Screen.TASKS));
        Ui.listRow(host,links,AgentIconView.Icon.MODEL,c.secondary,"模型","模型列表与思考设置",()->host.destination(MainActivity.Screen.MODELS));
        Ui.listRow(host,links,AgentIconView.Icon.CHART,c.success,"用量","官方额度与最近调用",()->host.destination(MainActivity.Screen.USAGE));
        Ui.listRow(host,links,AgentIconView.Icon.SETTINGS,c.muted,"设置","外观、手机控制与关于",()->host.destination(MainActivity.Screen.SETTINGS));
        scroll.addView(links);panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));Ui.divider(host,panel);
        LinearLayout footer=Ui.row(host);footer.setMinimumHeight(Ui.dp(host,64));footer.setPadding(Ui.dp(host,8),0,0,0);footer.setFocusable(true);footer.setClickable(true);
        footer.addView(new AgentIconView(host,AgentIconView.Icon.USER,c.accent),new LinearLayout.LayoutParams(Ui.dp(host,32),Ui.dp(host,32)));
        LinearLayout words=Ui.column(host);accountLabel=Ui.heading(host,"账号",15);usageLabel=Ui.text(host,"",12,Ui.MUTED);words.addView(accountLabel);words.addView(usageLabel);
        LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,-2,1);wp.setMargins(Ui.dp(host,12),0,0,0);footer.addView(words,wp);
        footer.setOnClickListener(v->host.destination(MainActivity.Screen.ACCOUNT));panel.addView(footer);
        int width=Math.min(320,Math.round(host.getResources().getConfiguration().screenWidthDp*.86f));
        overlay.addView(panel,new FrameLayout.LayoutParams(Ui.dp(host,width),-1,Gravity.START));root.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
    }
    void open(){
        android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)host.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if(keyboard!=null)keyboard.hideSoftInputFromWindow(host.getWindow().getDecorView().getWindowToken(),0);
        overlay.setVisibility(View.VISIBLE);overlay.bringToFront();update(host.account().snapshot());
    }
    void close(){overlay.setVisibility(View.GONE);}
    boolean isOpen(){return overlay.getVisibility()==View.VISIBLE;}
    void update(SiwcClient.Snapshot s) {
        draftRow.setVisibility(host.hasSavedDraft()?View.VISIBLE:View.GONE);
        accountLabel.setText(s.hasSession?"ChatGPT 账号":"连接账号");
        QuotaSnapshot.Window window=s.quota.getPrimary();
        usageLabel.setText(window==null?(s.hasSession?"官方用量待获取":"登录与账号管理"):
                "剩余 "+Ui.percent(window.getRemainingPercent())+"%"+(window.isStale(System.currentTimeMillis())?" · 上次读数":""));
    }
}
