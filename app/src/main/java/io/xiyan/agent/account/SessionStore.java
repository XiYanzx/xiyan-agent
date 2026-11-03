// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.account;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Tokens are written only after a verified login, under a non-exportable Android key. */
final class SessionStore {
    private static final String ALIAS = "xiyan.siwc.session.v1";
    private static final int MAX_PLAIN_BYTES = 256 * 1024;
    private static final int MAX_ENVELOPE_BYTES = 512 * 1024;
    private final AtomicFile file;
    private final String alias;
    private final String associatedData;
    SessionStore(Context context) {
        alias = ALIAS;
        associatedData = "xiyan-siwc-session-v1";
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "account/siwc-session.bin"));
    }
    private SessionStore(Context context, boolean pendingAuthorization) {
        if (!pendingAuthorization) throw new IllegalArgumentException("Invalid pending store purpose");
        alias = "xiyan.siwc.pending.v1";
        associatedData = "xiyan-siwc-pending-v1";
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "account/siwc-pending.bin"));
    }
    static SessionStore pendingAuthorization(Context context) { return new SessionStore(context, true); }
    /** Synthetic pending storage has its own canonical UUID, key, file and authenticated-data purpose. */
    private SessionStore(Context context, String testNamespace, boolean pendingAuthorization) {
        if (!pendingAuthorization) throw new IllegalArgumentException("Invalid synthetic pending purpose");
        UUID namespace = UUID.fromString(testNamespace);
        if (namespace.version() != 4 || namespace.variant() != 2 || !namespace.toString().equals(testNamespace)) {
            throw new IllegalArgumentException("Invalid synthetic credential namespace");
        }
        alias = "xiyan.siwc.pending.test." + testNamespace;
        associatedData = "xiyan-siwc-pending-test-v1:" + testNamespace;
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "account-tests/" + testNamespace + "/siwc-pending.bin"));
    }
    static SessionStore pendingAuthorizationTest(Context context, String testNamespace) {
        return new SessionStore(context, testNamespace, true);
    }
    /** Isolated synthetic-data seam; accepts only a fresh canonical UUID v4 namespace. */
    SessionStore(Context context, String testNamespace) {
        UUID namespace = UUID.fromString(testNamespace);
        if (namespace.version() != 4 || namespace.variant() != 2 || !namespace.toString().equals(testNamespace)) {
            throw new IllegalArgumentException("Invalid synthetic credential namespace");
        }
        alias = "xiyan.siwc.test." + testNamespace;
        associatedData = "xiyan-siwc-session-v1";
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "account-tests/" + testNamespace + "/siwc-session.bin"));
    }
    JSONObject read() throws Exception {
        InputStream opened;
        try { opened = file.openRead(); }
        catch (FileNotFoundException unavailable) {
            File base = file.getBaseFile();
            if (isMissing(base) && isMissing(new File(base.getPath() + ".bak"))) return null;
            throw unavailable;
        }
        try (InputStream input = opened) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_ENVELOPE_BYTES) throw new java.io.IOException("Session file limit exceeded");
                bytes.write(buffer, 0, count);
            }
            JSONObject envelope = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            if (envelope.getInt("version") != 1) throw new java.io.IOException("Unsupported session file");
            byte[] iv = Base64.getDecoder().decode(envelope.getString("iv"));
            if (iv.length != 12) throw new java.io.IOException("Invalid session IV");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, iv));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.US_ASCII));
            byte[] ciphertext = Base64.getDecoder().decode(envelope.getString("data"));
            if (ciphertext.length > MAX_PLAIN_BYTES + 16) throw new java.io.IOException("Session data limit exceeded");
            byte[] plain = cipher.doFinal(ciphertext);
            if (plain.length > MAX_PLAIN_BYTES) throw new java.io.IOException("Session data limit exceeded");
            return new JSONObject(new String(plain, StandardCharsets.UTF_8));
        }
    }
    private static boolean isMissing(File path) throws IOException {
        try { Os.stat(path.getPath()); return false; }
        catch (ErrnoException unavailable) {
            if (unavailable.errno == OsConstants.ENOENT) return true;
            throw new IOException("Session file status unavailable");
        }
    }
    void write(JSONObject session) throws Exception {
        byte[] plain = session.toString().getBytes(StandardCharsets.UTF_8);
        if (plain.length > MAX_PLAIN_BYTES) throw new java.io.IOException("Session data limit exceeded");
        File parent = file.getBaseFile().getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) throw new java.io.IOException("Session directory unavailable");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        cipher.updateAAD(associatedData.getBytes(StandardCharsets.US_ASCII));
        JSONObject envelope = new JSONObject().put("version", 1)
                .put("iv", Base64.getEncoder().encodeToString(cipher.getIV()))
                .put("data", Base64.getEncoder().encodeToString(cipher.doFinal(plain)));
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(envelope.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (Exception error) { if (output != null) file.failWrite(output); throw error; }
    }
    void clear() throws Exception {
        file.delete();
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(alias)) store.deleteEntry(alias);
    }
    private SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(alias)) return (SecretKey) store.getKey(alias, null);
        if (!create) throw new java.io.IOException("Session key unavailable");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
}
