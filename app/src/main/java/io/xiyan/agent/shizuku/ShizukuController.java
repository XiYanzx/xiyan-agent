package io.xiyan.agent.shizuku;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import io.xiyan.agent.BuildConfig;
import io.xiyan.agent.core.shizuku.InputAction;
import io.xiyan.agent.core.shizuku.StopCooldown;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/** App-owned, explicit Shizuku connection. Input requests are never retried automatically. */
public final class ShizukuController {
    private static final int PERMISSION_REQUEST = 0x5859;
    private static ShizukuController instance;
    public enum Mode { UNKNOWN, ADB, ROOT }

    public static final class Snapshot {
        public final boolean binderAlive;
        public final boolean permissionGranted;
        public final boolean binding;
        public final boolean bound;
        public final boolean busy;
        public final int uid;
        public final int serverVersion;
        public final long generation;
        public final Mode mode;
        public final String status;
        private Snapshot(ShizukuController controller) {
            binderAlive = controller.binderAlive;
            permissionGranted = controller.permissionGranted;
            binding = controller.binding;
            bound = controller.remote != null;
            busy = controller.active != null || controller.stopping || controller.cleaning
                    || controller.cooldown.remaining(SystemClock.elapsedRealtime()) > 0;
            uid = controller.uid;
            serverVersion = controller.serverVersion;
            generation = controller.generation;
            mode = uid == 0 ? Mode.ROOT : uid == 2000 ? Mode.ADB : Mode.UNKNOWN;
            status = controller.status;
        }
        public boolean canExecute() {
            return binderAlive && permissionGranted && bound && !busy
                    && (mode == Mode.ADB || mode == Mode.ROOT);
        }
    }

    public interface ResultListener { void onResult(ActionResult result); }
    public static final class ActionResult {
        public final long generation;
        public final long requestId;
        public final int status;
        public final int exitCode;
        public final boolean acceptedCurrentGeneration;
        private ActionResult(long generation, long requestId, int status, int exitCode, boolean current) {
            this.generation = generation;
            this.requestId = requestId;
            this.status = status;
            this.exitCode = exitCode;
            acceptedCurrentGeneration = current;
        }
        /** Only describes system input completion. Callers verify the actual screen separately. */
        public boolean commandCompleted() {
            return acceptedCurrentGeneration && status == InputAction.COMMAND_COMPLETED && exitCode == 0;
        }
    }

    private static final class Pending {
        final long generation;
        final long id;
        final ResultListener listener;
        final AtomicBoolean delivered = new AtomicBoolean();
        Pending(long generation, long id, ResultListener listener) {
            this.generation = generation;
            this.id = id;
            this.listener = listener;
        }
    }

    private final Object lock = new Object();
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService cancellation = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final IBinder owner = new Binder();
    private final StopCooldown cooldown = new StopCooldown();
    private final Shizuku.UserServiceArgs args;
    private boolean binderAlive;
    private boolean permissionGranted;
    private boolean binding;
    private boolean stopping;
    private boolean cleaning;
    private boolean cancellationPending;
    private int uid = -1;
    private int serverVersion = -1;
    private long generation = 1;
    private long nextRequest;
    private String status = "not_running";
    private IShizukuInputService remote;
    private IBinder serverBinder;
    private Connection connection;
    private Pending active;
    private boolean polling;

    private ShizukuController(Context context) {
        this.context = context.getApplicationContext();
        args = new Shizuku.UserServiceArgs(new ComponentName(this.context, XiyanInputService.class))
                .tag("xiyan.input.v1").processNameSuffix("xiyan_input")
                .version(BuildConfig.VERSION_CODE).daemon(false).debuggable(false);
        Shizuku.addBinderReceivedListenerSticky(this::refresh, main);
        Shizuku.addBinderDeadListener(() -> lost("binder_dead"), main);
        Shizuku.addRequestPermissionResultListener((request, result) -> {
            if (request != PERMISSION_REQUEST) return;
            worker.execute(() -> {
                refreshNow();
                synchronized (lock) {
                    status = result == PackageManager.PERMISSION_GRANTED ? "permission_granted" : "permission_denied";
                }
                publish();
            });
        }, main);
        refresh();
    }

