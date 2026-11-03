// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.control;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

import io.xiyan.agent.BuildConfig;

/**
 * Local P0 probe, deliberately restricted to the application's foreground TestPad.
 * No node text, screenshots, or coordinates are logged or persisted by this service.
 * User consent in Android's accessibility settings is the only connection mechanism.
 */
public final class XiyanAccessibilityService extends AccessibilityService {
    public static final String OWN_PACKAGE = BuildConfig.APPLICATION_ID;
    public static final String TEST_PAD_ACTIVITY = "io.xiyan.agent.TestPadActivity";
    public static final long OBSERVATION_MAX_AGE_MS = 1_500;
    private static final long OPERATION_TIMEOUT_MS = 2_000;
    private static final int MAX_NODES = 256;
    private static final int MAX_DEPTH = 32;
    private static final long TREE_BUDGET_MS = 50;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArraySet<StateListener> LISTENERS =
            new CopyOnWriteArraySet<>();
    private static volatile XiyanAccessibilityService connectedService;
    private static volatile WeakReference<Activity> foregroundPad = new WeakReference<>(null);
    private static volatile State lastState = new State(false, false, false, 0,
            "无障碍服务未连接，请在系统设置中手动开启");

    // dispatch and revocation share this lock: after stop returns, no older generation
    // can enter dispatchGesture. Android cannot retract a gesture already accepted.
    private final Object gate = new Object();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong observationIds = new AtomicLong();
    private volatile boolean connected;
    private volatile boolean armed;
    private volatile String stateMessage = "等待系统连接";
    private Observation lastObservation;
    private Observation armedObservation;
    // A canceled/timed-out platform call stays quarantined until its callback arrives.
    // Do not start a second platform operation merely because its waiter was canceled.
    private Operation<?> operation;

    public enum Status {
        OK, DISCONNECTED, STOPPED, BUSY, NO_ACTIVE_WINDOW, WRONG_WINDOW,
        TEST_PAD_NOT_FOREGROUND, STALE_OBSERVATION, DISPLAY_CHANGED,
        OUT_OF_BOUNDS, INVALID_ARGUMENT, CANCELLED, UNCERTAIN, TIMEOUT,
        PLATFORM_REJECTED, SCREENSHOT_UNSUPPORTED, SCREENSHOT_FAILED, INTERNAL_ERROR
    }

    public static final class Result<T> {
        public final Status status;
        public final T value;
        public final String message;

        private Result(Status status, T value, String message) {
            this.status = status;
            this.value = value;
            this.message = message;
        }

        public boolean isSuccess() { return status == Status.OK; }
    }

    @FunctionalInterface
    public interface Callback<T> { void onResult(Result<T> result); }

    @FunctionalInterface
    public interface StateListener { void onStateChanged(State state); }

    public static final class State {
        public final boolean connected;
        public final boolean controlEnabled;
        public final boolean inFlight;
        public final long generation;
        public final String message;

        private State(boolean connected, boolean controlEnabled, boolean inFlight,
                      long generation, String message) {
            this.connected = connected;
            this.controlEnabled = controlEnabled;
            this.inFlight = inFlight;
            this.generation = generation;
            this.message = message;
        }
    }

    /** Contains counts and geometry only; never retains nodes, labels or editable text. */
    public static final class Observation {
        public final long id;
        public final long generation;
        public final long observedElapsedRealtimeMs;
        public final String packageName;
        public final int windowId;
        public final int displayId;
        public final int rotation;
        public final int widthPx;
        public final int heightPx;
        public final int nodeCount;
        public final int clickableCount;
        public final int editableCount;
        public final int visibleCount;
        public final boolean truncated;
        public final boolean testPadForeground;
        public final int rootLeft;
        public final int rootTop;
        public final int rootRight;
        public final int rootBottom;
        public final int windowLeft;
        public final int windowTop;
        public final int windowRight;
        public final int windowBottom;

