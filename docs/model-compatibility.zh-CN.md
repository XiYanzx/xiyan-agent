# Xiyan Agent 模型兼容说明

## 当前状态

当前已提供纯 Java 的 [ModelCatalog 元数据解析与选择验证源码](../core/src/main/java/io/xiyan/agent/core/model/ModelCatalog.java)，以及 [8 个 JUnit4 测试](../core/src/test/java/io/xiyan/agent/core/model/ModelCatalogTest.java)。测试覆盖服务端新增值、未知模型与参数、未知及明确不可用状态、响应格式、快照不可变性、刷新后选项移除和错误元数据；测试数据为人工构造数据。

`0.1.0-p0.2` 已将该模块接入 Android 模型选择界面与官方 SIWC 账户目录接口。用户完成官方授权后，`SiwcClient` 使用手机自身的 OAuth 会话刷新目录，选择时复核当前账号、目录快照和思考选项。手动文本请求采用标准服务等级。具体模型的账户实际访问能力仍等待真实授权与完整成功回合验证；P0–P6 尚未完成验收。

## 输入与 API

JSON 解码和网络请求由 Android 账号适配器负责。本模块接收已解码的 `Map` / `List`，负责不可变目录与选择规则。

| API | 作用 |
| --- | --- |
| `fromMetadata(source, sourceDetail, fetchedAtEpochMillis, accountScope, rows)` | 从模型对象列表创建不可变目录快照 |
| `fromResponse(..., response)` | 从 `data` 或 `models` 数组创建快照，也接受 JSON-RPC `result` 对象内的上述数组 |
| `getModels()` / `findModel(id)` | 读取目录；未知 ID 的查找结果为 `null` |
| `select(id, reasoningEffort, serviceTier)` | 按当前快照验证选择，返回保留原值的 `Selection` |
| `Selection.getRequestModel()` | 取得服务端声明的请求模型值，供账号请求适配器使用 |

目录中的 `id` 与请求参数可能不同。模型 ID 按 `id`、`slug`、`model` 顺序读取；`getRequestModel()` 按 `model`、`slug`、`id` 顺序保留请求值。调用方应使用目录 ID 进行选择，再读取请求值，不能假定两者相同。

元数据兼容 `displayName` / `display_name`、`supportedReasoningEfforts` / `supported_reasoning_efforts` 等字段。推理程度和服务等级支持字符串数组或携带对应值的对象数组；未知扩展字段保存在原始元数据中。字段格式错误、模型 ID 重复、默认推理程度不在支持列表等情况会拒绝创建目录，不能静默修补为猜测值。

## 选择规则与可用性

模型 ID、推理程度和服务等级必须来自实际服务端元数据，不能硬编码模型名单或固定选项。新增值按服务端原值保留；模块不改写大小写、不映射 effort、不自动回退。

- 未知模型、未声明支持的非空推理程度或服务等级会被拒绝。
- `reasoningEffort` 或 `serviceTier` 为 `null` 表示未来调用层不发送该参数，由运行时决定默认值；模块不会补造选项或自动注入目录默认值。
- `available: false` 会阻止选择；`available: true` 只表示服务端作出该声明。
- 缺少 `available` 时保持 `UNKNOWN`。可见性 `visibility` 只保留原值，不能转换为账户权限。`UNKNOWN` 允许配置选择，仍需真实调用确认访问结果。

app-server 目录可能来自捆绑数据或缓存；即使服务端声明可用，也不能据此推断当前账户具有调用权限、额度充足或请求一定成功。

## 来源、刷新与后续验证

每个快照保留调用方提供的来源类型（`ACCOUNT_MODELS`、`APP_SERVER`、`IMPORTED`、`UNKNOWN`）、来源说明、刷新时间、账户范围 `accountScope` 和每个模型的完整原始元数据。账户范围应使用不含令牌的标识，未知时可为 `null`；这些标签本身不会触发账户核验。

刷新时创建新快照。当前 Android 接入已包含刷新、账号范围、旧快照拒绝和错误提示；实际访问结果由手动文本请求的完整结束事件确认。多账号切换和账户目录的网络实测仍待完成。

官方路线与本轮代码范围见 [SIWC 接入说明](runtime/SIWC-INTEGRATION.zh-CN.md)和[本地接手报告](testing/work-local-2026-11-02.md)。app-server 的 Android 兼容性与 SIWC 直接 HTTPS 目录、文本请求分别验证；本轮真实账号登录与推理继续等待用户授权。
