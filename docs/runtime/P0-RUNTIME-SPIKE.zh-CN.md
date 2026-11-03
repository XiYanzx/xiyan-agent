# P0 Android ARM64 runtime 实测记录

日期：2026-11-02 UTC。执行位置：Codex Cloud `/workspace`，x86_64 Linux；目标 Android 10+/ARM64、iQOO 12 Pro Android 15。全部构建和下载均在 Cloud 内完成。

**本次没有构建出 Codex Android 可执行文件，也没有在手机或 Android 模拟器运行 app-server。P0 A 仍未通过。** 已实际执行完整 app-server 与 code-mode-host Android 构建命令、验证 NDK/Bionic 交叉工具链、检查官方 Linux ARM64 发行包及 V8 资产，留下失败和中断证据。

## 固定来源与工具链

| 项目 | 实际版本/值 |
| --- | --- |
| 官方源码 | `https://github.com/openai/codex.git`，`rust-v0.166.0` |
| annotated tag object | `593389380740f7b4762771573c447ccfdc59742f` |
| peeled commit | `6ff1e1ca3b30fc8b914720681fcf5fe62e95f856` |
| 原始 Cargo.lock SHA256 | `22bd51f50864ce40f6686f3350c0547fdd50a8213240f54435ed394f68bf08e6` |
| Rust / Cargo | `rustc 1.95.0 (59807616e 2026-04-14)` / `cargo 1.95.0 (f2d3ce0bd 2026-03-21)` |
| rustup | `1.29.2 (d8c92c676 2026-10-21)` |
| Android NDK | 官方 r28c / `28.2.13676358`，Clang `19.0.1` |
| Android target | `aarch64-linux-android`，API 29 |
| 链接设置 | `max-page-size=16384`、`common-page-size=16384` |
| Host 页大小 | 4096；没有取得目标 iQOO 实测页大小 |
| V8 | 官方 Codex 锁定 `v8 152.2.0`，git commit `350c5a859a9b3c84ab8701b3ad51e3b7699b30c0` |

源码、NDK、依赖及散列详见 [upstream-lock.json](../../tools/runtime/upstream-lock.json) 与 [source-audit.json](evidence/source-audit.json)。原始 workspace 锁包含1478个package；原始源码目录最终 `git status --short` 为空。没有执行第三方 Termux port、参考项目脚本或取消项目的代码。

NDK ZIP 实际722,261,334字节，SHA1与 Google repository XML 中该版本匹配，另记录SHA256。官方 Linux package 实际145,150,661字节并验证官方digest。上述两项合计867,411,995字节；Rust和crates/git另有下载，未做全量网络流量计量。停止时 runtime-spike、Rust和NDK目录分别约1.9G、2.1G、2.2G磁盘占用；这不是下载量。

## 实际构建结果

| 路线 | code-mode-host | app-server | 证据与含义 |
| --- | --- | --- | --- |
| 原始tag、原始lock、正常Cargo git依赖 | exit 101，31.787s | exit 101，11.757s | [原始日志](evidence/codex-code-mode-host-android.log)、[app-server日志](evidence/codex-app-server-android.log)；V8 submodule抓取失败，未进入Rust编译 |
| 相同V8 crate本地source replacement、原始lock | exit 101 | exit 101 | [vendor日志](evidence-vendored/codex-code-mode-host-android.log)、[app-server日志](evidence-vendored/codex-app-server-android.log)；`--locked`要求更新workspace内部版本 |
| 隔离worktree、相同V8 crate、本地版本归一lock | SIGTERM / -15，20.098s | SIGTERM / -15，15.045s | [编译日志](evidence-normalized/codex-code-mode-host-android.log)、[app-server日志](evidence-normalized/codex-app-server-android.log)；已进入Rust依赖编译，收到停止指示后终止，未链接、未生成Codex ELF |

每条命令的argv、cwd、起止UTC、耗时、退出/终止值在对应目录同名JSON中。原始两次Cargo命令为：

```sh
cargo build --locked --profile dev-small --target aarch64-linux-android \
  --package codex-code-mode-host --bin codex-code-mode-host
cargo build --locked --profile dev-small --target aarch64-linux-android \
  --package codex-app-server --bin codex-app-server
```

首个实际阻碍：

```text
failed to load source for dependency `v8`
failed to update submodule `buildtools`
failed to fetch submodule `buildtools` from https://chromium.googlesource.com/chromium/src/buildtools.git
[56] Failure when receiving data from the peer (CONNECT tunnel failed, response 403); class=Net (12)
```

这是 Cloud 目的地主机策略拒绝，不是 Android 编译错误，也不是自动审批审核拒绝。未关闭TLS验证、未修改代理或网络策略、未从镜像获取被拒内容。原始Cargo内置重试及第二个构建记录都在首次发现该拒绝时已结束；后续未重试被拒clone。

