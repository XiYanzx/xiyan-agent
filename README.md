# Xiyan Agent

<!-- 中文文档默认展示字体：微软雅黑。Markdown 字体由阅读器设置决定。 -->

独立 Android 双模式智能体项目：通用手机操作与游戏操作。目标 Android 10+、ARM64，优先适配 iQOO 12 Pro / Android 15；按用户最新指示在本地 Windows 构建和测试。

**当前为 P0.10 开发阶段的技术预览，P0 整体验收继续进行。** 新版采用“XIYAN / 现场笔记”黑白编辑式界面，保留暖白与深黑阅读面，以橙色突出主操作，统一对话、任务、模型、账号、用量和设置。加入用户发起的登录前台保护、加密的原事务恢复、手动回调及 Shizuku 有界输入后端。完整手机自主任务、Galaxy 游戏闭环和长周期恢复继续开发。

本轮开发构建已有 167 项 JVM 测试和 app/runtime-probe 双 debug lint 通过。全新隔离 Android 15 x86_64 模拟器 `XiyanP010_Isolated` 实跑 8 项合成测试，全部通过；测试只使用隔离密钥、文件和本机回调。Shizuku 应用授权仍等待用户确认，授权后的输入与停止效果继续实测。iQOO 12 Pro 上登录问题的具体触发原因与改善待用户复测；当前进度见 [P0.10 阶段状态](docs/P010-STATUS.zh-CN.md)，最终安装包来源和发布结果由对应交付报告列明。

最新要求：**编译、测试、打包在用户本地电脑；不在 GitHub 上构建。** GitHub 保存源码并分发本地构建的 Release。详见[最新需求补充](docs/REQUIREMENTS-ADDENDUM.zh-CN.md)，该要求覆盖原任务书的自动 Actions 构建条款及此前 Cloud-only 要求。

对话采用独立消息区和固定输入栏，侧栏进入各个页面。生成过程中发送按钮原位变为停止，其他页面按运行状态在工具栏提供停止，空闲时保留菜单入口。草稿、输入焦点、选区和阅读位置继续保留；停止或失败后可由用户手动继续或重试原提问。原控制测试台收纳到开发者页面，保留 Android 14+ 指定窗口内存截图、单次点击/滑动复核及回调失效处理。主 APK 通过 HTTPS 连接官方服务；Codex/V8 仍在独立运行时探针模块中，等待兼容性验证。所有真实模型请求使用标准服务等级。

| 材料 | 内容 |
| --- | --- |
| [完整任务书](docs/REQUIREMENTS.zh-CN.md) | 用户要求及 P0–P6 验收 |
| [P0 研究报告](docs/P0-feasibility.md) | 官方账号、模型、运行时与手机控制路线 |
| [P0 开发状态](docs/P0-STATUS.zh-CN.md) | 已实现项、构建证据和未过门槛 |
| [Windows 本地复验](docs/testing/local-windows-2026-11-02.md) | 本地 APK、35 项测试、模拟器与公开下载核对 |
| [本轮本地接手](docs/testing/work-local-2026-11-02.md) | 源码差异、UI、账号基础、模拟器与待授权步骤 |
| [P0.10 阶段状态](docs/P010-STATUS.zh-CN.md) | 黑白界面、登录恢复、167 项 JVM 与隔离模拟器 8 项测试、待授权内容 |
| [设计系统 2.0](docs/DESIGN-SYSTEM-2.zh-CN.md) | XIYAN / 现场笔记、黑白排版、橙色主操作、组件及验收要求 |
| [Shizuku 后端](docs/runtime/SHIZUKU-INTEGRATION.zh-CN.md) | 官方依赖、有限动作、授权、实际身份及停止约束 |
| [P0.9 设计系统](docs/DESIGN-SYSTEM.zh-CN.md) | 青瓷方向的历史规范 |
| [P0.9 设计质量](docs/UI-DESIGN-QUALITY-P0.9.zh-CN.md) | 三方向取舍、主要页面变化、图标许可和分阶段验证状态 |
| [P0.8 页面结构与用量](docs/UI-REFRESH-MOBILE-CODEX.zh-CN.md) | Mobile Codex 参考、侧栏与独立页面的历史设计记录 |
| [官方用量](docs/runtime/OFFICIAL-USAGE.zh-CN.md) | 额度百分比与 token 的实际数据来源、时间及未知状态 |
| [SIWC 接入](docs/runtime/SIWC-INTEGRATION.zh-CN.md) | 自有身份、令牌保护、账户目录和受控文本推理 |
| [运行时实测](docs/runtime/P0-RUNTIME-SPIKE.zh-CN.md) | 实际命令、退出码、阻碍与原始证据 |
| [架构](docs/architecture-p0.zh-CN.md) | 当前模块及实现边界 |
| [模型兼容](docs/model-compatibility.zh-CN.md) | 动态目录解析与未接入项 |
| [安装和手机验证](docs/install.zh-CN.md) | 手动权限、测试台与报告 |
| [构建指南](docs/build.zh-CN.md) | SDK/JDK、本地检查、签名与校验 |
| [变更记录](CHANGELOG.md) | 版本范围 |

## P0.9 开发阶段原记录

以下两段保留当时的开发进度；本轮状态见上方 P0.10 记录。历史安装包的最终结果以对应 Release 附件为准。

**当前为 P0 技术预览，完整自主任务仍待实现，P0 未验收。** P0.9 采用“青瓷 · 墨色工作台”和统一 [Design System](docs/DESIGN-SYSTEM.zh-CN.md)，以暖白、墨绿和青瓷色组织深浅主题，重做聊天、任务、模型、用量及设置的视觉层级。官方 SIWC 登录、账户模型目录与标准等级文本推理保留；用量按实际官方字段显示。Galaxy 自主执行、任务监督与长周期验收继续待办。

P0.9 的 R1 开发构建已在模拟器取得八个账号模型，并完成一次 `gpt-6.1-sol` 服务默认思考、标准服务等级的真实文本请求：输入 36、输出 18、合计 54 token。R2 开发构建的 assemble 与 lint 已通过；最终完整测试、干净提交产物和独立视觉复审仍待完成。过程、素材来源和当前验收状态见 [P0.9 设计质量记录](docs/UI-DESIGN-QUALITY-P0.9.zh-CN.md)，安装包能力继续以对应版本验证报告为准。

## 安装包与许可

技术验证 APK 通过 [GitHub Releases](https://github.com/XiYanzx/xiyan-agent/releases) 的 prerelease 提供。下载时核对该 Release 的 `BUILD-INFO.json`、签名和 SHA256；正式 P6 发行必须满足全部阶段和真机门槛。P0 包名 `io.xiyan.agent.p0`，使用调试测试证书，与未来正式应用隔离。

源码采用 [GPL-3.0-only](LICENSE)，依赖与研究来源见 [第三方声明](THIRD_PARTY_NOTICES.md)。P0.9 功能图标采用固定版本 Lucide，保留完整 ISC 及相关 MIT 许可、原始 SVG 与派生资源散列，见 [图标来源清单](third_party/lucide/PROVENANCE.json)。Xiyan 品牌图形为原创；Mobile Codex 用作研究参考，其源码与 Termux 运行时二进制继续保持独立。

Shizuku API/provider 固定为 13.1.5，完整 MIT 许可随应用和源码保留。Shizuku 管理器为独立应用，启动与应用授权按其官方流程完成。
