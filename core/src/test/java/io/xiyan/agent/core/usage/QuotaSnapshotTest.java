// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.core.usage;

import static org.junit.Assert.*;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class QuotaSnapshotTest {
    private static final long NOW = 1800000000000L;
    private static QuotaSnapshot headers(Map<String, String> values, long at) {
        return QuotaSnapshot.fromHeaders(values, at, QuotaSnapshot.Source.RESPONSE_HEADERS);
    }
    @Test public void preservesActualZeroAndUsesServerWindowDurations() {
        QuotaSnapshot quota = headers(Map.of("x-codex-primary-used-percent", "0", "x-codex-primary-window-minutes", "300",
                "x-codex-primary-reset-at", "1800000300", "x-codex-secondary-used-percent", "79.25",
                "x-codex-secondary-window-minutes", "10080"), NOW);
        assertEquals(0, quota.getPrimary().getUsedPercent(), 0);
        assertEquals(100, quota.getPrimary().getRemainingPercent(), 0);
        assertEquals(Long.valueOf(300), quota.getPrimary().getWindowMinutes());
        assertEquals(20.75, quota.getSecondary().getRemainingPercent(), 0);
        assertEquals(Long.valueOf(10080), quota.getSecondary().getWindowMinutes());
        assertFalse(quota.isStale(NOW));
    }
    @Test public void missingAndInvalidPercentDoNotBecomeZero() {
        assertFalse(headers(Map.of(), NOW).hasData());
        for (String value : new String[]{"-1", "101", "NaN", "Infinity", "true", "", "0\r\nsecret"}) {
            assertNull(headers(Map.of("x-codex-primary-used-percent", value), NOW).getPrimary());
        }
        assertEquals(QuotaSnapshot.Source.NONE, headers(Map.of(), NOW).getSource());
        assertFalse(QuotaSnapshot.unknown().isStale(NOW));
    }
    @Test public void invalidOptionalWindowFieldsRemainUnknownIndependently() {
        QuotaSnapshot quota = headers(Map.of("x-codex-primary-used-percent", "50", "x-codex-primary-window-minutes", "0",
                "x-codex-primary-reset-at", "9223372036854775808", "x-codex-secondary-used-percent", "100",
                "x-codex-secondary-window-minutes", "-2"), NOW);
        assertNull(quota.getPrimary().getWindowMinutes()); assertNull(quota.getPrimary().getResetsAtEpochSeconds());
        assertNull(quota.getSecondary().getWindowMinutes()); assertEquals(0, quota.getSecondary().getRemainingPercent(), 0);
    }
    @Test public void acceptsExactOfficialRateLimitsEventAndRejectsStringCoercion() {
        Map<String, Object> event = Map.of("type", "codex.rate_limits", "rate_limits", Map.of(
                "primary", Map.of("used_percent", 12.5, "window_minutes", 300, "reset_at", 1800001000L),
                "secondary", Map.of("used_percent", "25", "window_minutes", 10080)),
                "credits", Map.of("has_credits", true, "unlimited", false, "balance", "12.500"));
        QuotaSnapshot quota = QuotaSnapshot.fromEvent(event, NOW);
        assertEquals(12.5, quota.getPrimary().getUsedPercent(), 0); assertNull(quota.getSecondary());
        assertEquals(QuotaSnapshot.Source.RESPONSE_EVENT, quota.getSource());
        assertEquals("12.5", quota.getCredits().getBalance());
        assertTrue(quota.getCredits().getHasCredits()); assertFalse(quota.getCredits().getUnlimited());
        assertFalse(QuotaSnapshot.fromEvent(Map.of("type", "response.rate_limits", "rate_limits", event.get("rate_limits")), NOW).hasData());
    }
    @Test public void rejectsNonFiniteEventPercentAndInvalidTypedScalars() {
        for (Object value : new Object[]{Double.NaN, Double.POSITIVE_INFINITY, -0.1, 100.1, true, "0"}) {
            assertNull(QuotaSnapshot.fromEvent(Map.of("type", "codex.rate_limits", "rate_limits", Map.of("primary", Map.of("used_percent", value))), NOW).getPrimary());
        }
        QuotaSnapshot quota = QuotaSnapshot.fromEvent(Map.of("type", "codex.rate_limits", "rate_limits", Map.of("primary",
                Map.of("used_percent", 10, "window_minutes", 1.5, "reset_at", "1800001000"))), NOW);
        assertNull(quota.getPrimary().getWindowMinutes()); assertNull(quota.getPrimary().getResetsAtEpochSeconds());
    }
    @Test public void ttlResetCrossingAndClockRollbackMarkObservedDataStale() {
        QuotaSnapshot quota = headers(Map.of("x-codex-primary-used-percent", "5", "x-codex-primary-reset-at", "1800000010"), NOW);
        assertFalse(quota.isStale(NOW + 9999)); assertTrue(quota.isStale(NOW + 10000));
        assertTrue(quota.isStale(NOW - 1));
        QuotaSnapshot ttl = headers(Map.of("x-codex-primary-used-percent", "5"), NOW);
        assertFalse(ttl.isStale(NOW + QuotaSnapshot.TTL_MILLIS - 1)); assertTrue(ttl.isStale(NOW + QuotaSnapshot.TTL_MILLIS));
    }
    @Test public void partialUpdatesPreserveOldWindowTimeAndDoNotFakeFreshness() {
        QuotaSnapshot first = headers(Map.of("x-codex-primary-used-percent", "10", "x-codex-secondary-used-percent", "20"), NOW);
        assertSame(first, first.merge(headers(Map.of(), NOW + 100)));
        QuotaSnapshot merged = first.merge(headers(Map.of("x-codex-primary-used-percent", "11"), NOW + 200000));
        assertEquals(NOW, merged.getSecondary().getObservedAtEpochMillis());
        assertEquals(NOW + 200000, merged.getPrimary().getObservedAtEpochMillis());
        assertTrue(merged.isStale(NOW + 300000)); assertFalse(merged.getPrimary().isStale(NOW + 300000));
    }
    @Test public void rereadingOldResponseHeadersCannotOverwriteLaterEvent() {
        QuotaSnapshot header = headers(Map.of("x-codex-primary-used-percent", "10"), NOW);
        QuotaSnapshot event = QuotaSnapshot.fromEvent(Map.of("type", "codex.rate_limits", "rate_limits", Map.of("primary", Map.of("used_percent", 15))), NOW + 50);
        QuotaSnapshot merged = header.merge(event).merge(header);
        assertEquals(15, merged.getPrimary().getUsedPercent(), 0);
        assertEquals(NOW + 50, merged.getObservedAtEpochMillis()); assertEquals(QuotaSnapshot.Source.RESPONSE_EVENT, merged.getSource());
        QuotaSnapshot simultaneous = QuotaSnapshot.fromEvent(Map.of("type", "codex.rate_limits", "rate_limits", Map.of("primary", Map.of("used_percent", 16))), NOW);
        assertEquals(16, header.merge(simultaneous).merge(header).getPrimary().getUsedPercent(), 0);
    }
    @Test public void creditsAreOptionalTypedAndBoundedAndDoNotContainFreeText() {
        QuotaSnapshot credits = headers(Map.of("x-codex-credits-has-credits", "1", "x-codex-credits-unlimited", "0", "x-codex-credits-balance", "0.000"), NOW);
        assertEquals("0", credits.getCredits().getBalance()); assertTrue(credits.getCredits().getHasCredits());
        assertFalse(credits.getCredits().getUnlimited()); assertTrue(credits.getCredits().isStale(NOW + 300000));
        for (String balance : new String[]{"-1", "NaN", "Infinity", "user@example.com", "1e5", "1".repeat(65)}) {
            assertFalse(headers(Map.of("x-codex-credits-balance", balance), NOW).hasData());
        }
        assertFalse(QuotaSnapshot.fromEvent(Map.of("type", "codex.rate_limits", "credits", Map.of("has_credits", "true")), NOW).hasData());
    }
    @Test public void sourceTimeAndAllowlistCannotBeForgedByMissingPayload() {
        assertFalse(QuotaSnapshot.fromHeaders(Map.of("x-codex-primary-used-percent", "1"), NOW, QuotaSnapshot.Source.NONE).hasData());
        assertFalse(headers(Map.of("x-codex-primary-used-percent", "1"), 0).hasData());
        String[] names = QuotaSnapshot.getHeaderNames(); names[0] = "Authorization";
        assertEquals(9, QuotaSnapshot.getHeaderNames().length);
        assertEquals("x-codex-primary-used-percent", QuotaSnapshot.getHeaderNames()[0]);
        Map<String, String> withUnknown = new HashMap<>(); withUnknown.put("authorization", "private");
        assertFalse(headers(withUnknown, NOW).hasData());
    }
    @Test public void mergedWindowsAndCreditsRetainTheirOwnSource() {
        QuotaSnapshot old = QuotaSnapshot.fromHeaders(Map.of("x-codex-primary-used-percent", "10", "x-codex-secondary-used-percent", "20",
                "x-codex-credits-balance", "3"), NOW, QuotaSnapshot.Source.MODEL_HEADERS);
        QuotaSnapshot update = QuotaSnapshot.fromEvent(Map.of("type", "codex.rate_limits", "rate_limits", Map.of("primary", Map.of("used_percent", 15))), NOW + 1);
        QuotaSnapshot merged = old.merge(update);
        assertEquals(QuotaSnapshot.Source.RESPONSE_EVENT, merged.getSource());
        assertEquals(QuotaSnapshot.Source.RESPONSE_EVENT, merged.getPrimary().getSource());
        assertEquals(QuotaSnapshot.Source.MODEL_HEADERS, merged.getSecondary().getSource());
        assertEquals(QuotaSnapshot.Source.MODEL_HEADERS, merged.getCredits().getSource());
        assertEquals(NOW, merged.getSecondary().getObservedAtEpochMillis());
    }
    @Test public void onlyDefaultCodexEventBucketIsMergedUsingOfficialPrecedence() {
        Map<String, Object> event = new HashMap<>(); event.put("type", "codex.rate_limits");
        event.put("rate_limits", Map.of("primary", Map.of("used_percent", 25)));
        assertTrue(QuotaSnapshot.fromEvent(event, NOW).hasData());
        event.put("limit_name", " CODEX "); assertTrue(QuotaSnapshot.fromEvent(event, NOW).hasData());
        event.put("limit_name", "codex_other"); assertFalse(QuotaSnapshot.fromEvent(event, NOW).hasData());
        event.put("metered_limit_name", "codex"); assertTrue(QuotaSnapshot.fromEvent(event, NOW).hasData());
        event.put("limit_name", "codex"); event.put("metered_limit_name", "codex-other");
        assertFalse(QuotaSnapshot.fromEvent(event, NOW).hasData());
        event.put("metered_limit_name", 1); assertFalse(QuotaSnapshot.fromEvent(event, NOW).hasData());
        event.put("metered_limit_name", ""); assertFalse(QuotaSnapshot.fromEvent(event, NOW).hasData());
    }
}
