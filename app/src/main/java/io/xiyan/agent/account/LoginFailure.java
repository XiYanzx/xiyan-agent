// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.account;

import java.io.IOException;

/** Fixed classifications only. Never attach a callback URI, authorization code or raw cause. */
final class LoginFailure extends IOException {
    enum Reason { FOREGROUND_UNAVAILABLE, FOREGROUND_TIMEOUT, FOREGROUND_STOPPED, SERVICE_TIMEOUT,
        PENDING_MISSING, PENDING_EXPIRED, PENDING_INVALID, PENDING_UNAVAILABLE, PENDING_CONSUMED,
        LOOPBACK_UNAVAILABLE, AUTHORIZATION_TIMEOUT }
    final Reason reason;
    LoginFailure(Reason reason) { super("Local authorization: " + reason.name()); this.reason = reason; }
}
