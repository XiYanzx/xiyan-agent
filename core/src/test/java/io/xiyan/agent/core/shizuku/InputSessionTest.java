package io.xiyan.agent.core.shizuku;

import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public final class InputSessionTest {
    @Test public void attachIsRequiredAndOnlyOneCommandCanOwnTheSlot() {
        InputSession session = new InputSession();
        reject(() -> session.begin(1, 1));
        session.attach(1);
        InputSession.Ticket first = session.begin(1, 1);
        reject(() -> session.begin(1, 2));
        assertTrue(session.finish(first));
        assertFalse(session.isBusy());
        assertNotNull(session.begin(1, 2));
    }

    @Test public void duplicateAndOutOfOrderRequestIdsCannotExecuteAgain() {
        InputSession session = attached();
        assertTrue(session.finish(session.begin(1, 9)));
        reject(() -> session.begin(1, 9));
        reject(() -> session.begin(1, 8));
        reject(() -> session.begin(1, 0));
        assertNotNull(session.begin(1, 10));
    }

    @Test public void stopBeforeDispatchRejectsTheOldGeneration() {
        InputSession session = attached();
        assertTrue(session.cancel(2));
        reject(() -> session.begin(1, 1));
        assertNotNull(session.begin(2, 1));
    }

    @Test public void cancellationKeepsSlotUntilActualTerminalAndRejectsLateSuccess() {
        InputSession session = attached();
        InputSession.Ticket stopped = session.begin(1, 1);
        session.cancel(2);
        assertFalse(session.isCurrent(stopped));
        assertTrue(session.isBusy());
        reject(() -> session.begin(2, 2));
        assertFalse(session.finish(stopped));
        InputSession.Ticket replacement = session.begin(2, 2);
        assertFalse(session.finish(stopped));
        assertTrue(session.isCurrent(replacement));
        assertTrue(session.isBusy());
    }

    @Test public void staleCancelCannotAffectNewerCommandsAndAttachCannotReplaceBusyOwner() {
        InputSession session = attached();
        session.cancel(5);
        InputSession.Ticket current = session.begin(5, 1);
        assertFalse(session.cancel(4));
        assertFalse(session.cancel(5));
        reject(() -> session.attach(6));
        assertTrue(session.finish(current));
        session.attach(6);
        reject(() -> session.attach(5));
        assertEquals(6, session.generation());
    }

    @Test public void ownerDeathRejectsActiveResultAndFreshSubmissions() {
        InputSession session = attached();
        InputSession.Ticket active = session.begin(1, 1);
        session.detach();
        assertFalse(session.isCurrent(active));
        reject(() -> session.begin(1, 2));
        assertFalse(session.finish(active));
        assertFalse(session.isBusy());
    }

    @Test public void cancellationRaceNeverAdmitsAnOldRequestAfterCancelReturns() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 32; attempt++) {
                InputSession session = attached();
                CountDownLatch start = new CountDownLatch(1);
                AtomicBoolean cancelled = new AtomicBoolean();
                Future<?> input = pool.submit(() -> {
                    await(start);
                    try {
                        InputSession.Ticket ticket = session.begin(1, 1);
                        // Cancellation may happen after begin; current is checked again at launch.
                        if (cancelled.get()) assertFalse(session.isCurrent(ticket));
                    } catch (IllegalStateException expected) {
                        assertEquals("input_session_rejected", expected.getMessage());
                    }
                });
                Future<?> stop = pool.submit(() -> { await(start); session.cancel(2); cancelled.set(true); });
                start.countDown();
                input.get(3, TimeUnit.SECONDS);
                stop.get(3, TimeUnit.SECONDS);
                reject(() -> session.begin(1, 2));
            }
        } finally { pool.shutdownNow(); }
    }

    private static InputSession attached() { InputSession value = new InputSession(); value.attach(1); return value; }
    private static void reject(Runnable operation) {
        try { operation.run(); fail("Stale or concurrent input must be rejected"); }
        catch (IllegalStateException expected) { assertEquals("input_session_rejected", expected.getMessage()); }
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(3, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
}
