// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import io.xiyan.agent.account.SiwcClient;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** 有界、仅含应用生成事件的本机诊断。禁止传入屏幕内容、授权 URL 或令牌。 */
public final class Diagnostics {
    private static final int MAX_EVENTS = 40;
    // 同一进程中的所有 Activity 实例共享读改写边界，不能各自缓存事件数组。
    private static final Object LOCK = new Object();
    private final SharedPreferences preferences;
    private final Context applicationContext;

    public Diagnostics(Context context) {
        applicationContext = context.getApplicationContext();
        preferences = context.getSharedPreferences("p0-diagnostics", Context.MODE_PRIVATE);
    }

    /** 调用方必须持有 LOCK；apply 会先同步更新本进程的 SharedPreferences 内存。 */
    private JSONArray loadEvents() {
        JSONArray stored;
        try { stored = new JSONArray(preferences.getString("events", "[]")); }
        catch (JSONException ignored) { stored = new JSONArray(); }
        return stored;
    }

    public void record(String category, String result) {
        synchronized (LOCK) {
            JSONArray events = loadEvents();
            try {
                JSONObject event = new JSONObject();
                event.put("timeEpochMillis", System.currentTimeMillis());
                event.put("category", category);
                event.put("result", result);
                events.put(event);
                while (events.length() > MAX_EVENTS) events.remove(0);
                preferences.edit().putString("events", events.toString()).apply();
            } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
    }

    public String latest() {
        synchronized (LOCK) {
            JSONArray events = loadEvents();
            if (events.length() == 0) return "暂无本机事件。";
            StringBuilder text = new StringBuilder();
            int start = Math.max(0, events.length() - 5);
            for (int i = start; i < events.length(); i++) {
                JSONObject event = events.optJSONObject(i);
                if (event != null) text.append(event.optString("category")).append(" · ")
                        .append(event.optString("result")).append('\n');
            }
            return text.toString().trim();
        }
    }

    public String export() {
        synchronized (LOCK) {
            JSONArray events = loadEvents();
            try {
                JSONObject report = new JSONObject();
                report.put("application", "Xiyan Agent");
                report.put("version", BuildConfig.VERSION_NAME);
                report.put("sourceCommit", BuildConfig.SOURCE_COMMIT);
                report.put("sourceTreeClean", BuildConfig.SOURCE_TREE_CLEAN);
                report.put("stage", "P0 — 未验收");
                report.put("androidApi", Build.VERSION.SDK_INT);
                report.put("abis", new JSONArray(Build.SUPPORTED_ABIS));
                report.put("runtimeBundled", RuntimeProbe.isBundled());
                SiwcClient.Snapshot account = SiwcClient.get(applicationContext).snapshot();
                report.put("accountAuthenticated", account.connected);
                report.put("accountSessionStored", account.hasSession);
                report.put("accountRequiresReauthorization", account.reauthRequired);
                report.put("accountEvidence", "Local verified session; server access is checked on each request");
                report.put("modelInferenceTested", "completed".equals(account.inferenceStatus));
                report.put("inferenceEvidenceScope", "Latest text request in this process");
                report.put("physicalDeviceAcceptance", "未在本报告中确认");
                report.put("events", events);
                return report.toString(2);
            } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
    }
}
