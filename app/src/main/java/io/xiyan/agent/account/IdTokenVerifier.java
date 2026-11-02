// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.account;

import io.xiyan.agent.core.auth.Rs256Signature;
import io.xiyan.agent.core.auth.TokenClaimsPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** ID tokens are never trusted before RS256 verification against discovery-provided JWKS. */
final class IdTokenVerifier {
    static final class Verified {
        final String subject, email;
        Verified(String subject, String email) { this.subject = subject; this.email = email; }
    }
    private IdTokenVerifier() { }
    static Verified verify(String compact, String accessToken, SiwcHttp.Discovery discovery,
            String issuedClientId, String nonce, String expectedSubject, boolean refresh) throws Exception {
        if (compact == null || compact.length() > 65536) throw new IOException("Invalid ID token size");
        String[] parts = compact.split("\\.", -1);
        if (parts.length != 3) throw new IOException("Invalid ID token framing");
        JSONObject header = jsonPart(parts[0]);
        if (!"RS256".equals(string(header, "alg", true)) || header.has("crit") || header.has("b64")) {
            throw new IOException("Unsupported ID token algorithm");
        }
        String type = string(header, "typ", false);
        if (type != null && !"JWT".equals(type)) throw new IOException("Unexpected ID token type");
        String kid = string(header, "kid", true);
        if (kid.length() > 256) throw new IOException("Invalid signing key identifier");
        JSONObject key = null;
        for (int attempt = 0; attempt < 2 && key == null; attempt++) {
            JSONArray keys = SiwcHttp.get(discovery.jwks, null).getJSONArray("keys");
            if (keys.length() > 64) throw new IOException("JWKS key limit exceeded");
            for (int i = 0; i < keys.length(); i++) {
                JSONObject candidate = keys.getJSONObject(i);
                if (!kid.equals(candidate.optString("kid"))) continue;
                if (key != null) throw new IOException("Ambiguous signing key");
                if (!"RSA".equals(candidate.optString("kty"))
                        || (candidate.has("alg") && !"RS256".equals(candidate.optString("alg")))
                        || (candidate.has("use") && !"sig".equals(candidate.optString("use")))) {
                    throw new IOException("Unexpected signing key type");
                }
                key = candidate;
            }
        }
        if (key == null || !Rs256Signature.verify(parts[0] + "." + parts[1], parts[2],
                string(key, "n", true), string(key, "e", true))) throw new IOException("ID token signature rejected");
        JSONObject value = jsonPart(parts[1]);
        List<String> audience = new ArrayList<>();
        Object aud = value.opt("aud");
        if (aud instanceof String) audience.add((String) aud);
        else if (aud instanceof JSONArray) {
            JSONArray array = (JSONArray) aud;
            if (array.length() > 16) throw new IOException("Invalid ID token audience");
            for (int i = 0; i < array.length(); i++) {
                Object entry = array.get(i);
                if (!(entry instanceof String)) throw new IOException("Invalid ID token audience");
                audience.add((String) entry);
            }
        } else throw new IOException("Missing ID token audience");
        String subject = string(value, "sub", true);
        TokenClaimsPolicy.Claims claims = new TokenClaimsPolicy.Claims(string(value, "iss", true), audience,
                subject, string(value, "nonce", false), integer(value, "exp"), integer(value, "iat"), string(value, "azp", false));
        long now = System.currentTimeMillis() / 1000;
        if (refresh) TokenClaimsPolicy.validateRefresh(claims, issuedClientId, expectedSubject, nonce, now);
        else {
            TokenClaimsPolicy.validate(claims, issuedClientId, nonce, now);
            if (expectedSubject != null && !expectedSubject.equals(subject)) throw new IOException("Account identity changed");
        }
        String tokenHash = string(value, "at_hash", false);
        if (tokenHash != null) {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(accessToken.getBytes(StandardCharsets.US_ASCII));
            String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(hash, hash.length / 2));
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), tokenHash.getBytes(StandardCharsets.US_ASCII))) {
                throw new IOException("Access token binding rejected");
            }
        }
        String email = Boolean.TRUE.equals(value.opt("email_verified")) ? string(value, "email", false) : null;
        return new Verified(subject, email == null ? "已连接账号" : email);
    }
    private static JSONObject jsonPart(String part) throws Exception {
        if (part.isEmpty() || !part.matches("[A-Za-z0-9_-]+")) throw new IOException("Invalid ID token encoding");
        byte[] bytes = Base64.getUrlDecoder().decode(part);
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(part)) throw new IOException("Noncanonical ID token encoding");
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }
    private static String string(JSONObject object, String key, boolean required) throws IOException {
        Object value = object.opt(key);
        if ((value == null || value == JSONObject.NULL) && !required) return null;
        if (!(value instanceof String) || ((String) value).isEmpty()) throw new IOException("Invalid ID token string claim");
        return (String) value;
    }
    private static Long integer(JSONObject value, String key) throws IOException {
        Object number = value.opt(key);
        if (!(number instanceof Integer) && !(number instanceof Long)) throw new IOException("Invalid ID token time claim");
        return ((Number) number).longValue();
    }
}
