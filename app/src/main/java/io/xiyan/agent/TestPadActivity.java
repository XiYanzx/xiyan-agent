// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import io.xiyan.agent.control.XiyanAccessibilityService;
import io.xiyan.agent.control.XiyanAccessibilityService.Observation;
import io.xiyan.agent.control.XiyanAccessibilityService.ScreenshotFrame;

/** 派发坐标来自本页真实布局；只以实际点击计数和滚动位置确认结果。 */
public final class TestPadActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Diagnostics diagnostics;
    private TextView result;
    private TextView countLabel;
    private Button target;
    private ScrollView swipePad;
    private ImageView preview;
    private ScreenshotFrame frame;
    private final CaptureRequestTracker<ScreenshotFrame> captureRequests =
            new CaptureRequestTracker<>(ScreenshotFrame::close);
    private int tapCount;
    private boolean resumed;
    private long uiGeneration;

    @Override public void onCreate(Bundle savedInstanceState) {
        Ui.applyTheme(this);
        super.onCreate(savedInstanceState);
        diagnostics = new Diagnostics(this);
        tapCount = savedInstanceState == null ? 0 : savedInstanceState.getInt("tapCount");
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = Ui.column(this);
        int padding = Ui.dp(this, 18);
        page.setPadding(padding, padding, padding, padding);
        scroll.addView(page);
        LinearLayout header=Ui.row(this);
        header.addView(Ui.iconButton(this,AgentIconView.Icon.BACK,"返回诊断",Ui.colors(this).text,this::finish),new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));
        header.addView(Ui.heading(this,"手机控制测试台",20),new LinearLayout.LayoutParams(0,-2,1));page.addView(header);
        page.addView(Ui.text(this, "每次按下验证按钮，只授权一次短手势。离开本页、旋转或紧急停止后，需要重新授权。已派发手势可能仍在结束。", 13, Ui.MUTED));
        result = Ui.text(this, "等待操作；请先在系统设置开启无障碍服务。", 14, Ui.ACCENT);
        page.addView(result);
        Ui.button(this, page, "紧急停止", true, this::stop);

        LinearLayout observe = Ui.card(this, page, "01 / OBSERVE", "观察与截图");
        Ui.button(this, observe, "读取当前窗口元数据", false, this::observe);
        Ui.button(this, observe, "抓取测试窗口截图（Android 14+）", false, this::capture);
        Ui.button(this, observe, "清除截图", false, this::clearFrame);
        preview = new ImageView(this);
        preview.setContentDescription("本次测试窗口截图预览");
        preview.setAdjustViewBounds(true);
        preview.setMaxHeight(Ui.dp(this, 260));
        observe.addView(preview, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout tap = Ui.card(this, page, "02 / TAP", "单步点击复核");
        countLabel = Ui.text(this, "", 14, Ui.TEXT);
        tap.addView(countLabel);
        updateCount();
        target = Ui.button(this, tap, "点击目标区域", false, () -> { tapCount++; updateCount(); });
        Ui.button(this, tap, "授权并验证一次点击", false, this::verifyTap);

        LinearLayout swipe = Ui.card(this, page, "03 / SWIPE", "单步滑动复核");
        swipe.addView(Ui.text(this, "滚动区域的实际 scrollY 变化才算复核通过。", 12, Ui.MUTED));
        swipePad = new ScrollView(this);
        swipePad.setContentDescription("滑动验证区域");
        swipePad.setNestedScrollingEnabled(true);
        LinearLayout strips = Ui.column(this);
        for (int i = 1; i <= 8; i++) {
            TextView strip = Ui.text(this, "本机测试区域 " + i, 14, Ui.TEXT);
            strip.setGravity(Gravity.CENTER);
            strip.setBackgroundColor(i % 2 == 0 ? Ui.colors(this).raised : Ui.colors(this).surface);
            strips.addView(strip, new LinearLayout.LayoutParams(-1, Ui.dp(this, 64)));
        }
        swipePad.addView(strips);
        swipe.addView(swipePad, new LinearLayout.LayoutParams(-1, Ui.dp(this, 160)));
        Ui.button(this, swipe, "授权并验证一次上滑", false, this::verifySwipe);
        Ui.button(this, page, "返回诊断首页", false, this::finish);
        Ui.install(this, scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        XiyanAccessibilityService.setTestPadForeground(this, true);
    }
    @Override protected void onPause() {
        resumed = false;
        uiGeneration++;
        XiyanAccessibilityService.setTestPadForeground(this, false);
        handler.removeCallbacksAndMessages(null);
        clearFrame();
        super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("tapCount", tapCount);
        super.onSaveInstanceState(state);
    }
    private XiyanAccessibilityService service() {
        XiyanAccessibilityService service = XiyanAccessibilityService.getConnectedService();
        if (service == null) show("权限", "无障碍服务未连接，请返回首页打开系统设置。", false);
        return service;
    }
    private void show(String category, String text, boolean record) {
        if (!resumed) return;
        result.setText(text);
        if (record) diagnostics.record(category, text);
    }
    private void updateCount() { countLabel.setText(getString(R.string.tap_count, tapCount)); }
    private void observe() {
        XiyanAccessibilityService service = service();
        if (service == null) return;
        final long expected = uiGeneration;
        service.observe(response -> {
            if (!resumed || expected != uiGeneration) return;
            if (response.isSuccess()) {
                Observation observation = response.value;
                show("观察", "观察 #" + observation.id + " · " + observation.widthPx + " × "
                        + observation.heightPx + "\n节点 " + observation.nodeCount + " · 可点击 "
                        + observation.clickableCount + (observation.truncated ? " · 已达采样上限" : ""), false);
                diagnostics.record("观察", "成功读取本页窗口元数据");
            } else show("观察", response.message, true);
        });
    }
    private void capture() {
        XiyanAccessibilityService service = service();
        if (service == null) return;
        final long expected = uiGeneration;
        final long request = captureRequests.begin();
        service.captureScreenshot(response -> {
            if (!resumed || expected != uiGeneration) {
                if (response.isSuccess()) captureRequests.discard(response.value);
                return;
            }
            if (!response.isSuccess()) {
                if (captureRequests.isCurrent(request)) show("截图", response.message, true);
                return;
            }
            captureRequests.deliver(request, response.value, accepted -> {
                releaseDisplayedFrame();
                frame = accepted;
                preview.setImageBitmap(frame.bitmap);
                show("截图", "测试窗口截图已保留在内存，可点击清除。", true);
            });
        });
    }
    private void clearFrame() {
        captureRequests.cancel();
        releaseDisplayedFrame();
    }
    private void releaseDisplayedFrame() {
        if (preview != null) preview.setImageDrawable(null);
        if (frame != null) { frame.close(); frame = null; }
    }
    private void verifyTap() {
        XiyanAccessibilityService service = service();
        if (service == null) return;
        android.graphics.Rect visible = new android.graphics.Rect();
        if (!target.getGlobalVisibleRect(visible) || visible.height() < target.getHeight()) {
            show("点击", "请先把整个点击目标滚动到屏幕内。", false); return;
        }
        int[] location = new int[2];
        target.getLocationOnScreen(location);
        float x = location[0] + target.getWidth() / 2f;
        float y = location[1] + target.getHeight() / 2f;
        int before = tapCount;
        final long expected = uiGeneration;
        service.enableTestPadControl(armed -> {
            if (!resumed || expected != uiGeneration) return;
            if (!armed.isSuccess()) { show("点击", armed.message, true); return; }
            service.tap(armed.value, x, y, response -> {
                if (!resumed || expected != uiGeneration) return;
                if (!response.isSuccess()) { show("点击", response.message, true); return; }
                handler.postDelayed(() -> {
                    if (!resumed || expected != uiGeneration) return;
                    show("点击", tapCount == before + 1 ? "点击复核通过：目标计数恰好增加 1。"
                            : "手势已结束，目标计数不符，结果待核实。", true);
                }, 150);
            });
        });
    }
    private void verifySwipe() {
        XiyanAccessibilityService service = service();
        if (service == null) return;
        android.graphics.Rect visible = new android.graphics.Rect();
        if (!swipePad.getGlobalVisibleRect(visible) || visible.height() < swipePad.getHeight()) {
            show("滑动", "请先把整个滑动区域滚动到屏幕内。", false); return;
        }
        int[] location = new int[2];
        swipePad.getLocationOnScreen(location);
        float x = location[0] + swipePad.getWidth() / 2f;
        float fromY = location[1] + swipePad.getHeight() * 0.8f;
        float toY = location[1] + swipePad.getHeight() * 0.2f;
        int before = swipePad.getScrollY();
        final long expected = uiGeneration;
        service.enableTestPadControl(armed -> {
            if (!resumed || expected != uiGeneration) return;
            if (!armed.isSuccess()) { show("滑动", armed.message, true); return; }
            service.swipe(armed.value, x, fromY, x, toY, 250, response -> {
                if (!resumed || expected != uiGeneration) return;
                if (!response.isSuccess()) { show("滑动", response.message, true); return; }
                handler.postDelayed(() -> {
                    if (!resumed || expected != uiGeneration) return;
                    show("滑动", swipePad.getScrollY() > before ? "滑动复核通过：区域实际向下滚动。"
                            : "手势已结束，滚动位置无变化，结果待核实。", true);
                }, 250);
            });
        });
    }
    private void stop() {
        uiGeneration++;
        handler.removeCallbacksAndMessages(null);
        clearFrame();
        XiyanAccessibilityService service = XiyanAccessibilityService.getConnectedService();
        if (service != null) service.emergencyStop();
        show("停止", "后续动作授权已撤销；已派发的短手势可能仍在结束。", true);
    }
}
