# 官方 Linux ARM64 发行包静态检查

观测日期：2026-11-02 UTC。状态：**完成下载、校验与静态 ELF 检查；未执行发行包中的任何程序，未验证 Android 启动或 P0 通过。**

## 固定来源与复现

- 官方 [openai/codex rust-v0.166.0 release](https://github.com/openai/codex/releases/tag/rust-v0.166.0)，发布时间 `2026-10-29T19:47:53Z`。
- API 清单读取成功，清单中无文件名含 `android` 的资产。API 源、资产数量、更新时间与选定资产的 ID/digest 见 [release-metadata.json](release-metadata.json)。这里只对本次清单负责，不推断其他版本。
- 选定 `codex-app-server-package-aarch64-unknown-linux-musl.tar.gz`，大小 **145,150,661 bytes**，SHA256 **`50bb0d5ce10faa13eb6e7b0b25e1fe9712d686c41b3c3fdff587512064630d5d`**。实际文件大小和 SHA256 与 GitHub asset 元数据一致；未执行 Sigstore 身份证明验证。
- 下载和检查退出码均为 **0**，原命令见 [execution-record.json](execution-record.json)。下载仅使用继承的 HTTPS 代理和 CA，未修改网络策略、未输出或读取凭据。
- 本机为 x86_64 GNU/Linux；GNU readelf 2.44、file 5.46、curl 8.14.1、Python 3.12.14。各工具原始版本输出已保留。

从仓库根目录执行以下命令。脚本在归档缺失时下载官方固定资产，在校验大小与 SHA256 后解包，再调用本机 `file` 和 `readelf`；不会运行提取的文件。解包文件刻意设为 `0644`。

```sh
python3 docs/runtime/linux-release-evidence/inspect_release.py \
  --work-dir /workspace/runtime-spike/release-inspect \
  --output-dir docs/runtime/linux-release-evidence
```

完整归档、提取文件保存在 Cloud 临时工作区 `/workspace/runtime-spike/release-inspect`，未将 145MB 二进制归档加入源码仓库。[inspection.json](inspection.json) 包含归档目录、每个 ELF 的字节数、SHA256、LOAD 段、解释器、NEEDED 和每条静态检查命令的退出码。`*.readelf.txt` 保留原始 ELF header/program header/dynamic/note 输出。

## 实际结果

| 文件 | 大小 bytes | ELF 类型 | PT_INTERP / DT_NEEDED | LOAD 对齐 |
| --- | ---: | --- | --- | --- |
| `bin/codex-app-server` | 214,949,224 | AArch64 ET_EXEC，静态 | 无 / 无 | 65,536 bytes |
| `bin/codex-code-mode-host` | 70,838,624 | AArch64 ET_EXEC，静态 | 无 / 无 | 65,536 bytes |
| `bin/codex-exec-server` | 72,132,456 | AArch64 ET_EXEC，静态 | 无 / 无 | 65,536 bytes |
| `codex-path/rg` | 4,506,992 | AArch64 ET_DYN，动态 PIE | GNU/Linux 解释器及依赖，见下 | 65,536 bytes |
| `codex-resources/bwrap` | 529,168 | AArch64 ET_EXEC，静态 | 无 / 无 | 65,536 bytes |

`rg` 的解释器为 `/lib/ld-linux-aarch64.so.1`，NEEDED 为 `libgcc_s.so.1`、`libpthread.so.0`、`libdl.so.2`、`libc.so.6`、`ld-linux-aarch64.so.1`。这些动态库不在该归档内；这是完整 Linux bundle 用作 Android 发行物前需解决和验证的明确依赖项。原始输出见 [codex-path_rg.readelf.txt](codex-path_rg.readelf.txt)。

各核心程序 SHA256：

```text
f21b0b95117d4f32151abe78d8030cc1175e877fe741deaaea46451f2b51ade2  bin/codex-app-server
812d23c5cdce3944f2e9839a46f107e5168ed78363cfd2b30be9638a9296119d  bin/codex-code-mode-host
ac3e7be94dd6c40eea7b4a4ea39af5274b96948e1fa913976aa86bbf4ad610c5  bin/codex-exec-server
```

包自身 [codex-package.json](codex-package.json) 声明 target 为 `aarch64-unknown-linux-musl`，variant 为 `codex-app-server`，同时指定独立 exec-server 和资源目录。与“只有一个 app-server 可执行文件”的假设不同，本包还包含 code-mode-host、exec-server、rg 和 bwrap，后续需逐项验证实际使用路径。

## 对 Android 路线的含义与边界

**静态核心 ELF 可以作为后续 Android 装包启动实验的候选输入，当前证据不足以认定可运行。** 无动态解释器避免了这些核心程序直接依赖 Android 缺省动态加载器路径的问题；64KB LOAD 对齐满足本次检查的 16KB ELF 段对齐条件。这些事实不验证 ELF 加载权限、SELinux/seccomp、Linux syscall、DNS/证书/文件系统路径、V8 运行时页假设、exec-server、MCP 或 code-mode-host 的执行行为，也不验证 APK ZIP 对齐。

固定源码 `6ff1e1ca3b30fc8b914720681fcf5fe62e95f856` 与 `rust-v0.166.0^{}` 一致，工作树检查为空。该版 [release workflow](https://github.com/openai/codex/blob/6ff1e1ca3b30fc8b914720681fcf5fe62e95f856/.github/workflows/rust-release.yml#L85-L145)列出 Linux musl、macOS、Windows target。该版 [sandbox manager](https://github.com/openai/codex/blob/6ff1e1ca3b30fc8b914720681fcf5fe62e95f856/codex-rs/sandboxing/src/manager.rs#L50-L64)对 Linux 选择 LinuxSeccomp，对未列举平台返回 `None`；使用 Linux binary 与编译 Android target 的平台选择可能不同，需真实受控测试。原始 git 命令、退出码和对应源码片段见 [source-observations.json](source-observations.json)。

Android 官方要求 target29+ 的应用从 APK 内嵌代码加载，禁止直接执行可写应用主目录中的文件，见 [Android 10 行为变更](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission)。官方 [16KB 页支持说明](https://developer.android.com/guide/practices/page-sizes)区分 ELF 段对齐、APK 打包和运行时测试；后两项均未由本次静态检查覆盖。

剩余动作是把经选择的 ARM64 输入放入签名测试 APK 的安装原生路径，在实际 Android 设备或受支持的 Android ARM64 测试环境中依次执行冷启动、initialize、code-mode-host/exec-server、MCP 往返及页大小场景。该动作 **pending / 未执行**，原因是本子任务限定静态检查，当前执行器为 x86_64 GNU/Linux。无操作被权限审核拒绝；没有结果不确定的启动操作。