        private Observation(long id, long generation, String packageName, int windowId,
                            int displayId, DisplayGeometry display, Counts counts,
                            boolean testPadForeground, Rect rootBounds, Rect windowBounds) {
            this.id = id;
            this.generation = generation;
            this.observedElapsedRealtimeMs = SystemClock.elapsedRealtime();
            this.packageName = packageName;
            this.windowId = windowId;
            this.displayId = displayId;
            this.rotation = display.rotation;
            this.widthPx = display.width;
            this.heightPx = display.height;
            this.nodeCount = counts.nodes;
            this.clickableCount = counts.clickable;
            this.editableCount = counts.editable;
            this.visibleCount = counts.visible;
            this.truncated = counts.truncated;
            this.testPadForeground = testPadForeground;
            this.rootLeft = rootBounds.left;
            this.rootTop = rootBounds.top;
            this.rootRight = rootBounds.right;
            this.rootBottom = rootBounds.bottom;
            this.windowLeft = windowBounds.left;
            this.windowTop = windowBounds.top;
            this.windowRight = windowBounds.right;
            this.windowBottom = windowBounds.bottom;
        }
    }

    /**
     * In-memory image ownership passes to the callback. Close after the ImageView has
     * released it. This service neither saves nor uploads the returned bitmap.
     * Window image dimensions are not a calibrated mapping to display coordinates.
     */
    public static final class ScreenshotFrame implements AutoCloseable {
        public final Bitmap bitmap;
        public final Observation observation;
        public final String captureScope = WindowCapturePolicy.SCOPE_WINDOW;
        public final int captureWindowId;
        public final int imageWidthPx;
        public final int imageHeightPx;
        public final long requestedElapsedRealtimeMs;
        public final long receivedElapsedRealtimeMs;
        // Android's screenshot timestamp uses uptimeMillis, not elapsedRealtime.
        public final long platformTimestampUptimeMillis;

        private ScreenshotFrame(Bitmap bitmap, Observation observation,
                                long requestedElapsedRealtimeMs, long receivedElapsedRealtimeMs,
                                long platformTimestampUptimeMillis) {
            this.bitmap = bitmap;
            this.observation = observation;
            this.captureWindowId = observation.windowId;
            this.imageWidthPx = bitmap.getWidth();
            this.imageHeightPx = bitmap.getHeight();
            this.requestedElapsedRealtimeMs = requestedElapsedRealtimeMs;
            this.receivedElapsedRealtimeMs = receivedElapsedRealtimeMs;
            this.platformTimestampUptimeMillis = platformTimestampUptimeMillis;
        }

        @Override public void close() {
            if (!bitmap.isRecycled()) bitmap.recycle();
        }
    }

    public static XiyanAccessibilityService getConnectedService() {
        XiyanAccessibilityService service = connectedService;
        return service != null && service.connected ? service : null;
    }

    public static State getConnectionState() { return lastState; }

    /** Call removeStateListener from Activity.onStop to release its reference. */
    public static void addStateListener(StateListener listener) {
        if (listener == null) return;
        LISTENERS.add(listener);
        MAIN.post(() -> { if (LISTENERS.contains(listener)) notifyListener(listener, lastState); });
    }

    public static void removeStateListener(StateListener listener) { LISTENERS.remove(listener); }

