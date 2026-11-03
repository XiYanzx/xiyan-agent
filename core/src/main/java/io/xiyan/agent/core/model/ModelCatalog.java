package io.xiyan.agent.core.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 服务端模型目录的一次不可变快照。JSON 解码和网络请求由平台适配器负责。
 *
 * <p>目录存在、可见和账户有调用权限是不同的证据。这里的可用状态只复述服务端
 * {@code available} 字段；app-server 可能返回缓存，所以选择成功不能作为账户权限证明。
 * 刷新或切换账户时应创建新快照，不能把旧目录当作新账户的目录。
 */
public final class ModelCatalog {
    public enum Source {
        ACCOUNT_MODELS, APP_SERVER, IMPORTED, UNKNOWN
    }

    public enum Availability {
        AVAILABLE, UNAVAILABLE, UNKNOWN
    }

    public enum Rejection {
        UNKNOWN_MODEL, MODEL_UNAVAILABLE, UNSUPPORTED_REASONING_EFFORT, UNSUPPORTED_SERVICE_TIER
    }

    /** 选择错误保留稳定的机器可读原因，供 Android 显示本地化提示。 */
    public static final class SelectionException extends IllegalArgumentException {
        private final Rejection reason;

        private SelectionException(Rejection reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Rejection getReason() {
            return reason;
        }
    }

    private final Source source;
    private final String sourceDetail;
    private final long fetchedAtEpochMillis;
    private final String accountScope;
    private final List<Model> models;
    private final Map<String, Model> byId;

    private ModelCatalog(Source source, String sourceDetail, long fetchedAtEpochMillis,
            String accountScope, List<? extends Map<String, ?>> rows) {
        this.source = Objects.requireNonNull(source, "source");
        this.sourceDetail = requireText(sourceDetail, "sourceDetail");
        if (fetchedAtEpochMillis < 0) {
            throw new IllegalArgumentException("目录刷新时间不能为负数");
        }
        this.fetchedAtEpochMillis = fetchedAtEpochMillis;
        this.accountScope = accountScope;
        Objects.requireNonNull(rows, "rows");
        Map<String, Model> indexed = new LinkedHashMap<>();
        for (Map<String, ?> row : rows) {
            Model model = new Model(Objects.requireNonNull(row, "model metadata"));
            if (indexed.put(model.getId(), model) != null) {
                throw new IllegalArgumentException("模型 ID 重复: " + model.getId());
            }
        }
        this.byId = Collections.unmodifiableMap(indexed);
        this.models = Collections.unmodifiableList(new ArrayList<>(indexed.values()));
    }

    /** accountScope 是调用方提供的不含令牌的账户标识；未知时允许为 null。 */
    public static ModelCatalog fromMetadata(Source source, String sourceDetail,
            long fetchedAtEpochMillis, String accountScope,
            List<? extends Map<String, ?>> rows) {
        return new ModelCatalog(source, sourceDetail, fetchedAtEpochMillis, accountScope, rows);
    }

