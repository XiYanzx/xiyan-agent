# Xiyan Agent P0 技术验证版

此 APK 是手机控制与运行环境的技术验证工具，**尚无自主 Agent 能力，P0 尚未验收**。

提供中文深色界面、无障碍权限入口、本页窗口元数据观察、Android 14+ 测试窗口截图预览、应用内单步点击与滑动复核、紧急停止后续派发、本机诊断 JSON 导出。截图只留内存，APK 没有互联网权限。

窗口截图在 API29–33 明确禁用，不回退整屏。清除、停止或退出会撤销待返回的截图，迟到图像被释放。分屏及覆盖层的设备像素隔离仍待验证；本版图像只作预览，不直接映射动作坐标。

纯 Java 核心具有动作安全门与动态模型元数据解析器及单元测试；当前 Android 控制器使用独立 P0 guard，尚未整合该核心。测试不代表真实账户目录、手机控制或长周期任务已通过。

未内嵌 Codex app-server/code-mode-host，未接入账号登录、订阅额度、模型推理、通用任务、游戏策略与恢复监督器。Android 10 MediaProjection 截图、iQOO 12 Pro/Android 15 真机验收及 30/60/120 分钟测试均待执行。运行时路线证据见源码 `docs/runtime/`，最新测试证据见 `docs/testing/`。

安装包使用 **Android 调试测试证书**，包名 `io.xiyan.agent.p0`，与未来正式应用隔离。此证书仅验证本次 APK 完整性，并非正式发行身份；不同云端构建的调试证书可能不同，覆盖安装失败时需要卸载旧 P0。正式签名密钥尚未配置。

从本 Release 下载 APK、`SHA256SUMS.txt`、`APK-SIGNATURE.txt` 和 `BUILD-INFO.json`。`sha256sum -c SHA256SUMS.txt` 可核对下载文件。GitHub 的 Source code 压缩包是本 tag 的对应源码；构建方式见 `docs/build.zh-CN.md`。请按 `docs/install.zh-CN.md` 在手机手动授权和记录验证结果，勿将模拟器截图当作真机证据。
