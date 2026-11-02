package io.xiyan.agent.core.shizuku;

import static org.junit.Assert.*;
import org.junit.Test;

public final class StopCooldownTest {
    @Test public void earlyAckOrTerminalCannotShortenActiveInputHold() {
        StopCooldown cooldown = new StopCooldown();
        cooldown.begin(2, 10_000, true);
        assertEquals(1750, cooldown.remaining(10_000));
        assertEquals(1749, cooldown.remaining(10_001));
        assertEquals(1, cooldown.remaining(11_749));
        assertEquals(0, cooldown.remaining(11_750));
    }

    @Test public void repeatedStopPreservesOriginalDeadlineAndGuardsLatestCallback() {
        StopCooldown cooldown = new StopCooldown();
        cooldown.begin(2, 1000, true);
        cooldown.begin(3, 1500, false);
        assertFalse(cooldown.isLatest(2));
        assertTrue(cooldown.isLatest(3));
        assertEquals(1250, cooldown.remaining(1500));
        cooldown.begin(4, 1600, true);
        assertEquals(1750, cooldown.remaining(1600));
    }

    @Test public void idleStopNeedsNoExtraDelayAndLargeClockDoesNotWrap() {
        StopCooldown cooldown = new StopCooldown();
        cooldown.begin(1, 1000, false);
        assertEquals(0, cooldown.remaining(1000));
        cooldown.begin(2, Long.MAX_VALUE - 10, true);
        assertEquals(10, cooldown.remaining(Long.MAX_VALUE - 10));
        assertEquals(0, cooldown.remaining(Long.MAX_VALUE));
    }

    @Test public void staleStopEpochCannotOverwriteLaterHold() {
        StopCooldown cooldown = new StopCooldown();
        cooldown.begin(7, 1000, true);
        try { cooldown.begin(6, 2000, false); fail("stale stop accepted"); }
        catch (IllegalArgumentException expected) { assertEquals("invalid_stop_epoch", expected.getMessage()); }
        assertTrue(cooldown.isLatest(7));
        assertEquals(750, cooldown.remaining(2000));
    }
}
