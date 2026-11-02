# Windows 本地构建与 P0 复验

2026-11-02 UTC。用户最新指示为本地电脑构建、测试并发布 GitHub；GitHub 构建禁令继续有效。

## 构建与公开下载

- 本地正常用户检出：`<TASK_ROOT>\xiyan-agent-local`；精确本机路径在私人交付记录中提供。
- APK 的真实 buildCommit：`1975523857d97fbb83923d15b78975621035045c`。该检出构建和打包时均为干净 HEAD。
- [P0 prerelease](https://github.com/XiYanzx/xiyan-agent/releases/tag/v0.1.0-p0-local.1) 已公开，tag 指向上述源码。PR #1 保持 draft，没有批准或合并。
- APK：126842 bytes；SHA256 `391476eaaf14f7b89067a80bc65245e360a98ab488dcd43385f357ff8a19a805`。
- 从公开 Release 重新下载全部 13 个首发附件，逐字节散列与本地产物一致；安装到模拟器后的 base.apk SHA256 也一致。

工具链为现有 Oracle JDK `17.0.12+8-LTS-286`、Gradle `8.11.1`、AGP `8.9.2`、SDK 35、Build Tools `35.0.0`。缺失 SDK 从 Google 官方源安装至本任务目录，Gradle 发行包按 wrapper 固定 SHA256 校验；没有修改全局网络、安全或 Git 信任配置。

```powershell
.\gradlew.bat --no-daemon --no-parallel --console=plain :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
python tools\package_p0.py
```

Gradle exit 0，`BUILD SUCCESSFUL in 1m 28s`，96 个 actionable tasks（76 executed、20 from cache）。核心 23 + 应用 12 = **35 项 JVM 单元测试通过，0 failure/error/skipped**。lint 为 `No issues found.`。release variant 无生产签名；公开安装包为调试测试签名 debug variant。

apksigner、`zipalign -c -P 16 -v 4` 和来源验证器均 exit 0。APK 内嵌清单、实际 DEX BuildConfig、binary manifest、干净 HEAD 和散列绑定 JUnit 来源一致。证书 SHA256 `cf794800ce16fa0e869fdd9f07f20a285e125abd21a18b1e792dc5318c16ef28`。私钥只在本地私有目录，未进入仓库或发布资产。

首轮沙箱内构建因 Java 访问依赖 JAR/签名锁文件失败；审批后的正常用户对沙箱账号拥有的仓库又被 Git 所有权保护拒绝。最终使用正常用户新建的干净检出成功完成上述命令，没有通过全局 safe.directory 例外关闭保护。本机 Git 凭据存储不可用，源码通过已连接 GitHub 工具上传；远端树 SHA 与本地源码树逐项一致后，再从远端检出实际 buildCommit 构建。

## 本机 Android 15 模拟器

Google 官方 `system-images;android-35;default;x86_64`，WHPX 可用，任务专用 AVD `XiyanP0_API35`、端口 5580；1080×2400、API35、x86_64、4096-byte 页。系统 fingerprint 为 `Android/sdk_phone64_x86_64/emu64x:15/AE3A.240806.019/12368160:userdebug/test-keys`。这是本地模拟器，**不是 iQOO 或 ARM64 真机**。

| 检查 | 实际结果 |
| --- | --- |
| 安装公开下载 APK | Streamed Install Success；安装后 APK hash 与 Release 一致 |
| 冷启动 | Status ok / COLD，TotalTime 789ms |
| 未授权时点击验证 | 明确显示服务未连接，目标计数仍为 0 |
| 系统授权 | 经 Settings 的 Use Xiyan Agent 和 ALLOW 对话框授权，仅操作空白测试模拟器 |
| 窗口元数据 | 观察 #1，1080×2400，32 节点、8 可点击节点 |
| 应用派发单次点击 | 目标实际计数 0→1，显示“点击复核通过” |
| API35 窗口截图 | 实际显示当前测试窗口的缩略预览；图像只留应用内存 |
| 清除 | 预览控件与可见图像消失；原有状态文字未自动清除，不能把文字当成预览仍在 |
| 紧急停止 | 已有预览被清除，显示后续动作授权撤销提示 |
| 显式重新授权单次上滑 | 测试区域内容实际滚动，显示“滑动复核通过：区域实际向下滚动” |
| 系统撤权后再次点击验证 | 经 TURN OFF 撤权；服务未连接、目标计数仍为 1，没有增加 |

取证每条 ADB 命令均指定 `emulator-5580`，分别保留 argv、UTC、耗时、退出码、XML 与截图。截图和 UI XML 是相继采集的不同时间点；初次 capture 的屏幕图早于异步回调，后续 `capture-visible.png` 才是已确认可见预览，不能混用。

原始证据、下载复核清单和本机 NDK smoke 在 Release 的 `LOCAL-SUPPLEMENT.zip` 中，附独立 `.sha256`。公开文本中的任务根路径已脱敏，原始本地记录保留。图片只含新建测试模拟器与本应用测试画面，不含用户真实屏幕或凭据。

## 后续工具修复与未通过门槛

Windows 原生运行时记录器使用私有 Job Object，在子进程启动前归属该 job；超时/中断时清理并确认其进程数归零。8 项独立 Python 专项测试通过，包含真实 Windows 子孙进程超时终止、正常/非零退出、启动失败和清理失败证据。此修复为首发后的开发工具变更，未计入 APK 的 35 项 JVM 测试，不改变已发布 APK 的 buildCommit。Linux 路径在本机未执行验证；硬 OS 终止记录器时不能保证最后证据写盘。

未验证分屏/覆盖层像素隔离、实际迟到系统回调竞态、in-flight 停止零派发、截图到手势坐标、Android10 MediaProjection 或 iQOO 真机。Codex app-server/V8、官方登录、实时模型目录/推理、游戏和 30/60/120 分钟任务均未实现或未验收，P0 尚未通过。完整运行时的本机技术前置见 [Windows runtime 预检](../runtime/LOCAL-WINDOWS-SPIKE.zh-CN.md)。

GitHub Actions 最后核对仍只有此前 2 个已结束的失败历史 run；本次没有创建、运行或重新触发工作流。P0 首发时的验证文件保留当时未做设备测试的历史范围，后补的本机模拟器结果以本报告为准。
