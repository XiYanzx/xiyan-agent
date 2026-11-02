package io.xiyan.agent.core.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class ModelCatalogTest {
    @Test
    public void preservesNewServerValuesAndSourceWithoutHardcodedOptions() {
        Map<String, Object> metadata = model("future-model");
        metadata.put("model", "future-request-slug");
        metadata.put("displayName", "服务端新模型");
        metadata.put("supportedReasoningEfforts", Arrays.asList(
                field("reasoningEffort", "server-new-effort"), field("reasoningEffort", "other-effort")));
        metadata.put("supportedServiceTiers", Collections.singletonList("server-new-tier"));
        metadata.put("available", true);
        ModelCatalog catalog = catalog(metadata);

        ModelCatalog.Selection selection = catalog.select("future-model", "server-new-effort", "server-new-tier");

        assertEquals("future-request-slug", selection.getRequestModel());
        assertEquals("server-new-effort", selection.getReasoningEffort());
        assertEquals("server-new-tier", selection.getServiceTier());
        assertEquals("服务端新模型", selection.getModel().getDisplayName());
        assertEquals(ModelCatalog.Availability.AVAILABLE, selection.getModel().getServerDeclaredAvailability());
        assertEquals(ModelCatalog.Source.APP_SERVER, selection.getCatalog().getSource());
        assertEquals("model/list", selection.getCatalog().getSourceDetail());
        assertEquals(1234L, selection.getCatalog().getFetchedAtEpochMillis());
        assertEquals("account-a", selection.getCatalog().getAccountScope());
    }

    @Test
    public void rejectsUnknownModelAndParametersInsteadOfFallingBack() {
        Map<String, Object> metadata = model("known-model");
        metadata.put("supportedReasoningEfforts", Collections.singletonList("server-effort"));
        ModelCatalog catalog = catalog(metadata);

        assertReason(ModelCatalog.Rejection.UNKNOWN_MODEL,
                () -> catalog.select("invented-model", null, null));
        assertReason(ModelCatalog.Rejection.UNSUPPORTED_REASONING_EFFORT,
                () -> catalog.select("known-model", "invented-effort", null));
        assertReason(ModelCatalog.Rejection.UNSUPPORTED_REASONING_EFFORT,
                () -> catalog.select("known-model", "SERVER-EFFORT", null));
        assertReason(ModelCatalog.Rejection.UNSUPPORTED_SERVICE_TIER,
                () -> catalog.select("known-model", "server-effort", "invented-tier"));
    }

    @Test
    public void missingAvailabilityAndVisibilityDoNotInventAccountPermission() {
        Map<String, Object> metadata = model("listed-model");
        metadata.put("visibility", "some-server-visibility");
        ModelCatalog catalog = catalog(metadata);

        assertEquals(ModelCatalog.Availability.UNKNOWN,
                catalog.findModel("listed-model").getServerDeclaredAvailability());
        assertEquals("some-server-visibility", catalog.findModel("listed-model").getVisibility());
        assertNull(catalog.select("listed-model", null, null).getReasoningEffort());
        assertNull(catalog.select("listed-model", null, null).getServiceTier());
        assertFalse(catalog.findModel("listed-model").getReasoningEfforts().contains("invented-effort"));
        assertReason(ModelCatalog.Rejection.UNSUPPORTED_REASONING_EFFORT,
                () -> catalog.select("listed-model", "invented-effort", null));
    }

    @Test
    public void explicitUnavailabilityAlwaysRejectsSelection() {
        Map<String, Object> metadata = model("unavailable-model");
        metadata.put("available", false);
        ModelCatalog catalog = catalog(metadata);

        assertReason(ModelCatalog.Rejection.MODEL_UNAVAILABLE,
                () -> catalog.select("unavailable-model", null, null));
    }

    @Test
    public void acceptsSnakeCaseMetadataAndJsonRpcEnvelope() {
        Map<String, Object> metadata = field("slug", "slug-only");
        metadata.put("display_name", "服务端名字");
        metadata.put("supported_reasoning_efforts", Collections.singletonList(field("reasoning_effort", "future-effort")));
        metadata.put("default_reasoning_effort", "future-effort");
        metadata.put("supported_service_tiers", Collections.singletonList(field("service_tier", "future-tier")));
        Map<String, Object> result = field("data", Collections.singletonList(metadata));
        ModelCatalog catalog = ModelCatalog.fromResponse(ModelCatalog.Source.ACCOUNT_MODELS,
                "/v1/models", 5678, "account-b", field("result", result));

        assertEquals("slug-only", catalog.select("slug-only", "future-effort", "future-tier").getRequestModel());
        assertEquals("future-effort", catalog.findModel("slug-only").getDefaultReasoningEffort());
        assertEquals("服务端名字", catalog.findModel("slug-only").getDisplayName());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void metadataIsAnImmutableDeepSnapshot() {
        Map<String, Object> extension = field("unknownField", "original-value");
        List<Object> options = new ArrayList<>(Collections.singletonList("original-effort"));
        Map<String, Object> metadata = model("snapshot-model");
        metadata.put("extension", extension);
        metadata.put("supportedReasoningEfforts", options);
        ModelCatalog catalog = catalog(metadata);
        extension.put("unknownField", "later-value");
        options.add("later-effort");
        metadata.put("available", false);

        ModelCatalog.Model stored = catalog.findModel("snapshot-model");
        assertEquals("original-value", ((Map<String, Object>) stored.getRawMetadata().get("extension")).get("unknownField"));
        assertEquals(ModelCatalog.Availability.UNKNOWN, stored.getServerDeclaredAvailability());
        assertFalse(stored.getReasoningEfforts().contains("later-effort"));
        assertThrows(UnsupportedOperationException.class,
                () -> stored.getRawMetadata().put("new", true));
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map<String, Object>) stored.getRawMetadata().get("extension")).put("new", true));
        assertThrows(UnsupportedOperationException.class,
                () -> stored.getReasoningEfforts().add("new-effort"));
        assertThrows(UnsupportedOperationException.class,
                () -> catalog.getModels().clear());
    }

    @Test
    public void refreshedCatalogDoesNotRetainRemovedModelsOrEfforts() {
        Map<String, Object> old = model("same-model");
        old.put("supportedReasoningEfforts", Collections.singletonList("old-effort"));
        ModelCatalog before = catalog(old);
        Map<String, Object> updated = model("same-model");
        updated.put("supportedReasoningEfforts", Collections.singletonList("new-effort"));
        ModelCatalog after = ModelCatalog.fromMetadata(ModelCatalog.Source.ACCOUNT_MODELS,
                "/v1/models", 5678, "account-b", Collections.singletonList(updated));

        assertEquals("old-effort", before.select("same-model", "old-effort", null).getReasoningEffort());
        assertReason(ModelCatalog.Rejection.UNSUPPORTED_REASONING_EFFORT,
                () -> after.select("same-model", "old-effort", null));
        assertEquals("new-effort", after.select("same-model", "new-effort", null).getReasoningEffort());
        ModelCatalog empty = ModelCatalog.fromMetadata(ModelCatalog.Source.ACCOUNT_MODELS,
                "/v1/models", 6789, "account-b", Collections.emptyList());
        assertReason(ModelCatalog.Rejection.UNKNOWN_MODEL,
                () -> empty.select("same-model", null, null));
    }

    @Test
    public void malformedOrConflictingMetadataFailsClearly() {
        assertThrows(IllegalArgumentException.class, () -> catalog(field("displayName", "missing-id")));
        Map<String, Object> metadata = model("duplicate-id");
        assertThrows(IllegalArgumentException.class, () -> ModelCatalog.fromMetadata(
                ModelCatalog.Source.IMPORTED, "fixture", 0, null, Arrays.asList(metadata, metadata)));
        metadata.put("supportedReasoningEfforts", Collections.singletonList("declared-effort"));
        metadata.put("defaultReasoningEffort", "undeclared-default");
        assertThrows(IllegalArgumentException.class, () -> catalog(metadata));
        assertThrows(IllegalArgumentException.class, () -> ModelCatalog.fromResponse(
                ModelCatalog.Source.UNKNOWN, "fixture", 0, null, field("data", "invalid-array")));
    }

    private static ModelCatalog catalog(Map<String, Object> metadata) {
        return ModelCatalog.fromMetadata(ModelCatalog.Source.APP_SERVER,
                "model/list", 1234, "account-a", Collections.singletonList(metadata));
    }

    private static Map<String, Object> model(String id) {
        return field("id", id);
    }

    private static Map<String, Object> field(String name, Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(name, value);
        return result;
    }

    private static void assertReason(ModelCatalog.Rejection reason, Runnable selection) {
        ModelCatalog.SelectionException failure = assertThrows(ModelCatalog.SelectionException.class, selection::run);
        assertEquals(reason, failure.getReason());
    }
}
