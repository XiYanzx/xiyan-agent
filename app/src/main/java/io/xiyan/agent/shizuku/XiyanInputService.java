package io.xiyan.agent.shizuku;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.view.Display;
import io.xiyan.agent.core.shizuku.InputAction;
import io.xiyan.agent.core.shizuku.InputSession;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Shizuku UserService: no Android Service entry, no string command/text/launch API. */
public final class XiyanInputService extends IShizukuInputService.Stub {
    public static final int PROTOCOL_VERSION = 1;
    private final Object lock = new Object();
    private final InputSession session = new InputSession();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AppIdentity identity;
    private IBinder owner;
    private java.lang.Process activeProcess;
    private boolean destroyed;
    private final IBinder.DeathRecipient ownerDeath = this::ownerDied;

    /** Older servers cannot establish the package identity required by this backend. */
    public XiyanInputService() {
        identity = null;
    }

    /** Shizuku 13 supplies a createPackageContextAsUser context for this installed APK. */
    public XiyanInputService(Context context) {
        try {
            identity = new AppIdentity(context);
        } catch (PackageManager.NameNotFoundException missing) {
            throw new SecurityException("input_package_missing", missing);
        }
    }

    @Override public int getProtocolVersion() { enforceCaller(); return PROTOCOL_VERSION; }
    @Override public int getServiceUid() { enforceCaller(); return Process.myUid(); }

    @Override public void attach(IBinder clientOwner, long generation, String signerDigest)
            throws RemoteException {
        enforceCaller();
        if (clientOwner == null || !clientOwner.isBinderAlive() || signerDigest == null
                || signerDigest.length() != identity.signerDigest.length()
                || !MessageDigest.isEqual(identity.signerDigest.getBytes(StandardCharsets.UTF_8),
                signerDigest.getBytes(StandardCharsets.UTF_8))) throw AppIdentity.denied();
        synchronized (lock) {
            if (destroyed || owner != null) throw new IllegalStateException("input_already_attached");
            session.attach(generation);
            clientOwner.linkToDeath(ownerDeath, 0);
            owner = clientOwner;
        }
    }

    @Override public void execute(long generation, long requestId, int action, int x1, int y1,
            int x2, int y2, int durationMillis, int displayId, int width, int height,
            int rotation, IShizukuInputCallback callback) {
        enforceCaller();
        if (callback == null) return;
        InputAction specification;
        InputSession.Ticket ticket;
        try {
            specification = InputAction.fromWire(action, x1, y1, x2, y2, durationMillis,
                    displayId, width, height, rotation);
            synchronized (lock) {
                if (destroyed || owner == null || !owner.isBinderAlive()) throw AppIdentity.denied();
                ticket = session.begin(generation, requestId);
                worker.execute(() -> run(ticket, specification, callback));
            }
        } catch (RuntimeException rejected) {
            deliver(callback, generation, requestId, InputAction.REJECTED, -1);
        }
    }

    /** ACK=true only if no command remains outstanding; a killed worker still sends its terminal. */
    @Override public boolean cancel(long nextGeneration) {
        enforceCaller();
        synchronized (lock) {
            if (session.cancel(nextGeneration) && activeProcess != null) activeProcess.destroyForcibly();
            return !session.isBusy();
        }
    }

    @Override public void destroy() {
        int caller = Binder.getCallingUid();
        if (caller != 0 && caller != 2000) enforceCaller();
        synchronized (lock) {
            destroyed = true;
            session.detach();
            if (owner != null) owner.unlinkToDeath(ownerDeath, 0);
            owner = null;
            if (activeProcess != null) activeProcess.destroyForcibly();
        }
        worker.shutdownNow();
        // This class is instantiated only in Shizuku's dedicated UserService process.
        System.exit(0);
    }

    private void run(InputSession.Ticket ticket, InputAction specification,
            IShizukuInputCallback callback) {
        int result = InputAction.UNAVAILABLE;
        int exitCode = -1;
        java.lang.Process process = null;
        try {
            synchronized (lock) {
                if (destroyed || owner == null || !owner.isBinderAlive() || !session.isCurrent(ticket)) {
                    result = InputAction.CANCELLED;
                } else if (!matchesActualDisplay(specification)) {
                    result = InputAction.GEOMETRY_CHANGED;
                } else {
                    // Holding the same lock as cancel makes the final check/start atomic.
                    process = new ProcessBuilder(specification.command()).redirectErrorStream(true)
                            .redirectOutput(new File("/dev/null")).start();
                    activeProcess = process;
                }
            }
            if (process != null) {
                if (process.waitFor(InputAction.COMMAND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    exitCode = process.exitValue();
                    result = exitCode == 0 ? InputAction.COMMAND_COMPLETED : InputAction.COMMAND_FAILED;
                } else {
                    process.destroyForcibly();
                    process.waitFor(1_000, TimeUnit.MILLISECONDS);
                    result = InputAction.TIMED_OUT;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            result = InputAction.CANCELLED;
        } catch (IOException | ReflectiveOperationException | RuntimeException unavailable) {
            result = InputAction.UNAVAILABLE;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            synchronized (lock) {
                if (activeProcess == process) activeProcess = null;
                if (process != null && process.isAlive()) {
                    // No replacement can run while native termination remains uncertain.
                    destroyed = true;
                    session.detach();
                }
                if (!session.finish(ticket)) result = InputAction.CANCELLED;
            }
            deliver(callback, ticket.generation, ticket.requestId, result, exitCode);
        }
    }

    /** Android 10+ UserService permits non-SDK access. Missing/changed APIs fail closed. */
    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"}) // Authorized UserService only; unavailable geometry APIs reject the input.
    @SuppressWarnings("deprecation")
    private static boolean matchesActualDisplay(InputAction action) throws ReflectiveOperationException {
        Class<?> globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Object global = globalClass.getDeclaredMethod("getInstance").invoke(null);
        Display display = (Display) globalClass.getDeclaredMethod("getRealDisplay", int.class)
                .invoke(global, Display.DEFAULT_DISPLAY);
        if (display == null || !display.isValid()) return false;
        Point size = new Point();
        display.getRealSize(size);
        return action.matchesDisplay(size.x, size.y, display.getRotation());
    }

    private void enforceCaller() {
        if (identity == null || (Process.myUid() != 0 && Process.myUid() != 2000)) {
            throw AppIdentity.denied();
        }
        identity.enforceUid(Binder.getCallingUid());
    }

    private void ownerDied() {
        synchronized (lock) {
            destroyed = true;
            session.detach();
            owner = null;
            if (activeProcess != null) activeProcess.destroyForcibly();
        }
        worker.shutdownNow();
    }

    private static void deliver(IShizukuInputCallback callback, long generation, long requestId,
            int result, int exitCode) {
        try { callback.onResult(generation, requestId, result, exitCode); }
        catch (RemoteException ignored) { /* Owner death/transport loss never retries an action. */ }
    }
}
