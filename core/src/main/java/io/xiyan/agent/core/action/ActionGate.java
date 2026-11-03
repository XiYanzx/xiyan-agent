package io.xiyan.agent.core.action;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * P0 设备动作安全门。每台设备的两个引擎必须共享同一个实例，所有派发都经过本类。
 *
 * <p>这不是任务监督器。Android 负责真实截图、窗口/画面 epoch、权限事件、后置条件、
 * 平台手势及取消回调；本类只维护进程内授权和串行性，不会在重启后恢复控制权。
 * Dispatcher 必须立即提交并返回，不得等待异步回调、网络或持有其他执行器锁。
 * 检查和平台提交使用同一锁，因此 stop 返回后旧代次不能再提交新动作。
 * 系统已经接收的动作无法由本类撤回；停止后仍保留其占位，直到真实终止回调。
 */
public final class ActionGate {
    public enum Reason {
        NONE, CONTROL_UNAVAILABLE, DEVICE_BUSY, STALE_LEASE, NO_SCREEN,
        SCREEN_CHANGED, NO_OBSERVATION, OBSERVATION_UNUSABLE, OBSERVATION_EXPIRED,
        OBSERVATION_FROM_FUTURE, OBSERVATION_REUSED, OBSERVATION_MISMATCH,
        ACTION_EXPIRED, INVALID_SEQUENCE, STALE_TOKEN, OUT_OF_ORDER,
        DISPATCH_REJECTED, DISPATCH_UNCERTAIN, STALE_DISPATCH, RESULT_UNVERIFIED,
        RESULT_BEFORE_DISPATCH, ACTION_CANCELLED, USER_STOP, OBSERVATION_INVALIDATED
    }

    private final MonotonicClock clock;
    private final long maxObservationAgeMillis;
    private final ArrayDeque<ActionToken> pending = new ArrayDeque<>();
    private final Set<String> usedObservationIds = new HashSet<>();
    private long generation;
    private long lastSequence;
    private boolean controlAvailable;
    private boolean submitting;
    private Lease owner;
    private ScreenState screen;
    private Observation latestObservation;
    private DispatchHandle inFlight;
    private Reason lastStopReason = Reason.NONE;