    /** TestPad must call this from onResume/onPause; setting foreground never arms control. */
    public static void setTestPadForeground(Activity activity, boolean foreground) {
        if (activity == null || !TEST_PAD_ACTIVITY.equals(activity.getClass().getName())
                || !OWN_PACKAGE.equals(activity.getPackageName())) return;
        if (foreground) {
            foregroundPad = new WeakReference<>(activity);
        } else if (foregroundPad.get() == activity) {
            foregroundPad = new WeakReference<>(null);
            XiyanAccessibilityService service = connectedService;
            if (service != null) service.revoke("测试页面已离开，控制已停止", false);
        }
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.packageNames = new String[] { OWN_PACKAGE };
            setServiceInfo(info);
        }
        synchronized (gate) {
            generation.incrementAndGet();
            connected = true;
            armed = false;
            stateMessage = "已连接；仅可在 P0 TestPad 手动启用单步控制";
        }
        connectedService = this;
        publishState();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // Event payloads and text are intentionally never retained or logged.
        if ((!armed && (operation == null || operation.terminal)) || event == null) return;
        Observation reference = operation == null ? armedObservation : operation.observation;
        if (reference == null) return;
        Result<Observation> current = inspect(false);
        if (!current.isSuccess() || !sameWindow(reference, current.value)
                || !current.value.testPadForeground) {
            revoke("当前窗口、显示方向或前台状态已变化，控制已停止", false);
        }
    }

    @Override public void onInterrupt() {
        // Interrupt is feedback interruption, not proof of disconnection.
        revoke("系统中断无障碍反馈，请重新手动启用", false);
    }

    @Override public boolean onUnbind(Intent intent) {
        disconnect();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        disconnect();
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        revoke("显示配置已变化，请重新观察并启用", false);
    }

    /** Metadata observation is available even if another app is active; it grants no control. */
    public void observe(Callback<Observation> callback) {
        long requestedGeneration = generation.get();
        onMain(() -> {
            if (requestedGeneration != generation.get()) {
                deliver(callback, failure(Status.CANCELLED, "观察请求已撤销"));
                return;
            }
            Result<Observation> result = inspect(true);
            if (requestedGeneration != generation.get()) {
                deliver(callback, failure(Status.CANCELLED, "观察请求已撤销"));
                return;
            }
            if (result.isSuccess()) {
                lastObservation = result.value;
                if (armed) armedObservation = result.value;
            }
            deliver(callback, result);
        });
    }

    /** A user button press may arm exactly one gesture, using a fresh observation. */
    public void enableTestPadControl(Callback<Observation> callback) {
        long requestedGeneration = generation.get();
        onMain(() -> {
            if (requestedGeneration != generation.get()) {
                deliver(callback, failure(Status.CANCELLED, "启用请求已撤销"));
                return;
            }
            if (!connected) {
                deliver(callback, failure(Status.DISCONNECTED, "无障碍服务未连接"));
                return;
            }
            if (operation != null) {
                deliver(callback, failure(Status.BUSY, "上一系统操作尚未结束，暂不能启用"));
                return;
            }
            final long enabledGeneration;
            synchronized (gate) {
                // Stop may run on a worker between the initial check and this lock.
                // Such an older queued enable must never revive control after stop.
                if (requestedGeneration != generation.get() || !connected) {
                    deliver(callback, failure(Status.CANCELLED, "启用请求已撤销"));
                    return;
                }
                enabledGeneration = generation.incrementAndGet();
                armed = false;
            }
            lastObservation = null;
            armedObservation = null;
            Result<Observation> result = inspect(true);
            if (result.isSuccess()) result = requireTestPad(result.value);
            synchronized (gate) {
                // A stop from another thread may have revoked this inspection.
                if (result.isSuccess() && (enabledGeneration != generation.get()
                        || result.value.generation != enabledGeneration)) {
                    result = failure(Status.CANCELLED, "启用期间收到停止请求");
                }
                if (result.isSuccess()) {
                    lastObservation = result.value;
                    armedObservation = result.value;
                    armed = true;
                    stateMessage = "已启用本次单步；观察有效期 1500 ms";
                } else stateMessage = result.message;
            }
            publishState();
            deliver(callback, result);
        });
    }

    public void tap(Observation observation, float x, float y, Callback<Void> callback) {
        gesture(observation, x, y, x, y, 80, callback);
    }

    /** Coordinates are physical screen coordinates, e.g. View.getLocationOnScreen(). */
    public void swipe(Observation observation, float startX, float startY,
                      float endX, float endY, long durationMs, Callback<Void> callback) {
        if (durationMs < 50 || durationMs > 500) {
            onMain(() -> deliver(callback,
                    failure(Status.INVALID_ARGUMENT, "P0 滑动时长须为 50–500 ms")));
            return;
        }
        gesture(observation, startX, startY, endX, endY, durationMs, callback);
    }

    /** Revokes synchronously; cannot retract a short gesture already accepted by Android. */
    public void emergencyStop() { revoke("紧急停止；需要再次手动启用才能派发", false); }

    public void cancelCurrentOperation() { revoke("当前操作已取消；需要重新手动启用", false); }

    /** Window-only TestPad preview. API29–33 have no fallback to display capture. */
    public void captureScreenshot(Callback<ScreenshotFrame> callback) {
        long requestedGeneration = generation.get();
        onMain(() -> {
            if (requestedGeneration != generation.get()) {
                deliver(callback, failure(Status.CANCELLED, "截图请求已撤销"));
                return;
            }
            if (!connected) {
                deliver(callback, failure(Status.DISCONNECTED, "无障碍服务未连接"));
                return;
            }
            if (Build.VERSION.SDK_INT < WindowCapturePolicy.MIN_API) {
                deliver(callback, failure(Status.SCREENSHOT_UNSUPPORTED,
                        "当前 P0 仅支持 Android 14（API34）及以上的本页窗口截图；低版本不执行整屏截图"));
                return;
            }
            if (operation != null) {
                deliver(callback, failure(Status.BUSY, "上一系统操作尚未结束"));
                return;
            }
            Result<Observation> observed = inspect(true);
            if (observed.isSuccess()) observed = requireTestPad(observed.value);
            if (!observed.isSuccess()) {
                deliver(callback, failure(observed.status, observed.message));
                return;
            }
            WindowCapturePolicy.Plan capturePlan = WindowCapturePolicy.plan(Build.VERSION.SDK_INT,
                    OWN_PACKAGE, observed.value.packageName, observed.value.testPadForeground,
                    observed.value.windowId, observed.value.displayId);
            if (capturePlan == null) {
                deliver(callback, failure(Status.WRONG_WINDOW, "无法确认当前 TestPad 窗口，未执行截图"));
                return;
            }
            synchronized (gate) { armed = false; }
            armedObservation = null;
            lastObservation = null;
            Operation<ScreenshotFrame> op = new Operation<>(callback, observed.value, false);
            operation = op;
            startTimeout(op);
            try {
                synchronized (gate) {
                    if (op.generation != requestedGeneration
                            || requestedGeneration != generation.get() || !connected) {
                        finish(op, failure(Status.CANCELLED, "截图请求已撤销"));
                        return;
                    }
                    takeScreenshotOfWindow(capturePlan.windowId, getMainExecutor(),
                            new TakeScreenshotCallback() {
                                @Override public void onSuccess(ScreenshotResult screenshot) {
                                    receiveScreenshot(op, screenshot);
                                }
                                @Override public void onFailure(int errorCode) {
                                    finish(op, failure(Status.SCREENSHOT_FAILED,
                                            "系统截图失败，错误码 " + errorCode
                                                    + "；受保护内容或系统限流须真机确认"));
                                }
                            });
                    op.platformAccepted = true;
                    stateMessage = "正在截取 TestPad 窗口；图像仅在内存中返回";
                }
                publishState();
            } catch (RuntimeException exception) {
                finish(op, failure(Status.SCREENSHOT_FAILED, "系统拒绝截图；请检查服务授权"));
            }
        });
    }

    private void gesture(Observation observation, float startX, float startY,
                         float endX, float endY, long durationMs, Callback<Void> callback) {
        long requestedGeneration = generation.get();
        onMain(() -> {
            Result<Void> invalid = validateGesture(observation, requestedGeneration,
                    startX, startY, endX, endY);
            if (invalid != null) {
                deliver(callback, invalid);
                return;
            }
            Path path = new Path();
            path.moveTo(startX, startY);
            if (startX != endX || startY != endY) path.lineTo(endX, endY);
            GestureDescription.Builder builder = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs));
            if (Build.VERSION.SDK_INT >= 30) builder.setDisplayId(Display.DEFAULT_DISPLAY);
            Operation<Void> op = new Operation<>(callback, observation, true);
            synchronized (gate) {
                // This is the final generation check immediately around the system dispatch.
                if (!armed || !connected || generation.get() != requestedGeneration) {
                    deliver(callback, failure(Status.CANCELLED, "派发前授权已撤销"));
                    return;
                }
                armed = false; // A single explicit enable is consumed before dispatch.
                armedObservation = null;
                lastObservation = null;
                operation = op;
                startTimeout(op);
                try {
                    op.platformAccepted = dispatchGesture(builder.build(), new GestureResultCallback() {
                        @Override public void onCompleted(GestureDescription gestureDescription) {
                            finish(op, success(null, "系统手势已完成；请以 TestPad 计数验证效果"));
                        }
                        @Override public void onCancelled(GestureDescription gestureDescription) {
                            finish(op, failure(Status.UNCERTAIN,
                                    "系统取消手势；可能已产生部分触点，请检查 TestPad"));
                        }
                    }, MAIN);
                    if (!op.platformAccepted) {
                        finish(op, failure(Status.PLATFORM_REJECTED, "系统未接受手势"));
                        return;
                    }
                    stateMessage = "本次手势已派发，等待系统回调";
                } catch (RuntimeException exception) {
                    finish(op, failure(Status.PLATFORM_REJECTED, "系统拒绝手势；请检查服务授权"));
                    return;
                }
            }
            publishState();
        });
    }

    private Result<Void> validateGesture(Observation observation, long requestedGeneration,
                                         float startX, float startY, float endX, float endY) {
        if (!connected) return failure(Status.DISCONNECTED, "无障碍服务未连接");
        if (operation != null) return failure(Status.BUSY, "上一系统操作尚未结束");
        if (!armed || requestedGeneration != generation.get()) {
            return failure(Status.STOPPED, "请先手动启用本次单步控制");
        }
        long age = observation == null ? Long.MAX_VALUE
                : SystemClock.elapsedRealtime() - observation.observedElapsedRealtimeMs;
        if (observation == null || observation != armedObservation || observation != lastObservation
                || observation.generation != generation.get() || age < 0
                || age > OBSERVATION_MAX_AGE_MS) {
            revoke("观察已过期，请重新手动启用", false);
            return failure(Status.STALE_OBSERVATION, "观察已过期或不属于本次启用");
        }
        Result<Observation> current = inspect(false);
        if (current.isSuccess()) current = requireTestPad(current.value);
        if (!current.isSuccess()) {
            revoke(current.message, false);
            return failure(current.status, current.message);
        }
        if (!sameWindow(observation, current.value)) {
            revoke("窗口或显示配置已变化", false);
            return failure(Status.DISPLAY_CHANGED, "窗口或显示配置已变化，请重新观察");
        }
        if (!contains(observation, startX, startY) || !contains(observation, endX, endY)) {
            return failure(Status.OUT_OF_BOUNDS, "坐标必须位于当前 TestPad 窗口内");
        }
        if (!isGestureAreaUncovered(observation, startX, startY, endX, endY)) {
            revoke("目标区域上方存在其他窗口，控制已停止", false);
            return failure(Status.WRONG_WINDOW, "目标区域被其他窗口遮挡，请关闭遮挡后重新启用");
        }
        return null;
    }

    /** Fail closed on overlays that could receive the intended TestPad touch. */
    private boolean isGestureAreaUncovered(Observation observation, float startX, float startY,
                                           float endX, float endY) {
        List<AccessibilityWindowInfo> windows = null;
        try {
            windows = getWindows(); // This API reports the default display only, including API29.
            int targetLayer = Integer.MIN_VALUE;
            for (AccessibilityWindowInfo window : windows) {
                if (window.getId() == observation.windowId) targetLayer = window.getLayer();
            }
            if (targetLayer == Integer.MIN_VALUE) return false;
            Rect touchArea = new Rect((int) Math.floor(Math.min(startX, endX)),
                    (int) Math.floor(Math.min(startY, endY)),
                    (int) Math.ceil(Math.max(startX, endX)) + 1,
                    (int) Math.ceil(Math.max(startY, endY)) + 1);
            for (AccessibilityWindowInfo window : windows) {
                if (window.getLayer() <= targetLayer || window.getId() == observation.windowId) continue;
                Rect bounds = new Rect();
                window.getBoundsInScreen(bounds);
                if (Rect.intersects(touchArea, bounds)) return false;
            }
            return true;
        } catch (RuntimeException exception) {
            return false;
        } finally {
            if (windows != null) for (AccessibilityWindowInfo window : windows) window.recycle();
        }
    }

    private Result<Observation> requireTestPad(Observation observation) {
        if (!OWN_PACKAGE.equals(observation.packageName)) {
            return failure(Status.WRONG_WINDOW, "P0 只允许控制自身 TestPad，当前包不匹配");
        }
        if (!observation.testPadForeground) {
            return failure(Status.TEST_PAD_NOT_FOREGROUND,
                    "请保持 P0 TestPad 解锁、前台且拥有窗口焦点");
        }
        if (observation.displayId != Display.DEFAULT_DISPLAY) {
            return failure(Status.DISPLAY_CHANGED, "P0 仅支持默认显示器");
        }
        return success(observation, "TestPad 当前窗口已确认");
    }

    private Result<Observation> inspect(boolean countTree) {
        if (!connected) return failure(Status.DISCONNECTED, "无障碍服务未连接");
        long observedGeneration = generation.get();
        AccessibilityNodeInfo root = null;
        AccessibilityWindowInfo window = null;
        try {
            DisplayManager manager = getSystemService(DisplayManager.class);
            Display display = manager == null ? null : manager.getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) return failure(Status.DISPLAY_CHANGED, "默认显示器不可用");
            Point size = new Point();
            display.getRealSize(size);
            if (size.x <= 0 || size.y <= 0) return failure(Status.DISPLAY_CHANGED, "显示尺寸无效");
            DisplayGeometry geometry = new DisplayGeometry(size.x, size.y, display.getRotation());
            root = getRootInActiveWindow();
            if (root == null) return failure(Status.NO_ACTIVE_WINDOW, "当前没有可读取的活动窗口");
            CharSequence packageValue = root.getPackageName();
            String packageName = packageValue == null ? "" : packageValue.toString();
            Rect rootBounds = new Rect();
            root.getBoundsInScreen(rootBounds);
            Rect windowBounds = new Rect();
            int displayId = Display.DEFAULT_DISPLAY;
            window = root.getWindow();
            if (window != null) window.getBoundsInScreen(windowBounds);
            if (Build.VERSION.SDK_INT >= 30) {
                // getRootInActiveWindow may return a root on another logical display.
                displayId = window == null ? -1 : window.getDisplayId();
            }
            boolean pad = isPadFocused() && OWN_PACKAGE.equals(packageName)
                    && displayId == Display.DEFAULT_DISPLAY && root.isVisibleToUser()
                    && window != null && root.getWindowId() == window.getId()
                    && window.isActive() && window.isFocused();
            Counts counts = countTree ? countNodes(root) : new Counts();
            return success(new Observation(observationIds.incrementAndGet(), observedGeneration,
                    packageName, root.getWindowId(), displayId, geometry, counts, pad,
                    rootBounds, windowBounds),
                    "已读取窗口元数据；未读取节点文本");
        } catch (RuntimeException exception) {
            return failure(Status.NO_ACTIVE_WINDOW, "读取活动窗口失败，请确认服务授权和前台页面");
        } finally {
            if (window != null) window.recycle();
            if (root != null) root.recycle();
        }
    }

    private boolean isPadFocused() {
        Activity activity = foregroundPad.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || !activity.hasWindowFocus()) return false;
        Display display = activity.getWindowManager().getDefaultDisplay();
        if (display.getDisplayId() != Display.DEFAULT_DISPLAY) return false;
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        PowerManager power = getSystemService(PowerManager.class);
        return keyguard != null && !keyguard.isKeyguardLocked()
                && power != null && power.isInteractive();
    }

    @SuppressWarnings("deprecation")
    private Counts countNodes(AccessibilityNodeInfo root) {
        Counts counts = new Counts();
        long deadline = SystemClock.elapsedRealtime() + TREE_BUDGET_MS;
        ArrayDeque<NodeAtDepth> pending = new ArrayDeque<>();
        // The caller owns root; each child is owned and recycled by this traversal.
        pending.push(new NodeAtDepth(root, 0, false));
        try {
            while (!pending.isEmpty()) {
                if (counts.nodes >= MAX_NODES || SystemClock.elapsedRealtime() > deadline) {
                    counts.truncated = true;
                    break;
                }
                NodeAtDepth item = pending.pop();
                try {
                    counts.nodes++;
                    if (item.node.isClickable()) counts.clickable++;
                    if (item.node.isEditable()) counts.editable++;
                    if (item.node.isVisibleToUser()) counts.visible++;
                    int children = item.node.getChildCount();
                    if (item.depth >= MAX_DEPTH) {
                        if (children > 0) counts.truncated = true;
                        continue;
                    }
                    int remaining = MAX_NODES - counts.nodes - pending.size();
                    int take = Math.min(children, remaining);
                    if (take < children) counts.truncated = true;
                    for (int index = 0; index < take; index++) {
                        if (SystemClock.elapsedRealtime() > deadline) {
                            counts.truncated = true;
                            break;
                        }
                        AccessibilityNodeInfo child = item.node.getChild(index);
                        if (child != null) pending.push(new NodeAtDepth(child, item.depth + 1, true));
                    }
                } finally {
                    if (item.owned) item.node.recycle();
                }
            }
        } finally {
            while (!pending.isEmpty()) {
                NodeAtDepth item = pending.pop();
                if (item.owned) item.node.recycle();
            }
        }
        return counts;
    }

    // This native-SDK-only module intentionally avoids an AndroidX annotation dependency.
    // The sole caller is an API34 window-capture callback behind the SDK check above.
    @android.annotation.SuppressLint("UseRequiresApi")
    @android.annotation.TargetApi(34)
    private void receiveScreenshot(Operation<ScreenshotFrame> op, ScreenshotResult screenshot) {
        HardwareBuffer buffer = null;
        Bitmap hardwareBitmap = null;
        Bitmap copy = null;
        try {
            buffer = screenshot.getHardwareBuffer();
            if (!isCurrent(op)) {
                finish(op, failure(Status.CANCELLED, "截图请求已撤销，图像已丢弃"));
                return;
            }
            long received = SystemClock.elapsedRealtime();
            Result<Observation> now = inspect(false);
            if (!now.isSuccess() || !now.value.testPadForeground
                    || !sameWindow(op.observation, now.value)) {
                finish(op, failure(Status.CANCELLED, "截图期间窗口已变化，图像已丢弃"));
                return;
            }
            if (!WindowCapturePolicy.isImageSizeAllowed(buffer.getWidth(), buffer.getHeight())) {
                finish(op, failure(Status.SCREENSHOT_FAILED, "截图超过 P0 内存尺寸限制"));
                return;
            }
            hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.getColorSpace());
            if (hardwareBitmap == null) {
                finish(op, failure(Status.SCREENSHOT_FAILED, "系统截图缓冲区无法转换"));
                return;
            }
            copy = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
            // The window API captures its Surface, whose dimensions need not equal root
            // bounds or the display. The image is a preview, never a gesture coordinate map.
            if (copy == null || !WindowCapturePolicy.isImageSizeAllowed(copy.getWidth(), copy.getHeight())) {
                finish(op, failure(Status.SCREENSHOT_FAILED, "窗口截图尺寸无效，图像已丢弃"));
                return;
            }
            ScreenshotFrame frame = new ScreenshotFrame(copy, op.observation,
                    op.startedElapsedRealtimeMs, received, screenshot.getTimestamp());
            copy = null; // ownership moves into frame / callback
            finish(op, success(frame, "TestPad 窗口截图仅在内存中；请在使用完毕后 close"));
        } catch (RuntimeException | OutOfMemoryError exception) {
            finish(op, failure(Status.SCREENSHOT_FAILED, "截图复制失败，图像已释放"));
        } finally {
            if (copy != null) copy.recycle();
            if (hardwareBitmap != null) hardwareBitmap.recycle();
            if (buffer != null) buffer.close();
            // Even a canceled/late callback must release the platform operation slot.
            release(op);
        }
    }

    private void startTimeout(Operation<?> op) {
        op.timeout = () -> {
            if (operation != op || op.terminal) return;
            synchronized (gate) {
                if (op.generation != generation.get()) return; // a stop cleanup is already queued
                generation.incrementAndGet();
                armed = false;
                stateMessage = "操作超时，结果未确认；等待系统回调前禁止继续派发";
            }
            lastObservation = null;
            armedObservation = null;
            terminateWaiter(op, Status.TIMEOUT, stateMessage);
            publishState();
        };
        MAIN.postDelayed(op.timeout, OPERATION_TIMEOUT_MS);
    }

    private void revoke(String message, boolean disconnecting) {
        final long revokedGeneration;
        synchronized (gate) {
            revokedGeneration = generation.incrementAndGet();
            armed = false;
            if (disconnecting) connected = false;
            stateMessage = message;
        }
        onMain(() -> {
            if (generation.get() == revokedGeneration) {
                lastObservation = null;
                armedObservation = null;
            }
            if (operation != null && operation.generation < revokedGeneration) {
                Operation<?> op = operation;
                terminateWaiter(op, op.gesture && op.platformAccepted
                                ? Status.UNCERTAIN : Status.CANCELLED,
                        op.gesture && op.platformAccepted
                                ? message + "；已进入系统的短手势可能已生效，请检查 TestPad" : message);
                if (disconnecting || !op.platformAccepted) release(op);
            }
            publishState();
        });
    }

    private void disconnect() {
        if (connectedService == this) connectedService = null;
        revoke("无障碍服务已断开；旧操作已撤销，重新连接后须手动启用", true);
    }

    private <T> void finish(Operation<T> op, Result<T> result) {
        final boolean current;
        synchronized (gate) {
            current = isCurrent(op);
            if (current) {
                op.terminal = true;
                stateMessage = result.message;
            }
        }
        if (!current) {
            closeValue(result.value);
            if (operation == op && !op.terminal) {
                terminateWaiter(op, op.gesture && op.platformAccepted
                                ? Status.UNCERTAIN : Status.CANCELLED,
                        op.gesture && op.platformAccepted
                                ? "操作已撤销；已进入系统的手势效果须在 TestPad 确认" : "操作已撤销");
            }
            release(op);
            return;
        }
        if (op.timeout != null) MAIN.removeCallbacks(op.timeout);
        release(op);
        Callback<T> callback = op.callback;
        op.callback = null;
        deliver(callback, result);
    }

    private void terminateWaiter(Operation<?> op, Status status, String message) {
        if (op.terminal) return;
        op.terminal = true;
        if (op.timeout != null) MAIN.removeCallbacks(op.timeout);
        deliverTerminated(op, status, message);
    }

    private <T> void deliverTerminated(Operation<T> op, Status status, String message) {
        Callback<T> callback = op.callback;
        op.callback = null;
        deliver(callback, failure(status, message));
    }

    private boolean isCurrent(Operation<?> op) {
        return operation == op && !op.terminal && connected && op.generation == generation.get();
    }

    private void release(Operation<?> op) {
        if (operation != op) return;
        operation = null;
        if (op.timeout != null) MAIN.removeCallbacks(op.timeout);
        publishState();
    }

    private void publishState() {
        // A late callback from an old service instance must not replace a newer state.
        if (connectedService != null && connectedService != this) return;
        State state = new State(connected, armed, operation != null, generation.get(), stateMessage);
        lastState = state;
        for (StateListener listener : LISTENERS) notifyListener(listener, state);
    }

    private static void notifyListener(StateListener listener, State state) {
        try { listener.onStateChanged(state); } catch (RuntimeException ignored) { /* no UI payload logs */ }
    }

    private static void onMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) runnable.run();
        else MAIN.post(runnable);
    }

    private static boolean contains(Observation observation, float x, float y) {
        return Float.isFinite(x) && Float.isFinite(y) && x >= 0 && y >= 0
                && x < observation.widthPx && y < observation.heightPx
                && x >= observation.rootLeft && x < observation.rootRight
                && y >= observation.rootTop && y < observation.rootBottom;
    }

    private static boolean sameWindow(Observation first, Observation second) {
        return first.windowId == second.windowId && first.displayId == second.displayId
                && first.rotation == second.rotation && first.widthPx == second.widthPx
                && first.heightPx == second.heightPx && first.packageName.equals(second.packageName)
                && first.rootLeft == second.rootLeft && first.rootTop == second.rootTop
                && first.rootRight == second.rootRight && first.rootBottom == second.rootBottom
                && first.windowLeft == second.windowLeft && first.windowTop == second.windowTop
                && first.windowRight == second.windowRight && first.windowBottom == second.windowBottom;
    }

    private static <T> Result<T> success(T value, String message) {
        return new Result<>(Status.OK, value, message);
    }

    private static <T> Result<T> failure(Status status, String message) {
        return new Result<>(status, null, message);
    }

    private static <T> void deliver(Callback<T> callback, Result<T> result) {
        if (callback == null) { closeValue(result.value); return; }
        try { callback.onResult(result); } catch (RuntimeException ignored) { closeValue(result.value); }
    }

    private static void closeValue(Object value) {
        if (value instanceof ScreenshotFrame) ((ScreenshotFrame) value).close();
    }

    private static final class DisplayGeometry {
        final int width;
        final int height;
        final int rotation;
        DisplayGeometry(int width, int height, int rotation) {
            this.width = width; this.height = height; this.rotation = rotation;
        }
    }

    private static final class Counts {
        int nodes;
        int clickable;
        int editable;
        int visible;
        boolean truncated;
    }

    private static final class NodeAtDepth {
        final AccessibilityNodeInfo node;
        final int depth;
        final boolean owned;
        NodeAtDepth(AccessibilityNodeInfo node, int depth, boolean owned) {
            this.node = node; this.depth = depth; this.owned = owned;
        }
    }

    private static final class Operation<T> {
        Callback<T> callback;
        final Observation observation;
        final long generation;
        final long startedElapsedRealtimeMs = SystemClock.elapsedRealtime();
        final boolean gesture;
        boolean platformAccepted;
        boolean terminal;
        Runnable timeout;
        Operation(Callback<T> callback, Observation observation, boolean gesture) {
            this.callback = callback;
            this.observation = observation;
            this.generation = observation.generation;
            this.gesture = gesture;
        }
    }
}
