// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.control;

/** Pure Java policy used by the controller before invoking the window-only Android API. */
final class WindowCapturePolicy {
    static final int MIN_API = 34;
    static final String SCOPE_WINDOW = "window";
    private static final long MAX_IMAGE_PIXELS = 16_777_216;

    private WindowCapturePolicy() { }

    /** There is deliberately no display-capture plan or lower-version fallback. */
    static Plan plan(int sdk, String ownPackage, String observedPackage,
                     boolean testPadConfirmed, int observedWindowId, int displayId) {
        if (sdk < MIN_API || ownPackage == null || !ownPackage.equals(observedPackage)
                || !testPadConfirmed || observedWindowId < 0 || displayId != 0) return null;
        return new Plan(observedWindowId);
    }

    static boolean isImageSizeAllowed(int width, int height) {
        return width > 0 && height > 0 && (long) width * height <= MAX_IMAGE_PIXELS;
    }

    static final class Plan {
        final int windowId;
        private Plan(int windowId) { this.windowId = windowId; }
    }
}
