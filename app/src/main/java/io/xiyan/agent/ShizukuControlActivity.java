// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import io.xiyan.agent.core.shizuku.InputAction;
import io.xiyan.agent.shizuku.ShizukuController;

/** User initiated, bounded Shizuku controls with an application-owned verification area. */
public final class ShizukuControlActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private ShizukuController controller;
    private TextView state,detail,result,guidance;
    private Button request,connect,tap,swipe,back,home,target,verifyEntry,stop,manager,disconnect;
    private ScrollView swipeTarget;
    private int clicks,viewGeneration;
    private boolean resumed;
    private final Runnable observer=()->runOnUiThread(this::refresh);

    @Override public void onCreate(Bundle saved){
        Ui.applyTheme(this);super.onCreate(saved);controller=ShizukuController.getInstance(this);
        LinearLayout page=Ui.page(this);LinearLayout top=Ui.row(this);
        top.addView(Ui.iconButton(this,AgentIconView.Icon.BACK,"返回设置",Ui.colors(this).text,this::finish),new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));
        boolean verificationMode=getIntent().getBooleanExtra("verification",false);
        top.addView(Ui.heading(this,verificationMode?"单步验证":"手机操控",20));page.addView(top);
        Ui.gap(this,page,20);Ui.editorial(this,page,"05 / SHIZUKU",verificationMode?"核对每次操作。":"让手机响应。");
        state=Ui.heading(this,"",20);page.addView(state);detail=Ui.text(this,"",13,Ui.MUTED);page.addView(detail);
        Ui.gap(this,page,12);guidance=Ui.text(this,"",14,Ui.TEXT);page.addView(guidance);
        LinearLayout setup=Ui.column(this);page.addView(setup);setup.setVisibility(verificationMode?View.GONE:View.VISIBLE);
        manager=Ui.action(this,"打开 Shizuku",true,false,()->{
            Intent launch=getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if(launch!=null)startActivity(launch);else startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://shizuku.rikka.app/download/")));
        });setup.addView(manager);
        request=Ui.action(this,"授权 Shizuku",true,false,()->controller.requestPermission());setup.addView(request,new LinearLayout.LayoutParams(-1,-2));
        connect=Ui.action(this,"连接操控服务",true,false,()->controller.connect());setup.addView(connect,new LinearLayout.LayoutParams(-1,-2));
        disconnect=Ui.textAction(this,"断开操控服务",()->controller.disconnect());setup.addView(disconnect);
        Ui.gap(this,setup,20);verifyEntry=Ui.action(this,"进入单步操控验证",true,false,()->startActivity(new Intent(this,ShizukuControlActivity.class).putExtra("verification",true)));setup.addView(verifyEntry,new LinearLayout.LayoutParams(-1,-2));
        stop=Ui.action(this,"停止后续动作",false,true,()->{viewGeneration++;controller.stop();result.setText("已撤销后续动作，等待已派发输入结束。");});page.addView(stop,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout verification=Ui.column(this);page.addView(verification);verification.setVisibility(verificationMode?View.VISIBLE:View.GONE);
        Ui.section(this,verification,"01 / 验证点击");
        target=Ui.action(this,getString(R.string.shizuku_click_count,0),false,false,()->{clicks++;target.setText(getString(R.string.shizuku_click_count,clicks));});
        target.setContentDescription("Shizuku 点击验证目标");verification.addView(target,new LinearLayout.LayoutParams(-1,Ui.dp(this,88)));
        tap=Ui.button(this,verification,"通过 Shizuku 验证点击",false,this::verifyTap);
        Ui.section(this,verification,"02 / 验证滑动");
        swipeTarget=new ScrollView(this);swipeTarget.setContentDescription("Shizuku 滑动验证区域");
        LinearLayout strips=Ui.column(this);for(int i=1;i<=10;i++){TextView row=Ui.text(this,"测试行 "+i,15,Ui.TEXT);row.setGravity(Gravity.CENTER);row.setBackgroundColor(i%2==0?Ui.colors(this).raised:Ui.colors(this).surface);strips.addView(row,new LinearLayout.LayoutParams(-1,Ui.dp(this,48)));}
        swipeTarget.addView(strips);verification.addView(swipeTarget,new LinearLayout.LayoutParams(-1,Ui.dp(this,160)));
        swipe=Ui.button(this,verification,"通过 Shizuku 验证上滑",false,this::verifySwipe);
        Ui.section(this,verification,"03 / 系统导航");
        back=Ui.button(this,verification,"通过 Shizuku 返回",false,()->navigation(false));
        home=Ui.button(this,verification,"通过 Shizuku 回到主页",false,()->navigation(true));
        result=Ui.text(this,verificationMode?"等待单步验证。每个按钮仅提交一次操作。":"连接后可在单步验证页核对点击、滑动与系统导航。",13,Ui.MUTED);result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);page.addView(result);
        Ui.gap(this,page,12);page.addView(Ui.text(this,"输入命令完成后继续核对画面。自主任务与游戏策略仍在开发。",12,Ui.MUTED));
        Ui.install(this,Ui.scroll(this,page));refresh();
    }
    @Override protected void onResume(){super.onResume();resumed=true;controller.addListener(observer);controller.refresh();refresh();}
    @Override protected void onPause(){resumed=false;viewGeneration++;handler.removeCallbacksAndMessages(null);if(controller.snapshot().busy)controller.stop();controller.removeListener(observer);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);super.onDestroy();}
    @SuppressWarnings("deprecation") private int[] geometry(){Point size=new Point();getWindowManager().getDefaultDisplay().getRealSize(size);return new int[]{size.x,size.y,getWindowManager().getDefaultDisplay().getRotation()};}
    private void verifyTap(){
        Rect visible=visibleOnScreen(target);if(visible==null||visible.height()<Ui.dp(this,48)){result.setText("先将点击目标完整滚动到画面中。");return;}
        int[] g=geometry();execute(InputAction.tap(visible.centerX(),visible.centerY(),g[0],g[1],g[2]),1,clicks);
    }
    private void verifySwipe(){
        Rect visible=visibleOnScreen(swipeTarget);if(visible==null||visible.height()<Ui.dp(this,80)){result.setText("先将滑动区域完整滚动到画面中。");return;}
        int[] g=geometry();execute(InputAction.swipe(visible.centerX(),visible.bottom-Ui.dp(this,16),visible.centerX(),visible.top+Ui.dp(this,16),350,g[0],g[1],g[2]),2,swipeTarget.getScrollY());
    }
    private void navigation(boolean goHome){int[] g=geometry();execute(goHome?InputAction.home(g[0],g[1],g[2]):InputAction.back(g[0],g[1],g[2]),0,0);}
    private Rect visibleOnScreen(View view){Rect rect=new Rect();if(!view.getLocalVisibleRect(rect))return null;int[] location=new int[2];view.getLocationOnScreen(location);rect.offset(location[0],location[1]);return rect;}
    @SuppressWarnings("deprecation") private void execute(InputAction action,int verification,int before){
        if(getWindowManager().getDefaultDisplay().getDisplayId()!=0){result.setText("请在手机主屏幕使用本次操控验证。");return;}
        if(!resumed)return;ShizukuController.Snapshot snapshot=controller.snapshot();int expected=viewGeneration;
        result.setText("正在派发本次输入…");boolean accepted=controller.execute(snapshot.generation,action,outcome->runOnUiThread(()->{
            if(!resumed||viewGeneration!=expected||!outcome.acceptedCurrentGeneration)return;
            if(!outcome.commandCompleted()){result.setText("本次输入未完成，请检查操控状态后重试。");return;}
            handler.postDelayed(()->{
                if(!resumed||viewGeneration!=expected)return;
                int[] current=geometry();if(!action.matchesDisplay(current[0],current[1],current[2])){result.setText("显示范围已改变，请重新核对本次输入。");return;}
                if(verification==1)result.setText(clicks==before+1?"点击验证通过：目标计数增加 1。":"输入命令完成，目标计数仍待核对。");
                else if(verification==2)result.setText(swipeTarget.getScrollY()>before?"滑动验证通过：测试区域已向上滚动。":"输入命令完成，滚动结果仍待核对。");
                else result.setText("本次导航输入已完成，继续核对当前画面。");
            },200);
        }));
        if(!accepted)result.setText("操控状态已变化，请检查连接后重试本次操作。");
    }
    private void refresh(){
        if(state==null)return;ShizukuController.Snapshot s=controller.snapshot();state.setText(ShizukuUi.status(s));
        String pending=getString(R.string.shizuku_pending);
        detail.setText(getString(R.string.shizuku_connection_detail,s.mode==ShizukuController.Mode.UNKNOWN?pending:s.mode,s.uid<0?pending:String.format(java.util.Locale.ROOT,"%d",s.uid),s.serverVersion<0?pending:String.format(java.util.Locale.ROOT,"%d",s.serverVersion)));
        Ui.enabled(request,s.binderAlive&&!s.permissionGranted);Ui.enabled(connect,s.binderAlive&&s.permissionGranted&&!s.binding&&!s.bound&&!s.busy);
        manager.setVisibility(s.binderAlive?View.GONE:View.VISIBLE);
        request.setVisibility(s.binderAlive&&!s.permissionGranted?View.VISIBLE:View.GONE);connect.setVisibility(s.permissionGranted&&!s.bound?View.VISIBLE:View.GONE);
        disconnect.setVisibility(s.bound?View.VISIBLE:View.GONE);verifyEntry.setVisibility(s.bound?View.VISIBLE:View.GONE);
        stop.setVisibility(s.busy?View.VISIBLE:View.GONE);Ui.enabled(verifyEntry,s.bound&&!s.busy);
        guidance.setText(s.busy?"正在处理已派发输入，请稍候再连接或开始下一次操作。":!s.binderAlive?"先在 Shizuku 管理器启动，再回到这里授权 Xiyan。":!s.permissionGranted?"Shizuku 已启动，请授权 Xiyan 使用本机操作通道。":!s.bound?"已取得授权。连接本机操控服务后，可进入单步验证。":"通道已连接，可进入单步验证核对操作结果。");
        boolean ready=resumed&&s.bound&&!s.busy;for(Button button:new Button[]{tap,swipe,back,home})Ui.enabled(button,ready);
    }
}
