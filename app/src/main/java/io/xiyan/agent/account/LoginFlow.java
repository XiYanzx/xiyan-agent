// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.account;

import android.os.SystemClock;
import io.xiyan.agent.core.auth.LoginCallbackHandoff;
import io.xiyan.agent.core.auth.LoopbackCallbackRequest;
import io.xiyan.agent.core.auth.PendingSignIn;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Original-port listener and explicit manual input share one single-use callback receipt. */
final class LoginFlow implements AutoCloseable {
    interface Guard { void check() throws IOException; }
    final PendingLoginStore.Record record;
    private final LoginCallbackHandoff handoff;
    private volatile ServerSocket server;
    private volatile Socket connection;
    private volatile LoginFailure.Reason interruption;

    private LoginFlow(PendingLoginStore.Record record, ServerSocket server) {
        this.record = record; this.server = server; handoff = new LoginCallbackHandoff(record.pending);
    }
    static LoginFlow fresh(URI endpoint, String issued, String host, String previousSubject, PendingLoginStore store) throws Exception {
        ServerSocket server = bind(0);
        try {
            PendingSignIn pending = PendingSignIn.create(endpoint, server.getLocalPort(), issued, host, System.currentTimeMillis() / 1000);
            PendingLoginStore.Record record = new PendingLoginStore.Record(UUID.randomUUID().toString(), pending,
                    previousSubject, SystemClock.elapsedRealtime());
            store.save(record);
            return new LoginFlow(record, server);
        } catch (Exception failure) { close(server); throw failure; }
    }
    static LoginFlow restore(PendingLoginStore.Record record, boolean manual) throws Exception {
        if (record.remainingMillis() <= 0) throw new LoginFailure(LoginFailure.Reason.PENDING_EXPIRED);
        return new LoginFlow(record, manual ? null : bind(record.pending.getRedirectUri().getPort()));
    }
    private static ServerSocket bind(int port) throws LoginFailure {
        ServerSocket server = null;
        try {
            server = new ServerSocket(); server.setReuseAddress(false);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4);
            return server;
        } catch (IOException unavailable) { close(server); throw new LoginFailure(LoginFailure.Reason.LOOPBACK_UNAVAILABLE); }
    }

    PendingSignIn.Callback submit(String callbackUri) throws LoginFailure {
        if (record.remainingMillis() <= 0) throw new LoginFailure(LoginFailure.Reason.PENDING_EXPIRED);
        try { return handoff.accept(callbackUri, System.currentTimeMillis() / 1000); }
        finally { if (handoff.isTerminal()) close(); }
    }
    boolean awaiting() { return !handoff.isTerminal() && record.remainingMillis() > 0; }
    boolean acknowledgementFailed() { return handoff.acknowledgementFailed(); }
    void interrupt(LoginFailure.Reason reason) { interruption = reason; close(); }
    private void check(Guard guard) throws IOException {
        guard.check();
        if (interruption != null) throw new LoginFailure(interruption);
    }

    PendingSignIn.Callback await(Guard guard) throws Exception {
        while (true) {
            check(guard);
            PendingSignIn.Callback received = handoff.received();
            if (received != null) return received;
            if (record.remainingMillis() <= 0) throw new LoginFailure(LoginFailure.Reason.AUTHORIZATION_TIMEOUT);
            ServerSocket listening = server;
            if (listening == null || listening.isClosed()) {
                received = handoff.received(); if (received != null) return received;
                throw new LoginFailure(LoginFailure.Reason.LOOPBACK_UNAVAILABLE);
            }
            try { listening.setSoTimeout((int) Math.max(1, Math.min(1500, record.remainingMillis()))); }
            catch (IOException closed) {
                check(guard); received = handoff.received(); if (received != null) return received;
                throw new LoginFailure(LoginFailure.Reason.LOOPBACK_UNAVAILABLE);
            }
            try (Socket socket = listening.accept()) {
                connection = socket;
                if (!socket.getInetAddress().isLoopbackAddress()) continue;
                long requestEnd = SystemClock.elapsedRealtime() + Math.min(5000, record.remainingMillis());
                String target;
                try {
                    target = LoopbackCallbackRequest.readTarget(socket.getInputStream(), listening.getLocalPort(), () -> {
                        check(guard);
                        if (handoff.isTerminal()) throw new IOException("Callback receipt already available");
                        long remaining = requestEnd - SystemClock.elapsedRealtime();
                        if (remaining <= 0) throw new SocketTimeoutException("Callback request deadline exceeded");
                        socket.setSoTimeout((int) Math.min(2000, remaining));
                    });
                } catch (SocketTimeoutException idle) { continue; }
                catch (IOException invalidRequest) {
                    check(guard);
                    received = handoff.received(); if (received != null) return received;
                    replyQuietly(socket, 400, "无效的本机回调请求。"); continue;
                }
                try {
                    return handoff.acceptAndAcknowledge("http://127.0.0.1:" + listening.getLocalPort() + target,
                            System.currentTimeMillis() / 1000,
                            () -> reply(socket, 200, "已收到授权回调。请返回 Xiyan Agent 查看验证结果。"));
                } catch (PendingSignIn.AuthException invalid) {
                    replyQuietly(socket, 400, "回调未通过校验，请返回 Xiyan Agent。");
                    if (handoff.isTerminal()) throw invalid;
                }
            } catch (SocketTimeoutException idle) { /* Bounded polling permits cancellation. */ }
            catch (IOException closed) {
                check(guard);
                received = handoff.received(); if (received != null) return received;
                throw new LoginFailure(LoginFailure.Reason.LOOPBACK_UNAVAILABLE);
            } finally { connection = null; }
        }
    }
    @Override public void close() { close(connection); close(server); }
    private static void close(java.io.Closeable resource) {
        if (resource != null) try { resource.close(); } catch (IOException ignored) { }
    }
    private static void replyQuietly(Socket socket, int status, String text) {
        try { reply(socket, status, text); } catch (IOException unavailable) { /* A browser reply is best effort. */ }
    }
    private static void reply(Socket socket, int status, String text) throws IOException {
        byte[] body = ("<!doctype html><meta charset=utf-8><title>Xiyan Agent</title><p>" + text + "</p>").getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Bad Request")
                + "\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer"
                + "\r\nContent-Security-Policy: default-src 'none'; frame-ancestors 'none'\r\nConnection: close\r\nContent-Length: " + body.length + "\r\n\r\n";
        socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(body); socket.getOutputStream().flush();
    }
    @Override public String toString() { return "LoginFlow[redacted]"; }
}
