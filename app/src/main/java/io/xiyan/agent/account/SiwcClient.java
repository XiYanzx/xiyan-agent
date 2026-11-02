// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.account;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import io.xiyan.agent.core.auth.LoopbackCallbackRequest;
import io.xiyan.agent.core.auth.PendingSignIn;
import io.xiyan.agent.core.auth.TokenClaimsPolicy;
import io.xiyan.agent.core.model.ModelCatalog;
import io.xiyan.agent.core.response.InferenceEvidence;
import io.xiyan.agent.core.response.ServiceFailure;
import io.xiyan.agent.core.response.SubscriptionTextRequest;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Official public-client SIWC. No desktop credentials, API keys, or token-bearing diagnostics. */
public final class SiwcClient {
    public interface Observer { void changed(); }
    public interface Browser { void open(URI authorizationUri); }
    public static final class Snapshot {
        public final boolean connected, hasSession, reauthRequired, busy;
        public final long expiresAtEpochSeconds;
        public final String message, accountLabel, inferenceText, inferenceStatus, operationStage, failureCode, catalogFormatJson;
        public final InferenceEvidence inferenceEvidence;
        public final ServiceFailure lastFailure;
        public final ModelCatalog catalog;
        Snapshot(boolean hasSession, boolean reauthRequired, long expiresAt, boolean busy,
                String message, String accountLabel, ModelCatalog catalog, String inferenceText, String inferenceStatus,
                String operationStage, InferenceEvidence inferenceEvidence, ServiceFailure lastFailure, String failureCode,
                String catalogFormatJson) {
            this.hasSession = hasSession; this.reauthRequired = reauthRequired;
            this.expiresAtEpochSeconds = expiresAt;
            this.connected = hasSession && !reauthRequired && expiresAt > now();
            this.busy = busy; this.message = message;
            this.accountLabel = accountLabel; this.catalog = catalog;
            this.inferenceText = inferenceText; this.inferenceStatus = inferenceStatus;
            this.operationStage = operationStage; this.inferenceEvidence = inferenceEvidence; this.lastFailure = lastFailure;
            this.failureCode = failureCode;
            this.catalogFormatJson = catalogFormatJson;
        }
    }
    private interface Work { void run(long generation) throws Exception; }
    private static SiwcClient instance;
    public static synchronized SiwcClient get(Context context) {
        if (instance == null) instance = new SiwcClient(context.getApplicationContext());
        return instance;
    }
    private final Object lock = new Object();
    private final SessionStore store;
    private final SharedPreferences installation;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();
    private JSONObject session;
    private ModelCatalog catalog;
    private String message = "正在检查本机会话……";
    private boolean busy;
    private boolean reauthRequired;
    private String inferenceText = "", inferenceStatus = "idle";
    private String operationStage = "尚未开始";
    private InferenceEvidence inferenceEvidence;
    private ServiceFailure lastFailure;
    private String failureCode;
    private String catalogFormatJson;
    private volatile Runnable inferenceCancellation;
    private long generation;
    private volatile ServerSocket callbackSocket;

