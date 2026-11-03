// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.account;

import io.xiyan.agent.core.usage.QuotaSnapshot;
import io.xiyan.agent.core.usage.ResponseTokenUsage;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** Reads a finite usage field allowlist; never enumerates headers or copies unknown payload fields. */
final class SiwcUsage {
    private SiwcUsage() { }
    static QuotaSnapshot headers(HttpsURLConnection connection, long observedAt, QuotaSnapshot.Source source) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : QuotaSnapshot.getHeaderNames()) {
            String value = connection.getHeaderField(name);
            if (value != null && value.length() <= 128) headers.put(name, value);
        }
        return QuotaSnapshot.fromHeaders(headers, observedAt, source);
    }
    static QuotaSnapshot event(JSONObject event, long observedAt) {
        Map<String, Object> values = fields(event, "type");
        for (String name : new String[]{"metered_limit_name", "limit_name"}) {
            if (event.has(name) && !event.isNull(name)) {
                Object bucket = event.opt(name);
                values.put(name, bucket instanceof String ? bucket : Boolean.FALSE);
            }
        }
        JSONObject rate = event.optJSONObject("rate_limits");
        Map<String, Object> windows = new LinkedHashMap<>();
        if (rate != null) for (String name : new String[]{"primary", "secondary"}) {
            windows.put(name, fields(rate.optJSONObject(name), "used_percent", "window_minutes", "reset_at"));
        }
        values.put("rate_limits", windows);
        values.put("credits", fields(event.optJSONObject("credits"), "has_credits", "unlimited", "balance"));
        return QuotaSnapshot.fromEvent(values, observedAt);
    }
    static ResponseTokenUsage tokens(JSONObject response, long observedAt) {
        JSONObject usage = response.optJSONObject("usage");
        if (usage == null) return null;
        Map<String, Object> values = fields(usage, "input_tokens", "output_tokens", "total_tokens");
        values.put("input_tokens_details", fields(usage.optJSONObject("input_tokens_details"), "cached_tokens", "cache_write_tokens"));
        values.put("output_tokens_details", fields(usage.optJSONObject("output_tokens_details"), "reasoning_tokens"));
        return ResponseTokenUsage.fromUsage(values, observedAt);
    }
    private static Map<String, Object> fields(JSONObject value, String... names) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value != null) for (String name : names) {
            Object field = value.opt(name);
            if (field instanceof String || field instanceof Number || field instanceof Boolean) result.put(name, field);
        }
        return result;
    }
}
