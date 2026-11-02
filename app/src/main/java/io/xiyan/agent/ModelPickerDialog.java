// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

import android.app.Dialog;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import io.xiyan.agent.account.SiwcClient;
import io.xiyan.agent.core.model.ModelCatalog;
import java.util.Locale;
import java.util.Objects;

final class ModelPickerDialog {
    private final MainActivity host;
    private final Dialog dialog;
    private final Panel panel;
    ModelPickerDialog(MainActivity host){this.host=host;dialog=new Dialog(host);panel=new Panel(host,true,dialog::dismiss);}
    void show(){Ui.showBottomDialog(host,dialog,panel.root);panel.shown();}
    boolean isShowing(){return dialog.isShowing();}
    void dismiss(){dialog.dismiss();}
    void update(SiwcClient.Snapshot snapshot){panel.update(snapshot);}

    static final class ModelScreen implements ScreenContent {
        private final Panel panel;
        ModelScreen(MainActivity host){
            panel=new Panel(host,false,()->{ });
            panel.root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
                @Override public void onViewAttachedToWindow(View view){panel.shown();}
                @Override public void onViewDetachedFromWindow(View view){ }
            });
        }
        @Override public View view(){return panel.root;}
        @Override public void update(SiwcClient.Snapshot s){panel.update(s);}
    }
    private static final class Panel {
        private final MainActivity host;
        private final Runnable saved;
        private final ScrollView root;
        private final LinearLayout body,list,efforts;
        private final EditText search;
        private final TextView status,effortHint;
        private final Button refresh,apply;
        private ModelCatalog catalog;
        private SiwcClient.Snapshot snapshot;
        private String candidateId,candidateEffort;
        private boolean opened,initialFetchAttempted;
        Panel(MainActivity host,boolean sheet,Runnable saved) {
            this.host=host;this.saved=saved;Ui.Colors c=Ui.colors(host);body=Ui.page(host);
            root=new ScrollView(host){
                @Override protected void onMeasure(int widthSpec,int heightSpec){
                    if(sheet){
                        int maximum=Ui.dp(host,Math.min(640,Math.round(host.getResources().getConfiguration().screenHeightDp*.8f)));
                        int offered=View.MeasureSpec.getSize(heightSpec);
                        if(View.MeasureSpec.getMode(heightSpec)!=View.MeasureSpec.UNSPECIFIED&&offered>0)maximum=Math.min(maximum,offered);
                        super.onMeasure(widthSpec,View.MeasureSpec.makeMeasureSpec(maximum,View.MeasureSpec.AT_MOST));
                    }else super.onMeasure(widthSpec,heightSpec);
                }
            };
            root.setFillViewport(false);root.setClipToPadding(false);root.addView(body,new android.widget.FrameLayout.LayoutParams(-1,-2));
            if(sheet)root.setBackground(Ui.shape(host,c.surface,24,Color.TRANSPARENT));
            LinearLayout title=Ui.row(host);title.addView(Ui.heading(host,sheet?"模型与思考":"选择适合这次任务的模型",sheet?19:20),new LinearLayout.LayoutParams(0,-2,1));
            if(sheet)title.addView(Ui.iconButton(host,AgentIconView.Icon.CLOSE,"关闭模型选择",c.muted,saved),new LinearLayout.LayoutParams(Ui.dp(host,48),Ui.dp(host,48)));
            body.addView(title);status=Ui.text(host,"",12,Ui.MUTED);body.addView(status);
            search=new EditText(host);search.setTextSize(15);search.setTextColor(c.text);search.setHintTextColor(c.muted);search.setSingleLine(true);
            search.setHint("搜索模型");search.setContentDescription("搜索账户模型");search.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            search.setPadding(Ui.dp(host,14),Ui.dp(host,12),Ui.dp(host,14),Ui.dp(host,12));search.setBackground(Ui.shape(host,c.raised,14,Color.TRANSPARENT));
            LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.setMargins(0,Ui.dp(host,12),0,Ui.dp(host,8));body.addView(search,sp);
            list=Ui.column(host);body.addView(list,new LinearLayout.LayoutParams(-1,-2));
            Ui.divider(host,body);body.addView(Ui.heading(host,"思考程度",15));
            HorizontalScrollView horizontal=new HorizontalScrollView(host);horizontal.setHorizontalScrollBarEnabled(false);efforts=Ui.row(host);horizontal.addView(efforts);body.addView(horizontal);
            effortHint=Ui.text(host,"先选择模型",12,Ui.MUTED);body.addView(effortHint);
            LinearLayout actions=Ui.row(host);refresh=Ui.action(host,"刷新列表",false,false,()->{initialFetchAttempted=true;host.account().refreshModels();});
            apply=Ui.action(host,"使用此模型",true,false,()->{if(catalog!=null&&candidateId!=null&&host.saveSelection(catalog,candidateId,candidateEffort))saved.run();});
            actions.addView(refresh,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(0,-2,1);ap.setMargins(Ui.dp(host,10),0,0,0);actions.addView(apply,ap);body.addView(actions);
            search.addTextChangedListener(new TextWatcher(){
                @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){ }
                @Override public void onTextChanged(CharSequence s,int start,int before,int count){renderModels();}
                @Override public void afterTextChanged(Editable value){ }
            });update(host.account().snapshot());
        }
        void shown(){opened=true;update(host.account().snapshot());}
        void update(SiwcClient.Snapshot current) {
            snapshot=current;boolean changed=current.catalog!=catalog;
            if(changed) {
                if(catalog!=null&&current.catalog!=null&&!Objects.equals(catalog.getAccountScope(),current.catalog.getAccountScope())){candidateId=null;candidateEffort=null;}
                catalog=current.catalog;
                ModelCatalog.Selection selected=host.configuredSelection(current);
                if(candidateId==null&&selected!=null){candidateId=selected.getModel().getId();candidateEffort=selected.getReasoningEffort();}
                if(catalog==null||catalog.findModel(candidateId)==null){candidateId=null;candidateEffort=null;}
                else if(candidateEffort!=null&&!catalog.findModel(candidateId).getReasoningEfforts().contains(candidateEffort))candidateEffort=null;
                renderModels();renderEfforts();
            } else if(list.getChildCount()==0){renderModels();renderEfforts();}
            status.setText(current.hasSession?(current.catalog==null?Ui.accountMessage(current):"更新于 "+Ui.time(current.catalog.getFetchedAtEpochMillis())):"连接账号后获取你的模型列表");
            Ui.enabled(refresh,current.hasSession&&!current.reauthRequired&&!current.busy);
            updateApply();
            if(current.catalog!=null||(current.busy&&current.operationStage.contains("目录")))initialFetchAttempted=true;
            if(opened&&!initialFetchAttempted&&current.catalog==null&&current.hasSession&&!current.reauthRequired&&!current.busy){
                initialFetchAttempted=true;host.account().refreshModels();
            }
        }
        private void updateApply() {
            ModelCatalog.Model model=catalog==null?null:catalog.findModel(candidateId);
            Ui.enabled(apply,snapshot!=null&&snapshot.connected&&!snapshot.reauthRequired&&!snapshot.busy&&model!=null&&model.getServerDeclaredAvailability()!=ModelCatalog.Availability.UNAVAILABLE);
        }
        private void renderModels() {
            list.removeAllViews();
            if(catalog==null||catalog.getModels().isEmpty()) {
                list.addView(Ui.text(host,catalog==null?"模型列表待获取":"本次列表中没有模型",16,Ui.TEXT));
                if(snapshot!=null&&!snapshot.hasSession)Ui.button(host,list,"连接账号",false,()->{saved.run();host.navigate(MainActivity.Screen.ACCOUNT);});
                return;
            }
            String query=search.getText().toString().trim().toLowerCase(Locale.ROOT);int found=0;
            for(ModelCatalog.Model model:catalog.getModels()) {
                if(!query.isEmpty()&&!(model.getDisplayName()+" "+model.getId()).toLowerCase(Locale.ROOT).contains(query))continue;
                found++;boolean selected=model.getId().equals(candidateId);Ui.Colors c=Ui.colors(host);
                LinearLayout row=Ui.row(host);row.setMinimumHeight(Ui.dp(host,64));row.setPadding(Ui.dp(host,12),Ui.dp(host,8),Ui.dp(host,10),Ui.dp(host,8));
                row.setBackground(Ui.shape(host,selected?c.accentSurface:Color.TRANSPARENT,14,Color.TRANSPARENT));
                LinearLayout labels=Ui.column(host);labels.addView(Ui.text(host,model.getDisplayName(),15,Ui.TEXT));
                labels.addView(Ui.text(host,model.getId()+(model.getServerDeclaredAvailability()==ModelCatalog.Availability.UNAVAILABLE?" · 暂不可用":""),12,Ui.MUTED));
                row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
                if(selected)row.addView(new AgentIconView(host,AgentIconView.Icon.CHECK,c.accent),new LinearLayout.LayoutParams(Ui.dp(host,22),Ui.dp(host,22)));
                row.setSelected(selected);row.setContentDescription(model.getDisplayName()+(selected?"，已选择":""));row.setFocusable(true);row.setClickable(true);
                Ui.enabled(row,model.getServerDeclaredAvailability()!=ModelCatalog.Availability.UNAVAILABLE);
                row.setOnClickListener(v->{candidateId=model.getId();candidateEffort=null;renderModels();renderEfforts();updateApply();});list.addView(row,new LinearLayout.LayoutParams(-1,-2));
            }
            if(found==0)list.addView(Ui.text(host,"没有匹配的模型",14,Ui.MUTED));
        }
        private void renderEfforts() {
            efforts.removeAllViews();ModelCatalog.Model model=catalog==null?null:catalog.findModel(candidateId);
            effortHint.setText(model==null?"先选择模型":"选项来自当前模型列表，实际调用结果会在对话中显示。");
            if(model==null)return;
            addEffort(null,"默认");for(String option:model.getReasoningEfforts())addEffort(option,option);
        }
        private void addEffort(String value,String label) {
            Button button=Ui.action(host,label,Objects.equals(value,candidateEffort),false,()->{candidateEffort=value;renderEfforts();updateApply();});
            button.setSelected(Objects.equals(value,candidateEffort));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.setMargins(0,0,Ui.dp(host,8),0);efforts.addView(button,p);
        }
    }
}
