// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.runtimeprobe;

import android.os.Process;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fixed offline protocol fixture. It has no phone, filesystem, or arbitrary-code tool. */
public final class LocalMcpFixture {
    private LocalMcpFixture() { }

    public static void main(String[] args) throws Exception {
        String markerPath = System.getenv("XIYAN_MCP_PROBE_DIR");
        if (markerPath == null) throw new IllegalStateException("Missing private probe directory");
        File marker = new File(markerPath);
        if (!marker.isDirectory()) throw new IllegalStateException("Missing private probe directory");
        append(new File(marker, "mcp-fixture-pids.txt"), Process.myPid() + "\n");
        try (InputStreamReader input = new InputStreamReader(System.in, StandardCharsets.UTF_8);
             PrintWriter output = new PrintWriter(System.out, true)) {
            String line;
            while ((line = readLine(input)) != null) {
                JSONObject request = new JSONObject(line);
                if (!request.has("id")) continue;
                Object id = request.get("id");
                if (!(id instanceof String) && !(id instanceof Number)) throw new IllegalArgumentException("Invalid ID");
                String method = request.getString("method");
                append(new File(marker, "mcp-fixture-events.txt"), method + "\n");
                JSONObject result;
                switch (method) {
                    case "initialize":
                        result = new JSONObject().put("protocolVersion", request.getJSONObject("params").getString("protocolVersion"))
                                .put("capabilities", new JSONObject().put("tools", new JSONObject()))
                                .put("serverInfo", new JSONObject().put("name", "xiyan-offline-fixture").put("version", "1"));
                        break;
                    case "tools/list":
                        result = new JSONObject().put("tools", new JSONArray().put(new JSONObject()
                                .put("name", "xiyan_echo").put("description", "Fixed offline probe marker; no device access")
                                .put("inputSchema", new JSONObject().put("type", "object")
                                        .put("properties", new JSONObject().put("text", new JSONObject()
                                                .put("type", "string").put("const", "xiyan-mcp-probe")))
                                        .put("required", new JSONArray().put("text")).put("additionalProperties", false))));
                        break;
                    case "tools/call":
                        JSONObject params = request.getJSONObject("params");
                        JSONObject arguments = params.getJSONObject("arguments");
                        if (!"xiyan_echo".equals(params.getString("name")) || arguments.length() != 1
                                || !"xiyan-mcp-probe".equals(arguments.getString("text"))) {
                            throw new IllegalArgumentException("Only the fixed probe marker is permitted");
                        }
                        result = new JSONObject().put("content", new JSONArray().put(new JSONObject()
                                .put("type", "text").put("text", "xiyan-mcp-probe"))).put("isError", false);
                        break;
                    case "ping": result = new JSONObject(); break;
                    default:
                        output.println(new JSONObject().put("jsonrpc", "2.0").put("id", id)
                                .put("error", new JSONObject().put("code", -32601).put("message", "Unsupported fixture method")));
                        continue;
                }
                output.println(new JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result));
                if (output.checkError()) throw new IllegalStateException("Output closed");
            }
        } finally {
            append(new File(marker, "mcp-fixture-events.txt"), "fixture_finally\n");
        }
    }

    private static String readLine(InputStreamReader input) throws Exception {
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') return line.toString();
            if (line.length() >= 32768) throw new IllegalArgumentException("Fixture input limit exceeded");
            if (value != '\r') line.append((char) value);
        }
        return line.length() == 0 ? null : line.toString();
    }

    private static void append(File file, String text) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file, true)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
