# Xiyan Agent P0 技术可行性报告

研究时间：2026 年 11 月 2 日 UTC。目标仓库：`XiYanzx/xiyan-agent`。目标设备：iQOO 12 Pro、Android 15；兼容目标：Android 10 及以上、ARM64。开发任务遵循用户指定的 Codex Cloud 环境、GPT-6.1 Sol、Ultra。本报告供工程启动和阶段验收使用。

## 结论与当前证据

**建议先制作最小技术验证 APK。正式功能开发依次经过运行时、官方授权和真实设备控制三道门槛。** Android 本地观察、手势、任务持久化具备公开 API 基础；OpenAI 已提供面向开源及本地应用的自有身份订阅授权与 app-server 接入文档。Android 原生运行时、该授权路线的移动端端到端体验、额度读数和真实游戏闭环仍待验证。

本次完成官方文档研究、公开上游代码选择性审阅、发行资产元数据核对与验收设计。构建、安装、账户登录、真实推理、设备操作、游戏截图、30/60/120 分钟运行均为**未执行**。报告中的技术路线属于研究结论；阶段通过必须有对应实测记录。此次未执行上游脚本、未安装运行时、未读取账户令牌、未创建发布。

| 事项 | 研究判断 | 当前状态 | 首个验收证据 |
| --- | --- | --- | --- |
| 手机端 app-server | 有开源基础与第三方 Android 移植参考 | 原版 Android 兼容性待验证 | 签名 APK 冷启动、初始化、真实回合 |
| 自有身份订阅授权 | 官方 OSS SIWC 路线已公开 | Android 回调与账户权限待验证 | 自有 agent 注册、有效 scopes、完成推理 |
| 模型与 Ultra | 上游实际目录含 Sol 和 Ultra | 账户实时目录待读取 | 运行时返回目录及最终选中值 |
| 数字额度 | Codex 托管账户有公开 RPC | SIWC env_key 路线整合待验证 | 正式支持来源的真实读数 |
| 通用手机操作 | Accessibility 与截图 API 有基础 | 真机待验证 | 观察、单步动作、结果复核 |
| 游戏纯视觉 | 可采用截图和坐标手势架构 | 游戏画面与可靠性待验证 | 空节点场景真实闭环 |
| 监督与恢复 | 本地状态机和事务日志可设计 | 持久性待验证 | 故障注入、恢复、停止日志 |

## 官方授权与 app-server

