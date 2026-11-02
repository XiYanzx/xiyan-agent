// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.core.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class PendingSignInTest {
    private static final URI AUTHORIZATION_ENDPOINT = URI.create("https://auth.openai.com/oauth/authorize");
    private static final String HOST_ID = "urn:uuid:123e4567-e89b-42d3-a456-426614174000";
    private static final String ISSUED_ID = "oaiapp_test-client";
    private static final int PORT = 43917;
    private static final long NOW = 1_000;

    @Test
    public void pkceMatchesPublishedRfc7636AppendixBVector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                PendingSignIn.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    @Test
    public void initialRequestUsesExactDynamicClientAndOfficialParameters() {
        PendingSignIn pending = pending(null);
        Map<String, String> request = query(pending.authorizationUri());

        assertEquals("https", pending.authorizationUri().getScheme());
        assertEquals("auth.openai.com", pending.authorizationUri().getHost());
        assertEquals("code", request.get("response_type"));
        assertEquals("dynamic_agent_client", request.get("client_id"));
        assertEquals("xiyan_agent", request.get("agent_name_hint"));
        assertEquals("openid profile email offline_access resource.invoke chatgpt.tokens.use.direct",
                request.get("scope"));
        assertEquals("https://api.openai.com/v1", request.get("resource"));
        assertEquals(HOST_ID, request.get("ext_agent_host_id"));
        assertEquals("http://127.0.0.1:43917/auth/callback", request.get("redirect_uri"));
        assertEquals(request.get("redirect_uri"), pending.getRedirectUri().toString());
        assertEquals("S256", request.get("code_challenge_method"));
        assertEquals(PendingSignIn.codeChallenge(pending.getCodeVerifier()), request.get("code_challenge"));
        assertEquals(pending.getNonce(), request.get("nonce"));
        assertEquals(NOW + 300, pending.getExpiresAtEpochSeconds());
        assertFalse(pending.authorizationUri().getRawQuery().contains("+"));
        assertTrue(pending.authorizationUri().getRawQuery().contains("scope=openid%20profile"));
        assertFalse(pending.authorizationUri().getRawQuery().contains("code_verifier"));
    }

    @Test
    public void requestEncodingUsesUtf8Rfc3986OctetsAndUnreservedCharacters() {
        assertEquals("a%20b%2Bc%2F%3F%26%3D%25%E9%9B%AA-._~",
                PendingSignIn.encodeQueryComponent("a b+c/?&=%\u96ea-._~"));
    }

    @Test
    public void newTransactionsHaveIndependentUnpadded256BitSecrets() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 24; i++) {
            PendingSignIn pending = pending(null);
            Map<String, String> request = query(pending.authorizationUri());
            for (String secret : new String[]{request.get("state"), pending.getNonce(), pending.getCodeVerifier()}) {
                assertTrue(secret.matches("[A-Za-z0-9_-]{43}"));
                assertEquals(32, Base64.getUrlDecoder().decode(secret).length);
                assertTrue("Random transaction values must never repeat", seen.add(secret));
            }
        }
        assertEquals(72, seen.size());
    }

    @Test
    public void hostIdentifierIsUuidV4AndMustBePersistedByCaller() {
        String first = PendingSignIn.newExternalAgentHostId();
        String second = PendingSignIn.newExternalAgentHostId();
        assertTrue(first.startsWith("urn:uuid:"));
        assertEquals(4, UUID.fromString(first.substring(9)).version());
        assertEquals(2, UUID.fromString(first.substring(9)).variant());
        assertFalse(first.equals(second));
        assertEquals(HOST_ID, query(pending(null).authorizationUri()).get("ext_agent_host_id"));
        for (String invalid : new String[]{null, "", "desktop-borrowed-id",
                "urn:uuid:123e4567-e89b-12d3-a456-426614174000",
                "urn:uuid:123e4567-e89b-42d3-7456-426614174000"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> PendingSignIn.create(AUTHORIZATION_ENDPOINT, PORT, null, invalid, NOW));
        }
    }

    @Test
    public void rejectsUntrustedEndpointsAndInvalidCreationInputs() {
        for (String endpoint : new String[]{"http://auth.openai.com/oauth/authorize",
                "https://auth.openai.com.evil.test/oauth/authorize",
                "https://auth.openai.com:8443/oauth/authorize",
                "https://user@auth.openai.com/oauth/authorize",
                "https://auth.openai.com/oauth/authorize#fragment",
                "https://auth.openai.com/oauth/authorize?client_id=other"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> PendingSignIn.create(URI.create(endpoint), PORT, null, HOST_ID, NOW));
        }
        assertThrows(IllegalArgumentException.class,
                () -> PendingSignIn.create(AUTHORIZATION_ENDPOINT, 0, null, HOST_ID, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> PendingSignIn.create(AUTHORIZATION_ENDPOINT, 65_536, null, HOST_ID, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> PendingSignIn.create(AUTHORIZATION_ENDPOINT, PORT, "dynamic_agent_client", HOST_ID, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> PendingSignIn.create(AUTHORIZATION_ENDPOINT, PORT, null, HOST_ID, Long.MAX_VALUE));
    }

    @Test
    public void firstCallbackRequiresTheServerIssuedClientAndReturnsExactCode() {
        PendingSignIn pending = pending(null);
        PendingSignIn.Callback callback = pending.consumeCallback(success(pending, "&client_id=" + ISSUED_ID), NOW);
        assertEquals("test+code", callback.getCode());
        assertEquals(ISSUED_ID, callback.getIssuedClientId());
        assertReason(PendingSignIn.Reason.ALREADY_CONSUMED,
                () -> pending.consumeCallback(success(pending, "&client_id=" + ISSUED_ID), NOW));
    }

    @Test
    public void missingOrInvalidDynamicClientDoesNotConsumePendingTransaction() {
        PendingSignIn pending = pending(null);
        for (String suffix : new String[]{"", "&client_id=", "&client_id=dynamic_agent_client",
                "&client_id=oaiapp_", "&client_id=oaiapp_%20bad"}) {
            assertReason(PendingSignIn.Reason.INVALID_CLIENT_ID,
                    () -> pending.consumeCallback(success(pending, suffix), NOW));
        }
        assertEquals(ISSUED_ID,
                pending.consumeCallback(success(pending, "&client_id=" + ISSUED_ID), NOW).getIssuedClientId());
    }

    @Test
    public void subsequentRequestReusesIssuedClientAndCallbackMayOmitIt() {
        PendingSignIn pending = pending(ISSUED_ID);
        assertEquals(ISSUED_ID, query(pending.authorizationUri()).get("client_id"));
        assertFalse(query(pending.authorizationUri()).containsKey("agent_name_hint"));
        assertEquals(HOST_ID, query(pending.authorizationUri()).get("ext_agent_host_id"));
        assertEquals(ISSUED_ID, pending.consumeCallback(success(pending, ""), NOW).getIssuedClientId());
        PendingSignIn matching = pending(ISSUED_ID);
        assertEquals(ISSUED_ID, matching.consumeCallback(
                success(matching, "&client_id=" + ISSUED_ID), NOW).getIssuedClientId());
    }

    @Test
    public void subsequentCallbackCannotChangeRegisteredClient() {
        PendingSignIn pending = pending(ISSUED_ID);
        assertReason(PendingSignIn.Reason.INVALID_CLIENT_ID,
                () -> pending.consumeCallback(success(pending, "&client_id=oaiapp_other"), NOW));
        assertEquals(ISSUED_ID, pending.consumeCallback(success(pending, ""), NOW).getIssuedClientId());
    }

    @Test
    public void wrongAndMissingStateCannotBurnLegitimatePendingSignIn() {
        PendingSignIn pending = pending(ISSUED_ID);
        assertReason(PendingSignIn.Reason.STATE_MISMATCH, () -> pending.consumeCallback(
                pending.getRedirectUri() + "?state=incorrect&code=attacker", NOW));
        assertReason(PendingSignIn.Reason.STATE_MISMATCH, () -> pending.consumeCallback(
                pending.getRedirectUri() + "?code=attacker", NOW));
        assertEquals("test+code", pending.consumeCallback(success(pending, ""), NOW).getCode());
    }

    @Test
    public void duplicateDecodedKeysAndMalformedEncodingCannotBurnPendingSignIn() {
        PendingSignIn pending = pending(ISSUED_ID);
        String valid = success(pending, "");
        for (String malformed : new String[]{valid + "&state=another", valid + "&%73tate=another",
                valid + "&code=another", valid + "&unknown=a&unknown=b", valid + "&code%FF=x",
                valid + "&unknown=%C0%AF", valid + "&unknown=%ED%A0%80",
                valid + "&unknown=%", valid + "&unknown=%GG", valid + "&", valid + "&bare"}) {
            assertReason(PendingSignIn.Reason.MALFORMED_CALLBACK,
                    () -> pending.consumeCallback(malformed, NOW));
        }
        assertEquals("test+code", pending.consumeCallback(valid, NOW).getCode());
    }

    @Test
    public void exactRedirectBindingRejectsAlternateHostPortPathAndUserInfo() {
        PendingSignIn pending = pending(ISSUED_ID);
        String query = URI.create(success(pending, "")).getRawQuery();
        for (String endpoint : new String[]{"https://127.0.0.1:43917/auth/callback",
                "http://localhost:43917/auth/callback", "http://127.0.0.2:43917/auth/callback",
                "http://127.0.0.1:43918/auth/callback", "http://127.0.0.1/auth/callback",
                "http://127.0.0.1:043917/auth/callback", "http://127.0.0.1:43917/auth/callback/",
                "http://127.0.0.1:43917/auth/%63allback", "http://127.0.0.1:43917/a/../auth/callback",
                "http://user@127.0.0.1:43917/auth/callback"}) {
            assertReason(PendingSignIn.Reason.REDIRECT_MISMATCH,
                    () -> pending.consumeCallback(URI.create(endpoint + "?" + query), NOW));
        }
        assertReason(PendingSignIn.Reason.REDIRECT_MISMATCH,
                () -> pending.consumeCallback(success(pending, "") + "#fragment", NOW));
        assertEquals("test+code", pending.consumeCallback(success(pending, ""), NOW).getCode());
    }

    @Test
    public void malformedUnknownAndAmbiguousRequestsLeaveValidCallbackAvailable() {
        PendingSignIn pending = pending(ISSUED_ID);
        String base = pending.getRedirectUri() + "?state=" + state(pending);
        for (String malformed : new String[]{base + "&unknown=request", base + "&code=",
                base + "&code=%00secret", base + "&code=ok&error=access_denied", base + "&error=",
                base + "&error=%20", base + "&error=%00invalid"}) {
            assertReason(PendingSignIn.Reason.MALFORMED_CALLBACK,
                    () -> pending.consumeCallback(malformed, NOW));
        }
        assertEquals("test+code", pending.consumeCallback(success(pending, ""), NOW).getCode());
    }

    @Test
    public void boundedQueryParsingRejectsOversizeAndExcessiveFieldsWithoutConsumption() {
        PendingSignIn pending = pending(ISSUED_ID);
        assertReason(PendingSignIn.Reason.MALFORMED_CALLBACK,
                () -> pending.consumeCallback(success(pending, "&extension=" + "x".repeat(16_384)), NOW));
        StringBuilder excessiveFields = new StringBuilder(success(pending, ""));
        for (int i = 0; i < 33; i++) {
            excessiveFields.append("&extension").append(i).append("=value");
        }
        assertReason(PendingSignIn.Reason.MALFORMED_CALLBACK,
                () -> pending.consumeCallback(excessiveFields.toString(), NOW));
        assertEquals("test+code", pending.consumeCallback(success(pending, ""), NOW).getCode());
    }

    @Test
    public void transactionExpiresAtFiveMinutesAndRejectsClockRollback() {
        PendingSignIn beforeBoundary = pending(ISSUED_ID);
        assertEquals("test+code", beforeBoundary.consumeCallback(success(beforeBoundary, ""), NOW + 299).getCode());
        PendingSignIn expired = pending(ISSUED_ID);
        assertReason(PendingSignIn.Reason.EXPIRED,
                () -> expired.consumeCallback(success(expired, ""), NOW + 300));
        PendingSignIn rollback = pending(ISSUED_ID);
        assertReason(PendingSignIn.Reason.EXPIRED,
                () -> rollback.consumeCallback(success(rollback, ""), NOW - 1));
    }

    @Test
    public void accessDeniedWithCorrectStateIsTerminalButWrongStateIsNot() {
        PendingSignIn pending = pending(null);
        assertReason(PendingSignIn.Reason.STATE_MISMATCH, () -> pending.consumeCallback(
                pending.getRedirectUri() + "?state=wrong&error=access_denied", NOW));
        assertReason(PendingSignIn.Reason.AUTHORIZATION_DENIED, () -> pending.consumeCallback(
                pending.getRedirectUri() + "?state=" + state(pending) + "&error=access_denied", NOW));
        assertReason(PendingSignIn.Reason.ALREADY_CONSUMED,
                () -> pending.consumeCallback(success(pending, "&client_id=" + ISSUED_ID), NOW));
    }

    @Test
    public void otherOAuthErrorsAreSanitizedAndTerminal() {
        PendingSignIn pending = pending(ISSUED_ID);
        PendingSignIn.AuthException failure = assertReason(PendingSignIn.Reason.AUTHORIZATION_ERROR,
                () -> pending.consumeCallback(pending.getRedirectUri() + "?state=" + state(pending)
                        + "&error=server_error&error_description=private-description", NOW));
        assertFalse(failure.toString().contains("private-description"));
        assertReason(PendingSignIn.Reason.ALREADY_CONSUMED,
                () -> pending.consumeCallback(success(pending, ""), NOW));
    }

    @Test
    public void simultaneousCallbacksCanProduceOnlyOneSuccess() throws Exception {
        PendingSignIn pending = pending(ISSUED_ID);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<String> attempt = () -> {
                start.await();
                try {
                    pending.consumeCallback(success(pending, ""), NOW);
                    return "success";
                } catch (PendingSignIn.AuthException failure) {
                    return failure.getReason().name();
                }
            };
            Future<String> first = executor.submit(attempt);
            Future<String> second = executor.submit(attempt);
            start.countDown();
            Set<String> outcomes = new HashSet<>();
            outcomes.add(first.get(5, TimeUnit.SECONDS));
            outcomes.add(second.get(5, TimeUnit.SECONDS));
            assertEquals(2, outcomes.size());
            assertTrue(outcomes.contains("success"));
            assertTrue(outcomes.contains("ALREADY_CONSUMED"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void diagnosticsDoNotExposeTransactionOrCallbackSecrets() {
        PendingSignIn pending = pending(ISSUED_ID);
        PendingSignIn.AuthException failure = assertReason(PendingSignIn.Reason.MALFORMED_CALLBACK,
                () -> pending.consumeCallback(success(pending, "") + "&unknown=%", NOW));
        assertNull(failure.getCause());
        String diagnostics = pending + " " + failure;
        for (String secret : new String[]{state(pending), pending.getNonce(), pending.getCodeVerifier(), "test+code"}) {
            assertFalse(diagnostics.contains(secret));
        }
        PendingSignIn.Callback callback = pending.consumeCallback(success(pending, ""), NOW);
        assertFalse(callback.toString().contains(callback.getCode()));
    }

    private static PendingSignIn pending(String issuedClientId) {
        return PendingSignIn.create(AUTHORIZATION_ENDPOINT, PORT, issuedClientId, HOST_ID, NOW);
    }

    private static String success(PendingSignIn pending, String suffix) {
        return pending.getRedirectUri() + "?state=" + state(pending) + "&code=test%2Bcode" + suffix;
    }

    private static String state(PendingSignIn pending) {
        return query(pending.authorizationUri()).get("state");
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String field : uri.getRawQuery().split("&")) {
            String[] pair = field.split("=", 2);
            result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return result;
    }

    private static PendingSignIn.AuthException assertReason(PendingSignIn.Reason reason, Runnable action) {
        PendingSignIn.AuthException failure = assertThrows(PendingSignIn.AuthException.class, action::run);
        assertEquals(reason, failure.getReason());
        return failure;
    }
}
