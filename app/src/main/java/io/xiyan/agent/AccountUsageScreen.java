// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.xiyan.agent.account.SiwcClient;
import io.xiyan.agent.core.usage.QuotaSnapshot;
import io.xiyan.agent.core.usage.ResponseTokenUsage;

final class AccountUsageScreen implements ScreenContent {
    private final MainActivity host;
    private final boolean accountPage;
    private final View root;
    private final TextView connection,message,updated;
    private final LinearLayout windows,credits,tokens;
    private final Button primary,renew,logout,cancel;
    AccountUsageScreen(MainActivity host,boolean accountPage) {
        this.host=host;this.accountPage=accountPage;Ui.Colors c=Ui.colors(host);LinearLayout page=Ui.page(host);
        if(accountPage) {
            LinearLayout identity=Ui.row(host);identity.addView(new AgentIconView(host,AgentIconView.Icon.USER,c.accent),new LinearLayout.LayoutParams(Ui.dp(host,44),Ui.dp(host,44)));
            LinearLayout words=Ui.column(host);words.addView(Ui.heading(host,"ChatGPT 账号",21));connection=Ui.text(host,"",14,Ui.MUTED);words.addView(connection);
            LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,-2,1);wp.setMargins(Ui.dp(host,14),0,0,0);identity.addView(words,wp);page.addView(identity);
            Ui.gap(host,page,18);message=Ui.text(host,"",14,Ui.TEXT);page.addView(message);
            primary=Ui.button(host,page,"连接账号",false,()->{
                SiwcClient.Snapshot s=host.account().snapshot();if(s.hasSession&&!s.reauthRequired)host.account().refreshModels();else host.explainSignIn();
            });
            renew=Ui.button(host,page,"更多账号操作",false,()->new android.app.AlertDialog.Builder(host).setTitle("账号操作")
                    .setItems(new String[]{"重新授权","退出本机账号"},(dialog,which)->{if(which==0)host.explainSignIn();else host.signOut();})
                    .setNegativeButton("取消",null).show());
            renew.setBackgroundColor(Color.TRANSPARENT);renew.setTextColor(c.muted);
            cancel=Ui.button(host,page,"取消当前操作",true,host::emergencyStop);
            logout=Ui.button(host,page,"退出本机账号",false,host::signOut);
            Ui.section(host,page,"账号服务");
            Ui.listRow(host,page,AgentIconView.Icon.CHART,c.success,"查看用量","官方额度与最近一次调用",()->host.navigate(MainActivity.Screen.USAGE));
            Ui.listRow(host,page,AgentIconView.Icon.MODEL,c.secondary,"模型列表","实际账号模型与思考选项",()->host.navigate(MainActivity.Screen.MODELS));
            Ui.listRow(host,page,AgentIconView.Icon.REFRESH,c.accent,"检查连接环境","只检查官方连接可达性",()->host.account().checkEnvironment());
            Ui.section(host,page,"连接说明");page.addView(Ui.text(host,"登录和授权在系统浏览器完成。切换页面或暂时离开应用后，连接流程会继续。",13,Ui.MUTED));
            updated=null;windows=null;credits=null;tokens=null;
        } else {
            connection=Ui.text(host,"",14,Ui.TEXT);page.addView(connection);
            Ui.listRow(host,page,AgentIconView.Icon.USER,c.accent,"管理账号","连接、续期与退出",()->host.navigate(MainActivity.Screen.ACCOUNT));
            Ui.section(host,page,"官方额度");message=Ui.text(host,"",13,Ui.MUTED);page.addView(message);
            windows=Ui.column(host);page.addView(windows);updated=Ui.text(host,"",12,Ui.MUTED);page.addView(updated);
            primary=Ui.button(host,page,"检查用量更新",false,()->host.account().refreshUsage());
            Ui.button(host,page,"打开官方用量页面",false,()->host.openUrl("https://chatgpt.com/settings/usage"));
            credits=Ui.column(host);page.addView(credits);Ui.section(host,page,"最近一次完成调用");tokens=Ui.column(host);page.addView(tokens);
            page.addView(Ui.text(host,"缓存与思考属于输入或输出的细分项。订阅额度和调用 token 分别显示。",12,Ui.MUTED));
            renew=null;logout=null;cancel=null;
        }
        root=Ui.scroll(host,page);
    }
    @Override public View view(){return root;}
    @Override public void update(SiwcClient.Snapshot s) {
        connection.setText(s.hasSession?(s.reauthRequired?"账号需要重新连接":s.connected?"账号已连接":"账号已保存，可刷新续期"):"尚未连接账号");
        if(accountPage) {
            message.setText(Ui.accountMessage(s));primary.setText(s.busy?"正在处理…":s.hasSession&&!s.reauthRequired?"刷新连接与模型":"连接 ChatGPT 账号");
            Ui.enabled(primary,!s.busy);renew.setVisibility(s.hasSession&&!s.reauthRequired?View.VISIBLE:View.GONE);Ui.enabled(renew,!s.busy);
            logout.setVisibility(s.hasSession&&s.reauthRequired?View.VISIBLE:View.GONE);Ui.enabled(logout,!s.busy);cancel.setVisibility(s.busy?View.VISIBLE:View.GONE);return;
        }
        Ui.enabled(primary,s.hasSession&&!s.reauthRequired&&!s.busy);
        long now=System.currentTimeMillis();QuotaSnapshot quota=s.quota;windows.removeAllViews();
        boolean noWindows=quota.getPrimary()==null&&quota.getSecondary()==null;
        boolean checked=s.catalog!=null||s.tokenUsage!=null||"completed".equals(s.inferenceStatus)||s.message.startsWith("用量连接检查完成");
        String usageStatus=noWindows&&!quota.hasData()?(checked?"本次未取得有效额度读数。可到官方用量页面查看。":"额度会随服务响应更新，也可查看官方用量页面。"):noWindows?"额度百分比待服务提供；已收到的 Credits 单独显示。":s.usageMessage;
        message.setText(usageStatus);
        if(noWindows)addUnknownWindow();
        else {
            if(quota.getPrimary()!=null)addWindow(quota.getPrimary(),"主要窗口",Ui.colors(host).accent,now);
            if(quota.getSecondary()!=null)addWindow(quota.getSecondary(),"次要窗口",Ui.colors(host).secondary,now);
        }
        updated.setVisibility(quota.getObservedAtEpochMillis()>0?View.VISIBLE:View.GONE);
        updated.setText(quota.getObservedAtEpochMillis()>0?"最近收到读数："+Ui.time(quota.getObservedAtEpochMillis()):"");
        credits.removeAllViews();QuotaSnapshot.Credits balance=quota.getCredits();
        if(balance!=null) {
            Ui.section(host,credits,"额外 Credits");String label=Boolean.TRUE.equals(balance.getUnlimited())?"Credits 不限额":balance.getBalance()==null?"—":balance.getBalance();
            TextView value=Ui.heading(host,label,26);value.setTextColor(Ui.colors(host).success);credits.addView(value);
            credits.addView(Ui.text(host,balance.isStale(now)?"上次余额读数":balance.getHasCredits()==null?"可用状态待提供":Boolean.TRUE.equals(balance.getHasCredits())?"存在可用余额":"当前无可用余额",12,Ui.MUTED));
            credits.addView(Ui.text(host,sourceLabel(balance.getSource())+" · "+Ui.time(balance.getObservedAtEpochMillis()),12,Ui.MUTED));
        }
        tokens.removeAllViews();ResponseTokenUsage usage=s.tokenUsage;
        tokens.addView(Ui.text(host,usage==null?"调用完成后显示实际用量":"更新于 "+Ui.time(usage.getObservedAtEpochMillis()),12,Ui.MUTED));
        numberRow(tokens,"输入 token",usage==null?null:usage.getInputTokens(),true);
        numberRow(tokens,"输出 token",usage==null?null:usage.getOutputTokens(),true);
        numberRow(tokens,"总计 token",usage==null?null:usage.getTotalTokens(),true);
        Ui.divider(host,tokens);
        numberRow(tokens,"缓存命中",usage==null?null:usage.getCachedTokens(),false);
        numberRow(tokens,"缓存写入",usage==null?null:usage.getCacheWriteTokens(),false);
        numberRow(tokens,"思考 token",usage==null?null:usage.getReasoningTokens(),false);
    }
    private void numberRow(LinearLayout parent,String title,Long amount,boolean main) {
        LinearLayout row=Ui.row(host);row.setMinimumHeight(Ui.dp(host,main?48:40));row.addView(Ui.text(host,title,main?14:13,Ui.MUTED),new LinearLayout.LayoutParams(0,-2,1));
        row.addView(Ui.text(host,amount==null?"—":String.valueOf(amount),main?22:15,Ui.TEXT));parent.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }
    private void addUnknownWindow() {
        Ui.Colors c=Ui.colors(host);LinearLayout row=Ui.row(host);row.setPadding(0,Ui.dp(host,12),0,Ui.dp(host,8));
        QuotaGaugeView gauge=new QuotaGaugeView(host);gauge.value(null,c.raised,c.accent,c.text,"额度百分比，待获取");
        row.addView(gauge,new LinearLayout.LayoutParams(Ui.dp(host,64),Ui.dp(host,64)));
        TextView label=Ui.text(host,"额度百分比",15,Ui.MUTED);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);
        lp.setMargins(Ui.dp(host,14),0,0,0);row.addView(label,lp);windows.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }
    private void addWindow(QuotaSnapshot.Window window,String fallback,int tint,long now) {
        Ui.Colors c=Ui.colors(host);LinearLayout panel=Ui.surface(host,windows,c.surface);LinearLayout row=Ui.row(host);
        String name=fallback;if(window!=null&&window.getWindowMinutes()!=null) {
            long minutes=window.getWindowMinutes();name=minutes%1440==0?(minutes/1440)+"天窗口":minutes%60==0?(minutes/60)+"小时窗口":minutes+"分钟窗口";
        }
        QuotaGaugeView gauge=new QuotaGaugeView(host);Double remaining=window==null?null:window.getRemainingPercent();
        int meterTint=remaining!=null&&remaining<15?c.alert:remaining!=null&&remaining<35?c.warning:tint;
        gauge.value(remaining,c.raised,meterTint,c.text,name+(remaining==null?"，待获取":"，剩余 "+Ui.percent(remaining)+"%"));
        row.addView(gauge,new LinearLayout.LayoutParams(Ui.dp(host,88),Ui.dp(host,88)));
        LinearLayout words=Ui.column(host);words.addView(Ui.heading(host,name,16));
        words.addView(Ui.text(host,window==null?"百分比待获取":"已用 "+Ui.percent(window.getUsedPercent())+"% · 剩余 "+Ui.percent(window.getRemainingPercent())+"%",13,Ui.MUTED));
        words.addView(Ui.text(host,window==null||window.getResetsAtEpochSeconds()==null?"重置时间待提供":"重置于 "+Ui.time(window.getResetsAtEpochSeconds()*1000),12,Ui.MUTED));
        if(window!=null)words.addView(Ui.text(host,sourceLabel(window.getSource())+" · "+Ui.time(window.getObservedAtEpochMillis()),12,Ui.MUTED));
        if(window!=null&&window.isStale(now)){TextView stale=Ui.text(host,"上次读数",12,c.warning);words.addView(stale);}
        LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,-2,1);wp.setMargins(Ui.dp(host,16),0,0,0);row.addView(words,wp);panel.addView(row);
    }
    private static String sourceLabel(QuotaSnapshot.Source source){return source==QuotaSnapshot.Source.MODEL_HEADERS?"刷新获取":source==QuotaSnapshot.Source.NONE?"待获取":"调用响应";}
}
