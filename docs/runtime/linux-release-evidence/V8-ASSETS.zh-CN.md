# 官方 V8 152.2.0 资产观察

观测日期：2026-11-02 UTC。**官方 `openai/codex` 的 `rusty-v8-v152.2.0` release 本次完整资产清单没有 `aarch64-linux-android` archive 或 binding，也没有任何文件名含 `android` 的资产。** 本次只读取 GitHub 元数据与本地固定源码，未下载 V8 archive、未执行 V8 或其构建脚本。

## 完整清单与核对方法

使用已连接的 GitHub connector `fetch`，分别读取：

```text
https://api.github.com/repos/openai/codex/releases/tags/rusty-v8-v152.2.0
https://api.github.com/repos/openai/codex/releases/389928130/assets?per_page=100&page=1
```

两次请求均成功。release 内嵌数组与独立 assets collection 均返回 **42 项**，两者 asset ID 集合完全一致；单页请求上限为100，返回42项。release 发布时间为 `2026-09-16T12:58:44Z`，本次元数据的 `updated_at` 与该时间相同。完整 release 响应、所有资产字段（含名称、URL、size、digest、ID、时间与上传者）及独立 collection 响应保存在 [v8-release-metadata.json](v8-release-metadata.json)。这里的“完整”指本次成功响应的资产集合，后续 release 变更需要重新核对。

| 资产 profile | target 集合 | archive / binding / checksum 数量 | Android 命名资产 |
| --- | --- | ---: | ---: |
| `ptrcomp_sandbox_release` | aarch64、x86_64 的 Apple Darwin / Linux GNU / Linux musl / Windows MSVC | 8组 × 3 = 24项 | 0 |
| 普通 `release` | aarch64、x86_64 的 Apple Darwin / Linux GNU / Linux musl | 6组 × 3 = 18项 | 0 |

因此本次未观察到下列 Android profile 的可直接取用官方配对资产：

```text
librusty_v8_ptrcomp_sandbox_release_aarch64-linux-android.a.gz
src_binding_ptrcomp_sandbox_release_aarch64-linux-android.rs
rusty_v8_ptrcomp_sandbox_release_aarch64-linux-android.sha256
```

这些名称用于说明期望的 target/profile 组合；没有对未列出的下载 URL 发起请求。

## 与固定 Codex 源码对照

核对本地官方 Codex `rust-v0.166.0` 对应 commit `6ff1e1ca3b30fc8b914720681fcf5fe62e95f856`，`git rev-parse HEAD` 成功，`git status --porcelain` 输出为空。

- [third_party/v8/README.md](https://github.com/openai/codex/blob/6ff1e1ca3b30fc8b914720681fcf5fe62e95f856/third_party/v8/README.md)固定 Rust `v8` crate 为 `152.2.0`、Bazel V8 source 为 `15.2.124.1`，要求 archive 与 binding 使用同一精确版本和 ABI，并列出普通及 sandbox profile 的命名规则。
- [rusty_v8_152_2_0.sha256](https://github.com/openai/codex/blob/6ff1e1ca3b30fc8b914720681fcf5fe62e95f856/third_party/v8/rusty_v8_152_2_0.sha256)包含8个 sandbox target 的 archive/binding 共16条；[rusty_v8_152_2_0_release_manifests.sha256](https://github.com/openai/codex/blob/6ff1e1ca3b30fc8b914720681fcf5fe62e95f856/third_party/v8/rusty_v8_152_2_0_release_manifests.sha256)包含相同8个 target 的 checksum manifest 共8条。**共24条与本次 GitHub API 的 asset digest 全部一致**。两份 manifest 均无 Android target。
- [code-mode-runtime/Cargo.toml](https://github.com/openai/codex/blob/6ff1e1ca3b30fc8b914720681fcf5fe62e95f856/codex-rs/code-mode-runtime/Cargo.toml#L27)显式启用 `v8_enable_sandbox`。[artifact_profile.bzl](https://github.com/openai/codex/blob/6ff1e1ca3b30fc8b914720681fcf5fe62e95f856/third_party/v8/artifact_profile.bzl#L22-L27)将 sandbox profile 与 pointer compression 联动；这指 V8 进程内 sandbox，不能与 Android 应用沙箱或 Codex OS sandbox 混同。普通 profile 的 Linux archive 不能仅因版本相同而当作该目标配对资产。
- [BUILD.bazel](https://github.com/openai/codex/blob/6ff1e1ca3b30fc8b914720681fcf5fe62e95f856/third_party/v8/BUILD.bazel#L85-L101)的 prebuilt selector 列出 Darwin、Windows MSVC、Linux GNU/musl，没有显式 Android selector；source selector同样没有命名 Android target。不过 [engine.BUILD.bazel](https://github.com/openai/codex/blob/6ff1e1ca3b30fc8b914720681fcf5fe62e95f856/third_party/v8/engine.BUILD.bazel#L196-L200)中保留 `V8_TARGET_OS_ANDROID` 分支。因此“该 release 没有 Android prebuilt”不能推导为“V8 源码无法编译 Android”。

逐项 manifest 比较、文件 SHA256、源码 commit 与命令退出码保存在 [v8-source-comparison.json](v8-source-comparison.json)。此处只比较源码声明与 API 元数据，**没有通过下载资产来独立验证资产字节，也没有验证实际 ABI 或链接结果**。

## 当前限制与后续动作

使用该 release 的官方预编译 V8 Android sandbox/pointer-compression 配对资产这一动作当前 **blocked（资产清单未提供目标组合）**，并非权限拒绝。Android 构建需要继续评估官方源码构建对应 target/profile，保留精确版本、生成 binding、匹配 ABI 与实际编译/链接错误；Linux archive 的 target 名称和 digest 本身不足以证明 Android 兼容。本次不替换依赖、不改 feature、不判断并行构建的结果，也不宣称 code-mode-host 已能在手机运行。没有被拒绝或结果不确定的下载/执行操作。
