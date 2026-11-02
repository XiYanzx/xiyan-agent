// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.runtimeprobe;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs only one fixed expression over the official helper's local framed stdio. */
final class CodeModeProbeRunner {
    private static final String SOURCE = "text('v8-probe:'+(1+1));";
    private static final String EXPECTED = "v8-probe:2";
    private static final String SESSION = "xiyan-offline-v8";
    private static final int FRAME_CAP = 65536;
    private static final int STDERR_CAP = 16384;
    private static final long WORK_MS = 12000;
    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
    private Run active;
    private boolean closed;

    CodeModeProbeRunner(Context context) { this.context = context; }
    synchronized void start(ProbeRunner.Listener listener) {
        if (closed || active != null) return;
        Run run = new Run(SystemClock.elapsedRealtime() + WORK_MS);
        active = run;
        worker.execute(() -> execute(run, listener));
    }
    synchronized void stop(String reason) { if (active != null) active.stop(reason); }
    synchronized void close() {
        closed = true;
        stop("activity_destroyed");
        worker.shutdown();
        timers.shutdown();
    }
    private File reportFile() { return new File(context.getFilesDir(), "code-mode-probe-last.json"); }
    String readLastReport() {
        try (InputStream input = new AtomicFile(reportFile()).openRead()) {
            return new String(readBounded(input, 1048576), StandardCharsets.UTF_8);
        } catch (Exception ignored) { return null; }
    }

