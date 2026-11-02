// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import io.xiyan.agent.account.SiwcClient;
import io.xiyan.agent.control.XiyanAccessibilityService;
import io.xiyan.agent.core.model.ModelCatalog;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Chinese application shell. Visible account, model and task states retain their evidence source. */
public final class MainActivity extends Activity {
    private static final String[] TABS = {"首页", "对话", "任务", "设置"};
    private static final AgentIconView.Icon[] TAB_ICONS = {AgentIconView.Icon.HOME,
            AgentIconView.Icon.CHAT, AgentIconView.Icon.TASKS, AgentIconView.Icon.SETTINGS};
    private Diagnostics diagnostics;
    private SiwcClient account;
    private int tab;
    private boolean modelPage;
    private boolean started;
    private boolean stoppedHere;
    private String pendingDraft;
    private EditText composer;
    private TextView accountStatus;
    private TextView controlStatus;
    private TextView selectedModelStatus;
    private TextView inferenceOutput;
    private TextView inferenceState;
    private TextView chatModelStatus;
    private Button sendButton;
    private TextView stopStatus;
    private final SiwcClient.Observer accountObserver = () -> runOnUiThread(this::accountChanged);
    private final XiyanAccessibilityService.StateListener controlObserver = state -> runOnUiThread(this::refreshStatus);

