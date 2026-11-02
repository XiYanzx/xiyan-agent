// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.core.auth;

import java.io.IOException;

/** One callback receipt shared by the loopback listener and an explicit manual recovery action. */
public final class LoginCallbackHandoff {
    public interface Acknowledgement { void send() throws IOException; }
    private final PendingSignIn pending;
    private PendingSignIn.Callback callback;
    private PendingSignIn.AuthException terminalFailure;
    private boolean acknowledgementFailed;

    public LoginCallbackHandoff(PendingSignIn pending) {
        if (pending == null) throw new IllegalArgumentException("Missing pending authorization");
        this.pending = pending;
    }

    public synchronized PendingSignIn.Callback accept(String callbackUri, long nowEpochSeconds) {
        try {
            PendingSignIn.Callback accepted = pending.consumeCallback(callbackUri, nowEpochSeconds);
            callback = accepted;
            return accepted;
        } catch (PendingSignIn.AuthException failure) {
            if (failure.getReason() == PendingSignIn.Reason.AUTHORIZATION_DENIED
                    || failure.getReason() == PendingSignIn.Reason.AUTHORIZATION_ERROR) terminalFailure = failure;
            throw failure;
        }
    }

    /** The validated receipt survives a browser disconnect or a failed local HTTP acknowledgement. */
    public PendingSignIn.Callback acceptAndAcknowledge(String callbackUri, long nowEpochSeconds,
            Acknowledgement acknowledgement) {
        PendingSignIn.Callback accepted = accept(callbackUri, nowEpochSeconds);
        try { acknowledgement.send(); }
        catch (IOException unavailable) { synchronized (this) { acknowledgementFailed = true; } }
        return accepted;
    }

    public synchronized PendingSignIn.Callback received() {
        if (terminalFailure != null) throw terminalFailure;
        return callback;
    }

    public synchronized boolean isTerminal() { return callback != null || terminalFailure != null; }
    public synchronized boolean acknowledgementFailed() { return acknowledgementFailed; }
    @Override public String toString() { return "LoginCallbackHandoff[redacted]"; }
}
