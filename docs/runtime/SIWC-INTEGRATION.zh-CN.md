# 手机端 ChatGPT 订阅接入

本阶段接回已有的账号模块，增加中文界面入口与一次受控文本推理。运行时探针仍作为独立开发模块保留。登录、模型目录和推理状态来自各自的实际结果，统一验收记录另见本次测试报告。

## 官方路线

首次登录使用 SIWC 开源本地应用的动态注册入口，应用身份为 `xiyan_agent`。手机先绑定 `127.0.0.1` 的本机回调，再打开系统浏览器；每次生成独立的 state、nonce 和 S256 PKCE。回调发回的 `oaiapp_…` 身份与经过验证的账号 subject 保存在同一注册关系中，后续重连沿用该关系。用户在官方浏览器页面完成登录和授权。[官方注册与登录说明](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)

模型选择读取当前 OAuth 会话的 `GET https://api.openai.com/v1/models`，按返回顺序显示 `visibility=list` 的条目，并保留 `slug`、名称及实际提供的推理参数。目录存在后，仍需完成推理请求来验证该次访问。文本请求发送至公开 Responses API，使用 `store:false`、`stream:true`；只有收到 `response.completed` 且响应状态为 `completed` 时，界面显示完成。[官方模型与推理说明](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)

思考选项兼容官方 `supported_reasoning_levels:[{effort,...}]` 与 `default_reasoning_level`，同时保留已有 `supported_reasoning_efforts` 等字段格式；服务等级对象支持 `id`。新增测试引用官方 Codex `6ff1e1ca3b30fc8b914720681fcf5fe62e95f856` 的公开模型元数据。目录校验失败时仅保存固定字段名、JSON 类型、数组长度和白名单成员键存在性；实际字段值留在应用内。推理请求独立固定为 `default`。

后续嵌入 app-server 时，可通过独立 Responses provider 传入本应用的 OAuth 令牌。官方说明该配置下的 `model/list` 可能来自随客户端提供的目录；手机界面因此优先展示 OAuth 账号目录。本阶段文本请求直接走上述公开接口。[官方 app-server 接入说明](https://developers.openai.com/siwc/token-sharing-open-source/codex-app-server)

## 已实现的代码

| 部分 | 行为 |
| --- | --- |
| `PendingSignIn` | 独立随机事务、精确回调校验、单次消费、五分钟期限、错误回调校验 |
| `LoopbackCallbackRequest` | 校验请求行与精确 Host，限制请求总量；每次读取检查取消与截止时间 |
| `IdTokenVerifier` / `TokenClaimsPolicy` | 从可信发现文档读取 JWKS，验证 RS256 签名及 issuer、audience、azp、nonce、时间和账号身份；存在 `at_hash` 时校验绑定 |
| `SessionStore` | Android Keystore 密钥、AES-GCM、AtomicFile 和应用私有非备份目录；统一限制明文和加密文件大小 |
| `SiwcClient` | 读取本机会话、官方发现文档检查、显式登录、账号目录刷新、取消、同账号重连及退出 |
| `SiwcInference` | 手动发起一次纯文本推理，按实际目录校验模型与思考程度；标准服务级别 `default`、输入上限 4000 字符，响应大小与耗时由本地限制 |

回调读取使用单调时钟，单条 HTTP 请求最多五秒，整个等待过程最多五分钟。文本推理设置九十秒看门狗、十五秒连接与读取超时；SSE 总量上限 2 MiB，显示文本上限 32768 字符。停止会断开当前推理连接，递增取消代次，迟到的内容和结束事件随之失效。

推理失败、未完成、连接中断、超时和取消分别保留状态。已接收的内容以预览形式展示；完整结束事件决定完成状态。该入口只处理文本，没有手机动作工具。

`0.1.0-p0.3` 按官方订阅接口的预览规范，将 HTTP `input` 发送为消息数组，并省略该路线尚未支持的 `max_output_tokens`；继续使用本地九十秒期限、2 MiB 流上限和 32768 字符显示上限。请求构造与错误分类有独立回归测试。[官方预览限制](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)

HTTP 错误只保留经过长度和格式限制的状态码、错误代码、参数名及请求 ID。403 策略或路由限制保留本机会话，401 或 `invalid_grant` 提示重新连接。完成证据单独记录服务端响应 ID、实际报告的模型与服务等级、结束状态和耗时，缺少字段时保持未知。[官方错误与恢复](https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery)

debug 主 APK 提供受 `android.permission.DUMP` 保护的 `LiveAccountVerificationActivity`，与当前应用共用进程和账号单例。`inspect` 即时读取状态，保留正在进行的授权事务；`catalog` 和 `verify` 遇到账号忙碌时返回 `operation_in_progress`，由操作者等待登录结束后再调用。普通启动方式保留应用进程，验证入口始终避免调用账号取消。

每次验证要求独立 UUIDv4 `requestId`，将白名单 JSON 原子写入应用 `no_backup/verification/<UUID>.json`。结果包括实际来源、进程 ID、登录阶段与固定错误码；排除账号邮箱、subject、客户端身份、目录 accountScope 和凭据。推理使用独立随机标记，要求新的完成证据与对应合成结果一致；其他回合的文本保留在应用内存中。release 构建排除该调试入口。

真实账号验证已移出 instrumentation。Android instrumentation 的启动和结束可能重启目标应用进程，因此四项合成密钥库测试安排在账号操作空闲时，并继续使用独立文件与密钥命名空间。

## 会话与隐私

访问令牌、刷新令牌和 ID token 保存在手机加密文件中，网络请求通过固定 HTTPS 源发送。授权 URL、回调查询、令牌和官方原始错误正文仅用于对应的私密处理流程，诊断只展示固定错误类别。模型目录只驻留当前进程内存，刷新开始时清除旧目录；账号变更与退出同时清除推理文本。

刷新请求由同一工作队列串行执行。若用户在服务器完成令牌轮换后取消，客户端先验证并原子保存同一会话的新令牌，再停止后续模型请求。刷新响应省略未变化的 scope 时，沿用此前已验证的 scope。退出会保留账号与注册身份的对应关系，并尝试撤销远端可续期会话；远端撤销未确认时，界面提示用户到官方设置管理授权。[官方账号与会话说明](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions)、[OAuth 2.0 刷新规范](https://www.rfc-editor.org/rfc/rfc6749#section-6)

`Snapshot.hasSession` 表示手机持有已保存的会话；`connected` 还检查本地有效期与需重登状态。官方发现文档可达、手机会话存在、账号目录返回和文本推理完成，各有独立状态。额度受限时显示实际错误并提供官方 Usage 入口，额度数字等待受支持来源。

## 本阶段验收与后续

本次开发先验证编译、核心密码学与回调策略、SSE 解析、界面及未登录状态。账号注册和浏览器授权由用户执行；真实目录、真实推理、Keystore 令牌轮换、取消时的服务器轮换竞态、退出撤销及进程恢复，需要在模拟器完成账号授权后继续验收。

现有历史证据显示：`99e74b5` 的 ARM64 真机离线 MCP 固定回显通过，app-server 退出码为零且 fixture 进程已退出；x86_64 模拟器的较早初始化实验退出码为 159。ARM64 AVD 在当前 x86_64 宿主上的启动日志报告架构不受支持。本轮设备实验仅使用模拟器，运行时兼容性以本轮实际探针报告更新。

多账号切换、完整 app-server 模型回合、观察与手机动作循环、任务持久化及长时间游戏任务继续分阶段实现。当前登录入口用于原账号注册和重连。
