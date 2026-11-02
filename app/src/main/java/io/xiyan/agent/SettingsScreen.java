// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.xiyan.agent.account.SiwcClient;
import io.xiyan.agent.control.XiyanAccessibilityService;
import io.xiyan.agent.shizuku.ShizukuController;

/** Compact settings destinations using the shared palette and existing host actions. */
final class SettingsScreen implements ScreenContent {
    private final MainActivity host;
    private final View root;
    private TextView controlStatus,controlDetail;
    private TextView shizukuStatus;
    private Row accountRow,controlRow;

    SettingsScreen(MainActivity host,MainActivity.Screen screen) {
        this.host=host;LinearLayout page=Ui.page(host);
        if(screen==MainActivity.Screen.APPEARANCE)appearance(page);
        else if(screen==MainActivity.Screen.CONTROLS)controls(page);
        else if(screen==MainActivity.Screen.ABOUT)about(page);
        else overview(page);
        root=Ui.scroll(host,page);
    }

    private void overview(LinearLayout page) {
        Ui.editorial(host,page,"06 / PREFERENCES","按你的习惯。");
        Ui.section(host,page,"偏好");
        new Row(page,AgentIconView.Icon.SUN,"外观与显示","",themeLabel(Ui.themeChoice(host)),()->host.navigate(MainActivity.Screen.APPEARANCE));
        controlRow=new Row(page,AgentIconView.Icon.PHONE,"手机控制","","",()->host.navigate(MainActivity.Screen.CONTROLS));
        Ui.section(host,page,"账号");
        accountRow=new Row(page,AgentIconView.Icon.USER,"账号连接","","",()->host.navigate(MainActivity.Screen.ACCOUNT));
        new Row(page,AgentIconView.Icon.CHART,"用量","最近完成调用与官方额度","",()->host.navigate(MainActivity.Screen.USAGE));
        Ui.section(host,page,"开发与关于");
        new Row(page,AgentIconView.Icon.SETTINGS,"开发者与诊断","测试台、运行环境与诊断","",host::openDeveloper);
        new Row(page,AgentIconView.Icon.INFO,"关于 Xiyan","",BuildConfig.VERSION_NAME,()->host.navigate(MainActivity.Screen.ABOUT));
    }

    private void appearance(LinearLayout page) {
        String current=Ui.themeChoice(host);Ui.Colors c=Ui.colors(host);
        page.addView(Ui.text(host,"选择主题",13,Ui.MUTED));
        Button system=Ui.chip(host,"跟随系统 · 当前"+(Ui.isLight(host)?"浅色":"深色"),"system".equals(current),()->host.changeTheme("system"));
        LinearLayout.LayoutParams systemSpace=new LinearLayout.LayoutParams(-1,-2);systemSpace.setMargins(0,Ui.dp(host,8),0,Ui.dp(host,16));page.addView(system,systemSpace);
        boolean stacked=stacked();LinearLayout previews=Ui.column(host);previews.setOrientation(stacked?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
        preview(previews,"浅色","light",true,current,stacked);preview(previews,"深色","dark",false,current,stacked);page.addView(previews);
        Ui.section(host,page,"显示");
        new Row(page,AgentIconView.Icon.PHONE,"文字大小","跟随 Android 系统设置","",null);
        Ui.gap(host,page,8);TextView note=Ui.text(host,"预览采用应用的实际主题色。文字大小跟随系统，界面会相应调整。",12,c.muted);page.addView(note);
    }

    private void preview(LinearLayout parent,String label,String value,boolean light,String current,boolean stacked) {
        Ui.Colors c=Ui.colors(host);boolean selected=value.equals(current);
        LinearLayout card=Ui.column(host);int pad=Ui.dp(host,8);card.setPadding(pad,pad,pad,pad);
        card.setBackground(Ui.shape(host,c.surface,4,selected?c.accent:c.border));
        ThemePreview image=new ThemePreview(new Ui.Colors(light));image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(image,new LinearLayout.LayoutParams(-1,Ui.dp(host,120)));
        Button choice=Ui.chip(host,label,selected,()->host.changeTheme(value));
        LinearLayout.LayoutParams choiceSpace=new LinearLayout.LayoutParams(-1,-2);choiceSpace.setMargins(0,Ui.dp(host,8),0,0);card.addView(choice,choiceSpace);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(stacked?-1:0,-2,stacked?0:1);
        lp.setMargins(0,0,stacked?0:Ui.dp(host,8),stacked?Ui.dp(host,12):0);parent.addView(card,lp);
    }

    private void controls(LinearLayout page) {
        Ui.editorial(host,page,"05 / CONTROL","连接手机能力。");
        Ui.section(host,page,"Shizuku");
        shizukuStatus=Ui.text(host,"",14,Ui.MUTED);page.addView(shizukuStatus);
        Button shizuku=Ui.action(host,"设置 Shizuku 操控",true,false,host::openShizuku);page.addView(shizuku,new LinearLayout.LayoutParams(-1,-2));
        Ui.gap(host,page,8);page.addView(Ui.text(host,"在 Shizuku 中授权后，可通过本机服务派发点击、滑动、返回和主页操作。",13,Ui.MUTED));
        Ui.divider(host,page);
        Ui.Colors c=Ui.colors(host);LinearLayout summary=Ui.surface(host,page,c.surface);removeTopMargin(summary);
        LinearLayout row=Ui.row(host);FrameLayout tile=new FrameLayout(host);tile.setBackground(Ui.shape(host,c.accentSurface,16,Color.TRANSPARENT));
        tile.addView(new AgentIconView(host,AgentIconView.Icon.PHONE,c.accent),new FrameLayout.LayoutParams(Ui.dp(host,24),Ui.dp(host,24),Gravity.CENTER));
        row.addView(tile,new LinearLayout.LayoutParams(Ui.dp(host,48),Ui.dp(host,48)));
        LinearLayout words=Ui.column(host);words.addView(Ui.heading(host,"无障碍服务",16));controlStatus=Ui.text(host,"",13,Ui.MUTED);words.addView(controlStatus);
        LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,-2,1);wp.setMargins(Ui.dp(host,12),0,0,0);row.addView(words,wp);summary.addView(row);
        Ui.gap(host,summary,12);controlDetail=Ui.text(host,"",13,Ui.MUTED);summary.addView(controlDetail);
        Button manage=Ui.action(host,"在系统中管理权限",true,false,host::openAccessibilitySettings);LinearLayout.LayoutParams actionSpace=new LinearLayout.LayoutParams(-1,-2);actionSpace.setMargins(0,Ui.dp(host,12),0,0);summary.addView(manage,actionSpace);
        Ui.section(host,page,"验证与停止");
        new Row(page,AgentIconView.Icon.SETTINGS,"单步验证与诊断","点击、滑动、截图与运行环境","",host::openDeveloper);
        Ui.gap(host,page,8);
        page.addView(Ui.text(host,Build.VERSION.SDK_INT>=34?"当前系统提供指定窗口截图。停止入口可撤销后续动作授权。":"当前系统的指定窗口截图路径仍待验证。停止入口可撤销后续动作授权。",12,Ui.MUTED));
        Ui.gap(host,page,8);page.addView(Ui.text(host,"手机自主执行与游戏策略继续开发。",12,Ui.MUTED));
    }

