// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.account;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AtomicFile;
import io.xiyan.agent.BuildConfig;
import io.xiyan.agent.core.model.ModelCatalog;
import io.xiyan.agent.core.response.InferenceEvidence;
import io.xiyan.agent.core.response.ServiceFailure;
import io.xiyan.agent.core.usage.QuotaSnapshot;
import io.xiyan.agent.core.usage.ResponseTokenUsage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Debug-only local verifier. Reports contain protocol metadata and synthetic test output only. */
final class LiveAccountVerifier {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();
    private static final Set<String> CLAIMED = new HashSet<>();
    private static final long TOTAL_MS = 135000;
    private LiveAccountVerifier() { }

    static void start(Context context, Bundle input, Runnable finished) {
        Bundle arguments = input == null ? new Bundle() : new Bundle(input);
        final String requestId = arguments.getString("requestId");
        if (!canonicalRequestId(requestId)) { finished.run(); return; }
        File directory = new File(context.getNoBackupFilesDir(), "verification");
        File destination = new File(directory, requestId + ".json");
        synchronized (CLAIMED) {
            if (CLAIMED.contains(requestId) || destination.exists()
                    || new File(destination.getPath() + ".bak").exists() || new File(destination.getPath() + ".new").exists()) {
                finished.run(); return;
            }
            if (CLAIMED.size() >= 256) { finished.run(); return; }
            CLAIMED.add(requestId);
        }
        final JSONObject report = initialReport(arguments, requestId);
        final SiwcClient account = SiwcClient.get(context);
        String mode = arguments.getString("mode", "inspect");
        if ("inspect".equals(mode)) {
            try { safeState(report, account.snapshot()); publish(destination, report, "inspection_completed"); }
            catch (Exception failure) { safeError(report, failure); publishQuietly(destination, report, "verification_failed"); }
            finally { finished.run(); }
            return;
        }
        if (!"catalog".equals(mode) && !"verify".equals(mode) && !"usage".equals(mode)) {
            publishQuietly(destination, report, "invalid_arguments"); finished.run(); return;
        }
        if (account.snapshot().busy || !ACTIVE.compareAndSet(false, true)) {
            try { safeState(report, account.snapshot()); } catch (Exception ignored) { /* Fixed report remains safe. */ }
            publishQuietly(destination, report, "operation_in_progress"); finished.run(); return;
        }
        WORKER.execute(() -> {
            String status = "verification_failed";
            try { status = verify(account, arguments, report); }
            catch (Exception failure) { safeError(report, failure); }
            finally {
                try { safeState(report, account.snapshot()); } catch (Exception ignored) { /* No raw data fallback. */ }
                publishQuietly(destination, report, status);
                ACTIVE.set(false); finished.run();
            }
        });
    }

    private static JSONObject initialReport(Bundle arguments, String requestId) {
        JSONObject report = new JSONObject();
        try {
            String mode = arguments.getString("mode", "inspect");
            report.put("schemaVersion", 1).put("requestId", requestId)
                    .put("mode", mode.matches("inspect|catalog|verify|usage") ? mode : "invalid")
                    .put("sourceCommit", BuildConfig.SOURCE_COMMIT).put("sourceTreeClean", BuildConfig.SOURCE_TREE_CLEAN)
                    .put("processId", android.os.Process.myPid()).put("configuredServiceTier", "default")
                    .put("credentialsExported", false).put("startedAtEpochMillis", System.currentTimeMillis());
        } catch (org.json.JSONException impossible) { throw new IllegalStateException("Fixed report creation failed"); }
        return report;
    }