    public static synchronized ShizukuController getInstance(Context context) {
        if (instance == null) instance = new ShizukuController(context);
        return instance;
    }

    public Snapshot snapshot() { synchronized (lock) { return new Snapshot(this); } }
    public void addListener(Runnable listener) { listeners.addIfAbsent(listener); main.post(listener); }
    public void removeListener(Runnable listener) { listeners.remove(listener); }
    public void refresh() { worker.execute(this::refreshNow); }

    /** Call only from an explicit user action; refresh/connect never requests permission. */
    public void requestPermission() {
        main.post(() -> {
            try {
                if (!Shizuku.pingBinder() || Shizuku.isPreV11() || Shizuku.getVersion() < 13) {
                    setStatus("server_unavailable");
                } else {
                    Shizuku.requestPermission(PERMISSION_REQUEST);
                    setStatus("permission_requested");
                }
            } catch (RuntimeException failure) { lost("permission_request_failed"); }
        });
    }

    public void connect() {
        worker.execute(() -> {
            refreshNow();
            Connection candidate;
            synchronized (lock) {
                if (binding || cleaning || cooldown.remaining(SystemClock.elapsedRealtime()) > 0
                        || remote != null || !binderAlive || !permissionGranted
                        || serverVersion < 13 || (uid != 0 && uid != 2000)) return;
                generation = Math.incrementExact(generation);
                candidate = new Connection(generation);
                connection = candidate;
                binding = true;
                status = "binding";
            }
            publish();
            try { Shizuku.bindUserService(args, candidate); }
            catch (RuntimeException failure) { connectionLost(candidate, "bind_failed"); }
            main.postDelayed(() -> {
                synchronized (lock) {
                    if (connection != candidate || !binding) return;
                }
                connectionLost(candidate, "bind_timeout");
            }, 10_000);
        });
    }

    public boolean execute(long expectedGeneration, InputAction action, ResultListener listener) {
        if (action == null || listener == null) throw new IllegalArgumentException("input_request_missing");
        Pending pending;
        IShizukuInputService service;
        int expectedUid;
        synchronized (lock) {
            if (generation != expectedGeneration || !new Snapshot(this).canExecute()) return false;
            pending = new Pending(generation, Math.incrementExact(nextRequest), listener);
            nextRequest = pending.id;
            active = pending;
            service = remote;
            expectedUid = uid;
            status = "dispatching";
        }
        publish();
        worker.execute(() -> {
            if (!freshPermission()) { lost("permission_revoked"); return; }
            synchronized (lock) {
                if (active != pending || generation != pending.generation || remote != service) {
                    finish(pending, InputAction.CANCELLED, -1);
                    return;
                }
            }
            try {
                service.execute(pending.generation, pending.id, action.kind, action.x1, action.y1,
                        action.x2, action.y2, action.durationMillis, 0, action.width, action.height,
                        action.rotation, new IShizukuInputCallback.Stub() {
                            @Override public void onResult(long resultGeneration, long requestId,
                                    int result, int exitCode) {
                                if (Binder.getCallingUid() != expectedUid
                                        || resultGeneration != pending.generation || requestId != pending.id) return;
                                int safeResult = result >= InputAction.COMMAND_COMPLETED && result <= InputAction.UNAVAILABLE
                                        ? result : InputAction.UNAVAILABLE;
                                worker.execute(() -> {
                                    if (!freshPermission()) { lost("permission_revoked"); return; }
                                    finish(pending, safeResult, exitCode);
                                });
                            }
                        });
                main.postDelayed(() -> {
                    synchronized (lock) { if (active != pending) return; }
                    // A missing terminal is uncertain: stop/unbind, do not submit a replacement.
                    lost("command_watchdog");
                }, InputAction.COMMAND_TIMEOUT_MILLIS + 3_000L);
            } catch (RemoteException | RuntimeException failure) { lost("dispatch_unavailable"); }
        });
        return true;
    }

