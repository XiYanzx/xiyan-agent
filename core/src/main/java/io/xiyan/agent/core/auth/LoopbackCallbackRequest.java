// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.core.auth;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** A bounded HTTP request reader. The caller enforces cancellation and socket deadlines per read. */
public final class LoopbackCallbackRequest {
    public interface ReadGuard { void beforeRead() throws IOException; }
    private static final int MAX_REQUEST_BYTES = 32768;
    private LoopbackCallbackRequest() { }

    /** Returns an origin-form callback target only after checking the exact loopback Host header. */
    public static String readTarget(InputStream input, int port, ReadGuard guard) throws IOException {
        if (input == null || guard == null || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid callback reader configuration");
        }
        Counter counter = new Counter();
        String[] parts = readLine(input, 16640, guard, counter).split(" ", -1);
        if (parts.length != 3 || !"GET".equals(parts[0])
                || !("HTTP/1.1".equals(parts[2]) || "HTTP/1.0".equals(parts[2]))
                || !parts[1].startsWith("/auth/callback?")) {
            throw new IOException("Invalid callback request line");
        }
        String host = null;
        for (String line; !(line = readLine(input, 8192, guard, counter)).isEmpty();) {
            int separator = line.indexOf(':');
            if (separator <= 0 || !line.substring(0, separator).matches("[!#$%&'*+.^_`|~A-Za-z0-9-]+")) {
                throw new IOException("Invalid callback header");
            }
            if ("Host".equalsIgnoreCase(line.substring(0, separator))) {
                if (host != null) throw new IOException("Duplicate callback host");
                host = line.substring(separator + 1).trim();
            }
        }
        if (!("127.0.0.1:" + port).equals(host)) throw new IOException("Invalid callback host");
        guard.beforeRead();
        return parts[1];
    }

    private static String readLine(InputStream input, int cap, ReadGuard guard, Counter counter) throws IOException {
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        boolean carriageReturn = false;
        for (;;) {
            guard.beforeRead();
            int value = input.read();
            if (value == -1) throw new IOException("Incomplete callback request");
            if (++counter.bytes > MAX_REQUEST_BYTES) throw new IOException("Callback request limit exceeded");
            if (carriageReturn) {
                if (value != '\n') throw new IOException("Invalid callback line ending");
                return new String(text.toByteArray(), StandardCharsets.US_ASCII);
            }
            if (value == '\r') { carriageReturn = true; continue; }
            if (value < 32 || value > 126 || text.size() >= cap) throw new IOException("Invalid callback line");
            text.write(value);
        }
    }

    private static final class Counter { int bytes; }
}