    private static String verify(SiwcClient account, Bundle arguments, JSONObject report) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + TOTAL_MS;
        SiwcClient.Snapshot snapshot = account.snapshot();
        safeState(report, snapshot);
        if (snapshot.busy) return "operation_in_progress";
        if (!snapshot.hasSession || snapshot.reauthRequired) return "authorization_required";
        if ("usage".equals(arguments.getString("mode"))) {
            if (!beginOnMain(account, account::refreshUsage)) return "operation_in_progress";
            snapshot = idle(account, Math.min(deadline, SystemClock.elapsedRealtime() + 75000));
            safeState(report, snapshot);
            return snapshot.failureCode != null ? "usage_failed"
                    : SiwcClient.USAGE_CHECK_COMPLETED.equals(snapshot.operationStage) ? "usage_checked" : "usage_not_completed";
        }
        if ("verify".equals(arguments.getString("mode"))) {
            String marker = arguments.getString("marker"), model = arguments.getString("modelId");
            if (marker == null || !marker.matches("[A-Z0-9_:-]{6,50}") || model == null || model.isEmpty() || model.length() > 256) return "invalid_arguments";
        }
        if (!beginOnMain(account, account::refreshModels)) return "operation_in_progress";
        snapshot = idle(account, Math.min(deadline, SystemClock.elapsedRealtime() + 75000));
        safeState(report, snapshot);
        ModelCatalog catalog = snapshot.catalog;
        if (catalog == null) return "catalog_failed";
        JSONArray models = new JSONArray();
        for (ModelCatalog.Model model : catalog.getModels()) {
            models.put(new JSONObject().put("id", model.getId()).put("requestModel", model.getRequestModel())
                    .put("displayName", model.getDisplayName()).put("availability", model.getServerDeclaredAvailability().name())
                    .put("reasoningEfforts", new JSONArray(model.getReasoningEfforts())));
        }
        report.put("catalogFetchedAtEpochMillis", catalog.getFetchedAtEpochMillis())
                .put("catalogSource", catalog.getSource().name()).put("modelCount", models.length()).put("models", models);
        if ("catalog".equals(arguments.getString("mode"))) return "catalog_verified";
        final String modelId = arguments.getString("modelId"), effort = arguments.getString("effort");
        String marker = arguments.getString("marker");
        if (marker == null || !marker.matches("[A-Z0-9_:-]{6,50}")) return "invalid_arguments";
        marker += "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        ModelCatalog.Selection selection = catalog.select(modelId, effort, null);
        final String expected = marker + ":43";
        final String prompt = "Calculate 17 + 26. Reply with exactly \"" + expected + "\". No other text.";
        report.put("selectedModelId", selection.getModel().getId()).put("requestedModel", selection.getRequestModel())
                .put("requestedEffort", nullable(effort)).put("requestedServiceTier", "default")
                .put("testPrompt", prompt).put("expectedText", expected);
        InferenceEvidence previousEvidence = snapshot.inferenceEvidence;
        long startedAtEpochMillis = System.currentTimeMillis();
        if (!beginOnMain(account, () -> account.sendText(modelId, effort, prompt))) return "operation_in_progress";
        snapshot = idle(account, Math.min(deadline, SystemClock.elapsedRealtime() + 110000));
        safeState(report, snapshot);
        InferenceEvidence evidence = snapshot.inferenceEvidence;
        boolean fresh = evidence != null && evidence != previousEvidence && evidence.getCompletedAtEpochMillis() >= startedAtEpochMillis;
        boolean matches = fresh && expected.equals(snapshot.inferenceText.trim());
        report.put("freshCompletionEvidence", fresh).put("replyMatches", matches).put("replyLength", snapshot.inferenceText.length());
        if (matches) report.put("testReply", snapshot.inferenceText);
        if (evidence != null) report.put("completionEvidence", completion(evidence));
        String status = "completed".equals(snapshot.inferenceStatus) && fresh
                && "completed".equals(evidence.getTerminalStatus()) && matches ? "inference_verified" : "inference_not_verified";
        if (evidence != null) {
            String tier = evidence.getReportedServiceTier();
            boolean accelerated = "fast".equals(tier) || "ultrafast".equals(tier) || "priority".equals(tier);
            report.put("reportedStandardTier", "default".equals(tier)).put("unexpectedAcceleratedTier", accelerated);
            if (accelerated) status = "unexpected_service_tier";
        }
        return status;
    }

    private static boolean beginOnMain(SiwcClient account, Runnable action) throws Exception {
        FutureTask<Boolean> start = new FutureTask<>(() -> {
            if (account.snapshot().busy) return false;
            action.run(); return true;
        });
        new Handler(Looper.getMainLooper()).post(start);
        try { return start.get(5, TimeUnit.SECONDS); }
        catch (Exception failure) { start.cancel(false); throw failure; }
    }

    private static SiwcClient.Snapshot idle(SiwcClient account, long deadline) throws IOException {
        while (account.snapshot().busy) {
            if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("Verification wait deadline exceeded");
            SystemClock.sleep(50);
        }
        return account.snapshot();
    }

    private static void safeState(JSONObject target, SiwcClient.Snapshot snapshot) throws Exception {
        target.put("hasSession", snapshot.hasSession).put("connected", snapshot.connected)
                .put("reauthRequired", snapshot.reauthRequired).put("busy", snapshot.busy)
                .put("operationStage", snapshot.operationStage).put("failureCode", nullable(snapshot.failureCode))
                .put("inferenceStatus", snapshot.inferenceStatus);
        long now = System.currentTimeMillis();
        target.put("quota", quota(snapshot.quota, now)).put("tokenUsage", snapshot.tokenUsage == null ? JSONObject.NULL : tokens(snapshot.tokenUsage));
        if (snapshot.inferenceEvidence != null) target.put("completionEvidence", completion(snapshot.inferenceEvidence));
        else target.remove("completionEvidence");
        if (snapshot.catalogFormatJson != null) target.put("catalogFormat", new JSONObject(snapshot.catalogFormatJson));
        else target.remove("catalogFormat");
        if (snapshot.streamFormatJson != null) target.put("streamFormat", new JSONObject(snapshot.streamFormatJson));
        else target.remove("streamFormat");
        ServiceFailure failure = snapshot.lastFailure;
        if (failure != null) target.put("serviceFailure", new JSONObject().put("httpStatus", failure.getHttpStatus())
                .put("kind", failure.getKind().name()).put("code", nullable(failure.getCode()))
                .put("param", nullable(failure.getParam())).put("requestId", nullable(failure.getRequestId())));
        else target.remove("serviceFailure");
    }

    private static JSONObject quota(QuotaSnapshot value, long now) throws Exception {
        QuotaSnapshot.Credits credits = value.getCredits();
        return new JSONObject().put("source", value.getSource().name()).put("hasData", value.hasData())
                .put("observedAtEpochMillis", value.getObservedAtEpochMillis()).put("stale", value.isStale(now))
                .put("primary", window(value.getPrimary(), now)).put("secondary", window(value.getSecondary(), now))
                .put("credits", credits == null ? JSONObject.NULL : new JSONObject().put("hasCredits", nullable(credits.getHasCredits()))
                        .put("unlimited", nullable(credits.getUnlimited())).put("balance", nullable(credits.getBalance()))
                        .put("source", credits.getSource().name()).put("observedAtEpochMillis", credits.getObservedAtEpochMillis()).put("stale", credits.isStale(now)));
    }

    private static Object window(QuotaSnapshot.Window window, long now) throws Exception {
        return window == null ? JSONObject.NULL : new JSONObject().put("usedPercent", window.getUsedPercent())
                .put("remainingPercent", window.getRemainingPercent()).put("windowMinutes", nullable(window.getWindowMinutes()))
                .put("resetsAtEpochSeconds", nullable(window.getResetsAtEpochSeconds()))
                .put("source", window.getSource().name()).put("observedAtEpochMillis", window.getObservedAtEpochMillis()).put("stale", window.isStale(now));
    }

    private static JSONObject tokens(ResponseTokenUsage value) throws Exception {
        return new JSONObject().put("inputTokens", nullable(value.getInputTokens())).put("outputTokens", nullable(value.getOutputTokens()))
                .put("totalTokens", nullable(value.getTotalTokens())).put("cachedTokens", nullable(value.getCachedTokens()))
                .put("cacheWriteTokens", nullable(value.getCacheWriteTokens())).put("reasoningTokens", nullable(value.getReasoningTokens()))
                .put("observedAtEpochMillis", value.getObservedAtEpochMillis());
    }

    private static JSONObject completion(InferenceEvidence evidence) throws Exception {
        return new JSONObject().put("responseId", nullable(evidence.getResponseId()))
                .put("reportedModel", nullable(evidence.getReportedModel())).put("reportedServiceTier", nullable(evidence.getReportedServiceTier()))
                .put("terminalStatus", nullable(evidence.getTerminalStatus())).put("completedAtEpochMillis", evidence.getCompletedAtEpochMillis())
                .put("elapsedMillis", evidence.getElapsedMillis());
    }

    private static void safeError(JSONObject report, Exception error) {
        String name = error.getClass().getSimpleName();
        try { report.put("errorClass", name.matches("[A-Za-z_$][A-Za-z0-9_$]{0,95}") ? name : "UnclassifiedError"); }
        catch (org.json.JSONException ignored) { /* No raw exception fallback. */ }
    }

    private static void publish(File destination, JSONObject report, String status) throws Exception {
        File directory = destination.getParentFile();
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) throw new IOException("Verification directory unavailable");
        report.put("status", status).put("message", fixedMessage(status)).put("finishedAtEpochMillis", System.currentTimeMillis());
        byte[] bytes = report.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 2 * 1024 * 1024) throw new IOException("Verification report limit exceeded");
        AtomicFile output = new AtomicFile(destination);
        FileOutputStream stream = null;
        try { stream = output.startWrite(); stream.write(bytes); output.finishWrite(stream); }
        catch (Exception failure) { if (stream != null) output.failWrite(stream); throw failure; }
    }

    private static void publishQuietly(File destination, JSONObject report, String status) {
        try { publish(destination, report, status); } catch (Exception ignored) { /* Never redirect private data to logs or another path. */ }
    }

    private static String fixedMessage(String status) {
        switch (status) {
            case "inspection_completed": return "已记录当前进程的安全状态。";
            case "operation_in_progress": return "应用正在处理其他操作，本次验证未启动。";
            case "authorization_required": return "当前进程尚无可用于验证的已保存会话。";
            case "catalog_verified": return "已收到当前账号的模型目录。";
            case "usage_checked": return "已检查服务返回的用量字段；缺失值保持未知。";
            case "usage_not_completed": return "本次用量检查尚未完成，已有观测时间保留。";
            case "inference_verified": return "本次合成文本验证已完成。";
            case "unexpected_service_tier": return "服务返回了加速级别，请检查处理级别证据。";
            case "invalid_arguments": return "本次验证参数未通过校验。";
            default: return "本次验证尚未确认成功。";
        }
    }

    private static boolean canonicalRequestId(String value) {
        if (value == null || value.length() != 36) return false;
        try { UUID id = UUID.fromString(value); return id.version() == 4 && id.variant() == 2 && id.toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }
    private static Object nullable(Object value) { return value == null ? JSONObject.NULL : value; }
}
