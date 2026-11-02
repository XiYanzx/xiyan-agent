#!/usr/bin/env python3
"""Capture a bounded, non-secret build command and its real exit status."""
import argparse
import datetime as dt
import json
import locale
import os
from pathlib import Path
import signal
import subprocess
import time


class WindowsJob:
    """A private job owns the command before any of its code can run."""
    def __init__(self):
        import ctypes
        from ctypes import wintypes as w
        self.ctypes = ctypes
        self.kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        k = self.kernel
        for name in ("CreateJobObjectW", "CreateToolhelp32Snapshot", "OpenThread"):
            getattr(k, name).restype = w.HANDLE
        for name in ("SetInformationJobObject", "AssignProcessToJobObject", "TerminateJobObject",
                     "QueryInformationJobObject", "CloseHandle", "Thread32First", "Thread32Next"):
            getattr(k, name).restype = w.BOOL
        class Basic(ctypes.Structure):
            _fields_ = [("process_time", ctypes.c_int64), ("job_time", ctypes.c_int64),
                        ("flags", w.DWORD), ("min_ws", ctypes.c_size_t), ("max_ws", ctypes.c_size_t),
                        ("active_limit", w.DWORD), ("affinity", ctypes.c_size_t),
                        ("priority", w.DWORD), ("scheduling", w.DWORD)]
        class Limits(ctypes.Structure):
            _fields_ = [("basic", Basic), ("io", ctypes.c_uint64 * 6),
                        ("process_memory", ctypes.c_size_t), ("job_memory", ctypes.c_size_t),
                        ("peak_process", ctypes.c_size_t), ("peak_job", ctypes.c_size_t)]
        self.handle = k.CreateJobObjectW(None, None)
        if not self.handle:
            raise ctypes.WinError(ctypes.get_last_error())
        limits = Limits()
        limits.basic.flags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        if not k.SetInformationJobObject(w.HANDLE(self.handle), 9, ctypes.byref(limits), ctypes.sizeof(limits)):
            error = ctypes.WinError(ctypes.get_last_error())
            self.close()
            raise error

    def assign_and_resume(self, proc):
        c = self.ctypes
        from ctypes import wintypes as w
        k = self.kernel
        if not k.AssignProcessToJobObject(w.HANDLE(self.handle), w.HANDLE(int(proc._handle))):
            raise c.WinError(c.get_last_error())
        class Thread(c.Structure):
            _fields_ = [("size", w.DWORD), ("usage", w.DWORD), ("tid", w.DWORD),
                        ("pid", w.DWORD), ("priority", w.LONG), ("delta", w.LONG), ("flags", w.DWORD)]
        snapshot = k.CreateToolhelp32Snapshot(4, 0)  # TH32CS_SNAPTHREAD
        if snapshot == c.c_void_p(-1).value:
            raise c.WinError(c.get_last_error())
        try:
            thread = Thread()
            thread.size = c.sizeof(thread)
            found = k.Thread32First(w.HANDLE(snapshot), c.byref(thread))
            while found:
                if thread.pid == proc.pid:
                    handle = k.OpenThread(0x0002, False, thread.tid)  # THREAD_SUSPEND_RESUME
                    if not handle:
                        raise c.WinError(c.get_last_error())
                    try:
                        if k.ResumeThread(w.HANDLE(handle)) == -1:
                            raise c.WinError(c.get_last_error())
                    finally:
                        k.CloseHandle(w.HANDLE(handle))
                    return
                found = k.Thread32Next(w.HANDLE(snapshot), c.byref(thread))
            raise OSError("Suspended command's initial thread was not found")
        finally:
            k.CloseHandle(w.HANDLE(snapshot))

    def terminate(self):
        c = self.ctypes
        from ctypes import wintypes as w
        if not self.kernel.TerminateJobObject(w.HANDLE(self.handle), 1):
            raise c.WinError(c.get_last_error())
        # Query BASIC_ACCOUNTING_INFORMATION until every owned process exits.
        class Accounting(c.Structure):
            _fields_ = [("times", c.c_int64 * 4), ("faults", w.DWORD), ("total", w.DWORD),
                        ("active", w.DWORD), ("terminated", w.DWORD)]
        deadline = time.monotonic() + 5
        while True:
            info = Accounting()
            if not self.kernel.QueryInformationJobObject(w.HANDLE(self.handle), 1, c.byref(info), c.sizeof(info), None):
                raise c.WinError(c.get_last_error())
            if info.active == 0:
                return
            if time.monotonic() >= deadline:
                raise RuntimeError("Job still has active descendants after termination deadline")
            time.sleep(0.02)

    def close(self):
        if self.handle:
            from ctypes import wintypes as w
            if not self.kernel.CloseHandle(w.HANDLE(self.handle)):
                raise self.ctypes.WinError(self.ctypes.get_last_error())
            self.handle = None


class CommandInterrupted(Exception):
    def __init__(self, signum):
        self.signum = signum


