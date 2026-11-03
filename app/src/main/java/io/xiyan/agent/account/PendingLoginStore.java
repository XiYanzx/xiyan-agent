// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.account;

import android.content.Context;
import android.os.SystemClock;
import io.xiyan.agent.core.auth.LoginAttemptLifetime;
import io.xiyan.agent.core.auth.PendingSignIn;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONObject;

/** A separate non-backup key, file and AAD for the original, five-minute OAuth transaction. */
final class PendingLoginStore {
    static final class Record {
        final String id, previousSubject;
        final PendingSignIn pending;
        final long createdElapsedMillis;
        private final LoginAttemptLifetime lifetime;
        Record(String id, PendingSignIn pending, String previousSubject, long createdElapsedMillis) {
            canonicalId(id);
            if (previousSubject != null && (previousSubject.isEmpty() || previousSubject.length() > 4096
                    || previousSubject.chars().anyMatch(Character::isISOControl))) {
                throw new IllegalArgumentException("Invalid prior authorization identity");
            }
            this.id = id; this.pending = pending; this.previousSubject = previousSubject;
            this.createdElapsedMillis = createdElapsedMillis;
            lifetime = new LoginAttemptLifetime(pending.getExpiresAtEpochSeconds() - PendingSignIn.LIFETIME_SECONDS, createdElapsedMillis);
        }
        long remainingMillis() { return lifetime.remainingMillis(System.currentTimeMillis() / 1000, SystemClock.elapsedRealtime()); }
        @Override public String toString() { return "PendingLoginRecord[redacted]"; }
    }
    private final SessionStore encrypted;
    private String currentId;
    private boolean identityKnown;
    PendingLoginStore(Context context) { encrypted = SessionStore.pendingAuthorization(context); }
    /** Instrumentation only: same protocol with a separate UUID-bound key, AAD and non-backup file. */
    PendingLoginStore(Context context, String syntheticNamespace) {
        encrypted = SessionStore.pendingAuthorizationTest(context, syntheticNamespace);
    }

    synchronized void save(Record record) throws Exception {
        if (record.remainingMillis() <= 0) throw new LoginFailure(LoginFailure.Reason.PENDING_EXPIRED);
        JSONObject pending = new JSONObject();
        for (Map.Entry<String, Object> entry : record.pending.exportForEncryptedStorage().entrySet()) {
            pending.put(entry.getKey(), entry.getValue() == null ? JSONObject.NULL : entry.getValue());
        }
        encrypted.write(new JSONObject().put("schemaVersion", 1).put("phase", "waiting").put("attemptId", record.id)
                .put("previousSubject", record.previousSubject == null ? JSONObject.NULL : record.previousSubject)
                .put("createdElapsedMillis", record.createdElapsedMillis).put("pending", pending));
        currentId = record.id; identityKnown = true;
    }

    synchronized Record read() throws Exception {
        JSONObject raw;
        try { raw = encrypted.read(); }
        catch (Exception unavailable) { throw new LoginFailure(LoginFailure.Reason.PENDING_UNAVAILABLE); }
        if (raw == null) { currentId = null; identityKnown = true; return null; }
        try {
            if (!Integer.valueOf(1).equals(raw.get("schemaVersion"))) throw new IllegalArgumentException();
            String id = text(raw, "attemptId"); canonicalId(id);
            currentId = id; identityKnown = true;
            if ("consumed".equals(raw.get("phase"))) {
                clear();
                throw new LoginFailure(LoginFailure.Reason.PENDING_CONSUMED);
            }
            if (!"waiting".equals(raw.get("phase")) || raw.length() != 6) throw new IllegalArgumentException();
            JSONObject saved = raw.getJSONObject("pending");
            Map<String, Object> values = new LinkedHashMap<>();
            for (Iterator<String> keys = saved.keys(); keys.hasNext();) {
                String key = keys.next(); Object value = saved.get(key);
                if ("createdAtEpochSeconds".equals(key) || "expiresAtEpochSeconds".equals(key)) value = integer(value);
                values.put(key, value == JSONObject.NULL ? null : value);
            }
            PendingSignIn pending = PendingSignIn.restoreFromEncryptedStorage(values, System.currentTimeMillis() / 1000);
            Object subject = raw.get("previousSubject");
            if (subject != JSONObject.NULL && !(subject instanceof String)) throw new IllegalArgumentException();
            Record record = new Record(id, pending, subject == JSONObject.NULL ? null : (String) subject,
                    integer(raw.get("createdElapsedMillis")));
            if (record.remainingMillis() <= 0) throw new LoginFailure(LoginFailure.Reason.PENDING_EXPIRED);
            return record;
        } catch (PendingSignIn.AuthException expired) {
            clear();
            throw new LoginFailure(expired.getReason() == PendingSignIn.Reason.EXPIRED
                    ? LoginFailure.Reason.PENDING_EXPIRED : LoginFailure.Reason.PENDING_INVALID);
        } catch (LoginFailure failure) { if (failure.reason == LoginFailure.Reason.PENDING_EXPIRED) clear(); throw failure; }
        catch (Exception invalid) { clear(); throw new LoginFailure(LoginFailure.Reason.PENDING_INVALID); }
    }

    /** Commit before any code exchange. A restarted process must not exchange this receipt again. */
    synchronized void markConsumed(String expectedId) throws Exception {
        JSONObject raw = encrypted.read();
        if (raw == null || !expectedId.equals(raw.optString("attemptId")) || !"waiting".equals(raw.optString("phase"))) {
            throw new LoginFailure(LoginFailure.Reason.PENDING_CONSUMED);
        }
        encrypted.write(new JSONObject().put("schemaVersion", 1).put("phase", "consumed").put("attemptId", expectedId));
    }
    synchronized void clearIfMatches(String expectedId) throws Exception {
        if (identityKnown) {
            if (expectedId.equals(currentId)) clear();
            return;
        }
        JSONObject raw = encrypted.read();
        if (raw != null && expectedId.equals(raw.optString("attemptId"))) clear();
    }
    synchronized void clear() throws Exception { encrypted.clear(); currentId = null; identityKnown = true; }
    private static String text(JSONObject value, String key) throws Exception {
        Object item = value.get(key);
        if (!(item instanceof String)) throw new IllegalArgumentException();
        return (String) item;
    }
    private static long integer(Object value) {
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException();
        return ((Number) value).longValue();
    }
    private static void canonicalId(String value) {
        UUID id = UUID.fromString(value);
        if (id.version() != 4 || id.variant() != 2 || !id.toString().equals(value)) throw new IllegalArgumentException("Invalid attempt identifier");
    }
}