    private void execute(Run run, ProbeRunner.Listener listener) {
        long started = SystemClock.elapsedRealtime();
        JSONObject report = new JSONObject();
        FrameReader reader = null;
        StderrReader stderr = null;
        ScheduledFuture<?> watchdog = null;
        String failure = null;
        boolean hello = false, opened = false, evaluated = false, sessionClosed = false;
        try {
            report.put("schemaVersion", 1).put("applicationId", BuildConfig.APPLICATION_ID)
                    .put("sourceCommit", BuildConfig.SOURCE_COMMIT).put("sourceTreeClean", BuildConfig.SOURCE_TREE_CLEAN)
                    .put("version", BuildConfig.VERSION_NAME).put("startedEpochMillis", System.currentTimeMillis())
                    .put("androidApi", Build.VERSION.SDK_INT).put("deviceAbis", new JSONArray(Build.SUPPORTED_ABIS))
                    .put("pageSize", Os.sysconf(OsConstants._SC_PAGESIZE))
                    .put("scope", "Offline code-mode-host V8 constant evaluation only")
                    .put("source", SOURCE).put("expectedOutput", EXPECTED).put("enabledTools", new JSONArray())
                    .put("accountLoginTested", false).put("inferenceTested", false).put("execServerTested", false)
                    .put("phoneActionsTested", false).put("mcpTested", false)
                    .put("workDeadlineMillis", WORK_MS).put("cleanupDeadlineMillis", 2000)
                    .put("stdoutFrameAndTotalByteCap", FRAME_CAP).put("stderrByteCap", STDERR_CAP);
            watchdog = timers.schedule(() -> run.stop("total_work_timeout"), WORK_MS, TimeUnit.MILLISECONDS);
            JSONObject manifest;
            try (InputStream input = context.getAssets().open("runtime-manifest.json")) {
                manifest = new JSONObject(new String(readBounded(input, FRAME_CAP), StandardCharsets.UTF_8));
            }
            if (manifest.getInt("schemaVersion") != 1 || !"rust-v0.166.0".equals(manifest.getString("upstreamTag"))) throw new IOException("Unsupported runtime manifest");
            report.put("runtimeManifest", manifest);
            File binary = new File(context.getApplicationInfo().nativeLibraryDir, "libcodex_code_mode_host.so");
            if (!binary.isFile() || !binary.canExecute()) throw new IOException("Installed helper missing");
            String abi = elfAbi(binary);
            boolean supported = false;
            for (String candidate : Build.SUPPORTED_ABIS) supported |= abi.equals(candidate);
            if (!supported) throw new IOException("Helper ELF ABI unsupported");
            String expected = manifest.getJSONObject("codeModeHost").getJSONObject(abi).getString("sha256");
            if (!expected.matches("[0-9a-f]{64}")) throw new IOException("Invalid expected helper SHA256");
            String actual = digest(binary, run);
            report.put("runtimeAbi", abi).put("nativePath", binary.getAbsolutePath())
                    .put("expectedSha256", expected).put("actualSha256", actual);
            if (!expected.equals(actual)) throw new IOException("Helper SHA256 mismatch");
            report.put("nativeSha256Verified", true);
            run.check();
            File directory = new File(context.getNoBackupFilesDir(), "code-mode-probe/" + UUID.randomUUID());
            File home = new File(directory, "home"), codexHome = new File(directory, "codex"), temporary = new File(directory, "tmp");
            if (!home.mkdirs() || !codexHome.mkdirs() || !temporary.mkdirs()) throw new IOException("Private helper directory creation failed");
            ProcessBuilder builder = new ProcessBuilder(binary.getAbsolutePath(), "--listen", "stdio://");
            builder.directory(directory);
            Map<String, String> env = builder.environment();
            env.clear();
            env.put("PATH", "/system/bin:/system/xbin");
            env.put("ANDROID_ROOT", "/system");
            env.put("ANDROID_DATA", "/data");
            env.put("HOME", home.getAbsolutePath());
            env.put("CODEX_HOME", codexHome.getAbsolutePath());
            env.put("TMPDIR", temporary.getAbsolutePath());
            env.put("LANG", "C.UTF-8");
            report.put("environmentKeys", new JSONArray(env.keySet()));
            Process child = builder.start();
            run.publish(child);
            run.check();
            report.put("processStartedMillis", SystemClock.elapsedRealtime() - started);
            reader = new FrameReader(child.getInputStream(), run);
            stderr = new StderrReader(child.getErrorStream(), run);
            reader.start(); stderr.start();
            OutputStream input = child.getOutputStream();
            writeFrame(input, new JSONObject().put("type", "connection/hello").put("supportedVersions", new JSONArray().put(1))
                    .put("requiredCapabilities", new JSONArray()).put("optionalCapabilities", new JSONArray()));
            JSONObject ready = reader.next();
            if (!"connection/ready".equals(ready.optString("type")) || ready.getInt("selectedVersion") != 1) throw new IOException("Helper V1 hello rejected");
            hello = true;
            send(input, 1, new JSONObject().put("method", "session/open").put("sessionId", SESSION));
            JSONObject session = response(reader, 1);
            if (!"session/ready".equals(session.optString("type")) || !SESSION.equals(session.getString("sessionId"))) throw new IOException("Unexpected session/open result");
            opened = true;
            JSONObject request = new JSONObject().put("tool_call_id", "xiyan-offline-probe").put("enabled_tools", new JSONArray())
                    .put("source", SOURCE).put("yield_time_ms", 1000).put("max_output_tokens", 256);
            send(input, 2, new JSONObject().put("method", "session/execute").put("sessionId", SESSION).put("request", request));
            JSONObject admitted = response(reader, 2);
            if (!"execution/started".equals(admitted.optString("type"))) throw new IOException("Expected execution/started");
            String cell = admitted.getString("cellId");
            if (cell.isEmpty()) throw new IOException("Empty execution cell id");
            JSONObject initial = reader.nextRelevant();
            if (!"execute/initialResponse".equals(initial.optString("type")) || !"2".equals(String.valueOf(initial.opt("id")))) throw new IOException("Expected execute/initialResponse id2");
            JSONObject outcome = ok(initial.getJSONObject("result"));
            JSONArray collectedContent = new JSONArray();
            int nextId = 3;
            while (outcome.has("Yielded")) {
                JSONObject yielded = outcome.getJSONObject("Yielded");
                if (!cell.equals(yielded.getString("cell_id"))) throw new IOException("Yielded cell mismatch");
                appendContent(collectedContent, yielded.getJSONArray("content_items"));
                run.check();
                int waitId = nextId++;
                send(input, waitId, new JSONObject().put("method", "session/wait").put("sessionId", SESSION)
                        .put("request", new JSONObject().put("cell_id", cell).put("yield_time_ms", 1000)));
                JSONObject waited = response(reader, waitId);
                if (!"wait/completed".equals(waited.optString("type"))) throw new IOException("Expected wait/completed");
                JSONObject state = waited.getJSONObject("outcome");
                if (state.has("MissingCell")) throw new IOException("Owned V8 cell unexpectedly missing");
                outcome = state.getJSONObject("LiveCell");
            }
            JSONObject completed = outcome.getJSONObject("Result");
            if (!cell.equals(completed.getString("cell_id")) || !completed.has("error_text") || !completed.isNull("error_text")) throw new IOException("V8 error or cell mismatch");
            appendContent(collectedContent, completed.getJSONArray("content_items"));
            JSONArray content = collectedContent;
            if (content.length() != 1 || !"input_text".equals(content.getJSONObject(0).optString("type"))
                    || !EXPECTED.equals(content.getJSONObject(0).getString("text"))) throw new IOException("V8 fixed expression output mismatch");
            evaluated = true;
            report.put("executionResult", completed).put("collectedContent", content);
            send(input, nextId, new JSONObject().put("method", "session/shutdown").put("sessionId", SESSION));
            JSONObject shutdown = response(reader, nextId);
            if (!"session/closed".equals(shutdown.optString("type")) || !SESSION.equals(shutdown.getString("sessionId"))) throw new IOException("Expected session/closed");
            sessionClosed = true;
        } catch (Exception problem) {
            failure = problem.getClass().getSimpleName() + ": " + problem.getMessage();
        } finally {
            if (watchdog != null) watchdog.cancel(false);
            JSONObject cleanup = run.cleanup();
            if (reader != null) reader.finish();
            if (stderr != null) stderr.finish();
            try {
                Integer code = cleanup.has("processExitCode") && !cleanup.isNull("processExitCode") ? cleanup.getInt("processExitCode") : null;
                String base = ProbeRunner.classifyOutcome(evaluated && sessionClosed, failure != null, run.reason != null,
                        cleanup.optBoolean("verified"), code, cleanup.optBoolean("exitedBeforeShutdown"),
                        cleanup.optBoolean("destroyRequested") || cleanup.optBoolean("forcedStopRequested"));
                String status = "native_initialize_passed".equals(base) ? "offline_v8_passed"
                        : "native_initialize_shutdown_uncertain".equals(base) ? "offline_v8_shutdown_uncertain" : "offline_v8_not_passed";
                report.put("helloCompleted", hello).put("sessionOpened", opened).put("v8ConstantEvaluated", evaluated)
                        .put("sessionClosed", sessionClosed).put("failure", failure == null ? JSONObject.NULL : failure)
                        .put("stopReason", run.reason == null ? JSONObject.NULL : run.reason).put("cleanup", cleanup)
                        .put("receivedFrames", reader == null ? new JSONArray() : reader.snapshot())
                        .put("stderr", stderr == null ? "" : stderr.snapshot()).put("elapsedMillis", SystemClock.elapsedRealtime() - started)
                        .put("status", status).put("summaryZh", "offline_v8_passed".equals(status)
                                ? "当前设备离线 V8 返回 v8-probe:2；不代表模型、MCP、exec-server 或手机动作通过。"
                                : "离线 V8 探针未通过；hello/session ready 不代表 V8 已执行，请检查阶段与原始帧。");
                save(report);
                synchronized (this) { if (active == run) active = null; }
                listener.complete(report.toString(2));
            } catch (Exception problem) {
                synchronized (this) { if (active == run) active = null; }
                listener.complete("{\"status\":\"offline_v8_evidence_generation_failed\"}");
            }
        }
    }

