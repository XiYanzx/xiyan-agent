// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.core.response;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** HTTP request shape supported by the Sign in with ChatGPT plan-usage route. */
public final class SubscriptionTextRequest {
    private SubscriptionTextRequest() { }

    public static Map<String, Object> create(String model, String reasoningEffort, String prompt) {
        if (model == null || model.trim().isEmpty() || model.length() > 256 || hasControl(model)
                || prompt == null || prompt.trim().isEmpty() || prompt.length() > 4000
                || (reasoningEffort != null && (reasoningEffort.isEmpty() || reasoningEffort.length() > 64 || hasControl(reasoningEffort)))) {
            throw new IllegalArgumentException("Invalid bounded text request");
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user"); message.put("content", prompt);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", Collections.singletonList(Collections.unmodifiableMap(message)));
        body.put("store", false); body.put("stream", true); body.put("service_tier", "default");
        if (reasoningEffort != null) body.put("reasoning", Collections.singletonMap("effort", reasoningEffort));
        return Collections.unmodifiableMap(body);
    }

    private static boolean hasControl(String value) {
        for (int index = 0; index < value.length(); index++) if (Character.isISOControl(value.charAt(index))) return true;
        return false;
    }
}
