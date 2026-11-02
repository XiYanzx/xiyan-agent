// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.core.response;

import static org.junit.Assert.*;
import org.junit.Test;

public class InferenceEvidenceTest {
    @Test public void recordsReportedTierWithoutRewritingItToTheRequestedTier() {
        InferenceEvidence value = new InferenceEvidence("resp_0123456789abcdef", "model-reported-revision", "priority", "completed", 1000, 50);
        assertEquals("priority", value.getReportedServiceTier()); assertEquals("model-reported-revision", value.getReportedModel());
        assertEquals("completed", value.getTerminalStatus()); assertEquals(1000, value.getCompletedAtEpochMillis()); assertEquals(50, value.getElapsedMillis());
    }
    @Test public void rejectsNonMetadataFieldsAndInvalidTerminals() {
        InferenceEvidence value = new InferenceEvidence("Bearer secret", "https://example.test", "invented-tier", "not-done", -1, -1);
        assertNull(value.getResponseId()); assertNull(value.getReportedModel()); assertNull(value.getReportedServiceTier()); assertNull(value.getTerminalStatus());
        assertEquals(0, value.getCompletedAtEpochMillis()); assertEquals(0, value.getElapsedMillis());
        assertNull(new InferenceEvidence(null, "sk-proj-synthetic", null, null, 0, 0).getReportedModel());
        assertNull(new InferenceEvidence(null, "eyJsynthetic.token.value", null, null, 0, 0).getReportedModel());
    }
}
