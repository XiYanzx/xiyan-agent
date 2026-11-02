// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public final class CaptureRequestTrackerTest {
    private static final class Frame {
        int closes;
        void close() { closes++; }
    }

    @Test public void clearMakesPendingFrameUndeliverableAndReleasesIt() {
        CaptureRequestTracker<Frame> requests = new CaptureRequestTracker<>(Frame::close);
        long pending = requests.begin();
        requests.cancel();
        Frame frame = new Frame();
        AtomicBoolean displayed = new AtomicBoolean();
        assertFalse(requests.deliver(pending, frame, value -> displayed.set(true)));
        assertFalse(displayed.get());
        assertEquals(1, frame.closes);
        assertFalse(requests.isCurrent(pending));
    }

    @Test public void newerCaptureDiscardsOutOfOrderOldFrame() {
        CaptureRequestTracker<Frame> requests = new CaptureRequestTracker<>(Frame::close);
        long first = requests.begin();
        long second = requests.begin();
        Frame old = new Frame();
        Frame current = new Frame();
        AtomicInteger displayed = new AtomicInteger();
        assertFalse(requests.deliver(first, old, value -> displayed.incrementAndGet()));
        assertTrue(requests.deliver(second, current, value -> displayed.incrementAndGet()));
        assertEquals(1, old.closes);
        assertEquals(0, current.closes);
        assertEquals(1, displayed.get());
    }

    @Test public void repeatedStopOrExitStillRejectsLateResult() {
        CaptureRequestTracker<Frame> requests = new CaptureRequestTracker<>(Frame::close);
        long pending = requests.begin();
        requests.cancel();
        requests.cancel();
        Frame frame = new Frame();
        assertFalse(requests.deliver(pending, frame, value -> fail("late frame displayed")));
        assertEquals(1, frame.closes);
        assertTrue(requests.isCurrent(requests.begin()));
    }

    @Test public void failedDisplayReleasesItsFrame() {
        CaptureRequestTracker<Frame> requests = new CaptureRequestTracker<>(Frame::close);
        Frame frame = new Frame();
        try {
            requests.deliver(requests.begin(), frame, value -> { throw new IllegalStateException("view disposed"); });
            fail("expected failure");
        } catch (IllegalStateException expected) {
            assertEquals(1, frame.closes);
        }
    }

    @Test public void duplicateCallbackCannotDisplayTwice() {
        CaptureRequestTracker<Frame> requests = new CaptureRequestTracker<>(Frame::close);
        long token = requests.begin();
        assertTrue(requests.deliver(token, new Frame(), value -> { }));
        Frame duplicate = new Frame();
        assertFalse(requests.deliver(token, duplicate, value -> fail("duplicate display")));
        assertEquals(1, duplicate.closes);
    }

    @Test public void cancellationWaitsForAlreadyAcceptedDeliveryThenBlocksOldOnes() throws Exception {
        CaptureRequestTracker<Frame> requests = new CaptureRequestTracker<>(Frame::close);
        long pending = requests.begin();
        CountDownLatch enteredDisplay = new CountDownLatch(1);
        CountDownLatch releaseDisplay = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        Thread delivery = new Thread(() -> {
            try {
                requests.deliver(pending, new Frame(), value -> {
                    enteredDisplay.countDown();
                    try { assertTrue(releaseDisplay.await(2, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                });
            } catch (Throwable failure) { threadFailure.set(failure); }
        });
        delivery.start();
        assertTrue(enteredDisplay.await(2, TimeUnit.SECONDS));
        Thread cancel = new Thread(() -> { requests.cancel(); cancelled.set(true); });
        cancel.start();
        assertFalse(cancelled.get());
        releaseDisplay.countDown();
        delivery.join(2_000);
        cancel.join(2_000);
        assertFalse(delivery.isAlive());
        assertFalse(cancel.isAlive());
        assertTrue(cancelled.get());
        assertNull(threadFailure.get());
        Frame late = new Frame();
        assertFalse(requests.deliver(pending, late, value -> fail("display after cancellation")));
        assertEquals(1, late.closes);
    }
}
