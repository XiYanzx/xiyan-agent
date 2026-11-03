# P0 开发与验证状态

本轮当前状态见[本地 Work 接手报告](testing/work-local-2026-11-02.md)：`0.1.0-p0.2` 已加入新版界面、SIWC 账号基础与手动文本请求代码，真实账号验证等待用户官方授权；97 项 JVM 集成测试已通过。独立原生探针本轮在 x86_64 模拟器初始化、V8 与 MCP 均退出 159。下文保留早期 P0 的开发记录，最终发布范围以新报告和对应 Release 的验证附件为准。

2026-11-02 UTC。按最新授权转为本地 Windows 构建与测试；以下 Cloud 记录保留历史范围。开发按 GPT-6.1 Sol / Ultra 执行。**P0 尚未通过验收，P1–P6 未开始验收；当前 APK 是本机技术验证工具，尚无自主 Agent 能力。**

最新的本地源码、APK、35 项测试、Android15 模拟器和公开 Release 结果见 [Windows 本地复验](testing/local-windows-2026-11-02.md)。[本地运行时预检](runtime/LOCAL-WINDOWS-SPIKE.zh-CN.md)单列 NDK smoke 与 Windows/V8 主机构建前置，不沿用 Cloud 网络故障作为本机阻碍。

## 已有源码与构建证据

- 独立 Android 原生 Java 工程，min29 / target35，Java17 源码；P0 包名 `io.xiyan.agent.p0`，调试测试签名。
- 中文界面、模式偏好、设备 API/ABI/页大小、服务连接状态、停止入口和不含屏幕内容的有界本机诊断。
- 测试台内的元数据观察、API34+ 窗口内存截图、单次显式授权手势、实际目标计数/滚动复核、短手势超时隔离和后续派发撤销。实际设备表现须以测试记录为准。
- 独立纯 Java 动作协议安全门和动态模型元数据解析器。**23 项核心单元测试通过**（15 动作、8 模型）；控制器目前使用独立 P0 guard，核心测试不能代表 Android 派发器通过。
- 截图审查修复新增12项应用单元测试，实际接线的窗口策略与截图交付门各6项；中间验证全部通过，lint为 `No issues found`。这不代替设备像素隔离与真实系统回调测试。
- 云端成功执行过 `:core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease`。首个完整构建的 Android unit task 是 `NO-SOURCE`，不能与新增测试混计。最终交付重新执行同一组检查，并强校验 APK 内来源、manifest 与两模块测试来源一致。Release variant 尚无生产签名；可安装文件来自 debug variant。

首个完整验证的源码 commit 为 `6e43c9e0fd361b9c192307198138b218234f81ee`。原始 Gradle、JUnit 与 lint 证据见 [build-evidence](testing/build-evidence/)。随后构建策略文档更新与工作流删除按[最新需求补充](REQUIREMENTS-ADDENDUM.zh-CN.md)执行；最终交付 APK 的精确 commit、签名和 SHA256 以下载旁的 `BUILD-INFO.json`、`APK-SIGNATURE.txt` 和 `SHA256SUMS.txt` 为准。

## P0 门槛

| 门槛 | 真实状态 | 下一项证据 |
| --- | --- | --- |
| 官方 Android app-server/code-mode-host | 原始构建 exit101，V8 子模块被网络策略拒绝；本地诊断按指示中断；未产 Codex Android ELF | 支持配置实际生效后复测原始路线，再验证 Android/V8、APK内启动与 initialize |
| 交叉工具链 | NDK/Bionic C smoke 编译/readelf exit0，ARM64 PIE、16KB LOAD 对齐 | 手机上实际启动；此 smoke 不代替 Codex 验证 |
| 官方 Linux ARM64 候选 | 官方包来源/散列及 ELF 静态检查完成，未执行 | 如采用该路线，需独立受控 APK 与 ARM64 手机实际启动证据 |
| 官方订阅授权 | 未注册客户端、未登录、未刷新/退出 | 手机上自有身份、系统浏览器、loopback、PKCE/state/nonce/JWKS 与 scope 验证 |
| 实际模型与思考选项 | 仅有元数据解析测试，无账户目录 | 同 token 真实目录、app-server metadata 及真实成功回合 |
| 文本/图像/本地工具回合 | 未执行 | 真实 Android runtime、官方授权、helper/MCP 及回合完成记录 |
| 数字额度 | 未读取 | 正式支持来源的读数；未提供时保持未知 |
| Android控制 | 有P0源码，云端模拟器记录单列；iQOO未测 | API29/35、真实观察/单步复核、撤权/旋转/停止/旧帧拒绝 |
| Android10纯视觉 | MediaProjection 适配未实现 | 用户授权的真实捕获及撤销测试 |
| 游戏与30/60/120分钟 | 未实现、未测试 | P0通过后按P1–P5逐阶段推进 |

详细 runtime 版本、每条命令、退出码、散列、停止核对及后续域名见 [运行时实测报告](runtime/P0-RUNTIME-SPIKE.zh-CN.md)。不能将网络拒绝称为 Android 编译不兼容，也不能将 Linux 静态链接或 NDK smoke 称为手机运行成功。

[云模拟器记录](testing/cloud-emulator-2026-11-02.md)只对应报告中的历史 APK hash：安装、系统 ANR 恢复后的首页、未授权拒绝、系统 UI 授权/绑定与停止入口反馈有证据；手势和应用截图没有通过记录。审查修复后的新 APK 不沿用旧包的运行结论，后续补测单列。

## GitHub与后续设备操作

源码分支 `feature/p0-runtime`，draft [PR #1](https://github.com/XiYanzx/xiyan-agent/pull/1)；未批准或合并。早先两个 GitHub Actions 准备失败记录均已结束，工作流已删除。**编译、测试和打包改在用户本地 Windows，GitHub只保存源码和Release下载。**

Release 仅可发布明确说明范围的 P0 prerelease。签名使用本次调试测试证书，未来正式密钥尚未配置。不会自动注册账户或索取明文凭据。手机用户后续需要按[安装指南](install.zh-CN.md)开启系统无障碍权限，完成真实截图、手势与撤销测试；账户接入及真机长周期测试另需对应实际授权和证据。
