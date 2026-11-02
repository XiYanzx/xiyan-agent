// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.account;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import io.xiyan.agent.BuildConfig;
import io.xiyan.agent.core.model.ModelCatalog;
import io.xiyan.agent.core.response.InferenceEvidence;
import io.xiyan.agent.core.response.ServiceFailure;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Debug test APK only. Uses the app's own verified session; exports no credentials or identity. */
final class LiveAccountVerification {
    private final Instrumentation instrumentation;
    private final Bundle arguments;
    LiveAccountVerification(Instrumentation instrumentation, Bundle arguments) {
        this.instrumentation = instrumentation;
        this.arguments = new Bundle(arguments);
    }
    void run() {
        JSONObject report = new JSONObject();
        String status = "verification_failed";
        SiwcClient account = null;
        try {
            String mode = arguments.getString("mode", "inspect");
            if (!mode.matches("inspect|catalog|verify")) throw new IOException("Unknown verification mode");
            report.put("mode", mode).put("sourceCommit", BuildConfig.SOURCE_COMMIT)
                    .put("sourceTreeClean", BuildConfig.SOURCE_TREE_CLEAN)
                    .put("configuredServiceTier", "default").put("credentialsExported", false);
            account = SiwcClient.get(instrumentation.getTargetContext());
            SiwcClient.Snapshot snapshot = idle(account, 10000);
            safeState(report, snapshot);
            if ("inspect".equals(mode)) status = "inspection_completed";
            else if (!snapshot.hasSession || snapshot.reauthRequired) status = "authorization_required";
            else {
                account.refreshModels();
                snapshot = idle(account, 75000);
                safeState(report, snapshot);
                ModelCatalog catalog = snapshot.catalog;
                if (catalog == null) status = "catalog_failed";
                else {
                    JSONArray models = new JSONArray();
                    for (ModelCatalog.Model model : catalog.getModels()) {
                        models.put(new JSONObject().put("id", model.getId()).put("requestModel", model.getRequestModel())
                                .put("displayName", model.getDisplayName()).put("availability", model.getServerDeclaredAvailability().name())
                                .put("reasoningEfforts", new JSONArray(model.getReasoningEfforts())));
                    }
                    report.put("catalogFetchedAtEpochMillis", catalog.getFetchedAtEpochMillis())
                            .put("catalogSource", catalog.getSource().name()).put("modelCount", models.length())
                            .put("models", models);
                    status = "catalog_verified";
                    if ("verify".equals(mode)) {
                        status = "inference_not_verified";
                        String modelId = arguments.getString("modelId");
                        String effort = arguments.getString("effort");
                        String marker = arguments.getString("marker");
                        if (marker == null || !marker.matches("[A-Z0-9_:-]{6,50}")) throw new IOException("Verification marker required");
                        marker += "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
                        ModelCatalog.Selection selection = catalog.select(modelId, effort, null);
                        String expected = marker + ":43";
                        String prompt = "Calculate 17 + 26. Reply with exactly \"" + expected + "\". No other text.";
                        report.put("selectedModelId", selection.getModel().getId())
                                .put("requestedModel", selection.getRequestModel())
                                .put("requestedEffort", nullable(effort)).put("requestedServiceTier", "default")
                                .put("testPrompt", prompt).put("expectedText", expected);
                        InferenceEvidence previousEvidence = snapshot.inferenceEvidence;
                        long startedAtEpochMillis = System.currentTimeMillis();
                        account.sendText(modelId, effort, prompt);
                        snapshot = idle(account, 110000);
                        safeState(report, snapshot);
                        InferenceEvidence evidence = snapshot.inferenceEvidence;
                        boolean fresh = evidence != null && evidence != previousEvidence
                                && evidence.getCompletedAtEpochMillis() >= startedAtEpochMillis;
                        boolean matches = fresh && expected.equals(snapshot.inferenceText.trim());
                        report.put("freshCompletionEvidence", fresh).put("replyMatches", matches)
                                .put("replyLength", snapshot.inferenceText.length());
                        if (matches) report.put("testReply", snapshot.inferenceText);
                        if (evidence != null) report.put("completionEvidence", new JSONObject()
                                .put("responseId", nullable(evidence.getResponseId()))
                                .put("reportedModel", nullable(evidence.getReportedModel()))
                                .put("reportedServiceTier", nullable(evidence.getReportedServiceTier()))
                                .put("terminalStatus", nullable(evidence.getTerminalStatus()))
                                .put("completedAtEpochMillis", evidence.getCompletedAtEpochMillis())
                                .put("elapsedMillis", evidence.getElapsedMillis()));
                        status = "completed".equals(snapshot.inferenceStatus) && fresh
                                && "completed".equals(evidence.getTerminalStatus())
                                && matches ? "inference_verified" : "inference_not_verified";
                        if (evidence != null) {
                            String tier = evidence.getReportedServiceTier();
                            boolean accelerated = "fast".equals(tier) || "ultrafast".equals(tier) || "priority".equals(tier);
                            report.put("reportedStandardTier", "default".equals(tier))
                                    .put("unexpectedAcceleratedTier", accelerated);
                            if (accelerated) status = "unexpected_service_tier";
                        }
                    }
                }
            }
        } catch (Exception failure) {
            status = "verification_failed";
            try { report.put("failureClass", failure.getClass().getSimpleName()); }
            catch (org.json.JSONException ignored) { /* Fixed diagnostic fields only. */ }
            if (account != null) account.cancel();
        }
        Bundle result = new Bundle();
        result.putString("status", status);
        result.putString("evidence", report.toString());
        instrumentation.finish("inspection_completed".equals(status) || "catalog_verified".equals(status)
                || "inference_verified".equals(status)
                ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
    private static SiwcClient.Snapshot idle(SiwcClient client, long timeout) throws IOException {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (client.snapshot().busy) {
            if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("Verification wait deadline exceeded");
            SystemClock.sleep(50);
        }
        return client.snapshot();
    }
    private static void safeState(JSONObject target, SiwcClient.Snapshot snapshot) throws Exception {
        target.put("hasSession", snapshot.hasSession).put("connected", snapshot.connected)
                .put("reauthRequired", snapshot.reauthRequired).put("busy", snapshot.busy)
                .put("operationStage", snapshot.operationStage).put("inferenceStatus", snapshot.inferenceStatus);
        ServiceFailure failure = snapshot.lastFailure;
        if (failure != null) target.put("serviceFailure", new JSONObject().put("httpStatus", failure.getHttpStatus())
                .put("kind", failure.getKind().name()).put("code", nullable(failure.getCode()))
                .put("param", nullable(failure.getParam())).put("requestId", nullable(failure.getRequestId())));
    }
    private static Object nullable(Object value) { return value == null ? JSONObject.NULL : value; }
}
