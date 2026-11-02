// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.account;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.AEADBadTagException;
import javax.crypto.SecretKey;
import org.json.JSONArray;
import org.json.JSONObject;

/** Platform-only instrumentation. Every file and key belongs to a new synthetic test namespace. */
public final class CredentialStoreInstrumentation extends Instrumentation {
    private interface Check { void run() throws Exception; }
    private final JSONArray cases = new JSONArray();
    private int failures;
    @Override public void onCreate(Bundle input) { super.onCreate(input); start(); }

    @Override public void onStart() {
        runCheck("keystore_aes_gcm_round_trip", this::roundTrip);
        runCheck("ciphertext_tamper_rejected", this::tamperRejected);
        runCheck("clear_isolates_test_namespace", this::clearIsolation);
        runCheck("legacy_atomic_backup_restored", this::legacyBackupRestored);
        Bundle result = new Bundle();
        result.putString("status", failures == 0 ? "passed" : "failed");
        result.putInt("tests", cases.length());
        result.putInt("failures", failures);
        result.putInt("androidApi", Build.VERSION.SDK_INT);
        result.putString("scope", "Synthetic AndroidKeyStore credential storage only");
        result.putBoolean("accountCredentialsRead", false);
        result.putBoolean("networkRequested", false);
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
}
