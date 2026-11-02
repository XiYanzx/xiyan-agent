# Xiyan Agent

独立 Android 双模式智能体项目：通用手机操作与游戏操作。目标 Android 10+、ARM64，优先适配 iQOO 12 Pro / Android 15；按用户最新指示在本地 Windows 构建和测试。

**当前为 P0 技术验证，尚无自主 Agent 能力，P0 未验收。** 已建立可安装验证工具和独立核心模块；没有接入账号、订阅额度或模型推理，没有完成 Galaxy、长周期任务与真机验收。每阶段验收后再进入下一阶段。

最新要求：**编译、测试、打包在用户本地电脑；不在 GitHub 上构建。** GitHub 保存源码并分发本地构建的 Release。详见[最新需求补充](docs/REQUIREMENTS-ADDENDUM.zh-CN.md)，该要求覆盖原任务书的自动 Actions 构建条款及此前 Cloud-only 要求。

P0 验证工具提供中文深色界面、权限状态、真实设备信息、测试台窗口元数据、Android 14+ 窗口内存截图、仅限本页的单步点击/滑动复核、停止入口与本机诊断导出。APK 不申请互联网权限，未打包 Codex/V8。核心动作门和动态模型目录解析有独立测试；尚未整合为自主执行引擎。

| 材料 | 内容 |
| --- | --- |
| [完整任务书](docs/REQUIREMENTS.zh-CN.md) | 用户要求及 P0–P6 验收 |
| [P0 研究报告](docs/P0-feasibility.md) | 官方账号、模型、运行时与手机控制路线 |
| [P0 开发状态](docs/P0-STATUS.zh-CN.md) | 已实现项、构建证据和未过门槛 |
| [Windows 本地复验](docs/testing/local-windows-2026-11-02.md) | 本地 APK、35 项测试、模拟器与公开下载核对 |
| [运行时实测](docs/runtime/P0-RUNTIME-SPIKE.zh-CN.md) | 实际命令、退出码、阻碍与原始证据 |
| [架构](docs/architecture-p0.zh-CN.md) | 当前模块及实现边界 |
| [模型兼容](docs/model-compatibility.zh-CN.md) | 动态目录解析与未接入项 |
| [安装和手机验证](docs/install.zh-CN.md) | 手动权限、测试台与报告 |
| [构建指南](docs/build.zh-CN.md) | SDK/JDK、本地检查、签名与校验 |
| [变更记录](CHANGELOG.md) | 版本范围 |

技术验证 APK 通过 [GitHub Releases](https://github.com/XiYanzx/xiyan-agent/releases) 的 prerelease 提供。下载时核对该 Release 的 `BUILD-INFO.json`、签名和 SHA256；正式 P6 发行必须满足全部阶段和真机门槛。P0 包名 `io.xiyan.agent.p0`，使用调试测试证书，与未来正式应用隔离。

源码采用 [GPL-3.0-only](LICENSE)，依赖与研究来源见 [第三方声明](THIRD_PARTY_NOTICES.md)。本版未复用第三方 Mobile Codex 源码或 Termux 运行时二进制。
