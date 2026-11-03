// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.core.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Claim checks only. The caller MUST verify the ID-token signature with trusted discovery/JWKS
 * metadata and an allowed signing algorithm before constructing these typed claims. Successful
 * validation alone never proves a JWT authentic. This class neither parses nor logs tokens.
 */
public final class TokenClaimsPolicy {
    public static final long CLOCK_SKEW_SECONDS = 5;

    public enum Reason {
        INVALID_ISSUER,
        INVALID_AUDIENCE,
        INVALID_AUTHORIZED_PARTY,
        INVALID_SUBJECT,
        INVALID_NONCE,
        INVALID_TIME,
        EXPIRED,
        FUTURE_ISSUED_AT
    }

    public static final class InvalidClaimsException extends IllegalArgumentException {
        private final Reason reason;

        private InvalidClaimsException(Reason reason) {
            super("ID token claims rejected: " + reason.name());
            this.reason = reason;
        }

        public Reason getReason() {
            return reason;
        }
    }

    /** Null expiration or issued-at values represent missing claims, and validation rejects them. */
    public static final class Claims {
        private final String issuer;
        private final List<String> audience;
        private final String subject;
        private final String nonce;
        private final Long expiresAtEpochSeconds;
        private final Long issuedAtEpochSeconds;
        private final String authorizedParty;

        public Claims(String issuer, List<String> audience, String subject, String nonce,
                Long expiresAtEpochSeconds, Long issuedAtEpochSeconds, String authorizedParty) {
            this.issuer = issuer;
            this.audience = audience == null ? null
                    : Collections.unmodifiableList(new ArrayList<>(audience));
            this.subject = subject;
            this.nonce = nonce;
            this.expiresAtEpochSeconds = expiresAtEpochSeconds;
            this.issuedAtEpochSeconds = issuedAtEpochSeconds;
            this.authorizedParty = authorizedParty;
        }

        @Override
        public String toString() {
            return "Claims[redacted]";
        }
    }

    private TokenClaimsPolicy() {
    }

    public static void validate(Claims claims, String expectedIssuedClientId,
            String expectedNonce, long nowEpochSeconds) {
        validateClaims(claims, expectedIssuedClientId, null, expectedNonce, nowEpochSeconds, false);
    }

    /**
     * Validate signature-verified refresh ID-token claims against the original login identity.
     * An absent nonce is allowed for refresh; a present nonce must match the original login nonce.
     * Persist that original nonce securely rather than generating a new one for refresh.
     */
    public static void validateRefresh(Claims claims, String expectedIssuedClientId,
            String expectedSubject, String originalNonce, long nowEpochSeconds) {
        validateClaims(claims, expectedIssuedClientId, expectedSubject, originalNonce,
                nowEpochSeconds, true);
    }

    private static void validateClaims(Claims claims, String expectedIssuedClientId,
            String expectedSubject, String expectedNonce, long nowEpochSeconds, boolean refresh) {
        if (claims == null) {
            throw failure(Reason.INVALID_ISSUER);
        }
        if (!PendingSignIn.ISSUER.equals(claims.issuer)) {
            throw failure(Reason.INVALID_ISSUER);
        }
        if (expectedIssuedClientId == null || !expectedIssuedClientId.startsWith("oaiapp_")
                || expectedIssuedClientId.length() <= "oaiapp_".length()
                || claims.audience == null || claims.audience.isEmpty()
                || !claims.audience.contains(expectedIssuedClientId)) {
            throw failure(Reason.INVALID_AUDIENCE);
        }
        for (String audience : claims.audience) {
            if (audience == null || audience.trim().isEmpty()) {
                throw failure(Reason.INVALID_AUDIENCE);
            }
        }
        if ((claims.audience.size() > 1 || claims.authorizedParty != null)
                && !expectedIssuedClientId.equals(claims.authorizedParty)) {
            throw failure(Reason.INVALID_AUTHORIZED_PARTY);
        }
        if (claims.subject == null || claims.subject.trim().isEmpty()) {
            throw failure(Reason.INVALID_SUBJECT);
        }
        if (refresh && (expectedSubject == null || expectedSubject.trim().isEmpty()
                || !expectedSubject.equals(claims.subject))) {
            throw failure(Reason.INVALID_SUBJECT);
        }
        if (expectedNonce == null || expectedNonce.isEmpty()
                || (!refresh && claims.nonce == null)
                || (claims.nonce != null && !MessageDigest.isEqual(
                        expectedNonce.getBytes(StandardCharsets.UTF_8),
                        claims.nonce.getBytes(StandardCharsets.UTF_8)))) {
            throw failure(Reason.INVALID_NONCE);
        }
        if (nowEpochSeconds < 0 || claims.expiresAtEpochSeconds == null
                || claims.issuedAtEpochSeconds == null || claims.issuedAtEpochSeconds < 0
                || claims.expiresAtEpochSeconds <= claims.issuedAtEpochSeconds) {
            throw failure(Reason.INVALID_TIME);
        }
        long expiration = claims.expiresAtEpochSeconds;
        long issuedAt = claims.issuedAtEpochSeconds;
        if (expiration < nowEpochSeconds && nowEpochSeconds - expiration > CLOCK_SKEW_SECONDS) {
            throw failure(Reason.EXPIRED);
        }
        if (issuedAt > nowEpochSeconds && issuedAt - nowEpochSeconds > CLOCK_SKEW_SECONDS) {
            throw failure(Reason.FUTURE_ISSUED_AT);
        }
    }

    private static InvalidClaimsException failure(Reason reason) {
        return new InvalidClaimsException(reason);
    }
}