    /** 接收已解码的 data/models 数组，或 JSON-RPC result 中的同类数组。 */
    public static ModelCatalog fromResponse(Source source, String sourceDetail,
            long fetchedAtEpochMillis, String accountScope, Map<String, ?> response) {
        Objects.requireNonNull(response, "response");
        Map<?, ?> container = response;
        if (response.get("result") instanceof Map<?, ?>) {
            container = (Map<?, ?>) response.get("result");
        }
        Object rows = first(container, "data", "models");
        if (!(rows instanceof List<?>)) {
            throw new IllegalArgumentException("响应中缺少模型数组 data/models");
        }
        List<Map<String, ?>> typedRows = new ArrayList<>();
        for (Object row : (List<?>) rows) {
            if (!(row instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("模型数组成员必须是对象");
            }
            typedRows.add(stringKeyMap((Map<?, ?>) row));
        }
        return fromMetadata(source, sourceDetail, fetchedAtEpochMillis, accountScope, typedRows);
    }

    public Source getSource() {
        return source;
    }

    public String getSourceDetail() {
        return sourceDetail;
    }

    public long getFetchedAtEpochMillis() {
        return fetchedAtEpochMillis;
    }

    public String getAccountScope() {
        return accountScope;
    }

    public List<Model> getModels() {
        return models;
    }

    /** 未知 ID 返回 null，调用方不能据此补造模型。 */
    public Model findModel(String id) {
        return byId.get(id);
    }

    /**
     * 验证原样传给运行时的选择；null 参数表示不发送该参数，由运行时使用默认值。
     * 不做 effort 映射、自动回退或大小写改写。可用性未知时仍可配置选择，但不能据此
     * 宣称该账户能够完成推理；实际访问结果必须由调用层另行记录。
     */
    public Selection select(String id, String reasoningEffort, String serviceTier) {
        Model model = byId.get(id);
        if (model == null) {
            throw new SelectionException(Rejection.UNKNOWN_MODEL, "当前目录没有该模型: " + id);
        }
        if (model.getServerDeclaredAvailability() == Availability.UNAVAILABLE) {
            throw new SelectionException(Rejection.MODEL_UNAVAILABLE, "服务端声明该模型不可用: " + id);
        }
        if (reasoningEffort != null && !model.reasoningEfforts.contains(reasoningEffort)) {
            throw new SelectionException(Rejection.UNSUPPORTED_REASONING_EFFORT,
                    "模型元数据未声明该推理程度: " + reasoningEffort);
        }
        if (serviceTier != null && !model.serviceTiers.contains(serviceTier)) {
            throw new SelectionException(Rejection.UNSUPPORTED_SERVICE_TIER,
                    "模型元数据未声明该服务等级: " + serviceTier);
        }
        return new Selection(this, model, reasoningEffort, serviceTier);
    }

    /** 模型数据只来源于本次响应；不预置任何模型或推理程度。 */
    public static final class Model {
        private final String id;
        private final String requestModel;
        private final String displayName;
        private final Availability serverDeclaredAvailability;
        private final String visibility;
        private final Set<String> reasoningEfforts;
        private final Set<String> serviceTiers;
        private final String defaultReasoningEffort;
        private final Map<String, Object> rawMetadata;

        private Model(Map<String, ?> metadata) {
            this.rawMetadata = immutableMap(metadata);
            this.id = requiredMetadataText(metadata, "id", "slug", "model");
            String requestValue = optionalMetadataText(metadata, "model", "slug", "id");
            this.requestModel = requestValue == null ? id : requestValue;
            String name = optionalMetadataText(metadata, "displayName", "display_name", "name");
            this.displayName = name == null ? id : name;
            Object available = metadata.get("available");
            if (available != null && !(available instanceof Boolean)) {
                throw new IllegalArgumentException("available 必须是布尔值");
            }
            this.serverDeclaredAvailability = Boolean.TRUE.equals(available)
                    ? Availability.AVAILABLE : Boolean.FALSE.equals(available)
                    ? Availability.UNAVAILABLE : Availability.UNKNOWN;
            this.visibility = optionalMetadataText(metadata, "visibility");
            this.reasoningEfforts = options(metadata,
                    new String[]{"supportedReasoningEfforts", "supported_reasoning_efforts", "supportedReasoningLevels", "supported_reasoning_levels"},
                    new String[]{"reasoningEffort", "reasoning_effort", "value", "effort"});
            this.serviceTiers = options(metadata,
                    new String[]{"supportedServiceTiers", "supported_service_tiers", "serviceTiers", "service_tiers"},
                    new String[]{"serviceTier", "service_tier", "value", "id"});
            this.defaultReasoningEffort = optionalMetadataText(metadata,
                    "defaultReasoningEffort", "default_reasoning_effort", "defaultReasoningLevel", "default_reasoning_level");
            if (defaultReasoningEffort != null && !reasoningEfforts.contains(defaultReasoningEffort)) {
                throw new IllegalArgumentException("默认推理程度不在服务端支持列表内: " + id);
            }
        }

        public String getId() {
            return id;
        }

        /** app-server 的 id 和实际请求 model 可不同，保留服务端请求值。 */
        public String getRequestModel() {
            return requestModel;
        }

        public String getDisplayName() {
            return displayName;
        }

        public Availability getServerDeclaredAvailability() {
            return serverDeclaredAvailability;
        }

        /** 可见性仅保留原值，不把它转换为账户可用性。 */
        public String getVisibility() {
            return visibility;
        }

        public Set<String> getReasoningEfforts() {
            return reasoningEfforts;
        }

        public Set<String> getServiceTiers() {
            return serviceTiers;
        }

        public String getDefaultReasoningEffort() {
            return defaultReasoningEffort;
        }

        /** 完整保留未知扩展字段，嵌套 Map/List 同样不可修改。 */
        public Map<String, Object> getRawMetadata() {
            return rawMetadata;
        }
    }

    public static final class Selection {
        private final ModelCatalog catalog;
        private final Model model;
        private final String reasoningEffort;
        private final String serviceTier;

        private Selection(ModelCatalog catalog, Model model, String reasoningEffort, String serviceTier) {
            this.catalog = catalog;
            this.model = model;
            this.reasoningEffort = reasoningEffort;
            this.serviceTier = serviceTier;
        }

        public ModelCatalog getCatalog() {
            return catalog;
        }

        public Model getModel() {
            return model;
        }

        public String getRequestModel() {
            return model.getRequestModel();
        }

        public String getReasoningEffort() {
            return reasoningEffort;
        }

        public String getServiceTier() {
            return serviceTier;
        }
    }

    private static Set<String> options(Map<String, ?> metadata, String[] keys, String[] valueKeys) {
        Object value = first(metadata, keys);
        if (value == null) {
            return Collections.emptySet();
        }
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException("支持参数必须是数组: " + keys[0]);
        }
        Set<String> values = new LinkedHashSet<>();
        for (Object option : (List<?>) value) {
            String text;
            if (option instanceof String) {
                text = requireText((String) option, keys[0]);
            } else if (option instanceof Map<?, ?>) {
                text = requiredMetadataText((Map<?, ?>) option, valueKeys);
            } else {
                throw new IllegalArgumentException("支持参数成员必须是字符串或对象: " + keys[0]);
            }
            values.add(text);
        }
        return Collections.unmodifiableSet(values);
    }

