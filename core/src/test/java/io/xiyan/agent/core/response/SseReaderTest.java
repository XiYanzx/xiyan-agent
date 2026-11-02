// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.core.response;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class SseReaderTest {
    @Test public void handlesFragmentedUtf8BomCommentsAndMultipleDataLines() throws Exception {
        byte[] bytes = "\ufeff: ping\r\nevent: response.output_text.delta\r\ndata: 你好\r\ndata: world\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        InputStream chunks = new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(byte[] b, int off, int len) { return super.read(b, off, Math.min(1, len)); }
        };
        List<SseReader.Event> events = new ArrayList<>();
        assertFalse(SseReader.read(chunks, () -> { }, item -> { events.add(item); return false; }));
        assertEquals(1, events.size());
        assertEquals("response.output_text.delta", events.get(0).event);
        assertEquals("你好\nworld", events.get(0).data);
        assertFalse(events.get(0).toString().contains("你好"));
    }
    @Test public void terminalListenerStopsBeforeLaterFrames() throws Exception {
        List<String> values = new ArrayList<>();
        assertTrue(read("data: delta\n\nevent: response.completed\ndata: verified\n\ndata: late\n\n", item -> {
            values.add(item.data); return item.event.equals("response.completed") && item.data.equals("verified");
        }));
        assertEquals(java.util.Arrays.asList("delta", "verified"), values);
    }
    @Test public void eofAndDoneDoNotMarkSuccess() throws Exception {
        assertFalse(read("data: [DONE]\n\n", item -> false));
        assertFalse(read("event: response.completed\ndata: partial", item -> true));
        assertFalse(read("event: response.completed\ndata: partial\n", item -> true));
    }
    @Test public void supportsCarriageReturnAndResetsEventName() throws Exception {
        List<String> kinds = new ArrayList<>();
        assertFalse(read("event: one\rdata: x\r\rdata\r\r", item -> { kinds.add(item.event + ":" + item.data); return false; }));
        assertEquals(java.util.Arrays.asList("one:x", "message:"), kinds);
    }
    @Test public void cancellationBeforeDeliverySuppressesBufferedEvent() {
        AtomicInteger checks = new AtomicInteger(), delivered = new AtomicInteger();
        assertThrows(IOException.class, () -> SseReader.read(input("data: answer\n\n"), () -> {
            if (checks.incrementAndGet() == 3) throw new IOException("cancelled");
        }, item -> { delivered.incrementAndGet(); return true; }));
        assertEquals(0, delivered.get());
    }
    @Test public void rejectsInvalidUtf8() {
        assertThrows(IOException.class, () -> SseReader.read(new ByteArrayInputStream(new byte[]{(byte) 0xc3, 10, 10}), () -> { }, item -> false));
    }
    @Test public void rejectsOversizedLineAndEvent() {
        assertThrows(IOException.class, () -> read("data: " + "x".repeat(SseReader.MAX_LINE_BYTES) + "\n\n", item -> false));
        String line = "data: " + "x".repeat(100000) + "\n";
        assertThrows(IOException.class, () -> read(line.repeat(6) + "\n", item -> false));
    }
    @Test public void rejectsUnboundedCommentsAndEventCount() {
        assertThrows(IOException.class, () -> read((": " + "x".repeat(100000) + "\n").repeat(22), item -> false));
        assertThrows(IOException.class, () -> read("data: x\n\n".repeat(8193), item -> false));
    }
    @Test public void listenerFailureStopsTheStream() {
        assertThrows(IOException.class, () -> read("data: failed\n\ndata: later\n\n", item -> { throw new IOException("failed"); }));
    }
    @Test public void ignoresFieldsWithoutDataAndPreservesDataLeadingSpaces() throws Exception {
        List<String> values = new ArrayList<>();
        assertFalse(read("event: ignored\nretry: 1000\n\ndata:  value\nunknown: x\n\n", item -> {
            assertEquals("message", item.event); values.add(item.data); return false;
        }));
        assertEquals(java.util.Collections.singletonList(" value"), values);
    }
    private static boolean read(String text, SseReader.Listener listener) throws IOException {
        return SseReader.read(input(text), () -> { }, listener);
    }
    private static InputStream input(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
}
