// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import io.xiyan.agent.account.SiwcClient;
import io.xiyan.agent.control.XiyanAccessibilityService;
import io.xiyan.agent.core.model.ModelCatalog;
import java.util.ArrayDeque;
import java.util.ArrayList;

/** Native, chat-first shell. Each destination owns its own content and scroll state. */
public final class MainActivity extends Activity {
    enum Screen {
        CHAT("对话"), TASKS("任务"), MODELS("模型"), ACCOUNT("账号"), USAGE("用量"),
        SETTINGS("设置"), APPEARANCE("外观与显示"), CONTROLS("手机控制"), ABOUT("关于");
        final String title;
        Screen(String title) { this.title=title; }
    }
    private SiwcClient account;
    private Diagnostics diagnostics;
    private Screen screen=Screen.CHAT;
    private final ArrayDeque<Screen> backStack=new ArrayDeque<>();
    private ScreenContent content;
    private FrameLayout screenHost;
    private LinearLayout toolbar;
    private NavigationDrawer drawer;
    private ModelPickerDialog picker;
    private TextView toolbarTitle;
    private FrameLayout toolbarAction;
    private boolean toolbarActive;
    private String draft="",submittedPrompt="";
    private String submittedAccountScope;
    private int draftStart,draftEnd;
    private boolean showingReply,preparingReply,started,lastHasSession;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable clockRefresh=new Runnable() {
        @Override public void run() { if(started){refresh();handler.postDelayed(this,30000);} }
    };
    private final SiwcClient.Observer accountObserver=()->runOnUiThread(this::refresh);
    private final XiyanAccessibilityService.StateListener controlObserver=state->runOnUiThread(this::refresh);

