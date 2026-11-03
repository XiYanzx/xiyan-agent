// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.core.response;

/** Safe protocol metadata only. Raw response bodies, messages and credential values stay out. */
public final class ServiceFailure {
    public enum Kind {
        AUTHORIZATION_REQUIRED, CLIENT_CONFIGURATION, POLICY_RESTRICTED, ROUTE_UNSUPPORTED,
        UNSUPPORTED_CAPABILITY, USAGE_LIMIT, TEMPORARY_UNAVAILABLE, MODEL_UNAVAILABLE, UNKNOWN
    }
    private final int httpStatus;
    private final String code, param, requestId;
    private final Kind kind;

    private ServiceFailure(int status, String code, String param, String requestId) {
        httpStatus = status >= 100 && status <= 599 ? status : 0;
        this.code = safeCode(code); this.param = safeParam(param); this.requestId = safeRequestId(requestId);
        kind = classify(httpStatus, this.code);
    }
    public static ServiceFailure from(int status, String code, String param, String requestId) {
        return new ServiceFailure(status, code, param, requestId);
    }
    public int getHttpStatus() { return httpStatus; }
    public String getCode() { return code; }
    public String getParam() { return param; }
    public String getRequestId() { return requestId; }
    public Kind getKind() { return kind; }
    public boolean requiresSignIn() { return kind == Kind.AUTHORIZATION_REQUIRED; }

    private static Kind classify(int status, String code) {
        if (status == 401 || "invalid_grant".equals(code)) return Kind.AUTHORIZATION_REQUIRED;
        if ("invalid_client".equals(code)) return Kind.CLIENT_CONFIGURATION;
        if ("subscription_sharing_route_not_supported".equals(code)) return Kind.ROUTE_UNSUPPORTED;
        if ("subscription_sharing_unsupported_capability".equals(code)) return Kind.UNSUPPORTED_CAPABILITY;
        if ("subscription_sharing_usage_limit_exceeded".equals(code) || "rate_limit_exceeded".equals(code) || status == 429) return Kind.USAGE_LIMIT;
        if ("subscription_sharing_usage_unavailable".equals(code)
                || "subscription_sharing_user_unavailable".equals(code) || status == 503) return Kind.TEMPORARY_UNAVAILABLE;
        if ("model_not_found".equals(code) || "model_not_available".equals(code)) return Kind.MODEL_UNAVAILABLE;
        if (status == 403 || "subscription_sharing_user_not_eligible".equals(code)
                || "chatpass_v2_scope_not_authorized".equals(code)
                || "chatpass_v2_invalid_authorization_context".equals(code)) return Kind.POLICY_RESTRICTED;
        return Kind.UNKNOWN;
    }
    private static String safeCode(String value) {
        return value != null && value.length() <= 96 && value.matches("[a-z][a-z0-9_]*") ? value : null;
    }
    private static String safeParam(String value) {
        return value != null && value.length() <= 128
                && value.matches("[A-Za-z_][A-Za-z0-9_]*(?:(?:\\.[A-Za-z_][A-Za-z0-9_]*)|(?:\\[[0-9]{1,4}\\]))*") ? value : null;
    }
    private static String safeRequestId(String value) {
        if (value == null || value.length() > 128) return null;
        return value.matches("req_[A-Za-z0-9]{8,124}")
                || value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") ? value : null;
    }
}
