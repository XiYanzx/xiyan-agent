// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

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

/** The editor is installed once; streamed replies update only the message views. */
final class ChatScreen implements ScreenContent {
    private final MainActivity host;
    private final LinearLayout root,welcome,answer,bottom,composer,tools;
    private final ScrollView messages;
    private final FrameLayout userMessage,send,mode,save,compactMenu;
    private final EditText editor;
    private final TextView prompt,reply,status,connection;
    private final Button model,edit,copy;
    private String lastReply="",lastPrompt="";
    private boolean compactInput;
    ChatScreen(MainActivity host) {
        this.host=host;Ui.Colors c=Ui.colors(host);root=Ui.column(host);
        LinearLayout flow=Ui.page(host);flow.setPadding(Ui.dp(host,20),Ui.dp(host,12),Ui.dp(host,20),Ui.dp(host,20));
        welcome=Ui.column(host);welcome.setGravity(Gravity.CENTER);welcome.setPadding(0,Ui.dp(host,42),0,Ui.dp(host,32));
        welcome.addView(new AgentIconView(host,AgentIconView.Icon.ORBIT,c.accent),new LinearLayout.LayoutParams(Ui.dp(host,56),Ui.dp(host,56)));
        Ui.gap(host,welcome,16);welcome.addView(Ui.heading(host,"今天想聊什么？",24));
        welcome.addView(Ui.text(host,"描述目标，或从一个小想法开始。",14,Ui.MUTED));Ui.gap(host,welcome,20);
        Button daily=Ui.action(host,"整理一项日常任务",false,false,()->host.fillDraft("我想在手机上完成：\n使用的应用：\n完成条件："));
        welcome.addView(daily);Ui.gap(host,welcome,8);
        welcome.addView(Ui.action(host,"准备 Galaxy 联合防御任务",false,false,()->host.fillTaskDraft("游戏操作","游戏：Galaxy 联合防御\n本次目标：\n停止条件：")));
        flow.addView(welcome,new LinearLayout.LayoutParams(-1,-2));
        userMessage=new FrameLayout(host);prompt=Ui.text(host,"",16,Ui.TEXT);
        prompt.setMaxWidth(Ui.dp(host,Math.min(620,Math.round(host.getResources().getConfiguration().screenWidthDp*.82f))));
        prompt.setPadding(Ui.dp(host,16),Ui.dp(host,12),Ui.dp(host,16),Ui.dp(host,12));
        prompt.setBackground(Ui.shape(host,c.accentSurface,20,Color.TRANSPARENT));
        userMessage.addView(prompt,new FrameLayout.LayoutParams(-2,-2,Gravity.END));flow.addView(userMessage,new LinearLayout.LayoutParams(-1,-2));
        answer=Ui.column(host);Ui.gap(host,answer,24);
        LinearLayout author=Ui.row(host);author.addView(new AgentIconView(host,AgentIconView.Icon.ORBIT,c.accent),new LinearLayout.LayoutParams(Ui.dp(host,26),Ui.dp(host,26)));
        TextView name=Ui.heading(host,"Xiyan",14);LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-2,-2);np.setMargins(Ui.dp(host,8),0,0,0);author.addView(name,np);answer.addView(author);
        Ui.gap(host,answer,10);reply=Ui.text(host,"",16,Ui.TEXT);reply.setTextIsSelectable(true);answer.addView(reply);
        status=Ui.text(host,"",12,Ui.MUTED);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);answer.addView(status);
        LinearLayout actions=Ui.row(host);
        copy=Ui.action(host,"复制回复",false,false,()->{
            ClipboardManager clipboard=(ClipboardManager)host.getSystemService(Context.CLIPBOARD_SERVICE);
            if(clipboard!=null){clipboard.setPrimaryClip(ClipData.newPlainText("Xiyan",host.account().snapshot().inferenceText));host.toast("回复已复制");}
        });
        edit=Ui.action(host,"编辑提问",false,false,host::editLastPrompt);actions.addView(copy);LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(-2,-2);ep.setMargins(Ui.dp(host,8),0,0,0);actions.addView(edit,ep);
        Ui.gap(host,answer,12);answer.addView(actions);flow.addView(answer,new LinearLayout.LayoutParams(-1,-2));
        messages=Ui.scroll(host,flow);root.addView(messages,new LinearLayout.LayoutParams(-1,0,1));
        bottom=Ui.column(host);bottom.setPadding(Ui.dp(host,12),Ui.dp(host,8),Ui.dp(host,12),Ui.dp(host,6));
        composer=Ui.column(host);composer.setPadding(Ui.dp(host,8),Ui.dp(host,6),Ui.dp(host,8),Ui.dp(host,6));
        composer.setBackground(Ui.shape(host,c.surface,25,c.border));composer.setElevation(Ui.dp(host,3));
        compactMenu=Ui.iconButton(host,AgentIconView.Icon.MENU,"打开导航",c.muted,host::openNavigation);
        compactMenu.setVisibility(View.GONE);composer.addView(compactMenu,new LinearLayout.LayoutParams(Ui.dp(host,48),Ui.dp(host,48)));
        editor=new EditText(host);editor.setTextSize(16);editor.setTextColor(c.text);editor.setHintTextColor(c.muted);editor.setBackgroundColor(Color.TRANSPARENT);
        editor.setHint("发消息，或描述一个任务…");editor.setContentDescription("消息输入，最多四千字");editor.setMinLines(1);editor.setMaxLines(5);
        editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editor.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);editor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4000)});
        editor.setPadding(Ui.dp(host,10),Ui.dp(host,10),Ui.dp(host,10),Ui.dp(host,8));editor.setText(host.draft());
        editor.setMaxHeight(Ui.dp(host,140));
        editor.setSelection(Math.min(host.draftStart(),editor.length()),Math.min(host.draftEnd(),editor.length()));
        editor.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){ }
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){host.draftEdited(s.toString());updateSend(host.account().snapshot());}
            @Override public void afterTextChanged(Editable value){ }
        });composer.addView(editor,new LinearLayout.LayoutParams(-1,-2));
        tools=Ui.row(host);
        mode=Ui.iconButton(host,AgentIconView.Icon.PHONE,"切换任务模式",c.secondary,host::chooseMode);
        tools.addView(mode,new LinearLayout.LayoutParams(Ui.dp(host,48),Ui.dp(host,48)));
        save=Ui.iconButton(host,AgentIconView.Icon.SAVE,"保存本机草稿",c.muted,host::saveDraft);
        tools.addView(save,new LinearLayout.LayoutParams(Ui.dp(host,48),Ui.dp(host,48)));
        model=Ui.action(host,"选择模型",false,false,host::openModelPicker);model.setTextSize(12);model.setMinWidth(0);model.setSingleLine(true);model.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(0,-2,1);mp.setMargins(Ui.dp(host,4),0,Ui.dp(host,8),0);tools.addView(model,mp);
        send=Ui.iconButton(host,AgentIconView.Icon.SEND,"发送消息",c.accent,host::sendPrompt);tools.addView(send,new LinearLayout.LayoutParams(Ui.dp(host,48),Ui.dp(host,48)));
        composer.addView(tools,new LinearLayout.LayoutParams(-1,-2));bottom.addView(composer);
        connection=Ui.text(host,"",11,Ui.MUTED);connection.setGravity(Gravity.CENTER);bottom.addView(connection);root.addView(bottom,new LinearLayout.LayoutParams(-1,-2));
        root.addOnLayoutChangeListener((v,left,top,right,bottomEdge,oldLeft,oldTop,oldRight,oldBottom)->{
            boolean compact=host.chatViewportHeight()>0&&host.chatViewportHeight()<Ui.dp(host,220);
            if(compact!=compactInput)compactInput(compact);
            int maximum=compact?Ui.dp(host,48):Math.max(Ui.dp(host,48),Math.min(Ui.dp(host,140),(bottomEdge-top)/3));
            if(editor.getMaxHeight()!=maximum)editor.setMaxHeight(maximum);
        });
    }
    @Override public View view(){return root;}
    @Override public void leave(){host.draftSelection(editor.getSelectionStart(),editor.getSelectionEnd());}
    private void compactInput(boolean compact) {
        compactInput=compact;host.compactChatChrome(compact);composer.setOrientation(compact?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);
        compactMenu.setVisibility(compact?View.VISIBLE:View.GONE);mode.setVisibility(compact?View.GONE:View.VISIBLE);
        save.setVisibility(compact?View.GONE:View.VISIBLE);model.setVisibility(compact?View.GONE:View.VISIBLE);connection.setVisibility(compact?View.GONE:View.VISIBLE);
        composer.setGravity(compact?Gravity.CENTER_VERTICAL:Gravity.TOP);
        editor.setLayoutParams(new LinearLayout.LayoutParams(compact?0:-1,-2,compact?1:0));
        tools.setLayoutParams(new LinearLayout.LayoutParams(compact?-2:-1,compact?Ui.dp(host,48):-2));
        editor.setMaxLines(compact?1:5);editor.setHorizontallyScrolling(compact);
        editor.setPadding(Ui.dp(host,compact?8:10),Ui.dp(host,compact?4:10),Ui.dp(host,compact?8:10),Ui.dp(host,compact?4:8));
        composer.setPadding(Ui.dp(host,compact?4:8),Ui.dp(host,compact?2:6),Ui.dp(host,compact?4:8),Ui.dp(host,compact?2:6));
        bottom.setPadding(Ui.dp(host,compact?8:12),Ui.dp(host,compact?2:8),Ui.dp(host,compact?8:12),Ui.dp(host,compact?2:6));
    }
    void replaceDraft(String value){editor.setText(value);editor.setSelection(editor.length());host.draftSelection(editor.length(),editor.length());}
    @Override public void update(SiwcClient.Snapshot s) {
        boolean visible=host.showingReply();welcome.setVisibility(visible?View.GONE:View.VISIBLE);answer.setVisibility(visible?View.VISIBLE:View.GONE);
        String currentPrompt=host.submittedPrompt();userMessage.setVisibility(visible&&!currentPrompt.isEmpty()?View.VISIBLE:View.GONE);
        if(!lastPrompt.equals(currentPrompt)){prompt.setText(currentPrompt);lastPrompt=currentPrompt;}
        String currentReply=host.preparingReply()?"":s.inferenceText;
        if(!lastReply.equals(currentReply)) {
            boolean atEnd=messages.getChildAt(0).getHeight()-messages.getScrollY()-messages.getHeight()<Ui.dp(host,80);
            reply.setText(currentReply);lastReply=currentReply;
            if(atEnd)messages.post(()->messages.fullScroll(View.FOCUS_DOWN));
        }
        reply.setVisibility(currentReply.isEmpty()?View.GONE:View.VISIBLE);
        String state=host.preparingReply()?"正在准备这次请求…":"running".equals(s.inferenceStatus)?"正在生成回复…":"completed".equals(s.inferenceStatus)?"回复已完成":
                "cancelled".equals(s.inferenceStatus)?"本次回复已停止":"failed".equals(s.inferenceStatus)?Ui.friendlyFailure(s):"等待回复";
        status.setText(state);status.setTextColor("failed".equals(s.inferenceStatus)?Ui.colors(host).alert:Ui.colors(host).muted);
        copy.setVisibility(currentReply.isEmpty()?View.GONE:View.VISIBLE);edit.setVisibility(currentPrompt.isEmpty()?View.GONE:View.VISIBLE);
        String effort=host.effortLabel(s);model.setText(effort.isEmpty()?host.modelLabel(s):host.getString(R.string.chat_model_with_effort,host.modelLabel(s),effort));
        model.setContentDescription("选择模型与思考程度，当前 "+host.modelLabel(s)+(effort.isEmpty()?"":"，"+effort));
        mode.setContentDescription("任务模式："+host.selectedMode()+"，点击切换");
        ((AgentIconView)mode.getChildAt(0)).appearance("游戏操作".equals(host.selectedMode())?AgentIconView.Icon.GAME:AgentIconView.Icon.PHONE,Ui.colors(host).secondary);
        String readiness=host.configuredSelection(s)!=null?"文字对话已就绪":s.hasSession?"选择模型后即可对话":"连接账号后开始对话";
        connection.setText(host.getString(R.string.chat_execution_status,readiness));updateSend(s);
    }
    private void updateSend(SiwcClient.Snapshot s) {
        boolean running=s.busy||"running".equals(s.inferenceStatus);Ui.Colors c=Ui.colors(host);
        ((AgentIconView)send.getChildAt(0)).appearance(running?AgentIconView.Icon.STOP:AgentIconView.Icon.SEND,running?c.alert:Ui.isLight(host)?Color.WHITE:c.background);
        send.setBackground(Ui.shape(host,running?c.alertSurface:c.accent,24,Color.TRANSPARENT));send.setContentDescription(running?"停止回复和后续手机动作":"发送消息");
        Ui.enabled(send,running||(!s.busy&&host.configuredSelection(s)!=null&&!editor.getText().toString().trim().isEmpty()));
    }
}
