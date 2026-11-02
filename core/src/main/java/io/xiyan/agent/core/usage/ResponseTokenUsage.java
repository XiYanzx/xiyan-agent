// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.core.usage;

import java.util.Map;

/** Server-reported tokens for one completed response. Detail counts remain subsets, never summed again. */
public final class ResponseTokenUsage {
    private final Long input, output, total, cached, cacheWrite, reasoning;
    private final long observedAt;
    private ResponseTokenUsage(Long input, Long output, Long total, Long cached, Long cacheWrite, Long reasoning, long observedAt) {
        this.input = input; this.output = output; this.total = total; this.cached = cached;
        this.cacheWrite = cacheWrite; this.reasoning = reasoning; this.observedAt = observedAt;
    }
    public static ResponseTokenUsage fromUsage(Map<String, ?> usage, long observedAt) {
        if (usage == null || observedAt <= 0) return null;
        Map<?, ?> in = UsageValues.map(usage.get("input_tokens_details")), out = UsageValues.map(usage.get("output_tokens_details"));
        Long input = UsageValues.count(usage.get("input_tokens")), output = UsageValues.count(usage.get("output_tokens")), total = UsageValues.count(usage.get("total_tokens"));
        Long cached = UsageValues.count(in.get("cached_tokens")), cacheWrite = UsageValues.count(in.get("cache_write_tokens")), reasoning = UsageValues.count(out.get("reasoning_tokens"));
        return input == null && output == null && total == null && cached == null && cacheWrite == null && reasoning == null ? null
                : new ResponseTokenUsage(input, output, total, cached, cacheWrite, reasoning, observedAt);
    }
    public Long getInputTokens() { return input; }
    public Long getOutputTokens() { return output; }
    public Long getTotalTokens() { return total; }
    public Long getCachedTokens() { return cached; }
    public Long getCacheWriteTokens() { return cacheWrite; }
    public Long getReasoningTokens() { return reasoning; }
    public long getObservedAtEpochMillis() { return observedAt; }
}
