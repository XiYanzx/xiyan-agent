// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.core.usage;

import java.util.Map;

/** Optional observed quota metadata; no identity, credentials, or estimated quota values. */
public final class QuotaSnapshot {
    public static final long TTL_MILLIS = 300000;
    public enum Source { NONE, RESPONSE_HEADERS, RESPONSE_EVENT, MODEL_HEADERS }
    private static final String[] HEADER_NAMES = {
        "x-codex-primary-used-percent", "x-codex-primary-window-minutes", "x-codex-primary-reset-at",
        "x-codex-secondary-used-percent", "x-codex-secondary-window-minutes", "x-codex-secondary-reset-at",
        "x-codex-credits-has-credits", "x-codex-credits-unlimited", "x-codex-credits-balance"
    };
    public static final class Window {
        private final double usedPercent;
        private final Long windowMinutes, resetsAtEpochSeconds;
        private final long observedAt;
        private final Source source;
        private Window(double used, Long minutes, Long reset, long observedAt, Source source) {
            usedPercent = used; windowMinutes = minutes != null && minutes > 0 ? minutes : null;
            resetsAtEpochSeconds = reset; this.observedAt = observedAt; this.source = source;
        }
        public double getUsedPercent() { return usedPercent; }
        public double getRemainingPercent() { return 100 - usedPercent; }
        public Long getWindowMinutes() { return windowMinutes; }
        public Long getResetsAtEpochSeconds() { return resetsAtEpochSeconds; }
        public long getObservedAtEpochMillis() { return observedAt; }
        public Source getSource() { return source; }
        public boolean isStale(long nowMillis) {
            return UsageValues.stale(observedAt, nowMillis)
                    || (resetsAtEpochSeconds != null && nowMillis / 1000 >= resetsAtEpochSeconds);
        }
    }
    public static final class Credits {
        private final Boolean hasCredits, unlimited;
        private final String balance;
        private final long observedAt;
        private final Source source;
        private Credits(Boolean hasCredits, Boolean unlimited, String balance, long observedAt, Source source) {
            this.hasCredits = hasCredits; this.unlimited = unlimited; this.balance = balance; this.observedAt = observedAt;
            this.source = source;
        }
        public Boolean getHasCredits() { return hasCredits; }
        public Boolean getUnlimited() { return unlimited; }
        public String getBalance() { return balance; }
        public long getObservedAtEpochMillis() { return observedAt; }
        public Source getSource() { return source; }
        public boolean isStale(long nowMillis) { return UsageValues.stale(observedAt, nowMillis); }
    }
    private final Window primary, secondary;
    private final Credits credits;
    private final Source source;
    private final long observedAt;
    private QuotaSnapshot(Window primary, Window secondary, Credits credits, Source source, long observedAt) {
        this.primary = primary; this.secondary = secondary; this.credits = credits;
        this.source = source; this.observedAt = observedAt;
    }
    public static QuotaSnapshot unknown() { return new QuotaSnapshot(null, null, null, Source.NONE, 0); }
    public static String[] getHeaderNames() { return HEADER_NAMES.clone(); }
    public Window getPrimary() { return primary; }
    public Window getSecondary() { return secondary; }
    public Credits getCredits() { return credits; }
    public Source getSource() { return source; }
    public long getObservedAtEpochMillis() { return observedAt; }
    public boolean hasData() { return primary != null || secondary != null || credits != null; }
    public boolean isStale(long nowMillis) {
        return primary != null && primary.isStale(nowMillis) || secondary != null && secondary.isStale(nowMillis)
                || credits != null && credits.isStale(nowMillis);
    }
    /** Missing fields preserve their original observation time; older headers cannot replace newer stream events. */
    public QuotaSnapshot merge(QuotaSnapshot update) {
        if (update == null || !update.hasData()) return this;
        boolean allowEqual = update.source == Source.RESPONSE_EVENT || source != Source.RESPONSE_EVENT;
        Window p = newer(primary, update.primary), s = newer(secondary, update.secondary);
        Credits c = update.credits != null && (credits == null || update.credits.observedAt > credits.observedAt
                || prefer(update.credits.source, credits.source) && update.credits.observedAt == credits.observedAt) ? update.credits : credits;
        boolean latest = update.observedAt > observedAt || allowEqual && update.observedAt == observedAt;
        return new QuotaSnapshot(p, s, c, latest ? update.source : source, Math.max(observedAt, update.observedAt));
    }
    private static Window newer(Window old, Window update) {
        return update != null && (old == null || update.observedAt > old.observedAt
                || prefer(update.source, old.source) && update.observedAt == old.observedAt) ? update : old;
    }
    private static boolean prefer(Source update, Source old) { return update == Source.RESPONSE_EVENT || old != Source.RESPONSE_EVENT; }
    public static QuotaSnapshot fromHeaders(Map<String, String> headers, long observedAt, Source source) {
        if (headers == null || observedAt <= 0 || source != Source.RESPONSE_HEADERS && source != Source.MODEL_HEADERS) return unknown();
        Window p = headerWindow(headers, "primary", observedAt, source), s = headerWindow(headers, "secondary", observedAt, source);
        Credits c = credits(UsageValues.headerFlag(headers.get(HEADER_NAMES[6])), UsageValues.headerFlag(headers.get(HEADER_NAMES[7])),
                UsageValues.balance(headers.get(HEADER_NAMES[8])), observedAt, source);
        return snapshot(p, s, c, source, observedAt);
    }
    public static QuotaSnapshot fromEvent(Map<String, ?> event, long observedAt) {
        if (event == null || observedAt <= 0 || !"codex.rate_limits".equals(event.get("type"))) return unknown();
        // The official event selects metered_limit_name before limit_name and defaults to codex.
        // Other buckets are kept separate upstream; this snapshot intentionally represents only the default bucket.
        Object bucket = event.get("metered_limit_name");
        if (bucket == null) bucket = event.get("limit_name");
        if (bucket != null && (!(bucket instanceof String)
                || !"codex".equals(((String) bucket).trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_')))) return unknown();
        Map<?, ?> rate = UsageValues.map(event.get("rate_limits")), c = UsageValues.map(event.get("credits"));
        return snapshot(eventWindow(UsageValues.map(rate.get("primary")), observedAt), eventWindow(UsageValues.map(rate.get("secondary")), observedAt),
                credits(UsageValues.flag(c.get("has_credits")), UsageValues.flag(c.get("unlimited")), UsageValues.balance(c.get("balance")), observedAt, Source.RESPONSE_EVENT),
                Source.RESPONSE_EVENT, observedAt);
    }
    private static QuotaSnapshot snapshot(Window p, Window s, Credits c, Source source, long at) {
        return p == null && s == null && c == null ? unknown() : new QuotaSnapshot(p, s, c, source, at);
    }
    private static Window headerWindow(Map<String, String> headers, String name, long at, Source source) {
        String prefix = "x-codex-" + name;
        Double used = UsageValues.headerPercent(headers.get(prefix + "-used-percent"));
        return used == null ? null : new Window(used, UsageValues.headerCount(headers.get(prefix + "-window-minutes")),
                UsageValues.headerCount(headers.get(prefix + "-reset-at")), at, source);
    }
    private static Window eventWindow(Map<?, ?> value, long at) {
        Double used = UsageValues.percent(value.get("used_percent"));
        return used == null ? null : new Window(used, UsageValues.count(value.get("window_minutes")), UsageValues.count(value.get("reset_at")), at, Source.RESPONSE_EVENT);
    }
    private static Credits credits(Boolean has, Boolean unlimited, String balance, long at, Source source) {
        return has == null && unlimited == null && balance == null ? null : new Credits(has, unlimited, balance, at, source);
    }
}