    /** Local generation invalidates immediately; remote cancellation has a separate executor. */
    public void stop() {
        IShizukuInputService service;
        long cancelledGeneration;
        boolean cancelBinding;
        synchronized (lock) {
            cancelBinding = binding;
            generation = Math.incrementExact(generation);
            cancelledGeneration = generation;
            service = remote;
            cooldown.begin(cancelledGeneration, SystemClock.elapsedRealtime(), active != null);
            cancellationPending = service != null;
            stopping = service != null || cooldown.remaining(SystemClock.elapsedRealtime()) > 0;
            status = service == null ? "stopped" : "stop_requested";
        }
        publish();
        scheduleStopRelease(cancelledGeneration);
        if (cancelBinding) { lost("stopped"); return; }
        if (service == null) return;
        cancellation.execute(() -> {
            try {
                boolean idle = service.cancel(cancelledGeneration);
                Pending pending;
                synchronized (lock) {
                    if (remote != service || generation != cancelledGeneration) return;
                    cancellationPending = false;
                    stopping = cooldown.remaining(SystemClock.elapsedRealtime()) > 0;
                    pending = idle ? active : null;
                    status = active == null || idle ? "stopped" : "waiting_for_terminal";
                }
                if (pending != null) finish(pending, InputAction.CANCELLED, -1);
                scheduleStopRelease(cancelledGeneration);
                publish();
            } catch (RemoteException | RuntimeException failure) { lost("cancel_unavailable"); }
        });
        main.postDelayed(() -> {
            synchronized (lock) {
                if (generation != cancelledGeneration || !cancellationPending) return;
            }
            lost("cancel_watchdog");
        }, 3_000);
    }

    public void disconnect() { lost("disconnected"); }

    private void refreshNow() {
        boolean alive = Shizuku.pingBinder();
        int actualUid = -1;
        int version = -1;
        boolean granted = false;
        IBinder binder = alive ? Shizuku.getBinder() : null;
        try {
            if (alive && !Shizuku.isPreV11()) {
                actualUid = Shizuku.getUid();
                version = Shizuku.getVersion();
                granted = freshPermission();
            }
        } catch (RuntimeException failure) { alive = false; }
        boolean invalidated;
        synchronized (lock) {
            invalidated = (remote != null || binding) && (!alive || !granted || binder != serverBinder);
            binderAlive = alive;
            uid = actualUid;
            serverVersion = version;
            permissionGranted = granted;
            serverBinder = binder;
            if (remote == null && !binding) {
                status = !alive ? "not_running" : version < 13 ? "server_too_old"
                        : !granted ? "permission_required" : (uid != 0 && uid != 2000)
                        ? "unsupported_uid" : "ready_to_connect";
            }
        }
        if (invalidated) lost(!alive ? "binder_dead" : "permission_or_server_changed");
        publish();
    }

    /** Unlike the SDK convenience method, this official typed IPC does not reuse a grant cache. */
    private static boolean freshPermission() {
        try {
            IBinder binder = Shizuku.getBinder();
            return binder != null && binder.pingBinder()
                    && IShizukuService.Stub.asInterface(binder).checkSelfPermission();
        } catch (RemoteException | RuntimeException failure) { return false; }
    }

    private final class Connection implements ServiceConnection {
        final long expectedGeneration;
        IBinder serviceBinder;
        final IBinder.DeathRecipient death = () -> connectionLost(this, "user_service_dead");
        Connection(long generation) { expectedGeneration = generation; }
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            worker.execute(() -> {
                try {
                    synchronized (lock) { if (connection != this || !binding) return; }
                    if (!freshPermission()) throw AppIdentity.denied();
                    IShizukuInputService service = IShizukuInputService.Stub.asInterface(binder);
                    int actualUid = service.getServiceUid();
                    if (service.getProtocolVersion() != XiyanInputService.PROTOCOL_VERSION
                            || (actualUid != 0 && actualUid != 2000)) throw AppIdentity.denied();
                    synchronized (lock) {
                        if (connection != this || generation != expectedGeneration || actualUid != uid) return;
                    }
                    AppIdentity identity = new AppIdentity(context);
                    service.attach(owner, expectedGeneration, identity.signerDigest);
                    binder.linkToDeath(death, 0);
                    synchronized (lock) {
                        if (connection != this || generation != expectedGeneration) {
                            binder.unlinkToDeath(death, 0);
                            return;
                        }
                        serviceBinder = binder;
                        remote = service;
                        binding = false;
                        status = "connected";
                    }
                    beginPolling();
                    publish();
                } catch (RemoteException | PackageManager.NameNotFoundException | RuntimeException failure) {
                    connectionLost(this, "handshake_failed");
                }
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            connectionLost(this, "user_service_disconnected");
        }
    }

