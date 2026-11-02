// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.runtimeprobe;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
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

/** No arbitrary commands, inherited credentials, network permission, or writable-code execution. */
final class ProbeRunner {
    interface Listener { void complete(String result); }
    private static final long STARTUP_MS = 15000;
    private static final long RESPONSE_MS = 10000;
    private static final int STDOUT_CAP = 65536;
    private static final int STDERR_CAP = 32768;
    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
    private Run active;
    private boolean closed;

    ProbeRunner(Context context) { this.context = context; }

    synchronized void start(Listener listener) {
        if (closed || active != null) return;
        Run run = new Run();
        active = run;
        worker.execute(() -> execute(run, listener));
    }

    synchronized void stop(String reason) {
        if (active != null) active.stop(reason);
    }

    synchronized void close() {
        closed = true;
        stop("activity_destroyed");
        worker.shutdown();
        // Let the worker finish bounded process cleanup and evidence writes.
        timers.shutdown();
    }

    String readLastReport() {
        try (InputStream input = new AtomicFile(new File(context.getFilesDir(), "runtime-probe-last.json")).openRead()) {
            return new String(readBounded(input, 524288), StandardCharsets.UTF_8);
        } catch (Exception ignored) { return null; }
    }

    private void execute(Run run, Listener listener) {
        long started = SystemClock.elapsedRealtime();
        JSONObject report = new JSONObject();
        Collector stdout = null;
        Collector stderr = null;
        ScheduledFuture<?> deadline = null;
        boolean initialized = false;
        String failure = null;
        try {
            report.put("schemaVersion", 1);
            report.put("applicationId", BuildConfig.APPLICATION_ID);
            report.put("sourceCommit", BuildConfig.SOURCE_COMMIT);
            report.put("sourceTreeClean", BuildConfig.SOURCE_TREE_CLEAN);
            report.put("version", BuildConfig.VERSION_NAME);
            report.put("startedEpochMillis", System.currentTimeMillis());
            report.put("androidApi", Build.VERSION.SDK_INT);
            report.put("deviceAbis", new JSONArray(Build.SUPPORTED_ABIS));
            report.put("pageSize", Os.sysconf(OsConstants._SC_PAGESIZE));
            report.put("scope", "Native app-server startup and initialize only");
            report.put("accountLoginTested", false);
            report.put("inferenceTested", false);
            report.put("v8OrHelpersTested", false);
            report.put("startupDeadlineMillis", STARTUP_MS);
            report.put("responseDeadlineMillis", RESPONSE_MS);
            deadline = timers.schedule(() -> run.stop("startup_timeout"), STARTUP_MS, TimeUnit.MILLISECONDS);
            JSONObject manifest;
            try (InputStream input = context.getAssets().open("runtime-manifest.json")) {
                manifest = new JSONObject(new String(readBounded(input, 65536), StandardCharsets.UTF_8));
            }
            if (manifest.getInt("schemaVersion") != 1 || !"rust-v0.166.0".equals(manifest.getString("upstreamTag"))) {
                throw new IOException("Unsupported runtime manifest");
            }
            report.put("runtimeManifest", manifest);
            File binary = new File(context.getApplicationInfo().nativeLibraryDir, "libcodex_app_server.so");
            if (!binary.isFile() || !binary.canExecute()) throw new IOException("Installed native executable missing");
            String abi = elfAbi(binary);
            boolean supported = false;
            for (String candidate : Build.SUPPORTED_ABIS) supported |= abi.equals(candidate);
            if (!supported) throw new IOException("Installed ELF ABI is not supported by this device");
            String expected = manifest.getJSONObject("appServer").getJSONObject(abi).getString("sha256");
            if (!expected.matches("[0-9a-f]{64}")) throw new IOException("Invalid expected SHA256");
            String actual = digest(binary);
            report.put("runtimeAbi", abi);
            report.put("nativePath", binary.getAbsolutePath());
            report.put("expectedSha256", expected);
            report.put("actualSha256", actual);
            if (!expected.equals(actual)) throw new IOException("Installed native SHA256 mismatch");
            report.put("nativeSha256Verified", true);
            run.check();
            File directory = new File(context.getNoBackupFilesDir(), "runtime-probe/" + UUID.randomUUID());
            File home = new File(directory, "home");
            File codexHome = new File(directory, "codex");
            File temporary = new File(directory, "tmp");
            if (!home.mkdirs() || !codexHome.mkdirs() || !temporary.mkdirs()) throw new IOException("Private run directory creation failed");
            ProcessBuilder builder = new ProcessBuilder(binary.getAbsolutePath(), "--listen", "stdio://");
            builder.directory(directory);
            Map<String, String> environment = builder.environment();
            environment.clear();
            environment.put("PATH", "/system/bin:/system/xbin");
            environment.put("ANDROID_ROOT", "/system");
            environment.put("ANDROID_DATA", "/data");
            environment.put("HOME", home.getAbsolutePath());
            environment.put("CODEX_HOME", codexHome.getAbsolutePath());
            environment.put("TMPDIR", temporary.getAbsolutePath());
            environment.put("LANG", "C.UTF-8");
            report.put("environmentKeys", new JSONArray(environment.keySet()));
            Process process = builder.start();
            run.publish(process);
            run.check();
            if (SystemClock.elapsedRealtime() - started > STARTUP_MS) throw new IOException("Startup deadline exceeded");
            deadline.cancel(false);
            deadline = timers.schedule(() -> run.stop("response_timeout"), RESPONSE_MS, TimeUnit.MILLISECONDS);
            report.put("processStartedMillis", SystemClock.elapsedRealtime() - started);
            stdout = new Collector(process.getInputStream(), STDOUT_CAP, true, run);
            stderr = new Collector(process.getErrorStream(), STDERR_CAP, false, run);
            stdout.start();
            stderr.start();
            JSONObject request = new JSONObject().put("id", 1).put("method", "initialize")
                    .put("params", new JSONObject().put("clientInfo", new JSONObject()
                            .put("name", "xiyan_android_runtime_probe").put("title", "Xiyan Android Runtime Probe")
                            .put("version", BuildConfig.VERSION_NAME))
                            .put("capabilities", new JSONObject().put("explicitGatewayOauth", true)));
            writeLine(process.getOutputStream(), request);
            long responseEnd = SystemClock.elapsedRealtime() + RESPONSE_MS;
            while (SystemClock.elapsedRealtime() < responseEnd) {
                run.check();
                String line = stdout.lines.poll(100, TimeUnit.MILLISECONDS);
                if (line == null) {
                    if (stdout.done) throw new IOException("stdout ended before initialize response");
                    continue;
                }
                JSONObject message = new JSONObject(line);
                if (!"1".equals(String.valueOf(message.opt("id")))) continue;
                report.put("initializeResponse", message);
                if (message.has("error")) throw new IOException("initialize returned JSON-RPC error");
                JSONObject result = message.getJSONObject("result");
                for (String key : new String[] {"userAgent", "codexHome", "platformFamily", "platformOs"}) result.getString(key);
                writeLine(process.getOutputStream(), new JSONObject().put("method", "initialized"));
                initialized = true;
                break;
            }
            if (!initialized) throw new IOException("Initialize response deadline exceeded");
        } catch (Exception problem) {
            failure = problem.getClass().getSimpleName() + ": " + problem.getMessage();
        } finally {
            if (deadline != null) deadline.cancel(false);
            JSONObject cleanup = run.cleanup();
            if (stdout != null) stdout.finish();
            if (stderr != null) stderr.finish();
            try {
                report.put("initializeCompleted", initialized);
                report.put("stopReason", run.reason == null ? JSONObject.NULL : run.reason);
                report.put("failure", failure == null ? JSONObject.NULL : failure);
                report.put("cleanup", cleanup);
                report.put("stdout", stdout == null ? "" : stdout.snapshot());
                report.put("stderr", stderr == null ? "" : stderr.snapshot());
                report.put("elapsedMillis", SystemClock.elapsedRealtime() - started);
                boolean passed = initialized && failure == null && run.reason == null && cleanup.optBoolean("verified");
                report.put("status", passed ? "native_initialize_passed" : "native_initialize_not_passed");
                report.put("summaryZh", passed ? "当前设备原生 initialize 完成；不代表 V8、登录、推理或其他 ABI 通过。" : "原生初始化探针未通过；请检查 failure、stopReason 与 stderr。");
                String text = report.toString(2);
                AtomicFile file = new AtomicFile(new File(context.getFilesDir(), "runtime-probe-last.json"));
                FileOutputStream output = null;
                try {
                    output = file.startWrite();
                    output.write(text.getBytes(StandardCharsets.UTF_8));
                    file.finishWrite(output);
                } catch (IOException saveFailure) {
                    if (output != null) file.failWrite(output);
                    report.put("evidenceWriteError", saveFailure.getClass().getSimpleName());
                    text = report.toString(2);
                }
                synchronized (this) { if (active == run) active = null; }
                listener.complete(text);
            } catch (Exception impossible) {
                synchronized (this) { if (active == run) active = null; }
                listener.complete("{\"status\":\"evidence_generation_failed\"}");
            }
        }
    }