    private void about(LinearLayout page) {
        Ui.Colors c=Ui.colors(host);LinearLayout brand=Ui.column(host);brand.setPadding(0,Ui.dp(host,8),0,Ui.dp(host,8));
        brand.addView(new AgentIconView(host,AgentIconView.Icon.ORBIT,c.accent),new LinearLayout.LayoutParams(Ui.dp(host,56),Ui.dp(host,56)));
        Ui.gap(host,brand,12);brand.addView(Ui.heading(host,"Xiyan Agent",22));brand.addView(Ui.text(host,"技术预览 "+BuildConfig.VERSION_NAME,13,Ui.MUTED));page.addView(brand);
        Ui.section(host,page,"当前能力");
        page.addView(Ui.text(host,"中文文本对话、账号模型、用量与手机单步验证。",15,Ui.TEXT));
        page.addView(Ui.text(host,"自主执行、游戏策略与长期任务继续开发。",13,Ui.MUTED));
        Ui.section(host,page,"开源");
        new Row(page,AgentIconView.Icon.INFO,"源码","XiYanzx / xiyan-agent","",()->host.openUrl("https://github.com/XiYanzx/xiyan-agent"));
        new Row(page,AgentIconView.Icon.INFO,"项目许可","","GPL-3.0-only",()->host.openUrl("https://github.com/XiYanzx/xiyan-agent/blob/main/LICENSE"));
        new Row(page,AgentIconView.Icon.INFO,"第三方许可","开源组件与图标来源","",()->host.openUrl("https://github.com/XiYanzx/xiyan-agent/blob/main/THIRD_PARTY_NOTICES.md"));
    }

    @Override public View view(){return root;}
    @Override public void update(SiwcClient.Snapshot snapshot) {
        ShizukuController.Snapshot shizuku=ShizukuController.getInstance(host).snapshot();
        if(shizukuStatus!=null)shizukuStatus.setText(ShizukuUi.status(shizuku));
        boolean connected=XiyanAccessibilityService.getConnectedService()!=null;
        if(controlStatus!=null){controlStatus.setText(connected?"服务已连接":"服务未连接");controlStatus.setTextColor(connected?Ui.colors(host).accent:Ui.colors(host).muted);}
        if(controlDetail!=null)controlDetail.setText(connected?"可以在开发者页面进行应用内单步验证。":"可前往系统检查权限，连接后进行应用内单步验证。");
        if(controlRow!=null)controlRow.value(shizuku.bound?"Shizuku 已连接":connected?"无障碍已连接":"待连接");
        if(accountRow!=null)accountRow.value(!snapshot.hasSession?"未连接":snapshot.reauthRequired?"需重新连接":snapshot.connected?"连接有效":"待续期");
    }