OpenAI 的 [OSS ChatGPT plan usage 概览](https://developers.openai.com/siwc/token-sharing-open-source)明确覆盖开源及本地托管应用。首次准备持久、不含个人身份的 `ext_agent_host_id`，通过官方动态客户端注册取得当前用户及工作区对应的 issued `client_id`。自有应用名称贯穿注册与客户端声明；公开仓库发行适合继续研究这一路线。

[注册及登录文档](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)规定：系统浏览器发起授权；初次入口 `dynamic_agent_client`；注册后的 issued ID 用于令牌交换和后续登录。使用 PKCE S256、随机 state、OIDC nonce；验证 ID token 签名、issuer、audience、expiry 和 nonce，确认 `chatgpt.tokens.use.direct` 等授予范围。回调使用 `http://127.0.0.1:{port}/auth/callback`，先启动监听，同次授权及交换使用完全一致的 URI。端口可以变化，scheme、host、path维持注册规则。

该文档给出本地 loopback 流程，Android 专属兼容声明与本项目端到端结果尚待建立。最小 APK 应测试：系统浏览器往返、应用后台存活、端口占用、用户取消、超时、重复回调和登录中断。此架构的回调监听位于手机本地；当前研究无需增加外部 BFF。OAuth 身份和密钥由官方流程产生，应用如实使用自身名称。

[官方 app-server SIWC 接入](https://developers.openai.com/siwc/token-sharing-open-source/codex-app-server)提供具体 provider 配置：`base_url=https://api.openai.com/v1`、`wire_api=responses`、`env_key=ACCESS_TOKEN`、`requires_openai_auth=false`、`supports_websockets=false`。应用向子进程提供自己的 OAuth access token，通过 stdio JSONL 完成 initialize、initialized、thread/start、turn/start。`clientInfo.name/title/version` 如实标识 Xiyan Agent，并与注册名称一致。令牌续期后重新启动子进程，再以保存的 thread ID 恢复。

[账户与会话文档](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions)要求按注册和账户隔离凭据、串行刷新轮换 token、原子替换整组令牌。退出时依据 OIDC discovery 的撤销端点处理，并提供 ChatGPT Settings Usage 入口。凭据留在受保护存储，授权 URL 中的 ID token hint 统一脱敏。

[预览能力说明](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)允许该路线的文本和图像输入及受支持的 function/custom 调用；原生 hosted computer use 等工具有额外范围限制。手机控制采用本机工具桥，并验证 app-server 发出的工具格式符合该 provider。模型推理经用户授权连接 OpenAI，设备控制、日志和任务监督运行于手机。

## 模型与额度

[模型目录文档](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)要求使用推理所用 token 请求 `GET https://api.openai.com/v1/models`，依据 `visibility`、`display_name`、`slug` 建立账户模型选择器。app-server 的 `model/list` 可能来自捆绑或缓存；一次成功推理提供当次访问证据。界面应标注目录来源和刷新时间，并在账户切换后刷新。

审阅的官方上游 commit `a09812981de703f18f38015cd5d500ab373f616c` 中，[models.json](https://github.com/openai/codex/blob/a09812981de703f18f38015cd5d500ab373f616c/codex-rs/models-manager/models.json) 对 `gpt-6.1-sol` 明列 `low/medium/high/xhigh/max/ultra`；Ultra 包含任务委派语义，`multi_agent_reasoning_effort=xhigh`。[归一化源码](https://github.com/openai/codex/blob/a09812981de703f18f38015cd5d500ab373f616c/codex-rs/protocol/src/openai_models/reasoning_effort.rs)依据模型元数据将 Ultra 转换为普通推理请求使用的 effort，并有 fallback 规则。因此保留用户选择 GPT-6.1 Sol / Ultra，以实际运行时元数据决定可选项，由 Codex 处理映射。公共 [Sol API 模型页](https://developers.openai.com/api/docs/models/gpt-6.1-sol)列出的基础 effort 为 low 至 max；应用应保留两层语义和实际执行证据。

`account/rateLimits/read` 和 `account/rateLimits/updated` 是官方 app-server 能力，但[账户处理器](https://github.com/openai/codex/blob/a09812981de703f18f38015cd5d500ab373f616c/codex-rs/app-server/src/request_processors/account_processor.rs#L1171-L1186)要求 auth_manager 中的 Codex 账户并检查 `uses_codex_backend()`。SIWC 官方 provider 示例使用独立 `env_key`；这条组合的额度数字读取尚缺实证。P0 单列验证，界面在来源缺失时显示“当前渠道未提供额度读数”，提供官方 Usage 页面，并正确处理 401、429、拒绝访问和重置时间。保持来源、账户、时间戳和有效状态；状态缺失时维持未知，等待新证据。

## Android 运行时与工具桥

核对 [openai/codex rust-v0.166.0 release](https://github.com/openai/codex/releases/tag/rust-v0.166.0)（2026 年 10 月 29 日）资产，列有 Linux aarch64 musl、macOS 和 Windows，当前资产清单未列 Android target。Linux ARM64 资产的 Android 兼容性需要单独实验。上游当前模型目录还设置 `tool_mode=code_mode_only`，spike 必须覆盖 code-mode-host 与 V8 依赖、进程启动和实际工具调用。

[Android 10 行为变更](https://developer.android.com/about/versions/10/behavior-changes-10)对 target29+ 的可写应用主目录施加 W^X 执行限制，官方建议加载 APK 内嵌代码。首选验证签名 APK 内置原生运行时及 helper，使用安装后的原生代码路径启动。[Manifest 原生库机制](https://developer.android.com/guide/topics/manifest/application-element)与 AGP 的 `useLegacyPackaging` 提供打包参考；具体 ELF、解释器及依赖仍需实测。[16KB 页支持](https://developer.android.com/guide/practices/page-sizes)要求检查 ELF、APK对齐与固定页大小假设；记录目标设备实际页大小。

建议 spike 顺序：先固定官方 release/tag，研究该版完整 app-server 与 helper 的 Android/Bionic 编译；记录所有编译错误及平台依赖。随后单独审阅固定版本 Android port 的最小平台差异，评估如实标识的开源移植。编译链、来源、补丁和许可证逐项登记。裁剪和组件替换放在完整可启动证据之后，并重新验证兼容性。各选项失败都输出具体 blocker；遇到权限拒绝则停在该操作并记录授权要求。

工具接入优先使用 [官方本地 MCP STDIO](https://learn.chatgpt.com/docs/extend/mcp)：手机上的受控 helper 连接 Android 服务，allowlist 限定观察和动作工具，按实际工具目录呈现能力。图像内容、工具调用及返回格式须纳入 SIWC 真实回合验证。app-server 客户端同样使用 stdio。[app-server 说明](https://learn.chatgpt.com/docs/app-server)将动态工具和 WebSocket 标为 experimental，WebSocket 又有明确 unsupported 标记；采用稳定路径可减少 P0 依赖。

[配置文档](https://learn.chatgpt.com/docs/config-file/config-basic)提供 `features.shell_tool=false` 等能力开关。手机控制初期应最小化模型可见工具，并验证关闭后实际目录与行为。官方[沙箱说明](https://learn.chatgpt.com/docs/sandboxing)覆盖 macOS/Linux/Windows；当前上游 [sandbox manager](https://github.com/openai/codex/blob/a09812981de703f18f38015cd5d500ab373f616c/codex-rs/sandboxing/src/manager.rs#L50-L64)对其他目标返回空平台沙箱。Android 原生子进程保留应用 UID 权限，需要独立验证数据隔离和 fail-closed 行为。

## 观察与动作

[AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)提供 API24+ 的手势、API30+ 的截图、API34+ 的窗口截图能力。Android10 的纯视觉需要 [MediaProjection](https://developer.android.com/media/grow/media-projection)适配器。Android14+ 的每次 capture session需用户同意并满足 mediaProjection FGS 声明；Android15 QPR1+ 锁屏终止投屏。捕获停止、服务断开、方向变化均进入暂停并清理动作。

通用模式采用节点树与截图；游戏模式直接从捕获适配器获取真实图像，覆盖空节点场景。观察记录 observation ID、捕获时间、窗口/包名、尺寸、方向、裁剪与坐标变换。动作携带 task ID、generation、序号、观察 ID、有效期和前后置条件，单队列串行执行；执行后重新观察确认结果。保护内容遵循 Android 的 [FLAG_SECURE](https://developer.android.com/security/fraud-prevention/activities)行为，空白或不可识别画面进入明确暂停。

游戏验收使用用户真实安装的 Galaxy 联合防御，先记录包名、版本、分辨率、画面与实际任务。测试真实截图、目标定位、单步操作、弹窗、遮挡、旋转、网络波动和界面变化。决策时延、截图新鲜度与游戏节奏共同决定可用性；目前这些指标均待实测。

## 持久监督与安全恢复

下列为工程设计建议。前台监督器维护任务状态、app-server 生命周期、超时、单一动作队列及有界退避。数据库事务记录 `prepared / executing / verified / uncertain`；检查点保存目标、已确认进度、当前页面证据和待决策状态。重启后重新观察页面，通过后置条件判断 uncertain 动作的结果，再恢复目标。历史点击保留审计用途。

停止入口先递增 generation、撤销授权并清空队列，再调用 `turn/interrupt` 和工具取消。每次实际派发前复查 generation、捕获能力和观察有效性；保持短手势并确认完成回调。本地 watchdog覆盖子进程死亡，heartbeat采用本地单调时钟与实际状态判断。断网和额度耗尽进入等待或暂停，恢复时刷新凭据、目录、屏幕与任务状态。

[FGS 类型](https://developer.android.com/develop/background-work/services/fgs/service-types)应与任务匹配：采集使用 mediaProjection，独立可见监督器可评估 specialUse 并写明 subtype。[Android13 用户 Stop](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping)会移除整个应用且无停止回调；[Android15 force-stop](https://developer.android.com/about/versions/15/behavior-changes-all)保持 stopped 至用户交互；[Doze](https://developer.android.com/training/monitoring-device-state/doze-standby)可延迟 CPU、网络和作业。系统停止后以持久化状态支持用户显式恢复，长期运行依靠真机数据建立承诺。

[Android Sandbox](https://source.android.com/docs/security/app-sandbox)与[Keystore](https://developer.android.com/privacy-and-security/keystore)提供平台基础。OAuth存储采用应用私有区加密、Keystore保护密钥、备份排除和日志脱敏；解密后的令牌及子进程环境继续按秘密处理。同UID子进程的数据可达性需实测。手机控制授权独立、可撤销，进程重启后重新开启。侧载 APK 在 Android13+ 可能出现[受限设置](https://support.google.com/android/answer/12623953)，通过系统引导由用户授权。

## mobile codex 选择性审计

固定审阅版本：`nokryong/mobile-codex` main commit `0bca7f0eed28eb3a3c9554db38e13d00136b95d0`（2026-10-29）。官方源码审阅版本为 `openai/codex` main commit `a09812981de703f18f38015cd5d500ab373f616c`（2026-11-01）。二者均通过 commits/main 返回值核实；发行元数据另以 rust-v0.166.0 为准。本次只读审阅以下材料，证据仅对应这些文件与该版本。

- [runtime-lock.json](https://github.com/nokryong/mobile-codex/blob/0bca7f0eed28eb3a3c9554db38e13d00136b95d0/tools/runtime-lock.json)：第三方 `@mmmbuto/codex-cli-termux` 0.162.1 port，加 Live patch，固定校验和和来源。
- [prepare_runtime.py](https://github.com/nokryong/mobile-codex/blob/0bca7f0eed28eb3a3c9554db38e13d00136b95d0/tools/prepare_runtime.py)：对 helper 名称作定长二进制改写，输出 APK 原生文件与哈希。借鉴前需逐项审查来源、差异和构建可复现性。
- [build.gradle](https://github.com/nokryong/mobile-codex/blob/0bca7f0eed28eb3a3c9554db38e13d00136b95d0/app/build.gradle)：min29、target35、ARM64、legacy原生打包，要求 libcodex、code-mode-host 和 libc++ 文件存在。
- [PhoneUseService.java](https://github.com/nokryong/mobile-codex/blob/0bca7f0eed28eb3a3c9554db38e13d00136b95d0/app/src/main/java/dev/mobilecodex/app/PhoneUseService.java#L196-L241)：SDK<30报告截图不可用，观察依赖非空 root node。Xiyan Agent 的 Android10纯视觉和空节点游戏仍需专项实现与测试。
- [EngineService.java](https://github.com/nokryong/mobile-codex/blob/0bca7f0eed28eb3a3c9554db38e13d00136b95d0/app/src/main/java/dev/mobilecodex/app/EngineService.java)：前台通知和停止入口，START_NOT_STICKY。持久监督需结合其他模块重新审计和测试。
- [session-recovery.md](https://github.com/nokryong/mobile-codex/blob/0bca7f0eed28eb3a3c9554db38e13d00136b95d0/docs/session-recovery.md)：会话文件原子写入、校验备份与显式恢复，可作为数据损坏处理参考。
- [verification.md](https://github.com/nokryong/mobile-codex/blob/0bca7f0eed28eb3a3c9554db38e13d00136b95d0/docs/verification.md)：历史自动化记录与物理设备行为分别说明。测试记录按版本核对；Xiyan Agent 验收需要自身证据。
- [THIRD_PARTY_NOTICES.md](https://github.com/nokryong/mobile-codex/blob/0bca7f0eed28eb3a3c9554db38e13d00136b95d0/THIRD_PARTY_NOTICES.md)：原项目代码和原创美术采用 GPL-3.0-only，第三方组件保留自身许可。复用时登记路径与改动，保留版权/许可/NOTICE，在发行包提供对应源码和构建材料，结合实际依赖核查发布义务。独立仓库保持自有项目身份。

## 阶段门槛与交付

| 阶段 | 通过条件 | 当前状态 |
| --- | --- | --- |
| P0 A 运行时 spike | 固定 upstream/tag及工具链；完整 APK 内嵌运行时冷启动；initialize成功；code-mode-host和本地MCP可启动；记录ABI、页大小、依赖、哈希、退出码 | 未执行 |
| P0 B 官方授权 spike | 手机上完成自有注册、loopback、scope验证、轮换刷新和退出；文本及图像本地工具回合 completed；Sol/Ultra由实际metadata选择；额度来源核实或明确未提供 | 未执行 |
| P0 C 控制 spike | API29与35真实抓图；iQOO游戏空节点闭环；截图到手势坐标正确；旧帧/旋转/捕获丢失时零后续派发；停止撤销通过 | 未执行 |
| P1 基础应用 | UI、模型与思考程度、账户与授权、控制开关和停止入口完成；全部选项取自实际metadata；登录中断、权限撤销和重复操作覆盖 | 未执行 |
| P2 长周期 Engine | 前台监督、持久任务状态、检查点、单动作队列、generation撤销、超时、watchdog和恢复完成；断网、401、429、捕获及服务断开具有明确状态 | 未执行 |
| P3 通用模式 | 真实应用的打开、读取、输入、滑动和结果确认；危险动作确认规则；旧观察、旋转与重复操作覆盖 | 未执行 |
| P4 游戏模式 | 真实版本/任务的成功率与P50/P95时延达目标；空节点场景、弹窗、遮挡和失败恢复；证据覆盖每次动作和结果 | 未执行 |
| P5 稳定性验证 | 依次完成真实iQOO 30/60/120分钟任务；提交前、提交后、确认前故障注入；零重复及旧generation动作；子进程崩溃、用户Stop、force-stop恢复与取消通过；记录版本、系统build、时间、动作、截图、时延、流量、内存、温度和电量 | 未执行 |
| P6 正式交付 | 针对同一commit通过构建/lint/测试和上述真机门槛；签名一致；APK、SHA256、对应源码、许可、已知问题和验收记录齐备；从用户仓库GitHub Release验证下载资产 | 未执行 |

首个交付可以是明确标为技术验证用途的测试 APK，并附其实际覆盖范围。功能阶段继续由 Codex Cloud 执行。现阶段所需外部输入仅在相应测试发生时取得：用户在手机完成官方登录与系统权限、真实设备观测或受支持的设备测试通道，以及 Release签名材料的安全配置。账户令牌和签名私钥通过受支持秘密存储传递，保持日志与公开仓库洁净。
