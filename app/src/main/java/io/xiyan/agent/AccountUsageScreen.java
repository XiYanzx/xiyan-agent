// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

import android.app.AlertDialog;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import io.xiyan.agent.account.SiwcClient;
import io.xiyan.agent.core.usage.QuotaSnapshot;
import io.xiyan.agent.core.usage.ResponseTokenUsage;
import java.text.NumberFormat;
import java.util.Locale;

/** Account status and observed usage, without changing the account workflow. */
final class AccountUsageScreen implements ScreenContent {
    private final MainActivity host;
    private final boolean accountPage;
    private final View root;
    private final NumberFormat numbers=NumberFormat.getIntegerInstance(Locale.SIMPLIFIED_CHINESE);
    private TextView connection,message,total,totalUnit,tokenTime,quotaMessage;
    private TextView input,output,cached,cacheWrite,reasoning;
    private LinearLayout windows,credits,tokenPair,tokenDetails,quotaNotice;
    private ProgressBar progress;
    private Button primary,more,logout,cancel,startChat,connect,recover,importCallback;
    private View environment,accountFeedback,accountNavigation;
    private QuotaSnapshot renderedQuota;
    private ResponseTokenUsage renderedTokens;
    private int renderedStale=-1;
    private boolean tokensRendered;

    AccountUsageScreen(MainActivity host,boolean accountPage) {
        this.host=host;this.accountPage=accountPage;
        LinearLayout page=Ui.page(host);
        if(accountPage)buildAccount(page);else buildUsage(page);
        root=Ui.scroll(host,page);
    }

