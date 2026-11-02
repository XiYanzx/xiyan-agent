// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import io.xiyan.agent.account.SiwcClient;
import io.xiyan.agent.core.model.ModelCatalog;
import java.util.Locale;

/** The editor remains installed while streamed replies and compact chrome update. */
final class ChatScreen implements ScreenContent {
    private final MainActivity host;
    private final LinearLayout root,flow,welcome,suggestions,answer,bottom,composer,tools;
    private final ScrollView messages;
    private final FrameLayout userMessage,send,compactMenu;
    private final EditText editor;
    private final TextView welcomeEyebrow,welcomeTitle,welcomeSubtitle,prompt,reply,status,connection;
    private final Button mode,model,edit,copy,retry,changeEffort;
    private String lastReply="",lastPrompt="";
    private String displayedModelName,displayedEffort,displayedMode,contextMode,contextModel;
    private boolean compactInput,selectionReady,draftHasContent;
    private int sendAppearance=-1,statusColor,scrollAnchorY,contextState=-1,welcomeMode=-1;
    private final Runnable scrollToLatest=this::scrollLatestReply;
    private final Runnable welcomeLayout=this::layoutWelcome;

    ChatScreen(MainActivity host) {
        this.host=host;Ui.Colors c=Ui.colors(host);root=Ui.column(host);
        int edge=host.getResources().getConfiguration().screenWidthDp<360?16:20;
        flow=Ui.page(host);flow.setPadding(Ui.dp(host,edge),Ui.dp(host,8),Ui.dp(host,edge),Ui.dp(host,24));
        welcome=Ui.column(host);welcomeEyebrow=Ui.text(host,"想法 · 目标 · 下一步",12,Ui.MUTED);welcome.addView(welcomeEyebrow);
        welcomeTitle=Ui.heading(host,"从一句话开始",28);LinearLayout.LayoutParams titleSpace=new LinearLayout.LayoutParams(-1,-2);titleSpace.topMargin=Ui.dp(host,8);welcome.addView(welcomeTitle,titleSpace);
        welcomeSubtitle=Ui.text(host,"聊一件事，或准备下一项任务。",15,Ui.MUTED);LinearLayout.LayoutParams subtitleSpace=new LinearLayout.LayoutParams(-1,-2);subtitleSpace.topMargin=Ui.dp(host,8);welcome.addView(welcomeSubtitle,subtitleSpace);
        suggestions=Ui.row(host);
        Button daily=Ui.chip(host,"日常任务",false,()->host.fillTaskDraft("通用操作","我想在手机上完成：\n使用的应用：\n完成条件："));
        Button game=Ui.chip(host,"游戏任务",false,()->host.fillTaskDraft("游戏操作","游戏：Galaxy 联合防御\n本次目标：\n停止条件："));
        game.setContentDescription("准备 Galaxy 联合防御任务草稿");
        suggestions.addView(daily,new LinearLayout.LayoutParams(-2,-2));LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-2,-2);gp.setMargins(Ui.dp(host,8),0,0,0);suggestions.addView(game,gp);
        LinearLayout.LayoutParams suggestionSpace=new LinearLayout.LayoutParams(-1,-2);suggestionSpace.topMargin=Ui.dp(host,16);welcome.addView(suggestions,suggestionSpace);flow.addView(welcome,new LinearLayout.LayoutParams(-1,-2));

