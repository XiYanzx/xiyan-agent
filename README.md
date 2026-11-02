# Xiyan Agent

独立 Android 双模式智能体项目：通用手机操作与游戏操作。目标 Android 10+、ARM64，优先适配 iQOO 12 Pro / Android 15；按用户最新指示在本地 Windows 构建和测试。

**当前为 P0 技术预览，完整自主任务仍待实现，P0 未验收。** `0.1.0-p0.8` 以对话为主屏，采用侧栏、固定输入栏和独立的模型、账号用量、任务及设置页面，加入深浅主题与蓝、紫、青、琥珀语义配色。官方 SIWC 登录、账户模型目录与标准等级文本推理保留；用量按实际官方字段显示，周期百分比和最近完成调用的 token 分区呈现。各项实际支持情况见对应安装包验证报告，Galaxy、任务监督与长周期验收继续待办。

最新要求：**编译、测试、打包在用户本地电脑；不在 GitHub 上构建。** GitHub 保存源码并分发本地构建的 Release。详见[最新需求补充](docs/REQUIREMENTS-ADDENDUM.zh-CN.md)，该要求覆盖原任务书的自动 Actions 构建条款及此前 Cloud-only 要求。

对话采用独立消息区和固定输入栏，侧栏进入各个页面。生成过程中发送按钮原位变为停止，其他页面的工具栏保留停止入口。原控制测试台收纳到开发者页面，保留 Android 14+ 指定窗口内存截图、单次点击/滑动复核及回调失效处理。主 APK 通过 HTTPS 连接官方服务；Codex/V8 仍在独立运行时探针模块中，等待兼容性验证。所有真实模型请求使用标准服务等级。

| 材料 | 内容 |
| --- | --- |
| [完整任务书](docs/REQUIREMENTS.zh-CN.md) | 用户要求及 P0–P6 验收 |
| [P0 研究报告](docs/P0-feasibility.md) | 官方账号、模型、运行时与手机控制路线 |
| [P0 开发状态](docs/P0-STATUS.zh-CN.md) | 已实现项、构建证据和未过门槛 |
| [Windows 本地复验](docs/testing/local-windows-2026-11-02.md) | 本地 APK、35 项测试、模拟器与公开下载核对 |
| [本轮本地接手](docs/testing/work-local-2026-11-02.md) | 源码差异、UI、账号基础、模拟器与待授权步骤 |
| [界面改版](docs/UI-REFRESH-MOBILE-CODEX.zh-CN.md) | Mobile Codex 参考、侧栏与独立页面、配色及无障碍 |
| [官方用量](docs/runtime/OFFICIAL-USAGE.zh-CN.md) | 额度百分比与 token 的实际数据来源、时间及未知状态 |
| [SIWC 接入](docs/runtime/SIWC-INTEGRATION.zh-CN.md) | 自有身份、令牌保护、账户目录和受控文本推理 |
| [运行时实测](docs/runtime/P0-RUNTIME-SPIKE.zh-CN.md) | 实际命令、退出码、阻碍与原始证据 |
| [架构](docs/architecture-p0.zh-CN.md) | 当前模块及实现边界 |
| [模型兼容](docs/model-compatibility.zh-CN.md) | 动态目录解析与未接入项 |
| [安装和手机验证](docs/install.zh-CN.md) | 手动权限、测试台与报告 |
| [构建指南](docs/build.zh-CN.md) | SDK/JDK、本地检查、签名与校验 |
| [变更记录](CHANGELOG.md) | 版本范围 |

技术验证 APK 通过 [GitHub Releases](https://github.com/XiYanzx/xiyan-agent/releases) 的 prerelease 提供。下载时核对该 Release 的 `BUILD-INFO.json`、签名和 SHA256；正式 P6 发行必须满足全部阶段和真机门槛。P0 包名 `io.xiyan.agent.p0`，使用调试测试证书，与未来正式应用隔离。

源码采用 [GPL-3.0-only](LICENSE)，依赖与研究来源见 [第三方声明](THIRD_PARTY_NOTICES.md)。本版未复用第三方 Mobile Codex 源码或 Termux 运行时二进制。
