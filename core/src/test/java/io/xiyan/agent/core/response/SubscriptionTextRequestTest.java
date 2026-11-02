// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.core.response;

import static org.junit.Assert.*;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class SubscriptionTextRequestTest {
    @Test public void usesArrayInputAndRetainsSelectedModelAndPrompt() {
        Map<String, Object> body = SubscriptionTextRequest.create("account-returned-model", null, "只回复测试成功。");
        assertEquals("account-returned-model", body.get("model"));
        List<?> input = (List<?>) body.get("input");
        assertEquals(1, input.size());
        Map<?, ?> message = (Map<?, ?>) input.get(0);
        assertEquals("user", message.get("role")); assertEquals("只回复测试成功。", message.get("content"));
    }
    @Test public void fixesStandardStatelessStreamingAndOmitsUnsupportedFields() {
        Map<String, Object> body = SubscriptionTextRequest.create("model", "high", "short");
        assertEquals(false, body.get("store")); assertEquals(true, body.get("stream"));
        assertEquals("default", body.get("service_tier"));
        assertEquals("high", ((Map<?, ?>) body.get("reasoning")).get("effort"));
        for (String field : new String[]{"max_output_tokens", "previous_response_id", "background", "temperature", "tools"}) assertFalse(body.containsKey(field));
        assertEquals(6, body.size());
    }
    @Test public void omittedEffortStaysOmittedAndRequestIsImmutable() {
        Map<String, Object> body = SubscriptionTextRequest.create("model", null, "short");
        assertFalse(body.containsKey("reasoning"));
        assertThrows(UnsupportedOperationException.class, () -> body.put("service_tier", "priority"));
    }
    @Test public void enforcesInputBoundsAndRejectsControlCharactersInModelParameters() {
        for (String prompt : new String[]{null, "", "  ", String.join("", java.util.Collections.nCopies(4001, "x"))}) {
            assertThrows(IllegalArgumentException.class, () -> SubscriptionTextRequest.create("model", null, prompt));
        }
        assertThrows(IllegalArgumentException.class, () -> SubscriptionTextRequest.create("model\n", null, "short"));
        assertThrows(IllegalArgumentException.class, () -> SubscriptionTextRequest.create("model", "high\r", "short"));
    }
}
