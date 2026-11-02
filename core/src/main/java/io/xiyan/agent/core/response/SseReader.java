// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.core.response;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Bounded SSE framing. The caller validates JSON and decides which event proves completion. */
public final class SseReader {
    public static final int MAX_TOTAL_BYTES = 2 * 1024 * 1024;
    public static final int MAX_LINE_BYTES = 128 * 1024;
    public static final int MAX_EVENT_BYTES = 512 * 1024;
    private static final int MAX_EVENTS = 8192;

    @FunctionalInterface public interface Guard { void check() throws IOException; }
    @FunctionalInterface public interface Listener { boolean onEvent(Event event) throws IOException; }

    public static final class Event {
        public final String event;
        public final String data;
        private Event(String event, String data) { this.event = event; this.data = data; }
        @Override public String toString() { return "SSE event[content redacted]"; }
    }

    private SseReader() { }

    /**
     * Returns true only when the listener accepts an event as terminal. EOF, [DONE], and an
     * unterminated final frame do not independently prove completion. Does not close the stream.
     * The guard runs before blocking reads and before delivering an event.
     */
    public static boolean read(InputStream stream, Guard guard, Listener listener) throws IOException {
        Objects.requireNonNull(stream, "stream");
        Objects.requireNonNull(guard, "guard");
        Objects.requireNonNull(listener, "listener");
        Cursor cursor = new Cursor(stream, guard);
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        StringBuilder data = new StringBuilder();
        String event = "message";
        boolean firstLine = true, afterCarriageReturn = false, hasData = false;
        int eventBytes = 0, delivered = 0;
        for (int next; (next = cursor.next()) != -1;) {
            if (afterCarriageReturn) {
                afterCarriageReturn = false;
                if (next == '\n') continue;
            }
            if (next != '\r' && next != '\n') {
                if (line.size() >= MAX_LINE_BYTES) throw new IOException("SSE line limit exceeded");
                line.write(next);
                continue;
            }
            afterCarriageReturn = next == '\r';
            int bytes = line.size();
            String text = decode(line.toByteArray());
            line.reset();
            if (firstLine) {
                firstLine = false;
                if (text.startsWith("\ufeff")) text = text.substring(1);
            }
            if (text.isEmpty()) {
                if (hasData) {
                    if (++delivered > MAX_EVENTS) throw new IOException("SSE event count limit exceeded");
                    guard.check();
                    if (listener.onEvent(new Event(event, data.toString()))) return true;
                }
                event = "message"; data.setLength(0); hasData = false; eventBytes = 0;
                continue;
            }
            if (text.charAt(0) == ':') continue;
            int colon = text.indexOf(':');
            String field = colon < 0 ? text : text.substring(0, colon);
            String value = colon < 0 ? "" : text.substring(colon + 1);
            if (value.startsWith(" ")) value = value.substring(1);
            if ("data".equals(field) || "event".equals(field)) {
                eventBytes += bytes;
                if (eventBytes > MAX_EVENT_BYTES) throw new IOException("SSE event limit exceeded");
            }
            if ("data".equals(field)) {
                if (hasData) data.append('\n');
                data.append(value); hasData = true;
            } else if ("event".equals(field)) {
                event = value.isEmpty() ? "message" : value;
            }
        }
        guard.check();
        return false;
    }

    private static String decode(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IOException("Invalid SSE UTF-8");
        }
    }

    private static final class Cursor {
        private final InputStream input;
        private final Guard guard;
        private final byte[] buffer = new byte[8192];
        private int position, length, total;
        Cursor(InputStream input, Guard guard) { this.input = input; this.guard = guard; }
        int next() throws IOException {
            if (position == length) {
                guard.check();
                length = input.read(buffer); position = 0;
                guard.check();
                if (length == -1) return -1;
                if (length == 0) throw new IOException("SSE stream made no progress");
                total += length;
                if (total > MAX_TOTAL_BYTES) throw new IOException("SSE stream limit exceeded");
            }
            return buffer[position++] & 255;
        }
    }
}