    private SiwcClient(Context context) {
        store = new SessionStore(context);
        installation = context.getSharedPreferences("siwc-installation", Context.MODE_PRIVATE);
        operate("正在读取本机会话……", expected -> {
            JSONObject saved = store.read();
            if (saved != null) {
                requireSession(saved);
                synchronized (lock) {
                    check(expected); session = saved;
                    saveRegistration(saved.getString("clientId"), saved.getString("subject"));
                }
                progress(expected, "已读取手机上的加密会话；调用前会检查有效期。");
            } else progress(expected, "尚未登录。请通过手机系统浏览器连接 ChatGPT 订阅。");
        });
    }
    public void addObserver(Observer observer) { observers.addIfAbsent(observer); }
    public void removeObserver(Observer observer) { observers.remove(observer); }
    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(session != null, reauthRequired, session == null ? 0 : session.optLong("expiresAt"), busy, message,
                    session == null ? "未连接" : session.optString("email", "已连接账号"), catalog, inferenceText, inferenceStatus,
                    operationStage, inferenceEvidence, lastFailure, failureCode, catalogFormatJson);
        }
    }
    public void checkEnvironment() {
        operate("正在核对官方登录端点……", expected -> {
            SiwcHttp.discovery(); check(expected);
            boolean hasSavedSession;
            synchronized (lock) { hasSavedSession = session != null; }
            progress(expected, hasSavedSession ? "官方发现文档可达；手机已保存会话，账号目录与推理状态分别验证。"
                    : "官方发现文档可达；当前手机尚无已保存会话，可继续官方登录。");
        });
    }
    /** Call only from an explicit user sign-in action; the browser owns login and consent. */
    public void signIn(Browser browser) {
        operate("正在准备手机本机登录回调……", expected -> {
            SiwcHttp.Discovery discovery = SiwcHttp.discovery(); check(expected);
            String host = installation.getString("hostId", null);
            if (host == null) {
                host = PendingSignIn.newExternalAgentHostId();
                if (!installation.edit().putString("hostId", host).commit()) throw new IOException("Installation identity unavailable");
            }
            String issued = installation.getString("clientId", null);
            String previousSubject;
            synchronized (lock) { previousSubject = session == null
                    ? issued == null ? null : installation.getString("clientSubject", null) : session.getString("subject"); }
            if (issued != null && previousSubject == null) throw new IOException("Registration identity unavailable");
            try (ServerSocket server = new ServerSocket()) {
                server.setReuseAddress(false);
                server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4);
                server.setSoTimeout(1500);
                callbackSocket = server;
                PendingSignIn pending = PendingSignIn.create(new URI(discovery.authorize), server.getLocalPort(), issued, host, now());
                progress(expected, "请在手机浏览器完成登录与订阅授权，完成后返回此应用。等待最多 5 分钟。");
                main.post(() -> {
                    try { check(expected); browser.open(pending.authorizationUri()); }
                    catch (Exception unavailable) { cancel(); }
                });
                PendingSignIn.Callback callback = awaitCallback(server, pending, expected);
                check(expected);
                progress(expected, "已收到本机回调，正在交换官方令牌……");
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("grant_type", "authorization_code"); fields.put("client_id", callback.getIssuedClientId());
                fields.put("code", callback.getCode()); fields.put("code_verifier", pending.getCodeVerifier());
                fields.put("redirect_uri", pending.getRedirectUri().toASCIIString()); fields.put("resource", SiwcHttp.RESOURCE);
                JSONObject token = SiwcHttp.form(discovery.token, fields);
                progress(expected, "令牌交换响应已接收，正在验证 ID 签名……");
                String access = tokenString(token, "access_token", true);
                IdTokenVerifier.Verified identity = IdTokenVerifier.verify(tokenString(token, "id_token", true), access,
                        discovery, callback.getIssuedClientId(), pending.getNonce(), previousSubject, false);
                progress(expected, "ID 签名已通过，正在验证订阅授权范围……");
                JSONObject saved = tokenSession(token, callback.getIssuedClientId(), identity, pending.getNonce(), null);
                progress(expected, "授权范围已通过，正在加密保存手机本机会话……");
                synchronized (lock) {
                    check(expected); store.write(saved); session = saved; catalog = null; reauthRequired = false;
                    inferenceText = ""; inferenceStatus = "idle"; inferenceEvidence = null;
                    saveRegistration(callback.getIssuedClientId(), identity.subject);
                }
                progress(expected, "官方登录与 ID 签名校验完成，会话已加密保存在手机。可以刷新账号模型目录。");
            } finally { callbackSocket = null; }
        });
    }
    public void refreshModels() {
        operate("正在读取账号模型目录……", expected -> {
            synchronized (lock) { catalog = null; catalogFormatJson = null; }
            JSONObject current = currentSession();
            if (current.getLong("expiresAt") <= now() + 60) current = refresh(current, expected);
            check(expected);
            JSONObject response;
            try { response = SiwcHttp.get(SiwcHttp.RESOURCE + "/models", current.getString("accessToken")); }
            catch (SiwcHttp.Failure failure) { invalidateSession(current, failure); throw failure; }
            long fetched = System.currentTimeMillis();
            ModelCatalog result;
            try { result = parseModels(response, fetched, current.getString("clientId") + ":" + current.getString("subject")); }
            catch (IllegalArgumentException invalidMetadata) {
                synchronized (lock) { check(expected); catalogFormatJson = catalogFormat(response).toString(); }
                throw new CatalogFormatFailure(catalogFailureCode(invalidMetadata));
            }
            synchronized (lock) {
                check(expected);
                session = current; catalog = result;
            }
            progress(expected, "已获取账号实时模型目录。推理结果待实际回合验证；思考选项采用返回的元数据。");
        });
    }
    /** A user-submitted text request only; this entry point exposes no phone or runtime tools. */
    public void sendText(String modelId, String reasoningEffort, String prompt) {
        operate("正在准备文本推理……", expected -> {
            ModelCatalog.Selection selection;
            JSONObject current;
            synchronized (lock) {
                check(expected); inferenceText = ""; inferenceStatus = "running"; inferenceEvidence = null;
                if (prompt == null || prompt.trim().isEmpty() || prompt.length() > 4000) {
                    throw new SiwcInference.Failure("invalid_prompt");
                }
                if (catalog == null) throw new SiwcInference.Failure("catalog_required");
                selection = catalog.select(modelId, reasoningEffort, null);
                current = currentSession();
                if (!(current.getString("clientId") + ":" + current.getString("subject")).equals(catalog.getAccountScope())) {
                    throw new SiwcInference.Failure("catalog_required");
                }
            }
            if (current.getLong("expiresAt") <= now() + 60) current = refresh(current, expected);
            check(expected);
            JSONObject body = new JSONObject(SubscriptionTextRequest.create(selection.getRequestModel(), selection.getReasoningEffort(), prompt));
            progress(expected, "正在接收模型文本。收到完整结束事件后确认结果；可随时停止。");
            try {
                SiwcInference.Result completed = SiwcInference.run(current.getString("accessToken"), body, new SiwcInference.Control() {
                    @Override public void check() throws IOException { SiwcClient.this.check(expected); }
                    @Override public void opened(Runnable cancel) throws IOException {
                        synchronized (lock) {
                            if (generation != expected) { cancel.run(); throw new IOException("Operation cancelled"); }
                            inferenceCancellation = cancel;
                        }
                    }
                    @Override public void partial(String text) throws IOException {
                        synchronized (lock) { check(); inferenceText = text; }
                        notifyChanged();
                    }
                });
                synchronized (lock) {
                    check(expected); inferenceText = completed.text; inferenceEvidence = completed.evidence; inferenceStatus = "completed";
                }
                progress(expected, "本次文本推理已完成，已收到官方 response.completed 事件。");
            } catch (SiwcHttp.Failure failure) {
                invalidateSession(current, failure); throw failure;
            } catch (SiwcInference.Failure failure) {
                if ("authorization_required".equals(failure.reason)) {
                    invalidateSession(current, new SiwcHttp.Failure(401, null));
                }
                throw failure;
            } finally { inferenceCancellation = null; }
        });
    }
    public void signOut() {
        operate("正在移除手机上的会话……", expected -> {
            JSONObject old;
            synchronized (lock) {
                check(expected); old = session; store.clear(); session = null; catalog = null; reauthRequired = false;
                inferenceText = ""; inferenceStatus = "idle"; inferenceEvidence = null;
            }
            progress(expected, "本机会话已移除，正在请求官方撤销刷新令牌……");
            if (old != null && !old.optString("refreshToken").isEmpty()) {
                try {
                    SiwcHttp.Discovery discovery = SiwcHttp.discovery();
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("token", old.getString("refreshToken")); fields.put("token_type_hint", "refresh_token");
                    fields.put("client_id", old.getString("clientId"));
                    SiwcHttp.revoke(discovery.revoke, fields);
                } catch (Exception unavailable) {
                    progress(expected, "本机会话已移除；远端撤销尚未确认，可在 ChatGPT 账号设置管理授权。");
                    return;
                }
            }
            progress(expected, "已退出，手机会话已移除。");
        });
    }
    public void cancel() {
        synchronized (lock) {
            generation++; message = "已取消当前操作；手机上的账号会话保留。";
            if ("running".equals(inferenceStatus)) inferenceStatus = "cancelled";
        }
        Runnable cancellation = inferenceCancellation;
        if (cancellation != null) cancellation.run();
        ServerSocket server = callbackSocket;
        if (server != null) try { server.close(); } catch (IOException ignored) { }
        notifyChanged();
    }
    private void operate(String status, Work work) {
        final long expected;
        synchronized (lock) {
            if (busy) return; busy = true; expected = ++generation; message = status; operationStage = status; lastFailure = null;
            failureCode = null; catalogFormatJson = null;
        }
        notifyChanged();
        worker.execute(() -> {
            try { work.run(expected); }
            catch (Exception error) {
                synchronized (lock) {
                    if (generation == expected) {
                        failureCode = safeFailureCode(error);
                        if (error instanceof SiwcHttp.Failure) lastFailure = ((SiwcHttp.Failure) error).diagnostic;
                        if (error instanceof SiwcInference.Failure) {
                            SiwcInference.Failure failure = (SiwcInference.Failure) error;
                            lastFailure = failure.diagnostic;
                            if (failure.evidence != null) inferenceEvidence = failure.evidence;
                        }
                        message = operationStage + "\n" + safeFailure(error);
                        if ("running".equals(inferenceStatus)) inferenceStatus = "failed";
                    }
                }
            } finally {
                synchronized (lock) { busy = false; }
                notifyChanged();
            }
        });
    }
    private JSONObject currentSession() throws Exception {
        synchronized (lock) {
            if (session == null) throw new IOException("No verified session");
            if (reauthRequired) throw new IOException("Session needs reauthorization");
            return new JSONObject(session.toString());
        }
    }
    private JSONObject refresh(JSONObject old, long expected) throws Exception {
        if (old.optString("refreshToken").isEmpty()) throw new IOException("Offline session renewal unavailable");
        SiwcHttp.Discovery discovery = SiwcHttp.discovery(); check(expected);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("grant_type", "refresh_token"); fields.put("client_id", old.getString("clientId"));
        fields.put("refresh_token", old.getString("refreshToken")); fields.put("resource", SiwcHttp.RESOURCE);
        JSONObject token;
        try { token = SiwcHttp.form(discovery.token, fields); }
        catch (SiwcHttp.Failure failure) { invalidateSession(old, failure); throw failure; }
        String access = tokenString(token, "access_token", true);
        IdTokenVerifier.Verified identity;
        if (token.has("id_token")) identity = IdTokenVerifier.verify(tokenString(token, "id_token", true), access, discovery,
                old.getString("clientId"), old.getString("nonce"), old.getString("subject"), true);
        else identity = new IdTokenVerifier.Verified(old.getString("subject"), old.getString("email"));
        JSONObject saved = tokenSession(token, old.getString("clientId"), identity, old.getString("nonce"), old);
        synchronized (lock) {
            if (session == null || !old.getString("clientId").equals(session.getString("clientId"))
                    || !old.getString("subject").equals(session.getString("subject"))
                    || !old.getString("refreshToken").equals(session.optString("refreshToken"))) {
                throw new IOException("Session changed during renewal");
            }
            // A cancelled UI operation may still have caused a server-side rotation. Save the
            // verified replacement first; cancellation only prevents the next model request.
            store.write(saved); session = saved; catalog = null; reauthRequired = false;
            check(expected);
        }
        return saved;
    }
    private static JSONObject tokenSession(JSONObject token, String clientId, IdTokenVerifier.Verified identity,
            String nonce, JSONObject previous) throws Exception {
        if (!"Bearer".equalsIgnoreCase(tokenString(token, "token_type", true))) throw new SessionValidationException("oauth_token_type_unsupported");
        String scope = tokenString(token, "scope", previous == null);
        if (scope == null) scope = previous.getString("scope");
        boolean direct = false;
        for (String item : scope.split(" +")) direct |= "chatgpt.tokens.use.direct".equals(item);
        if (!direct) throw new SessionValidationException("subscription_scope_missing");
        Object expiry = token.opt("expires_in");
        if (!(expiry instanceof Integer) && !(expiry instanceof Long)) throw new SessionValidationException("oauth_token_expiry_missing");
        long lifetime = ((Number) expiry).longValue();
        if (lifetime <= 0 || lifetime > 31536000) throw new SessionValidationException("oauth_token_expiry_invalid");
        String refresh = tokenString(token, "refresh_token", false);
        String idToken = tokenString(token, "id_token", false);
        return new JSONObject().put("version", 1).put("clientId", clientId).put("subject", identity.subject)
                .put("email", identity.email).put("nonce", nonce).put("accessToken", tokenString(token, "access_token", true))
                .put("refreshToken", refresh == null ? previous == null ? "" : previous.optString("refreshToken") : refresh)
                .put("idToken", idToken == null ? previous == null ? "" : previous.optString("idToken") : idToken)
                .put("scope", scope).put("expiresAt", now() + lifetime);
    }
    private static String tokenString(JSONObject value, String key, boolean required) throws IOException {
        Object text = value.opt(key);
        if ((text == null || text == JSONObject.NULL) && !required) return null;
        if (!(text instanceof String) || ((String) text).isEmpty() || ((String) text).length() > 65536
                || ((String) text).indexOf('\r') >= 0 || ((String) text).indexOf('\n') >= 0) throw new SessionValidationException("oauth_invalid_" + key);
        return (String) text;
    }
    private static void requireSession(JSONObject value) throws Exception {
        if (value.getInt("version") != 1 || !value.getString("clientId").startsWith("oaiapp_")
                || value.getString("subject").isEmpty() || value.getString("accessToken").isEmpty()
                || value.getString("nonce").isEmpty() || value.getLong("expiresAt") < 0) throw new IOException("Invalid saved session");
    }
    private void saveRegistration(String clientId, String subject) throws IOException {
        if (!installation.edit().putString("clientId", clientId).putString("clientSubject", subject).commit()) {
            throw new IOException("Registration identity save failed");
        }
    }
    private void invalidateSession(JSONObject expected, SiwcHttp.Failure failure) {
        if (!failure.requiresSignIn()) return;
        synchronized (lock) {
            if (session != null && expected.optString("clientId").equals(session.optString("clientId"))
                    && expected.optString("subject").equals(session.optString("subject"))) {
                reauthRequired = true; catalog = null;
            }
        }
    }
    private PendingSignIn.Callback awaitCallback(ServerSocket server, PendingSignIn pending, long expected) throws Exception {
        final long callbackEnd = SystemClock.elapsedRealtime() + PendingSignIn.LIFETIME_SECONDS * 1000;
        while (now() < pending.getExpiresAtEpochSeconds() && SystemClock.elapsedRealtime() < callbackEnd) {
            check(expected);
            server.setSoTimeout((int) Math.max(1, Math.min(1500, callbackEnd - SystemClock.elapsedRealtime())));
            try (Socket socket = server.accept()) {
                if (!socket.getInetAddress().isLoopbackAddress()) continue;
                final long requestEnd = Math.min(callbackEnd, SystemClock.elapsedRealtime() + 5000);
                String target;
                try {
                    target = LoopbackCallbackRequest.readTarget(socket.getInputStream(), server.getLocalPort(), () -> {
                        check(expected);
                        long remaining = requestEnd - SystemClock.elapsedRealtime();
                        if (remaining <= 0) throw new SocketTimeoutException("Callback request deadline exceeded");
                        socket.setSoTimeout((int) Math.min(2000, remaining));
                    });
                } catch (SocketTimeoutException timeout) {
                    continue;
                } catch (IOException invalidRequest) {
                    check(expected);
                    reply(socket, 400, "无效的本机回调请求。"); continue;
                }
                try {
                    PendingSignIn.Callback callback = pending.consumeCallback("http://127.0.0.1:" + server.getLocalPort() + target, now());
                    reply(socket, 200, "已收到授权回调。请返回 Xiyan Agent 查看验证结果。");
                    return callback;
                } catch (PendingSignIn.AuthException invalid) {
                    reply(socket, 400, "回调未通过校验，请返回 Xiyan Agent。");
                    if (invalid.getReason() == PendingSignIn.Reason.AUTHORIZATION_DENIED
                            || invalid.getReason() == PendingSignIn.Reason.AUTHORIZATION_ERROR) throw invalid;
                }
            } catch (SocketTimeoutException idle) { /* Bounded polling permits cancellation. */ }
        }
        throw new IOException("Authorization timed out");
    }
    private static void reply(Socket socket, int status, String text) throws IOException {
        byte[] body = ("<!doctype html><meta charset=utf-8><title>Xiyan Agent</title><p>" + text + "</p>")
                .getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Bad Request")
                + "\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer"
                + "\r\nContent-Security-Policy: default-src 'none'; frame-ancestors 'none'\r\nConnection: close\r\nContent-Length: " + body.length + "\r\n\r\n";
        socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(body); socket.getOutputStream().flush();
    }
    private static ModelCatalog parseModels(JSONObject value, long fetched, String subject) throws Exception {
        JSONArray models = value.getJSONArray("models");
        if (models.length() > 1000) throw new IOException("Account model limit exceeded");
        List<Map<String, ?>> rows = new ArrayList<>();
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.getJSONObject(i);
            if (!"list".equals(model.optString("visibility"))) continue;
            String slug = model.getString("slug");
            Map<String, Object> row = jsonObject(model); row.put("id", slug); row.put("model", slug); rows.add(row);
        }
        return ModelCatalog.fromMetadata(ModelCatalog.Source.ACCOUNT_MODELS, SiwcHttp.RESOURCE + "/models", fetched, subject, rows);
    }
    /** Fixed keys and structural types only; values, descriptions and account data stay in memory. */
    private static JSONObject catalogFormat(JSONObject value) throws Exception {
        JSONArray models = value.getJSONArray("models");
        JSONArray shapes = new JSONArray();
        String[] fields = {"slug", "display_name", "available", "visibility", "supportedReasoningEfforts",
                "supported_reasoning_efforts", "supportedReasoningLevels", "supported_reasoning_levels",
                "defaultReasoningEffort", "default_reasoning_effort", "defaultReasoningLevel", "default_reasoning_level",
                "supportedServiceTiers", "supported_service_tiers", "serviceTiers", "service_tiers"};
        String[] optionKeys = {"reasoningEffort", "reasoning_effort", "effort", "value", "serviceTier", "service_tier"};
        int listed = 0;
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.getJSONObject(i);
            if (!"list".equals(model.optString("visibility"))) continue;
            listed++;
            if (shapes.length() >= 32) continue;
            JSONObject shape = new JSONObject().put("rowIndex", i);
            for (String field : fields) {
                Object item = model.opt(field);
                JSONObject kind = new JSONObject().put("type", model.has(field) ? jsonType(item) : "absent");
                if (item instanceof JSONArray) {
                    JSONArray array = (JSONArray) item;
                    JSONArray members = new JSONArray();
                    for (int j = 0; j < Math.min(array.length(), 8); j++) {
                        Object member = array.get(j);
                        JSONObject memberShape = new JSONObject().put("type", jsonType(member));
                        if (member instanceof JSONObject) {
                            JSONObject object = (JSONObject) member;
                            for (String key : optionKeys) if (object.has(key)) memberShape.put(key, jsonType(object.opt(key)));
                        }
                        members.put(memberShape);
                    }
                    kind.put("length", array.length()).put("memberTypes", members);
                }
                shape.put(field, kind);
            }
            shapes.put(shape);
        }
        return new JSONObject().put("totalModels", models.length()).put("listedModels", listed)
                .put("truncated", listed > shapes.length()).put("shapes", shapes);
    }
    private static String jsonType(Object value) {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) return "object";
        if (value instanceof JSONArray) return "array";
        if (value instanceof String) return "string";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Number) return "number";
        return "other";
    }
    private static String catalogFailureCode(IllegalArgumentException error) {
        String message = error.getMessage();
        if (message == null) return "model_catalog_invalid";
        if (message.startsWith("默认推理程度不在")) return "model_catalog_default_not_declared";
        if (message.startsWith("模型元数据缺少字段")) return "model_catalog_field_missing";
        if (message.startsWith("模型元数据字段必须是字符串")) return "model_catalog_field_type";
        if (message.startsWith("支持参数必须是数组")) return "model_catalog_options_type";
        if (message.startsWith("支持参数成员必须")) return "model_catalog_option_type";
        if (message.startsWith("available 必须")) return "model_catalog_availability_type";
        if (message.startsWith("模型 ID 重复")) return "model_catalog_duplicate_id";
        return "model_catalog_invalid";
    }
    private static Map<String, Object> jsonObject(JSONObject value) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Iterator<String> keys = value.keys(); keys.hasNext();) { String key = keys.next(); result.put(key, jsonValue(value.get(key))); }
        return result;
    }
    private static Object jsonValue(Object value) throws Exception {
        if (value == JSONObject.NULL) return null;
        if (value instanceof JSONObject) return jsonObject((JSONObject) value);
        if (value instanceof JSONArray) {
            List<Object> result = new ArrayList<>(); JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) result.add(jsonValue(array.get(i))); return result;
        }
        return value;
    }
    private void check(long expected) throws IOException {
        synchronized (lock) { if (generation != expected) throw new IOException("Operation cancelled"); }
    }
    private void progress(long expected, String text) throws IOException {
        synchronized (lock) { check(expected); operationStage = text; message = text; } notifyChanged();
    }
    private void notifyChanged() { main.post(() -> { for (Observer observer : observers) observer.changed(); }); }
    private static long now() { return System.currentTimeMillis() / 1000; }
    private static final class SessionValidationException extends IOException {
        final String code;
        SessionValidationException(String code) { super("OAuth response validation failed"); this.code = code; }
    }
    private static final class CatalogFormatFailure extends IOException {
        final String code;
        CatalogFormatFailure(String code) { super("Account model metadata validation failed"); this.code = code; }
    }
    private static String safeFailureCode(Exception error) {
        if (error instanceof CatalogFormatFailure) return ((CatalogFormatFailure) error).code;
        if (error instanceof SessionValidationException) return ((SessionValidationException) error).code;
        if (error instanceof TokenClaimsPolicy.InvalidClaimsException) {
            return "id_claim_" + ((TokenClaimsPolicy.InvalidClaimsException) error).getReason().name();
        }
        if (error instanceof PendingSignIn.AuthException) return "callback_" + ((PendingSignIn.AuthException) error).getReason().name();
        if (error instanceof java.net.UnknownHostException) return "network_unknown_host";
        if (error instanceof java.net.SocketTimeoutException) return "network_timeout";
        if (error instanceof javax.net.ssl.SSLException) return "network_tls";
        if (error instanceof SiwcHttp.Failure) return "http_" + ((SiwcHttp.Failure) error).status;
        String type = error.getClass().getSimpleName();
        return type.matches("[A-Za-z0-9_]{1,80}") ? type : "operation_failed";
    }
    private static String safeFailure(Exception error) {
        if (error instanceof CatalogFormatFailure) return "模型目录格式尚未兼容（" + ((CatalogFormatFailure) error).code + "），会话已保留。";
        if (error instanceof SessionValidationException) {
            String code = ((SessionValidationException) error).code;
            return "subscription_scope_missing".equals(code)
                    ? "订阅使用权限尚未授予，请在官方授权中启用 ChatGPT 订阅使用。"
                    : "官方令牌响应字段未通过校验（" + code + "）。";
        }
        if (error instanceof TokenClaimsPolicy.InvalidClaimsException) {
            return "ID 令牌校验未通过（" + ((TokenClaimsPolicy.InvalidClaimsException) error).getReason().name() + "）。请重新连接。";
        }
        if (error instanceof ModelCatalog.SelectionException) return "所选模型或思考程度已变化，请刷新目录后重新选择。";
        if (error instanceof SiwcInference.Failure) {
            switch (((SiwcInference.Failure) error).reason) {
                case "invalid_prompt": return "请输入 1 至 4000 字符的任务文本。";
                case "catalog_required": return "请先登录并刷新当前账号的模型目录。";
                case "usage_limit": return "本次推理受到额度或频率限制，请查看官方 Usage 页面后重试。";
                case "model_unavailable": return "当前模型未能完成请求，请刷新账号目录后重新选择。";
                case "authorization_required": return "本次推理授权已失效，请重新登录。";
                case "policy_restricted": return "官方策略或地区限制了本次请求，会话已保留。请检查返回的错误类别。";
                case "route_unsupported": return "官方服务未支持本次请求的路由，会话已保留。";
                case "unsupported_capability": return "本次请求包含服务未支持的能力，请检查返回的错误字段后调整。";
                case "temporary_unavailable": return "官方服务暂时无法处理本次请求，会话已保留，可稍后重试。";
                case "client_configuration": return "官方客户端配置未通过校验，请检查接入配置。";
                case "incomplete": return "模型返回了未完成状态。已接收内容作为预览保留，可调整输入后重试。";
                case "interrupted_stream": return "连接已结束，完整结果尚未确认。已接收内容作为预览保留。";
                case "invalid_stream": return "收到的推理事件未通过格式校验，完整结果尚未确认。";
                case "output_limit": return "本次文本输出达到本地上限，完整结果尚未确认。";
                default: return "本次文本推理失败。已接收内容作为预览保留，可检查连接后重试。";
            }
        }
        if (error instanceof java.net.SocketTimeoutException) return "请求等待超时，完整结果尚未确认，可稍后重试。";
        if (error instanceof SiwcHttp.Failure) {
            SiwcHttp.Failure failure = (SiwcHttp.Failure) error;
            int status = failure.status;
            switch (failure.diagnostic.getKind()) {
                case AUTHORIZATION_REQUIRED: return "授权需要重新连接，请使用原账号登录。";
                case CLIENT_CONFIGURATION: return "官方客户端配置未通过校验，请检查接入配置。";
                case POLICY_RESTRICTED: return "官方策略或地区限制了本次请求，会话已保留。请检查返回的错误类别。";
                case ROUTE_UNSUPPORTED: return "官方服务未支持本次请求的路由，会话已保留。";
                case UNSUPPORTED_CAPABILITY: return "本次请求包含服务未支持的能力，请检查返回的错误字段后调整。";
                case TEMPORARY_UNAVAILABLE: return "官方服务暂时无法处理本次请求，会话已保留，可稍后重试。";
                case USAGE_LIMIT: return "当前额度或请求频率受限，请稍后重试并查看官方 Usage 页面。";
                case MODEL_UNAVAILABLE: return "当前模型未能处理请求，请刷新账号目录后重新选择。";
                default: break;
            }
            return "官方服务请求未成功（HTTP " + status + "），未记录令牌或响应正文。";
        }
        if (error instanceof PendingSignIn.AuthException) return "登录回调未完成：" + ((PendingSignIn.AuthException) error).getReason().name();
        return "操作未完成（" + error.getClass().getSimpleName() + "）。可检查网络后重试；未记录凭据。";
    }
}
