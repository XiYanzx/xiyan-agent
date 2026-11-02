// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.runtimeprobe;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Explicit, local-only native initialization diagnostic. */
public final class MainActivity extends Activity {
    private static final int EXPORT = 1;
    private ProbeRunner runner;
    private TextView output;
    private Button run;
    private Button stop;
    private Button export;
    private String report;
    private String exportSnapshot;
    private boolean destroyed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        runner = new ProbeRunner(getApplicationContext());
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.rgb(16, 24, 32));
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        page.setPadding(padding, padding, padding, padding);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            page.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom);
                return insets;
            });
        } else {
            page.setFitsSystemWindows(true);
        }
        TextView title = text("运行时初始化探针", 26);
        page.addView(title);
        page.addView(text("仅验证当前设备上的原生启动与 initialize。\n不登录、不联网；不验证 V8、工具、推理或真机兼容性。", 15));
        run = new Button(this);
        run.setText("核对哈希并运行探针");
        page.addView(run);
        stop = new Button(this);
        stop.setText("停止当前探针");
        stop.setEnabled(false);
        page.addView(stop);
        export = new Button(this);
        export.setText(R.string.export_evidence);
        page.addView(export);
        ScrollView scroll = new ScrollView(this);
        output = text("准备就绪。每次运行使用全新的应用私有目录。", 13);
        output.setTextIsSelectable(true);
        scroll.addView(output);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
        report = runner.readLastReport();
        if (report != null) output.setText(report);
        export.setEnabled(report != null);
        run.setOnClickListener(view -> {
            run.setEnabled(false);
            stop.setEnabled(true);
            export.setEnabled(false);
            output.setText("正在核对官方清单与原生文件；启动与响应均有超时限制……");
            runner.start(result -> runOnUiThread(() -> {
                if (destroyed) return;
                report = result;
                output.setText(result);
                run.setEnabled(true);
                stop.setEnabled(false);
                export.setEnabled(true);
            }));
        });
        stop.setOnClickListener(view -> runner.stop("user_stop"));
        export.setOnClickListener(view -> {
            exportSnapshot = report;
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "xiyan-runtime-probe.json");
            startActivityForResult(intent, EXPORT);
        });
    }

    private TextView text(String content, int size) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(222, 234, 242));
        view.setPadding(0, 8, 0, 12);
        return view;
    }

    @Override protected void onStop() {
        super.onStop();
        runner.stop("activity_not_visible");
    }

    @Override protected void onDestroy() {
        destroyed = true;
        runner.close();
        super.onDestroy();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT || result != RESULT_OK || data == null || data.getData() == null || exportSnapshot == null) return;
        try (OutputStream stream = getContentResolver().openOutputStream(data.getData())) {
            if (stream == null) throw new java.io.IOException("未能打开导出目标");
            stream.write(exportSnapshot.getBytes(StandardCharsets.UTF_8));
        } catch (Exception failure) {
            output.setText(getString(R.string.export_failed, failure.getClass().getSimpleName(), report));
        } finally { exportSnapshot = null; }
    }
}