    public ActionGate(MonotonicClock clock, long maxObservationAgeMillis) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxObservationAgeMillis <= 0) {
            throw new IllegalArgumentException("观察最大时效必须大于零");
        }
        this.maxObservationAgeMillis = maxObservationAgeMillis;
    }

    /** 平台必须在无障碍/捕获能力丢失、锁屏或服务断开时及时传入 false。 */
    public synchronized void setControlAvailable(boolean available) {
        controlAvailable = available;
        if (!available) {
            screen = null;
            invalidateLocked(Reason.CONTROL_UNAVAILABLE);
        }
    }

    /**
     * 更新平台当前屏幕指纹。epoch 必须覆盖同窗口内的重要画面变化，不能只用窗口 ID。
     * 窗口、epoch、尺寸或方向任意变化，任务均停止，需重新授权和观察。
     */
    public synchronized void updateScreen(ScreenState currentScreen) {
        Objects.requireNonNull(currentScreen, "currentScreen");
        if (screen != null && !screen.equals(currentScreen)) {
            screen = currentScreen;
            invalidateLocked(Reason.SCREEN_CHANGED);
        } else {
            screen = currentScreen;
        }
    }

    /** 仅在用户明确开启控制时调用；任务 ID 相同也不能重复取得设备租约。 */
    public synchronized Lease acquire(String taskId) {
        requireText(taskId, "taskId");
        if (!controlAvailable) throw rejected(Reason.CONTROL_UNAVAILABLE);
        if (owner != null || inFlight != null || submitting) throw rejected(Reason.DEVICE_BUSY);
        generation = Math.incrementExact(generation);
        owner = new Lease(this, taskId, generation);
        lastSequence = 0;
        latestObservation = null;
        pending.clear();
        usedObservationIds.clear();
        lastStopReason = Reason.NONE;
        return owner;
    }

    /** 保存平台产生的完整观察；不可识别、过期、重复或坐标系不一致时停止该任务。 */
    public synchronized void recordObservation(Lease lease, Observation observation) {
        requireLease(lease);
        Objects.requireNonNull(observation, "observation");
        Reason reason = observationFailure(observation, now());
        if (reason == Reason.NONE && usedObservationIds.contains(observation.id)) {
            reason = Reason.OBSERVATION_REUSED;
        }
        if (reason != Reason.NONE) failTask(reason);
        latestObservation = observation;
        usedObservationIds.add(observation.id);
        // 旧帧的动作不能借新截图继续执行。
        pending.clear();
    }

    /** 排队不是授权执行；实际派发和结果回调还会再次检查全部前置条件。 */
    public synchronized ActionToken enqueue(Lease lease, ActionRequest request) {
        requireLease(lease);
        Objects.requireNonNull(request, "request");
        if (latestObservation == null) throw rejected(Reason.NO_OBSERVATION);
        Reason reason = observationFailure(latestObservation, now());
        if (reason != Reason.NONE) failTask(reason);
        if (!latestObservation.id.equals(request.observationId)) {
            throw rejected(Reason.OBSERVATION_MISMATCH);
        }
        if (request.expiresAtMillis <= now()) failTask(Reason.ACTION_EXPIRED);
        if (request.sequence <= lastSequence) throw rejected(Reason.INVALID_SEQUENCE);
        ActionToken token = new ActionToken(this, lease, request, latestObservation);
        pending.addLast(token);
        lastSequence = request.sequence;
        return token;
    }

    /**
     * 在锁内执行最后检查及非阻塞平台提交。false 仅用于平台明确没有接收动作。
     * 若提交抛异常，执行结果不确定：撤销任务但保留占位，由终止回调释放。
     */
    public synchronized DispatchHandle dispatch(ActionToken token, Dispatcher dispatcher) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        requireToken(token);
        if (inFlight != null || submitting) throw rejected(Reason.DEVICE_BUSY);
        if (pending.peekFirst() != token) throw rejected(Reason.OUT_OF_ORDER);
        Reason reason = actionFailure(token, now());
        if (reason != Reason.NONE) failTask(reason);
        pending.removeFirst();
        DispatchHandle handle = new DispatchHandle(this, token, now());
        inFlight = handle;
        submitting = true;
        boolean accepted;
        try {
            accepted = dispatcher.dispatch(handle);
        } catch (RuntimeException failure) {
            invalidateLocked(Reason.DISPATCH_UNCERTAIN);
            throw new GateRejectedException(Reason.DISPATCH_UNCERTAIN, handle, failure);
        } finally {
            submitting = false;
        }
        if (!accepted) {
            if (inFlight == handle) inFlight = null;
            invalidateLocked(Reason.DISPATCH_REJECTED);
            throw new GateRejectedException(Reason.DISPATCH_REJECTED, handle, null);
        }
        return handle;
    }

    /**
     * 仅在平台动作已经结束且取得真实新观察后调用。成功表示平台后置条件被调用方确认，
     * 不表示模型或本类独立识别了画面；旧任务的迟到回调只释放自身占位。
     */
    public synchronized Confirmation complete(DispatchHandle handle, Verification verification) {
        if (!ownsHandle(handle) || inFlight != handle) {
            return new Confirmation(false, Reason.STALE_DISPATCH);
        }
        Objects.requireNonNull(verification, "verification");
        Reason reason = actionFailure(handle.token, now());
        if (reason == Reason.NONE) reason = observationFailure(verification.observation, now());
        if (reason == Reason.NONE
                && verification.observation.capturedAtMillis < handle.dispatchedAtMillis) {
            reason = Reason.RESULT_BEFORE_DISPATCH;
        }
        if (reason == Reason.NONE && usedObservationIds.contains(verification.observation.id)) {
            reason = Reason.OBSERVATION_REUSED;
        }
        if (reason == Reason.NONE && !verification.postconditionSatisfied) {
            reason = Reason.RESULT_UNVERIFIED;
        }
        inFlight = null;
        if (reason != Reason.NONE) {
            if (owner == handle.token.lease) invalidateLocked(reason);
            return new Confirmation(false, reason);
        }
        latestObservation = verification.observation;
        usedObservationIds.add(verification.observation.id);
        pending.clear();
        return new Confirmation(true, Reason.NONE);
    }

    /** 只用于平台已经确认动作取消/终止的回调，调用它不能代替真正的平台取消。 */
    public synchronized boolean acknowledgeCancelled(DispatchHandle handle) {
        if (!ownsHandle(handle) || inFlight != handle) return false;
        inFlight = null;
        if (owner == handle.token.lease) invalidateLocked(Reason.ACTION_CANCELLED);
        return true;
    }

    public synchronized void stop() { invalidateLocked(Reason.USER_STOP); }

    public synchronized void invalidateObservation() {
        invalidateLocked(Reason.OBSERVATION_INVALIDATED);
    }

    public synchronized boolean isLeaseActive(Lease lease) { return leaseFailure(lease) == Reason.NONE; }
    public synchronized int getPendingCount() { return pending.size(); }
    public synchronized boolean hasInFlightAction() { return inFlight != null; }
    public synchronized long getGeneration() { return generation; }
    public synchronized Reason getLastStopReason() { return lastStopReason; }

    private long now() {
        long value = clock.nowMillis();
        if (value < 0) throw new IllegalStateException("单调时钟不能为负值");
        return value;
    }

    private Reason leaseFailure(Lease lease) {
        if (!controlAvailable) return Reason.CONTROL_UNAVAILABLE;
        if (lease == null || lease.gate != this || owner != lease || lease.generation != generation) {
            return Reason.STALE_LEASE;
        }
        return Reason.NONE;
    }

    private void requireLease(Lease lease) {
        Reason reason = leaseFailure(lease);
        if (reason != Reason.NONE) throw rejected(reason);
    }

    private void requireToken(ActionToken token) {
        if (token == null || token.gate != this) throw rejected(Reason.STALE_TOKEN);
        requireLease(token.lease);
        if (!pending.contains(token)) throw rejected(Reason.STALE_TOKEN);
    }

    private Reason observationFailure(Observation observation, long currentTime) {
        if (screen == null) return Reason.NO_SCREEN;
        if (!observation.usable) return Reason.OBSERVATION_UNUSABLE;
        if (!screen.equals(observation.screen)) return Reason.SCREEN_CHANGED;
        if (observation.capturedAtMillis > currentTime) return Reason.OBSERVATION_FROM_FUTURE;
        if (currentTime - observation.capturedAtMillis > maxObservationAgeMillis) {
            return Reason.OBSERVATION_EXPIRED;
        }
        return Reason.NONE;
    }

    private Reason actionFailure(ActionToken token, long currentTime) {
        Reason reason = leaseFailure(token.lease);
        if (reason != Reason.NONE) return reason;
        reason = observationFailure(token.observation, currentTime);
        if (reason != Reason.NONE) return reason;
        if (latestObservation != token.observation) return Reason.OBSERVATION_MISMATCH;
        if (token.request.expiresAtMillis <= currentTime) return Reason.ACTION_EXPIRED;
        return Reason.NONE;
    }

    private void invalidateLocked(Reason reason) {
        generation = Math.incrementExact(generation);
        owner = null;
        latestObservation = null;
        pending.clear();
        usedObservationIds.clear();
        lastStopReason = reason;
    }

    private void failTask(Reason reason) {
        invalidateLocked(reason);
        throw rejected(reason);
    }

    private boolean ownsHandle(DispatchHandle handle) { return handle != null && handle.gate == this; }
    private static GateRejectedException rejected(Reason reason) {
        return new GateRejectedException(reason, null, null);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " 不能为空");
        return value;
    }

    @FunctionalInterface
    public interface Dispatcher { boolean dispatch(DispatchHandle handle); }

    public static final class GateRejectedException extends IllegalStateException {
        private final Reason reason;
        private final DispatchHandle handle;
        private GateRejectedException(Reason reason, DispatchHandle handle, Throwable cause) {
            super("动作被安全门拒绝：" + reason, cause);
            this.reason = reason;
            this.handle = handle;
        }
        public Reason getReason() { return reason; }
        /** 仅提交失败/不确定时存在，供平台回调关联，不可据此重发动作。 */
        public DispatchHandle getHandle() { return handle; }
    }

    public static final class Lease {
        private final ActionGate gate;
        private final String taskId;
        private final long generation;
        private Lease(ActionGate gate, String taskId, long generation) {
            this.gate = gate; this.taskId = taskId; this.generation = generation;
        }
        public String getTaskId() { return taskId; }
        public long getGeneration() { return generation; }
    }

    public static final class ScreenState {
        private final String windowId;
        private final long screenEpoch;
        private final int width;
        private final int height;
        private final int rotationDegrees;
        public ScreenState(String windowId, long screenEpoch, int width, int height, int rotationDegrees) {
            this.windowId = requireText(windowId, "windowId");
            if (screenEpoch < 0 || width <= 0 || height <= 0) {
                throw new IllegalArgumentException("画面 epoch 和尺寸无效");
            }
            if (rotationDegrees != 0 && rotationDegrees != 90 && rotationDegrees != 180 && rotationDegrees != 270) {
                throw new IllegalArgumentException("方向必须为 0、90、180 或 270 度");
            }
            this.screenEpoch = screenEpoch; this.width = width; this.height = height;
            this.rotationDegrees = rotationDegrees;
        }
        public String getWindowId() { return windowId; }
        public long getScreenEpoch() { return screenEpoch; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public int getRotationDegrees() { return rotationDegrees; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof ScreenState)) return false;
            ScreenState value = (ScreenState) other;
            return windowId.equals(value.windowId) && screenEpoch == value.screenEpoch
                    && width == value.width && height == value.height && rotationDegrees == value.rotationDegrees;
        }
        @Override public int hashCode() {
            return Objects.hash(windowId, screenEpoch, width, height, rotationDegrees);
        }
    }

    public static final class Observation {
        private final String id;
        private final ScreenState screen;
        private final long capturedAtMillis;
        private final boolean usable;
        public Observation(String id, ScreenState screen, long capturedAtMillis, boolean usable) {
            this.id = requireText(id, "observationId");
            this.screen = Objects.requireNonNull(screen, "screen");
            if (capturedAtMillis < 0) throw new IllegalArgumentException("捕获时间不能为负值");
            this.capturedAtMillis = capturedAtMillis; this.usable = usable;
        }
        public String getId() { return id; }
        public ScreenState getScreen() { return screen; }
        public long getCapturedAtMillis() { return capturedAtMillis; }
        public boolean isUsable() { return usable; }
    }

    public static final class ActionRequest {
        private final long sequence;
        private final String observationId;
        private final long expiresAtMillis;
        private final String kind;
        /** 参数/坐标由平台适配器校验并绑定此 token，kind 仅用于可读审计。 */
        public ActionRequest(long sequence, String observationId, long expiresAtMillis, String kind) {
            if (sequence <= 0 || expiresAtMillis < 0) throw new IllegalArgumentException("序号或有效期无效");
            this.sequence = sequence; this.observationId = requireText(observationId, "observationId");
            this.expiresAtMillis = expiresAtMillis; this.kind = requireText(kind, "kind");
        }
        public long getSequence() { return sequence; }
        public String getObservationId() { return observationId; }
        public long getExpiresAtMillis() { return expiresAtMillis; }
        public String getKind() { return kind; }
    }

    public static final class ActionToken {
        private final ActionGate gate;
        private final Lease lease;
        private final ActionRequest request;
        private final Observation observation;
        private ActionToken(ActionGate gate, Lease lease, ActionRequest request, Observation observation) {
            this.gate = gate; this.lease = lease; this.request = request; this.observation = observation;
        }
        public Lease getLease() { return lease; }
        public ActionRequest getRequest() { return request; }
        public Observation getObservation() { return observation; }
    }

    public static final class DispatchHandle {
        private final ActionGate gate;
        private final ActionToken token;
        private final long dispatchedAtMillis;
        private DispatchHandle(ActionGate gate, ActionToken token, long dispatchedAtMillis) {
            this.gate = gate; this.token = token; this.dispatchedAtMillis = dispatchedAtMillis;
        }
        public ActionToken getToken() { return token; }
        public long getDispatchedAtMillis() { return dispatchedAtMillis; }
    }

    public static final class Verification {
        private final Observation observation;
        private final boolean postconditionSatisfied;
        public Verification(Observation observation, boolean postconditionSatisfied) {
            this.observation = Objects.requireNonNull(observation, "observation");
            this.postconditionSatisfied = postconditionSatisfied;
        }
        public Observation getObservation() { return observation; }
        public boolean isPostconditionSatisfied() { return postconditionSatisfied; }
    }

    public static final class Confirmation {
        private final boolean confirmed;
        private final Reason reason;
        private Confirmation(boolean confirmed, Reason reason) { this.confirmed = confirmed; this.reason = reason; }
        public boolean isConfirmed() { return confirmed; }
        public Reason getReason() { return reason; }
    }
}
