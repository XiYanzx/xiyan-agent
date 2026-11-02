# 本地运行时启动探针

这是独立、无互联网权限的开发模块，包名 `io.xiyan.agent.runtimeprobe`。它不改变已发布的 P0 控制测试包，也不提供账号登录、模型推理或自主操作。

`python tools/runtime/prepare_runtime_probe.py` 从 OpenAI 官方 `rust-v0.166.0` Release 下载两个固定 SHA256 的 Linux musl app-server 包，只提取原始 app-server 文件至被 Git 忽略的 `src/main/jniLibs/`。APK 使用安装后的 nativeLibraryDir 执行原始 ELF，启用 legacy 提取并保留原始字节；清单中的 SHA256 会在执行前复核。**Linux 发行文件不等于 Android 受支持发行版，必须以实际设备结果为准。**

```powershell
python tools\runtime\prepare_runtime_probe.py
.\gradlew.bat --no-daemon :runtime-probe:lintDebug :runtime-probe:assembleDebug
```

启动后按“核对哈希并运行探针”。每次创建全新的私有 HOME/CODEX_HOME，子进程环境不继承账号信息；仅发出 truthful clientInfo、`explicitGatewayOauth=true` 的 initialize，然后 initialized，保存有界 stdout/stderr 和退出结果。没有 INTERNET 权限，不会注册、登录或读取桌面凭据。停止、离开页面和超时会终止本次自有进程。

`native_initialize_passed` 只证明当前 ABI/设备的进程启动与该 RPC，不能证明 V8、helpers、MCP、认证、推理、ARM64 或长时间任务通过。调试构建可通过 `adb -s <明确的测试设备> shell run-as io.xiyan.agent.runtimeprobe cat files/runtime-probe-last.json` 获取本机证据。

NDK/Bionic 源码构建路线与本模块的官方 Linux 二进制兼容性实验分开记录。完整 Codex/V8 源码构建仍须遵循固定 GN 图的 Linux 主机要求。所有编译与测试在用户本地完成，禁止 GitHub Actions 构建。