        userMessage=new FrameLayout(host);prompt=Ui.text(host,"",16,Ui.TEXT);
        prompt.setMaxWidth(Ui.dp(host,Math.min(640,Math.round(host.getResources().getConfiguration().screenWidthDp*.86f))));
        prompt.setPadding(Ui.dp(host,16),Ui.dp(host,12),Ui.dp(host,16),Ui.dp(host,12));prompt.setBackground(Ui.shape(host,c.accentSurface,20,Color.TRANSPARENT));
        userMessage.addView(prompt,new FrameLayout.LayoutParams(-2,-2,Gravity.END));flow.addView(userMessage,new LinearLayout.LayoutParams(-1,-2));
        answer=Ui.column(host);Ui.gap(host,answer,24);LinearLayout author=Ui.row(host);
        AgentIconView brand=new AgentIconView(host,AgentIconView.Icon.ORBIT,c.accent);brand.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        author.addView(brand,new LinearLayout.LayoutParams(Ui.dp(host,24),Ui.dp(host,24)));
        TextView name=Ui.heading(host,"Xiyan",14);LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-2,-2);np.setMargins(Ui.dp(host,8),0,0,0);author.addView(name,np);answer.addView(author);
        Ui.gap(host,answer,12);reply=Ui.text(host,"",16,Ui.TEXT);reply.setTextIsSelectable(true);answer.addView(reply);
        Ui.gap(host,answer,8);status=Ui.text(host,"",12,Ui.MUTED);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);answer.addView(status);
        changeEffort=Ui.textAction(host,"更改思考程度",host::openModelPicker);answer.addView(changeEffort,new LinearLayout.LayoutParams(-2,-2));
        LinearLayout actions=Ui.row(host);
        copy=Ui.textAction(host,"复制",()->{
            ClipboardManager clipboard=(ClipboardManager)host.getSystemService(Context.CLIPBOARD_SERVICE);
            if(clipboard!=null){clipboard.setPrimaryClip(ClipData.newPlainText("Xiyan",host.account().snapshot().inferenceText));host.toast("回复已复制");}
        });
        edit=Ui.textAction(host,"编辑提问",host::editLastPrompt);actions.addView(copy);actions.addView(edit);answer.addView(actions);
        retry=Ui.outlineAction(host,"重试原提问",this::retryOriginal);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-2,-2);rp.setMargins(0,Ui.dp(host,8),0,0);answer.addView(retry,rp);
        flow.addView(answer,new LinearLayout.LayoutParams(-1,-2));messages=Ui.scroll(host,flow);root.addView(messages,new LinearLayout.LayoutParams(-1,0,1));

        bottom=Ui.column(host);bottom.setPadding(Ui.dp(host,12),Ui.dp(host,8),Ui.dp(host,12),Ui.dp(host,8));
        composer=Ui.column(host);composer.setPadding(Ui.dp(host,8),Ui.dp(host,6),Ui.dp(host,8),Ui.dp(host,6));
        composer.setBackground(Ui.shape(host,c.surface,24,c.border));composer.setElevation(Ui.dp(host,1));
        compactMenu=Ui.iconButton(host,AgentIconView.Icon.MENU,"打开导航",c.muted,host::openNavigation);compactMenu.setVisibility(View.GONE);
        composer.addView(compactMenu,new LinearLayout.LayoutParams(Ui.dp(host,48),Ui.dp(host,48)));
        editor=new EditText(host);editor.setTextSize(16);editor.setTextColor(c.text);editor.setHintTextColor(c.muted);editor.setBackgroundColor(Color.TRANSPARENT);
        editor.setHint("说说你的想法…");editor.setContentDescription("消息输入，最多四千字");editor.setMinLines(1);editor.setMaxLines(5);
        editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editor.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);editor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4000)});
        editor.setPadding(Ui.dp(host,10),Ui.dp(host,10),Ui.dp(host,10),Ui.dp(host,8));editor.setText(host.draft());draftHasContent=!host.draft().trim().isEmpty();editor.setMaxHeight(Ui.dp(host,140));
        editor.setSelection(Math.min(host.draftStart(),editor.length()),Math.min(host.draftEnd(),editor.length()));
        editor.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){ }
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){
                String value=s.toString();host.draftEdited(value);boolean wasDraft=draftHasContent;draftHasContent=!value.trim().isEmpty();updateSend(host.account().snapshot());
                if(wasDraft!=draftHasContent){messages.removeCallbacks(welcomeLayout);messages.post(welcomeLayout);}
            }
            @Override public void afterTextChanged(Editable value){ }
        });composer.addView(editor,new LinearLayout.LayoutParams(-1,-2));
        tools=Ui.row(host);mode=Ui.chip(host,"手机",false,this::showInputTools);mode.setTextSize(12);mode.setMinWidth(0);Ui.downIndicator(host,mode,c.muted);
        tools.addView(mode,new LinearLayout.LayoutParams(-2,Ui.dp(host,48)));
        model=Ui.textAction(host,"选择模型",host::openModelPicker);model.setTextSize(12);model.setMinWidth(0);model.setSingleLine(true);model.setEllipsize(TextUtils.TruncateAt.END);
        model.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);model.setPadding(Ui.dp(host,10),0,Ui.dp(host,4),0);
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(0,Ui.dp(host,48),1);mp.setMargins(Ui.dp(host,4),0,Ui.dp(host,4),0);tools.addView(model,mp);
        send=Ui.iconButton(host,AgentIconView.Icon.SEND,"发送消息",c.onAccent,host::sendPrompt);tools.addView(send,new LinearLayout.LayoutParams(Ui.dp(host,48),Ui.dp(host,48)));
        composer.addView(tools,new LinearLayout.LayoutParams(-1,-2));bottom.addView(composer);
        connection=Ui.text(host,"",12,Ui.MUTED);connection.setGravity(Gravity.CENTER);connection.setSingleLine(true);connection.setEllipsize(TextUtils.TruncateAt.END);bottom.addView(connection);
        root.addView(bottom,new LinearLayout.LayoutParams(-1,-2));
        root.addOnLayoutChangeListener((v,left,top,right,bottomEdge,oldLeft,oldTop,oldRight,oldBottom)->{
            boolean compact=host.chatViewportHeight()>0&&host.chatViewportHeight()<Ui.dp(host,220);
            if(compact!=compactInput)compactInput(compact);
            layoutWelcome();
            updateContext(host.account().snapshot());
            int maximum=compact?Ui.dp(host,48):Math.max(Ui.dp(host,48),Math.min(Ui.dp(host,140),(bottomEdge-top)/3));
            if(editor.getMaxHeight()!=maximum)editor.setMaxHeight(maximum);
        });
        messages.addOnLayoutChangeListener((v,left,top,right,bottomEdge,oldLeft,oldTop,oldRight,oldBottom)->layoutWelcome());
    }
    @Override public View view(){return root;}
    @Override public void leave(){host.draftSelection(editor.getSelectionStart(),editor.getSelectionEnd());messages.removeCallbacks(scrollToLatest);messages.removeCallbacks(welcomeLayout);}
    private void layoutWelcome(){
        if(compactInput||host.showingReply()){welcome.setVisibility(View.GONE);return;}
        int nextMode=draftHasContent?1:0;
        if(welcomeMode!=nextMode){
            welcomeMode=nextMode;welcomeEyebrow.setVisibility(draftHasContent?View.GONE:View.VISIBLE);
            welcomeSubtitle.setVisibility(draftHasContent?View.GONE:View.VISIBLE);suggestions.setVisibility(draftHasContent?View.GONE:View.VISIBLE);
            textIfChanged(welcomeTitle,draftHasContent?"继续写下你的想法":"从一句话开始");welcomeTitle.setTextSize(draftHasContent?20:28);
            float line=(draftHasContent?20:28)*1.3f;welcomeTitle.setLineHeight(Math.round(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,line,host.getResources().getDisplayMetrics())));
            LinearLayout.LayoutParams titleSpace=(LinearLayout.LayoutParams)welcomeTitle.getLayoutParams();titleSpace.topMargin=draftHasContent?0:Ui.dp(host,8);welcomeTitle.setLayoutParams(titleSpace);
        }
        int width=flow.getWidth()-flow.getPaddingLeft()-flow.getPaddingRight(),viewport=messages.getHeight();
        if(width<=0||viewport<=0){welcome.setVisibility(View.GONE);return;}
        welcome.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int natural=welcome.getMeasuredHeight(),available=viewport-flow.getPaddingTop()-flow.getPaddingBottom();
        if(available<natural+Ui.dp(host,48)){welcome.setVisibility(View.GONE);return;}
        int free=available-natural,top=draftHasContent?free:Math.round(free*.55f);LinearLayout.LayoutParams placement=(LinearLayout.LayoutParams)welcome.getLayoutParams();
        if(placement.topMargin!=top){placement.topMargin=top;welcome.setLayoutParams(placement);}welcome.setVisibility(View.VISIBLE);
    }
    private void scrollLatestReply(){
        if(compactInput||messages.getVisibility()!=View.VISIBLE||messages.getChildCount()==0||messages.getScrollY()<scrollAnchorY)return;
        View body=messages.getChildAt(0);messages.scrollTo(0,Math.max(0,body.getBottom()-messages.getHeight()+messages.getPaddingBottom()));
    }
    private static void textIfChanged(TextView view,String value){if(!TextUtils.equals(view.getText(),value))view.setText(value);}
    private static void enabledIfChanged(View view,boolean enabled){if(view.isEnabled()!=enabled)Ui.enabled(view,enabled);}
    private void showInputTools() {
        new AlertDialog.Builder(host).setTitle("输入与任务")
                .setItems(new String[]{"通用手机任务","Galaxy 联合防御","保存本机草稿"},(dialog,which)->{
                    if(which==0)host.selectMode("通用操作");else if(which==1)host.selectMode("游戏操作");else host.saveDraft();
                }).setNegativeButton("关闭",null).show();
    }
    private void retryOriginal() {
        SiwcClient.Snapshot s=host.account().snapshot();String original=host.submittedPrompt();
        if(s.busy||original.isEmpty()||(!"failed".equals(s.inferenceStatus)&&!"cancelled".equals(s.inferenceStatus)))return;
        Runnable resend=()->{
            SiwcClient.Snapshot current=host.account().snapshot();
            if(current.catalog!=s.catalog||current.busy||current.reauthRequired||!current.hasSession||!original.equals(host.submittedPrompt())||!s.inferenceStatus.equals(current.inferenceStatus)){
                host.toast("当前对话或账号已变化，请重新检查提问");return;
            }
            replaceDraft(original);host.sendPrompt();
        };
        if(!host.draft().trim().isEmpty()&&!host.draft().equals(original))new AlertDialog.Builder(host)
                .setTitle("重试原提问？").setMessage("当前输入会先保存到本机草稿，再重新发送原提问。")
                .setNegativeButton("保留当前输入",null).setPositiveButton("保存并重试",(dialog,which)->{host.saveDraft();resend.run();}).show();
        else resend.run();
    }
    private void compactInput(boolean compact) {
        compactInput=compact;host.compactChatChrome(compact);messages.setVisibility(compact?View.GONE:View.VISIBLE);root.setGravity(compact?Gravity.BOTTOM:Gravity.TOP);
        composer.setOrientation(compact?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);compactMenu.setVisibility(compact?View.VISIBLE:View.GONE);
        mode.setVisibility(compact?View.GONE:View.VISIBLE);model.setVisibility(compact?View.GONE:View.VISIBLE);composer.setGravity(compact?Gravity.CENTER_VERTICAL:Gravity.TOP);
        editor.setLayoutParams(new LinearLayout.LayoutParams(compact?0:-1,-2,compact?1:0));tools.setLayoutParams(new LinearLayout.LayoutParams(compact?-2:-1,compact?Ui.dp(host,48):-2));
        editor.setMaxLines(compact?1:5);editor.setHorizontallyScrolling(compact);
        editor.setPadding(Ui.dp(host,compact?8:10),Ui.dp(host,compact?4:10),Ui.dp(host,compact?8:10),Ui.dp(host,compact?4:8));
        composer.setPadding(Ui.dp(host,compact?4:8),Ui.dp(host,compact?2:6),Ui.dp(host,compact?4:8),Ui.dp(host,compact?2:6));
        bottom.setPadding(Ui.dp(host,compact?8:12),Ui.dp(host,compact?2:8),Ui.dp(host,compact?8:12),Ui.dp(host,compact?2:8));
    }
    void replaceDraft(String value){editor.setText(value);editor.setSelection(editor.length());host.draftSelection(editor.length(),editor.length());}
    @Override public void update(SiwcClient.Snapshot s) {
        boolean visible=host.showingReply();answer.setVisibility(visible?View.VISIBLE:View.GONE);
        String currentPrompt=host.submittedPrompt();userMessage.setVisibility(visible&&!currentPrompt.isEmpty()?View.VISIBLE:View.GONE);
        if(!lastPrompt.equals(currentPrompt)){prompt.setText(currentPrompt);lastPrompt=currentPrompt;}
        String currentReply=host.preparingReply()?"":s.inferenceText;
        if(!lastReply.equals(currentReply)) {
            boolean atEnd=messages.getChildAt(0).getHeight()-messages.getScrollY()-messages.getHeight()<=Ui.dp(host,8);
            reply.setText(currentReply);lastReply=currentReply;
            if(atEnd&&!compactInput){scrollAnchorY=messages.getScrollY();messages.removeCallbacks(scrollToLatest);messages.post(scrollToLatest);}
        }
        reply.setVisibility(currentReply.isEmpty()?View.GONE:View.VISIBLE);
        boolean failed="failed".equals(s.inferenceStatus),stopped="cancelled".equals(s.inferenceStatus);
        String state=host.preparingReply()?"正在准备这次请求…":"running".equals(s.inferenceStatus)?"正在生成回复…":"completed".equals(s.inferenceStatus)?"回复已完成":stopped?"回复已停止，原提问已保留":failed?Ui.friendlyFailure(s):"等待回复";
        textIfChanged(status,state);int nextStatusColor=failed?Ui.colors(host).alert:Ui.colors(host).muted;
        if(statusColor!=nextStatusColor){status.setTextColor(nextStatusColor);statusColor=nextStatusColor;}
        boolean effortRejected=failed&&s.lastFailure!=null&&s.lastFailure.getHttpStatus()==400&&"reasoning.effort".equals(s.lastFailure.getParam())&&"invalid_value".equals(s.lastFailure.getCode());
        changeEffort.setVisibility(effortRejected?View.VISIBLE:View.GONE);enabledIfChanged(changeEffort,!s.busy);
        copy.setVisibility(currentReply.isEmpty()?View.GONE:View.VISIBLE);edit.setVisibility(currentPrompt.isEmpty()?View.GONE:View.VISIBLE);enabledIfChanged(edit,!s.busy);
        retry.setVisibility((failed||stopped)&&!host.preparingReply()&&!currentPrompt.isEmpty()?View.VISIBLE:View.GONE);
        textIfChanged(retry,stopped?"继续提问":"重试原提问");enabledIfChanged(retry,!s.busy);
        ModelCatalog.Selection configured=host.configuredSelection(s);selectionReady=configured!=null;
        String name=configured==null?"选择模型":configured.getModel().getDisplayName();
        String effort=configured==null?"":configured.getReasoningEffort()==null?"服务默认值":configured.getReasoningEffort();
        if(!TextUtils.equals(displayedModelName,name)||!TextUtils.equals(displayedEffort,effort)){
            displayedModelName=name;displayedEffort=effort;String complete=effort.isEmpty()?name:host.getString(R.string.chat_model_with_effort,name,effort);
            String label=configured==null||configured.getReasoningEffort()==null?name:complete;textIfChanged(model,label);model.setContentDescription("选择模型与思考程度，当前 "+complete);
        }
        enabledIfChanged(model,!s.busy);String nextMode=host.selectedMode();
        if(!TextUtils.equals(displayedMode,nextMode)){
            displayedMode=nextMode;boolean gameMode="游戏操作".equals(nextMode);textIfChanged(mode,gameMode?"游戏":"手机");
            int tint=gameMode?Ui.colors(host).secondary:Ui.colors(host).muted;mode.setTextColor(tint);Ui.downIndicator(host,mode,tint);mode.setContentDescription("任务模式："+nextMode+"，打开模式与草稿操作");
        }
        layoutWelcome();updateContext(s);updateSend(s,selectionReady);
    }
    private void updateContext(SiwcClient.Snapshot s) {
        int nextState=compactInput?(s.busy?3:4):(selectionReady?0:s.hasSession?1:2);
        String nextMode=compactInput?(displayedMode==null?host.selectedMode():displayedMode):null;
        String nextModel=compactInput&&!s.busy?displayedModelName:null;
        connection.setVisibility(!compactInput||host.chatViewportHeight()>=Ui.dp(host,90)?View.VISIBLE:View.GONE);
        if(contextState==nextState&&TextUtils.equals(contextMode,nextMode)&&TextUtils.equals(contextModel,nextModel))return;
        contextState=nextState;contextMode=nextMode;contextModel=nextModel;
        if(compactInput) {
            String context=String.format(Locale.SIMPLIFIED_CHINESE,"%s · %s",nextMode,s.busy?"正在处理，可随时停止":nextModel==null?"选择模型":nextModel);
            textIfChanged(connection,context);
        } else {
            String readiness=selectionReady?"文字对话已就绪":s.hasSession?"选择模型后即可对话":"连接账号后开始对话";
            textIfChanged(connection,host.getString(R.string.chat_execution_status,readiness));
        }
    }
    private void updateSend(SiwcClient.Snapshot s) {
        updateSend(s,host.configuredSelection(s)!=null);
    }
    private void updateSend(SiwcClient.Snapshot s,boolean configured) {
        boolean running=s.busy||"running".equals(s.inferenceStatus);Ui.Colors c=Ui.colors(host);
        int appearance=running?1:0;
        if(sendAppearance!=appearance){
            sendAppearance=appearance;((AgentIconView)send.getChildAt(0)).appearance(running?AgentIconView.Icon.STOP:AgentIconView.Icon.SEND,running?c.alert:c.onAccent);
            Ui.iconSurface(host,send,running?c.alertSurface:c.accent,running?c.alert:c.onAccent,24);send.setContentDescription(running?"停止回复和后续手机动作":"发送消息");
        }
        enabledIfChanged(send,running||(!s.busy&&configured&&draftHasContent));
    }
}
