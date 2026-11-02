// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.core.auth;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One short-lived authorization-code transaction. Bind the loopback listener before
 * creating this object, and keep it private until token exchange finishes. This class performs
 * no networking, token exchange, persistence, or JWT verification.
 */
public final class PendingSignIn {
    public static final String ISSUER = "https://auth.openai.com";
    public static final String INITIAL_CLIENT_ID = "dynamic_agent_client";
    public static final String AGENT_NAME_HINT = "xiyan_agent";
    public static final String SCOPE =
            "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
    public static final String RESOURCE = "https://api.openai.com/v1";
    public static final long LIFETIME_SECONDS = 300;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_QUERY_LENGTH = 16_384;
    private static final int MAX_QUERY_FIELDS = 32;
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    public enum Reason {
        MALFORMED_CALLBACK,
        REDIRECT_MISMATCH,
        STATE_MISMATCH,
        EXPIRED,
        ALREADY_CONSUMED,
        INVALID_CLIENT_ID,
        AUTHORIZATION_DENIED,
        AUTHORIZATION_ERROR
    }

    /** Only fixed reason names are exposed; callback values never enter diagnostics. */
    public static final class AuthException extends IllegalArgumentException {
        private final Reason reason;

        private AuthException(Reason reason) {
            super("Authorization callback rejected: " + reason.name());
            this.reason = reason;
        }

        public Reason getReason() {
            return reason;
        }
    }

    /** Sensitive success values for a single token exchange; do not log their accessors. */
    public static final class Callback {
        private final String code;
        private final String issuedClientId;

        private Callback(String code, String issuedClientId) {
            this.code = code;
            this.issuedClientId = issuedClientId;
        }

        public String getCode() {
            return code;
        }

        public String getIssuedClientId() {
            return issuedClientId;
        }

        @Override
        public String toString() {
            return "Callback[redacted]";
        }
    }

    private final URI authorizationUri;
    private final URI authorizationEndpoint;
    private final URI redirectUri;
    private final String externalAgentHostId;
    private final String issuedClientId;
    private final String state;
    private final String nonce;
    private final String codeVerifier;
    private final long createdAtEpochSeconds;
    private final long expiresAtEpochSeconds;
    private boolean consumed;

    private PendingSignIn(URI authorizationEndpoint, int boundRedirectPort,
            String issuedClientId, String externalAgentHostId, long nowEpochSeconds) {
        this(authorizationEndpoint, boundRedirectPort, issuedClientId, externalAgentHostId,
                nowEpochSeconds, randomValue(), randomValue(), randomValue());
    }

    private PendingSignIn(URI authorizationEndpoint, int boundRedirectPort,
            String issuedClientId, String externalAgentHostId, long nowEpochSeconds,
            String state, String nonce, String codeVerifier) {
        requireTrustedEndpoint(authorizationEndpoint);
        if (authorizationEndpoint.getRawQuery() != null) {
            throw new IllegalArgumentException("Authorization endpoint must not contain a query");
        }
        if (boundRedirectPort < 1 || boundRedirectPort > 65_535) {
            throw new IllegalArgumentException("Invalid bound loopback port");
        }
        if (issuedClientId != null && !isIssuedClientId(issuedClientId)) {
            throw new IllegalArgumentException("Invalid issued client identifier");
        }
        requireExternalAgentHostId(externalAgentHostId);
        if (nowEpochSeconds < 0 || nowEpochSeconds > Long.MAX_VALUE - LIFETIME_SECONDS) {
            throw new IllegalArgumentException("Invalid authorization creation time");
        }
        this.redirectUri = URI.create("http://127.0.0.1:" + boundRedirectPort + "/auth/callback");
        this.authorizationEndpoint = authorizationEndpoint;
        this.externalAgentHostId = externalAgentHostId;
        this.issuedClientId = issuedClientId;
        this.createdAtEpochSeconds = nowEpochSeconds;
        this.expiresAtEpochSeconds = nowEpochSeconds + LIFETIME_SECONDS;
        this.state = requireSavedSecret(state);
        this.nonce = requireSavedSecret(nonce);
        this.codeVerifier = requireSavedSecret(codeVerifier);

        Map<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", issuedClientId == null ? INITIAL_CLIENT_ID : issuedClientId);
        query.put("redirect_uri", redirectUri.toASCIIString());
        query.put("scope", SCOPE);
        query.put("resource", RESOURCE);
        if (issuedClientId == null) {
            query.put("agent_name_hint", AGENT_NAME_HINT);
        }
        query.put("ext_agent_host_id", externalAgentHostId);
        query.put("state", state);
        query.put("nonce", nonce);
        query.put("code_challenge", codeChallenge(codeVerifier));
        query.put("code_challenge_method", "S256");
        StringBuilder encoded = new StringBuilder(authorizationEndpoint.toASCIIString()).append('?');
        for (Map.Entry<String, String> item : query.entrySet()) {
            if (encoded.charAt(encoded.length() - 1) != '?') {
                encoded.append('&');
            }
            encoded.append(encodeQueryComponent(item.getKey())).append('=')
                    .append(encodeQueryComponent(item.getValue()));
        }
        this.authorizationUri = URI.create(encoded.toString());
    }