    @Override public void onCreate(Bundle savedInstanceState) {
        Ui.applyTheme(this);
        super.onCreate(savedInstanceState);
        diagnostics = new Diagnostics(this);
        account = SiwcClient.get(this);
        if (savedInstanceState != null) {
            tab = Math.max(0, Math.min(3, savedInstanceState.getInt("tab")));
            modelPage = savedInstanceState.getBoolean("models");
            pendingDraft = savedInstanceState.getString("draft");
            stoppedHere = savedInstanceState.getBoolean("stopped");
        }
        if (pendingDraft == null) pendingDraft = getPreferences(MODE_PRIVATE).getString("taskDraft", "");
        diagnostics.record("应用", "打开助手首页");
        render();
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
    }
    @Override protected void onStart() {
        super.onStart();
        started = true;
        account.addObserver(accountObserver);
        XiyanAccessibilityService.addStateListener(controlObserver);
        refreshStatus();
    }
    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
    }
    @Override protected void onStop() {
        started = false;
        account.removeObserver(accountObserver);
        XiyanAccessibilityService.removeStateListener(controlObserver);
        super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        rememberDraft();
        state.putInt("tab", tab);
        state.putBoolean("models", modelPage);
        state.putBoolean("stopped", stoppedHere);
        state.putString("draft", pendingDraft);
        super.onSaveInstanceState(state);
    }
    private void rememberDraft() {
        if (composer != null) pendingDraft = composer.getText().toString();
    }
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() { handleBack(); }
    private void handleBack() {
        if (modelPage) { modelPage = false; render(); }
        else if (tab != 0) navigate(0);
        else finish();
    }
    private void navigate(int destination) {
        rememberDraft();
        tab = destination; modelPage = false; render();
    }
    private void openModels() {
        rememberDraft(); modelPage = true; render();
    }
    private void accountChanged() {
        if (!started || isDestroyed()) return;
        if (modelPage) render(); else refreshStatus();
    }
    private void render() {
        rememberDraft();
        composer = null; accountStatus = null; controlStatus = null; selectedModelStatus = null; stopStatus = null;
        inferenceOutput = null; inferenceState = null; chatModelStatus = null; sendButton = null;
        LinearLayout root = Ui.column(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout page = Ui.column(this);
        page.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 24));
        FrameLayout center = new FrameLayout(this);
        int width = Math.min(getResources().getConfiguration().screenWidthDp, 680);
        center.addView(page, new FrameLayout.LayoutParams(Ui.dp(this, width), -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        scroll.addView(center);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (modelPage) showModels(page);
        else if (tab == 0) showHome(page);
        else if (tab == 1) showChat(page);
        else if (tab == 2) showTasks(page);
        else showSettings(page);
        addStopBar(root);
        addNavigation(root);
        Ui.install(this, root);
        refreshStatus();
    }
    private void header(LinearLayout page, String title, String subtitle) {
        LinearLayout brand = Ui.row(this);
        TextView name = Ui.text(this, "XIYAN AGENT", 12, Ui.ACCENT);
        name.setLetterSpacing(.15f);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        brand.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        brand.addView(Ui.badge(this, "技术预览"));
        page.addView(brand);
        Ui.gap(this, page, 15);
        page.addView(Ui.heading(this, title, 28));
        page.addView(Ui.text(this, subtitle, 14, Ui.MUTED));
    }
    private void showHome(LinearLayout page) {
        header(page, "你的手机助手", "从一句话开始，掌握任务的每一步。");
        LinearLayout hero = Ui.surface(this, page, Ui.colors(this).accentSurface);
        LinearLayout title = Ui.row(this);
        title.addView(new AgentIconView(this, AgentIconView.Icon.ORBIT, Ui.colors(this).accent),
                new LinearLayout.LayoutParams(Ui.dp(this, 50), Ui.dp(this, 50)));
        TextView heroTitle = Ui.heading(this, "准备就绪后，开始协作", 21);
        LinearLayout.LayoutParams heroTitleParams = new LinearLayout.LayoutParams(0, -2, 1);
        heroTitleParams.setMargins(Ui.dp(this, 12), 0, 0, 0);
        title.addView(heroTitle, heroTitleParams); hero.addView(title);
        hero.addView(Ui.text(this, "当前没有运行中的任务。先连接账号，查看实际模型目录与执行条件。", 14, Ui.TEXT));
        Button next = Ui.action(this, "查看账号与模型", true, false, this::openModels);
        addButton(hero, next);

        LinearLayout status = Ui.card(this, page, "当前状态", "连接与执行");
        accountStatus = Ui.text(this, "", 14, Ui.TEXT); status.addView(accountStatus);
        controlStatus = Ui.text(this, "", 14, Ui.MUTED); status.addView(controlStatus);
        selectedModelStatus = Ui.text(this, "", 14, Ui.MUTED); status.addView(selectedModelStatus);
        status.addView(Ui.text(this, "任务引擎：等待模型推理验证", 14, Ui.MUTED));

        Ui.gap(this, page, 22);
        page.addView(Ui.heading(this, "选择工作方式", 20));
        page.addView(Ui.text(this, "保存本机模式偏好，任务开始前可再次调整。", 13, Ui.MUTED));
        LinearLayout modes = Ui.row(this);
        addMode(modes, "通用操作", "日常应用与多步任务", AgentIconView.Icon.PHONE);
        addMode(modes, "游戏操作", "Galaxy 联合防御", AgentIconView.Icon.GAME);
        page.addView(modes);

        LinearLayout task = Ui.card(this, page, "任务空间", "先写下想做的事");
        task.addView(Ui.text(this, "对话页可以保存任务草稿。账号、模型和执行引擎完成验证后，再提交执行。", 14, Ui.MUTED));
        Ui.button(this, task, "编写任务草稿", false, () -> navigate(1));
    }
    private void addMode(LinearLayout modes, String name, String subtitle, AgentIconView.Icon icon) {
        boolean selected = name.equals(selectedMode());
        Ui.Colors c = Ui.colors(this);
        LinearLayout card = Ui.column(this);
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
        card.setBackground(Ui.shape(this, selected ? c.accentSurface : c.surface, 22,
                selected ? c.accent : c.border));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
        params.setMargins(name.equals("通用操作") ? 0 : Ui.dp(this, 10), Ui.dp(this, 12), 0, 0);
        card.addView(new AgentIconView(this, icon, selected ? c.accent : c.muted),
                new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32)));
        Ui.gap(this, card, 10);
        card.addView(Ui.heading(this, name, 17));
        card.addView(Ui.text(this, subtitle, 12, Ui.MUTED));
        Button choose = Ui.action(this, selected ? "已选择" : "选择模式", false, false, () -> {
            getPreferences(MODE_PRIVATE).edit().putString("mode", name).apply();
            diagnostics.record("模式偏好", name); render();
        });
        choose.setSelected(selected);
        addButton(card, choose);
        modes.addView(card, params);
    }
    private String selectedMode() { return getPreferences(MODE_PRIVATE).getString("mode", "通用操作"); }
    private void showChat(LinearLayout page) {
        header(page, "和 Xiyan 对话", "先写下目标，准备好后发送给模型。");
        LinearLayout welcome = Ui.card(this, page, "开始一段任务", "今天想完成什么？");
        welcome.addView(Ui.text(this, "可以描述目标、使用的应用和完成条件。运行中的每一步将在任务页呈现。", 15, Ui.MUTED));
        Ui.button(this, welcome, "整理一项日常任务", false, () -> fillDraft("我想在手机上完成：\n使用的应用：\n完成条件："));
        Ui.button(this, welcome, "准备游戏任务", false, () -> fillDraft("游戏：Galaxy 联合防御\n本次目标：\n停止条件："));

        LinearLayout draft = Ui.card(this, page, "本机草稿 · 手动发送", "任务草稿");
        composer = new EditText(this);
        composer.setTextColor(Ui.colors(this).text);
        composer.setHintTextColor(Ui.colors(this).muted);
        composer.setTextSize(16);
        composer.setGravity(Gravity.TOP | Gravity.START);
        composer.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        composer.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4000)});
        composer.setMinLines(5); composer.setMaxLines(12);
        composer.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        composer.setBackground(Ui.shape(this, Ui.colors(this).background, 16, Ui.colors(this).border));
        composer.setHint("例如：打开一个应用，完成指定操作，并在完成后停下。");
        composer.setContentDescription("任务草稿，最多四千字，保存后留在此设备");
        composer.setText(pendingDraft);
        LinearLayout.LayoutParams editorParams = new LinearLayout.LayoutParams(-1, -2);
        editorParams.setMargins(0, Ui.dp(this, 12), 0, 0); draft.addView(composer, editorParams);
        Button save = Ui.action(this, "保存草稿", true, false, () -> {
            rememberDraft();
            getPreferences(MODE_PRIVATE).edit().putString("taskDraft", pendingDraft).apply();
            Toast.makeText(this, "草稿已保存在此设备，尚未提交执行", Toast.LENGTH_SHORT).show();
        });
        addButton(draft, save);
        chatModelStatus = Ui.text(this, "", 13, Ui.MUTED); draft.addView(chatModelStatus);
        sendButton = Ui.action(this, "发送给模型", true, false, this::sendPrompt);
        addButton(draft, sendButton);
        draft.addView(Ui.text(this, "保存草稿会留在此设备；发送后，文本会提交给所选模型。手机自主执行仍待接入。", 13, Ui.MUTED));
        Ui.button(this, draft, "查看连接条件", false, this::openModels);
        LinearLayout answer = Ui.card(this, page, "模型回复", "本次对话");
        inferenceState = Ui.text(this, "", 13, Ui.ACCENT); answer.addView(inferenceState);
        inferenceOutput = Ui.text(this, "", 15, Ui.TEXT);
        inferenceOutput.setTextIsSelectable(true); answer.addView(inferenceOutput);
    }
    private void fillDraft(String value) {
        if (composer == null) return;
        if (composer.getText().length() > 0) {
            new AlertDialog.Builder(this).setTitle("替换当前草稿？")
                    .setMessage("这会替换输入框中的内容。已保存的草稿会在你再次保存时更新。")
                    .setNegativeButton("保留内容", null)
                    .setPositiveButton("使用模板", (dialog, which) -> composer.setText(value)).show();
        } else composer.setText(value);
    }
    private void showTasks(LinearLayout page) {
        header(page, "任务进度", "目标、执行记录和恢复入口，集中在这里。");
        LinearLayout empty = Ui.card(this, page, "当前任务", "暂无执行任务");
        empty.addView(new AgentIconView(this, AgentIconView.Icon.TASKS, Ui.colors(this).accent),
                new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64)));
        empty.addView(Ui.text(this, "任务开始后，可在这里查看运行时间、已完成步骤、失败原因与检查点。", 15, Ui.MUTED));
        LinearLayout controls = Ui.row(this);
        Button pause = Ui.action(this, "暂停", false, false, () -> { });
        Button resume = Ui.action(this, "恢复", false, false, () -> { });
        pause.setEnabled(false); resume.setEnabled(false);
        pause.setAlpha(.55f); resume.setAlpha(.55f);
        addWeighted(controls, pause, false); addWeighted(controls, resume, true);
        empty.addView(controls);
        empty.addView(Ui.text(this, "暂停与恢复在任务运行后启用。底部停止入口随时可用。", 12, Ui.MUTED));
        LinearLayout readiness = Ui.card(this, page, "开始前", "执行准备");
        readiness.addView(Ui.text(this, "01  连接官方账号\n02  读取账户模型与思考选项\n03  验证模型推理与手机执行\n04  提交任务并确认运行条件", 14, Ui.MUTED));
        Ui.button(this, readiness, "查看账号与模型", false, this::openModels);
        Ui.button(this, readiness, "回到任务草稿", false, () -> navigate(1));
    }
    private void showSettings(LinearLayout page) {
        header(page, "设置", "账号、模型与手机控制，一目了然。");
        LinearLayout connection = Ui.card(this, page, "账号与模型", "连接你的 ChatGPT 订阅");
        accountStatus = Ui.text(this, "", 14, Ui.TEXT); connection.addView(accountStatus);
        Ui.button(this, connection, "账号连接与模型目录", false, this::openModels);
        Ui.button(this, connection, "打开官方用量页面", false, () -> openUrl("https://chatgpt.com/settings/usage"));

        LinearLayout appearance = Ui.card(this, page, "界面", "外观主题");
        String selected = Ui.themeChoice(this);
        appearance.addView(Ui.text(this, "当前：" + ("light".equals(selected) ? "浅色" : "dark".equals(selected) ? "深色" : "跟随系统"), 14, Ui.MUTED));
        Ui.button(this, appearance, "选择主题", false, this::chooseTheme);
        appearance.addView(Ui.text(this, "字号跟随系统设置，长内容可滚动查看。", 12, Ui.MUTED));

        LinearLayout device = Ui.card(this, page, "手机控制", "权限与安全停止");
        controlStatus = Ui.text(this, "", 14, Ui.TEXT); device.addView(controlStatus);
        device.addView(Ui.text(this, "无障碍服务由你在系统设置中授权。当前控制验证集中在应用内测试台。", 13, Ui.MUTED));
        Ui.button(this, device, "打开无障碍设置", false, this::openAccessibilitySettings);

        LinearLayout development = Ui.card(this, page, "开发与反馈", "技术预览 " + BuildConfig.VERSION_NAME);
        development.addView(Ui.text(this, "运行环境、控制测试台、近期诊断和日志导出已收纳到开发者页面。", 13, Ui.MUTED));
        Ui.button(this, development, "开发者与诊断", false, () -> startActivity(new Intent(this, DeveloperActivity.class)));
        Ui.button(this, development, "查看开源项目", false, () -> openUrl("https://github.com/XiYanzx/xiyan-agent"));
        page.addView(Ui.text(this, "GPL-3.0-only · 技术预览阶段", 12, Ui.MUTED));
    }
    private void chooseTheme() {
        String[] values = {"system", "light", "dark"};
        String current = Ui.themeChoice(this);
        int checked = "light".equals(current) ? 1 : "dark".equals(current) ? 2 : 0;
        new AlertDialog.Builder(this).setTitle("选择外观")
                .setSingleChoiceItems(new String[]{"跟随系统", "浅色", "深色"}, checked, (dialog, which) -> {
                    rememberDraft();
                    getSharedPreferences("xiyan_appearance", MODE_PRIVATE).edit().putString("theme", values[which]).apply();
                    dialog.dismiss(); recreate();
                }).setNegativeButton("取消", null).show();
    }
    private void showModels(LinearLayout page) {
        Button back = Ui.action(this, "‹ 返回" + TABS[tab], false, false, () -> { modelPage = false; render(); });
        page.addView(back, new LinearLayout.LayoutParams(-2, -2));
        Ui.gap(this, page, 12);
        header(page, "账号与模型", "连接状态、模型目录与思考选项以实际响应为准。");
        SiwcClient.Snapshot snapshot = account.snapshot();
        LinearLayout login = Ui.card(this, page, "官方授权", snapshot.hasSession ? (snapshot.reauthRequired ? "需要重新登录" : snapshot.connected ? "本机会话有效" : "本机会话已过期") : "尚未登录");
        accountStatus = Ui.text(this, snapshot.message, 14, Ui.TEXT); login.addView(accountStatus);
        login.addView(Ui.text(this, snapshot.hasSession ? "会话保存在此设备，调用前检查有效期。具体推理状态请查看对话页。"
                : "通过系统浏览器完成 ChatGPT 登录与授权。授权完成后返回本应用。", 13, Ui.MUTED));
        Button environment = Ui.button(this, login, "检查官方连接环境", false, account::checkEnvironment);
        environment.setEnabled(!snapshot.busy);
        if (!snapshot.hasSession || !snapshot.connected || snapshot.reauthRequired) {
            Button signIn = Ui.button(this, login, "在浏览器登录", false, this::explainSignIn);
            signIn.setEnabled(!snapshot.busy);
        }
        if (snapshot.hasSession) {
            Button signOut = Ui.button(this, login, "退出本机账号", false, () -> new AlertDialog.Builder(this)
                    .setTitle("退出本机账号？").setMessage("移除本机会话，并请求官方撤销刷新令牌。")
                    .setNegativeButton("保留连接", null).setPositiveButton("退出", (dialog, which) -> account.signOut()).show());
            signOut.setEnabled(!snapshot.busy);
        }
        if (snapshot.busy) Ui.button(this, login, "取消当前连接操作", false, account::cancel);

        LinearLayout models = Ui.card(this, page, "模型目录", "账户返回的模型");
        Button refresh = Ui.button(this, models, "刷新模型目录", false, account::refreshModels);
        refresh.setEnabled(snapshot.hasSession && !snapshot.reauthRequired && !snapshot.busy);
        if (snapshot.catalog == null || snapshot.catalog.getModels().isEmpty()) {
            models.addView(Ui.text(this, snapshot.catalog == null ? "尚未获取模型目录" : "本次响应中没有可显示的模型", 17, Ui.TEXT));
            models.addView(Ui.text(this, "连接账号并刷新后，这里会显示模型名称、服务端状态与支持的思考程度。", 13, Ui.MUTED));
        } else {
            String fetched = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(new Date(snapshot.catalog.getFetchedAtEpochMillis()));
            models.addView(Ui.text(this, "最近获取：" + fetched + "\n来源：" + snapshot.catalog.getSourceDetail(), 12, Ui.MUTED));
            for (ModelCatalog.Model model : snapshot.catalog.getModels()) addModel(models, snapshot.catalog, model);
        }
        LinearLayout usage = Ui.card(this, page, "订阅用量", "等待官方额度读数");
        usage.addView(Ui.text(this, "当前渠道尚未提供已验证的额度数字。可前往官方页面查看账户用量。", 13, Ui.MUTED));
        Ui.button(this, usage, "打开官方用量页面", false, () -> openUrl("https://chatgpt.com/settings/usage"));
    }
    private void addModel(LinearLayout parent, ModelCatalog catalog, ModelCatalog.Model model) {
        LinearLayout item = Ui.card(this, parent, model.getId(), model.getDisplayName());
        String availability = model.getServerDeclaredAvailability() == ModelCatalog.Availability.AVAILABLE ? "服务端声明可用"
                : model.getServerDeclaredAvailability() == ModelCatalog.Availability.UNAVAILABLE ? "服务端声明不可用" : "服务端尚未声明可用性";
        item.addView(Ui.text(this, availability, 13, Ui.MUTED));
        item.addView(Ui.text(this, "思考程度：" + (model.getReasoningEfforts().isEmpty() ? "本次元数据未提供" : String.join("、", model.getReasoningEfforts())), 13, Ui.MUTED));
        Button choose = Ui.button(this, item, "配置此模型", false, () -> chooseModel(catalog, model));
        choose.setEnabled(model.getServerDeclaredAvailability() != ModelCatalog.Availability.UNAVAILABLE && !account.snapshot().busy);
    }
    private void chooseModel(ModelCatalog catalog, ModelCatalog.Model model) {
        List<String> efforts = new ArrayList<>(); efforts.add(null); efforts.addAll(model.getReasoningEfforts());
        List<String> labels = new ArrayList<>(); labels.add("使用服务默认值"); labels.addAll(model.getReasoningEfforts());
        new AlertDialog.Builder(this).setTitle(model.getDisplayName() + " · 思考程度")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    try {
                        SiwcClient.Snapshot current = account.snapshot();
                        if (current.catalog != catalog || !current.connected || current.busy || current.reauthRequired) {
                            Toast.makeText(this, "账号或目录已变化，请刷新后重新选择", Toast.LENGTH_LONG).show();
                            return;
                        }
                        ModelCatalog.Selection selection = catalog.select(model.getId(), efforts.get(which), null);
                        getSharedPreferences("xiyan_model_preferences", MODE_PRIVATE).edit()
                                .putString("accountScope", catalog.getAccountScope()).putString("modelId", selection.getModel().getId())
                                .putString("effort", selection.getReasoningEffort()).apply();
                        Toast.makeText(this, "已保存模型偏好，实际推理仍待验证", Toast.LENGTH_LONG).show();
                        render();
                    } catch (ModelCatalog.SelectionException exception) {
                        Toast.makeText(this, "目录已变化，请刷新后重新选择", Toast.LENGTH_LONG).show();
                    }
                }).setNegativeButton("取消", null).show();
    }
    private void explainSignIn() {
        new AlertDialog.Builder(this).setTitle("连接 ChatGPT 订阅")
                .setMessage("接下来将打开手机系统浏览器，由你完成官方登录与订阅授权。Xiyan 使用本应用的身份发起请求，授权会话加密保存在此设备。\n\n当前模型推理与自主任务仍待验证。")
                .setNegativeButton("稍后连接", null)
                .setPositiveButton("打开官方登录", (dialog, which) -> account.signIn(uri -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri.toASCIIString()))); }
                    catch (ActivityNotFoundException exception) { account.cancel(); Toast.makeText(this, "请先安装浏览器", Toast.LENGTH_LONG).show(); }
                })).show();
    }
    private void addStopBar(LinearLayout root) {
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 10));
        bar.setBackgroundColor(Ui.colors(this).surface);
        stopStatus = Ui.text(this, "当前无运行任务", 12, Ui.MUTED);
        bar.addView(stopStatus, new LinearLayout.LayoutParams(0, -2, 1));
        Button stop = Ui.action(this, "■  停止", false, true, this::emergencyStop);
        stop.setContentDescription("紧急停止，撤销后续手机控制动作");
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMargins(Ui.dp(this, 12), 0, 0, 0); bar.addView(stop, params);
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
    }
    private void addNavigation(LinearLayout root) {
        LinearLayout navigation = Ui.row(this);
        navigation.setBackgroundColor(Ui.colors(this).surface);
        navigation.setPadding(Ui.dp(this, 8), Ui.dp(this, 3), Ui.dp(this, 8), Ui.dp(this, 5));
        for (int i = 0; i < TABS.length; i++) {
            final int destination = i;
            boolean selected = tab == i;
            LinearLayout item = new LinearLayout(this) {
                @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(info);
                    info.setClassName(Button.class.getName());
                }
            };
            item.setOrientation(LinearLayout.VERTICAL); item.setGravity(Gravity.CENTER);
            item.setPadding(Ui.dp(this, 4), Ui.dp(this, 9), Ui.dp(this, 4), Ui.dp(this, 7));
            item.setMinimumHeight(Ui.dp(this, 68)); item.setFocusable(true); item.setClickable(true);
            item.setSelected(selected); item.setContentDescription(TABS[i]);
            item.setBackground(Ui.shape(this, selected ? Ui.colors(this).accentSurface : Color.TRANSPARENT, 20, Color.TRANSPARENT));
            item.addView(new AgentIconView(this, TAB_ICONS[i], selected ? Ui.colors(this).accent : Ui.colors(this).muted),
                    new LinearLayout.LayoutParams(Ui.dp(this, 25), Ui.dp(this, 25)));
            TextView label = Ui.text(this, TABS[i], 12, selected ? Ui.ACCENT : Ui.MUTED);
            label.setGravity(Gravity.CENTER);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            item.addView(label); item.setOnClickListener(view -> navigate(destination));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            params.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0); navigation.addView(item, params);
        }
        root.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
    }
    private void refreshStatus() {
        if (account == null || isDestroyed()) return;
        SiwcClient.Snapshot snapshot = account.snapshot();
        if (accountStatus != null) accountStatus.setText(modelPage ? snapshot.message
                : snapshot.connected ? "账号：本机会话有效 · 调用前检查" : snapshot.hasSession ? "账号：本机会话需要重新连接" : "账号：尚未登录");
        if (selectedModelStatus != null) selectedModelStatus.setText(selectedModelDescription(snapshot));
        if (chatModelStatus != null) chatModelStatus.setText(selectedModelDescription(snapshot));
        if (sendButton != null) sendButton.setEnabled(!snapshot.busy && validSelection(snapshot) != null);
        if (inferenceState != null) {
            String status = snapshot.inferenceStatus;
            inferenceState.setText("running".equals(status) ? "正在接收模型响应"
                    : "completed".equals(status) ? "本次文本回复已完成"
                    : "cancelled".equals(status) ? "本次响应已取消"
                    : "failed".equals(status) ? snapshot.message : "等待你的第一条消息");
        }
        if (inferenceOutput != null) inferenceOutput.setText(snapshot.inferenceText.isEmpty()
                ? "模型回复会显示在这里。" : snapshot.inferenceText);
        if (controlStatus != null) controlStatus.setText(XiyanAccessibilityService.getConnectedService() == null
                ? "手机控制：无障碍服务未连接" : "手机控制：已连接 · 测试台内单步授权");
        if (stopStatus != null) stopStatus.setText("running".equals(snapshot.inferenceStatus) ? "模型正在响应"
                : stoppedHere ? "已撤销后续控制授权" : "当前无运行任务");
    }
    private String selectedModelDescription(SiwcClient.Snapshot snapshot) {
        if (snapshot.catalog == null) return "当前模型：等待账户模型目录";
        android.content.SharedPreferences preference = getSharedPreferences("xiyan_model_preferences", MODE_PRIVATE);
        String scope = preference.getString("accountScope", "");
        String modelId = preference.getString("modelId", "");
        if (!scope.equals(snapshot.catalog.getAccountScope())) return "当前模型：等待选择";
        ModelCatalog.Model model = snapshot.catalog.findModel(modelId);
        if (model == null || model.getServerDeclaredAvailability() == ModelCatalog.Availability.UNAVAILABLE) {
            return "当前模型：请从最新目录选择";
        }
        String effort = preference.getString("effort", null);
        try { snapshot.catalog.select(modelId, effort, null); }
        catch (ModelCatalog.SelectionException exception) { return "模型偏好：请按最新目录重新选择"; }
        return "模型偏好：" + model.getDisplayName() + "\n思考程度：" + (effort == null ? "服务默认值" : effort);
    }
    private ModelCatalog.Selection validSelection(SiwcClient.Snapshot snapshot) {
        if (!snapshot.connected || snapshot.reauthRequired || snapshot.catalog == null) return null;
        android.content.SharedPreferences preference = getSharedPreferences("xiyan_model_preferences", MODE_PRIVATE);
        if (!preference.getString("accountScope", "").equals(snapshot.catalog.getAccountScope())) return null;
        try { return snapshot.catalog.select(preference.getString("modelId", ""), preference.getString("effort", null), null); }
        catch (ModelCatalog.SelectionException exception) { return null; }
    }
    private void sendPrompt() {
        rememberDraft();
        ModelCatalog.Selection selection = validSelection(account.snapshot());
        if (selection == null) { Toast.makeText(this, "请先连接账号并从最新目录选择模型", Toast.LENGTH_LONG).show(); return; }
        if (pendingDraft == null || pendingDraft.trim().isEmpty()) {
            Toast.makeText(this, "请先写下想发送的内容", Toast.LENGTH_SHORT).show(); return;
        }
        account.sendText(selection.getModel().getId(), selection.getReasoningEffort(), pendingDraft);
    }
    private void emergencyStop() {
        XiyanAccessibilityService service = XiyanAccessibilityService.getConnectedService();
        if (service != null) service.emergencyStop();
        account.cancel(); stoppedHere = true;
        diagnostics.record("停止请求", service == null ? "服务未连接，无可撤销的控制会话" : "已请求撤销后续手势授权");
        refreshStatus();
        Toast.makeText(this, "已撤销后续动作授权；已派发的短手势可能仍在结束", Toast.LENGTH_LONG).show();
    }
    private void addButton(LinearLayout parent, Button button) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, Ui.dp(this, 12), 0, 0); parent.addView(button, params);
    }
    private void addWeighted(LinearLayout parent, View child, boolean margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMargins(margin ? Ui.dp(this, 10) : 0, Ui.dp(this, 14), 0, 0); parent.addView(child, params);
    }
    private void openAccessibilitySettings() {
        try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
        catch (ActivityNotFoundException exception) { Toast.makeText(this, "系统未提供此设置入口", Toast.LENGTH_SHORT).show(); }
    }
    private void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (ActivityNotFoundException exception) { Toast.makeText(this, "请先安装浏览器", Toast.LENGTH_SHORT).show(); }
    }
}
