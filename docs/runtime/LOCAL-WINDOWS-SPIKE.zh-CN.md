# 本地 Windows 运行时预检

2026-11-02 UTC。用户已将执行环境改为本地 Windows；历史 Cloud 的网络拒绝记录不再代表本机网络状态。本次没有修改系统网络或安全设置。

## 已实际完成

- 通过 Google 官方 `sdkmanager` 安装 Windows NDK r28c / `28.2.13676358`。本机已有 JDK 17、Rust 1.98.1 和 Visual Studio 2022 Build Tools/MSVC `14.40.33807`。
- 固定官方 Codex `rust-v0.166.0`，检出 commit `6ff1e1ca3b30fc8b914720681fcf5fe62e95f856`；原始 Cargo.lock SHA256 为 `22bd51f50864ce40f6686f3350c0547fdd50a8213240f54435ed394f68bf08e6`，与锁文件一致。Windows 超长文件名仅通过本次 Git 命令的 `core.longpaths=true` 处理；未改系统设置。
- 检出官方锁定的 V8 依赖 commit `350c5a859a9b3c84ab8701b3ad51e3b7699b30c0`，审阅 Android 分支；未执行该依赖的下载/编译脚本或第三方 Termux 二进制。
- 本机对 `https://chromium.googlesource.com/chromium/src/buildtools.git` 的一次 `git ls-remote ... HEAD` 成功，exit 0，返回 `d0d4c192e3c718016b3a0c5d44ccec632bccfe96`。这只证明该公开元数据请求可达，不代表所有子模块或构建依赖均已下载。
- 本机 NDK 编译既有 `tools/runtime/android_smoke.c`，exit 0；`llvm-readelf` 检查 exit 0。输出 6288 bytes，SHA256 `facd54348bc46a08c4fcd6b16ace8967c049a0d413898ebb1bf42a3f1443938f`。

ELF 为 AArch64、ET_DYN/PIE，解释器 `/system/bin/linker64`；四个 LOAD 段对齐 `0x4000`，段 offset 与 virtual address 模 16KB 一致。**该 ARM64 文件未在设备执行，这仅验证本机 NDK/Bionic 交叉生成能力。** 本地测试模拟器是 x86_64，不能替代 ARM64 运行证据。

复现编译的 PowerShell 命令如下，`ANDROID_NDK_HOME` 指向本机官方 Windows NDK：

```powershell
$toolchainRoot = "$env:ANDROID_NDK_HOME\toolchains\llvm\prebuilt\windows-x86_64"
& "$toolchainRoot\bin\clang.exe" --target=aarch64-linux-android29 "--sysroot=$toolchainRoot\sysroot" -fPIE -pie '-Wl,-z,max-page-size=16384' '-Wl,-z,common-page-size=16384' tools\runtime\android_smoke.c -o build\android-smoke
& "$toolchainRoot\bin\llvm-readelf.exe" -h -l -d build\android-smoke
```

## 当前技术阻碍

固定 V8 依赖的 `build.rs:585–634` 在 Android 交叉分支明确使用 `prebuilt/linux-x86_64`，下载 `android-ndk-r26c-linux.zip` 并调用 `unzip`。仓库现有 `tools/runtime/build_android.sh` 同样使用 Linux 主机路径，不能直接作为已验证的 Windows 原生构建入口。该判断来自固定源码审阅，**本次未发起完整 Windows Cargo/V8 编译，不能声称得到了编译错误或兼容结论**。

本机 `wsl.exe --status` 实际 exit 50，未输出详情；未确认可用的本地 Linux 执行环境。没有安装或启用 WSL、改变 Windows 功能、重启系统或将任务移回 Cloud。

| Action | Target | Reason | 状态 |
| --- | --- | --- | --- |
| 完整 Android app-server / helper 编译 | 固定官方 Codex + V8 | Windows 主机入口仍需适配；固定 Android 分支依赖 Linux 主机 NDK；可用本地 Linux 环境未确认 | pending 技术前置；不是本机网络或自动审批拒绝 |
| APK 内 ARM64 冷启动、initialize、helper/MCP | Android ARM64 | 尚无完整 Codex Android 产物，也没有连接的目标真机 | pending |
| 官方自有身份登录、账户模型目录及真实回合 | 手机上的完整运行时 | 启动前置未通过；未注册、读取或迁移桌面凭据 | pending |

本地 P0 Java APK 的构建、35 项测试、lint、签名和 Release 属于独立已验证范围，见 [Windows 构建报告](../testing/local-windows-2026-11-02.md)。后续需要受控地适配 Windows/V8 构建入口，或准备经用户授权的本地 Linux 构建环境，再验证完整运行时；不能以本次 NDK smoke 冒充该门槛通过。
