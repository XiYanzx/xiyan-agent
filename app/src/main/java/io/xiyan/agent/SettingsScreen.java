// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

import android.content.res.ColorStateList;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import io.xiyan.agent.account.SiwcClient;
import io.xiyan.agent.control.XiyanAccessibilityService;

final class SettingsScreen implements ScreenContent {
    private final MainActivity host;
    private final View root;
    private TextView controlStatus;
    SettingsScreen(MainActivity host,MainActivity.Screen screen) {
        this.host=host;Ui.Colors c=Ui.colors(host);LinearLayout page=Ui.page(host);
        if(screen==MainActivity.Screen.APPEARANCE) {
            page.addView(Ui.heading(host,"选择你习惯的外观",21));page.addView(Ui.text(host,"所有页面使用统一主题，字号跟随系统。",14,Ui.MUTED));
            RadioGroup choices=new RadioGroup(host);choices.setOrientation(RadioGroup.VERTICAL);
            String[] values={"system","light","dark"};String[] labels={"跟随系统","浅色","深色"};String current=Ui.themeChoice(host);
            for(int i=0;i<values.length;i++) {
                RadioButton button=new RadioButton(host);button.setId(View.generateViewId());button.setTag(values[i]);button.setText(labels[i]);
                button.setTextColor(c.text);button.setTextSize(16);button.setMinimumHeight(Ui.dp(host,64));button.setButtonTintList(ColorStateList.valueOf(c.accent));
                choices.addView(button,new RadioGroup.LayoutParams(-1,-2));if(values[i].equals(current))button.setChecked(true);
            }
            choices.setOnCheckedChangeListener((group,id)->{RadioButton selected=group.findViewById(id);if(selected!=null&&!current.equals(selected.getTag()))host.changeTheme((String)selected.getTag());});
            Ui.gap(host,page,20);page.addView(choices);Ui.section(host,page,"界面色彩");
            page.addView(Ui.text(host,"蓝色用于对话与主要操作，紫色用于模型与游戏，青色表示连接状态，琥珀色提示需要留意的读数。",14,Ui.MUTED));
        } else if(screen==MainActivity.Screen.CONTROLS) {
            page.addView(Ui.heading(host,"手机控制权限",21));controlStatus=Ui.text(host,"",15,Ui.TEXT);page.addView(controlStatus);
            page.addView(Ui.text(host,"权限由你在 Android 系统中开启和关闭。当前提供应用内单步验证，自主手机任务仍待接入。",14,Ui.MUTED));
            Ui.button(host,page,"在系统中管理权限",false,host::openAccessibilitySettings);
            Ui.section(host,page,"观察与停止");page.addView(Ui.text(host,"Android 14 及以上使用指定窗口截图。停止会撤销后续动作授权，截图验证和近期诊断集中在开发者页面。",14,Ui.MUTED));
            Ui.listRow(host,page,AgentIconView.Icon.SETTINGS,c.muted,"开发者与诊断","控制测试台、环境与日志",host::openDeveloper);
        } else if(screen==MainActivity.Screen.ABOUT) {
            page.addView(new AgentIconView(host,AgentIconView.Icon.ORBIT,c.accent),new LinearLayout.LayoutParams(Ui.dp(host,54),Ui.dp(host,54)));
            Ui.gap(host,page,14);page.addView(Ui.heading(host,"Xiyan Agent",24));page.addView(Ui.text(host,"技术预览 "+BuildConfig.VERSION_NAME,14,Ui.MUTED));
            Ui.section(host,page,"当前能力");page.addView(Ui.text(host,"中文文本对话、账号模型列表和手机单步控制验证。手机自主执行、游戏策略与长周期任务继续开发。",14,Ui.MUTED));
            Ui.listRow(host,page,AgentIconView.Icon.INFO,c.accent,"开源项目","XiYanzx / xiyan-agent",()->host.openUrl("https://github.com/XiYanzx/xiyan-agent"));
            Ui.listRow(host,page,AgentIconView.Icon.INFO,c.secondary,"开源许可","GPL-3.0-only",()->host.openUrl("https://github.com/XiYanzx/xiyan-agent/blob/main/LICENSE"));
        } else {
            page.addView(Ui.text(host,"按你的习惯设置 Xiyan。",14,Ui.MUTED));Ui.section(host,page,"偏好");
            Ui.listRow(host,page,AgentIconView.Icon.SUN,c.secondary,"外观与显示","系统、浅色与深色",()->host.navigate(MainActivity.Screen.APPEARANCE));
            Ui.listRow(host,page,AgentIconView.Icon.PHONE,c.accent,"手机控制","系统权限与停止",()->host.navigate(MainActivity.Screen.CONTROLS));
            Ui.section(host,page,"账号");
            Ui.listRow(host,page,AgentIconView.Icon.USER,c.accent,"账号连接","登录、刷新与退出",()->host.navigate(MainActivity.Screen.ACCOUNT));
            Ui.listRow(host,page,AgentIconView.Icon.CHART,c.success,"用量","官方额度与最近一次调用",()->host.navigate(MainActivity.Screen.USAGE));
            Ui.section(host,page,"开发与关于");
            Ui.listRow(host,page,AgentIconView.Icon.SETTINGS,c.muted,"开发者与诊断","测试台、运行环境、诊断导出",host::openDeveloper);
            Ui.listRow(host,page,AgentIconView.Icon.INFO,c.secondary,"关于 Xiyan","版本、源码与开源许可",()->host.navigate(MainActivity.Screen.ABOUT));
        }
        root=Ui.scroll(host,page);
    }
    @Override public View view(){return root;}
    @Override public void update(SiwcClient.Snapshot snapshot) {
        if(controlStatus!=null)controlStatus.setText(XiyanAccessibilityService.getConnectedService()==null?"无障碍服务未连接":"无障碍服务已连接");
    }
}
