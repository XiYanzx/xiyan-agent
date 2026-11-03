package io.xiyan.agent.core.shizuku;

/** Monotonic, conservative overlap guard; it makes no physical cancellation claim. */
public final class StopCooldown {
    public static final long HOLD_MILLIS = InputAction.MAX_SWIPE_MILLIS + 250L;
    private long generation;
    private long notBefore;

    public synchronized void begin(long nextGeneration, long now, boolean hadActiveInput) {
        if (nextGeneration <= generation || now < 0) throw new IllegalArgumentException("invalid_stop_epoch");
        generation = nextGeneration;
        long deadline = hadActiveInput
                ? now > Long.MAX_VALUE - HOLD_MILLIS ? Long.MAX_VALUE : now + HOLD_MILLIS
                : now;
        notBefore = Math.max(notBefore, deadline);
    }

    public synchronized long remaining(long now) {
        if (now < 0) throw new IllegalArgumentException("invalid_monotonic_time");
        return now >= notBefore ? 0 : notBefore - now;
    }

    public synchronized boolean isLatest(long expectedGeneration) {
        return generation == expectedGeneration;
    }
}
