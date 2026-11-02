# 官方用量数据接入

2026-11-02，Asia/Shanghai。本轮把周期额度和最近一次完成调用的 token 统计接入独立用量页面。真实百分比是否可取得，以本应用当前账号请求实际返回的字段为准。

## 当前路线

Xiyan 使用自己注册的 SIWC 身份和公开 `api.openai.com/v1` 接口。正常 Responses 请求被动观察官方额度响应头和 `codex.rate_limits` 事件；完成事件读取 `response.usage`。主动检查复用已有的 `/v1/models` GET，反馈该请求是否提供读数，保留官方 Usage 页面入口。

Mobile Codex 使用本地 app-server 的 `account/rateLimits/read` 与 `account/rateLimits/updated`。固定官方 app-server 源码要求相应 Codex 认证，当前 SIWC 文档提供 Usage 页面入口。本轮保持现有账号授权路线，按实际响应更新数据。

## 字段与含义

| 展示 | 官方字段 |
| --- | --- |
| 主要窗口 | `x-codex-primary-used-percent`、`x-codex-primary-window-minutes`、`x-codex-primary-reset-at` |
| 第二窗口 | 对应的 `x-codex-secondary-*` 字段 |
| 额外 credits | `x-codex-credits-has-credits`、`x-codex-credits-unlimited`、`x-codex-credits-balance` |
| 流内窗口更新 | `codex.rate_limits` 的默认 Codex bucket |
| token 总量 | `usage.input_tokens`、`output_tokens`、`total_tokens` |
| token 明细 | `input_tokens_details.cached_tokens`、`cache_write_tokens`、`output_tokens_details.reasoning_tokens` |

周期剩余百分比从官方已用百分比计算。窗口名称依据实际分钟数生成，重置时间使用返回的秒级时间戳。缓存和推理 token 属于明细，单独列示，保留服务端报告的 total。

## 状态与合并

`QuotaSnapshot` 与 `ResponseTokenUsage` 分别建模。缺失为未知，真实零值保留；非法数值和非有限数值保持未知。每个窗口与 credits 各自记录来源及观测时间，五分钟或重置时间经过后标记旧数据。保留旧窗口时继续使用它自己的来源与时间。

响应首尾只读取九个允许头；后读头保持同一次采集时间，流内较新的窗口优先。事件限定为默认或明确的 Codex bucket，其他专属 bucket 保留隔离。账号绑定与取消代际在写回前再次检查，退出或新登录清空旧记录。

缺少新字段时保留同账号已有记录及原观测时间；失败不覆盖最近一次完成调用的 token。用量检查只有到达本轮成功完成阶段才记录 `usage_checked`；取消、失败和未完成各自保留状态。该状态表示检查完成，额度是否实际提供由 `quota.hasData` 表达。

## 真实验证与诊断

debug 同进程入口增加 `usage` 模式。安全报告仅包含固定数据字段、来源、时间与数值，账号范围和凭据留在应用内；`inspect` 可读取最近完成回合的模型、服务等级和完成元数据。页面和截图按实际返回值验收；当前渠道没有周期字段时显示 `—`，token 区独立显示可取得的计数。

本轮开发构建已通过模拟器聊天界面的真实调用：`gpt-6.1-sol` 返回 `completed`，服务等级为 `default`，耗时 3800 ms；输入 16、输出 11、合计 27 token，缓存、缓存写入和推理明细均为 0。该记录来自 `cbb8ecc07cfc23444741b643d53713e0860eb7f8` 上的未提交 P0.8 开发构建。目录检查及本次推理均未取得有效的默认周期额度读数，页面保留 `—`。安全摘要记录有效读数状态，头缺失与非法值被拒绝尚未分别导出。

最终干净提交的构建、界面及实际调用记录随本轮 Release 附件提供，开发构建证据保留自己的来源。本轮新增 16 项纯 Java 用量边界测试，早先 P0.7 测试保留其原来源。

## 依据

- [SIWC 账户与用量说明](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions)
- [官方 app-server](https://learn.chatgpt.com/docs/app-server)
- [官方 `rate_limits.rs`](https://github.com/openai/codex/blob/6ff1e1ca3b30fc8b914720681fcf5fe62e95f856/codex-rs/codex-api/src/rate_limits.rs)
- [官方 Responses SSE](https://github.com/openai/codex/blob/6ff1e1ca3b30fc8b914720681fcf5fe62e95f856/codex-rs/codex-api/src/sse/responses.rs)
- [Responses 结果字段](https://developers.openai.com/api/reference/resources/responses/methods/retrieve)
- [Mobile Codex 固定版本账户读取](https://github.com/nokryong/mobile-codex/blob/0bca7f0eed28eb3a3c9554db38e13d00136b95d0/app/src/main/java/dev/mobilecodex/app/Engine.java)
