package io.xiyan.agent.core.shizuku;

import static org.junit.Assert.*;
import org.junit.Test;

public final class InputActionTest {
    @Test public void tapUsesExactArgumentVectorAndAcceptsDisplayEdges() {
        assertArrayEquals(new String[] {"/system/bin/input", "touchscreen", "-d", "0", "tap", "0", "2399"},
                InputAction.tap(0, 2399, 1080, 2400, 0).command());
        assertArrayEquals(new String[] {"/system/bin/input", "touchscreen", "-d", "0", "tap", "1079", "0"},
                InputAction.tap(1079, 0, 1080, 2400, 0).command());
    }

    @Test public void swipeHasBoundedDurationAndOnlyNumericCoordinates() {
        assertArrayEquals(new String[] {"/system/bin/input", "touchscreen", "-d", "0", "swipe",
                "10", "20", "30", "40", "1500"},
                InputAction.swipe(10, 20, 30, 40, 1500, 1080, 2400, 0).command());
        InputAction.swipe(0, 0, 1, 1, 50, 2, 2, 3);
        reject(() -> InputAction.swipe(0, 0, 1, 1, 49, 2, 2, 0));
        reject(() -> InputAction.swipe(0, 0, 1, 1, 1501, 2, 2, 0));
        reject(() -> InputAction.swipe(0, 0, 1, 1, Integer.MAX_VALUE, 2, 2, 0));
    }

    @Test public void onlyBackAndHomeKeycodesAreRepresentable() {
        assertArrayEquals(new String[] {"/system/bin/input", "keyboard", "-d", "0", "keyevent", "4"},
                InputAction.back(1080, 2400, 0).command());
        assertEquals("3", InputAction.home(1080, 2400, 0).command()[5]);
        reject(() -> InputAction.fromWire(85, 0, 0, 0, 0, 0, 0, 1080, 2400, 0));
    }

    @Test public void rejectsNegativeOutOfBoundsAndOverflowCoordinates() {
        reject(() -> InputAction.tap(-1, 0, 1080, 2400, 0));
        reject(() -> InputAction.tap(1080, 0, 1080, 2400, 0));
        reject(() -> InputAction.tap(0, 2400, 1080, 2400, 0));
        reject(() -> InputAction.tap(Integer.MAX_VALUE, 0, 1080, 2400, 0));
        reject(() -> InputAction.swipe(0, 0, 0, -1, 200, 1080, 2400, 0));
    }

    @Test public void wireCannotSmuggleUnusedFieldsOrOtherDisplays() {
        reject(() -> InputAction.fromWire(InputAction.TAP, 1, 1, 2, 0, 0, 0, 1080, 2400, 0));
        reject(() -> InputAction.fromWire(InputAction.TAP, 1, 1, 0, 0, 100, 0, 1080, 2400, 0));
        reject(() -> InputAction.fromWire(InputAction.BACK, 1, 0, 0, 0, 0, 0, 1080, 2400, 0));
        reject(() -> InputAction.fromWire(InputAction.HOME, 0, 0, 0, 0, 0, 1, 1080, 2400, 0));
    }

    @Test public void invalidDimensionsAndRotationFailClosed() {
        reject(() -> InputAction.home(0, 2400, 0));
        reject(() -> InputAction.home(-1, 2400, 0));
        reject(() -> InputAction.home(1080, InputAction.MAX_DIMENSION + 1, 0));
        reject(() -> InputAction.home(1080, 2400, 4));
        reject(() -> InputAction.home(1080, 2400, -1));
    }

    @Test public void rotatedOrResizedDisplayRequiresNewAction() {
        InputAction action = InputAction.tap(10, 20, 1080, 2400, 0);
        assertTrue(action.matchesDisplay(1080, 2400, 0));
        assertFalse(action.matchesDisplay(2400, 1080, 1));
        assertFalse(action.matchesDisplay(1080, 2400, 2));
        assertFalse(action.matchesDisplay(1079, 2400, 0));
    }

    @Test public void commandArrayIsFreshAndCannotMutateStoredAction() {
        InputAction action = InputAction.tap(1, 2, 1080, 2400, 0);
        String[] first = action.command();
        first[0] = "sh";
        first[4] = "text";
        assertEquals("/system/bin/input", action.command()[0]);
        assertEquals("tap", action.command()[4]);
    }

    private static void reject(Runnable operation) {
        try { operation.run(); fail("Invalid input must be rejected"); }
        catch (IllegalArgumentException expected) { assertEquals("invalid_input_action", expected.getMessage()); }
    }
}
