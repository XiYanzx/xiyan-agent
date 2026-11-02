#!/usr/bin/env python3
"""Copy only already acquired, unchanged V8 crate blobs for prebuilt diagnostics.

Does not acquire Chromium gitlinks or retrieve any network resources.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile

COMMIT = "350c5a859a9b3c84ab8701b3ad51e3b7699b30c0"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("checkout", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    actual = subprocess.check_output(
        ["git", "-C", str(args.checkout), "rev-parse", "HEAD"], text=True).strip()
    if actual != COMMIT:
        raise SystemExit("Unexpected V8 commit")
    # git archive omits all submodules: only the existing crate's own blobs.
    archive = subprocess.check_output(
        ["git", "-C", str(args.checkout), "archive", COMMIT])
    crate = args.destination / "v8-152.2.0"
    # Refuse to mix an old build's generated files with exact source blobs.
    crate.mkdir(parents=True, exist_ok=False)
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        tar.extractall(crate, filter="data")
    hashes = {str(p.relative_to(crate)): hashlib.sha256(p.read_bytes()).hexdigest()
              for p in sorted(crate.rglob("*")) if p.is_file()}
    (crate / ".cargo-checksum.json").write_text(
        json.dumps({"files": hashes, "package": None}, sort_keys=True) + "\n")
    config = args.destination / "source-replacement.toml"
    config.write_text(
        '[source.locked-v8]\n'
        'git = "https://github.com/seanh-oai/rusty_v8.git"\n'
        f'rev = "{COMMIT}"\n'
        'replace-with = "locked-v8-local"\n\n'
        '[source.locked-v8-local]\n'
        f'directory = {json.dumps(str(args.destination.resolve()))}\n')
    record = {
        "commit": COMMIT,
        "network_requests": 0,
        "method": "git archive of already acquired exact checkout; no submodules",
        "git_archive_sha256": hashlib.sha256(archive).hexdigest(),
        "tracked_files": hashes,
        "config": str(config),
        "crate": str(crate),
    }
    (args.destination / "vendor-record.json").write_text(
        json.dumps(record, indent=2, sort_keys=True) + "\n")
    print(json.dumps({k: v for k, v in record.items() if k != "tracked_files"}))


if __name__ == "__main__":
    main()