本地诊断使用已合法获得的V8 crate自身tracked blobs，由 `git archive` 原样复制；没有获取Chromium submodule或替代资源。[Cargo官方source replacement说明](https://doc.rust-lang.org/cargo/reference/source-replacement.html)要求替代来源保持相同源码。该诊断不含第三方Android补丁，单独标记并记录[所有文件散列](evidence-vendored/vendor-record.json)。

release tag 的 Cargo.toml 将 workspace.version 设为0.166.0，Cargo.lock却保留170个内部package版本0.0.0。隔离诊断worktree在离线metadata解析时仅把这些内部版本归一为0.166.0；外部依赖source、version、checksum及dependency列表逐项相等。见[归一记录](evidence-vendored/lock-normalization.json)和[完整差异](evidence-vendored/diagnostic-lock-delta.patch)。原始源码与lock没有变化。离线metadata随后因未缓存 `Inflector v0.11.4` 正常停止；完整编译诊断采用归一后的固定lock和 `--locked`。

随后上层指示等待网络授权、停止所有vendor路线。已停止driver和两个Cargo进程组；driver终止exit143。03:10:26 UTC核对无活动Cargo、curl、git或rustc，剩余条目均为未回收Z状态，见[停止观测](evidence-normalized/stop-observation.json)。历史JSON中中断记录的 `wrapper_exit_code=-15` 是旧记录器给Python的退出参数，不是POSIX shell退出码；可靠的直接子进程终止值为 `process_return_code=-15`。记录器现已修正后续SIGTERM映射为143，并在中断后停止后续命令；历史JSON原样保留。

## NDK/Bionic smoke

自有最小C程序 [android_smoke.c](../../tools/runtime/android_smoke.c) 真实编译exit0，[readelf检查](evidence/android-smoke-readelf.log)exit0：

- AArch64、ET_DYN/PIE；解释器 `/system/bin/linker64`。
- NEEDED `libdl.so`、`libc.so`，属于Android Bionic链接结果。
- 四个LOAD段 `p_align=0x4000`，offset与virtual address模16KB一致。
- 输出 `/workspace/runtime-spike/android-smoke`，SHA256 `d3af27b065f31ea82daf5124a1099a57e69f1df312297d0706beba78429bb276`。

完整argv与退出码见[NDK编译记录](evidence/android-ndk-smoke.json)。未执行该ARM64文件；这只证明Cloud交叉工具链可生成目标ELF，不证明APK打包、手机执行或Codex兼容。

## 官方Linux发行包与V8

详细证据见[Linux发行ELF检查](linux-release-evidence/README.zh-CN.md)。app-server、code-mode-host、exec-server均为AArch64静态ET_EXEC，无PT_INTERP/DT_NEEDED，LOAD对齐64KB；可以列为后续受控APK启动实验的候选。包内rg是依赖GNU/Linux加载器和glibc的动态PIE，完整bundle不能直接当作已兼容Android的发行物。所有Linux发行binary均未执行。

[V8资产检查](linux-release-evidence/V8-ASSETS.zh-CN.md)核对官方 `openai/codex` 的 `rusty-v8-v152.2.0` 共42个release assets，无Android命名archive/binding；固定源码两份manifest共24条与API digest一致。code-mode-runtime显式要求 `v8_enable_sandbox`，不能把另一个profile的archive或Linux archive当作Android兼容替代。源码中的Android分支本身存在，但未完成其编译。

## 后续阻碍与验证门槛

| Action | Target | Reason | 状态 |
| --- | --- | --- | --- |
| Cargo获取上游固定V8子模块 | `chromium.googlesource.com/.../buildtools.git` | 当前Cloud HTTP allowlist拒绝CONNECT403 | denied；等待受支持配置授权，不重试、不替换来源 |
| vendor后续编译 | 隔离诊断worktree | 上层要求网络授权前停止该路线 | interrupted；可核实SIGTERM，无运行结果不确定项 |
| 官方Android V8预编译配对资产 | `rusty-v8-v152.2.0` release | 该release assets无目标组合 | blocked by missing artifact；不是授权拒绝 |
| APK内app-server/helper冷启动和initialize | Android API29/35及iQOO | 无Codex Android产物；未接入设备执行通道 | pending |
| code-mode-host/MCP与文本/图像真实回合 | 真实Android运行时/官方用户授权 | 前置启动和登录未验证 | pending |

后续源码构建至少需要 `chromium.googlesource.com`（多个已声明submodule；Android分支还拉取android_platform和catapult）。从本地locked V8 build scripts静态发现的其他潜在官方下载目的地有 `chrome-infra-packages.appspot.com`（GN/Ninja）、`storage.googleapis.com`（Chromium Rust toolchain），以及已允许的 `dl.google.com`、GitHub和Rust/crates站点；这些后续地址尚未发起访问，也未验证完整依赖闭包。`tools/clang` submodule尚未取得，不能声称已穷尽Clang下载和重定向域名。API元数据使用受支持GitHub connector；若改用shell API，需另获 `api.github.com` 的网络权限。

在受支持的网络配置实际生效并核验后，优先复测原始上游路线，再处理版本归一、V8 Android源码编译与页大小/依赖。随后才是签名APK内原生文件启动、stdio initialize、helper和MCP启动、真机登录与回合验证。任何一步成功仅覆盖相应门槛。
