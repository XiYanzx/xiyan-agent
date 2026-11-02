// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.core.response;

/** Immutable, bounded response metadata for diagnosing a completed or failed inference attempt. */
public final class InferenceEvidence {
    private final String responseId, reportedModel, reportedServiceTier, terminalStatus;
    private final long completedAtEpochMillis, elapsedMillis;
    public InferenceEvidence(String responseId, String model, String serviceTier, String terminalStatus,
            long completedAtEpochMillis, long elapsedMillis) {
        this.responseId = responseId != null && responseId.length() <= 160
                && responseId.matches("resp_[A-Za-z0-9]{8,155}") ? responseId : null;
        reportedModel = model != null && model.length() <= 128 && model.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")
                && !model.startsWith("sk-") && !model.startsWith("rk-") && !model.startsWith("eyJ") ? model : null;
        reportedServiceTier = serviceTier != null && serviceTier.matches("default|auto|flex|scale|priority|fast|ultrafast") ? serviceTier : null;
        this.terminalStatus = terminalStatus != null && terminalStatus.matches("completed|failed|incomplete|cancelled") ? terminalStatus : null;
        this.completedAtEpochMillis = Math.max(0, completedAtEpochMillis);
        this.elapsedMillis = Math.max(0, elapsedMillis);
    }
    public String getResponseId() { return responseId; }
    public String getReportedModel() { return reportedModel; }
    public String getReportedServiceTier() { return reportedServiceTier; }
    public String getTerminalStatus() { return terminalStatus; }
    public long getCompletedAtEpochMillis() { return completedAtEpochMillis; }
    public long getElapsedMillis() { return elapsedMillis; }
}
