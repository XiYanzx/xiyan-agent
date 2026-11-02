#!/usr/bin/env bash
# Build unmodified, fixed official Codex sources. No third-party Android port.
set -uo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd -- "${script_dir}/../.." && pwd)"
source_dir="${CODEX_SOURCE_DIR:-/workspace/runtime-spike/codex}"
ndk_dir="${ANDROID_NDK_HOME:-/workspace/toolchains/android-ndk/android-ndk-r28c}"
export RUSTUP_HOME="${XIYAN_RUSTUP_HOME:-/workspace/toolchains/rust/rustup}"
export CARGO_HOME="${XIYAN_CARGO_HOME:-/workspace/toolchains/rust/cargo}"
export PATH="${CARGO_HOME}/bin:${ndk_dir}/toolchains/llvm/prebuilt/linux-x86_64/bin:${PATH}"
export RUSTUP_TOOLCHAIN=1.95.0
export CARGO_BUILD_JOBS="${XIYAN_BUILD_JOBS:-4}"
export CARGO_INCREMENTAL=0
export CARGO_NET_RETRY=0
export CARGO_TARGET_DIR="${XIYAN_TARGET_DIR:-/workspace/runtime-spike/target}"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="${ndk_dir}/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
export CC_aarch64_linux_android="${CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER}"
export CXX_aarch64_linux_android="${CC_aarch64_linux_android}++"
export AR_aarch64_linux_android="${ndk_dir}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384'
evidence_dir="${XIYAN_EVIDENCE_DIR:-${repo_dir}/docs/runtime/evidence}"
mkdir -p "${evidence_dir}"
if [[ "$(git -C "${source_dir}" rev-parse HEAD)" != 6ff1e1ca3b30fc8b914720681fcf5fe62e95f856 ]]; then
    echo 'Unexpected upstream commit' >&2
    exit 2
fi
expected_lock="${XIYAN_EXPECTED_LOCK_SHA256:-22bd51f50864ce40f6686f3350c0547fdd50a8213240f54435ed394f68bf08e6}"
if [[ "$(sha256sum "${source_dir}/codex-rs/Cargo.lock" | cut -d' ' -f1)" != "${expected_lock}" ]]; then
    echo 'Unexpected upstream Cargo.lock' >&2
    exit 2
fi
cd "${source_dir}/codex-rs" || exit 2
result=0
config_args=()
if [[ -n "${XIYAN_CARGO_CONFIG:-}" ]]; then
    config_args=(--config "${XIYAN_CARGO_CONFIG}")
fi
for package in codex-code-mode-host codex-app-server; do
    python3 "${script_dir}/capture_command.py" \
        --output "${evidence_dir}/${package}-android" \
        --timeout "${XIYAN_BUILD_TIMEOUT:-900}" -- \
        cargo "${config_args[@]}" build --locked --profile dev-small --target aarch64-linux-android \
        --package "${package}" --bin "${package}"
    status=$?
    if [[ "$status" != 0 ]]; then result=1; fi
    if [[ "$status" -ge 128 ]]; then
        echo 'Build interrupted; stopping remaining commands.' >&2
        break
    fi
    if rg -q 'CONNECT tunnel failed, response 403' "${evidence_dir}/${package}-android.log"; then
        echo 'Destination denied by network policy; stopping this route.' >&2
        break
    fi
done
exit "$result"
