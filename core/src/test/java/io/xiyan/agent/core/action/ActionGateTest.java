package io.xiyan.agent.core.action;

import io.xiyan.agent.core.action.ActionGate.ActionRequest;
import io.xiyan.agent.core.action.ActionGate.ActionToken;
import io.xiyan.agent.core.action.ActionGate.Confirmation;
import io.xiyan.agent.core.action.ActionGate.DispatchHandle;
import io.xiyan.agent.core.action.ActionGate.GateRejectedException;
import io.xiyan.agent.core.action.ActionGate.Lease;
import io.xiyan.agent.core.action.ActionGate.Observation;
import io.xiyan.agent.core.action.ActionGate.Reason;
import io.xiyan.agent.core.action.ActionGate.ScreenState;
import io.xiyan.agent.core.action.ActionGate.Verification;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** 纯 JVM 契约测试；不替代 Android 手势、捕获、取消或真机行为测试。 */
public class ActionGateTest {
    private static final class FakeClock implements MonotonicClock {
        long value = 1_000;
        @Override public long nowMillis() { return value; }
    }

    private static final class Fixture {
        final FakeClock clock = new FakeClock();
        final ActionGate gate = new ActionGate(clock, 500);
        final ScreenState screen = new ScreenState("window-A", 7, 1080, 2400, 0);
        final Lease lease;
        Fixture() {
            gate.setControlAvailable(true);
            gate.updateScreen(screen);
            lease = gate.acquire("task-general");
            gate.recordObservation(lease, observation("frame-1", clock.value));
        }
        Observation observation(String id, long capturedAt) { return new Observation(id, screen, capturedAt, true); }
        ActionToken token(long sequence) {
            return gate.enqueue(lease, new ActionRequest(sequence, "frame-1", 2_000, "tap"));
        }
    }

    @Test public void freshActionRequiresNewVerifiedResultAndDropsOldFrameQueue() {
        Fixture f = new Fixture();
        ActionToken first = f.token(1);
        ActionToken second = f.token(2);
        DispatchHandle handle = f.gate.dispatch(first, ignored -> true);
        reject(Reason.DEVICE_BUSY, () -> f.gate.dispatch(second, ignored -> true));
        f.clock.value++;
        Confirmation confirmed = f.gate.complete(handle, new Verification(f.observation("frame-2", f.clock.value), true));
        assertTrue(confirmed.isConfirmed());
        assertEquals(Reason.NONE, confirmed.getReason());
        assertFalse(f.gate.hasInFlightAction());
        assertTrue(f.gate.isLeaseActive(f.lease));
        assertEquals(0, f.gate.getPendingCount());
        reject(Reason.STALE_TOKEN, () -> f.gate.dispatch(second, ignored -> true));
    }

    @Test public void stopBeforeDispatchPreventsAnyPlatformCallAndInvalidatesQueue() {
        Fixture f = new Fixture();
        ActionToken first = f.token(1);
        f.token(2);
        long oldGeneration = f.lease.getGeneration();
        f.gate.stop();
        AtomicInteger calls = new AtomicInteger();
        reject(Reason.STALE_LEASE, () -> f.gate.dispatch(first, ignored -> { calls.incrementAndGet(); return true; }));
        assertEquals(0, calls.get());
        assertEquals(0, f.gate.getPendingCount());
        Lease replacement = f.gate.acquire("task-game");
        assertTrue(replacement.getGeneration() > oldGeneration);
        reject(Reason.STALE_LEASE, () -> f.gate.enqueue(f.lease, new ActionRequest(3, "frame-1", 2_000, "tap")));
    }

    @Test public void stoppedNativeActionKeepsDeviceOccupiedUntilItsRealTerminalCallback() {
        Fixture f = new Fixture();
        DispatchHandle handle = f.gate.dispatch(f.token(1), ignored -> true);
        f.gate.stop();
        assertTrue(f.gate.hasInFlightAction());
        reject(Reason.DEVICE_BUSY, () -> f.gate.acquire("task-game"));
        Confirmation late = f.gate.complete(handle, new Verification(f.observation("late-frame", 1_000), true));
        assertFalse(late.isConfirmed());
        assertEquals(Reason.STALE_LEASE, late.getReason());
        assertFalse(f.gate.hasInFlightAction());
        Lease replacement = f.gate.acquire("task-game");
        f.gate.recordObservation(replacement, f.observation("new-task-frame", 1_000));
        ActionToken newToken = f.gate.enqueue(replacement, new ActionRequest(1, "new-task-frame", 2_000, "tap"));
        DispatchHandle newHandle = f.gate.dispatch(newToken, ignored -> true);
        assertFalse(f.gate.acknowledgeCancelled(handle));
        assertTrue(f.gate.hasInFlightAction());
        assertTrue(f.gate.acknowledgeCancelled(newHandle));
    }

