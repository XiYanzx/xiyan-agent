// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.account;

import io.xiyan.agent.core.response.SseReader;
import io.xiyan.agent.core.response.InferenceEvidence;
import io.xiyan.agent.core.response.ServiceFailure;
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
        final ServiceFailure diagnostic;
        final InferenceEvidence evidence;
        Failure(String reason) { this(reason, null, null); }
        Failure(String reason, ServiceFailure diagnostic, InferenceEvidence evidence) {
            super("Text inference did not complete"); this.reason = reason; this.diagnostic = diagnostic; this.evidence = evidence;
        }
    }
    static final class Result {
        final String text;
        final InferenceEvidence evidence;
        Result(String text, InferenceEvidence evidence) { this.text = text; this.evidence = evidence; }
    }
    private SiwcInference() { }

    static Result run(String accessToken, JSONObject body, Control control) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(SiwcHttp.RESOURCE + "/responses").openConnection();
        long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(90);
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture<?> timeout = watchdog.schedule(() -> { timedOut.set(true); connection.disconnect(); }, 90, TimeUnit.SECONDS);
        StringBuilder text = new StringBuilder();
        final InferenceEvidence[] evidence = {null};
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
            String requestId = connection.getHeaderField("x-request-id");
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
                            evidence[0] = evidence(response, "completed", started);
                            return true;
                        } else if ("response.failed".equals(type)) {
                            JSONObject response = value.getJSONObject("response");
                            JSONObject error = response.optJSONObject("error");
                            ServiceFailure failure = ServiceFailure.from(status, field(error, "code"), field(error, "param"), requestId);
                            throw new Failure(errorReason(failure), failure, evidence(response, "failed", started));
                        } else if ("response.incomplete".equals(type)) {
                            throw new Failure("incomplete", null, evidence(value.optJSONObject("response"), "incomplete", started));
                        } else if ("error".equals(type)) {
                            ServiceFailure failure = ServiceFailure.from(status, field(value, "code"), field(value, "param"), requestId);
                            throw new Failure(errorReason(failure), failure, null);
                        }
                        return false;
                    } catch (org.json.JSONException invalid) { throw new Failure("invalid_stream"); }
                });
                control.check();
                if (!complete) throw new Failure("interrupted_stream");
            }
            return new Result(text.toString(), evidence[0]);
        } catch (Exception failure) {
            if (timedOut.get()) throw new SocketTimeoutException("Text inference deadline exceeded");
            throw failure;
        } finally { timeout.cancel(true); watchdog.shutdownNow(); connection.disconnect(); }
    }

    private static String errorReason(ServiceFailure failure) {
        switch (failure.getKind()) {
            case AUTHORIZATION_REQUIRED: return "authorization_required";
            case POLICY_RESTRICTED: return "policy_restricted";
            case ROUTE_UNSUPPORTED: return "route_unsupported";
            case UNSUPPORTED_CAPABILITY: return "unsupported_capability";
            case TEMPORARY_UNAVAILABLE: return "temporary_unavailable";
            case USAGE_LIMIT: return "usage_limit";
            case MODEL_UNAVAILABLE: return "model_unavailable";
            case CLIENT_CONFIGURATION: return "client_configuration";
            default: return "failed";
        }
    }
    private static String field(JSONObject value, String name) {
        Object field = value == null ? null : value.opt(name);
        return field instanceof String ? (String) field : null;
    }
    private static InferenceEvidence evidence(JSONObject response, String terminal, long started) {
        return new InferenceEvidence(field(response, "id"), field(response, "model"), field(response, "service_tier"), terminal,
                System.currentTimeMillis(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }
}
