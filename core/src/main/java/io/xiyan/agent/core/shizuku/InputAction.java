package io.xiyan.agent.core.shizuku;

/** A closed, display-0-only input vocabulary. No caller-supplied command strings. */
public final class InputAction {
    public static final int TAP = 1;
    public static final int SWIPE = 2;
    public static final int BACK = 3;
    public static final int HOME = 4;
    public static final int MAX_DIMENSION = 16_384;
    public static final int MAX_SWIPE_MILLIS = 1_500;
    public static final int COMMAND_TIMEOUT_MILLIS = 6_000;

    public static final int COMMAND_COMPLETED = 0;
    public static final int COMMAND_FAILED = 1;
    public static final int CANCELLED = 2;
    public static final int GEOMETRY_CHANGED = 3;
    public static final int TIMED_OUT = 4;
    public static final int REJECTED = 5;
    public static final int UNAVAILABLE = 6;

    public final int kind;
    public final int x1;
    public final int y1;
    public final int x2;
    public final int y2;
    public final int durationMillis;
    public final int width;
    public final int height;
    public final int rotation;

    private InputAction(int kind, int x1, int y1, int x2, int y2, int duration,
            int width, int height, int rotation) {
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                || rotation < 0 || rotation > 3) throw invalid();
        if (kind == TAP) {
            coordinate(x1, y1, width, height);
            if (x2 != 0 || y2 != 0 || duration != 0) throw invalid();
        } else if (kind == SWIPE) {
            coordinate(x1, y1, width, height);
            coordinate(x2, y2, width, height);
            if (duration < 50 || duration > MAX_SWIPE_MILLIS) throw invalid();
        } else if (kind == BACK || kind == HOME) {
            if (x1 != 0 || y1 != 0 || x2 != 0 || y2 != 0 || duration != 0) throw invalid();
        } else {
            throw invalid();
        }
        this.kind = kind;
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
        this.durationMillis = duration;
        this.width = width;
        this.height = height;
        this.rotation = rotation;
    }

    public static InputAction tap(int x, int y, int width, int height, int rotation) {
        return fromWire(TAP, x, y, 0, 0, 0, 0, width, height, rotation);
    }

    public static InputAction swipe(int x1, int y1, int x2, int y2, int duration,
            int width, int height, int rotation) {
        return fromWire(SWIPE, x1, y1, x2, y2, duration, 0, width, height, rotation);
    }

    public static InputAction back(int width, int height, int rotation) {
        return fromWire(BACK, 0, 0, 0, 0, 0, 0, width, height, rotation);
    }

    public static InputAction home(int width, int height, int rotation) {
        return fromWire(HOME, 0, 0, 0, 0, 0, 0, width, height, rotation);
    }

    /** Revalidate the complete parcel in the privileged process. */
    public static InputAction fromWire(int kind, int x1, int y1, int x2, int y2,
            int duration, int displayId, int width, int height, int rotation) {
        if (displayId != 0) throw invalid();
        return new InputAction(kind, x1, y1, x2, y2, duration, width, height, rotation);
    }

    public boolean matchesDisplay(int actualWidth, int actualHeight, int actualRotation) {
        return width == actualWidth && height == actualHeight && rotation == actualRotation;
    }

    public String[] command() {
        if (kind == TAP) return new String[] {"/system/bin/input", "touchscreen", "-d", "0",
                "tap", Integer.toString(x1), Integer.toString(y1)};
        if (kind == SWIPE) return new String[] {"/system/bin/input", "touchscreen", "-d", "0",
                "swipe", Integer.toString(x1), Integer.toString(y1), Integer.toString(x2),
                Integer.toString(y2), Integer.toString(durationMillis)};
        return new String[] {"/system/bin/input", "keyboard", "-d", "0", "keyevent",
                kind == BACK ? "4" : "3"};
    }

    private static void coordinate(int x, int y, int width, int height) {
        if (x < 0 || y < 0 || x >= width || y >= height) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid_input_action");
    }
}
