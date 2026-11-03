// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.runtimeprobe;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.ErrnoException;
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
        start(listener, false);
    }

    synchronized void startMcp(Listener listener) {
        start(listener, true);
    }

    private synchronized void start(Listener listener, boolean mcp) {
        if (closed || active != null) return;
        Run run = new Run(mcp);
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
        return readReport("runtime-probe-last.json");
    }

    String readLastMcpReport() {
        return readReport("mcp-probe-last.json");
    }

    private String readReport(String name) {
        try (InputStream input = new AtomicFile(new File(context.getFilesDir(), name)).openRead()) {
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
        boolean mcpCatalogVerified = false;
        boolean mcpCallVerified = false;
        File directory = null;
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
            report.put("scope", run.mcp ? "Offline MCP catalog and fixed echo tool call in a fresh ephemeral thread; no model turn" : "Native app-server startup and initialize only");
            report.put("accountLoginTested", false);
            report.put("inferenceTested", false);
            report.put("v8Tested", false);
            report.put("codexNativeHelpersTested", false);
            report.put("mcpFixtureTested", run.mcp);
            report.put("mcpToolCallTested", false);
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
            directory = new File(context.getNoBackupFilesDir(), "runtime-probe/" + UUID.randomUUID());
            File home = new File(directory, "home");
            File codexHome = new File(directory, "codex");
            File temporary = new File(directory, "tmp");
            if (!home.mkdirs() || !codexHome.mkdirs() || !temporary.mkdirs()) throw new IOException("Private run directory creation failed");
            if (run.mcp) writeMcpConfig(codexHome, directory);
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
            if (run.mcp) {
                writeLine(process.getOutputStream(), new JSONObject().put("id", 2).put("method", "mcpServerStatus/list")
                        .put("params", new JSONObject().put("serverName", "xiyan_probe").put("detail", "toolsAndAuthOnly")));
                JSONObject response = awaitResponse(stdout, run, 2, SystemClock.elapsedRealtime() + RESPONSE_MS);
                report.put("mcpCatalogResponse", response);
                if (response.has("error")) throw new IOException("MCP catalog RPC returned an error");
                JSONArray servers = response.getJSONObject("result").getJSONArray("data");
                if (servers.length() != 1) throw new IOException("Unexpected MCP server count");
                JSONObject server = servers.getJSONObject(0);
                if (!"xiyan_probe".equals(server.getString("name")) || !server.isNull("toolsError")) {
                    throw new IOException("MCP fixture discovery failed");
                }
                JSONObject tools = server.getJSONObject("tools");
                JSONObject tool = tools.getJSONObject("xiyan_echo");
                if (tools.length() != 1 || !"xiyan_echo".equals(tool.getString("name"))
                        || !"xiyan-offline-fixture".equals(server.getJSONObject("serverInfo").getString("name"))) {
                    throw new IOException("Unexpected MCP fixture catalog");
                }
                mcpCatalogVerified = true;
                // Upstream thread creation can schedule auth prewarm. This APK
                // has no INTERNET permission and this CODEX_HOME has no credentials.
                // Do not alter provider identity or manufacture auth to make it pass.
                String probeCwd = directory.getCanonicalPath();
                writeLine(process.getOutputStream(), new JSONObject().put("id", 3).put("method", "thread/start")
                        .put("params", new JSONObject().put("cwd", probeCwd).put("ephemeral", true)));
                JSONObject threadResponse = awaitResponse(stdout, run, 3, SystemClock.elapsedRealtime() + RESPONSE_MS);
                report.put("threadStartResponse", threadResponse);
                if (threadResponse.has("error")) throw new IOException("Offline thread/start returned an error");
                JSONObject threadResult = threadResponse.getJSONObject("result");
                JSONObject thread = threadResult.getJSONObject("thread");
                String threadId = thread.getString("id");
                if (threadId.isEmpty() || !thread.getBoolean("ephemeral") || !thread.isNull("path")
                        || !probeCwd.equals(thread.getString("cwd"))
                        || !probeCwd.equals(threadResult.getString("cwd"))) {
                    throw new IOException("Unexpected ephemeral thread identity or directory");
                }
                report.put("mcpToolCallTested", true);
                writeLine(process.getOutputStream(), new JSONObject().put("id", 4).put("method", "mcpServer/tool/call")
                        .put("params", new JSONObject().put("threadId", threadId).put("server", "xiyan_probe")
                                .put("tool", "xiyan_echo").put("arguments", new JSONObject().put("text", "xiyan-mcp-probe"))));
                JSONObject callResponse = awaitResponse(stdout, run, 4, SystemClock.elapsedRealtime() + RESPONSE_MS);
                report.put("mcpToolCallResponse", callResponse);
                if (callResponse.has("error")) throw new IOException("Offline MCP tool call returned an error");
                JSONObject callResult = callResponse.getJSONObject("result");
                JSONArray content = callResult.getJSONArray("content");
                if (!(callResult.opt("isError") instanceof Boolean) || callResult.getBoolean("isError") || content.length() != 1
                        || !"text".equals(content.getJSONObject(0).getString("type"))
                        || !"xiyan-mcp-probe".equals(content.getJSONObject(0).getString("text"))) {
                    throw new IOException("MCP fixed echo output mismatch");
                }
                mcpCallVerified = true;
            }
        } catch (Exception problem) {
            failure = problem.getClass().getSimpleName() + ": " + problem.getMessage();
        } finally {
            if (deadline != null) deadline.cancel(false);
            JSONObject cleanup = run.cleanup();
            if (stdout != null) stdout.finish();
            if (stderr != null) stderr.finish();
            try {
                report.put("initializeCompleted", initialized);
                if (run.mcp) {
                    report.put("mcpCatalogVerified", mcpCatalogVerified);
                    report.put("mcpToolCallVerified", mcpCallVerified);
                    JSONObject fixture;
                    try { fixture = verifyFixtureStopped(directory); }
                    catch (Exception error) { fixture = new JSONObject().put("verified", false)
                            .put("error", error.getClass().getSimpleName() + ": " + error.getMessage()); }
                    report.put("mcpFixtureCleanup", fixture);
                    if (!fixture.optBoolean("verified")) failure = failure == null ? "MCP fixture cleanup unverified" : failure;
                }
                report.put("stopReason", run.reason == null ? JSONObject.NULL : run.reason);
                report.put("failure", failure == null ? JSONObject.NULL : failure);
                report.put("cleanup", cleanup);
                report.put("stdout", stdout == null ? "" : stdout.snapshot());
                report.put("stderr", stderr == null ? "" : stderr.snapshot());
                report.put("elapsedMillis", SystemClock.elapsedRealtime() - started);
                Integer exitCode = cleanup.has("processExitCode") && !cleanup.isNull("processExitCode")
                        ? cleanup.getInt("processExitCode") : null;
                String status = classifyOutcome(initialized, failure != null, run.reason != null,
                        cleanup.optBoolean("verified"), exitCode,
                        cleanup.optBoolean("exitedBeforeShutdown"), cleanup.optBoolean("destroyRequested")
                                || cleanup.optBoolean("forcedStopRequested"));
                if (run.mcp) status = mcpCatalogVerified && mcpCallVerified && "native_initialize_passed".equals(status)
                        ? "offline_mcp_tool_call_passed" : "offline_mcp_tool_call_not_passed";
                report.put("status", status);
                report.put("summaryZh", run.mcp
                        ? ("offline_mcp_tool_call_passed".equals(status) ? "离线 MCP 固定回显工具调用通过；未发起模型回合或账号登录。" : "离线 MCP 工具回合未通过；请检查目录、线程、调用与清理证据。")
                        : "native_initialize_passed".equals(status)
                        ? "当前设备原生 initialize 完成；不代表 V8、登录、推理或其他 ABI 通过。"
                        : "native_initialize_shutdown_uncertain".equals(status)
                        ? "initialize 已完成，但关闭输入后出现非零退出，退出原因未能确定；未标为通过。"
                        : "原生初始化探针未通过；请检查 failure、stopReason、cleanup 与 stderr。");
                String text = report.toString(2);
                AtomicFile file = new AtomicFile(new File(context.getFilesDir(), run.mcp ? "mcp-probe-last.json" : "runtime-probe-last.json"));
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

    private void writeMcpConfig(File codexHome, File directory) throws Exception {
        StringBuilder config = new StringBuilder("[mcp_servers.xiyan_probe]\ncommand = \"/system/bin/app_process\"\n")
                .append("args = [\"/system/bin\", \"io.xiyan.agent.runtimeprobe.LocalMcpFixture\"]\n")
                .append("startup_timeout_sec = 5\ntool_timeout_sec = 5\n[mcp_servers.xiyan_probe.env]\n")
                .append("CLASSPATH = ").append(tomlPath(context.getApplicationInfo().sourceDir)).append('\n')
                .append("ANDROID_ROOT = \"/system\"\nANDROID_DATA = \"/data\"\n")
                .append("XIYAN_MCP_PROBE_DIR = ").append(tomlPath(directory.getAbsolutePath())).append('\n');
        for (String name : new String[] {"BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH", "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT", "ANDROID_RUNTIME_ROOT"}) {
            String value = System.getenv(name);
            if (value != null && !value.isEmpty()) config.append(name).append(" = ").append(tomlPath(value)).append('\n');
        }
        try (FileOutputStream output = new FileOutputStream(new File(codexHome, "config.toml"))) {
            output.write(config.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String tomlPath(String value) throws IOException {
        if (!value.matches("[/A-Za-z0-9_~+=.:-]+")) throw new IOException("Unexpected character in installed Android path");
        return "\"" + value + "\"";
    }

    private static JSONObject awaitResponse(Collector stdout, Run run, int id, long end) throws Exception {
        while (SystemClock.elapsedRealtime() < end) {
            run.check();
            String line = stdout.lines.poll(100, TimeUnit.MILLISECONDS);
            if (line == null) {
                if (stdout.done) throw new IOException("stdout ended before RPC response");
                continue;
            }
            JSONObject message = new JSONObject(line);
            if (String.valueOf(id).equals(String.valueOf(message.opt("id")))) return message;
        }
        throw new IOException("RPC response deadline exceeded");
    }

    private static JSONObject verifyFixtureStopped(File directory) throws Exception {
        JSONObject result = new JSONObject();
        File pids = directory == null ? null : new File(directory, "mcp-fixture-pids.txt");
        if (pids == null || !pids.isFile()) return result.put("verified", false).put("reason", "fixture_pid_not_recorded");
        String text;
        try (InputStream input = new FileInputStream(pids)) { text = new String(readBounded(input, 1024), StandardCharsets.UTF_8); }
        JSONArray records = new JSONArray();
        boolean verified = true;
        String[] pidLines = text.trim().split("\\s+");
        if (pidLines.length > 4) throw new IOException("Unexpected fixture process count");
        for (String line : pidLines) {
            int pid = Integer.parseInt(line);
            if (pid <= 0) throw new IOException("Invalid fixture PID");
            JSONObject record = new JSONObject().put("pid", pid);
            boolean stopped = waitForFixtureExit(pid, 300);
            if (!stopped) {
                // A cancelled app-server may not finish its own child cleanup.
                // Signal only a process whose UID and command still identify our fixture.
                boolean ownFixture = Os.stat("/proc/" + pid).st_uid == android.os.Process.myUid();
                try (InputStream input = new FileInputStream("/proc/" + pid + "/cmdline")) {
                    ownFixture &= new String(readBounded(input, 4096), StandardCharsets.UTF_8)
                            .contains("io.xiyan.agent.runtimeprobe.LocalMcpFixture");
                }
                record.put("ownFixtureIdentityVerified", ownFixture);
                if (ownFixture) {
                    Os.kill(pid, OsConstants.SIGTERM);
                    record.put("termRequested", true);
                    stopped = waitForFixtureExit(pid, 500);
                    if (!stopped) {
                        Os.kill(pid, OsConstants.SIGKILL);
                        record.put("killRequested", true);
                        stopped = waitForFixtureExit(pid, 500);
                    }
                }
            }
            records.put(record.put("stopped", stopped));
            verified &= stopped;
        }
        File events = new File(directory, "mcp-fixture-events.txt");
        if (events.isFile()) try (InputStream input = new FileInputStream(events)) {
            result.put("events", new String(readBounded(input, 4096), StandardCharsets.UTF_8));
        }
        return result.put("verified", verified).put("processes", records);
    }

    private static boolean waitForFixtureExit(int pid, long millis) throws Exception {
        long end = SystemClock.elapsedRealtime() + millis;
        do {
            try { Os.kill(pid, 0); }
            catch (ErrnoException error) {
                if (error.errno == OsConstants.ESRCH) return true;
                throw error;
            }
            Thread.sleep(50);
        } while (SystemClock.elapsedRealtime() < end);
        return false;
    }

    /** Classifies observations, without attributing an exit to a signal we cannot observe. */
    static String classifyOutcome(boolean initialized, boolean failed, boolean stopped,
            boolean cleanupVerified, Integer exitCode, boolean exitedBeforeShutdown,
            boolean terminationRequested) {
        if (!initialized || failed || stopped || !cleanupVerified || exitCode == null) {
            return "native_initialize_not_passed";
        }
        if (exitCode == 0) return "native_initialize_passed";
        if (exitedBeforeShutdown) return "native_initialize_not_passed";
        // A nonzero code after our destroy/force request is not evidence of a
        // native startup failure. The probe's scope is the completed handshake.
        if (terminationRequested) return "native_initialize_passed";
        // Process can exit between isAlive() and closing stdin. We cannot tell
        // a concurrent crash from shutdown on EOF, so do not claim a pass.
        return "native_initialize_shutdown_uncertain";
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
        final boolean mcp;
        private volatile Process process;
        volatile String reason;
        private volatile boolean forcedStopRequested;
        Run(boolean mcp) { this.mcp = mcp; }
        synchronized void publish(Process child) {
            process = child;
            if (reason != null) { forcedStopRequested = true; child.destroyForcibly(); }
        }
        synchronized void stop(String value) {
            if (reason == null) reason = value;
            if (process != null) { forcedStopRequested = true; process.destroyForcibly(); }
        }
        void check() throws IOException { if (reason != null) throw new IOException(reason); }
        JSONObject cleanup() {
            JSONObject result = new JSONObject();
            try {
                if (process == null) return result.put("verified", true).put("processStarted", false);
                boolean exitedBeforeShutdown = !process.isAlive();
                result.put("exitedBeforeShutdown", exitedBeforeShutdown);
                if (exitedBeforeShutdown) result.put("exitCodeBeforeShutdown", process.exitValue());
                result.put("stdinCloseRequested", true);
                try { process.getOutputStream().close(); } catch (IOException ignored) { }
                boolean destroyRequested = false;
                // The upstream EOF watchdog allows 45 seconds for normal shutdown.
                // Do not hold this Run's monitor while waiting: UI stop must remain responsive.
                long gracefulWait = mcp ? 50000 : 500;
                result.put("gracefulExitWaitMillis", gracefulWait);
                if (!process.waitFor(gracefulWait, TimeUnit.MILLISECONDS)) {
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
