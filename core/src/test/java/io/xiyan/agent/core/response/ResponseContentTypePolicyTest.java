// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.core.response;

import static org.junit.Assert.*;
import org.junit.Test;

public class ResponseContentTypePolicyTest {
    @Test public void missingHeaderPermitsBodyVerificationWithoutInventingAMimeType() {
        assertTrue(ResponseContentTypePolicy.mayReadSse(null));
        assertEquals(ResponseContentTypePolicy.Disposition.MISSING, ResponseContentTypePolicy.headerDisposition(null));
    }

    @Test public void acceptsOnlyExactSseMimeWithLegalCaseWhitespaceAndParameters() {
        for (String header : new String[]{"text/event-stream", "TEXT/EVENT-STREAM", " text/event-stream ",
                "text/event-stream;charset=utf-8", "Text/Event-Stream ; charset=\"UTF-8\""}) {
            assertTrue(ResponseContentTypePolicy.mayReadSse(header));
            assertEquals(ResponseContentTypePolicy.Disposition.SSE, ResponseContentTypePolicy.headerDisposition(header));
        }
    }

    @Test public void rejectsExplicitNonSseTypesAndFalsePrefixes() {
        for (String header : new String[]{"application/json", "text/html", "text/plain", "text/event-streaming",
                "text/event-stream-json", "text/event-stream/extra", "text/event-stream, application/json", "", "   "}) {
            assertFalse(ResponseContentTypePolicy.mayReadSse(header));
            assertEquals(ResponseContentTypePolicy.Disposition.REJECTED, ResponseContentTypePolicy.headerDisposition(header));
        }
    }

    @Test public void rejectsInjectedLinesAndOversizedHeaderValues() {
        assertFalse(ResponseContentTypePolicy.mayReadSse("text/event-stream\r\nX-Extra: value"));
        assertFalse(ResponseContentTypePolicy.mayReadSse("text/event-stream\n"));
        assertFalse(ResponseContentTypePolicy.mayReadSse("text/event-stream;" + String.join("", java.util.Collections.nCopies(4096, "x"))));
    }
}
