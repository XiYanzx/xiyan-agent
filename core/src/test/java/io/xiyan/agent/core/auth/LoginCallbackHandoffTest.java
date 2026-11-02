// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.core.auth;

import static org.junit.Assert.*;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class LoginCallbackHandoffTest {
    private static final long NOW = 1700000000;
    @Test public void browserWriteFailureDoesNotLoseAnAlreadyValidatedReceipt() {
        PendingSignIn pending = pending(); LoginCallbackHandoff handoff = new LoginCallbackHandoff(pending);
        PendingSignIn.Callback received = handoff.acceptAndAcknowledge(callback(pending), NOW,
                () -> { throw new IOException("synthetic-browser-disconnect"); });
        assertEquals("synthetic-code", received.getCode());
        assertSame(received, handoff.received());
        assertTrue(handoff.acknowledgementFailed()); assertTrue(handoff.isTerminal());
        assertFalse(handoff.toString().contains("synthetic-code"));
    }
    @Test public void successfulBrowserAcknowledgementStillUsesExactlyOneReceipt() {
        PendingSignIn pending = pending(); LoginCallbackHandoff handoff = new LoginCallbackHandoff(pending);
        AtomicInteger sent = new AtomicInteger();
        handoff.acceptAndAcknowledge(callback(pending), NOW, sent::incrementAndGet);
        assertEquals(1, sent.get()); assertFalse(handoff.acknowledgementFailed());
        assertEquals(PendingSignIn.Reason.ALREADY_CONSUMED,
                assertThrows(PendingSignIn.AuthException.class, () -> handoff.accept(callback(pending), NOW)).getReason());
    }
    @Test public void invalidManualInputLeavesTheBrowserAttemptAvailable() {
        PendingSignIn pending = pending(); LoginCallbackHandoff handoff = new LoginCallbackHandoff(pending);
        assertThrows(PendingSignIn.AuthException.class,
                () -> handoff.accept(pending.getRedirectUri() + "?state=wrong&code=untrusted", NOW));
        assertFalse(handoff.isTerminal()); assertNull(handoff.received());
        assertEquals("synthetic-code", handoff.accept(callback(pending), NOW).getCode());
    }
    @Test public void validatedOAuthDenialIsTerminalForBothInputPaths() {
        PendingSignIn pending = pending(); LoginCallbackHandoff handoff = new LoginCallbackHandoff(pending);
        assertEquals(PendingSignIn.Reason.AUTHORIZATION_DENIED,
                assertThrows(PendingSignIn.AuthException.class, () -> handoff.accept(
                        pending.getRedirectUri() + "?state=" + state(pending) + "&error=access_denied", NOW)).getReason());
        assertTrue(handoff.isTerminal());
        assertEquals(PendingSignIn.Reason.AUTHORIZATION_DENIED,
                assertThrows(PendingSignIn.AuthException.class, handoff::received).getReason());
    }
    @Test public void racingManualAndBrowserCallbacksYieldOneSuccessAndOneAcknowledgementAtMost() throws Exception {
        PendingSignIn pending = pending(); LoginCallbackHandoff handoff = new LoginCallbackHandoff(pending);
        ExecutorService executor = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        AtomicInteger acknowledgements = new AtomicInteger();
        try {
            Future<Boolean> browser = executor.submit(() -> {
                start.await();
                try { handoff.acceptAndAcknowledge(callback(pending), NOW, acknowledgements::incrementAndGet); return true; }
                catch (PendingSignIn.AuthException consumed) { return false; }
            });
            Future<Boolean> manual = executor.submit(() -> {
                start.await();
                try { handoff.accept(callback(pending), NOW); return true; }
                catch (PendingSignIn.AuthException consumed) { return false; }
            });
            start.countDown();
            assertNotEquals(browser.get(5, TimeUnit.SECONDS), manual.get(5, TimeUnit.SECONDS));
            assertTrue(acknowledgements.get() <= 1); assertEquals("synthetic-code", handoff.received().getCode());
        } finally { executor.shutdownNow(); }
    }
    private static PendingSignIn pending() {
        return PendingSignIn.create(URI.create("https://auth.openai.com/api/accounts/authorize"), 43917,
                "oaiapp_synthetic", "urn:uuid:123e4567-e89b-42d3-a456-426614174000", NOW);
    }
    private static String callback(PendingSignIn pending) {
        return pending.getRedirectUri() + "?state=" + state(pending) + "&code=synthetic-code";
    }
    private static String state(PendingSignIn pending) {
        for (String field : pending.authorizationUri().getRawQuery().split("&")) {
            if (field.startsWith("state=")) return URLDecoder.decode(field.substring(6), StandardCharsets.UTF_8);
        }
        throw new AssertionError("Synthetic state missing");
    }
}