    /** A null issuedClientId starts dynamic registration. Persist a returned oaiapp_ ID securely. */
    public static PendingSignIn create(URI authorizationEndpoint, int boundRedirectPort,
            String issuedClientId, String externalAgentHostId, long nowEpochSeconds) {
        return new PendingSignIn(authorizationEndpoint, boundRedirectPort, issuedClientId,
                externalAgentHostId, nowEpochSeconds);
    }

    /** Sensitive values: pass directly to this app's encrypted, non-backup pending store only. */
    public synchronized Map<String, Object> exportForEncryptedStorage() {
        if (consumed) throw failure(Reason.ALREADY_CONSUMED);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("authorizationEndpoint", authorizationEndpoint.toASCIIString());
        value.put("redirectUri", redirectUri.toASCIIString());
        value.put("issuedClientId", issuedClientId);
        value.put("externalAgentHostId", externalAgentHostId);
        value.put("state", state); value.put("nonce", nonce); value.put("codeVerifier", codeVerifier);
        value.put("createdAtEpochSeconds", createdAtEpochSeconds);
        value.put("expiresAtEpochSeconds", expiresAtEpochSeconds);
        return Collections.unmodifiableMap(value);
    }

    /** Restore the original transaction without generating a new state, verifier, URI or expiry. */
    public static PendingSignIn restoreFromEncryptedStorage(Map<String, ?> value, long nowEpochSeconds) {
        if (value == null || !value.keySet().equals(new HashSet<>(Arrays.asList("authorizationEndpoint", "redirectUri", "issuedClientId",
                "externalAgentHostId", "state", "nonce", "codeVerifier", "createdAtEpochSeconds", "expiresAtEpochSeconds")))) {
            throw new IllegalArgumentException("Invalid saved authorization fields");
        }
        Object createdValue = value.get("createdAtEpochSeconds"), expiresValue = value.get("expiresAtEpochSeconds");
        if (!(createdValue instanceof Long) || !(expiresValue instanceof Long)) {
            throw new IllegalArgumentException("Invalid saved authorization time");
        }
        long created = (Long) createdValue, expires = (Long) expiresValue;
        if (created < 0 || created > Long.MAX_VALUE - LIFETIME_SECONDS || expires != created + LIFETIME_SECONDS
                || nowEpochSeconds < created || nowEpochSeconds >= expires) throw failure(Reason.EXPIRED);
        try {
            URI redirect = new URI(savedString(value, "redirectUri", false));
            PendingSignIn pending = new PendingSignIn(new URI(savedString(value, "authorizationEndpoint", false)),
                    redirect.getPort(), savedString(value, "issuedClientId", true),
                    savedString(value, "externalAgentHostId", false), created,
                    savedString(value, "state", false), savedString(value, "nonce", false), savedString(value, "codeVerifier", false));
            if (!pending.redirectUri.toASCIIString().equals(redirect.toASCIIString())) throw failure(Reason.REDIRECT_MISMATCH);
            return pending;
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("Invalid saved authorization URI");
        }
    }

