原始报告与所有相对证据文件见同目录 `CLOUD-EMULATOR-EVIDENCE.zip`，本平铺版本仅调整链接展示。

# 最终 APK 云端 Android 模拟器有限验证

本轮最终 APK 的安装、首页和测试台导航、无障碍服务连接、一次真实单步点击、测试窗口截图预览、清除预览及停止时清除预览均取得实测证据。**这些结果只覆盖本应用测试台，不能判定 P0 整体、隐私隔离竞态、真机或自主任务验收通过。**

## 构建与测试范围

- 源码 commit：`a2ac08dafdd33ca2dd8d408e10a78b89472cec74`。
- APK：`xiyan-agent-0.1.0-p0-debug.apk`，195134 bytes，应用 ID `io.xiyan.agent.p0`。
- APK SHA256：`827d1bd801d1c6de5681671ef30cc6a47908196c0310afe13709ca373091bdd2`。
- 安装后的 `base.apk` 再次计算 SHA256，与上述固定 APK 一致：安装路径（证据包内 `33-installed-path.txt`）、设备端校验结果（证据包内 `34-installed-apk-sha256.txt`）。
- 全部操作发生在 Codex Cloud。设备为既有的官方 Android Emulator 37.2.13，Android 15/API35 default x86_64 r2，720×1280/320dpi，4096 B 页，SELinux Enforcing；无 KVM，使用 TCG/软件图形。不是 iQOO 或 ARM64 真机。
- 时间以每条命令的 UTC JSON 为准，本轮从 `2026-11-02T03:48:07Z` 开始，在 15 分钟预算内结束。平台原文见 32-platform.txt（证据包内 `32-platform.txt`），汇总见 result-summary.json（证据包内 `result-summary.json`）。
- 复用前一轮在同一模拟器通过系统 UI 批准的服务；没有再次授予权限，也没有使用 UiAutomation、`settings put secure` 或 shell 权限身份开启服务。未修改仓库，结束时 Git clean，HEAD 保持上述 commit。

## 已完成验证

| 项目 | 观察与结论 | 证据 |
| --- | --- | --- |
| 安装 | `Success`，exit 0，约 12.3 秒 | 01-install.txt（证据包内 `01-install.txt`）、命令记录（证据包内 `01-install.json`） |
| 启动 | `Status: ok`，`LaunchState: WARM`，TotalTime 8303ms；不等同新的冷启动测试 | 02-launch.txt（证据包内 `02-launch.txt`）、首页（证据包内 `04-home.png`） |
| 服务连接与测试台 | 更新后服务 enabled/bound，首页显示已连接；正常点击入口进入测试台 | 更新后绑定（证据包内 `03-binding-after-install.txt`）、首页连接状态（证据包内 `07-controls.png`）、测试台（证据包内 `09-testpad-initial.png`）、结束时绑定（证据包内 `31-final-binding.txt`） |
| 真实单步点击 | 初始目标计数 0；仅点“授权并验证一次点击”，由应用派发手势后计数恰好变为 1，并显示/记录“点击复核通过” | 之前（证据包内 `11-tap-before.png`）、请求按钮点击（证据包内 `12-request-single-tap.json`）、之后（证据包内 `13-tap-after.png`）、顶部结果（证据包内 `16-tap-result-top.png`）、应用日志（证据包内 `14-diagnostics-after-tap.txt`） |
| 测试窗口截图 | 正常点“抓取测试窗口截图（Android 14+）”；应用成功回调并显示预览，日志记录截图已保留在内存 | 请求（证据包内 `17-request-window-capture.json`）、成功文案（证据包内 `18-window-capture-top.png`）、完整可见预览（证据包内 `21-window-preview.png`）、日志（证据包内 `19-diagnostics-after-capture.txt`） |
| 清除截图 | 预览显示后点“清除截图”，同一页面上的预览消失 | 清除之前（证据包内 `21-window-preview.png`）、清除动作（证据包内 `22-clear-preview.json`）、清除之后（证据包内 `23-preview-cleared.png`） |
| 停止时清理预览 | 再次截图后出现新的预览；按“紧急停止”后预览消失，页面和日志提示后续动作授权撤销；目标计数仍为 1 | 第二次预览（证据包内 `25-preview-before-stop.png`）、停止动作（证据包内 `26-stop-with-preview.json`）、预览消失（证据包内 `27-stop-clears-preview.png`）、停止反馈（证据包内 `29-stop-feedback.png`）、最终日志（证据包内 `30-final-diagnostics.txt`） |

所有编号命令记录均保存实际 argv、UTC、耗时、exit code、是否超时及原始 stdout/stderr。导航用的 ADB 输入与应用手势已分开：本轮直接输入落在验证按钮，目标上的单次触点由应用服务产生，前后计数与应用复核日志共同支持通过结论。

诊断数据通过 debug 测试包 `run-as` 只读获取，仅读取应用生成的 `p0-diagnostics` 事件，没有读取屏幕内容或凭据。因为原地更新保留测试数据，原始 XML 中有旧候选的历史事件；events-this-run.json（证据包内 `events-this-run.json`）按本轮开始时间筛选，避免将旧事件作为最终构建证据。设备时钟与宿主可能有小量偏差，操作耗时采用宿主命令 JSON。

## 尚未验证

本轮是正常单窗口场景。虽然最终源码的 API34+ 实现使用窗口截图，本轮没有可靠建立分屏或 overlay 夹具，**不能声称像素隔离通过**。截图预览能显示和移除，也不能证明迟到回调不会重显；未故意制造 pending screenshot 的清除/停止竞态。

停止时没有正在执行的应用手势，未证明 in-flight 取消竞态、旧 generation 零派发或系统已接收的手势可撤回。没有进行滑动、长按、文字输入、最终构建的权限拒绝/撤销、新冷启动、Android10–13截图、真机游戏、OAuth、模型目录/推理、Codex runtime 或 30/60/120 分钟稳定性验证。

本轮没有遇到操作审批拒绝或结果不确定的安装/点击/截图操作。上述未测项是验收覆盖范围，不应标为已通过，也不阻止将本证据作为 P0 技术验证包的有限实验记录。

## 交付与状态

`SHA256SUMS` 覆盖压缩包内全部报告及原始证据。ZIP 不重复包含 APK；APK 哈希、设备安装副本哈希和大小保存在 `build.json` 与设备端原始校验输出中。

结束时模拟器 `emulator-5554` 保持运行，服务仅在这台既有测试夹具中启用，测试台已按停止按钮且预览清空。仓库未改动、未 commit/push。
