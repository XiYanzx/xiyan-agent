#!/usr/bin/env python3
"""Capture a bounded, non-secret build command and its real exit status."""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import signal
import subprocess
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--timeout", type=int, default=900)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("a command is required")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    started = dt.datetime.now(dt.timezone.utc).isoformat()
    tick = time.monotonic()
    timed_out = False
    with args.output.with_suffix(".log").open("wb") as log:
        proc = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT,
                                start_new_session=True)
        try:
            code = proc.wait(timeout=args.timeout)
        except subprocess.TimeoutExpired:
            timed_out = True
            os.killpg(proc.pid, signal.SIGTERM)
            try:
                code = proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(proc.pid, signal.SIGKILL)
                code = proc.wait()
    wrapper_code = 124 if timed_out else (128 - code if code < 0 else code)
    result = {
        "command": command,
        "cwd": os.getcwd(),
        "started_utc": started,
        "finished_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "elapsed_seconds": round(time.monotonic() - tick, 3),
        "timeout_seconds": args.timeout,
        "timed_out": timed_out,
        "process_return_code": code,
        "wrapper_exit_code": wrapper_code,
        "log": args.output.with_suffix(".log").name,
    }
    args.output.with_suffix(".json").write_text(
        json.dumps(result, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps(result, ensure_ascii=False))
    raise SystemExit(wrapper_code)


if __name__ == "__main__":
    main()
