// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import io.xiyan.agent.account.SiwcClient;

final class TasksScreen implements ScreenContent {
    private final View root;
    TasksScreen(MainActivity host) {
        Ui.Colors c=Ui.colors(host);LinearLayout page=Ui.page(host);LinearLayout empty=Ui.column(host);
        empty.setPadding(0,Ui.dp(host,30),0,Ui.dp(host,30));empty.setGravity(Gravity.CENTER);
        empty.addView(new AgentIconView(host,AgentIconView.Icon.TASKS,c.secondary),new LinearLayout.LayoutParams(Ui.dp(host,48),Ui.dp(host,48)));
        Ui.gap(host,empty,14);empty.addView(Ui.heading(host,"暂时还没有任务",22));
        empty.addView(Ui.text(host,"手机自主执行待接入",14,Ui.MUTED));page.addView(empty);
        page.addView(Ui.text(host,"执行功能接入后，这里会显示真实步骤、失败原因和暂停、恢复入口。",14,Ui.MUTED));
        Ui.section(host,page,"先准备任务草稿");
        Ui.listRow(host,page,AgentIconView.Icon.PHONE,c.accent,"通用手机任务","写下使用的应用、目标与完成条件",()->{
            host.fillTaskDraft("通用操作","我想在手机上完成：\n使用的应用：\n完成条件：");
        });
        Ui.listRow(host,page,AgentIconView.Icon.GAME,c.secondary,"Galaxy 联合防御","先描述目标，游戏执行适配待完成",()->{
            host.fillTaskDraft("游戏操作","游戏：Galaxy 联合防御\n本次目标：\n停止条件：");
        });
        Ui.section(host,page,"执行准备");
        Ui.listRow(host,page,AgentIconView.Icon.USER,c.success,"账号连接","管理当前账号",()->host.navigate(MainActivity.Screen.ACCOUNT));
        Ui.listRow(host,page,AgentIconView.Icon.MODEL,c.secondary,"模型设置","选择实际账号模型",()->host.navigate(MainActivity.Screen.MODELS));
        Ui.listRow(host,page,AgentIconView.Icon.SETTINGS,c.accent,"手机控制","查看系统权限状态",()->host.navigate(MainActivity.Screen.CONTROLS));
        root=Ui.scroll(host,page);
    }
    @Override public View view(){return root;}
    @Override public void update(SiwcClient.Snapshot snapshot){ }
}
