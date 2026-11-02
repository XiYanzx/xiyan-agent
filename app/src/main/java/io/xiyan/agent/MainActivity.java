// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import io.xiyan.agent.control.XiyanAccessibilityService;

/** P0 可安装验证入口。尚未接入账户，不提供自动任务的启动按钮。 */
public final class MainActivity extends Activity {
    private static final int EXPORT_REPORT = 20;
    private Diagnostics diagnostics;
    private TextView controlStatus;
    private TextView log;
    private TextView mode;
    private final XiyanAccessibilityService.StateListener stateListener = state -> refresh();

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        diagnostics = new Diagnostics(this);
        diagnostics.record("应用", "打开 P0 技术验证");
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = Ui.column(this);
        int padding = Ui.dp(this, 22);
        page.setPadding(padding, Ui.dp(this, 22), padding, Ui.dp(this, 32));
        scroll.addView(page);
        page.addView(Ui.text(this, "XIYAN / LOCAL AGENT", 12, Ui.ACCENT));
        page.addView(Ui.heading(this, "把每一步，交给证据。", 28));
        page.addView(Ui.text(this, "P0 技术验证  ·  " + BuildConfig.VERSION_NAME, 13, Ui.MUTED));

        LinearLayout stage = Ui.card(this, page, "CURRENT STAGE", "P0 验证中");
        stage.addView(Ui.text(this, "本版用于检查手机控制基础与运行环境。账号登录、模型推理和自主任务尚未接入，P0 尚未验收。", 14, Ui.MUTED));

        LinearLayout modes = Ui.card(this, page, "TWO MODES", "一个设备，一个执行入口");
        mode = Ui.text(this, "", 15, Ui.TEXT);
        modes.addView(mode);
        Ui.button(this, modes, "通用操作", false, () -> selectMode("通用操作"));
        Ui.button(this, modes, "游戏操作", false, () -> selectMode("游戏操作"));
        modes.addView(Ui.text(this, "当前仅保存模式偏好。Galaxy 联合防御适配等待真实游戏画面。", 12, Ui.MUTED));

        LinearLayout models = Ui.card(this, page, "ACCOUNT & MODEL", "等待官方账号接入");
        models.addView(Ui.text(this, "当前模型：未连接\n思考程度：等待模型元数据\n订阅额度：未知\n任务状态：未启动 · 运行时间：—", 14, Ui.TEXT));
        Ui.button(this, models, "模型与账号说明", false, () -> new AlertDialog.Builder(this)
                .setTitle("动态模型目录")
                .setMessage("模型和思考选项将由账号接口与 app-server 元数据提供。本版没有账户令牌、目录缓存或登录入口；不会将示例模型冒充实际可用模型。\n\n官方订阅认证与 Android 运行时仍需 P0 验证。")
                .setPositiveButton("知道了", null).show());
        Ui.button(this, models, "打开官方 Usage 页面", false, () -> openUrl("https://chatgpt.com/settings/usage"));

        LinearLayout control = Ui.card(this, page, "DEVICE CONTROL", "本机控制验证");
        controlStatus = Ui.text(this, "", 14, Ui.TEXT);
        control.addView(controlStatus);
        control.addView(Ui.text(this, "无障碍权限由你在系统开启。测试台只验证本应用内操作；Android 14+ 使用窗口截图，仅保留内存。此 P0 在 Android 10–13 暂不截图。", 13, Ui.MUTED));
        Ui.button(this, control, "打开无障碍设置", false, () -> openSettings());
        Ui.button(this, control, "进入手机控制测试台", false, () -> startActivity(new Intent(this, TestPadActivity.class)));
        Ui.button(this, control, "紧急停止", true, this::emergencyStop);

        LinearLayout runtime = Ui.card(this, page, "RUNTIME INVENTORY", "运行环境");
        runtime.addView(Ui.text(this, RuntimeProbe.describe(), 13, Ui.MUTED));
        Ui.button(this, runtime, "查看源码与验证进展", false, () -> openUrl("https://github.com/XiYanzx/xiyan-agent"));

        LinearLayout logs = Ui.card(this, page, "LOCAL DIAGNOSTICS", "最近事件");
        log = Ui.text(this, "", 12, Ui.MUTED);
        logs.addView(log);
        Ui.button(this, logs, "导出本机诊断 JSON", false, this::exportReport);
        logs.addView(Ui.text(this, "报告仅含版本、系统 API / ABI 和应用生成的事件，不含截图、UI 文本或凭据。", 12, Ui.MUTED));
        page.addView(Ui.text(this, "GPL-3.0-only · 源码与许可证见项目仓库", 11, Ui.MUTED));
        Ui.install(this, scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }
    @Override protected void onStart() {
        super.onStart();
        XiyanAccessibilityService.addStateListener(stateListener);
    }
    @Override protected void onStop() {
        XiyanAccessibilityService.removeStateListener(stateListener);
        super.onStop();
    }
    private void refresh() {
        controlStatus.setText(XiyanAccessibilityService.getConnectedService() == null
                ? "无障碍服务：未连接" : "无障碍服务：已连接 · 测试台内单步授权");
        log.setText(diagnostics.latest());
        mode.setText(getString(R.string.mode_preference, getPreferences(MODE_PRIVATE).getString("mode", "通用操作")));
    }
    private void selectMode(String value) {
        getPreferences(MODE_PRIVATE).edit().putString("mode", value).apply();
        diagnostics.record("模式偏好", value);
        refresh();
    }
    private void emergencyStop() {
        XiyanAccessibilityService service = XiyanAccessibilityService.getConnectedService();
        if (service != null) service.emergencyStop();
        diagnostics.record("停止请求", service == null ? "服务未连接，无可撤销的控制会话" : "已请求撤销后续手势授权");
        refresh();
        Toast.makeText(this, "已停止后续动作；已派发的短手势可能仍在结束", Toast.LENGTH_LONG).show();
    }
    private void openSettings() {
        try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
        catch (ActivityNotFoundException exception) { Toast.makeText(this, "系统未提供此设置入口", Toast.LENGTH_SHORT).show(); }
    }
    private void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (ActivityNotFoundException exception) { Toast.makeText(this, "请先安装浏览器", Toast.LENGTH_SHORT).show(); }
    }
    private void exportReport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "xiyan-p0-diagnostics.json");
        try { startActivityForResult(intent, EXPORT_REPORT); }
        catch (ActivityNotFoundException exception) { Toast.makeText(this, "系统未提供文件保存入口", Toast.LENGTH_SHORT).show(); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == EXPORT_REPORT && result == RESULT_OK && data != null && data.getData() != null) {
            final Uri destination = data.getData();
            final String report = diagnostics.export();
            new Thread(() -> {
                boolean saved = false;
                try (OutputStream output = getApplicationContext().getContentResolver().openOutputStream(destination)) {
                    if (output == null) throw new IOException("无输出流");
                    output.write(report.getBytes(StandardCharsets.UTF_8));
                    saved = true;
                } catch (IOException | SecurityException exception) {
                    // 输出位置由系统提供；失败细节不包含在公开诊断中。
                }
                final boolean success = saved;
                runOnUiThread(() -> {
                    if (!isDestroyed()) Toast.makeText(this, success ? "诊断报告已保存" : "保存失败，请重新选择文件位置", Toast.LENGTH_SHORT).show();
                });
            }, "xiyan-diagnostic-export").start();
        }
    }
}