    private static String savedString(Map<String, ?> value, String key, boolean nullable) {
        Object item = value.get(key);
        if (nullable && item == null) return null;
        if (!(item instanceof String)) throw new IllegalArgumentException("Invalid saved authorization value");
        return (String) item;
    }

    private static String requireSavedSecret(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("Invalid saved authorization secret");
        }
        byte[] bytes;
        try { bytes = Base64.getUrlDecoder().decode(value); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid saved authorization secret"); }
        if (bytes.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value)) {
            throw new IllegalArgumentException("Invalid saved authorization secret");
        }
        return value;
    }

    /** Generate once per installation, then persist and reuse this non-account host identifier. */
    public static String newExternalAgentHostId() {
        return "urn:uuid:" + UUID.randomUUID();
    }

    /** Apply to discovery-provided authorization, token, and JWKS endpoints before networking. */
    public static void requireTrustedEndpoint(URI endpoint) {
        if (endpoint == null || !"https".equals(endpoint.getScheme())
                || !"auth.openai.com".equals(endpoint.getHost())
                || (endpoint.getPort() != -1 && endpoint.getPort() != 443)
                || endpoint.getRawUserInfo() != null || endpoint.getRawFragment() != null
                || endpoint.isOpaque() || endpoint.getRawPath() == null
                || endpoint.getRawPath().isEmpty()) {
            throw new IllegalArgumentException("Untrusted authorization service endpoint");
        }
    }

    /** Sensitive: contains the transaction state and nonce. Open in a browser; do not log it. */
    public URI authorizationUri() {
        return authorizationUri;
    }

    public URI getRedirectUri() {
        return redirectUri;
    }

    /** Sensitive: supply only to the token endpoint for this transaction. */
    public String getCodeVerifier() {
        return codeVerifier;
    }

    /** Sensitive: compare against the signature-verified ID token's nonce. */
    public String getNonce() {
        return nonce;
    }

    public long getExpiresAtEpochSeconds() {
        return expiresAtEpochSeconds;
    }

    /**
     * Reject malformed or unrelated traffic without consuming the transaction. A valid success
     * or OAuth error with the correct state consumes it atomically, including access_denied.
     */
    public synchronized Callback consumeCallback(URI callback, long nowEpochSeconds) {
        if (consumed) {
            throw failure(Reason.ALREADY_CONSUMED);
        }
        if (callback == null || !"http".equals(callback.getScheme())
                || !"127.0.0.1".equals(callback.getHost())
                || callback.getPort() != redirectUri.getPort()
                || !redirectUri.getRawAuthority().equals(callback.getRawAuthority())
                || !"/auth/callback".equals(callback.getRawPath())
                || callback.getRawUserInfo() != null || callback.getRawFragment() != null
                || callback.isOpaque()) {
            throw failure(Reason.REDIRECT_MISMATCH);
        }
        Map<String, String> parameters = decodeQuery(callback.getRawQuery());
        String returnedState = parameters.get("state");
        if (returnedState == null || !MessageDigest.isEqual(
                state.getBytes(StandardCharsets.UTF_8), returnedState.getBytes(StandardCharsets.UTF_8))) {
            throw failure(Reason.STATE_MISMATCH);
        }
        if (nowEpochSeconds < createdAtEpochSeconds || nowEpochSeconds >= expiresAtEpochSeconds) {
            throw failure(Reason.EXPIRED);
        }
        String error = parameters.get("error");
        String code = parameters.get("code");
        if (error != null) {
            if (error.trim().isEmpty() || containsControl(error) || code != null) {
                throw failure(Reason.MALFORMED_CALLBACK);
            }
            consumed = true;
            throw failure("access_denied".equals(error)
                    ? Reason.AUTHORIZATION_DENIED : Reason.AUTHORIZATION_ERROR);
        }
        if (code == null || code.trim().isEmpty() || containsControl(code)) {
            throw failure(Reason.MALFORMED_CALLBACK);
        }
        String returnedClientId = parameters.get("client_id");
        if (issuedClientId == null) {
            if (!isIssuedClientId(returnedClientId)) {
                throw failure(Reason.INVALID_CLIENT_ID);
            }
        } else if (returnedClientId != null && !issuedClientId.equals(returnedClientId)) {
            throw failure(Reason.INVALID_CLIENT_ID);
        }
        consumed = true;
        return new Callback(code, issuedClientId == null ? returnedClientId : issuedClientId);
    }

    /** Raw loopback request adapter; URI syntax errors are sanitized and leave pending state intact. */
    public Callback consumeCallback(String callback, long nowEpochSeconds) {
        if (callback == null || callback.length() > MAX_QUERY_LENGTH + 256) {
            throw failure(Reason.MALFORMED_CALLBACK);
        }
        try {
            return consumeCallback(new URI(callback), nowEpochSeconds);
        } catch (URISyntaxException ignored) {
            throw failure(Reason.MALFORMED_CALLBACK);
        }
    }

    @Override
    public String toString() {
        return "PendingSignIn[redacted]";
    }

    static String codeChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    static String encodeQueryComponent(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte item : value.getBytes(StandardCharsets.UTF_8)) {
            int c = item & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~') {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(HEX[c >>> 4]).append(HEX[c & 15]);
            }
        }
        return encoded.toString();
    }

    private static String randomValue() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean isIssuedClientId(String value) {
        return value != null && value.length() > "oaiapp_".length() && value.length() <= 256
                && value.startsWith("oaiapp_") && value.matches("[A-Za-z0-9._~-]+");
    }

    private static void requireExternalAgentHostId(String value) {
        if (value == null || !value.startsWith("urn:uuid:") || value.length() != 45) {
            throw new IllegalArgumentException("Invalid external agent host identifier");
        }
        try {
            String suffix = value.substring("urn:uuid:".length());
            UUID uuid = UUID.fromString(suffix);
            if (uuid.version() != 4 || uuid.variant() != 2 || !uuid.toString().equals(suffix)) {
                throw new IllegalArgumentException("Invalid external agent host identifier");
            }
        } catch (IllegalArgumentException ignored) {
            throw new IllegalArgumentException("Invalid external agent host identifier");
        }
    }

    private static Map<String, String> decodeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty() || rawQuery.length() > MAX_QUERY_LENGTH) {
            throw failure(Reason.MALFORMED_CALLBACK);
        }
        Map<String, String> values = new LinkedHashMap<>();
        String[] fields = rawQuery.split("&", -1);
        if (fields.length > MAX_QUERY_FIELDS) {
            throw failure(Reason.MALFORMED_CALLBACK);
        }
        for (String field : fields) {
            int separator = field.indexOf('=');
            if (separator < 1) {
                throw failure(Reason.MALFORMED_CALLBACK);
            }
            String key = decodeComponent(field.substring(0, separator));
            String value = decodeComponent(field.substring(separator + 1));
            if (key.isEmpty() || containsControl(key) || values.put(key, value) != null) {
                throw failure(Reason.MALFORMED_CALLBACK);
            }
        }
        return values;
    }

    private static String decodeComponent(String raw) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%') {
                if (i + 2 >= raw.length()) {
                    throw failure(Reason.MALFORMED_CALLBACK);
                }
                int high = Character.digit(raw.charAt(++i), 16);
                int low = Character.digit(raw.charAt(++i), 16);
                if (high < 0 || low < 0) {
                    throw failure(Reason.MALFORMED_CALLBACK);
                }
                bytes.write((high << 4) | low);
            } else if (c <= 0x7f) {
                bytes.write(c == '+' ? ' ' : c);
            } else {
                // URI.toASCIIString() also represents literal non-ASCII as UTF-8 percent octets.
                throw failure(Reason.MALFORMED_CALLBACK);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException ignored) {
            throw failure(Reason.MALFORMED_CALLBACK);
        }
    }

    private static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static AuthException failure(Reason reason) {
        return new AuthException(reason);
    }
}
