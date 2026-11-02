// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.core.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class LoopbackCallbackRequestTest {
    private static final int PORT = 43917;
    @Test public void acceptsBrowserCallbackAndPreservesEncodedQuery() throws Exception {
        assertEquals("/auth/callback?code=a%2Bb&state=opaque", read("GET /auth/callback?code=a%2Bb&state=opaque HTTP/1.1\r\n"
                + "host: 127.0.0.1:43917\r\nUser-Agent: Browser/1\r\n\r\n"));
    }
    @Test public void rejectsHostConfusionAndHeaderSmuggling() {
        for (String headers : new String[]{"", "Host: localhost:43917\r\n", "Host: 127.0.0.1:43918\r\n",
                "Host: 127.0.0.1:43917\r\nHost: 127.0.0.1:43917\r\n",
                "Host: 127.0.0.1:43917\r\n X-Folded: value\r\n",
                "Host: 127.0.0.1:43917\n", "Host: 127.0.0.1:43917\rX: bad\r\n"}) {
            assertThrows(IOException.class, () -> read("GET /auth/callback?state=x HTTP/1.1\r\n" + headers + "\r\n"));
        }
    }
    @Test public void rejectsUnrelatedPathsMethodsAndAbsoluteRequestTargets() {
        for (String request : new String[]{"POST /auth/callback?state=x HTTP/1.1", "GET /favicon.ico HTTP/1.1",
                "GET http://127.0.0.1:43917/auth/callback?state=x HTTP/1.1", "GET /auth/callback?x=y HTTP/2"}) {
            assertThrows(IOException.class, () -> read(request + "\r\nHost: 127.0.0.1:43917\r\n\r\n"));
        }
    }
    @Test public void enforcesTotalRequestLimitAcrossShortHeaders() {
        StringBuilder headers = new StringBuilder("GET /auth/callback?state=x HTTP/1.1\r\nHost: 127.0.0.1:43917\r\n");
        for (int i = 0; i < 4000; i++) headers.append("X-Test: 123\r\n");
        assertThrows(IOException.class, () -> read(headers + "\r\n"));
    }
    @Test public void deadlineOrCancellationGuardStopsBeforeTheNextInputRead() {
        final int[] guarded = {0}, consumed = {0};
        InputStream input = new InputStream() {
            @Override public int read() { consumed[0]++; return 'G'; }
        };
        assertThrows(IOException.class, () -> LoopbackCallbackRequest.readTarget(input, PORT, () -> {
            if (++guarded[0] == 4) throw new IOException("Stopped");
        }));
        assertEquals(3, consumed[0]);
    }
    private static String read(String text) throws IOException {
        return LoopbackCallbackRequest.readTarget(new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII)), PORT, () -> { });
    }
}