def terminate_tree(proc, job=None):
    """Stop only this command's tree; an unverified cleanup is always reported."""
    result = {"method": "job-object" if job is not None else ("taskkill" if os.name == "nt" else "process-group"),
              "verified": False, "errors": []}
    try:
        if job is not None:
            job.terminate()
            proc.wait(timeout=5)
        elif os.name == "nt":
            # A completed parent can no longer identify its descendants reliably.
            # Do not risk targeting a recycled PID or claim its tree was stopped.
            if proc.poll() is not None:
                raise RuntimeError("Parent exited before tree cleanup; descendants are unverified")
            taskkill = Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32/taskkill.exe"
            stopped = subprocess.run(
                [str(taskkill), "/PID", str(proc.pid), "/T", "/F"],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                timeout=5, creationflags=subprocess.CREATE_NO_WINDOW)
            result["taskkill_return_code"] = stopped.returncode
            result["taskkill_output"] = stopped.stdout.decode(locale.getpreferredencoding(False), errors="replace")[-8192:]
            if stopped.returncode != 0:
                raise RuntimeError(f"taskkill failed with exit {stopped.returncode}; descendants are unverified")
            proc.wait(timeout=5)
        else:
            os.killpg(proc.pid, signal.SIGTERM)
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(proc.pid, signal.SIGKILL)
                proc.wait(timeout=5)
        result["verified"] = True
    except Exception as failure:
        # Cleanup errors must be evidence, even when a platform API fails in an
        # unexpected way. The caller still receives a failing wrapper status.
        result["errors"].append(f"{type(failure).__name__}: {failure}")
        # Bound even a failed tree cleanup. This fallback stops our direct child
        # only, so it must never turn the tree's verified flag back to true.
        try:
            if proc.poll() is None:
                proc.kill()
                proc.wait(timeout=5)
        except Exception as fallback_failure:
            result["errors"].append(f"Direct-child cleanup failed: {fallback_failure}")
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--timeout", type=int, default=900)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("a command is required")
    if args.timeout <= 0:
        parser.error("--timeout must be positive")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    started = dt.datetime.now(dt.timezone.utc).isoformat()
    tick = time.monotonic()
    timed_out = False
    interrupted_signal = None
    cleanup = None
    launch_error = None
    code = None
    proc = None
    job = None
    previous_handlers = {}

    def interrupted(signum, _frame):
        raise CommandInterrupted(signum)

    try:
        for signum in (signal.SIGINT, signal.SIGTERM):
            previous_handlers[signum] = signal.signal(signum, interrupted)
        with args.output.with_suffix(".log").open("wb") as log:
            try:
                job = WindowsJob() if os.name == "nt" else None
                options = ({"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP | 0x00000004}
                           if os.name == "nt" else {"start_new_session": True})
                proc = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, **options)
                if job is not None:
                    job.assign_and_resume(proc)
                code = proc.wait(timeout=args.timeout)
            except subprocess.TimeoutExpired:
                timed_out = True
            except CommandInterrupted as failure:
                interrupted_signal = failure.signum
            except KeyboardInterrupt:
                interrupted_signal = signal.SIGINT
            except OSError as failure:
                launch_error = f"{type(failure).__name__}: {failure}"
            finally:
                if proc is not None and (timed_out or interrupted_signal is not None or launch_error):
                    # Let a repeated interrupt neither abandon cleanup nor skip
                    # its evidence. Hard OS termination cannot be caught here.
                    for signum in previous_handlers:
                        signal.signal(signum, signal.SIG_IGN)
                    cleanup = terminate_tree(proc, job)
                    code = proc.poll()
                if job is not None:
                    try:
                        # Also remove background descendants after a normal exit.
                        if cleanup is None and proc is not None:
                            cleanup = terminate_tree(proc, job)
                        job.close()
                    except Exception as failure:
                        cleanup = cleanup or {"method": "job-object", "verified": False, "errors": []}
                        cleanup["verified"] = False
                        cleanup["errors"].append(f"Job close failed: {failure}")
    finally:
        for signum, handler in previous_handlers.items():
            signal.signal(signum, handler)
    if interrupted_signal is not None:
        wrapper_code = 128 + interrupted_signal
    elif cleanup is not None and not cleanup["verified"]:
        wrapper_code = 125
    elif timed_out:
        wrapper_code = 124
    elif launch_error:
        wrapper_code = 127
    else:
        wrapper_code = 128 - code if code < 0 else code
    result = {
        "command": command,
        "cwd": os.getcwd(),
        "started_utc": started,
        "finished_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "elapsed_seconds": round(time.monotonic() - tick, 3),
        "timeout_seconds": args.timeout,
        "timed_out": timed_out,
        "interrupted_signal": interrupted_signal,
        "cleanup": cleanup,
        "launch_error": launch_error,
        "process_return_code": code,
        "wrapper_exit_code": wrapper_code,
        "log": args.output.with_suffix(".log").name,
    }
    args.output.with_suffix(".json").write_text(
        json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    # Keep the console summary safe even under a legacy Windows code page.
    print(json.dumps(result, ensure_ascii=True))
    raise SystemExit(wrapper_code)


if __name__ == "__main__":
    main()
