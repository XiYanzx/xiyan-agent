#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to an Android SDK containing build-tools 35.0.0}"
mkdir -p artifacts
apk=artifacts/xiyan-agent-0.1.0-p0-debug.apk
cp app/build/outputs/apk/debug/app-debug.apk "$apk"
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs "$apk" > artifacts/APK-SIGNATURE.txt
"$ANDROID_HOME/build-tools/35.0.0/zipalign" -c -P 16 -v 4 "$apk" > artifacts/APK-ALIGNMENT.txt
python3 - <<'PY'
import json, os, pathlib, subprocess, xml.etree.ElementTree as ET
root = pathlib.Path('.')
summaries = []
for f in sorted(root.glob('core/build/test-results/test/TEST-*.xml')):
    r = ET.parse(f).getroot()
    summaries.append({k:r.attrib.get(k) for k in ['name','tests','failures','errors','skipped','time']})
if not summaries:
    raise SystemExit('Missing core JUnit results; run :core:test before packaging')
if any(int(x.get('failures') or 0) or int(x.get('errors') or 0) for x in summaries):
    raise SystemExit('JUnit results contain failures')
report = {
    'stage': 'P0 technical preview; acceptance pending',
    'sourceCommit': os.environ.get('XIYAN_SOURCE_COMMIT') or subprocess.check_output(['git','rev-parse','HEAD'], text=True).strip(),
    'applicationId': 'io.xiyan.agent.p0',
    'version': '0.1.0-p0',
    'signature': 'Android debug test certificate; not a production signing identity',
    'runtimeBundled': False,
    'accountLoginTested': False,
    'realInferenceTested': False,
    'iqooPhysicalDeviceTested': False,
    'coreUnitTests': summaries,
    'androidUnitTests': 'No Android unit tests; see core unit tests and separately recorded emulator checks',
    'longDurationTests': '30/60/120 minutes not performed',
}
pathlib.Path('artifacts/BUILD-INFO.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
PY
cp LICENSE artifacts/LICENSE.txt
cp THIRD_PARTY_NOTICES.md artifacts/THIRD_PARTY_NOTICES.md
cp docs/P0-release-notes.zh-CN.md artifacts/P0-README.zh-CN.md
python3 - <<'PY'
import hashlib, pathlib
directory = pathlib.Path('artifacts')
lines = [hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name for p in sorted(directory.iterdir())
         if p.is_file() and p.name != 'SHA256SUMS.txt']
(directory/'SHA256SUMS.txt').write_text('\n'.join(lines)+'\n')
PY
