// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.core.usage;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;

/** Strict scalar readers. Invalid optional service fields remain unknown. */
final class UsageValues {
    private UsageValues() { }
    static Map<?, ?> map(Object value) { return value instanceof Map ? (Map<?, ?>) value : java.util.Collections.emptyMap(); }
    static Long count(Object value) {
        if (!(value instanceof Number)) return null;
        try {
            long number = new BigDecimal(value.toString()).longValueExact();
            return number >= 0 ? number : null;
        } catch (ArithmeticException | NumberFormatException invalid) { return null; }
    }
    static Long headerCount(String value) {
        if (value == null || !value.trim().matches("[0-9]{1,19}")) return null;
        try { return count(new BigInteger(value.trim())); }
        catch (NumberFormatException invalid) { return null; }
    }
    static Double percent(Object value) {
        if (!(value instanceof Number)) return null;
        double number = ((Number) value).doubleValue();
        return Double.isFinite(number) && number >= 0 && number <= 100 ? number : null;
    }
    static Double headerPercent(String value) {
        if (value == null || value.length() > 64 || !value.trim().matches("[0-9]+(?:\\.[0-9]+)?")) return null;
        try { return percent(Double.valueOf(value.trim())); }
        catch (NumberFormatException invalid) { return null; }
    }
    static Boolean flag(Object value) { return value instanceof Boolean ? (Boolean) value : null; }
    static Boolean headerFlag(String value) {
        if (value == null) return null;
        switch (value.trim()) { case "true": case "1": return true; case "false": case "0": return false; default: return null; }
    }
    static String balance(Object value) {
        if (!(value instanceof String) && !(value instanceof Number)) return null;
        String text = value.toString();
        if (text.length() > 64 || !text.matches("[0-9]+(?:\\.[0-9]+)?")) return null;
        try { return new BigDecimal(text).stripTrailingZeros().toPlainString(); }
        catch (NumberFormatException invalid) { return null; }
    }
    static boolean stale(long observedAt, long now) {
        return observedAt <= 0 || now < observedAt || now - observedAt >= QuotaSnapshot.TTL_MILLIS;
    }
}
