#!/usr/bin/env python3
"""Verify and statically inspect the pinned official Linux ARM64 release.

Never executes an extracted release program. Uses Python stdlib and host curl,
file, readelf. Retains the inherited proxy and TLS trust configuration.
"""
import argparse
import hashlib
import json
import pathlib
import platform
import shutil
import struct
import subprocess
import tarfile
from datetime import datetime, timezone

NAME = "codex-app-server-package-aarch64-unknown-linux-musl.tar.gz"
URL = "https://github.com/openai/codex/releases/download/rust-v0.166.0/" + NAME
SHA256 = "50bb0d5ce10faa13eb6e7b0b25e1fe9712d686c41b3c3fdff587512064630d5d"
SIZE = 145150661


def digest(path):
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work-dir", type=pathlib.Path, required=True)
    parser.add_argument("--output-dir", type=pathlib.Path, required=True)
    args = parser.parse_args()
    work = args.work_dir.resolve()
    out = args.output_dir.resolve()
    work.mkdir(parents=True, exist_ok=True)
    out.mkdir(parents=True, exist_ok=True)
    commands = []

    def run(argv, filename):
        p = subprocess.run(argv, capture_output=True, text=True, check=False)
        (out / filename).write_text(p.stdout + p.stderr)
        commands.append({"argv": argv, "exit_code": p.returncode, "output": filename})
        if p.returncode:
            raise RuntimeError(f"command failed with {p.returncode}: {argv}")
        return p.stdout

    archive = work / NAME
    if not archive.exists():
        run(["curl", "--fail", "--location", "--retry", "2", "--max-time", "300",
             "--output", str(archive), URL], "download.log")
    actual_size = archive.stat().st_size
    actual_sha = digest(archive)
    if (actual_size, actual_sha) != (SIZE, SHA256):
        raise RuntimeError(f"archive verification failed: size={actual_size}, sha256={actual_sha}")

    destination = work / "extracted"
    destination.mkdir(exist_ok=True)
    members = []
    with tarfile.open(archive, "r:gz") as t:
        for member in t:
            target = (destination / member.name).resolve()
            if not target.is_relative_to(destination) or not (member.isfile() or member.isdir()):
                raise RuntimeError(f"unexpected archive entry: {member.name}")
            members.append({"name": member.name, "size": member.size,
                            "mode": oct(member.mode), "kind": "file" if member.isfile() else "directory"})
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with t.extractfile(member) as src, target.open("wb") as dst:
                    shutil.copyfileobj(src, dst)
                # Extracted artifacts are deliberately non-executable.
                target.chmod(0o644)

    for tool in (["file", "--version"], ["readelf", "--version"],
                 ["curl", "--version"], ["python3", "--version"]):
        run(tool, tool[0] + "-version.txt")

    elf_results = []
    for member in members:
        if member["kind"] != "file":
            continue
        target = destination / member["name"]
        with target.open("rb") as f:
            head = f.read(64)
            if head[:4] != b"\x7fELF":
                continue
            if head[4:6] != bytes([2, 1]):
                raise RuntimeError("expected little-endian ELF64")
            fields = struct.unpack("<16sHHIQQQIHHHHHH", head)
            e_type, e_machine, phoff, phentsize, phnum = fields[1], fields[2], fields[5], fields[9], fields[10]
            load = []
            interp = None
            for i in range(phnum):
                f.seek(phoff + i * phentsize)
                p_type, flags, offset, vaddr, _, filesz, memsz, align = struct.unpack("<IIQQQQQQ", f.read(56))
                if p_type == 1:
                    load.append({"flags": flags, "offset": offset, "virtual_address": vaddr,
                                 "file_size": filesz, "memory_size": memsz, "alignment": align,
                                 "compatible_16k_alignment": align >= 16384 and offset % 16384 == vaddr % 16384})
                if p_type == 3:
                    f.seek(offset)
                    interp = f.read(filesz).rstrip(b"\0").decode()
        label = member["name"].replace("/", "_")
        description = run(["file", str(target)], label + ".file.txt").strip()
        run(["readelf", "-h", "-l", "-d", "-n", "--wide", str(target)], label + ".readelf.txt")
        dynamic = run(["readelf", "-d", "--wide", str(target)], label + ".dynamic.txt")
        elf_results.append({"path": member["name"], "size": target.stat().st_size,
                            "sha256": digest(target), "file": description,
                            "elf_type": e_type, "machine": e_machine, "interpreter": interp,
                            "needed_lines": [s.strip() for s in dynamic.splitlines() if "NEEDED" in s],
                            "load_segments": load,
                            "all_loads_aligned_16k": all(s["compatible_16k_alignment"] for s in load)})

    result = {"observed_at": datetime.now(timezone.utc).isoformat(), "inspection_only": True,
              "executed_release_binaries": False, "host": platform.uname()._asdict(),
              "archive": {"name": NAME, "url": URL, "size": actual_size, "sha256": actual_sha,
                          "matches_github_asset_size_and_digest": True},
              "archive_members": members, "elf_results": elf_results, "commands": commands}
    (out / "inspection.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"output": str(out / "inspection.json"),
                      "archive_sha256": actual_sha, "elf_count": len(elf_results),
                      "executed_release_binaries": False}, indent=2))


if __name__ == "__main__":
    main()
