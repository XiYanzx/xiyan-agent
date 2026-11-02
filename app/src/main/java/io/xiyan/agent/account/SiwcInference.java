// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.account;

import io.xiyan.agent.core.response.SseReader;
import io.xiyan.agent.core.response.InferenceEvidence;
import io.xiyan.agent.core.response.ServiceFailure;
import io.xiyan.agent.core.response.ResponseContentTypePolicy;
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
import org.json.JSONArray;
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
        final String streamFormatJson;
        Failure(String reason) { this(reason, null, null); }
        Failure(String reason, ServiceFailure diagnostic, InferenceEvidence evidence) {
            this(reason, diagnostic, evidence, null);
        }
        Failure(String reason, ServiceFailure diagnostic, InferenceEvidence evidence, String streamFormatJson) {
            super("Text inference did not complete"); this.reason = reason; this.diagnostic = diagnostic; this.evidence = evidence;
            this.streamFormatJson = streamFormatJson;
        }
    }
    static final class Result {
        final String text;
        final InferenceEvidence evidence;
        final String streamFormatJson;
        Result(String text, InferenceEvidence evidence, String streamFormatJson) {
            this.text = text; this.evidence = evidence; this.streamFormatJson = streamFormatJson;
        }
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
        StreamFormatTrace trace = new StreamFormatTrace();
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
            trace.http(status, contentType);
            if (!ResponseContentTypePolicy.mayReadSse(contentType)) {
                trace.reject("content_type");
                throw new Failure("invalid_stream");
            }
            try (InputStream input = connection.getInputStream()) {
                boolean complete = SseReader.read(input, () -> {
                    control.check();
                    long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (remaining <= 0) throw new SocketTimeoutException("Text inference deadline exceeded");
                    connection.setReadTimeout((int) Math.max(1, Math.min(15000, remaining)));
                }, event -> {
                    JSONObject frame = trace.frame(event.event, event.data);
                    String parsing = "json_parse";
                    try {
                        JSONObject value = new JSONObject(event.data);
                        trace.parsed(frame, value);
                        parsing = "top_type_access";
                        String type = value.getString("type");
                        if (!event.event.isEmpty() && !"message".equals(event.event) && !event.event.equals(type)) {
                            trace.reject("event_type_mismatch");
                            throw new Failure("invalid_stream");
                        }
                        if ("response.output_text.delta".equals(type) || "response.refusal.delta".equals(type)) {
                            parsing = "output_delta_access";
                            Object delta = value.get("delta");
                            if (!(delta instanceof String) || text.length() + ((String) delta).length() > 32768) {
                                trace.reject(delta instanceof String ? "output_limit" : "output_delta_type");
                                throw new Failure("output_limit");
                            }
                            text.append((String) delta); control.partial(text.toString());
                        } else if ("response.completed".equals(type)) {
                            parsing = "completion_response_access";
                            JSONObject response = value.getJSONObject("response");
                            parsing = "completion_status_access";
                            if (!"completed".equals(response.getString("status"))) {
                                trace.reject("completion_status");
                                throw new Failure("invalid_stream");
                            }
                            if (!response.isNull("error")) {
                                trace.reject("completion_error");
                                throw new Failure("invalid_stream");
                            }
                            evidence[0] = evidence(response, "completed", started);
                            return true;
                        } else if ("response.failed".equals(type)) {
                            parsing = "failed_response_access";
                            JSONObject response = value.getJSONObject("response");
                            JSONObject error = response.optJSONObject("error");
                            ServiceFailure failure = ServiceFailure.from(status, field(error, "code"), field(error, "param"), requestId);
                            trace.reject("server_failed");
                            throw new Failure(errorReason(failure), failure, evidence(response, "failed", started));
                        } else if ("response.incomplete".equals(type)) {
                            trace.reject("server_incomplete");
                            throw new Failure("incomplete", null, evidence(value.optJSONObject("response"), "incomplete", started));
                        } else if ("error".equals(type)) {
                            trace.reject("server_error");
                            ServiceFailure failure = ServiceFailure.from(status, field(value, "code"), field(value, "param"), requestId);
                            throw new Failure(errorReason(failure), failure, null);
                        }
                        return false;
                    } catch (org.json.JSONException invalid) { trace.reject(parsing); throw new Failure("invalid_stream"); }
                });
                control.check();
                if (!complete) { trace.reject("eof_without_completion"); throw new Failure("interrupted_stream"); }
            }
            return new Result(text.toString(), evidence[0], trace.json());
        } catch (Exception failure) {
            if (failure instanceof SiwcHttp.Failure) throw failure;
            if (failure instanceof Failure) {
                Failure original = (Failure) failure;
                throw new Failure(original.reason, original.diagnostic, original.evidence, trace.json());
            }
            if (trace.enabled) {
                trace.reject(timedOut.get() || failure instanceof SocketTimeoutException ? "stream_timeout" : "reader_or_transport");
                throw new Failure(timedOut.get() || failure instanceof SocketTimeoutException ? "timeout" : "stream_io", null, null, trace.json());
            }
            if (timedOut.get()) throw new SocketTimeoutException("Text inference deadline exceeded");
            throw failure;
        } finally { timeout.cancel(true); watchdog.shutdownNow(); connection.disconnect(); }
    }

    /** Fixed-shape trace: event payloads and unknown names/values never enter this object. */
    private static final class StreamFormatTrace {
        private static final int FRAME_LIMIT = 16;
        private final JSONArray frames = new JSONArray();
        private int count, status;
        private boolean enabled;
        private String contentType = "missing", rejection;
        void http(int status, String rawType) {
            enabled = true; this.status = status;
            if (rawType == null) return;
            String type = rawType.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
            contentType = type.matches("text/event-stream|application/json|application/problem\\+json|text/plain|text/html|application/octet-stream") ? type : "other";
        }
        JSONObject frame(String event, String data) {
            count++;
            if (frames.length() >= FRAME_LIMIT) return null;
            JSONObject frame = new JSONObject();
            try {
                frame.put("event", eventName(event)).put("dataDone", "[DONE]".equals(data.trim())).put("jsonParsed", false);
                frames.put(frame);
            } catch (org.json.JSONException ignored) { /* No raw fallback. */ }
            return frame;
        }
        void parsed(JSONObject frame, JSONObject value) {
            if (frame == null) return;
            try {
                Object type = value.opt("type");
                JSONObject keys = new JSONObject();
                for (String key : new String[]{"type", "response", "choices", "object", "delta", "error"}) keys.put(key, value.has(key));
                frame.put("jsonParsed", true).put("typePresent", value.has("type")).put("typeKind", kind(type))
                        .put("type", type instanceof String ? typeName((String) type) : "other").put("knownKeys", keys);
                if (value.has("object")) {
                    Object object = value.opt("object");
                    frame.put("object", "response".equals(object) || "chat.completion.chunk".equals(object) ? object : "other");
                }
                JSONObject response = value.optJSONObject("response");
                if (response != null && response.has("status")) {
                    Object terminal = response.opt("status");
                    frame.put("responseStatus", terminal instanceof String && ((String) terminal).matches("completed|failed|in_progress|cancelled|queued|incomplete") ? terminal : "other");
                }
            } catch (org.json.JSONException ignored) { /* No raw fallback. */ }
        }
        void reject(String reason) { rejection = reason; }
        String json() {
            if (!enabled) return null;
            try {
                JSONObject value = new JSONObject().put("schemaVersion", 1).put("httpStatus", status)
                        .put("contentType", contentType).put("eventCount", count).put("eventsTruncated", count > FRAME_LIMIT)
                        .put("rejection", rejection == null ? JSONObject.NULL : rejection).put("frames", frames);
                String text = value.toString();
                return text.length() <= 32768 ? text : "{\"schemaVersion\":1,\"diagnosticTruncated\":true}";
            } catch (org.json.JSONException ignored) { return "{\"schemaVersion\":1,\"diagnosticError\":true}"; }
        }
        private static String eventName(String value) {
            return value != null && (knownResponseName(value) || value.matches("error|message|ping")) ? value : "other";
        }
        private static String typeName(String value) {
            return knownResponseName(value) || value.matches("error|ping") ? value : "other";
        }
        private static boolean knownResponseName(String value) {
            switch (value) {
                case "response.created":
                case "response.in_progress":
                case "response.queued":
                case "response.completed":
                case "response.failed":
                case "response.incomplete":
                case "response.cancelled":
                case "response.output_item.added":
                case "response.output_item.done":
                case "response.content_part.added":
                case "response.content_part.done":
                case "response.output_text.delta":
                case "response.output_text.done":
                case "response.output_text.annotation.added":
                case "response.refusal.delta":
                case "response.refusal.done":
                case "response.reasoning_summary_part.added":
                case "response.reasoning_summary_part.done":
                case "response.reasoning_summary_text.delta":
                case "response.reasoning_summary_text.done":
                case "response.reasoning_text.delta":
                case "response.reasoning_text.done":
                case "response.function_call_arguments.delta":
                case "response.function_call_arguments.done":
                case "response.custom_tool_call_input.delta":
                case "response.custom_tool_call_input.done": return true;
                default: return false;
            }
        }
        private static String kind(Object value) {
            if (value == null) return "missing";
            if (value == JSONObject.NULL) return "null";
            if (value instanceof String) return "string";
            if (value instanceof Number) return "number";
            if (value instanceof Boolean) return "boolean";
            if (value instanceof JSONObject) return "object";
            if (value instanceof JSONArray) return "array";
            return "other";
        }
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
