// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.account;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.AtomicFile;
import io.xiyan.agent.core.auth.LoginCallbackHandoff;
import io.xiyan.agent.core.auth.PendingSignIn;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.security.KeyStore;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.AEADBadTagException;
import javax.crypto.SecretKey;
import org.json.JSONArray;
import org.json.JSONObject;

/** Platform-only instrumentation. Every file and key belongs to a new synthetic test namespace. */
public final class CredentialStoreInstrumentation extends Instrumentation {
    private interface Check { void run() throws Exception; }
    private final JSONArray cases = new JSONArray();
    private int failures;
    private boolean localLoopbackUsed;
    @Override public void onCreate(Bundle input) { super.onCreate(input); start(); }

    @Override public void onStart() {
        runCheck("keystore_aes_gcm_round_trip", this::roundTrip);
        runCheck("ciphertext_tamper_rejected", this::tamperRejected);
        runCheck("clear_isolates_test_namespace", this::clearIsolation);
        runCheck("legacy_atomic_backup_restored", this::legacyBackupRestored);
        runCheck("pending_encrypted_attempt_restored", this::pendingRestored);
        runCheck("pending_consumed_tombstone_rejected", this::pendingConsumed);
        runCheck("loopback_original_port_and_manual_ack_recovery", this::loopbackRecovery);
        runCheck("login_foreground_lease_lifecycle", this::foregroundLifecycle);
        Bundle result = new Bundle();
        result.putString("status", failures == 0 ? "passed" : "failed");
        result.putInt("tests", cases.length());
        result.putInt("failures", failures);
        result.putInt("androidApi", Build.VERSION.SDK_INT);
        result.putString("scope", "Synthetic Keystore, pending authorization, local loopback and foreground service only");
        result.putBoolean("accountCredentialsRead", false);
        // The original networkRequested field describes external service requests.
        result.putBoolean("networkRequested", false);
        result.putBoolean("externalNetworkRequested", false);
        result.putBoolean("localLoopbackUsed", localLoopbackUsed);
        result.putString("cases", cases.toString());
        finish(failures == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void runCheck(String name, Check test) {
        JSONObject result = new JSONObject();
        try {
            result.put("name", name);
            test.run();
            result.put("passed", true);
        } catch (Throwable failure) {
            failures++;
            try { result.put("passed", false).put("failureClass", failure.getClass().getSimpleName()); }
            catch (org.json.JSONException impossible) { throw new AssertionError("Test result serialization failed"); }
        }
        cases.put(result);
    }

    private void roundTrip() throws Exception {
        Fixture fixture = new Fixture(getTargetContext());
        try {
            JSONObject synthetic = synthetic("round-trip");
            fixture.store.write(synthetic);
            JSONObject restored = new SessionStore(getTargetContext(), fixture.namespace).read();
            require(restored != null && synthetic.toString().equals(restored.toString()));
            String encrypted = readFile(fixture.file);
            require(!encrypted.contains("synthetic-access-token") && !encrypted.contains("synthetic-refresh-token"));
            JSONObject envelope = new JSONObject(encrypted);
            require(envelope.getInt("version") == 1 && Base64.getDecoder().decode(envelope.getString("iv")).length == 12);
            SecretKey secret = (SecretKey) keyStore().getKey(fixture.alias, null);
            require(secret != null && secret.getEncoded() == null);
        } finally { fixture.clean(); }
    }

    private void tamperRejected() throws Exception {
        Fixture fixture = new Fixture(getTargetContext());
        try {
            fixture.store.write(synthetic("tamper"));
            JSONObject envelope = new JSONObject(readFile(fixture.file));
            byte[] ciphertext = Base64.getDecoder().decode(envelope.getString("data"));
            require(ciphertext.length > 16);
            ciphertext[ciphertext.length - 1] ^= 1;
            envelope.put("data", Base64.getEncoder().encodeToString(ciphertext));
            AtomicFile changed = new AtomicFile(fixture.file);
            FileOutputStream output = null;
            try {
                output = changed.startWrite();
                output.write(envelope.toString().getBytes(StandardCharsets.UTF_8));
                changed.finishWrite(output);
            } catch (Exception failure) { if (output != null) changed.failWrite(output); throw failure; }
            boolean rejected = false;
            try { fixture.store.read(); }
            catch (AEADBadTagException expected) { rejected = true; }
            require(rejected);
        } finally { fixture.clean(); }
    }

    private void clearIsolation() throws Exception {
        Fixture first = new Fixture(getTargetContext());
        Fixture second = new Fixture(getTargetContext());
        try {
            first.store.write(synthetic("first"));
            JSONObject retained = synthetic("second");
            second.store.write(retained);
            require(keyStore().containsAlias(first.alias) && keyStore().containsAlias(second.alias));
            first.store.clear();
            require(!first.file.exists() && !keyStore().containsAlias(first.alias) && first.store.read() == null);
            require(second.file.isFile() && keyStore().containsAlias(second.alias));
            require(retained.toString().equals(second.store.read().toString()));
        } finally {
            try { first.clean(); } finally { second.clean(); }
        }
    }

    private void legacyBackupRestored() throws Exception {
        Fixture fixture = new Fixture(getTargetContext());
        File backup = new File(fixture.file.getPath() + ".bak");
        try {
            JSONObject original = synthetic("legacy-backup");
            fixture.store.write(original);
            require(!backup.exists() && fixture.file.renameTo(backup));
            require(!fixture.file.exists() && backup.isFile());
            JSONObject restored = new SessionStore(getTargetContext(), fixture.namespace).read();
            require(restored != null && original.toString().equals(restored.toString()));
            require(fixture.file.isFile() && !backup.exists());
        } finally { fixture.clean(); }
    }

    private void pendingRestored() throws Exception {
        PendingFixture fixture = new PendingFixture(getTargetContext());
        try (ServerSocket seed = loopback()) {
            PendingLoginStore.Record original = fixture.record(seed.getLocalPort());
            fixture.store.save(original);
            PendingLoginStore.Record restored = new PendingLoginStore(getTargetContext(), fixture.namespace).read();
            require(restored != null && original.id.equals(restored.id));
            require(original.pending.authorizationUri().equals(restored.pending.authorizationUri()));
            require(original.pending.getRedirectUri().equals(restored.pending.getRedirectUri()));
            require(original.pending.getNonce().equals(restored.pending.getNonce()));
            require(original.pending.getCodeVerifier().equals(restored.pending.getCodeVerifier()));
            require(original.pending.getExpiresAtEpochSeconds() == restored.pending.getExpiresAtEpochSeconds());
            require(original.createdElapsedMillis == restored.createdElapsedMillis && restored.remainingMillis() > 0);
            String encrypted = readFile(fixture.file);
            require(!encrypted.contains(original.pending.getNonce()) && !encrypted.contains(original.pending.getCodeVerifier())
                    && !encrypted.contains("synthetic-subject-only"));
            SecretKey key = (SecretKey) keyStore().getKey(fixture.alias, null);
            require(key != null && key.getEncoded() == null);
        } finally { fixture.clean(); }
    }

    private void pendingConsumed() throws Exception {
        PendingFixture fixture = new PendingFixture(getTargetContext());
        try (ServerSocket seed = loopback()) {
            PendingLoginStore.Record original = fixture.record(seed.getLocalPort());
            fixture.store.save(original); fixture.store.markConsumed(original.id);
            JSONObject tombstone = fixture.raw.read();
            require(tombstone.length() == 3 && "consumed".equals(tombstone.getString("phase")) && !tombstone.has("pending"));
            boolean rejected = false;
            try { new PendingLoginStore(getTargetContext(), fixture.namespace).read(); }
            catch (LoginFailure consumed) { rejected = consumed.reason == LoginFailure.Reason.PENDING_CONSUMED; }
            require(rejected && !fixture.file.exists() && !keyStore().containsAlias(fixture.alias));
            PendingLoginStore.Record newer = fixture.record(seed.getLocalPort());
            fixture.store.save(newer);
            fixture.store.clearIfMatches(original.id);
            require(newer.id.equals(fixture.store.read().id));
        } finally { fixture.clean(); }
    }

    private void loopbackRecovery() throws Exception {
        PendingFixture fixture = new PendingFixture(getTargetContext());
        ExecutorService listener = Executors.newSingleThreadExecutor();
        LoginFlow flow = null;
        try {
            PendingLoginStore.Record original;
            try (ServerSocket seed = loopback()) {
                original = fixture.record(seed.getLocalPort()); fixture.store.save(original);
            }
            PendingLoginStore.Record restored = new PendingLoginStore(getTargetContext(), fixture.namespace).read();
            require(restored.pending.getRedirectUri().equals(original.pending.getRedirectUri()));
            flow = LoginFlow.restore(restored, false);
            LoginFlow listening = flow;
            Future<PendingSignIn.Callback> received = listener.submit(() -> listening.await(() -> {
                if (Thread.currentThread().isInterrupted()) throw new java.io.IOException("Synthetic test interrupted");
            }));
            URI callback = URI.create(callback(restored.pending));
            try (Socket browser = new Socket()) {
                browser.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), callback.getPort()), 2000);
                browser.setSoTimeout(5000);
                String request = "GET " + callback.getRawPath() + "?" + callback.getRawQuery()
                        + " HTTP/1.1\r\nHost: " + callback.getRawAuthority() + "\r\n\r\n";
                browser.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                browser.getOutputStream().flush();
                ByteArrayOutputStream response = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int count;
                while ((count = browser.getInputStream().read(buffer)) != -1) {
                    require(response.size() + count <= 8192); response.write(buffer, 0, count);
                }
                require(new String(response.toByteArray(), StandardCharsets.UTF_8).startsWith("HTTP/1.1 200 OK\r\n"));
            }
            require("synthetic-code-only".equals(received.get(8, TimeUnit.SECONDS).getCode()));
            fixture.store.markConsumed(restored.id); fixture.store.clearIfMatches(restored.id); flow.close(); flow = null;

            try (ServerSocket seed = loopback()) {
                PendingLoginStore.Record manual = fixture.record(seed.getLocalPort()); fixture.store.save(manual);
                flow = LoginFlow.restore(new PendingLoginStore(getTargetContext(), fixture.namespace).read(), true);
                flow.submit(callback(manual.pending));
                require("synthetic-code-only".equals(flow.await(() -> { }).getCode()));
                fixture.store.markConsumed(manual.id); fixture.store.clearIfMatches(manual.id); flow.close(); flow = null;

                PendingSignIn acknowledgement = fixture.record(seed.getLocalPort()).pending;
                LoginCallbackHandoff handoff = new LoginCallbackHandoff(acknowledgement);
                PendingSignIn.Callback accepted = handoff.acceptAndAcknowledge(callback(acknowledgement),
                        System.currentTimeMillis() / 1000, () -> { throw new java.io.IOException("Synthetic browser disconnect"); });
                require(handoff.acknowledgementFailed() && handoff.received() == accepted
                        && "synthetic-code-only".equals(accepted.getCode()));
            }
        } finally {
            if (flow != null) flow.close(); listener.shutdownNow();
            try { require(listener.awaitTermination(5, TimeUnit.SECONDS)); }
            finally { fixture.clean(); }
        }
    }

    private void foregroundLifecycle() throws Exception {
        AtomicReference<AccountLoginService.Lease> acquired = new AtomicReference<>();
        AtomicReference<LoginFailure.Reason> interruption = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        try {
            runOnMainSync(() -> acquired.set(AccountLoginService.begin(getTargetContext(),
                    () -> cancelled.set(true), interruption::set)));
            AccountLoginService.Lease lease = acquired.get(); require(lease != null);
            lease.awaitReady();
            require(AccountLoginService.syntheticLeaseForeground(lease) && interruption.get() == null);
            runOnMainSync(lease::close);
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (!AccountLoginService.syntheticLeaseDestroyed(lease) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
            require(AccountLoginService.syntheticLeaseDestroyed(lease) && interruption.get() == null && !cancelled.get());
        } finally {
            AccountLoginService.Lease lease = acquired.get();
            if (lease != null) runOnMainSync(lease::close);
        }
    }

    private ServerSocket loopback() throws Exception {
        ServerSocket server = new ServerSocket();
        try {
            server.setReuseAddress(false);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)); localLoopbackUsed = true;
            return server;
        } catch (Exception failure) { server.close(); throw failure; }
    }
    private static String callback(PendingSignIn pending) throws Exception {
        for (String field : pending.authorizationUri().getRawQuery().split("&")) {
            if (field.startsWith("state=")) return pending.getRedirectUri() + "?state="
                    + URLDecoder.decode(field.substring(6), StandardCharsets.UTF_8.name()) + "&code=synthetic-code-only";
        }
        throw new AssertionError("Synthetic callback state missing");
    }

    private static JSONObject synthetic(String marker) throws Exception {
        return new JSONObject().put("version", 1).put("marker", marker)
                .put("accessToken", "synthetic-access-token-only").put("refreshToken", "synthetic-refresh-token-only");
    }

    private static KeyStore keyStore() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null); return store;
    }

    private static String readFile(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream body = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024]; int count;
            while ((count = input.read(buffer)) != -1) {
                require(body.size() + count <= 8192); body.write(buffer, 0, count);
            }
            return new String(body.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void require(boolean condition) { if (!condition) throw new AssertionError("Synthetic credential check failed"); }

    private static final class Fixture {
        final String namespace = UUID.randomUUID().toString();
        final String alias = "xiyan.siwc.test." + namespace;
        final File file;
        final SessionStore store;
        Fixture(Context context) {
            file = new File(context.getNoBackupFilesDir(), "account-tests/" + namespace + "/siwc-session.bin");
            store = new SessionStore(context, namespace);
        }
        void clean() throws Exception {
            store.clear();
            require(!file.exists() && !new File(file.getPath() + ".bak").exists() && !keyStore().containsAlias(alias));
            File directory = file.getParentFile();
            if (directory != null && directory.isDirectory()) require(directory.delete());
        }
    }

    private static final class PendingFixture {
        final String namespace = UUID.randomUUID().toString();
        final String alias = "xiyan.siwc.pending.test." + namespace;
        final File file;
        final SessionStore raw;
        final PendingLoginStore store;
        PendingFixture(Context context) {
            file = new File(context.getNoBackupFilesDir(), "account-tests/" + namespace + "/siwc-pending.bin");
            raw = SessionStore.pendingAuthorizationTest(context, namespace);
            store = new PendingLoginStore(context, namespace);
        }
        PendingLoginStore.Record record(int boundPort) {
            PendingSignIn pending = PendingSignIn.create(URI.create("https://auth.openai.com/api/accounts/authorize"), boundPort,
                    "oaiapp_synthetic", "urn:uuid:" + UUID.randomUUID(), System.currentTimeMillis() / 1000);
            return new PendingLoginStore.Record(UUID.randomUUID().toString(), pending, "synthetic-subject-only", SystemClock.elapsedRealtime());
        }
        void clean() throws Exception {
            store.clear();
            require(!file.exists() && !new File(file.getPath() + ".bak").exists() && !new File(file.getPath() + ".new").exists()
                    && !keyStore().containsAlias(alias));
            File directory = file.getParentFile();
            if (directory != null && directory.isDirectory()) require(directory.delete());
        }
    }
}