    @Override public void onCreate(Bundle savedState) {
        Ui.applyTheme(this);super.onCreate(savedState);
        diagnostics=new Diagnostics(this);account=SiwcClient.get(this);
        draft=getPreferences(MODE_PRIVATE).getString("taskDraft","");
        if(savedState!=null) {
            try {screen=Screen.valueOf(savedState.getString("screen","CHAT"));}catch(IllegalArgumentException ignored){screen=Screen.CHAT;}
            draft=savedState.getString("draft",draft);submittedPrompt=savedState.getString("submittedPrompt","");
            submittedAccountScope=savedState.getString("submittedAccountScope");
            draftStart=savedState.getInt("draftStart",draft.length());draftEnd=savedState.getInt("draftEnd",draft.length());
            showingReply=savedState.getBoolean("showingReply");
            preparingReply=savedState.getBoolean("preparingReply");
            ArrayList<String> history=savedState.getStringArrayList("backStack");
            if(history!=null)for(String name:history)try{backStack.addLast(Screen.valueOf(name));}catch(IllegalArgumentException ignored){ }
        } else {
            draftStart=draftEnd=draft.length();
            showingReply=!account.snapshot().inferenceText.isEmpty()||"running".equals(account.snapshot().inferenceStatus);
        }
        lastHasSession=account.snapshot().hasSession;
        installShell();
        if(android.os.Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::handleBack);
    }
    private void installShell() {
        FrameLayout root=new FrameLayout(this);
        LinearLayout main=Ui.column(this);
        toolbar=Ui.row(this);toolbar.setMinimumHeight(Ui.dp(this,56));toolbar.setPadding(Ui.dp(this,6),0,Ui.dp(this,8),0);
        main.addView(toolbar,new LinearLayout.LayoutParams(-1,-2));
        screenHost=new FrameLayout(this);main.addView(screenHost,new LinearLayout.LayoutParams(-1,0,1));
        root.addView(main,new FrameLayout.LayoutParams(-1,-1));
        drawer=new NavigationDrawer(this,root);
        Ui.install(this,root);showScreen();refresh();
    }
    @Override protected void onStart() {
        super.onStart();started=true;account.addObserver(accountObserver);
        XiyanAccessibilityService.addStateListener(controlObserver);refresh();handler.postDelayed(clockRefresh,30000);
    }
    @Override protected void onResume() {super.onResume();refresh();}
    @Override protected void onStop() {
        started=false;handler.removeCallbacks(clockRefresh);account.removeObserver(accountObserver);
        XiyanAccessibilityService.removeStateListener(controlObserver);super.onStop();
    }
    @Override protected void onDestroy() {
        if(content!=null)content.leave();
        if(picker!=null)picker.dismiss();handler.removeCallbacks(clockRefresh);super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        if(content!=null)content.leave();
        state.putString("screen",screen.name());state.putString("draft",draft);
        state.putString("submittedPrompt",submittedPrompt);state.putBoolean("showingReply",showingReply);
        state.putString("submittedAccountScope",submittedAccountScope);
        state.putBoolean("preparingReply",preparingReply);
        state.putInt("draftStart",draftStart);state.putInt("draftEnd",draftEnd);
        ArrayList<String> history=new ArrayList<>();for(Screen item:backStack)history.add(item.name());
        state.putStringArrayList("backStack",history);super.onSaveInstanceState(state);
    }
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed(){handleBack();}
    private void handleBack() {
        if(drawer!=null&&drawer.isOpen()){drawer.close();return;}
        if(!backStack.isEmpty()){screen=backStack.removeLast();showScreen();return;}
        if(screen!=Screen.CHAT){screen=Screen.CHAT;showScreen();return;}
        finish();
    }
    void navigate(Screen destination) {
        if(destination!=screen){backStack.addLast(screen);screen=destination;showScreen();}
        drawer.close();
    }
    void destination(Screen destination) {
        backStack.clear();screen=destination;showScreen();drawer.close();
    }
    private void showScreen() {
        if(content!=null)content.leave();
        if(screen==Screen.CHAT)content=new ChatScreen(this);
        else if(screen==Screen.MODELS)content=new ModelPickerDialog.ModelScreen(this);
        else if(screen==Screen.USAGE||screen==Screen.ACCOUNT)content=new AccountUsageScreen(this,screen==Screen.ACCOUNT);
        else if(screen==Screen.TASKS)content=new TasksScreen(this);
        else content=new SettingsScreen(this,screen);
        screenHost.removeAllViews();screenHost.addView(content.view(),new FrameLayout.LayoutParams(-1,-1));
        rebuildToolbar();content.update(account.snapshot());Ui.enter(this,content.view());
    }
    private void rebuildToolbar() {
        setTitle(screen==Screen.CHAT?"Xiyan":screen.title);
        toolbar.setVisibility(View.VISIBLE);toolbar.removeAllViews();Ui.Colors c=Ui.colors(this);
        boolean child=screen==Screen.APPEARANCE||screen==Screen.CONTROLS||screen==Screen.ABOUT||screen==Screen.ACCOUNT;
        toolbar.addView(Ui.iconButton(this,child?AgentIconView.Icon.BACK:AgentIconView.Icon.MENU,
                child?"返回":"打开导航",c.text,child?this::handleBack:drawer::open),new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));
        toolbarTitle=Ui.heading(this,screen==Screen.CHAT?"Xiyan":screen.title,20);
        toolbar.addView(toolbarTitle,new LinearLayout.LayoutParams(0,-2,1));
        toolbarAction=null;
        if(screen==Screen.CHAT)toolbar.addView(Ui.iconButton(this,AgentIconView.Icon.PLUS,"新建对话",c.accent,this::newChat),
                new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));
        else {
            toolbarAction=Ui.iconButton(this,AgentIconView.Icon.MORE,"页面操作",c.muted,this::pageAction);
            toolbar.addView(toolbarAction,new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));
            toolbarActive=false;updateToolbarAction(account.snapshot());
        }
    }
    Screen currentScreen(){return screen;}
    private void pageAction(){
        SiwcClient.Snapshot s=account.snapshot();
        if(s.busy||"running".equals(s.inferenceStatus)){emergencyStop();return;}
        android.widget.PopupMenu menu=new android.widget.PopupMenu(this,toolbarAction);
        menu.getMenu().add("停止后续手机动作").setOnMenuItemClickListener(item->{emergencyStop();return true;});
        menu.show();
    }
    private void updateToolbarAction(SiwcClient.Snapshot snapshot){
        if(toolbarAction==null)return;boolean active=snapshot.busy||"running".equals(snapshot.inferenceStatus);
        Ui.Colors colors=Ui.colors(this);
        ((AgentIconView)toolbarAction.getChildAt(0)).appearance(active?AgentIconView.Icon.STOP:AgentIconView.Icon.MORE,active?colors.alert:colors.muted);
        toolbarAction.setContentDescription(active?"停止当前回复与后续手机动作":"页面操作");
        if(active!=toolbarActive){
            toolbarActive=active;toolbarAction.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x22087f72),Ui.shape(this,active?colors.alertSurface:Color.TRANSPARENT,20,Color.TRANSPARENT),null));
        }
    }
    int chatViewportHeight(){return toolbar.getParent() instanceof View?((View)toolbar.getParent()).getHeight():0;}
    void compactChatChrome(boolean compact){toolbar.setVisibility(compact&&screen==Screen.CHAT?View.GONE:View.VISIBLE);}
    void openNavigation(){drawer.open();}
    private void refresh() {
        if(account==null||isDestroyed()||content==null)return;
        SiwcClient.Snapshot s=account.snapshot();
        if(lastHasSession&&!s.hasSession)clearLocalTurn();
        if(preparingReply&&("running".equals(s.inferenceStatus)||!s.busy))preparingReply=false;
        if(!preparingReply&&"idle".equals(s.inferenceStatus))clearLocalTurn();
        if(submittedAccountScope!=null&&s.catalog!=null&&!submittedAccountScope.equals(s.catalog.getAccountScope()))clearLocalTurn();
        lastHasSession=s.hasSession;
        updateToolbarAction(s);
        content.update(s);if(drawer!=null)drawer.update(s);
        if(picker!=null&&picker.isShowing())picker.update(s);
    }
    private void clearLocalTurn(){submittedPrompt="";submittedAccountScope=null;showingReply=false;preparingReply=false;}
    SiwcClient account(){return account;}
    String draft(){return draft;}
    String submittedPrompt(){return submittedPrompt;}
    boolean showingReply(){return showingReply;}
    boolean preparingReply(){return preparingReply;}
    int draftStart(){return draftStart;}
    int draftEnd(){return draftEnd;}
    void draftEdited(String value){draft=value;}
    void draftSelection(int start,int end){draftStart=Math.max(0,start);draftEnd=Math.max(0,end);}
    String selectedMode(){return getPreferences(MODE_PRIVATE).getString("mode","通用操作");}
    void selectMode(String mode) {
        getPreferences(MODE_PRIVATE).edit().putString("mode",mode).apply();diagnostics.record("模式偏好",mode);refresh();
    }
    void chooseMode() {
        new AlertDialog.Builder(this).setTitle("任务模式")
                .setSingleChoiceItems(new String[]{"通用操作","游戏操作"},"游戏操作".equals(selectedMode())?1:0,(dialog,which)->{
                    selectMode(which==0?"通用操作":"游戏操作");dialog.dismiss();
                }).setNegativeButton("关闭",null).show();
    }
    void saveDraft() {
        if(content!=null)content.leave();
        getPreferences(MODE_PRIVATE).edit().putString("taskDraft",draft).apply();
        toast("草稿已保存在此设备");drawer.update(account.snapshot());
    }
    boolean hasSavedDraft(){return !getPreferences(MODE_PRIVATE).getString("taskDraft","").isEmpty();}
    void loadSavedDraft() {
        String saved=getPreferences(MODE_PRIVATE).getString("taskDraft","");
        Runnable load=()->replaceDraft(saved);
        if(!draft.isEmpty()&&!draft.equals(saved))new AlertDialog.Builder(this).setTitle("载入已保存草稿？")
                .setMessage("当前输入将被替换。").setNegativeButton("保留当前输入",null)
                .setPositiveButton("载入草稿",(d,w)->load.run()).show();
        else load.run();
        drawer.close();
    }
    void fillDraft(String value) {fillTaskDraft(null,value);}
    void fillTaskDraft(String mode,String value) {
        Runnable fill=()->{if(mode!=null)selectMode(mode);replaceDraft(value);};
        if(!draft.isEmpty()&&!draft.equals(value))new AlertDialog.Builder(this).setTitle("替换当前输入？")
                .setMessage("你可以先保存当前草稿，再使用任务模板。").setNegativeButton("保留内容",null)
                .setPositiveButton("使用模板",(d,w)->fill.run()).show();
        else fill.run();
    }
    private void replaceDraft(String value) {
        draft=value;draftStart=draftEnd=value.length();
        if(screen==Screen.CHAT&&content instanceof ChatScreen)((ChatScreen)content).replaceDraft(value);
        else navigate(Screen.CHAT);
    }
    void editLastPrompt(){if(!submittedPrompt.isEmpty())fillDraft(submittedPrompt);}
    private void newChat() {
        boolean running=preparingReply||"running".equals(account.snapshot().inferenceStatus);
        Runnable create=()->{
            if(preparingReply||"running".equals(account.snapshot().inferenceStatus))emergencyStop();
            if(!draft.isEmpty())getPreferences(MODE_PRIVATE).edit().putString("taskDraft",draft).apply();
            draft="";draftStart=draftEnd=0;clearLocalTurn();
            destination(Screen.CHAT);
        };
        if(!draft.isEmpty()||running)new AlertDialog.Builder(this).setTitle("新建对话？")
                .setMessage((running?"当前回复将停止。":"")+"当前输入会保留到侧栏的草稿入口。")
                .setNegativeButton("继续当前对话",null).setPositiveButton("保存并新建",(d,w)->create.run()).show();
        else create.run();
    }
    ModelCatalog.Selection configuredSelection(SiwcClient.Snapshot snapshot) {
        if(!snapshot.hasSession||snapshot.reauthRequired||snapshot.catalog==null)return null;
        SharedPreferences preference=getSharedPreferences("xiyan_model_preferences",MODE_PRIVATE);
        if(!preference.getString("accountScope","").equals(snapshot.catalog.getAccountScope()))return null;
        try{return snapshot.catalog.select(preference.getString("modelId",""),preference.getString("effort",null),null);}
        catch(ModelCatalog.SelectionException invalid){return null;}
    }
    String modelLabel(SiwcClient.Snapshot s) {
        ModelCatalog.Selection selected=configuredSelection(s);
        return selected==null?"选择模型":selected.getModel().getDisplayName();
    }
    String effortLabel(SiwcClient.Snapshot s) {
        ModelCatalog.Selection selected=configuredSelection(s);
        return selected==null?"":selected.getReasoningEffort()==null?"服务默认值":selected.getReasoningEffort();
    }
    boolean saveSelection(ModelCatalog catalog,String id,String effort) {
        SiwcClient.Snapshot current=account.snapshot();
        if(current.catalog!=catalog||!current.connected||current.busy||current.reauthRequired){
            toast("账号或目录已变化，请刷新后重新选择");return false;
        }
        try {
            ModelCatalog.Selection selection=catalog.select(id,effort,null);
            getSharedPreferences("xiyan_model_preferences",MODE_PRIVATE).edit()
                    .putString("accountScope",catalog.getAccountScope()).putString("modelId",selection.getModel().getId())
                    .putString("effort",selection.getReasoningEffort()).apply();
            toast("模型偏好已更新");refresh();return true;
        } catch(ModelCatalog.SelectionException invalid){toast("请按最新模型目录重新选择");return false;}
    }
    void openModelPicker() {
        if(picker!=null&&picker.isShowing())return;
        picker=new ModelPickerDialog(this);picker.show();
    }
    void sendPrompt() {
        SiwcClient.Snapshot snapshot=account.snapshot();
        if(snapshot.busy||"running".equals(snapshot.inferenceStatus)){emergencyStop();return;}
        ModelCatalog.Selection selection=configuredSelection(snapshot);
        if(selection==null){openModelPicker();return;}
        if(draft.trim().isEmpty()){toast("请先写下想发送的内容");return;}
        submittedPrompt=draft;submittedAccountScope=selection.getCatalog().getAccountScope();showingReply=true;preparingReply=true;
        account.sendText(selection.getModel().getId(),selection.getReasoningEffort(),submittedPrompt);
        draft="";draftStart=draftEnd=0;
        if(content instanceof ChatScreen)((ChatScreen)content).replaceDraft("");
        refresh();
    }
    void emergencyStop() {
        XiyanAccessibilityService service=XiyanAccessibilityService.getConnectedService();
        if(service!=null)service.emergencyStop();
        account.cancel();diagnostics.record("停止请求",service==null?"服务未连接，无可撤销的控制会话":"已请求撤销后续手势授权");
        refresh();toast("已停止后续动作；已派发的短手势可能仍在结束");
    }
    void explainSignIn() {
        new AlertDialog.Builder(this).setTitle("连接 ChatGPT 账号")
                .setMessage("将在系统浏览器中由你完成官方登录和授权，完成后返回 Xiyan。")
                .setNegativeButton("稍后",null).setPositiveButton("继续连接",(d,w)->account.signIn(uri->{
                    try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(uri.toASCIIString())));}
                    catch(ActivityNotFoundException unavailable){account.cancel();toast("请先安装浏览器");}
                })).show();
    }
    void signOut() {
        new AlertDialog.Builder(this).setTitle("退出本机账号？").setMessage("移除本机保存的账号连接，并请求撤销授权。")
                .setNegativeButton("保留连接",null).setPositiveButton("退出",(d,w)->account.signOut()).show();
    }
    void changeTheme(String value){getSharedPreferences("xiyan_appearance",MODE_PRIVATE).edit().putString("theme",value).apply();recreate();}
    void openDeveloper(){startActivity(new Intent(this,DeveloperActivity.class));}
    void openAccessibilitySettings(){try{startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}catch(ActivityNotFoundException e){toast("系统未提供此设置入口");}}
    void openUrl(String url){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(ActivityNotFoundException e){toast("请先安装浏览器");}}
    void toast(String value){Toast.makeText(this,value,Toast.LENGTH_SHORT).show();}
}
