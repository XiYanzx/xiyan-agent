#!/usr/bin/env python3
"""Package the verified P0 debug APK on Windows, Linux, or macOS."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile

from verify_p0_package import verify, git_state, require, require_source


def main():
    root = Path(__file__).resolve().parents[1]
    sdk = Path(os.environ['ANDROID_HOME']).resolve()
    build = root / 'build'
    build.mkdir(exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix='p0-package-', dir=build))
    artifacts = root / 'artifacts'
    backup = None

    def remove_staging(path):
        # Only remove our own ordinary staging/backup directories under this checkout.
        require(not path.is_symlink() and path.resolve().is_relative_to(build.resolve()),
                'Refusing to remove a directory outside the checkout build directory')
        shutil.rmtree(path)

    try:
        require(not artifacts.is_symlink() and (not artifacts.exists() or artifacts.is_dir()),
                'artifacts must be an ordinary directory')
        apk = stage / 'input.apk'
        shutil.copyfile(root / 'app/build/outputs/apk/debug/app-debug.apk', apk)
        tools = sdk / 'build-tools/35.0.0'
        signer = tools / ('apksigner.bat' if os.name == 'nt' else 'apksigner')
        align = tools / ('zipalign.exe' if os.name == 'nt' else 'zipalign')
        for command, output in (
            ([str(signer), 'verify', '--verbose', '--print-certs', str(apk)], 'APK-SIGNATURE.txt'),
            ([str(align), '-c', '-P', '16', '-v', '4', str(apk)], 'APK-ALIGNMENT.txt'),
        ):
            with (stage / output).open('wb') as log:
                subprocess.run(command, cwd=root, check=True, stdout=log, stderr=subprocess.STDOUT)
        report, evidence = verify(root, apk)
        apk_name = f"xiyan-agent-{report['version']}-debug.apk"
        apk.rename(stage / apk_name)
        (stage / 'BUILD-INFO.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        with zipfile.ZipFile(stage / 'VALIDATION-RESULTS.zip', 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            for module, files in sorted(evidence.items()):
                for name, data in sorted(files.items()):
                    archive.writestr(f'{module}/{name}', data)
        for source, name in (
            ('LICENSE', 'LICENSE.txt'), ('THIRD_PARTY_NOTICES.md', 'THIRD_PARTY_NOTICES.md'),
            ('third_party/lucide/LICENSE', 'LUCIDE-LICENSE.txt'),
            ('app/src/main/assets/licenses/shizuku-api-LICENSE.txt', 'SHIZUKU-API-LICENSE.txt'),
            ('app/src/main/assets/licenses/androidx-annotation-LICENSE.txt', 'ANDROIDX-ANNOTATION-LICENSE.txt'),
            ('docs/P0-release-notes.zh-CN.md', 'P0-README.zh-CN.md'),
        ):
            shutil.copyfile(root / source, stage / name)
        checksums = [hashlib.sha256(path.read_bytes()).hexdigest() + '  ' + path.name
                     for path in sorted(stage.iterdir()) if path.is_file()]
        (stage / 'SHA256SUMS.txt').write_text('\n'.join(checksums) + '\n', encoding='utf-8')
        current = git_state(root)
        require(current['sourceTreeClean'], 'Source changed before final package replacement')
        require_source(report, current, 'Final package')
        if artifacts.exists():
            backup = Path(tempfile.mkdtemp(prefix='p0-previous-', dir=build))
            backup.rmdir()
            artifacts.rename(backup)
        stage.rename(artifacts)
        if backup is not None:
            remove_staging(backup)
            backup = None
        print(json.dumps({'artifact': str(artifacts / apk_name), 'sourceCommit': report['sourceCommit'],
                          'sha256': report['apkSha256'], 'tests': report['unitTestTotals']}))
    finally:
        if backup is not None and backup.exists() and not artifacts.exists():
            backup.rename(artifacts)
        if stage.exists():
            remove_staging(stage)


if __name__ == '__main__':
    main()
