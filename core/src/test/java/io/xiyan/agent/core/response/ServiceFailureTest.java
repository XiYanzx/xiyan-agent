// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.core.response;

import static org.junit.Assert.*;
import org.junit.Test;

public class ServiceFailureTest {
    @Test public void preservesBoundedProtocolMetadata() {
        ServiceFailure failure = ServiceFailure.from(400, "subscription_sharing_unsupported_capability", "input[0].content", "req_0123456789abcdef");
        assertEquals(400, failure.getHttpStatus()); assertEquals("input[0].content", failure.getParam());
        assertEquals("req_0123456789abcdef", failure.getRequestId());
        assertEquals(ServiceFailure.Kind.UNSUPPORTED_CAPABILITY, failure.getKind());
    }
    @Test public void policyRegionAndRoutingRestrictionsRetainSession() {
        for (String code : new String[]{null, "subscription_sharing_user_not_eligible", "subscription_sharing_route_not_supported",
                "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context"}) {
            assertFalse(ServiceFailure.from(403, code, null, null).requiresSignIn());
        }
        assertEquals(ServiceFailure.Kind.ROUTE_UNSUPPORTED, ServiceFailure.from(403, "subscription_sharing_route_not_supported", null, null).getKind());
    }
    @Test public void authenticationAndClientConfigurationAreDistinct() {
        assertTrue(ServiceFailure.from(401, null, null, null).requiresSignIn());
        assertTrue(ServiceFailure.from(400, "invalid_grant", null, null).requiresSignIn());
        ServiceFailure client = ServiceFailure.from(400, "invalid_client", null, null);
        assertFalse(client.requiresSignIn()); assertEquals(ServiceFailure.Kind.CLIENT_CONFIGURATION, client.getKind());
    }
    @Test public void usageLimitsAndTemporaryFailuresHaveDifferentRecovery() {
        assertEquals(ServiceFailure.Kind.USAGE_LIMIT, ServiceFailure.from(429, "subscription_sharing_usage_limit_exceeded", null, null).getKind());
        ServiceFailure temporary = ServiceFailure.from(503, "subscription_sharing_usage_unavailable", null, null);
        assertEquals(ServiceFailure.Kind.TEMPORARY_UNAVAILABLE, temporary.getKind()); assertFalse(temporary.requiresSignIn());
    }
    @Test public void rejectsCredentialsUrlsControlCharactersAndOversizedMetadata() {
        for (String value : new String[]{"Bearer sk-secret", "sk-proj-secret", "https://example.test/path", "a@b.test", "bad\nvalue",
                String.join("", java.util.Collections.nCopies(161, "x"))}) {
            ServiceFailure failure = ServiceFailure.from(999, value, value, value);
            assertEquals(0, failure.getHttpStatus()); assertNull(failure.getCode()); assertNull(failure.getParam()); assertNull(failure.getRequestId());
        }
    }
}
