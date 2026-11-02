// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.RippleDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import io.xiyan.agent.account.SiwcClient;
import io.xiyan.agent.core.model.ModelCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

final class ModelPickerDialog {
    private final Dialog dialog;
    private final Panel panel;
    ModelPickerDialog(MainActivity host){dialog=new Dialog(host);panel=new Panel(host,true,dialog::dismiss);}
    void show(){Ui.showBottomDialog(panel.host,dialog,panel.root);panel.shown();}
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
        private final boolean sheet;
        private final ScrollView root;
        private final LinearLayout body,list;
        private final EditText search;
        private final ProgressBar loading;
        private final TextView status,selection,effortHint,appliedState;
        private final Button refresh,apply,effortChoice;
        private ModelCatalog catalog;
        private SiwcClient.Snapshot snapshot;
        private String candidateId,candidateEffort;
        private boolean opened,initialFetchAttempted;
        Panel(MainActivity host,boolean sheet,Runnable saved) {
            this.host=host;this.sheet=sheet;this.saved=saved;Ui.Colors c=Ui.colors(host);body=Ui.page(host);
            if(host.getResources().getConfiguration().screenWidthDp<360)body.setPadding(Ui.dp(host,16),Ui.dp(host,12),Ui.dp(host,16),Ui.dp(host,20));
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
            if(sheet){
                root.setBackground(Ui.shape(host,c.surface,DesignTokens.RADIUS_SHEET,Color.TRANSPARENT));LinearLayout title=Ui.row(host);
                title.addView(Ui.heading(host,"模型与思考",22),new LinearLayout.LayoutParams(0,-2,1));
                title.addView(Ui.iconButton(host,AgentIconView.Icon.CLOSE,"关闭模型选择",c.muted,saved),new LinearLayout.LayoutParams(Ui.dp(host,48),Ui.dp(host,48)));body.addView(title);
            }
            LinearLayout metadata=Ui.row(host);loading=new ProgressBar(host,null,android.R.attr.progressBarStyleSmall);loading.setIndeterminateTintList(ColorStateList.valueOf(c.accent));loading.setVisibility(View.GONE);loading.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(Ui.dp(host,20),Ui.dp(host,20));lp.setMargins(0,0,Ui.dp(host,8),0);metadata.addView(loading,lp);
            status=Ui.text(host,"",12,Ui.MUTED);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);metadata.addView(status,new LinearLayout.LayoutParams(0,-2,1));
            refresh=Ui.textAction(host,"刷新",()->{initialFetchAttempted=true;host.account().refreshModels();});metadata.addView(refresh,new LinearLayout.LayoutParams(-2,Ui.dp(host,48)));body.addView(metadata);
            search=new EditText(host);search.setTextSize(15);search.setTextColor(c.text);search.setHintTextColor(c.muted);search.setSingleLine(true);
            search.setHint("搜索账号模型");search.setContentDescription("搜索账号模型");search.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            search.setMinimumHeight(Ui.dp(host,48));search.setPadding(Ui.dp(host,14),Ui.dp(host,12),Ui.dp(host,14),Ui.dp(host,12));search.setBackground(Ui.shape(host,c.raised,4,Color.TRANSPARENT));
            LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.setMargins(0,Ui.dp(host,4),0,Ui.dp(host,12));body.addView(search,sp);
            selection=Ui.text(host,"",12,Ui.MUTED);body.addView(selection);Ui.gap(host,body,8);list=Ui.column(host);body.addView(list,new LinearLayout.LayoutParams(-1,-2));
            Ui.divider(host,body);body.addView(Ui.heading(host,"思考程度",16));Ui.gap(host,body,8);
            effortChoice=Ui.outlineAction(host,"服务默认值",this::chooseEffort);effortChoice.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);Ui.downIndicator(host,effortChoice,c.muted);effortChoice.setContentDescription("选择思考程度，当前服务默认值");body.addView(effortChoice,new LinearLayout.LayoutParams(-1,-2));
            Ui.gap(host,body,8);effortHint=Ui.text(host,"",12,Ui.MUTED);body.addView(effortHint);Ui.gap(host,body,16);
            appliedState=Ui.text(host,"当前选择已应用",14,c.accent);body.addView(appliedState);
            apply=Ui.action(host,"应用选择",true,false,()->{
                if(isCurrentSelection()){if(sheet)saved.run();return;}
                if(catalog!=null&&candidateId!=null&&host.saveSelection(catalog,candidateId,candidateEffort))saved.run();
            });body.addView(apply,new LinearLayout.LayoutParams(-1,-2));
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
                catalog=current.catalog;ModelCatalog.Selection selected=host.configuredSelection(current);
                if(candidateId==null&&selected!=null){candidateId=selected.getModel().getId();candidateEffort=selected.getReasoningEffort();}
                if(catalog==null||catalog.findModel(candidateId)==null){candidateId=null;candidateEffort=null;}
                else if(candidateEffort!=null&&!catalog.findModel(candidateId).getReasoningEfforts().contains(candidateEffort))candidateEffort=null;
                renderModels();
            }else if(list.getChildCount()==0)renderModels();
            boolean readingModels=current.busy&&current.operationStage.startsWith("正在读取账号模型目录");loading.setVisibility(readingModels?View.VISIBLE:View.GONE);
            String metadata=readingModels?"正在读取账号模型…":current.busy?"其他操作进行中，模型稍后更新":current.hasSession?(current.catalog==null?Ui.accountMessage(current):"更新于 "+Ui.time(current.catalog.getFetchedAtEpochMillis())):"连接账号后获取你的模型";
            status.setText(metadata);Ui.enabled(refresh,current.hasSession&&!current.reauthRequired&&!current.busy);
            for(int i=0;i<list.getChildCount();i++){
                View row=list.getChildAt(i);if(row.getTag() instanceof ModelCatalog.Model)Ui.enabled(row,!current.busy&&!current.reauthRequired&&((ModelCatalog.Model)row.getTag()).getServerDeclaredAvailability()!=ModelCatalog.Availability.UNAVAILABLE);
            }
            updateChoice();
            if(current.catalog!=null||(current.busy&&current.operationStage.contains("目录")))initialFetchAttempted=true;
            if(opened&&!initialFetchAttempted&&current.catalog==null&&current.hasSession&&!current.reauthRequired&&!current.busy){initialFetchAttempted=true;host.account().refreshModels();}
        }
        private boolean isCurrentSelection(){
            ModelCatalog.Selection current=host.configuredSelection(host.account().snapshot());
            return current!=null&&current.getCatalog()==catalog&&current.getModel().getId().equals(candidateId)&&Objects.equals(current.getReasoningEffort(),candidateEffort);
        }
        private void updateChoice() {
            ModelCatalog.Model model=catalog==null?null:catalog.findModel(candidateId);boolean current=isCurrentSelection();
            String candidate=model==null?"选择模型后设置思考程度":String.format(Locale.SIMPLIFIED_CHINESE,"%s · %s",model.getDisplayName(),candidateEffort==null?"服务默认值":candidateEffort);
            String summary=model==null?"账号模型":(current?"当前使用：":"待应用：")+candidate;selection.setText(summary);
            String effort=candidateEffort==null?"服务默认值":candidateEffort;effortChoice.setText(effort);
            effortChoice.setContentDescription("选择思考程度，当前 "+effort);Ui.enabled(effortChoice,model!=null&&snapshot!=null&&!snapshot.busy&&!snapshot.reauthRequired);
            boolean rejected=snapshot!=null&&"failed".equals(snapshot.inferenceStatus)&&snapshot.lastFailure!=null&&snapshot.lastFailure.getHttpStatus()==400&&"reasoning.effort".equals(snapshot.lastFailure.getParam())&&"invalid_value".equals(snapshot.lastFailure.getCode());
            effortHint.setText(model==null?"先选择一个账号模型。":rejected?Ui.friendlyFailure(snapshot):"默认由服务决定，其他选项来自当前模型列表。");effortHint.setTextColor(rejected?Ui.colors(host).warning:Ui.colors(host).muted);
            appliedState.setVisibility(current?View.VISIBLE:View.GONE);apply.setVisibility(current&&!sheet?View.GONE:View.VISIBLE);apply.setText(current?"完成":"应用选择");
            Ui.enabled(apply,snapshot!=null&&snapshot.connected&&!snapshot.reauthRequired&&!snapshot.busy&&model!=null&&model.getServerDeclaredAvailability()!=ModelCatalog.Availability.UNAVAILABLE&&(!current||sheet));
        }
        private void chooseEffort() {
            ModelCatalog expected=catalog;String modelId=candidateId;ModelCatalog.Model model=expected==null?null:expected.findModel(modelId);
            if(model==null||snapshot==null||snapshot.busy||snapshot.reauthRequired)return;
            List<String> values=new ArrayList<>();values.add(null);values.addAll(model.getReasoningEfforts());String[] labels=new String[values.size()];labels[0]="服务默认值";
            int selected=0;for(int i=1;i<values.size();i++){labels[i]=values.get(i);if(Objects.equals(candidateEffort,values.get(i)))selected=i;}
            new AlertDialog.Builder(host).setTitle("思考程度").setSingleChoiceItems(labels,selected,(dialog,which)->{
                SiwcClient.Snapshot current=host.account().snapshot();
                if(current.catalog!=expected||catalog!=expected||!Objects.equals(candidateId,modelId)||current.busy||current.reauthRequired||!current.hasSession){host.toast("账号或目录已变化，请重新选择");dialog.dismiss();return;}
                candidateEffort=values.get(which);updateChoice();dialog.dismiss();
            }).setNegativeButton("取消",null).show();
        }
        private void renderModels() {
            list.removeAllViews();
            if(catalog==null||catalog.getModels().isEmpty()) {
                list.addView(Ui.text(host,catalog==null?"模型列表待获取":"本次列表中没有模型",14,Ui.MUTED));
                if(snapshot!=null&&(!snapshot.hasSession||snapshot.reauthRequired)){
                    Button connect=Ui.textAction(host,"连接账号",()->{saved.run();host.navigate(MainActivity.Screen.ACCOUNT);});list.addView(connect);
                }
                return;
            }
            String query=search.getText().toString().trim().toLowerCase(Locale.ROOT);int found=0;
            for(ModelCatalog.Model model:catalog.getModels()) {
                if(!query.isEmpty()&&!(model.getDisplayName()+" "+model.getId()).toLowerCase(Locale.ROOT).contains(query))continue;
                found++;boolean selected=model.getId().equals(candidateId);Ui.Colors c=Ui.colors(host);
                LinearLayout row=new LinearLayout(host){
                    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName(Button.class.getName());}
                };
                row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setMinimumHeight(Ui.dp(host,60));row.setPadding(Ui.dp(host,12),Ui.dp(host,12),Ui.dp(host,12),Ui.dp(host,12));
                row.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.ripple(host)),Ui.shape(host,Color.TRANSPARENT,0,Color.TRANSPARENT),null));
                LinearLayout labels=Ui.column(host);labels.addView(Ui.text(host,model.getDisplayName(),15,Ui.TEXT));
                if(!model.getDisplayName().equalsIgnoreCase(model.getId()))labels.addView(Ui.text(host,model.getId(),12,Ui.MUTED));
                if(model.getServerDeclaredAvailability()==ModelCatalog.Availability.UNAVAILABLE)labels.addView(Ui.text(host,"暂不可用",12,Ui.MUTED));row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
                if(selected){AgentIconView check=new AgentIconView(host,AgentIconView.Icon.CHECK,c.accent);check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.addView(check,new LinearLayout.LayoutParams(Ui.dp(host,24),Ui.dp(host,24)));}
                row.setTag(model);row.setSelected(selected);row.setContentDescription(model.getDisplayName()+(selected?"，已选择":"")+(model.getServerDeclaredAvailability()==ModelCatalog.Availability.UNAVAILABLE?"，暂不可用":""));row.setFocusable(true);row.setClickable(true);
                Ui.enabled(row,snapshot!=null&&!snapshot.busy&&!snapshot.reauthRequired&&model.getServerDeclaredAvailability()!=ModelCatalog.Availability.UNAVAILABLE);
                row.setOnClickListener(v->{
                    if(snapshot==null||snapshot.busy||snapshot.reauthRequired||snapshot.catalog!=catalog)return;
                    candidateId=model.getId();candidateEffort=null;renderModels();updateChoice();
                });LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,Ui.dp(host,4));list.addView(row,lp);
            }
            if(found==0)list.addView(Ui.text(host,"没有匹配的模型",14,Ui.MUTED));
        }
    }
}
