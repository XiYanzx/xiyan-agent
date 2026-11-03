#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to an Android SDK containing build-tools 35.0.0}"

# 所有写入先放在被 Git 忽略的 build 下，与 artifacts 同文件系统，校验失败不覆盖旧包。
mkdir -p build
stage=$(mktemp -d "$PWD/build/p0-package.XXXXXXXX")
backup=""
cleanup() {
  if [[ -n "$backup" && -d "$backup" && ! -e artifacts ]]; then
    mv -T -- "$backup" artifacts
  fi
  if [[ -d "$stage" ]]; then rm -rf -- "$stage"; fi
}
trap cleanup EXIT

cp app/build/outputs/apk/debug/app-debug.apk "$stage/input.apk"
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs "$stage/input.apk" > "$stage/APK-SIGNATURE.txt"
"$ANDROID_HOME/build-tools/35.0.0/zipalign" -c -P 16 -v 4 "$stage/input.apk" > "$stage/APK-ALIGNMENT.txt"
python3 tools/verify_p0_package.py --apk "$stage/input.apk" \
  --report "$stage/BUILD-INFO.json" --evidence-dir "$stage/test-results"

apk_name=$(python3 - "$stage/BUILD-INFO.json" <<'PY'
import json, sys
report = json.load(open(sys.argv[1], encoding='utf-8'))
print('xiyan-agent-' + report['version'] + '-debug.apk')
PY
)
mv -- "$stage/input.apk" "$stage/$apk_name"
cp LICENSE "$stage/LICENSE.txt"
cp THIRD_PARTY_NOTICES.md "$stage/THIRD_PARTY_NOTICES.md"
cp docs/P0-release-notes.zh-CN.md "$stage/P0-README.zh-CN.md"
python3 - "$stage" <<'PY'
import hashlib, pathlib, shutil, sys, zipfile
directory = pathlib.Path(sys.argv[1])
evidence = directory / 'test-results'
with zipfile.ZipFile(directory / 'VALIDATION-RESULTS.zip', 'w', compression=zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(evidence.rglob('*')):
        if path.is_file():
            archive.write(path, path.relative_to(evidence).as_posix())
shutil.rmtree(evidence)
lines = [hashlib.sha256(path.read_bytes()).hexdigest() + '  ' + path.relative_to(directory).as_posix()
         for path in sorted(directory.rglob('*')) if path.is_file() and path.name != 'SHA256SUMS.txt']
(directory / 'SHA256SUMS.txt').write_text('\n'.join(lines) + '\n', encoding='utf-8')
PY
python3 tools/verify_p0_package.py --check-source-only "$stage/BUILD-INFO.json"

if [[ -e artifacts || -L artifacts ]]; then
  if [[ ! -d artifacts || -L artifacts ]]; then
    printf '%s\n' '拒绝替换不是普通目录的 artifacts' >&2
    exit 1
  fi
  backup=$(mktemp -d "$PWD/build/p0-artifacts-previous.XXXXXXXX")
  rmdir "$backup"
  mv -T -- artifacts "$backup"
fi
mv -T -- "$stage" artifacts
if [[ -n "$backup" && -d "$backup" ]]; then rm -rf -- "$backup"; fi
printf '%s\n' "已验证并打包 artifacts/$apk_name"
