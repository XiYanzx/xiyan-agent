// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.account;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

/** Small bounded HTTPS client. Redirects never carry credentials to another origin. */
final class SiwcHttp {
    static final String ISSUER = "https://auth.openai.com";
    static final String RESOURCE = "https://api.openai.com/v1";
    static final String DISCOVERY = ISSUER + "/.well-known/openid-configuration";
    static final class Failure extends IOException {
        final int status;
        final String code;
        Failure(int status, String code) {
            super("HTTPS request did not succeed"); this.status = status; this.code = code;
        }
        boolean requiresSignIn() {
            return status == 401 || status == 403 || "invalid_grant".equals(code) || "invalid_client".equals(code);
        }
    }
    static final class Discovery {
        final String issuer, authorize, token, revoke, jwks;
        Discovery(JSONObject value) throws Exception {
            issuer = value.getString("issuer");
            if (!ISSUER.equals(issuer)) throw new IOException("Unexpected discovery issuer");
            authorize = authEndpoint(value.getString("authorization_endpoint"));
            token = authEndpoint(value.getString("token_endpoint"));
            revoke = authEndpoint(value.getString("revocation_endpoint"));
            jwks = authEndpoint(value.getString("jwks_uri"));
            JSONArray algorithms = value.getJSONArray("id_token_signing_alg_values_supported");
            boolean rs256 = false;
            for (int i = 0; i < algorithms.length(); i++) rs256 |= "RS256".equals(algorithms.getString(i));
            if (!rs256) throw new IOException("Unsupported ID token signing algorithm");
        }
    }
    private SiwcHttp() { }
    static Discovery discovery() throws Exception { return new Discovery(get(DISCOVERY, null)); }
    static String authEndpoint(String text) throws IOException {
        try {
            URI uri = new URI(text);
            if (!"https".equals(uri.getScheme()) || !"auth.openai.com".equals(uri.getHost())
                    || uri.getPort() != -1 || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getRawQuery() != null || uri.getRawPath().isEmpty()) throw new IOException("Untrusted authentication endpoint");
            return uri.toASCIIString();
        } catch (java.net.URISyntaxException bad) { throw new IOException("Invalid authentication endpoint"); }
    }
    static JSONObject get(String url, String bearer) throws Exception { return request(url, null, bearer, false); }
    static JSONObject form(String url, Map<String, String> fields) throws Exception {
        return request(authEndpoint(url), encode(fields), null, false);
    }
    static void revoke(String url, Map<String, String> fields) throws Exception {
        request(authEndpoint(url), encode(fields), null, true);
    }
    private static String encode(Map<String, String> fields) throws IOException {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (text.length() > 0) text.append('&');
            text.append(URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8.name())).append('=')
                    .append(URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8.name()));
        }
        return text.toString();
    }
    private static JSONObject request(String text, String form, String bearer, boolean allowEmpty) throws Exception {
        URL url = new URL(text);
        boolean auth = "auth.openai.com".equals(url.getHost());
        boolean api = "api.openai.com".equals(url.getHost()) && "/v1/models".equals(url.getPath()) && url.getQuery() == null;
        if (!"https".equals(url.getProtocol()) || url.getPort() != -1 || url.getUserInfo() != null
                || url.getRef() != null || (!auth && !api) || (bearer != null && !api)) throw new IOException("Untrusted HTTPS origin");
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept", "application/json");
            if (bearer != null) connection.setRequestProperty("Authorization", "Bearer " + bearer);
            if (form != null) {
                byte[] bytes = form.getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw readFailure(connection, deadline);
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (System.nanoTime() > deadline) throw new IOException("HTTPS response deadline exceeded");
                    if (body.size() + count > 2 * 1024 * 1024) throw new IOException("HTTPS response limit exceeded");
                    body.write(buffer, 0, count);
                }
                if (body.size() == 0 && allowEmpty) return new JSONObject();
                return new JSONObject(new String(body.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally { connection.disconnect(); }
    }
    static Failure readFailure(HttpsURLConnection connection, long deadline) throws IOException {
        // Retain fixed protocol codes only; raw service bodies never enter diagnostics.
        int status = connection.getResponseCode();
        String code = null;
        InputStream errors = connection.getErrorStream();
        if (errors != null) try (InputStream input = errors) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024]; int count;
            while ((count = input.read(buffer)) != -1 && body.size() + count <= 8192
                    && System.nanoTime() < deadline) body.write(buffer, 0, count);
            Object value = new JSONObject(new String(body.toByteArray(), StandardCharsets.UTF_8)).opt("error");
            if (value instanceof String && ("invalid_grant".equals(value) || "invalid_client".equals(value))) code = (String) value;
        } catch (Exception ignored) { /* The HTTP status remains sufficient for a safe failure. */ }
        return new Failure(status, code);
    }
}
