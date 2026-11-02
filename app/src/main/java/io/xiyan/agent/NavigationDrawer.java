// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
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

/** A flat contents page; selection is a rule and weight, rather than a tinted panel. */
final class NavigationDrawer {
    private static final class Entry {
        final LinearLayout view;
        final View marker;
        final TextView number,label;
        Entry(LinearLayout view,View marker,TextView number,TextView label){this.view=view;this.marker=marker;this.number=number;this.label=label;}
    }
    private final MainActivity host;
    private final FrameLayout overlay;
    private final View scrim,main;
    private final LinearLayout panel,footer,draftRow;
    private final TextView accountLabel,usageLabel;
    private final EnumMap<MainActivity.Screen,Entry> destinations=new EnumMap<>(MainActivity.Screen.class);
    private int animationGeneration;
    private boolean open;
    NavigationDrawer(MainActivity host,FrameLayout root){
        this.host=host;Ui.Colors c=Ui.colors(host);main=root.getChildAt(0);
        overlay=new FrameLayout(host);overlay.setVisibility(View.GONE);
        scrim=new View(host);scrim.setBackgroundColor(0x66000000);scrim.setContentDescription("关闭侧栏遮罩");
        scrim.setOnClickListener(v->close());overlay.addView(scrim,new FrameLayout.LayoutParams(-1,-1));
        panel=Ui.column(host);panel.setAccessibilityPaneTitle("导航目录");panel.setBackgroundColor(c.background);panel.setPadding(Ui.dp(host,20),Ui.dp(host,12),Ui.dp(host,20),Ui.dp(host,16));panel.setClickable(true);
        LinearLayout brand=Ui.row(host);TextView name=Ui.heading(host,"XIYAN.",32);name.setLetterSpacing(-.04f);brand.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        brand.addView(Ui.iconButton(host,AgentIconView.Icon.CLOSE,"关闭导航",c.text,this::close),new LinearLayout.LayoutParams(Ui.dp(host,48),Ui.dp(host,48)));panel.addView(brand);
        TextView index=Ui.text(host,"目录 / 00",12,c.accent);index.setTypeface(Typeface.MONOSPACE);panel.addView(index);Ui.gap(host,panel,16);rule(panel);Ui.gap(host,panel,8);
        ScrollView scroll=new ScrollView(host);scroll.setFillViewport(false);LinearLayout links=Ui.column(host);
        destination(links,MainActivity.Screen.CHAT,"01","对话");
        draftRow=row(links,"—","已保存草稿",host::loadSavedDraft).view;
        Ui.gap(host,links,16);rule(links);Ui.gap(host,links,8);
        destination(links,MainActivity.Screen.TASKS,"02","任务");
        destination(links,MainActivity.Screen.MODELS,"03","模型");
        destination(links,MainActivity.Screen.USAGE,"04","用量");
        destination(links,MainActivity.Screen.SETTINGS,"05","设置");
        scroll.addView(links);panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));rule(panel);
        footer=Ui.row(host);footer.setPadding(0,Ui.dp(host,16),0,0);footer.setMinimumHeight(Ui.dp(host,76));footer.setFocusable(true);footer.setClickable(true);
        footer.setBackground(new RippleDrawable(ColorStateList.valueOf(c.accentSurface),Ui.shape(host,Color.TRANSPARENT,0,Color.TRANSPARENT),null));
        footer.addView(new AgentIconView(host,AgentIconView.Icon.USER,c.text),new LinearLayout.LayoutParams(Ui.dp(host,24),Ui.dp(host,24)));
        LinearLayout words=Ui.column(host);accountLabel=Ui.heading(host,"账号",14);usageLabel=Ui.text(host,"",12,Ui.MUTED);words.addView(accountLabel);Ui.gap(host,words,4);words.addView(usageLabel);
        LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,-2,1);wp.setMargins(Ui.dp(host,12),0,0,0);footer.addView(words,wp);
        footer.setOnClickListener(v->host.destination(MainActivity.Screen.ACCOUNT));panel.addView(footer);
        int width=Math.min(320,Math.round(host.getResources().getConfiguration().screenWidthDp*.86f));
        overlay.addView(panel,new FrameLayout.LayoutParams(Ui.dp(host,width),-1,Gravity.START));root.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
    }
    private void rule(LinearLayout parent){View line=new View(host);line.setBackgroundColor(Ui.colors(host).border);parent.addView(line,new LinearLayout.LayoutParams(-1,Ui.dp(host,1)));}
    private Entry row(LinearLayout parent,String index,String title,Runnable action){
        Ui.Colors c=Ui.colors(host);LinearLayout row=new LinearLayout(host){
            @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName(Button.class.getName());}
        };
        row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,Ui.dp(host,8),Ui.dp(host,4),Ui.dp(host,8));row.setMinimumHeight(Ui.dp(host,56));
        View marker=new View(host);marker.setBackgroundColor(c.accent);marker.setVisibility(View.INVISIBLE);marker.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(Ui.dp(host,3),Ui.dp(host,22));mp.setMargins(0,0,Ui.dp(host,12),0);row.addView(marker,mp);
        TextView number=Ui.text(host,index,12,c.muted);number.setTypeface(Typeface.MONOSPACE);number.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.addView(number,new LinearLayout.LayoutParams(Ui.dp(host,34),-2));
        TextView text=Ui.text(host,title,16,Ui.TEXT);text.setTypeface(Typeface.create(Typeface.create("sans-serif",Typeface.NORMAL),500,false));row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        row.setContentDescription(title);row.setFocusable(true);row.setClickable(true);row.setOnClickListener(v->action.run());
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(c.accentSurface),Ui.shape(host,Color.TRANSPARENT,0,Color.TRANSPARENT),null));parent.addView(row,new LinearLayout.LayoutParams(-1,-2));return new Entry(row,marker,number,text);
    }
    private void destination(LinearLayout parent,MainActivity.Screen screen,String number,String title){destinations.put(screen,row(parent,number,title,()->host.destination(screen)));}
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
            Entry entry=destinations.get(screen);boolean active=screen==selected;
            if(entry.view.isSelected()!=active){
                entry.view.setSelected(active);entry.marker.setVisibility(active?View.VISIBLE:View.INVISIBLE);entry.number.setTextColor(active?c.accent:c.muted);
                entry.label.setTypeface(Typeface.create(Typeface.create("sans-serif",Typeface.NORMAL),active?700:500,false));entry.view.setContentDescription(entry.label.getText()+(active?"，当前页面":""));
            }
        }
        String name=s.hasSession?"ChatGPT 账号":"连接账号";if(!accountLabel.getText().toString().equals(name))accountLabel.setText(name);
        String status=!s.hasSession?"登录并选择你的模型":s.reauthRequired?"需要重新连接":s.connected?"已连接":"会话已保存 · 待续期";
        QuotaSnapshot.Window window=s.quota.getPrimary();
        if(s.hasSession&&window!=null)status+=" · "+(window.isStale(System.currentTimeMillis())?"上次剩余 ":"剩余 ")+Ui.percent(window.getRemainingPercent())+"%";
        else if(s.hasSession&&s.tokenUsage!=null&&s.tokenUsage.getTotalTokens()!=null)status+=" · 最近 "+s.tokenUsage.getTotalTokens()+" token";
        if(!usageLabel.getText().toString().equals(status))usageLabel.setText(status);usageLabel.setTextColor(s.reauthRequired?c.alert:c.muted);footer.setContentDescription(accountLabel.getText()+"，"+status);
    }
}