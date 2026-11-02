#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Record an adb command and its exit status against an explicit emulator only."""

import argparse
import datetime
import json
import os
from pathlib import Path
import re
import subprocess
import time


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=os.path.join(
        os.environ.get("ANDROID_HOME", "/workspace/toolchains/android-sdk"),
        "platform-tools", "adb"))
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--stdout-extension", default="txt")
    parser.add_argument("--timeout", type=float, default=30)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("This recorder only targets an explicit emulator-NNNN serial.")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", args.name):
        parser.error("Use a simple evidence name without directory separators.")
    if not re.fullmatch(r"[A-Za-z0-9]+", args.stdout_extension):
        parser.error("Use a simple stdout extension.")
    command_args = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command_args:
        parser.error("An adb command is required after --.")
    args.output.mkdir(parents=True, exist_ok=True)
    command = [args.adb, "-s", args.serial, *command_args]
    started = utc_now()
    monotonic_start = time.monotonic()
    timed_out = False
    try:
        result = subprocess.run(command, capture_output=True, timeout=args.timeout)
        stdout, stderr, exit_code = result.stdout, result.stderr, result.returncode
    except subprocess.TimeoutExpired as error:
        stdout, stderr, exit_code = error.stdout or b"", error.stderr or b"", 124
        timed_out = True
    stdout_name = f"{args.name}.{args.stdout_extension}"
    stderr_name = f"{args.name}.stderr.txt"
    (args.output / stdout_name).write_bytes(stdout)
    (args.output / stderr_name).write_bytes(stderr)
    record = {
        "command": command,
        "started_utc": started,
        "finished_utc": utc_now(),
        "elapsed_seconds": round(time.monotonic() - monotonic_start, 3),
        "exit_code": exit_code,
        "timed_out": timed_out,
        "stdout": stdout_name,
        "stderr": stderr_name,
        "platform": "cloud Android emulator; not a physical device",
    }
    (args.output / f"{args.name}.json").write_text(
        json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(record, ensure_ascii=False))
    return exit_code if 0 <= exit_code <= 255 else 1


if __name__ == "__main__":
    raise SystemExit(main())
