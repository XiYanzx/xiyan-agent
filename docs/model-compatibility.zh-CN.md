# Xiyan Agent 模型兼容说明

## 当前状态

当前已提供纯 Java 的 [ModelCatalog 元数据解析与选择验证源码](../core/src/main/java/io/xiyan/agent/core/model/ModelCatalog.java)，以及 [8 个 JUnit4 测试](../core/src/test/java/io/xiyan/agent/core/model/ModelCatalogTest.java)。测试覆盖服务端新增值、未知模型与参数、未知及明确不可用状态、响应格式、快照不可变性、刷新后选项移除和错误元数据；测试数据为人工构造数据。

该模块尚未接入 Android 模型选择界面、账户目录接口、OAuth、Codex app-server 或手机端运行时。任何具体模型、推理程度和服务等级的账户实际可用性均未验证；解析成功或 `select()` 成功不能作为账户调用权限或推理成功的证据。P0 尚未验收，P1–P6 也尚未验收。

## 输入与 API

JSON 解码和网络请求由未来的 Android 适配器负责。本模块只接收已解码的 `Map` / `List`，自身不发起请求、不执行认证或模型调用。

| API | 作用 |
| --- | --- |
| `fromMetadata(source, sourceDetail, fetchedAtEpochMillis, accountScope, rows)` | 从模型对象列表创建不可变目录快照 |
| `fromResponse(..., response)` | 从 `data` 或 `models` 数组创建快照，也接受 JSON-RPC `result` 对象内的上述数组 |
| `getModels()` / `findModel(id)` | 读取目录；未知 ID 的查找结果为 `null` |
| `select(id, reasoningEffort, serviceTier)` | 按当前快照验证选择，返回保留原值的 `Selection` |
| `Selection.getRequestModel()` | 取得服务端声明的请求模型值，供未来运行时适配器使用 |

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

刷新时创建新快照。账户切换后须重新取得对应目录并更新选择；已移除的模型或参数不能从旧快照继续补入。Android 接入时还需实现目录刷新、账户隔离、错误提示和实际访问结果记录。

官方授权、账户模型目录与 app-server 的研究路线及来源见 [P0 技术可行性报告](P0-feasibility.md)。该报告中的路线仍待手机端实测。本说明不新增账户登录、运行时启动、模型推理、构建或真机验证成功的结论。
