// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import io.xiyan.agent.control.XiyanAccessibilityService;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Local developer tools, kept separate from user task navigation. */
public final class DeveloperActivity extends Activity {
    private static final int EXPORT_REPORT = 20;
    private Diagnostics diagnostics;
    private TextView controlStatus;
    private TextView log;
    private final XiyanAccessibilityService.StateListener stateListener = state -> refresh();

    @Override public void onCreate(Bundle savedInstanceState) {
        Ui.applyTheme(this);
        super.onCreate(savedInstanceState);
        diagnostics = new Diagnostics(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = Ui.column(this);
        int padding = Ui.dp(this, 20);
        page.setPadding(padding, padding, padding, Ui.dp(this, 32));
        scroll.addView(page);
        LinearLayout header=Ui.row(this);
        header.addView(Ui.iconButton(this,AgentIconView.Icon.BACK,"返回设置",Ui.colors(this).text,this::finish),new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));
        header.addView(Ui.heading(this,"开发者与诊断",20),new LinearLayout.LayoutParams(0,-2,1));page.addView(header);
        Ui.gap(this, page, 12);
        page.addView(Ui.text(this, "技术预览 " + BuildConfig.VERSION_NAME + " · P0 验证中", 13, Ui.MUTED));
        page.addView(Ui.text(this, "此处验证本机控制与运行环境。自主任务与长周期稳定性仍待验收。", 14, Ui.MUTED));
        android.widget.Button stop=Ui.outlineAction(this,"紧急停止",this::emergencyStop);stop.setTextColor(Ui.colors(this).alert);page.addView(stop);

        LinearLayout control = Ui.card(this, page, "手机控制", "本机控制测试台");
        controlStatus = Ui.text(this, "", 14, Ui.TEXT); control.addView(controlStatus);
        control.addView(Ui.text(this, "每次测试只授权一次短手势。Android 14+ 使用指定窗口截图，图像只留内存；Android 10–13 的截图适配仍待完成。", 13, Ui.MUTED));
        Ui.button(this, control, "打开无障碍设置", false, () -> {
            try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
            catch (ActivityNotFoundException exception) { toast("系统未提供此设置入口"); }
        });
        Ui.button(this, control, "进入手机控制测试台", false, () -> startActivity(new Intent(this, TestPadActivity.class)));

        LinearLayout runtime = Ui.card(this, page, "运行环境", "本应用运行时");
        runtime.addView(Ui.text(this, RuntimeProbe.describe(), 13, Ui.MUTED));
        runtime.addView(Ui.text(this, "源码提交：" + BuildConfig.SOURCE_COMMIT + "\n构建工作区："
                + (BuildConfig.SOURCE_TREE_CLEAN ? "干净" : "包含未提交改动"), 12, Ui.MUTED));
        Ui.button(this, runtime, "查看源码与验证进展", false, () -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/XiYanzx/xiyan-agent"))); }
            catch (ActivityNotFoundException exception) { toast("请先安装浏览器"); }
        });

        LinearLayout logs = Ui.card(this, page, "本机诊断", "最近事件");
        log = Ui.text(this, "", 13, Ui.MUTED); logs.addView(log);
        Ui.button(this, logs, "导出本机诊断 JSON", false, this::exportReport);
        logs.addView(Ui.text(this, "报告包含版本、系统 API / ABI 与应用生成的事件。截图、草稿、UI 文本和账号凭据均留在报告之外。", 12, Ui.MUTED));
        page.addView(Ui.text(this, "GPL-3.0-only · 源码与许可证见项目仓库", 12, Ui.MUTED));
        Ui.install(this, scroll);
    }
    @Override protected void onStart() {
        super.onStart(); XiyanAccessibilityService.addStateListener(stateListener);
    }
    @Override protected void onResume() { super.onResume(); refresh(); }
    @Override protected void onStop() {
        XiyanAccessibilityService.removeStateListener(stateListener); super.onStop();
    }
    private void refresh() {
        if (controlStatus == null || isDestroyed()) return;
        controlStatus.setText(XiyanAccessibilityService.getConnectedService() == null
                ? "无障碍服务：未连接" : "无障碍服务：已连接 · 测试台内单步授权");
        log.setText(diagnostics.latest());
    }
    private void emergencyStop() {
        XiyanAccessibilityService service = XiyanAccessibilityService.getConnectedService();
        if (service != null) service.emergencyStop();
        diagnostics.record("停止请求", service == null ? "服务未连接，无可撤销的控制会话" : "已请求撤销后续手势授权");
        refresh(); toast("已撤销后续动作授权；已派发的短手势可能仍在结束");
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private void exportReport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "xiyan-p0-diagnostics.json");
        try { startActivityForResult(intent, EXPORT_REPORT); }
        catch (ActivityNotFoundException exception) { toast("系统未提供文件保存入口"); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT_REPORT || result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri destination = data.getData(); final String report = diagnostics.export();
        new Thread(() -> {
            boolean saved = false;
            try (OutputStream output = getApplicationContext().getContentResolver().openOutputStream(destination)) {
                if (output == null) throw new IOException("无输出流");
                output.write(report.getBytes(StandardCharsets.UTF_8)); saved = true;
            } catch (IOException | SecurityException exception) {
                // System-selected destination details stay out of diagnostics.
            }
            final boolean success = saved;
            runOnUiThread(() -> { if (!isDestroyed()) toast(success ? "诊断报告已保存" : "保存失败，请重新选择文件位置"); });
        }, "xiyan-diagnostic-export").start();
    }
}