    private static void appendContent(JSONArray target, JSONArray additional) throws Exception {
        // The only fixed source emits exactly one item across all observations.
        if (target.length() + additional.length() > 1) throw new IOException("Unexpected extra V8 content");
        for (int i = 0; i < additional.length(); i++) target.put(additional.getJSONObject(i));
    }

    private void save(JSONObject report) throws Exception {
        AtomicFile file = new AtomicFile(reportFile());
        FileOutputStream output = null;
        try {
            output = file.startWrite(); output.write(report.toString(2).getBytes(StandardCharsets.UTF_8)); file.finishWrite(output);
        } catch (IOException failure) {
            if (output != null) file.failWrite(output);
            report.put("evidenceWriteError", failure.getClass().getSimpleName());
        }
    }
    private static JSONObject ok(JSONObject result) throws Exception {
        if (!"ok".equals(result.getString("status"))) throw new IOException("Helper operation failed: " + result.optString("message"));
        return result.getJSONObject("value");
    }
    private static JSONObject response(FrameReader reader, int id) throws Exception {
        JSONObject frame = reader.nextRelevant();
        if (!"operation/response".equals(frame.optString("type")) || !String.valueOf(id).equals(String.valueOf(frame.opt("id")))) throw new IOException("Unexpected helper response id/type");
        return ok(frame.getJSONObject("result"));
    }
    private static void send(OutputStream output, int id, JSONObject request) throws Exception {
        writeFrame(output, new JSONObject().put("type", "operation/request").put("id", id).put("request", request));
    }
    private static void writeFrame(OutputStream output, JSONObject value) throws IOException {
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > FRAME_CAP) throw new IOException("Outgoing helper frame cap exceeded");
        int n = bytes.length;
        output.write(new byte[] {(byte) n, (byte) (n >>> 8), (byte) (n >>> 16), (byte) (n >>> 24)});
        output.write(bytes); output.flush();
    }
    private static byte[] readBounded(InputStream input, int cap) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (bytes.size() + count > cap) throw new IOException("Input byte cap exceeded");
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }
    private static String elfAbi(File binary) throws IOException {
        byte[] header = new byte[20];
        try (DataInputStream input = new DataInputStream(new FileInputStream(binary))) { input.readFully(header); }
        if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F' || header[4] != 2 || header[5] != 1) throw new IOException("Expected little-endian 64-bit ELF");
        int machine = (header[18] & 255) | ((header[19] & 255) << 8);
        if (machine == 62) return "x86_64";
        if (machine == 183) return "arm64-v8a";
        throw new IOException("Unexpected helper ELF architecture");
    }
    private static String digest(File file, Run run) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) { run.check(); digest.update(buffer, 0, count); }
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private static final class Run {
        final long end;
        volatile Process process;
        volatile String reason;
        volatile boolean shuttingDown;
        private boolean forced;
        Run(long end) { this.end = end; }
        void check() throws IOException {
            if (reason != null) throw new IOException(reason);
            if (SystemClock.elapsedRealtime() >= end) { stop("total_work_timeout"); throw new IOException(reason); }
        }
        synchronized void publish(Process value) { process = value; if (reason != null) { forced = true; value.destroyForcibly(); } }
        synchronized void stop(String value) {
            if (reason == null) reason = value;
            if (process != null) { forced = true; process.destroyForcibly(); }
        }
        synchronized JSONObject cleanup() {
            JSONObject result = new JSONObject(); shuttingDown = true;
            try {
                if (process == null) return result.put("verified", true).put("processStarted", false);
                boolean exited = !process.isAlive();
                result.put("exitedBeforeShutdown", exited).put("stdinCloseRequested", true);
                if (exited) result.put("exitCodeBeforeShutdown", process.exitValue());
                try { process.getOutputStream().close(); } catch (IOException ignored) { }
                boolean destroy = false;
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) { destroy = true; process.destroy(); }
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) { forced = true; process.destroyForcibly(); }
                boolean stopped = process.waitFor(1000, TimeUnit.MILLISECONDS);
                result.put("verified", stopped).put("processStarted", true).put("destroyRequested", destroy)
                        .put("forcedStopRequested", forced).put("processExitCode", stopped ? process.exitValue() : JSONObject.NULL);
                if (!stopped) result.put("error", "Owned helper process termination unverified");
                try { process.getInputStream().close(); } catch (IOException ignored) { }
                try { process.getErrorStream().close(); } catch (IOException ignored) { }
            } catch (Exception failure) {
                try { result.put("verified", false).put("error", failure.getClass().getSimpleName()); }
                catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
            }
            return result;
        }
    }
    private static final class FrameReader implements Runnable {
        private final DataInputStream input;
        private final Run owner;
        private final ArrayBlockingQueue<JSONObject> queue = new ArrayBlockingQueue<>(32);
        private final JSONArray seen = new JSONArray();
        private final Thread thread;
        private volatile boolean done;
        private volatile String failure;
        FrameReader(InputStream input, Run owner) {
            this.input = new DataInputStream(input); this.owner = owner;
            thread = new Thread(this, "v8-probe-frames"); thread.setDaemon(true);
        }
        void start() { thread.start(); }
        @Override public void run() {
            int total = 0;
            try {
                while (true) {
                    int first = input.read(); if (first == -1) return;
                    byte[] rest = new byte[3]; input.readFully(rest);
                    long length = (long) first | ((long) (rest[0] & 255) << 8) | ((long) (rest[1] & 255) << 16) | ((long) (rest[2] & 255) << 24);
                    if (length == 0 || length > FRAME_CAP || total + 4 + length > FRAME_CAP) throw new IOException("Helper stdout frame/total byte cap exceeded");
                    total += 4 + (int) length;
                    byte[] body = new byte[(int) length]; input.readFully(body);
                    JSONObject frame = new JSONObject(new String(body, StandardCharsets.UTF_8));
                    synchronized (seen) { seen.put(frame); }
                    if (!queue.offer(frame)) throw new IOException("Helper stdout message cap exceeded");
                }
            } catch (Exception problem) {
                if (!owner.shuttingDown) { failure = problem.getClass().getSimpleName() + ": " + problem.getMessage(); owner.stop("helper_stdout_failed"); }
            } finally { done = true; }
        }
        JSONObject next() throws Exception {
            while (true) {
                owner.check();
                if (failure != null) throw new IOException(failure);
                JSONObject frame = queue.poll(50, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    if ("delegate/request".equals(frame.optString("type"))) throw new IOException("Unexpected delegate request with no enabled tools");
                    return frame;
                }
                if (done) throw new EOFException("Helper stdout ended before expected frame");
            }
        }
        JSONObject nextRelevant() throws Exception {
            while (true) { JSONObject frame = next(); if (!"cell/closed".equals(frame.optString("type"))) return frame; }
        }
        void finish() {
            try { thread.join(250); } catch (InterruptedException problem) { Thread.currentThread().interrupt(); }
            if (thread.isAlive()) owner.stop("helper_stdout_cleanup_unverified");
        }
        JSONArray snapshot() throws Exception { synchronized (seen) { return new JSONArray(seen.toString()); } }
    }
    private static final class StderrReader implements Runnable {
        private final InputStream input;
        private final Run owner;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final Thread thread;
        StderrReader(InputStream input, Run owner) {
            this.input = input; this.owner = owner;
            thread = new Thread(this, "v8-probe-stderr"); thread.setDaemon(true);
        }
        void start() { thread.start(); }
        @Override public void run() {
            byte[] buffer = new byte[2048];
            try {
                int count;
                while ((count = input.read(buffer)) != -1) synchronized (bytes) {
                    if (bytes.size() + count > STDERR_CAP) {
                        int remaining = STDERR_CAP - bytes.size();
                        if (remaining > 0) bytes.write(buffer, 0, remaining);
                        owner.stop("helper_stderr_byte_limit"); return;
                    }
                    bytes.write(buffer, 0, count);
                }
            } catch (IOException problem) {
                if (!owner.shuttingDown && owner.process != null && owner.process.isAlive()) owner.stop("helper_stderr_read_failed");
            }
        }
        void finish() {
            try { thread.join(250); } catch (InterruptedException problem) { Thread.currentThread().interrupt(); }
            if (thread.isAlive()) owner.stop("helper_stderr_cleanup_unverified");
        }
        String snapshot() { synchronized (bytes) { return new String(bytes.toByteArray(), StandardCharsets.UTF_8); } }
    }
}
