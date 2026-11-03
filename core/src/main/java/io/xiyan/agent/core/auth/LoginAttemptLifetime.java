// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.core.auth;

/** The original authorization's wall and monotonic deadlines survive process recreation. */
public final class LoginAttemptLifetime {
    private static final long DURATION_MILLIS = PendingSignIn.LIFETIME_SECONDS * 1000;
    private final long createdWallSeconds, createdElapsedMillis;

    public LoginAttemptLifetime(long createdWallSeconds, long createdElapsedMillis) {
        if (createdWallSeconds < 0 || createdWallSeconds > Long.MAX_VALUE - PendingSignIn.LIFETIME_SECONDS
                || createdElapsedMillis < 0 || createdElapsedMillis > Long.MAX_VALUE - DURATION_MILLIS) {
            throw new IllegalArgumentException("Invalid authorization lifetime");
        }
        this.createdWallSeconds = createdWallSeconds;
        this.createdElapsedMillis = createdElapsedMillis;
    }

    public long remainingMillis(long nowWallSeconds, long nowElapsedMillis) {
        if (nowWallSeconds < createdWallSeconds || nowElapsedMillis < createdElapsedMillis
                || nowWallSeconds >= createdWallSeconds + PendingSignIn.LIFETIME_SECONDS
                || nowElapsedMillis >= createdElapsedMillis + DURATION_MILLIS) return 0;
        long wall = (createdWallSeconds + PendingSignIn.LIFETIME_SECONDS - nowWallSeconds) * 1000;
        return Math.min(wall, createdElapsedMillis + DURATION_MILLIS - nowElapsedMillis);
    }
}
