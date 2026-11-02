// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.core.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import org.junit.Test;

public class LoginAttemptLifetimeTest {
    @Test public void eitherOriginalDeadlineEndsTheAttemptWithoutRestorationExtension() {
        LoginAttemptLifetime original = new LoginAttemptLifetime(1000, 5000);
        LoginAttemptLifetime restored = new LoginAttemptLifetime(1000, 5000);
        assertEquals(1, original.remainingMillis(1299, 304999));
        assertEquals(1, restored.remainingMillis(1299, 304999));
        assertEquals(0, restored.remainingMillis(1300, 5001));
        assertEquals(0, restored.remainingMillis(1001, 305000));
    }
    @Test public void wallClockRollbackAndMonotonicResetInvalidateTheAttempt() {
        LoginAttemptLifetime lifetime = new LoginAttemptLifetime(1000, 5000);
        assertEquals(0, lifetime.remainingMillis(999, 6000));
        assertEquals(0, lifetime.remainingMillis(1001, 4999));
        assertEquals(0, lifetime.remainingMillis(Long.MAX_VALUE, Long.MAX_VALUE));
    }
    @Test public void invalidCreationTimesCannotOverflowEitherDeadline() {
        for (long wall : new long[]{-1, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new LoginAttemptLifetime(wall, 100));
        }
        for (long elapsed : new long[]{-1, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new LoginAttemptLifetime(100, elapsed));
        }
    }
}
