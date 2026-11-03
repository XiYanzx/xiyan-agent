// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.control;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** JVM tests of the policy wired into captureScreenshot, not Android screenshot/device tests. */
public final class WindowCapturePolicyTest {
    private static final String OWN_PACKAGE = "io.xiyan.agent.p0";

    @Test public void olderAndroidCannotProduceAnyCapturePlan() {
        for (int sdk = 29; sdk <= 33; sdk++) {
            assertNull("API " + sdk + " must not fall back to display capture",
                    plan(sdk, OWN_PACKAGE, true, 42, 0));
        }
    }

    @Test public void supportedAndroidKeepsTheObservedWindowIdentity() {
        for (int sdk : new int[] {34, 35}) {
            WindowCapturePolicy.Plan plan = plan(sdk, OWN_PACKAGE, true, 42, 0);
            assertNotNull(plan);
            assertEquals(42, plan.windowId);
        }
    }

    @Test public void anotherPackageIsRejectedEvenWithForegroundFlag() {
        assertNull(plan(35, "com.example.other", true, 42, 0));
        assertNull(plan(35, null, true, 42, 0));
    }

    @Test public void unconfirmedWindowCannotBeCaptured() {
        assertNull(plan(35, OWN_PACKAGE, false, 42, 0));
        assertNull(plan(35, OWN_PACKAGE, true, -1, 0));
        assertNull(plan(35, OWN_PACKAGE, true, 42, 1));
    }

    @Test public void realWindowPreviewDoesNotNeedDisplaySizedPixels() {
        assertTrue(WindowCapturePolicy.isImageSizeAllowed(540, 800));
        assertTrue(WindowCapturePolicy.isImageSizeAllowed(4096, 4096));
    }

    @Test public void invalidOrExcessiveBuffersAreRejectedBeforeCopy() {
        assertFalse(WindowCapturePolicy.isImageSizeAllowed(0, 800));
        assertFalse(WindowCapturePolicy.isImageSizeAllowed(540, -1));
        assertFalse(WindowCapturePolicy.isImageSizeAllowed(4097, 4096));
        assertFalse(WindowCapturePolicy.isImageSizeAllowed(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    private static WindowCapturePolicy.Plan plan(int sdk, String observedPackage,
                                                  boolean foreground, int windowId, int displayId) {
        return WindowCapturePolicy.plan(sdk, OWN_PACKAGE, observedPackage, foreground,
                windowId, displayId);
    }
}
