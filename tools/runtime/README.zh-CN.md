# 官方 Codex Android ARM64 runtime spike

结果和原始证据见 [P0-RUNTIME-SPIKE.zh-CN.md](../../docs/runtime/P0-RUNTIME-SPIKE.zh-CN.md)。这些脚本属于编译诊断工具，不是已可运行的Android发行包。

## 环境准备

以下为本次实际采用的来源、版本和目录。执行网络命令前按Cloud runtime指引核对当前网络状态；保持继承的代理、CA和TLS验证。目前Chromium子模块目的地被拒绝，相关编译已停止，须先完成受支持的网络授权并确认实际生效。不要通过镜像或其他路线获取被拒资源。

```sh
mkdir -p /workspace/runtime-spike /workspace/toolchains
git clone --depth 1 --branch rust-v0.166.0 \
  https://github.com/openai/codex.git /workspace/runtime-spike/codex
git -C /workspace/runtime-spike/codex rev-parse HEAD
# 必须为6ff1e1ca3b30fc8b914720681fcf5fe62e95f856

curl --fail --location --show-error --silent \
  https://static.rust-lang.org/rustup/dist/x86_64-unknown-linux-gnu/rustup-init \
  -o /workspace/runtime-spike/rustup-init
printf '%s  %s\n' \
  5be191e8c70a5007074254cb74d78dbfdd25cd5a61c27de7b8e5410122ec24f4 \
  /workspace/runtime-spike/rustup-init | sha256sum --check -
# 此dist地址可变；散列不匹配就停止，重新核验官方版本后再决定。
chmod +x /workspace/runtime-spike/rustup-init
RUSTUP_HOME=/workspace/toolchains/rust/rustup \
  CARGO_HOME=/workspace/toolchains/rust/cargo \
  /workspace/runtime-spike/rustup-init --profile minimal \
  --default-toolchain 1.95.0 --no-modify-path -y
export RUSTUP_HOME=/workspace/toolchains/rust/rustup
export CARGO_HOME=/workspace/toolchains/rust/cargo
export PATH="$CARGO_HOME/bin:$PATH"
rustup target add --toolchain 1.95.0 aarch64-linux-android
rustup component add --toolchain 1.95.0 rust-src

curl --fail --location --show-error --silent \
  https://dl.google.com/android/repository/android-ndk-r28c-linux.zip \
  -o /workspace/runtime-spike/android-ndk-r28c-linux.zip
printf '%s  %s\n' \
  dfb20d396df28ca02a8c708314b814a4d961dc9074f9a161932746f815aa552f \
  /workspace/runtime-spike/android-ndk-r28c-linux.zip | sha256sum --check -
unzip -q /workspace/runtime-spike/android-ndk-r28c-linux.zip \
  -d /workspace/toolchains/android-ndk
```

Google原始版本清单：`https://dl.google.com/android/repository/repository2-1.xml`，package `ndk;28.2.13676358` 的Linux ZIP为722261334字节，SHA1 `a7b54a5de87fecd125a17d54f73c446199e72a64`。Rust安装器实际版本1.29.2，Rust编译器固定1.95.0。

## 原始构建

```sh
cd /workspace/xiyan-agent
bash tools/runtime/build_android.sh
```

脚本核对官方commit和原始Cargo.lock散列，设置API29/ARM64、NDK编译器、16KB链接参数、4个job和dev-small profile，顺序执行两个真实 `cargo build --locked`。默认每条命令上限900秒。遇到目的地CONNECT403或进程中断就停止路线。JSON记录argv/cwd/UTC/退出码；`.log`记录原始合并stdout/stderr。无需安装执行第三方Termux脚本。

默认目录可通过 `CODEX_SOURCE_DIR`、`ANDROID_NDK_HOME`、`XIYAN_RUSTUP_HOME`、`XIYAN_CARGO_HOME`、`XIYAN_TARGET_DIR`、`XIYAN_EVIDENCE_DIR`调整。历史记录已经存在；重跑应设置新的 `XIYAN_EVIDENCE_DIR`，避免覆盖证据。

## 本地诊断复现（当前已停止）

以下只描述已执行的独立诊断；在当前等待网络授权的约束解除前不要继续。此路线未构建成功，也不替代原始源码构建验收。

`vendor_locked_v8.py` 只从已经获得的固定checkout执行 `git archive`，原样复制crate自身tracked blobs，拒绝覆盖现有destination crate。它不下载submodule、不会使用镜像或修改源码。Cargo source replacement遵循官方相同源码规则。V8的source build所需Chromium内容不在这个副本中。

```sh
python3 tools/runtime/vendor_locked_v8.py \
  /workspace/toolchains/rust/cargo/git/checkouts/rusty_v8-1c356b58a53d1d98/350c5a8 \
  /workspace/runtime-spike/vendor-new
git -C /workspace/runtime-spike/codex worktree add --detach \
  /workspace/runtime-spike/codex-android-diagnostic-new \
  6ff1e1ca3b30fc8b914720681fcf5fe62e95f856
git -C /workspace/runtime-spike/codex-android-diagnostic-new apply \
  /workspace/xiyan-agent/docs/runtime/evidence-vendored/diagnostic-lock-delta.patch
# patch只改170个workspace本地版本；所有外部版本/来源/校验和保持不变。
CODEX_SOURCE_DIR=/workspace/runtime-spike/codex-android-diagnostic-new \
  XIYAN_EXPECTED_LOCK_SHA256=360ea1e0ec391255ffc153a73548be524aa5d50c3476f247631cec184c21d4cb \
  XIYAN_CARGO_CONFIG=/workspace/runtime-spike/vendor-new/source-replacement.toml \
  XIYAN_EVIDENCE_DIR=/workspace/runtime-spike/new-diagnostic-evidence \
  bash tools/runtime/build_android.sh
```

原始tag的lock内部版本与manifest不同，故不归一时 `--locked`会退出101。允许的锁散列覆盖值专门用于有完整差异和外部依赖不变校验的隔离诊断；不得把修改后的lock冒充原始上游lock。

## NDK/Bionic工具链验证

```sh
python3 tools/runtime/capture_command.py \
  --output /workspace/runtime-spike/new-smoke-evidence/compile --timeout 30 -- \
  /workspace/toolchains/android-ndk/android-ndk-r28c/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang \
  -Wall -Wextra -Werror -fPIE -pie \
  -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
  tools/runtime/android_smoke.c -o /workspace/runtime-spike/android-smoke
readelf -h -lW -d /workspace/runtime-spike/android-smoke
sha256sum /workspace/runtime-spike/android-smoke
```

本次编译和检查均exit0；程序未执行。预期可检查的属性是AArch64 PIE、Android linker64、Bionic依赖及16KB LOAD对齐。不能据此推断Codex或APK能够在手机运行。

## Linux发行包静态检查

[inspect_release.py](../../docs/runtime/linux-release-evidence/inspect_release.py) 可验证官方release metadata的资产digest，解包为不可执行权限并运行readelf。官方Linux资产没有被启动。该脚本和完整命令记录由相同Cloud会话生成，见[原始检查报告](../../docs/runtime/linux-release-evidence/README.zh-CN.md)。
