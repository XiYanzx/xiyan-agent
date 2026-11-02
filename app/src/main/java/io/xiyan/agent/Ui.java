// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import io.xiyan.agent.account.SiwcClient;
import io.xiyan.agent.core.response.ServiceFailure;
import java.util.Date;
import java.util.Locale;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Original native components and shared semantic colors. */
final class Ui {
    static final int BACKGROUND=0xff101919, SURFACE=0xff172323, TEXT=0xffedf5f2;
    static final int MUTED=0xffa1b6ae, ACCENT=0xff79d5bd, ALERT=0xfff39bab;
    static final class Colors extends DesignTokens.Palette {
        Colors(boolean light) { super(light); }
    }
    private static final Colors LIGHT=new Colors(true), DARK=new Colors(false);
    private Ui() { }
    static String themeChoice(Context c) { return c.getSharedPreferences("xiyan_appearance",Context.MODE_PRIVATE).getString("theme","system"); }
    static boolean isLight(Activity a) {
        String choice=themeChoice(a);
        return "light".equals(choice)||("system".equals(choice)&&(a.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)!=Configuration.UI_MODE_NIGHT_YES);
    }
    static Colors colors(Activity a) { return isLight(a)?LIGHT:DARK; }
    static void applyTheme(Activity a) { a.setTheme(isLight(a)?R.style.XiyanLightTheme:R.style.XiyanDarkTheme); }
    static int dp(Activity a,float n) { return Math.round(n*a.getResources().getDisplayMetrics().density); }
    static LinearLayout column(Activity a) { LinearLayout v=new LinearLayout(a);v.setOrientation(LinearLayout.VERTICAL);return v; }
    static LinearLayout row(Activity a) { LinearLayout v=new LinearLayout(a);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v; }
    static LinearLayout page(Activity a) { LinearLayout v=column(a);int side=a.getResources().getConfiguration().screenWidthDp<=360?DesignTokens.PAGE_NARROW:DesignTokens.PAGE;v.setPadding(dp(a,side),dp(a,12),dp(a,side),dp(a,24));return v; }
    static ScrollView scroll(Activity a,View content) {
        ScrollView v=new ScrollView(a);v.setFillViewport(true);v.setClipToPadding(false);
        FrameLayout center=new FrameLayout(a) {
            @Override protected void onMeasure(int widthSpec,int heightSpec) {
                content.getLayoutParams().width=Math.min(View.MeasureSpec.getSize(widthSpec),dp(a,DesignTokens.CONTENT_MAX));
                super.onMeasure(widthSpec,heightSpec);
            }
        };
        center.addView(content,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL));
        v.addView(center);return v;
    }
    @SuppressWarnings("deprecation")
    static void install(Activity a,View root) {
        Colors c=colors(a);root.setBackgroundColor(c.background);a.setContentView(root);
        a.getWindow().setStatusBarColor(c.background);a.getWindow().setNavigationBarColor(c.background);
        if(Build.VERSION.SDK_INT>=30) {
            android.view.WindowInsetsController controller=a.getWindow().getInsetsController();
            if(controller!=null)controller.setSystemBarsAppearance(isLight(a)?android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS:0,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        } else a.getWindow().getDecorView().setSystemUiVisibility(isLight(a)?View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:0);
        if(Build.VERSION.SDK_INT>=30)installAnimatedInsets(root);
        else root.setOnApplyWindowInsetsListener((v,insets)->{
            v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;
        });
        root.requestApplyInsets();
    }
    private static void installAnimatedInsets(View root){
        if(Build.VERSION.SDK_INT<30)return;
        Set<WindowInsetsAnimation> imeAnimations=new HashSet<>();
        WindowInsets[] target=new WindowInsets[1];
        root.setOnApplyWindowInsetsListener((view,insets)->{
            target[0]=insets;
            if(imeAnimations.isEmpty())applyInsets(view,insets);
            return insets;
        });
        root.setWindowInsetsAnimationCallback(new WindowInsetsAnimation.Callback(WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE){
            @Override public void onPrepare(WindowInsetsAnimation animation){
                if((animation.getTypeMask()&WindowInsets.Type.ime())!=0&&motionEnabled())imeAnimations.add(animation);
            }
            @Override public WindowInsets onProgress(WindowInsets insets,List<WindowInsetsAnimation> runningAnimations){
                if(!imeAnimations.isEmpty())applyInsets(root,insets);
                return insets;
            }
            @Override public void onEnd(WindowInsetsAnimation animation){
                if(imeAnimations.remove(animation)&&imeAnimations.isEmpty()){
                    if(target[0]!=null)applyInsets(root,target[0]);
                    root.requestApplyInsets();
                }
            }
        });
    }
    private static void applyInsets(View root,WindowInsets insets){
        if(Build.VERSION.SDK_INT<30)return;
        android.graphics.Insets padding=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());
        if(root.getPaddingLeft()!=padding.left||root.getPaddingTop()!=padding.top||root.getPaddingRight()!=padding.right||root.getPaddingBottom()!=padding.bottom)
            root.setPadding(padding.left,padding.top,padding.right,padding.bottom);
    }
    static GradientDrawable shape(Activity a,int fill,int radius,int border) {
        GradientDrawable v=new GradientDrawable();v.setColor(fill);v.setCornerRadius(dp(a,radius));
        if(border!=Color.TRANSPARENT)v.setStroke(Math.max(1,dp(a,.5f)),border);return v;
    }
    private static int resolve(Activity a,int value) {
        Colors c=colors(a);if(value==TEXT)return c.text;if(value==MUTED)return c.muted;if(value==ACCENT)return c.accent;if(value==ALERT)return c.alert;return value;
    }
    static TextView text(Activity a,String value,int size,int color) {
        TextView v=new TextView(a);v.setText(value);v.setTextSize(size);v.setTextColor(resolve(a,color));
        v.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));v.setIncludeFontPadding(false);
        float line=size*(size>=22?1.3f:1.5f);v.setLineHeight(Math.round(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,line,a.getResources().getDisplayMetrics())));
        v.setFontFeatureSettings("tnum");v.setPadding(0,dp(a,2),0,dp(a,2));return v;
    }
    static TextView heading(Activity a,String label,int size) { TextView v=text(a,label,size,TEXT);v.setTypeface(Typeface.create(Typeface.create("sans-serif",Typeface.NORMAL),600,false));v.setAccessibilityHeading(true);return v; }
    static void section(Activity a,LinearLayout parent,String label) { TextView v=heading(a,label,16);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(a,24),0,dp(a,10));parent.addView(v,p); }
    static void gap(Activity a,LinearLayout parent,int height) { parent.addView(new View(a),new LinearLayout.LayoutParams(1,dp(a,height))); }
    static void divider(Activity a,LinearLayout parent) { View v=new View(a);v.setBackgroundColor(colors(a).border);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(a,1));p.setMargins(0,dp(a,10),0,dp(a,10));parent.addView(v,p); }
    static Button action(Activity a,String label,boolean primary,boolean danger,Runnable action) {
        Colors c=colors(a);Button b=new Button(a);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        b.setStateListAnimator(null);b.setElevation(0);b.setTranslationZ(0);b.setIncludeFontPadding(false);
        int fg=danger?c.alert:primary?c.onAccent:c.text;
        b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{c.muted,fg}));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(isLight(a)?0x22087f72:0x3379d5bd),shape(a,danger?c.alertSurface:primary?c.accent:c.raised,DesignTokens.RADIUS_CONTROL,Color.TRANSPARENT),null));
        b.setMinHeight(dp(a,48));b.setMinimumHeight(dp(a,48));b.setMinWidth(dp(a,48));b.setMinimumWidth(dp(a,48));b.setPadding(dp(a,14),dp(a,10),dp(a,14),dp(a,10));b.setOnClickListener(v->action.run());return b;
    }
    static Button outlineAction(Activity a,String label,Runnable action) {
        Button button=action(a,label,false,false,action);Colors c=colors(a);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22087f72),shape(a,Color.TRANSPARENT,DesignTokens.RADIUS_CONTROL,c.border),null));return button;
    }
    static Button textAction(Activity a,String label,Runnable action) {
        Button button=action(a,label,false,false,action);Colors c=colors(a);button.setTextColor(c.accent);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22087f72),shape(a,Color.TRANSPARENT,12,Color.TRANSPARENT),null));return button;
    }
    static Button chip(Activity a,String label,boolean selected,Runnable action) {
        Button button=action(a,label,false,false,action);Colors c=colors(a);button.setTextSize(12);
        button.setTextColor(selected?c.accent:c.muted);button.setSelected(selected);
        button.setBackground(new InsetDrawable(new RippleDrawable(ColorStateList.valueOf(0x22087f72),
                shape(a,selected?c.accentSurface:c.raised,24,Color.TRANSPARENT),null),0,dp(a,6),0,dp(a,6)));
        button.setPadding(dp(a,12),dp(a,8),dp(a,12),dp(a,8));return button;
    }
    static FrameLayout iconButton(Activity a,AgentIconView.Icon icon,String description,int color,Runnable action) {
        FrameLayout hit=new FrameLayout(a) {
            @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) { super.onInitializeAccessibilityNodeInfo(info);info.setClassName(Button.class.getName()); }
        };
        hit.setMinimumWidth(dp(a,48));hit.setMinimumHeight(dp(a,48));hit.setFocusable(true);hit.setClickable(true);hit.setContentDescription(description);
        hit.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22087f72),shape(a,Color.TRANSPARENT,24,Color.TRANSPARENT),null));
        hit.addView(new AgentIconView(a,icon,color),new FrameLayout.LayoutParams(dp(a,24),dp(a,24),Gravity.CENTER));hit.setOnClickListener(v->action.run());return hit;
    }
    static void iconSurface(Activity a,View button,int fill,int ink,int radius){
        int ripple=Color.argb(42,Color.red(ink),Color.green(ink),Color.blue(ink));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple),shape(a,fill,radius,Color.TRANSPARENT),null));
    }
    static void downIndicator(Activity a,TextView view,int color){
        android.graphics.drawable.Drawable down=a.getDrawable(R.drawable.ic_lucide_chevron_down).mutate();down.setTint(color);
        down.setBounds(0,0,dp(a,14),dp(a,14));view.setCompoundDrawablesRelative(null,null,down,null);view.setCompoundDrawablePadding(dp(a,6));
    }
    static void enabled(View view,boolean value) { view.setEnabled(value);view.setAlpha(value?1f:.42f); }
    static LinearLayout listRow(Activity a,LinearLayout parent,AgentIconView.Icon icon,int tint,String title,String subtitle,Runnable action) {
        LinearLayout row=row(a);row.setMinimumHeight(dp(a,subtitle.isEmpty()?56:64));row.setPadding(dp(a,4),dp(a,8),dp(a,4),dp(a,8));
        row.addView(new AgentIconView(a,icon,tint),new LinearLayout.LayoutParams(dp(a,24),dp(a,24)));
        LinearLayout words=column(a);TextView name=text(a,title,15,TEXT);name.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));words.addView(name);if(!subtitle.isEmpty())words.addView(text(a,subtitle,13,MUTED));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(dp(a,14),0,dp(a,8),0);row.addView(words,p);
        if(action!=null) {
            row.addView(new AgentIconView(a,AgentIconView.Icon.CHEVRON,colors(a).muted),new LinearLayout.LayoutParams(dp(a,16),dp(a,16)));
            row.setFocusable(true);row.setClickable(true);row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22087f72),shape(a,Color.TRANSPARENT,12,Color.TRANSPARENT),null));row.setOnClickListener(v->action.run());
        }
        parent.addView(row,new LinearLayout.LayoutParams(-1,-2));return row;
    }
    // Compatibility for the unchanged control lab and developer Activity.
    static LinearLayout surface(Activity a,LinearLayout parent,int fill) { LinearLayout v=column(a);int p=dp(a,16);v.setPadding(p,p,p,p);v.setBackground(shape(a,fill,DesignTokens.RADIUS_PANEL,colors(a).border));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(a,12),0,0);parent.addView(v,lp);return v; }
    static LinearLayout card(Activity a,LinearLayout parent,String eyebrow,String title) { LinearLayout v=surface(a,parent,colors(a).surface);if(!eyebrow.isEmpty())v.addView(text(a,eyebrow,12,ACCENT));v.addView(heading(a,title,20));return v; }
    static Button button(Activity a,LinearLayout parent,String label,boolean danger,Runnable action) { Button b=action(a,label,false,danger,action);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(a,10),0,0);parent.addView(b,p);return b; }
    static TextView badge(Activity a,String label) { TextView v=text(a,label,12,ACCENT);v.setPadding(dp(a,10),dp(a,5),dp(a,10),dp(a,5));v.setBackground(shape(a,colors(a).accentSurface,20,Color.TRANSPARENT));return v; }
    static void showBottomDialog(Activity a,Dialog dialog,View content) {
        dialog.setContentView(content);Window w=dialog.getWindow();if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);w.setWindowAnimations(motionEnabled()?R.style.XiyanSheetAnimation:0);}
        dialog.show();if(w!=null){w.setGravity(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);w.setDimAmount(.35f);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setLayout(dp(a,Math.min(a.getResources().getConfiguration().screenWidthDp-16,480)),-2);}
    }
    static boolean motionEnabled(){return android.animation.ValueAnimator.areAnimatorsEnabled();}
    static void enter(Activity activity,View view){
        if(!motionEnabled())return;view.setAlpha(0f);view.setTranslationY(dp(activity,8));
        view.animate().alpha(1f).translationY(0f).setDuration(DesignTokens.MOTION_ENTER)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }
    static String percent(double n) { return n==Math.rint(n)?String.format(Locale.ROOT,"%.0f",n):String.format(Locale.ROOT,"%.1f",n); }
    static String time(long millis) { return millis<=0?"待获取":new java.text.SimpleDateFormat("M月d日 HH:mm",Locale.SIMPLIFIED_CHINESE).format(new Date(millis)); }
    static String friendlyFailure(SiwcClient.Snapshot s) {
        ServiceFailure f=s.lastFailure;
        if(f!=null) {
            if(f.getHttpStatus()==400&&"reasoning.effort".equals(f.getParam())&&"invalid_value".equals(f.getCode()))return "当前接口未接受所选思考程度，请选择默认后重试。";
            switch(f.getKind()) {
                case AUTHORIZATION_REQUIRED:return "账号需要重新连接，请前往账号页面。";
                case USAGE_LIMIT:return "官方额度或请求频率受限，请查看用量后再试。";
                case MODEL_UNAVAILABLE:return "所选模型暂不可用，请刷新模型列表。";
                case TEMPORARY_UNAVAILABLE:return "服务暂时不可用，请稍后重试。";
                case POLICY_RESTRICTED:return "当前账号的授权范围受限，请检查账号状态。";
                case UNSUPPORTED_CAPABILITY:return "当前连接暂未支持所选功能。";
                case CLIENT_CONFIGURATION:return "账号连接配置尚未通过，请查看账号状态。";
                case ROUTE_UNSUPPORTED:return "当前连接暂未支持此请求。";
                default:break;
            }
        }
        return "这次请求未完成，请检查连接或调整选项后重试。";
    }
    static String accountMessage(SiwcClient.Snapshot s) {
        if(s.busy) {
            if("running".equals(s.inferenceStatus))return "正在接收模型回复…";
            if(s.operationStage.contains("目录"))return "正在更新模型与用量信息…";
            return "正在连接账号，请完成浏览器授权后返回。";
        }
        if(s.failureCode!=null)return friendlyFailure(s);
        if(s.reauthRequired)return "请重新连接账号，继续使用模型。";
        if(s.hasSession)return s.connected?"账号已连接":"账号已保存，刷新时会续期";
        return "连接 ChatGPT 账号后即可选择模型。";
    }
}