    private void connectionLost(Connection expected, String reason) {
        lost(reason, expected);
    }

    private void lost(String reason) { lost(reason, null); }

    private void lost(String reason, Connection expected) {
        Connection previous;
        IShizukuInputService service;
        Pending pending;
        long cancelledGeneration;
        synchronized (lock) {
            if (expected != null && connection != expected) return;
            generation = Math.incrementExact(generation);
            cancelledGeneration = generation;
            previous = connection;
            service = remote;
            pending = active;
            cooldown.begin(cancelledGeneration, SystemClock.elapsedRealtime(), pending != null);
            connection = null;
            remote = null;
            binding = false;
            stopping = cooldown.remaining(SystemClock.elapsedRealtime()) > 0;
            cancellationPending = false;
            if (previous != null) cleaning = true;
            if ("binder_dead".equals(reason)) {
                binderAlive = false;
                permissionGranted = false;
                uid = -1;
                serverVersion = -1;
            }
            status = reason;
            if (reason.startsWith("permission_")) permissionGranted = false;
        }
        scheduleStopRelease(cancelledGeneration);
        if (pending != null) finish(pending, InputAction.CANCELLED, -1);
        if (previous != null && previous.serviceBinder != null) previous.serviceBinder.unlinkToDeath(previous.death, 0);
        if (service != null) cancellation.execute(() -> {
            try { service.cancel(cancelledGeneration); }
            catch (RemoteException | RuntimeException ignored) { /* Unbind below also destroys the service. */ }
        });
        if (previous != null) worker.execute(() -> {
            try { Shizuku.unbindUserService(args, previous, true); }
            catch (RuntimeException ignored) { /* Dead manager is already unavailable. */ }
            finally { synchronized (lock) { cleaning = false; } publish(); }
        });
        publish();
    }

    private void finish(Pending pending, int result, int exitCode) {
        if (!pending.delivered.compareAndSet(false, true)) return;
        synchronized (lock) {
            if (active == pending) active = null;
            if (generation == pending.generation) status = result == InputAction.COMMAND_COMPLETED
                    ? "command_completed" : "command_failed";
            else if (!stopping && remote != null) status = "stopped";
        }
        publish();
        main.post(() -> {
            boolean current;
            synchronized (lock) { current = generation == pending.generation && remote != null && permissionGranted; }
            pending.listener.onResult(new ActionResult(pending.generation, pending.id,
                    current ? result : InputAction.CANCELLED, exitCode, current));
        });
    }

    private void beginPolling() {
        main.post(() -> {
            if (polling) return;
            polling = true;
            poll();
        });
    }

    private void scheduleStopRelease(long expectedGeneration) {
        long remaining = cooldown.remaining(SystemClock.elapsedRealtime());
        main.postDelayed(() -> {
            synchronized (lock) {
                if (generation == expectedGeneration && cooldown.isLatest(expectedGeneration)) {
                    if (cooldown.remaining(SystemClock.elapsedRealtime()) > 0) {
                        scheduleStopRelease(expectedGeneration);
                        return;
                    }
                    stopping = cancellationPending;
                    if (!stopping && remote != null) status = active == null ? "stopped" : "waiting_for_terminal";
                }
            }
            // A stale timer may refresh computed busy, but never mutates a newer generation.
            publish();
        }, remaining);
    }

    private void poll() {
        synchronized (lock) {
            if (remote == null && !binding) { polling = false; return; }
        }
        refresh();
        main.postDelayed(this::poll, 1_000);
    }

    private void setStatus(String value) { synchronized (lock) { status = value; } publish(); }
    private void publish() { main.post(() -> { for (Runnable listener : listeners) listener.run(); }); }
}