    @Test public void permissionRevocationRejectsLateSuccessAndRequiresFreshAuthorization() {
        Fixture f = new Fixture();
        DispatchHandle handle = f.gate.dispatch(f.token(1), ignored -> true);
        f.gate.setControlAvailable(false);
        Confirmation late = f.gate.complete(handle, new Verification(f.observation("late", 1_000), true));
        assertFalse(late.isConfirmed());
        assertEquals(Reason.CONTROL_UNAVAILABLE, late.getReason());
        reject(Reason.CONTROL_UNAVAILABLE, () -> f.gate.acquire("task-game"));
        f.gate.setControlAvailable(true);
        Lease replacement = f.gate.acquire("task-game");
        reject(Reason.NO_SCREEN, () -> f.gate.recordObservation(replacement, f.observation("new", 1_000)));
        assertFalse(f.gate.isLeaseActive(replacement));
    }

    @Test public void observationExpiryIsCheckedAgainImmediatelyBeforeDispatch() {
        Fixture f = new Fixture();
        ActionToken token = f.token(1);
        f.clock.value = 1_501;
        AtomicInteger calls = new AtomicInteger();
        reject(Reason.OBSERVATION_EXPIRED, () -> f.gate.dispatch(token, ignored -> { calls.incrementAndGet(); return true; }));
        assertEquals(0, calls.get());
        assertFalse(f.gate.isLeaseActive(f.lease));
        assertEquals(0, f.gate.getPendingCount());
    }

    @Test public void actionDeadlineAndObservationExpiryAreAlsoCheckedAtConfirmation() {
        Fixture expiredFrame = new Fixture();
        DispatchHandle first = expiredFrame.gate.dispatch(expiredFrame.token(1), ignored -> true);
        expiredFrame.clock.value = 1_501;
        Confirmation frameResult = expiredFrame.gate.complete(first,
                new Verification(expiredFrame.observation("result", 1_501), true));
        assertEquals(Reason.OBSERVATION_EXPIRED, frameResult.getReason());
        assertFalse(expiredFrame.gate.isLeaseActive(expiredFrame.lease));

        Fixture expiredAction = new Fixture();
        ActionToken token = expiredAction.gate.enqueue(expiredAction.lease,
                new ActionRequest(1, "frame-1", 1_001, "tap"));
        DispatchHandle second = expiredAction.gate.dispatch(token, ignored -> true);
        expiredAction.clock.value = 1_001;
        Confirmation deadlineResult = expiredAction.gate.complete(second,
                new Verification(expiredAction.observation("result", 1_001), true));
        assertEquals(Reason.ACTION_EXPIRED, deadlineResult.getReason());
        assertFalse(deadlineResult.isConfirmed());
    }

    @Test public void futureAndUnusableObservationsCannotFallBackToAnOlderFrame() {
        Fixture future = new Fixture();
        future.token(1);
        reject(Reason.OBSERVATION_FROM_FUTURE,
                () -> future.gate.recordObservation(future.lease, future.observation("future", 1_001)));
        assertEquals(0, future.gate.getPendingCount());
        assertFalse(future.gate.isLeaseActive(future.lease));

        Fixture blank = new Fixture();
        ActionToken token = blank.token(1);
        reject(Reason.OBSERVATION_UNUSABLE, () -> blank.gate.recordObservation(blank.lease,
                new Observation("secure-frame", blank.screen, 1_000, false)));
        reject(Reason.STALE_LEASE, () -> blank.gate.dispatch(token, ignored -> true));
    }

    @Test public void windowEpochDirectionAndDimensionsEachStopTheQueue() {
        ScreenState[] changes = {
                new ScreenState("window-B", 7, 1080, 2400, 0),
                new ScreenState("window-A", 8, 1080, 2400, 0),
                new ScreenState("window-A", 7, 1080, 2400, 90),
                new ScreenState("window-A", 7, 2400, 1080, 0)
        };
        for (ScreenState changed : changes) {
            Fixture f = new Fixture();
            ActionToken token = f.token(1);
            f.gate.updateScreen(changed);
            assertEquals(Reason.SCREEN_CHANGED, f.gate.getLastStopReason());
            assertFalse(f.gate.isLeaseActive(f.lease));
            assertEquals(0, f.gate.getPendingCount());
            reject(Reason.STALE_LEASE, () -> f.gate.dispatch(token, ignored -> true));
        }
    }

    @Test public void screenChangeBetweenSubmissionAndResultCannotBeConfirmed() {
        Fixture f = new Fixture();
        DispatchHandle handle = f.gate.dispatch(f.token(1), ignored -> true);
        ScreenState changed = new ScreenState("window-A", 8, 1080, 2400, 0);
        f.gate.updateScreen(changed);
        Confirmation confirmation = f.gate.complete(handle,
                new Verification(new Observation("result", changed, 1_000, true), true));
        assertFalse(confirmation.isConfirmed());
        assertFalse(f.gate.hasInFlightAction());
    }

    @Test public void replacementFrameInvalidatesQueuedTokensAndIdsCannotBeRebound() {
        Fixture f = new Fixture();
        ActionToken token = f.token(1);
        f.gate.recordObservation(f.lease, f.observation("frame-2", 1_000));
        reject(Reason.STALE_TOKEN, () -> f.gate.dispatch(token, ignored -> true));
        reject(Reason.OBSERVATION_REUSED, () -> f.gate.recordObservation(f.lease, f.observation("frame-1", 1_000)));
        assertFalse(f.gate.isLeaseActive(f.lease));
    }

