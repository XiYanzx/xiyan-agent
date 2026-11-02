// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import io.xiyan.agent.account.SiwcClient;
import io.xiyan.agent.core.usage.QuotaSnapshot;
import java.util.EnumMap;

final class NavigationDrawer {
    private final MainActivity host;
    private final FrameLayout overlay;
    private final View scrim,main;
    private final LinearLayout panel,footer;
    private final TextView accountLabel,usageLabel;
    private final LinearLayout draftRow;
    private final EnumMap<MainActivity.Screen,LinearLayout> destinations=new EnumMap<>(MainActivity.Screen.class);
    private final EnumMap<MainActivity.Screen,TextView> labels=new EnumMap<>(MainActivity.Screen.class);
    private final EnumMap<MainActivity.Screen,AgentIconView> icons=new EnumMap<>(MainActivity.Screen.class);
    private int animationGeneration;
    private boolean open;
    NavigationDrawer(MainActivity host,FrameLayout root){
        this.host=host;Ui.Colors c=Ui.colors(host);main=root.getChildAt(0);
        overlay=new FrameLayout(host);overlay.setVisibility(View.GONE);
        scrim=new View(host);scrim.setBackgroundColor(0x66050d0b);scrim.setContentDescription("关闭侧栏遮罩");
        scrim.setOnClickListener(v->close());overlay.addView(scrim,new FrameLayout.LayoutParams(-1,-1));
        panel=Ui.column(host);panel.setAccessibilityPaneTitle("导航");panel.setBackgroundColor(c.sidebar);panel.setPadding(Ui.dp(host,16),Ui.dp(host,8),Ui.dp(host,16),Ui.dp(host,16));panel.setClickable(true);
        LinearLayout brand=Ui.row(host);brand.addView(new AgentIconView(host,AgentIconView.Icon.ORBIT,c.accent),new LinearLayout.LayoutParams(Ui.dp(host,28),Ui.dp(host,28)));
        TextView name=Ui.heading(host,"Xiyan",21);LinearLayout.LayoutParams title=new LinearLayout.LayoutParams(0,-2,1);title.setMargins(Ui.dp(host,10),0,0,0);brand.addView(name,title);
        brand.addView(Ui.iconButton(host,AgentIconView.Icon.CLOSE,"关闭导航",c.muted,this::close),new LinearLayout.LayoutParams(Ui.dp(host,48),Ui.dp(host,48)));panel.addView(brand);
        Ui.gap(host,panel,16);ScrollView scroll=new ScrollView(host);scroll.setFillViewport(false);
        LinearLayout links=Ui.column(host);
        destination(links,MainActivity.Screen.CHAT,AgentIconView.Icon.CHAT,"对话");
        draftRow=row(links,AgentIconView.Icon.SAVE,"已保存草稿",host::loadSavedDraft);
        TextView group=Ui.text(host,"工作空间",12,Ui.MUTED);LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-1,-2);gp.setMargins(Ui.dp(host,12),Ui.dp(host,24),0,Ui.dp(host,8));links.addView(group,gp);
        destination(links,MainActivity.Screen.TASKS,AgentIconView.Icon.TASKS,"任务");
        destination(links,MainActivity.Screen.MODELS,AgentIconView.Icon.MODEL,"模型");
        destination(links,MainActivity.Screen.USAGE,AgentIconView.Icon.CHART,"用量");
        destination(links,MainActivity.Screen.SETTINGS,AgentIconView.Icon.SETTINGS,"设置");
        scroll.addView(links);panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));Ui.divider(host,panel);
        footer=Ui.row(host);footer.setPadding(Ui.dp(host,10),Ui.dp(host,8),Ui.dp(host,8),Ui.dp(host,8));footer.setMinimumHeight(Ui.dp(host,72));footer.setFocusable(true);footer.setClickable(true);
        footer.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22087f72),Ui.shape(host,Color.TRANSPARENT,14,Color.TRANSPARENT),null));
        footer.addView(new AgentIconView(host,AgentIconView.Icon.USER,c.muted),new LinearLayout.LayoutParams(Ui.dp(host,24),Ui.dp(host,24)));
        LinearLayout words=Ui.column(host);accountLabel=Ui.heading(host,"账号",14);usageLabel=Ui.text(host,"",12,Ui.MUTED);words.addView(accountLabel);words.addView(usageLabel);
        LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,-2,1);wp.setMargins(Ui.dp(host,12),0,0,0);footer.addView(words,wp);
        footer.setOnClickListener(v->host.destination(MainActivity.Screen.ACCOUNT));panel.addView(footer);
        int width=Math.min(320,Math.round(host.getResources().getConfiguration().screenWidthDp*.86f));
        overlay.addView(panel,new FrameLayout.LayoutParams(Ui.dp(host,width),-1,Gravity.START));root.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
    }
    private LinearLayout row(LinearLayout parent,AgentIconView.Icon icon,String title,Runnable action){
        Ui.Colors c=Ui.colors(host);LinearLayout row=new LinearLayout(host){
            @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName(Button.class.getName());}
        };
        row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(Ui.dp(host,12),Ui.dp(host,8),Ui.dp(host,12),Ui.dp(host,8));row.setMinimumHeight(Ui.dp(host,52));
        row.addView(new AgentIconView(host,icon,c.muted),new LinearLayout.LayoutParams(Ui.dp(host,22),Ui.dp(host,22)));
        TextView text=Ui.text(host,title,15,Ui.TEXT);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(Ui.dp(host,14),0,0,0);row.addView(text,lp);
        row.setContentDescription(title);row.setFocusable(true);row.setClickable(true);row.setOnClickListener(v->action.run());
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22087f72),Ui.shape(host,Color.TRANSPARENT,12,Color.TRANSPARENT),null));
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.setMargins(0,Ui.dp(host,2),0,Ui.dp(host,2));parent.addView(row,rp);return row;
    }
    private void destination(LinearLayout parent,MainActivity.Screen screen,AgentIconView.Icon icon,String title){
        LinearLayout row=row(parent,icon,title,()->host.destination(screen));destinations.put(screen,row);labels.put(screen,(TextView)row.getChildAt(1));icons.put(screen,(AgentIconView)row.getChildAt(0));
    }
    void open(){
        android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)host.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if(keyboard!=null)keyboard.hideSoftInputFromWindow(host.getWindow().getDecorView().getWindowToken(),0);
        ++animationGeneration;boolean wasVisible=overlay.getVisibility()==View.VISIBLE;
        panel.animate().cancel();scrim.animate().cancel();open=true;overlay.setVisibility(View.VISIBLE);overlay.bringToFront();
        main.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);update(host.account().snapshot());
        if(Ui.motionEnabled()&&!wasVisible){
            float width=panel.getLayoutParams().width;panel.setTranslationX(overlay.getLayoutDirection()==View.LAYOUT_DIRECTION_RTL?width:-width);scrim.setAlpha(0);
            panel.animate().translationX(0).setDuration(DesignTokens.MOTION_PANEL).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            scrim.animate().alpha(1).setDuration(DesignTokens.MOTION_PANEL).start();
        }else{panel.setTranslationX(0);scrim.setAlpha(1);}
    }
    void close(){
        if(!open&&overlay.getVisibility()!=View.VISIBLE)return;open=false;int generation=++animationGeneration;
        panel.animate().cancel();scrim.animate().cancel();
        Runnable finished=()->{if(generation!=animationGeneration||open)return;overlay.setVisibility(View.GONE);main.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);};
        if(Ui.motionEnabled()){
            float width=panel.getLayoutParams().width;panel.animate().translationX(overlay.getLayoutDirection()==View.LAYOUT_DIRECTION_RTL?width:-width).setDuration(DesignTokens.MOTION_ENTER).start();
            scrim.animate().alpha(0).setDuration(DesignTokens.MOTION_ENTER).withEndAction(finished).start();
        }else finished.run();
    }
    boolean isOpen(){return open;}
    void update(SiwcClient.Snapshot s){
        Ui.Colors c=Ui.colors(host);draftRow.setVisibility(host.hasSavedDraft()?View.VISIBLE:View.GONE);
        MainActivity.Screen selected=host.currentScreen();
        if(selected==MainActivity.Screen.APPEARANCE||selected==MainActivity.Screen.ABOUT||selected==MainActivity.Screen.CONTROLS)selected=MainActivity.Screen.SETTINGS;
        for(MainActivity.Screen screen:destinations.keySet()){
            LinearLayout row=destinations.get(screen);boolean active=screen==selected;
            if(row.isSelected()!=active){
                row.setSelected(active);row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22087f72),Ui.shape(host,active?c.accentSurface:Color.TRANSPARENT,12,Color.TRANSPARENT),null));
                TextView label=labels.get(screen);label.setTextColor(active?c.accent:c.text);label.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL),active?600:400,false));
                icons.get(screen).tint(active?c.accent:c.muted);
            }
        }
        String name=s.hasSession?"ChatGPT 账号":"连接账号";if(!accountLabel.getText().toString().equals(name))accountLabel.setText(name);
        String status=!s.hasSession?"登录并选择你的模型":s.reauthRequired?"需要重新连接":s.connected?"已连接":"会话已保存 · 待续期";
        QuotaSnapshot.Window window=s.quota.getPrimary();
        if(s.hasSession&&window!=null)status+=" · "+(window.isStale(System.currentTimeMillis())?"上次剩余 ":"剩余 ")+Ui.percent(window.getRemainingPercent())+"%";
        else if(s.hasSession&&s.tokenUsage!=null&&s.tokenUsage.getTotalTokens()!=null)status+=" · 最近 "+s.tokenUsage.getTotalTokens()+" token";
        if(!usageLabel.getText().toString().equals(status))usageLabel.setText(status);usageLabel.setTextColor(s.reauthRequired?c.warning:c.muted);footer.setContentDescription(accountLabel.getText()+"，"+status);
    }
}
