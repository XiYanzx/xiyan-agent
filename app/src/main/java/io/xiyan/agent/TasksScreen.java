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
        TextView chapter=Ui.text(host,"02 / 任务",12,c.accent);chapter.setTypeface(Typeface.MONOSPACE);page.addView(chapter);
        Ui.gap(host,page,8);page.addView(Ui.heading(host,"执行清单",28));Ui.gap(host,page,8);
        page.addView(Ui.text(host,"写下目标，补充完成与停止条件。",14,Ui.MUTED));Ui.gap(host,page,24);rule(page);
        taskEntry(page,"01","通用手机任务","应用、目标与完成条件",()->host.fillTaskDraft("通用操作","我想在手机上完成：\n使用的应用：\n完成条件："));rule(page);
        taskEntry(page,"02","Galaxy 联合防御","策略目标与停止条件",()->host.fillTaskDraft("游戏操作","游戏：Galaxy 联合防御\n本次目标：\n停止条件："));rule(page);
        Ui.gap(host,page,24);TextView readiness=Ui.text(host,"准备状态 / 系统报告",12,Ui.MUTED);readiness.setTypeface(Typeface.MONOSPACE);page.addView(readiness);Ui.gap(host,page,8);
        accountState=preparation(page,"账号",()->host.navigate(MainActivity.Screen.ACCOUNT));
        modelState=preparation(page,"模型",()->host.navigate(MainActivity.Screen.MODELS));
        controlState=preparation(page,"无障碍服务",()->host.navigate(MainActivity.Screen.CONTROLS));
        Ui.gap(host,page,20);rule(page);Ui.gap(host,page,16);
        page.addView(Ui.text(host,"手机自动执行待接入",14,Ui.TEXT));Ui.gap(host,page,4);
        page.addView(Ui.text(host,"当前可先保存任务草稿。",12,Ui.MUTED));
        root=Ui.scroll(host,page);
    }
    private void rule(LinearLayout parent){View line=new View(host);line.setBackgroundColor(Ui.colors(host).border);parent.addView(line,new LinearLayout.LayoutParams(-1,Ui.dp(host,1)));}
    private void taskEntry(LinearLayout parent,String number,String title,String description,Runnable action) {
        LinearLayout row=clickableRow(action);row.setMinimumHeight(Ui.dp(host,88));row.setPadding(0,Ui.dp(host,16),0,Ui.dp(host,16));
        TextView index=Ui.text(host,number,22,Ui.colors(host).accent);index.setTypeface(Typeface.MONOSPACE);row.addView(index,new LinearLayout.LayoutParams(Ui.dp(host,44),-2));
        LinearLayout words=Ui.column(host);words.addView(Ui.heading(host,title,16));Ui.gap(host,words,4);words.addView(Ui.text(host,description,12,Ui.MUTED));
        LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,-2,1);wp.setMargins(0,0,Ui.dp(host,8),0);row.addView(words,wp);
        row.addView(new AgentIconView(host,AgentIconView.Icon.CHEVRON,Ui.colors(host).text),new LinearLayout.LayoutParams(Ui.dp(host,20),Ui.dp(host,20)));
        row.setContentDescription(title+"，"+description+"，准备任务草稿");parent.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }
    private TextView preparation(LinearLayout parent,String title,Runnable action) {
        LinearLayout row=clickableRow(action);row.setMinimumHeight(Ui.dp(host,60));
        row.addView(Ui.text(host,title,14,Ui.TEXT),new LinearLayout.LayoutParams(0,-2,1));
        TextView value=Ui.text(host,"",12,Ui.MUTED);value.setGravity(Gravity.END);LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(0,-2,1.5f);vp.setMargins(Ui.dp(host,8),0,Ui.dp(host,8),0);row.addView(value,vp);
        row.addView(new AgentIconView(host,AgentIconView.Icon.CHEVRON,Ui.colors(host).muted),new LinearLayout.LayoutParams(Ui.dp(host,20),Ui.dp(host,20)));
        value.setTag(title);parent.addView(row,new LinearLayout.LayoutParams(-1,-2));return value;
    }
    private LinearLayout clickableRow(Runnable action) {
        LinearLayout row=new LinearLayout(host){
            @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName(Button.class.getName());}
        };
        row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setFocusable(true);row.setClickable(true);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.colors(host).accentSurface),Ui.shape(host,Color.TRANSPARENT,0,Color.TRANSPARENT),null));row.setOnClickListener(v->action.run());return row;
    }
    @Override public View view(){return root;}
    @Override public void update(SiwcClient.Snapshot snapshot){
        preparationState(accountState,snapshot.reauthRequired?"需要重新连接":snapshot.connected?"已连接":snapshot.hasSession?"已保存，刷新可续期":"尚未连接");
        preparationState(modelState,host.configuredSelection(snapshot)==null?(snapshot.catalog==null?"等待模型列表":"待选择模型"):host.modelLabel(snapshot));
        preparationState(controlState,XiyanAccessibilityService.getConnectedService()==null?"未连接":"已连接");
    }
    private void preparationState(TextView value,String state){if(!value.getText().toString().equals(state))value.setText(state);((View)value.getParent()).setContentDescription(value.getTag()+"，"+state+"，查看设置");}
}
