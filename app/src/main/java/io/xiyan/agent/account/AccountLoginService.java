// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.account;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import io.xiyan.agent.MainActivity;
import io.xiyan.agent.R;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** User-started, non-sticky protection for one bounded local OAuth operation. No OAuth values in intents. */
public final class AccountLoginService extends Service {
    private static final Object REGISTRY = new Object();
    private static final String START = "io.xiyan.agent.account.LOGIN_START";
    private static final String CANCEL = "io.xiyan.agent.account.LOGIN_CANCEL";
    private static final String CHANNEL = "xiyan-account-login";
    private static final int NOTIFICATION = 1709;
    private static final long MAX_SERVICE_MILLIS = 7 * 60 * 1000L;
    private static Lease active;
    interface FailureListener { void failed(LoginFailure.Reason reason); }

    static final class Lease implements AutoCloseable {
        final String id = UUID.randomUUID().toString();
        final CountDownLatch ready = new CountDownLatch(1);
        final Runnable cancel;
        final FailureListener interrupted;
        volatile LoginFailure.Reason failure;
        volatile boolean closed;
        AccountLoginService service;
        Lease(Runnable cancel, FailureListener interrupted) { this.cancel = cancel; this.interrupted = interrupted; }
        void awaitReady() throws Exception {
            if (!ready.await(8, TimeUnit.SECONDS)) throw new LoginFailure(LoginFailure.Reason.FOREGROUND_TIMEOUT);
            check();
        }
        void check() throws LoginFailure {
            if (failure != null) throw new LoginFailure(failure);
            if (closed) throw new LoginFailure(LoginFailure.Reason.FOREGROUND_STOPPED);
        }
        void fail(LoginFailure.Reason reason) {
            synchronized (REGISTRY) {
                if (closed || active != this) return;
                failure = reason; ready.countDown();
            }
            interrupted.failed(reason);
        }
        @Override public void close() {
            AccountLoginService running;
            synchronized (REGISTRY) {
                if (closed) return;
                closed = true; ready.countDown();
                if (active == this) active = null;
                running = service;
            }
            if (running != null) running.handler.post(() -> running.finishLease(this));
        }
        @Override public String toString() { return "AccountLoginLease[redacted]"; }
    }

    /** Invoke synchronously from the user's visible connect/resume/paste action, before opening a browser. */
    static Lease begin(Context context, Runnable cancel, FailureListener interrupted) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Login requires a visible user action");
        Lease lease = new Lease(cancel, interrupted);
        synchronized (REGISTRY) {
            if (active != null && !active.closed) {
                lease.failure = LoginFailure.Reason.FOREGROUND_UNAVAILABLE; lease.ready.countDown(); return lease;
            }
            active = lease;
        }
        try { context.startForegroundService(new Intent(context, AccountLoginService.class).setAction(START).putExtra("leaseId", lease.id)); }
        catch (RuntimeException unavailable) { lease.fail(LoginFailure.Reason.FOREGROUND_UNAVAILABLE); }
        return lease;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Lease lease;
    private int latestStartId;
    private boolean destroyed;

    /** Package-only synthetic observation; does not obtain a client or inspect any account storage. */
    static boolean syntheticLeaseForeground(Lease expected) {
        synchronized (REGISTRY) {
            return expected != null && active == expected && !expected.closed && expected.failure == null
                    && expected.service != null && expected.service.lease == expected && !expected.service.destroyed;
        }
    }
    static boolean syntheticLeaseDestroyed(Lease expected) {
        synchronized (REGISTRY) {
            return expected != null && expected.closed && expected.service != null && expected.service.destroyed;
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        String action = intent == null ? null : intent.getAction();
        String id = intent == null ? null : intent.getStringExtra("leaseId");
        Lease selected;
        synchronized (REGISTRY) { selected = active != null && active.id.equals(id) ? active : null; }
        if (CANCEL.equals(action)) {
            if (selected != null) selected.cancel.run();
            else if (lease == null) stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (!START.equals(action) || selected == null) {
            // A stale service start must not stop a later, registered operation.
            synchronized (REGISTRY) { if (active == null && lease == null) stopSelfResult(startId); }
            return START_NOT_STICKY;
        }
        lease = selected;
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager == null) throw new IllegalStateException("Notification service unavailable");
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "账号连接", NotificationManager.IMPORTANCE_LOW));
            Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent content = PendingIntent.getActivity(this, NOTIFICATION, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Intent stop = new Intent(this, AccountLoginService.class).setAction(CANCEL).setIdentifier(selected.id).putExtra("leaseId", selected.id);
            PendingIntent cancel = PendingIntent.getService(this, NOTIFICATION, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_lucide_shield_check)
                    .setContentTitle("Xiyan Agent 正在连接账号")
                    .setContentText("请完成官方登录；返回应用可查看状态或停止。本次等待最多 5 分钟。")
                    .setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_SECRET)
                    .setContentIntent(content).addAction(new Notification.Action.Builder(null, "停止连接", cancel).build()).build();
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTIFICATION, notification);
            synchronized (REGISTRY) {
                if (active == selected && !selected.closed) { selected.service = this; selected.ready.countDown(); }
            }
            handler.postDelayed(() -> {
                if (lease == selected && !selected.closed) selected.fail(LoginFailure.Reason.SERVICE_TIMEOUT);
                selected.close();
            }, MAX_SERVICE_MILLIS);
            if (selected.closed) finishLease(selected);
        } catch (RuntimeException unavailable) {
            selected.fail(LoginFailure.Reason.FOREGROUND_UNAVAILABLE);
            selected.close(); stopSelfResult(startId);
        }
        return START_NOT_STICKY;
    }

    private void finishLease(Lease expected) {
        synchronized (REGISTRY) {
            if (lease != expected || active != null && active != expected) return;
            if (stopSelfResult(latestStartId)) { stopForeground(STOP_FOREGROUND_REMOVE); lease = null; }
        }
    }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        synchronized (REGISTRY) { destroyed = true; }
        Lease previous = lease; lease = null;
        if (previous != null && !previous.closed) {
            previous.fail(LoginFailure.Reason.FOREGROUND_STOPPED);
            previous.close();
        }
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