    private static void writeLine(OutputStream output, JSONObject json) throws IOException {
        output.write((json.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static byte[] readBounded(InputStream input, int cap) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (result.size() + count > cap) throw new IOException("Input byte limit exceeded");
            result.write(buffer, 0, count);
        }
        return result.toByteArray();
    }

    private static String elfAbi(File binary) throws IOException {
        byte[] header = new byte[20];
        try (FileInputStream input = new FileInputStream(binary)) {
            if (input.read(header) != header.length) throw new IOException("ELF header is truncated");
        }
        if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F' || header[4] != 2 || header[5] != 1) throw new IOException("Expected little-endian 64-bit ELF");
        int machine = (header[18] & 255) | ((header[19] & 255) << 8);
        if (machine == 62) return "x86_64";
        if (machine == 183) return "arm64-v8a";
        throw new IOException("Unexpected ELF architecture");
    }

    private static String digest(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private static final class Run {
        private volatile Process process;
        volatile String reason;
        private boolean forcedStopRequested;
        synchronized void publish(Process child) {
            process = child;
            if (reason != null) { forcedStopRequested = true; child.destroyForcibly(); }
        }
        synchronized void stop(String value) {
            if (reason == null) reason = value;
            if (process != null) { forcedStopRequested = true; process.destroyForcibly(); }
        }
        void check() throws IOException { if (reason != null) throw new IOException(reason); }
        synchronized JSONObject cleanup() {
            JSONObject result = new JSONObject();
            try {
                if (process == null) return result.put("verified", true).put("processStarted", false);
                try { process.getOutputStream().close(); } catch (IOException ignored) { }
                boolean destroyRequested = false;
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                    destroyRequested = true;
                    process.destroy();
                }
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                    forcedStopRequested = true;
                    process.destroyForcibly();
                }
                boolean stopped = process.waitFor(1000, TimeUnit.MILLISECONDS);
                result.put("verified", stopped).put("processStarted", true);
                result.put("destroyRequested", destroyRequested).put("forcedStopRequested", forcedStopRequested);
                result.put("processExitCode", stopped ? process.exitValue() : JSONObject.NULL);
                if (!stopped) result.put("error", "Own process termination remains unverified");
                try { process.getInputStream().close(); } catch (IOException ignored) { }
                try { process.getErrorStream().close(); } catch (IOException ignored) { }
            } catch (Exception failure) {
                try { result.put("verified", false).put("error", failure.getClass().getSimpleName()); }
                catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
            }
            return result;
        }
    }