    private void buildAccount(LinearLayout page) {
        Ui.editorial(host,page,"03 / ACCOUNT","连接你的模型。");
        Ui.Colors c=Ui.colors(host);
        LinearLayout summary=Ui.surface(host,page,c.surface);
        removeTopMargin(summary);
        LinearLayout identity=Ui.row(host);
        FrameLayout icon=new FrameLayout(host);
        icon.setBackground(Ui.shape(host,c.accentSurface,16,Color.TRANSPARENT));
        icon.addView(new AgentIconView(host,AgentIconView.Icon.USER,c.accent),new FrameLayout.LayoutParams(Ui.dp(host,24),Ui.dp(host,24),Gravity.CENTER));
        identity.addView(icon,new LinearLayout.LayoutParams(Ui.dp(host,48),Ui.dp(host,48)));
        LinearLayout words=Ui.column(host);words.addView(Ui.heading(host,"ChatGPT 账号",16));
        connection=Ui.text(host,"",13,Ui.MUTED);words.addView(connection);
        LinearLayout.LayoutParams label=new LinearLayout.LayoutParams(0,-2,1);label.setMargins(Ui.dp(host,12),0,0,0);
        identity.addView(words,label);summary.addView(identity);
        LinearLayout feedback=Ui.row(host);accountFeedback=feedback;progress=smallProgress();
        LinearLayout.LayoutParams spinner=new LinearLayout.LayoutParams(Ui.dp(host,18),Ui.dp(host,18));spinner.setMargins(0,0,Ui.dp(host,8),0);
        feedback.addView(progress,spinner);message=Ui.text(host,"",13,Ui.MUTED);feedback.addView(message,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams feedbackSpace=new LinearLayout.LayoutParams(-1,-2);feedbackSpace.setMargins(0,Ui.dp(host,12),0,0);summary.addView(feedback,feedbackSpace);
        primary=Ui.action(host,"连接 ChatGPT",true,false,()->{
            SiwcClient.Snapshot current=host.account().snapshot();
            if(current.hasSession&&!current.reauthRequired)host.account().refreshModels();else host.explainSignIn();
        });
        addAction(summary,primary,12);
        recover=Ui.action(host,"恢复本次连接",true,false,()->host.account().resumeSignIn());addAction(summary,recover,8);
        importCallback=Ui.textAction(host,"浏览器回调无法打开？",host::showLoginCallback);addAction(summary,importCallback,4);
        more=Ui.textAction(host,"更多账号操作",()->new AlertDialog.Builder(host).setTitle("账号操作")
                .setItems(new String[]{"重新授权","退出本机账号"},(dialog,which)->{if(which==0)host.explainSignIn();else host.signOut();})
                .setNegativeButton("取消",null).show());
        addAction(summary,more,4);
        logout=Ui.textAction(host,"退出本机账号",host::signOut);logout.setTextColor(c.alert);addAction(summary,logout,4);
        cancel=Ui.action(host,"取消当前操作",false,true,host::emergencyStop);addAction(summary,cancel,8);
        Ui.section(host,page,"账号服务");
        Ui.listRow(host,page,AgentIconView.Icon.MODEL,c.accent,"模型","当前账号的模型与思考选项",()->host.navigate(MainActivity.Screen.MODELS));
        Ui.listRow(host,page,AgentIconView.Icon.CHART,c.accent,"用量","额度与最近完成调用",()->host.navigate(MainActivity.Screen.USAGE));
        Ui.section(host,page,"连接");
        environment=Ui.listRow(host,page,AgentIconView.Icon.REFRESH,c.muted,"检查连接环境","官方登录服务的可达性",()->host.account().checkEnvironment());
        Ui.gap(host,page,8);page.addView(Ui.text(host,"登录与授权在系统浏览器完成。等待期间可以切换页面。",12,Ui.MUTED));
    }

    private void buildUsage(LinearLayout page) {
        Ui.editorial(host,page,"04 / USAGE","看清每次用量。");
        Ui.Colors c=Ui.colors(host);
        LinearLayout recent=Ui.surface(host,page,c.surface);
        removeTopMargin(recent);
        TextView eyebrow=Ui.text(host,"最近一次完成调用",13,Ui.MUTED);eyebrow.setAccessibilityHeading(true);recent.addView(eyebrow);
        total=Ui.heading(host,"",52);total.setFontFeatureSettings("tnum");recent.addView(total,new LinearLayout.LayoutParams(-1,-2));
        totalUnit=Ui.text(host,"token",13,Ui.MUTED);recent.addView(totalUnit);
        tokenTime=Ui.text(host,"",12,Ui.MUTED);recent.addView(tokenTime);
        tokenPair=Ui.column(host);boolean stacked=stacked();tokenPair.setOrientation(stacked?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
        input=statistic(tokenPair,"输入",stacked);output=statistic(tokenPair,"输出",stacked);
        LinearLayout.LayoutParams pairSpace=new LinearLayout.LayoutParams(-1,-2);pairSpace.setMargins(0,Ui.dp(host,16),0,0);recent.addView(tokenPair,pairSpace);
        tokenDetails=Ui.column(host);Ui.divider(host,tokenDetails);
        cached=detail(tokenDetails,"缓存命中");cacheWrite=detail(tokenDetails,"缓存写入");reasoning=detail(tokenDetails,"思考 token");
        tokenDetails.addView(Ui.text(host,"细分项已包含在输入或输出中。",12,Ui.MUTED));recent.addView(tokenDetails);
        startChat=Ui.textAction(host,"开始对话",()->host.navigate(MainActivity.Screen.CHAT));addAction(recent,startChat,8);
        Ui.section(host,page,"官方额度");
        quotaNotice=Ui.row(host);quotaNotice.setGravity(Gravity.TOP);
        quotaNotice.setPadding(Ui.dp(host,12),Ui.dp(host,12),Ui.dp(host,12),Ui.dp(host,12));
        quotaNotice.setBackground(Ui.shape(host,c.raised,4,Color.TRANSPARENT));
        quotaNotice.addView(new AgentIconView(host,AgentIconView.Icon.INFO,c.muted),new LinearLayout.LayoutParams(Ui.dp(host,20),Ui.dp(host,20)));
        quotaMessage=Ui.text(host,"",13,Ui.MUTED);LinearLayout.LayoutParams note=new LinearLayout.LayoutParams(0,-2,1);note.setMargins(Ui.dp(host,8),0,0,0);quotaNotice.addView(quotaMessage,note);
        page.addView(quotaNotice,new LinearLayout.LayoutParams(-1,-2));
        windows=Ui.column(host);page.addView(windows);credits=Ui.column(host);page.addView(credits);
        LinearLayout actions=Ui.column(host);actions.setOrientation(stacked?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
        primary=Ui.outlineAction(host,"检查更新",()->host.account().refreshUsage());
        Button official=Ui.textAction(host,"官方用量页面",()->host.openUrl("https://chatgpt.com/settings/usage"));
        actionCell(actions,primary,stacked);actionCell(actions,official,stacked);Ui.gap(host,page,12);page.addView(actions);
        progress=smallProgress();progress.setVisibility(View.GONE);LinearLayout.LayoutParams progressSize=new LinearLayout.LayoutParams(Ui.dp(host,18),Ui.dp(host,18));progressSize.setMargins(0,Ui.dp(host,8),0,0);page.addView(progress,progressSize);
        connect=Ui.textAction(host,"前往连接账号",()->host.navigate(MainActivity.Screen.ACCOUNT));addAction(page,connect,4);
        Ui.gap(host,page,16);accountNavigation=Ui.listRow(host,page,AgentIconView.Icon.USER,c.muted,"账号管理","连接、续期与退出",()->host.navigate(MainActivity.Screen.ACCOUNT));
    }

    @Override public View view(){return root;}
    @Override public void update(SiwcClient.Snapshot s) {
        if(accountPage){updateAccount(s);return;}
        Ui.enabled(primary,s.hasSession&&!s.reauthRequired&&!s.busy);
        connect.setVisibility(!s.hasSession||s.reauthRequired?View.VISIBLE:View.GONE);
        accountNavigation.setVisibility(s.hasSession&&!s.reauthRequired?View.VISIBLE:View.GONE);
        boolean checking=s.busy&&s.operationStage.contains("用量");progress.setVisibility(checking?View.VISIBLE:View.GONE);
        primary.setText(checking?"正在检查…":"检查更新");
        long now=System.currentTimeMillis();QuotaSnapshot quota=s.quota;
        boolean noWindows=quota.getPrimary()==null&&quota.getSecondary()==null;
        boolean usageError=s.failureCode!=null&&s.operationStage.contains("用量");
        boolean checked=s.catalog!=null||s.tokenUsage!=null||"completed".equals(s.inferenceStatus)||s.operationStage.contains("用量连接检查完成");
        String note=checking?"正在检查服务返回的用量信息…":usageError?Ui.friendlyFailure(s):!s.hasSession?"连接账号后，随服务响应更新额度。":s.reauthRequired?"重新连接账号后可检查额度。":
                !quota.hasData()?(checked?"本次未取得有效额度读数，可查看官方用量页面。":"额度百分比待服务提供，可查看官方用量页面。"):
                noWindows?"周期额度待服务提供；已收到的 Credits 单独显示。":"";
        quotaNotice.setVisibility(!note.isEmpty()?View.VISIBLE:View.GONE);set(quotaMessage,note);quotaMessage.setTextColor(usageError?Ui.colors(host).alert:Ui.colors(host).muted);
        int stale=(quota.getPrimary()!=null&&quota.getPrimary().isStale(now)?1:0)|(quota.getSecondary()!=null&&quota.getSecondary().isStale(now)?2:0)|(quota.getCredits()!=null&&quota.getCredits().isStale(now)?4:0);
        if(renderedQuota!=quota||renderedStale!=stale) {
            windows.removeAllViews();credits.removeAllViews();
            if(quota.getPrimary()!=null)addWindow(quota.getPrimary(),"主要窗口",Ui.colors(host).accent,now);
            if(quota.getSecondary()!=null)addWindow(quota.getSecondary(),"次要窗口",Ui.colors(host).secondary,now);
            if(quota.getCredits()!=null)addCredits(quota.getCredits(),now);
            renderedQuota=quota;renderedStale=stale;
        }
        if(!tokensRendered||renderedTokens!=s.tokenUsage){renderTokens(s.tokenUsage);tokensRendered=true;renderedTokens=s.tokenUsage;}
    }

    private void updateAccount(SiwcClient.Snapshot s) {
        Ui.Colors c=Ui.colors(host);
        set(connection,!s.hasSession?"尚未连接":s.reauthRequired?"需要重新连接":s.connected?"本机会话有效":"会话待续期");
        connection.setTextColor(s.reauthRequired?c.alert:s.connected?c.accent:c.muted);
        String note=s.busy?busyMessage(s):s.reauthRequired?"在系统浏览器完成授权后继续使用。":s.failureCode!=null?Ui.friendlyFailure(s):!s.hasSession?"连接后可读取账号模型并发起对话。":!s.connected?"续期后更新当前账号的模型目录。":"";
        set(message,note);message.setVisibility(note.isEmpty()?View.GONE:View.VISIBLE);message.setTextColor(s.failureCode!=null&&!s.reauthRequired?c.alert:c.muted);
        progress.setVisibility(s.busy?View.VISIBLE:View.GONE);
        accountFeedback.setVisibility(s.busy||!note.isEmpty()?View.VISIBLE:View.GONE);
        primary.setText(s.busy?"正在处理…":!s.hasSession?"连接 ChatGPT":s.reauthRequired?"重新连接账号":s.connected?"刷新连接与模型":"续期并刷新模型");
        Ui.enabled(primary,!s.busy);Ui.enabled(environment,!s.busy);
        primary.setVisibility(s.loginRecoveryAvailable&&!s.busy?View.GONE:View.VISIBLE);
        recover.setVisibility(s.loginRecoveryAvailable&&!s.busy?View.VISIBLE:View.GONE);
        importCallback.setVisibility(s.loginRecoveryAvailable&&(!s.busy||s.loginAwaitingCallback)?View.VISIBLE:View.GONE);
        more.setVisibility(s.hasSession&&!s.reauthRequired?View.VISIBLE:View.GONE);Ui.enabled(more,!s.busy);
        logout.setVisibility(s.hasSession&&s.reauthRequired?View.VISIBLE:View.GONE);Ui.enabled(logout,!s.busy);
        cancel.setVisibility(s.busy?View.VISIBLE:View.GONE);
    }

    private void renderTokens(ResponseTokenUsage usage) {
        boolean available=usage!=null;
        total.setTextSize(available?52:22);set(total,available?number(usage.getTotalTokens()):"暂无调用统计");
        total.setContentDescription(available?"总计 "+number(usage.getTotalTokens())+" token":"暂无完成调用的 token 统计");
        set(totalUnit,available?"token":"完成一次对话后查看实际用量。");tokenTime.setVisibility(available?View.VISIBLE:View.GONE);
        set(tokenTime,available?"完成调用 · "+Ui.time(usage.getObservedAtEpochMillis()):"");
        tokenPair.setVisibility(available?View.VISIBLE:View.GONE);tokenDetails.setVisibility(available?View.VISIBLE:View.GONE);startChat.setVisibility(available?View.GONE:View.VISIBLE);
        set(input,available?number(usage.getInputTokens()):"—");set(output,available?number(usage.getOutputTokens()):"—");
        set(cached,available?number(usage.getCachedTokens()):"—");set(cacheWrite,available?number(usage.getCacheWriteTokens()):"—");set(reasoning,available?number(usage.getReasoningTokens()):"—");
    }
    private TextView statistic(LinearLayout parent,String title,boolean stacked) {
        LinearLayout cell=Ui.column(host);cell.addView(Ui.text(host,title,13,Ui.MUTED));TextView value=Ui.heading(host,"—",24);value.setFontFeatureSettings("tnum");cell.addView(value);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(stacked?-1:0,-2,stacked?0:1);lp.setMargins(0,0,Ui.dp(host,8),Ui.dp(host,8));parent.addView(cell,lp);return value;
    }
    private TextView detail(LinearLayout parent,String title) {
        LinearLayout row=Ui.row(host);row.setMinimumHeight(Ui.dp(host,36));row.addView(Ui.text(host,title,13,Ui.MUTED),new LinearLayout.LayoutParams(0,-2,1));
        TextView value=Ui.text(host,"—",14,Ui.TEXT);value.setFontFeatureSettings("tnum");row.addView(value);parent.addView(row);return value;
    }
    private void addWindow(QuotaSnapshot.Window window,String fallback,int tint,long now) {
        Ui.Colors c=Ui.colors(host);boolean old=window.isStale(now);LinearLayout panel=Ui.surface(host,windows,c.surface);
        LinearLayout title=Ui.row(host);title.addView(Ui.heading(host,windowName(window,fallback),16),new LinearLayout.LayoutParams(0,-2,1));
        if(old)title.addView(Ui.text(host,"上次读数",12,c.warning));panel.addView(title);
        TextView remaining=Ui.heading(host,"剩余 "+Ui.percent(window.getRemainingPercent())+"%",24);remaining.setTextColor(old?c.muted:tint);panel.addView(remaining);
        QuotaGaugeView meter=new QuotaGaugeView(host);int meterTint=old?c.muted:window.getRemainingPercent()<15?c.alert:window.getRemainingPercent()<35?c.warning:tint;
        meter.value(window.getRemainingPercent(),c.raised,meterTint,c.text,windowName(window,fallback)+"，剩余 "+Ui.percent(window.getRemainingPercent())+"%");
        LinearLayout.LayoutParams bar=new LinearLayout.LayoutParams(-1,Ui.dp(host,8));bar.setMargins(0,Ui.dp(host,8),0,Ui.dp(host,10));panel.addView(meter,bar);
        panel.addView(Ui.text(host,"已用 "+Ui.percent(window.getUsedPercent())+"% · "+resetTime(window.getResetsAtEpochSeconds(),now),13,Ui.MUTED));
        panel.addView(Ui.text(host,sourceLabel(window.getSource())+" · "+Ui.time(window.getObservedAtEpochMillis()),12,Ui.MUTED));
    }
    private void addCredits(QuotaSnapshot.Credits balance,long now) {
        Ui.Colors c=Ui.colors(host);boolean old=balance.isStale(now);Ui.section(host,credits,"额外 Credits");LinearLayout panel=Ui.surface(host,credits,c.surface);
        String label=Boolean.TRUE.equals(balance.getUnlimited())?"不限额":balance.getBalance()==null?"—":balance.getBalance();
        TextView value=Ui.heading(host,label,24);value.setTextColor(old?c.muted:c.accent);value.setFontFeatureSettings("tnum");panel.addView(value);
        panel.addView(Ui.text(host,old?"上次余额读数":balance.getHasCredits()==null?"可用状态待提供":Boolean.TRUE.equals(balance.getHasCredits())?"存在可用余额":"当前无可用余额",13,Ui.MUTED));
        panel.addView(Ui.text(host,sourceLabel(balance.getSource())+" · "+Ui.time(balance.getObservedAtEpochMillis()),12,Ui.MUTED));
    }
    private ProgressBar smallProgress(){ProgressBar v=new ProgressBar(host);v.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.colors(host).accent));v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return v;}
    private boolean stacked(){return host.getResources().getConfiguration().screenWidthDp<360||host.getResources().getConfiguration().fontScale>=1.3f;}
    private String number(Long amount){return amount==null?"—":numbers.format(amount);}
    private void addAction(LinearLayout parent,Button button,int top){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,Ui.dp(host,top),0,0);parent.addView(button,lp);}
    private void actionCell(LinearLayout parent,Button button,boolean stacked){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(stacked?-1:0,-2,stacked?0:1);lp.setMargins(0,0,stacked?0:Ui.dp(host,8),stacked?Ui.dp(host,4):0);parent.addView(button,lp);}
    private static void set(TextView view,String value){if(!view.getText().toString().equals(value))view.setText(value);}
    private static void removeTopMargin(View view){LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)view.getLayoutParams();p.topMargin=0;view.setLayoutParams(p);}
    private static String busyMessage(SiwcClient.Snapshot s){return "running".equals(s.inferenceStatus)?"正在接收模型回复…":s.operationStage.contains("用量")?"正在检查用量信息…":s.operationStage.contains("目录")?"正在更新模型目录…":s.operationStage.contains("端点")?"正在检查官方连接…":s.operationStage.contains("移除")||s.operationStage.contains("撤销")?"正在退出账号…":s.operationStage.contains("浏览器")?"请在系统浏览器完成登录与授权。":"正在处理账号操作…";}
    private static String sourceLabel(QuotaSnapshot.Source source){return source==QuotaSnapshot.Source.MODEL_HEADERS?"连接检查":source==QuotaSnapshot.Source.NONE?"待获取":"调用响应";}
    private static String windowName(QuotaSnapshot.Window window,String fallback){Long minutes=window.getWindowMinutes();return minutes==null?fallback:minutes%1440==0?(minutes/1440)+"天窗口":minutes%60==0?(minutes/60)+"小时窗口":minutes+"分钟窗口";}
    private static String resetTime(Long seconds,long now){if(seconds==null)return "重置时间待提供";if(seconds>Long.MAX_VALUE/1000)return "重置时间暂不可显示";return seconds*1000<=now?"重置时间已到":"重置于 "+Ui.time(seconds*1000);}
}