    private final class Row {
        private final LinearLayout view;
        private final TextView valueView;
        private final String title,subtitle;
        Row(LinearLayout parent,AgentIconView.Icon icon,String title,String subtitle,String value,Runnable action) {
            this.title=title;this.subtitle=subtitle;Ui.Colors c=Ui.colors(host);view=Ui.row(host);view.setMinimumHeight(Ui.dp(host,64));view.setPadding(0,Ui.dp(host,10),0,Ui.dp(host,10));
            view.addView(new AgentIconView(host,icon,c.muted),new LinearLayout.LayoutParams(Ui.dp(host,24),Ui.dp(host,24)));
            LinearLayout words=Ui.column(host);TextView heading=Ui.text(host,title,15,Ui.TEXT);heading.setTypeface(Typeface.create(Typeface.create("sans-serif",Typeface.NORMAL),500,false));heading.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);words.addView(heading);
            valueView=Ui.text(host,"",13,Ui.MUTED);valueView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            if(stacked())words.addView(valueView);
            if(!subtitle.isEmpty()){TextView description=Ui.text(host,subtitle,12,Ui.MUTED);description.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);words.addView(description);}
            LinearLayout.LayoutParams body=new LinearLayout.LayoutParams(0,-2,1);body.setMargins(Ui.dp(host,12),0,Ui.dp(host,8),0);view.addView(words,body);
            if(!stacked()){valueView.setMaxWidth(Ui.dp(host,128));valueView.setGravity(Gravity.END);view.addView(valueView);}
            if(action!=null){
                AgentIconView chevron=new AgentIconView(host,AgentIconView.Icon.CHEVRON,c.muted);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(Ui.dp(host,18),Ui.dp(host,18));cp.setMargins(Ui.dp(host,8),0,0,0);view.addView(chevron,cp);
                int ripple=Color.argb(32,Color.red(c.accent),Color.green(c.accent),Color.blue(c.accent));view.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple),Ui.shape(host,Color.TRANSPARENT,12,Color.TRANSPARENT),null));
                view.setFocusable(true);view.setClickable(true);view.setOnClickListener(v->action.run());
                view.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View v,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(v,info);info.setClassName(Button.class.getName());}});
            }
            view.setScreenReaderFocusable(true);value(value);parent.addView(view,new LinearLayout.LayoutParams(-1,-2));
        }
        void value(String value){if(!valueView.getText().toString().equals(value))valueView.setText(value);valueView.setVisibility(value.isEmpty()?View.GONE:View.VISIBLE);view.setContentDescription(title+(value.isEmpty()?"":"，"+value)+(subtitle.isEmpty()?"":"，"+subtitle));}
    }

    /** Palette preview with abstract reading, message and input surfaces; no fabricated account data. */
    private final class ThemePreview extends View {
        private final Ui.Colors colors;
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds=new RectF();
        ThemePreview(Ui.Colors colors){super(host);this.colors=colors;}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);float w=getWidth(),h=getHeight(),p=Ui.dp(host,12),r=Ui.dp(host,10);
            block(canvas,0,0,w,h,colors.background,r);block(canvas,p,p,p+Ui.dp(host,42),p+Ui.dp(host,5),colors.text,Ui.dp(host,2));
            block(canvas,w*.45f,h*.29f,w-p,h*.48f,colors.accentSurface,Ui.dp(host,8));
            block(canvas,p,h*.57f,w*.65f,h*.57f+Ui.dp(host,4),colors.muted,Ui.dp(host,2));
            block(canvas,p,h*.67f,w*.5f,h*.67f+Ui.dp(host,4),colors.muted,Ui.dp(host,2));
            block(canvas,p,h-Ui.dp(host,24),w-p,h-Ui.dp(host,10),colors.surface,Ui.dp(host,7));
            paint.setColor(colors.accent);canvas.drawCircle(w-p-Ui.dp(host,7),h-Ui.dp(host,17),Ui.dp(host,4),paint);
        }
        private void block(Canvas canvas,float l,float t,float r,float b,int color,float radius){paint.setColor(color);bounds.set(l,t,r,b);canvas.drawRoundRect(bounds,radius,radius,paint);}
    }
    private boolean stacked(){return host.getResources().getConfiguration().screenWidthDp<360||host.getResources().getConfiguration().fontScale>=1.3f;}
    private static String themeLabel(String value){return "dark".equals(value)?"深色":"light".equals(value)?"浅色":"跟随系统";}
    private static void removeTopMargin(View view){LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)view.getLayoutParams();p.topMargin=0;view.setLayoutParams(p);}
}