    private static final class Collector implements Runnable {
        final ArrayBlockingQueue<String> lines = new ArrayBlockingQueue<>(64);
        private final InputStream input;
        private final int cap;
        private final boolean parseLines;
        private final Run owner;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final Thread thread;
        volatile boolean done;
        Collector(InputStream input, int cap, boolean parseLines, Run owner) {
            this.input = input; this.cap = cap; this.parseLines = parseLines; this.owner = owner;
            thread = new Thread(this, parseLines ? "probe-stdout" : "probe-stderr");
            thread.setDaemon(true);
        }
        void start() { thread.start(); }
        @Override public void run() {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            try {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    synchronized (bytes) {
                        if (bytes.size() + count > cap) {
                            int remaining = cap - bytes.size();
                            if (remaining > 0) bytes.write(buffer, 0, remaining);
                            owner.stop(parseLines ? "stdout_byte_limit" : "stderr_byte_limit");
                            return;
                        }
                        bytes.write(buffer, 0, count);
                    }
                    if (parseLines) for (int i = 0; i < count; i++) {
                        if (buffer[i] == '\n') {
                            String value = line.toString(StandardCharsets.UTF_8.name()).trim();
                            line.reset();
                            if (!value.isEmpty() && !lines.offer(value)) { owner.stop("stdout_message_limit"); return; }
                        } else line.write(buffer[i]);
                    }
                }
                if (parseLines && line.size() > 0 && !lines.offer(line.toString(StandardCharsets.UTF_8.name()))) owner.stop("stdout_message_limit");
            } catch (IOException failure) {
                // Closing the owned process streams during cleanup is expected.
                if (owner.reason == null && owner.process != null && owner.process.isAlive()) owner.stop("stream_read_failed");
            } finally { done = true; }
        }
        void finish() {
            try { thread.join(500); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            if (thread.isAlive()) owner.stop("stream_cleanup_unverified");
        }
        String snapshot() { synchronized (bytes) { return new String(bytes.toByteArray(), StandardCharsets.UTF_8); } }
    }
}
