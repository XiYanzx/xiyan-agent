"""Focused recorder tests. Run: python -B -m unittest discover -s tools/runtime -p test_capture_command.py"""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock


SCRIPT = Path(__file__).with_name("capture_command.py")
SPEC = importlib.util.spec_from_file_location("capture_command", SCRIPT)
recorder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(recorder)


class CaptureCommandTest(unittest.TestCase):
    def run_capture(self, directory, source, timeout=3):
        output = Path(directory) / "capture"
        completed = subprocess.run(
            [sys.executable, "-B", str(SCRIPT), "--output", str(output),
             "--timeout", str(timeout), "--", sys.executable, "-c", source],
            capture_output=True, timeout=15)
        record = json.loads(output.with_suffix(".json").read_text(encoding="utf-8"))
        return completed, record, output.with_suffix(".log").read_bytes()

    def test_normal_exit(self):
        with tempfile.TemporaryDirectory() as directory:
            completed, record, log = self.run_capture(directory, "print('captured', flush=True)")
        self.assertEqual(completed.returncode, 0)
        self.assertEqual(record["process_return_code"], 0)
        self.assertEqual(record["wrapper_exit_code"], 0)
        self.assertFalse(record["timed_out"])
        if os.name == "nt":
            self.assertTrue(record["cleanup"]["verified"], record)
        else:
            self.assertIsNone(record["cleanup"])
        self.assertIn(b"captured", log)

    def test_nonzero_exit(self):
        with tempfile.TemporaryDirectory() as directory:
            completed, record, _ = self.run_capture(directory, "raise SystemExit(7)")
        self.assertEqual(completed.returncode, 7)
        self.assertEqual(record["process_return_code"], 7)
        self.assertEqual(record["wrapper_exit_code"], 7)

    def test_launch_failure_still_writes_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "launch-failure"
            args = [str(SCRIPT), "--output", str(output), "--", str(Path(directory) / "missing-command")]
            with mock.patch.object(sys, "argv", args), contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(SystemExit) as ended:
                    recorder.main()
            record = json.loads(output.with_suffix(".json").read_text(encoding="utf-8"))
        self.assertEqual(ended.exception.code, 127)
        self.assertIn("FileNotFoundError", record["launch_error"])
        self.assertIsNone(record["process_return_code"])

    def test_job_cleanup_exception_is_reported(self):
        proc = mock.Mock(pid=123)
        proc.poll.return_value = None
        job = mock.Mock()
        job.terminate.side_effect = OSError("test termination denied")
        result = recorder.terminate_tree(proc, job)
        self.assertFalse(result["verified"])
        self.assertIn("test termination denied", result["errors"][0])
        proc.kill.assert_called_once_with()

    def test_timeout_terminates_child_tree(self):
        with tempfile.TemporaryDirectory() as directory:
            # A surviving grandchild would write a marker after the recorder's
            # one-second timeout. No child sleeps for more than five seconds.
            marker = Path(directory) / "orphan-marker"
            child = f"import pathlib,time; time.sleep(3); pathlib.Path({str(marker)!r}).write_text('orphan')"
            source = ("import subprocess,sys,time; "
                      f"p=subprocess.Popen([sys.executable,'-c',{child!r}]); "
                      "print(p.pid,flush=True); time.sleep(5)")
            completed, record, log = self.run_capture(directory, source, timeout=1)
            if completed.returncode != 124:
                time.sleep(3)  # Let the bounded fixture exit before temp cleanup.
            self.assertEqual(completed.returncode, 124, record)
            self.assertTrue(record["timed_out"])
            self.assertTrue(record["cleanup"]["verified"], record)
            self.assertEqual(record["cleanup"]["errors"], [])
            child_pid = int(log.strip())
            if os.name == "nt":
                import ctypes
                kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
                kernel32.OpenProcess.restype = ctypes.c_void_p
                handle = kernel32.OpenProcess(0x00100000, False, child_pid)
                if handle:
                    try:
                        self.assertEqual(kernel32.WaitForSingleObject(ctypes.c_void_p(handle), 1000), 0)
                    finally:
                        kernel32.CloseHandle(ctypes.c_void_p(handle))
            else:
                # The marker also works where a killed child remains a zombie.
                time.sleep(3)
            self.assertFalse(marker.exists(), "Timed-out command left a running descendant")

    def test_cleanup_failure_is_written_to_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "failed-cleanup"
            proc = mock.Mock(pid=123)
            proc.wait.side_effect = subprocess.TimeoutExpired("child", 1)
            proc.poll.return_value = None
            failed = {"method": "taskkill", "verified": False, "errors": ["test cleanup failure"]}
            args = [str(SCRIPT), "--output", str(output), "--timeout", "1", "--", "child"]
            with mock.patch.object(sys, "argv", args), mock.patch.object(recorder, "WindowsJob", return_value=mock.Mock()), \
                    mock.patch.object(recorder.subprocess, "Popen", return_value=proc), \
                    mock.patch.object(recorder, "terminate_tree", return_value=failed), contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(SystemExit) as ended:
                    recorder.main()
            record = json.loads(output.with_suffix(".json").read_text(encoding="utf-8"))
        self.assertEqual(ended.exception.code, 125)
        self.assertTrue(record["timed_out"])
        self.assertEqual(record["cleanup"], failed)
        self.assertIsNone(record["process_return_code"])

    @unittest.skipUnless(os.name == "nt", "Windows-specific taskkill failure")
    def test_taskkill_failure_reports_unverified_descendants(self):
        proc = mock.Mock(pid=123)
        proc.poll.side_effect = [None, None]
        failed = subprocess.CompletedProcess([], 5, stdout=b"access denied")
        with mock.patch.object(recorder.subprocess, "run", return_value=failed) as taskkill:
            result = recorder.terminate_tree(proc)
        self.assertFalse(result["verified"])
        self.assertEqual(result["taskkill_return_code"], 5)
        self.assertIn("descendants are unverified", result["errors"][0])
        self.assertEqual(taskkill.call_args.args[0][1:], ["/PID", "123", "/T", "/F"])
        proc.kill.assert_called_once_with()

    def test_interrupt_runs_cleanup_and_records_signal(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "interrupted"
            proc = mock.Mock(pid=123)
            proc.wait.side_effect = recorder.CommandInterrupted(signal.SIGTERM)
            proc.poll.return_value = -signal.SIGTERM
            cleanup = {"method": "test", "verified": True, "errors": []}
            args = [str(SCRIPT), "--output", str(output), "--", "child"]
            with mock.patch.object(sys, "argv", args), mock.patch.object(recorder, "WindowsJob", return_value=mock.Mock()), \
                    mock.patch.object(recorder.subprocess, "Popen", return_value=proc), \
                    mock.patch.object(recorder, "terminate_tree", return_value=cleanup) as stop, \
                    contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(SystemExit) as ended:
                    recorder.main()
            record = json.loads(output.with_suffix(".json").read_text(encoding="utf-8"))
        self.assertEqual(ended.exception.code, 143)
        self.assertEqual(record["interrupted_signal"], signal.SIGTERM)
        stop.assert_called_once()
        self.assertIs(stop.call_args.args[0], proc)


if __name__ == "__main__":
    unittest.main()
