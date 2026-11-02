// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.core.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class TokenClaimsPolicyTest {
    private static final String ISSUER = "https://auth.openai.com";
    private static final String CLIENT_ID = "oaiapp_expected-client";
    private static final String NONCE = "expected-private-nonce";
    private static final long NOW = 1_000;

    @Test
    public void acceptsMatchingSignatureVerifiedClaimsWithSingleAudience() {
        validate(claims(ISSUER, Collections.singletonList(CLIENT_ID), "subject", NONCE, 1_300L, 990L, null));
    }

    @Test
    public void issuerMustMatchExactly() {
        for (String issuer : new String[]{null, "https://auth.openai.com/", "http://auth.openai.com",
                "https://auth.openai.com.evil.test"}) {
            assertReason(TokenClaimsPolicy.Reason.INVALID_ISSUER,
                    () -> validate(claims(issuer, Collections.singletonList(CLIENT_ID),
                            "subject", NONCE, 1_300L, 990L, null)));
        }
    }

    @Test
    public void audienceMustContainTheRegisteredClientWithNoInvalidMembers() {
        List<List<String>> invalid = Arrays.asList(null, Collections.emptyList(),
                Collections.singletonList("dynamic_agent_client"), Collections.singletonList("oaiapp_other"),
                Arrays.asList(CLIENT_ID, null), Arrays.asList(CLIENT_ID, ""));
        for (List<String> audience : invalid) {
            assertReason(TokenClaimsPolicy.Reason.INVALID_AUDIENCE,
                    () -> validate(claims(ISSUER, audience, "subject", NONCE, 1_300L, 990L, CLIENT_ID)));
        }
    }

    @Test
    public void multipleAudiencesRequireMatchingAuthorizedParty() {
        List<String> audience = Arrays.asList("https://api.openai.com/v1", CLIENT_ID);
        for (String azp : new String[]{null, "", "oaiapp_other"}) {
            assertReason(TokenClaimsPolicy.Reason.INVALID_AUTHORIZED_PARTY,
                    () -> validate(claims(ISSUER, audience, "subject", NONCE, 1_300L, 990L, azp)));
        }
        validate(claims(ISSUER, audience, "subject", NONCE, 1_300L, 990L, CLIENT_ID));
        assertReason(TokenClaimsPolicy.Reason.INVALID_AUTHORIZED_PARTY,
                () -> validate(claims(ISSUER, Collections.singletonList(CLIENT_ID),
                        "subject", NONCE, 1_300L, 990L, "oaiapp_other")));
    }

    @Test
    public void subjectAndNonceMustBePresentAndNonceMustMatchExactly() {
        for (String subject : new String[]{null, "", "   "}) {
            assertReason(TokenClaimsPolicy.Reason.INVALID_SUBJECT,
                    () -> validate(claims(ISSUER, Collections.singletonList(CLIENT_ID),
                            subject, NONCE, 1_300L, 990L, null)));
        }
        for (String nonce : new String[]{null, "", NONCE + "x", NONCE.toUpperCase()}) {
            assertReason(TokenClaimsPolicy.Reason.INVALID_NONCE,
                    () -> validate(claims(ISSUER, Collections.singletonList(CLIENT_ID),
                            "subject", nonce, 1_300L, 990L, null)));
        }
    }

    @Test
    public void expirationAndIssuedAtAllowAtMostFiveSecondsOfClockSkew() {
        validate(claims(ISSUER, Collections.singletonList(CLIENT_ID), "subject", NONCE, 995L, 900L, null));
        assertReason(TokenClaimsPolicy.Reason.EXPIRED,
                () -> validate(claims(ISSUER, Collections.singletonList(CLIENT_ID),
                        "subject", NONCE, 994L, 900L, null)));
        validate(claims(ISSUER, Collections.singletonList(CLIENT_ID), "subject", NONCE, 1_300L, 1_005L, null));
        assertReason(TokenClaimsPolicy.Reason.FUTURE_ISSUED_AT,
                () -> validate(claims(ISSUER, Collections.singletonList(CLIENT_ID),
                        "subject", NONCE, 1_300L, 1_006L, null)));
    }

    @Test
    public void missingNegativeAndInvertedTimesAreRejected() {
        for (Long[] times : new Long[][]{{null, 990L}, {1_300L, null}, {1_300L, -1L},
                {990L, 990L}, {980L, 990L}, {-1L, 0L}}) {
            assertReason(TokenClaimsPolicy.Reason.INVALID_TIME,
                    () -> validate(claims(ISSUER, Collections.singletonList(CLIENT_ID),
                            "subject", NONCE, times[0], times[1], null)));
        }
    }

    @Test
    public void extremeValidEpochValuesDoNotOverflowClockSkewArithmetic() {
        TokenClaimsPolicy.validate(claims(ISSUER, Collections.singletonList(CLIENT_ID), "subject", NONCE,
                Long.MAX_VALUE, Long.MAX_VALUE - 10, null), CLIENT_ID, NONCE, Long.MAX_VALUE);
        assertReason(TokenClaimsPolicy.Reason.FUTURE_ISSUED_AT,
                () -> TokenClaimsPolicy.validate(claims(ISSUER, Collections.singletonList(CLIENT_ID),
                        "subject", NONCE, Long.MAX_VALUE, Long.MAX_VALUE - 1, null), CLIENT_ID, NONCE, 0));
    }

    @Test
    public void refreshMayOmitNonceButLoginMustIncludeIt() {
        TokenClaimsPolicy.Claims withoutNonce = claims(ISSUER, Collections.singletonList(CLIENT_ID),
                "original-subject", null, 1_300L, 990L, null);
        TokenClaimsPolicy.validateRefresh(withoutNonce, CLIENT_ID, "original-subject", NONCE, NOW);
        assertReason(TokenClaimsPolicy.Reason.INVALID_NONCE, () -> validate(withoutNonce));
        TokenClaimsPolicy.validateRefresh(claims(ISSUER, Collections.singletonList(CLIENT_ID),
                "original-subject", NONCE, 1_300L, 990L, null),
                CLIENT_ID, "original-subject", NONCE, NOW);
    }

    @Test
    public void refreshRejectsChangedSubjectAndPresentNonceThatDoesNotMatchOriginal() {
        for (String subject : new String[]{null, "", "different-subject"}) {
            assertReason(TokenClaimsPolicy.Reason.INVALID_SUBJECT,
                    () -> TokenClaimsPolicy.validateRefresh(claims(ISSUER, Collections.singletonList(CLIENT_ID),
                            "original-subject", null, 1_300L, 990L, null), CLIENT_ID, subject, NONCE, NOW));
        }
        for (String nonce : new String[]{"", "new-login-nonce", NONCE + "x"}) {
            assertReason(TokenClaimsPolicy.Reason.INVALID_NONCE,
                    () -> TokenClaimsPolicy.validateRefresh(claims(ISSUER, Collections.singletonList(CLIENT_ID),
                            "original-subject", nonce, 1_300L, 990L, null),
                            CLIENT_ID, "original-subject", NONCE, NOW));
        }
    }

    @Test
    public void refreshKeepsIssuerAudienceAuthorizedPartyAndTimeChecks() {
        assertReason(TokenClaimsPolicy.Reason.INVALID_ISSUER,
                () -> refresh(claims("https://wrong.example", Collections.singletonList(CLIENT_ID),
                        "original-subject", null, 1_300L, 990L, null)));
        assertReason(TokenClaimsPolicy.Reason.INVALID_AUDIENCE,
                () -> refresh(claims(ISSUER, Collections.singletonList("oaiapp_other"),
                        "original-subject", null, 1_300L, 990L, null)));
        assertReason(TokenClaimsPolicy.Reason.INVALID_AUTHORIZED_PARTY,
                () -> refresh(claims(ISSUER, Arrays.asList(CLIENT_ID, "other-audience"),
                        "original-subject", null, 1_300L, 990L, null)));
        assertReason(TokenClaimsPolicy.Reason.EXPIRED,
                () -> refresh(claims(ISSUER, Collections.singletonList(CLIENT_ID),
                        "original-subject", null, 994L, 900L, null)));
        assertReason(TokenClaimsPolicy.Reason.FUTURE_ISSUED_AT,
                () -> refresh(claims(ISSUER, Collections.singletonList(CLIENT_ID),
                        "original-subject", null, 1_300L, 1_006L, null)));
        assertReason(TokenClaimsPolicy.Reason.INVALID_TIME,
                () -> refresh(claims(ISSUER, Collections.singletonList(CLIENT_ID),
                        "original-subject", null, null, 990L, null)));
    }

    @Test
    public void typedClaimsCopyAudienceAndRedactDiagnosticValues() {
        List<String> audience = new ArrayList<>(Collections.singletonList(CLIENT_ID));
        TokenClaimsPolicy.Claims claims = claims(ISSUER, audience, "private-subject", NONCE, 1_300L, 990L, null);
        audience.clear();
        validate(claims);
        assertFalse(claims.toString().contains(NONCE));
        assertFalse(claims.toString().contains("private-subject"));
        TokenClaimsPolicy.InvalidClaimsException failure = assertReason(TokenClaimsPolicy.Reason.INVALID_NONCE,
                () -> TokenClaimsPolicy.validate(claims, CLIENT_ID, "other-private-nonce", NOW));
        assertFalse(failure.toString().contains(NONCE));
        assertFalse(failure.toString().contains("other-private-nonce"));
    }

    private static TokenClaimsPolicy.Claims claims(String issuer, List<String> audience,
            String subject, String nonce, Long expiration, Long issuedAt, String azp) {
        return new TokenClaimsPolicy.Claims(issuer, audience, subject, nonce, expiration, issuedAt, azp);
    }

    private static void validate(TokenClaimsPolicy.Claims claims) {
        TokenClaimsPolicy.validate(claims, CLIENT_ID, NONCE, NOW);
    }

    private static void refresh(TokenClaimsPolicy.Claims claims) {
        TokenClaimsPolicy.validateRefresh(claims, CLIENT_ID, "original-subject", NONCE, NOW);
    }

    private static TokenClaimsPolicy.InvalidClaimsException assertReason(
            TokenClaimsPolicy.Reason reason, Runnable action) {
        TokenClaimsPolicy.InvalidClaimsException failure = assertThrows(
                TokenClaimsPolicy.InvalidClaimsException.class, action::run);
        assertEquals(reason, failure.getReason());
        return failure;
    }
}
