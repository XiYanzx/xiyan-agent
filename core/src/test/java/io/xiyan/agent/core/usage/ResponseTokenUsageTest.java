// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.core.usage;

import static org.junit.Assert.*;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class ResponseTokenUsageTest {
    @Test public void retainsOfficialTotalsAndDetailSubsetsWithoutAddingThemAgain() {
        ResponseTokenUsage usage = ResponseTokenUsage.fromUsage(Map.of("input_tokens", 100, "output_tokens", 40, "total_tokens", 140,
                "input_tokens_details", Map.of("cached_tokens", 80, "cache_write_tokens", 10), "output_tokens_details", Map.of("reasoning_tokens", 30)), 1000);
        assertEquals(Long.valueOf(100), usage.getInputTokens()); assertEquals(Long.valueOf(40), usage.getOutputTokens());
        assertEquals(Long.valueOf(140), usage.getTotalTokens()); assertEquals(Long.valueOf(80), usage.getCachedTokens());
        assertEquals(Long.valueOf(10), usage.getCacheWriteTokens()); assertEquals(Long.valueOf(30), usage.getReasoningTokens());
        assertEquals(1000, usage.getObservedAtEpochMillis());
    }
    @Test public void missingUsageRemainsUnknownWhileReportedZeroIsRetained() {
        assertNull(ResponseTokenUsage.fromUsage(null, 1000)); assertNull(ResponseTokenUsage.fromUsage(Map.of(), 1000));
        ResponseTokenUsage usage = ResponseTokenUsage.fromUsage(Map.of("input_tokens", 0), 1000);
        assertEquals(Long.valueOf(0), usage.getInputTokens()); assertNull(usage.getOutputTokens()); assertNull(usage.getTotalTokens());
        assertNull(ResponseTokenUsage.fromUsage(Map.of("input_tokens", 1), 0));
    }
    @Test public void rejectsNegativeFractionalOverflowAndStringCountersIndependently() {
        for (Object value : new Object[]{-1, 1.5, Double.NaN, Double.POSITIVE_INFINITY, new BigInteger("9223372036854775808"), "4", true}) {
            ResponseTokenUsage usage = ResponseTokenUsage.fromUsage(Map.of("input_tokens", value, "output_tokens", 2), 1000);
            assertNull(usage.getInputTokens()); assertEquals(Long.valueOf(2), usage.getOutputTokens()); assertNull(usage.getTotalTokens());
        }
        assertEquals(Long.valueOf(Long.MAX_VALUE), ResponseTokenUsage.fromUsage(Map.of("total_tokens", Long.MAX_VALUE), 1000).getTotalTokens());
    }
    @Test public void ignoresUnknownKeysAndMalformedDetailsWithoutDerivingMissingTotals() {
        Map<String, Object> values = new HashMap<>(); values.put("input_tokens_details", "secret");
        values.put("output_tokens_details", Map.of("reasoning_tokens", 5, "unknown", "private")); values.put("unknown", "private");
        ResponseTokenUsage usage = ResponseTokenUsage.fromUsage(values, 1000);
        assertNull(usage.getInputTokens()); assertNull(usage.getOutputTokens()); assertNull(usage.getTotalTokens());
        assertNull(usage.getCachedTokens()); assertEquals(Long.valueOf(5), usage.getReasoningTokens());
    }
}