    private static String requiredMetadataText(Map<?, ?> metadata, String... keys) {
        String value = optionalMetadataText(metadata, keys);
        if (value == null) {
            throw new IllegalArgumentException("模型元数据缺少字段: " + keys[0]);
        }
        return value;
    }

    private static String optionalMetadataText(Map<?, ?> metadata, String... keys) {
        Object value = first(metadata, keys);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("模型元数据字段必须是字符串: " + keys[0]);
        }
        return requireText((String) value, keys[0]);
    }

    private static Object first(Map<?, ?> metadata, String... keys) {
        for (String key : keys) {
            if (metadata.get(key) != null) {
                return metadata.get(key);
            }
        }
        return null;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }

    private static Map<String, ?> stringKeyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new IllegalArgumentException("JSON 对象键必须是字符串");
            }
            result.put((String) entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static Map<String, Object> immutableMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new IllegalArgumentException("JSON 对象键必须是字符串");
            }
            result.put((String) entry.getKey(), immutableValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Object immutableValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Integer || value instanceof Long || value instanceof Double
                || value instanceof Float || value instanceof Short || value instanceof Byte
                || value instanceof BigInteger || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Map<?, ?>) {
            return immutableMap((Map<?, ?>) value);
        }
        if (value instanceof List<?>) {
            List<Object> result = new ArrayList<>();
            for (Object item : (List<?>) value) {
                result.add(immutableValue(item));
            }
            return Collections.unmodifiableList(result);
        }
        throw new IllegalArgumentException("模型元数据只接受已解码的 JSON 值");
    }
}
