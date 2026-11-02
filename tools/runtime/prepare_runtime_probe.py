#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Stage byte-identical official Linux musl binaries for an Android compatibility probe.

These are not Android builds. Successful staging does not establish Android compatibility.
"""
import hashlib
import json
from pathlib import Path
import shutil
import struct
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
TAG = 'rust-v0.166.0'
ARTIFACTS = {
    'x86_64': ('x86_64', 155332411, 'e69a1b62488288f702186b9c216a8e3930e45475f70124381d4e1c633f2e0c76', 62),
    'arm64-v8a': ('aarch64', 145150661, '50bb0d5ce10faa13eb6e7b0b25e1fe9712d686c41b3c3fdff587512064630d5d', 183),
}


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def main():
    downloads = ROOT / 'toolchains/runtime-probe/downloads'
    downloads.mkdir(parents=True, exist_ok=True)
    manifest = {'schemaVersion': 1, 'upstreamTag': TAG,
                'upstreamCommit': '6ff1e1ca3b30fc8b914720681fcf5fe62e95f856',
                'purpose': 'Official Linux musl binaries; experimental Android startup only', 'appServer': {}}
    for abi, (arch, size, expected, machine) in ARTIFACTS.items():
        name = f'codex-app-server-package-{arch}-unknown-linux-musl.tar.gz'
        url = f'https://github.com/openai/codex/releases/download/{TAG}/{name}'
        archive = downloads / name
        if not archive.is_file() or archive.stat().st_size != size or digest(archive) != expected:
            partial = archive.with_suffix('.partial')
            print(f'Downloading official {name}', flush=True)
            with urllib.request.urlopen(url, timeout=60) as response, partial.open('wb') as target:
                shutil.copyfileobj(response, target, 1024 * 1024)
            if partial.stat().st_size != size or digest(partial) != expected:
                raise RuntimeError('Official archive size/SHA256 mismatch: ' + name)
            partial.replace(archive)
        destination = ROOT / 'runtime-probe/src/main/jniLibs' / abi / 'libcodex_app_server.so'
        destination.parent.mkdir(parents=True, exist_ok=True)
        with tarfile.open(archive, 'r:gz') as bundle:
            member = bundle.getmember('bin/codex-app-server')
            if not member.isfile() or not 0 < member.size < 512 * 1024 * 1024:
                raise RuntimeError('Unexpected official app-server member')
            with bundle.extractfile(member) as source, destination.open('wb') as target:
                shutil.copyfileobj(source, target, 1024 * 1024)
        with destination.open('rb') as binary:
            header = binary.read(64)
            if header[:6] != b'\x7fELF\x02\x01' or struct.unpack_from('<H', header, 18)[0] != machine:
                raise RuntimeError('Official binary ELF ABI mismatch')
        manifest['appServer'][abi] = {'sha256': digest(destination), 'bytes': destination.stat().st_size,
                                      'sourceUrl': url, 'archiveSha256': expected, 'archiveBytes': size}
        print(json.dumps({'abi': abi, **manifest['appServer'][abi]}), flush=True)
    asset = ROOT / 'runtime-probe/src/main/assets/runtime-manifest.json'
    asset.parent.mkdir(parents=True, exist_ok=True)
    asset.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