    @Test public void falsePostconditionAndReusedResultDoNotReportSuccess() {
        Fixture failed = new Fixture();
        DispatchHandle first = failed.gate.dispatch(failed.token(1), ignored -> true);
        assertEquals(Reason.RESULT_UNVERIFIED, failed.gate.complete(first,
                new Verification(failed.observation("result", 1_000), false)).getReason());
        assertFalse(failed.gate.isLeaseActive(failed.lease));

        Fixture reused = new Fixture();
        DispatchHandle second = reused.gate.dispatch(reused.token(1), ignored -> true);
        assertEquals(Reason.OBSERVATION_REUSED, reused.gate.complete(second,
                new Verification(reused.observation("frame-1", 1_000), true)).getReason());
    }

    @Test public void queueIsOrderedAndTokensCannotCrossGateInstances() {
        Fixture f = new Fixture();
        ActionToken first = f.token(1);
        ActionToken second = f.token(2);
        reject(Reason.OUT_OF_ORDER, () -> f.gate.dispatch(second, ignored -> true));
        reject(Reason.INVALID_SEQUENCE, () -> f.token(2));
        Fixture other = new Fixture();
        reject(Reason.STALE_TOKEN, () -> other.gate.dispatch(first, ignored -> true));
        reject(Reason.STALE_LEASE, () -> other.gate.recordObservation(f.lease, other.observation("foreign", 1_000)));
    }

    @Test public void platformRejectionReleasesSlotButSubmissionExceptionKeepsUncertainSlot() {
        Fixture rejected = new Fixture();
        reject(Reason.DISPATCH_REJECTED, () -> rejected.gate.dispatch(rejected.token(1), ignored -> false));
        assertFalse(rejected.gate.hasInFlightAction());
        assertFalse(rejected.gate.isLeaseActive(rejected.lease));

        Fixture uncertain = new Fixture();
        GateRejectedException failure = reject(Reason.DISPATCH_UNCERTAIN,
                () -> uncertain.gate.dispatch(uncertain.token(1), ignored -> { throw new IllegalStateException("platform outcome unknown"); }));
        assertNotNull(failure.getHandle());
        assertTrue(uncertain.gate.hasInFlightAction());
        reject(Reason.DEVICE_BUSY, () -> uncertain.gate.acquire("task-game"));
        assertTrue(uncertain.gate.acknowledgeCancelled(failure.getHandle()));
        assertNotNull(uncertain.gate.acquire("task-game"));
    }

    @Test public void twoEnginesRacingForOneDeviceHaveExactlyOneLease() throws Exception {
        FakeClock clock = new FakeClock();
        ActionGate gate = new ActionGate(clock, 500);
        gate.setControlAvailable(true);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger acquired = new AtomicInteger();
        AtomicInteger busy = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> general = pool.submit(() -> acquireAfter(start, gate, "general", acquired, busy));
            Future<?> game = pool.submit(() -> acquireAfter(start, gate, "game", acquired, busy));
            start.countDown();
            general.get(5, TimeUnit.SECONDS);
            game.get(5, TimeUnit.SECONDS);
            assertEquals(1, acquired.get());
            assertEquals(1, busy.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test public void stopRacingWithDispatchNeverAllowsASubmissionAfterStopReturns() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 64; attempt++) {
                Fixture f = new Fixture();
                ActionToken token = f.token(1);
                CountDownLatch start = new CountDownLatch(1);
                AtomicBoolean stopReturned = new AtomicBoolean();
                AtomicInteger calls = new AtomicInteger();
                Future<?> dispatch = pool.submit(() -> {
                    await(start);
                    try {
                        f.gate.dispatch(token, ignored -> {
                            assertFalse("停止返回后不能提交旧动作", stopReturned.get());
                            calls.incrementAndGet();
                            return true;
                        });
                    } catch (GateRejectedException expected) {
                        assertEquals(Reason.STALE_LEASE, expected.getReason());
                    }
                });
                Future<?> stop = pool.submit(() -> { await(start); f.gate.stop(); stopReturned.set(true); });
                start.countDown();
                dispatch.get(5, TimeUnit.SECONDS);
                stop.get(5, TimeUnit.SECONDS);
                assertTrue(calls.get() <= 1);
                reject(Reason.STALE_LEASE, () -> f.gate.dispatch(token, ignored -> { calls.incrementAndGet(); return true; }));
                assertTrue(calls.get() <= 1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void acquireAfter(CountDownLatch start, ActionGate gate, String task,
            AtomicInteger acquired, AtomicInteger busy) {
        await(start);
        try { gate.acquire(task); acquired.incrementAndGet(); }
        catch (GateRejectedException expected) {
            assertEquals(Reason.DEVICE_BUSY, expected.getReason());
            busy.incrementAndGet();
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static GateRejectedException reject(Reason reason, Runnable action) {
        try { action.run(); fail("应拒绝动作：" + reason); }
        catch (GateRejectedException expected) { assertEquals(reason, expected.getReason()); return expected; }
        throw new AssertionError("未捕获安全门异常");
    }
}
