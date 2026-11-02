// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.account;

import io.xiyan.agent.core.response.SseReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** One bounded, user-initiated text-only Responses request using the phone's OAuth session. */
final class SiwcInference {
    interface Control {
        void check() throws IOException;
        void opened(Runnable cancel) throws IOException;
        void partial(String text) throws IOException;
    }
    static final class Failure extends IOException {
        final String reason;
        Failure(String reason) { super("Text inference did not complete"); this.reason = reason; }
    }
    private SiwcInference() { }

    static String run(String accessToken, JSONObject body, Control control) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(SiwcHttp.RESOURCE + "/responses").openConnection();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture<?> timeout = watchdog.schedule(() -> { timedOut.set(true); connection.disconnect(); }, 90, TimeUnit.SECONDS);
        StringBuilder text = new StringBuilder();
        try {
            control.opened(connection::disconnect);
            control.check();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setDoOutput(true);
            byte[] request = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(request.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(request); }
            control.check();
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw SiwcHttp.readFailure(connection, deadline);
            String contentType = connection.getContentType();
            if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("text/event-stream")) {
                throw new Failure("invalid_stream");
            }
            try (InputStream input = connection.getInputStream()) {
                boolean complete = SseReader.read(input, () -> {
                    control.check();
                    long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (remaining <= 0) throw new SocketTimeoutException("Text inference deadline exceeded");
                    connection.setReadTimeout((int) Math.max(1, Math.min(15000, remaining)));
                }, event -> {
                    try {
                        JSONObject value = new JSONObject(event.data);
                        String type = value.getString("type");
                        if (!event.event.isEmpty() && !"message".equals(event.event) && !event.event.equals(type)) {
                            throw new Failure("invalid_stream");
                        }
                        if ("response.output_text.delta".equals(type) || "response.refusal.delta".equals(type)) {
                            Object delta = value.get("delta");
                            if (!(delta instanceof String) || text.length() + ((String) delta).length() > 32768) {
                                throw new Failure("output_limit");
                            }
                            text.append((String) delta); control.partial(text.toString());
                        } else if ("response.completed".equals(type)) {
                            JSONObject response = value.getJSONObject("response");
                            if (!"completed".equals(response.getString("status")) || !response.isNull("error")) {
                                throw new Failure("invalid_stream");
                            }
                            return true;
                        } else if ("response.failed".equals(type)) {
                            JSONObject response = value.getJSONObject("response");
                            JSONObject error = response.optJSONObject("error");
                            throw new Failure(errorReason(error == null ? null : error.optString("code")));
                        } else if ("response.incomplete".equals(type)) {
                            throw new Failure("incomplete");
                        } else if ("error".equals(type)) {
                            throw new Failure(errorReason(value.optString("code")));
                        }
                        return false;
                    } catch (org.json.JSONException invalid) { throw new Failure("invalid_stream"); }
                });
                control.check();
                if (!complete) throw new Failure("interrupted_stream");
            }
            return text.toString();
        } catch (Exception failure) {
            if (timedOut.get()) throw new SocketTimeoutException("Text inference deadline exceeded");
            throw failure;
        } finally { timeout.cancel(true); watchdog.shutdownNow(); connection.disconnect(); }
    }

    private static String errorReason(String code) {
        if ("subscription_sharing_usage_limit_exceeded".equals(code)
                || "subscription_sharing_usage_unavailable".equals(code)
                || "rate_limit_exceeded".equals(code)) return "usage_limit";
        if ("model_not_found".equals(code) || "model_not_available".equals(code)) return "model_unavailable";
        if ("invalid_api_key".equals(code) || "invalid_token".equals(code)) return "authorization_required";
        return "failed";
    }
}
