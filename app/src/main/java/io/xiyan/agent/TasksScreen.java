// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.xiyan.agent.account.SiwcClient;
import io.xiyan.agent.control.XiyanAccessibilityService;

final class TasksScreen implements ScreenContent {
    private final MainActivity host;
    private final View root;
    private final TextView accountState,modelState,controlState;
    TasksScreen(MainActivity host) {
        this.host=host;Ui.Colors c=Ui.colors(host);LinearLayout page=Ui.page(host);
        page.addView(Ui.heading(host,"先准备一个目标",22));Ui.gap(host,page,8);
        page.addView(Ui.text(host,"写下想做的事，补充完成条件。",14,Ui.MUTED));Ui.gap(host,page,20);
        LinearLayout drafts=Ui.column(host);drafts.setPadding(Ui.dp(host,16),Ui.dp(host,4),Ui.dp(host,16),Ui.dp(host,4));
        drafts.setBackground(Ui.shape(host,c.surface,20,c.border));
        taskEntry(drafts,AgentIconView.Icon.PHONE,c.accent,"通用手机任务","应用、目标与完成条件",()->host.fillTaskDraft("通用操作","我想在手机上完成：\n使用的应用：\n完成条件："));
        Ui.divider(host,drafts);
        taskEntry(drafts,AgentIconView.Icon.GAME,c.secondary,"Galaxy 联合防御","策略目标与停止条件",()->host.fillTaskDraft("游戏操作","游戏：Galaxy 联合防御\n本次目标：\n停止条件："));page.addView(drafts);
        Ui.gap(host,page,20);LinearLayout progress=Ui.row(host);
        AgentIconView clock=new AgentIconView(host,AgentIconView.Icon.CLOCK,c.muted);clock.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        progress.addView(clock,new LinearLayout.LayoutParams(Ui.dp(host,24),Ui.dp(host,24)));
        LinearLayout progressWords=Ui.column(host);progressWords.addView(Ui.text(host,"自动操作准备中",14,Ui.TEXT));
        progressWords.addView(Ui.text(host,"当前可先保存任务草稿。",12,Ui.MUTED));LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(0,-2,1);pp.setMargins(Ui.dp(host,12),0,0,0);progress.addView(progressWords,pp);page.addView(progress);
        Ui.section(host,page,"准备状态");
        accountState=preparation(page,AgentIconView.Icon.USER,"账号",()->host.navigate(MainActivity.Screen.ACCOUNT));
        modelState=preparation(page,AgentIconView.Icon.MODEL,"模型",()->host.navigate(MainActivity.Screen.MODELS));
        controlState=preparation(page,AgentIconView.Icon.PHONE,"手机控制",()->host.navigate(MainActivity.Screen.CONTROLS));
        root=Ui.scroll(host,page);
    }
    private void taskEntry(LinearLayout parent,AgentIconView.Icon icon,int tint,String title,String description,Runnable action) {
        LinearLayout row=clickableRow(action);row.setMinimumHeight(Ui.dp(host,84));row.setPadding(0,Ui.dp(host,12),0,Ui.dp(host,12));
        AgentIconView mark=new AgentIconView(host,icon,tint);mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(mark,new LinearLayout.LayoutParams(Ui.dp(host,24),Ui.dp(host,24)));
        LinearLayout words=Ui.column(host);words.addView(Ui.heading(host,title,16));Ui.gap(host,words,4);words.addView(Ui.text(host,description,13,Ui.MUTED));
        LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,-2,1);wp.setMargins(Ui.dp(host,14),0,Ui.dp(host,8),0);row.addView(words,wp);
        AgentIconView next=new AgentIconView(host,AgentIconView.Icon.CHEVRON,Ui.colors(host).muted);next.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.addView(next,new LinearLayout.LayoutParams(Ui.dp(host,20),Ui.dp(host,20)));
        row.setContentDescription(title+"，"+description+"，准备任务草稿");parent.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }
    private TextView preparation(LinearLayout parent,AgentIconView.Icon icon,String title,Runnable action) {
        LinearLayout row=clickableRow(action);row.setMinimumHeight(Ui.dp(host,64));
        AgentIconView mark=new AgentIconView(host,icon,Ui.colors(host).muted);mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.addView(mark,new LinearLayout.LayoutParams(Ui.dp(host,24),Ui.dp(host,24)));
        LinearLayout words=Ui.column(host);words.addView(Ui.text(host,title,14,Ui.TEXT));TextView value=Ui.text(host,"",12,Ui.MUTED);words.addView(value);
        LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,-2,1);wp.setMargins(Ui.dp(host,14),0,Ui.dp(host,8),0);row.addView(words,wp);
        AgentIconView next=new AgentIconView(host,AgentIconView.Icon.CHEVRON,Ui.colors(host).muted);next.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.addView(next,new LinearLayout.LayoutParams(Ui.dp(host,20),Ui.dp(host,20)));
        value.setTag(title);parent.addView(row,new LinearLayout.LayoutParams(-1,-2));return value;
    }
    private LinearLayout clickableRow(Runnable action) {
        LinearLayout row=new LinearLayout(host){
            @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName(Button.class.getName());}
        };
        row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setFocusable(true);row.setClickable(true);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.colors(host).accentSurface),Ui.shape(host,Color.TRANSPARENT,12,Color.TRANSPARENT),null));row.setOnClickListener(v->action.run());return row;
    }
    @Override public View view(){return root;}
    @Override public void update(SiwcClient.Snapshot snapshot){
        preparationState(accountState,snapshot.reauthRequired?"需要重新连接":snapshot.connected?"已连接":snapshot.hasSession?"已保存，刷新可续期":"尚未连接");
        preparationState(modelState,host.configuredSelection(snapshot)==null?(snapshot.catalog==null?"等待获取模型列表":"待选择模型"):host.modelLabel(snapshot));
        preparationState(controlState,XiyanAccessibilityService.getConnectedService()==null?"服务未连接":"服务已连接");
    }
    private void preparationState(TextView value,String state){value.setText(state);((View)value.getParent().getParent()).setContentDescription(value.getTag()+"，"+state+"，查看设置");}
}
