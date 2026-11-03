# Codex Cloud Android 模拟器验证记录

本轮证实探索 APK 可以在云端 Android 15 模拟器安装，系统 ANR 恢复后可以显示首页、进入测试台、拒绝未授权观察，并通过系统界面连接无障碍服务。**本轮不构成 P0 验收，也不证明截图、手势或真机控制通过。** 后续截图隐私及回调修复需要对新构建重新验证。

## 被测构建与平台

- 时间：2026-11-02 03:09–03:34 UTC。所有执行均在 Codex Cloud。
- 被测历史候选源码：`6e43c9e0fd361b9c192307198138b218234f81ee`；版本 `0.1.0-p0`；应用 ID `io.xiyan.agent.p0`。
- APK SHA256：`83f1d4ed6de9a047d6ad0c4bfddcfe8bc966d5974cc3e11371e603e49764f43e`，190265 bytes。此构建随后进入修复流程，不是最终验收构建。
- Android Emulator `37.2.13`，官方 `system-images;android-35;default;x86_64` r2，Android 15/API35，720×1280、320dpi、4096 B 系统页。
- 指纹：`Android/sdk_phone64_x86_64/emu64x:15/AE3A.240806.019/12368160:userdebug/test-keys`。
- 宿主没有 `/dev/kvm`，`-accel-check` 返回 3；使用 TCG 与软件图形，SELinux 保持 `Enforcing`。这是 x86_64 云模拟器，不是 iQOO 12 Pro、ARM64 真机或游戏测试。

## 实测结果

| 验证项 | 结果 | 原始证据 |
| --- | --- | --- |
| 系统启动 | 03:09:43 启动，03:16:53 `sys.boot_completed=1`，约 7 分 11 秒 | [启动轮询](cloud-emulator-evidence/boot-monitor.log) |
| 指定候选安装 | `Success`，退出码 0，约 27 秒 | [命令与时间](cloud-emulator-evidence/install.json)、[输出](cloud-emulator-evidence/install.txt) |
| 清测试应用数据后冷启动 | shell 退出码 0，但 `Status: timeout`、WaitTime 25905ms；屏幕显示系统 ANR，不能判为冷启动通过 | [输出](cloud-emulator-evidence/cold-launch.txt)、[系统 ANR 截图](cloud-emulator-evidence/system-ui-anr.png) |
| 一次系统 Wait 后恢复 | 再发 intent 返回 `Status: ok`，实际首页显示中文阶段说明；这不是新的冷启动测试 | [输出](cloud-emulator-evidence/recovered-launch.txt)、[首页](cloud-emulator-evidence/home.png) |
| 首页进入测试台 | 正常点击首页入口后出现测试台 | [未授权测试台](cloud-emulator-evidence/permission-denied.png) |
| 未授权观察 | 点击“读取当前窗口元数据”后提示服务未连接，目标计数保持 0 | [UI 原文](cloud-emulator-evidence/permission-denied-ui.xml.txt)、[截图](cloud-emulator-evidence/permission-denied.png) |
| 系统授权与绑定 | 在模拟器 Settings 中点击服务开关，再点击系统 `ALLOW`；随后系统记录 enabled/bound，首页显示已连接 | [系统确认界面](cloud-emulator-evidence/system-permission-ui.xml.txt)、[确认动作](cloud-emulator-evidence/allow-service.json)、[绑定状态](cloud-emulator-evidence/accessibility-binding.txt)、[首页状态](cloud-emulator-evidence/service-connected.png) |
| 停止入口反馈 | 点击测试台“紧急停止”，显示后续动作授权撤销；仅覆盖入口反馈 | [截图](cloud-emulator-evidence/stop-feedback.png) |
| 单步点击/滑动结果复核 | 未执行；没有应用手势派发成功证据 | 待新构建继续 |
| 应用截图与预览 | 未执行；未点击应用截图按钮；不能以 ADB 截图代替应用截图证据 | 待隐私及迟到回调修复后重测 |

停止入口反馈不证明取消竞态、旧 generation 零派发或已派发手势可撤回。OAuth、账户模型目录、模型推理、Codex runtime、API29 截图、分屏/遮挡、真实游戏和 30/60/120 分钟稳定性均未执行。

## 测试夹具限制

首次在系统未完成启动时安装探索包，Android `StorageManagerService` 内部服务未就绪而抛出 NPE，退出码 1；系统启动完成后安装成功。该早期失败保留在工作区原始记录中，没有作为应用缺陷结论。

系统曾出现 `System UI isn't responding`，只点一次正常的 `Wait` 后恢复。TCG 模式的启动、页面切换、截图和 UI dump 明显缓慢；本轮不能建立性能或稳定性承诺。Android 官方说明 [`-accel off`](https://developer.android.com/studio/run/emulator-commandline) 纯软件模式不受支持且很慢，需要显式的 `ANDROID_I_WANT_MY_TCG=yes`。

`uiautomator dump` 使用 UiAutomation，会影响被测无障碍服务。本轮 dump 期间首页曾显示未连接；断开 UiAutomation 后，通过不连接 UiAutomation 的 `dumpsys accessibility` 与 `adb screencap` 确认 enabled/bound 和已连接。Android 的 [UiAutomation 文档](https://developer.android.com/reference/android/app/UiAutomation#FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)说明默认会抑制其他无障碍服务。后续测试应使用保留服务的 instrumentation 配置，或使用原生截图和只读系统状态，避免夹具改变被测状态。

本轮没有使用 `settings put secure`、shell 权限身份或 instrumentation 强行开启服务。权限通过模拟器系统 UI 点击授予。`HOME` 环境变量保持不变；官方 adb 忽略工作区用户目录变量而尝试写只读 home 后，自动审批允许仅创建 `/home/agent/.android -> /workspace/.android` 的可逆链接。网络策略与 Android 安全模式未更改。

## 复核与继续

仓库保存选定的截图、日志与 [SHA256SUMS](cloud-emulator-evidence/SHA256SUMS)。完整原始记录在云工作区 `/workspace/emulator-evidence`，压缩证据为 `/workspace/emulator-evidence/cloud-emulator-evidence.zip`；SDK 下载文件和 APK 不包含在压缩证据内。

[`tools/device/emulator_evidence.py`](../../tools/device/emulator_evidence.py) 强制显式模拟器序列号，记录命令、UTC、耗时、退出码、超时以及原始 stdout/stderr。退出码为 0 仍须检查命令的实际状态，例如本轮 `am start -W` 返回了 `Status: timeout`。

```bash
python3 tools/device/emulator_evidence.py \
  --serial emulator-5554 --output /workspace/emulator-evidence \
  --name example-screen --stdout-extension png \
  -- exec-out screencap -p
```

收尾时模拟器保留运行，服务仅在这台测试夹具中启用，测试台已按停止按钮，没有派发过应用手势。修复后须记录新源码 commit 与 APK 哈希，再重新执行安装、启动、单步点击/滑动、窗口截图、预览清理、权限撤销与停止竞态验证。
