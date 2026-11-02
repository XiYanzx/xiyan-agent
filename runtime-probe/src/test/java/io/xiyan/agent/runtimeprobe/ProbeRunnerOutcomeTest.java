// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.runtimeprobe;

import org.junit.Test;

/** JVM checks: no Android services, native process, network, or device. */
public final class ProbeRunnerOutcomeTest {
    private static void expect(String scenario, String expected, boolean initialized,
            boolean failed, boolean stopped, boolean verified, Integer code,
            boolean exitedBeforeShutdown, boolean terminationRequested) {
        String actual = ProbeRunner.classifyOutcome(initialized, failed, stopped, verified,
                code, exitedBeforeShutdown, terminationRequested);
        if (!expected.equals(actual)) throw new AssertionError(scenario + ": expected " + expected + ", got " + actual);
    }

    @Test public void naturalAbnormalExitFails() {
        expect("initialize then natural abnormal exit", "native_initialize_not_passed",
                true, false, false, true, 159, true, false);
    }
    @Test public void naturalSuccessfulExitPasses() {
        expect("initialize then natural successful exit", "native_initialize_passed",
                true, false, false, true, 0, true, false);
    }
    @Test public void probeForceCleanupDoesNotImplyNativeFailure() {
        expect("completed handshake then probe force cleanup", "native_initialize_passed",
                true, false, false, true, 137, false, true);
    }
    @Test public void gracefulEofExitPasses() {
        expect("completed handshake then graceful EOF exit", "native_initialize_passed",
                true, false, false, true, 0, false, false);
    }
    @Test public void exitBeforeInitializeFails() {
        expect("exit before initialize", "native_initialize_not_passed",
                false, false, false, true, 159, true, false);
    }
    @Test public void nonzeroExitRacingWithEofIsUncertain() {
        expect("nonzero exit racing with EOF", "native_initialize_shutdown_uncertain",
                true, false, false, true, 159, false, false);
    }
    @Test public void userCancellationFailsAcceptance() {
        expect("user cancellation after initialize", "native_initialize_not_passed",
                true, false, true, true, 137, false, true);
    }
    @Test public void unverifiedCleanupFails() {
        expect("unverified cleanup", "native_initialize_not_passed",
                true, false, false, false, null, false, true);
    }
    @Test public void earlierProtocolFailureFails() {
        expect("earlier protocol failure", "native_initialize_not_passed",
                true, true, false, true, 0, true, false);
    }
}
