// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.core.response;

/** Header admission only; a readable stream still needs strict SSE and completed-response checks. */
public final class ResponseContentTypePolicy {
    public enum Disposition { MISSING, SSE, REJECTED }
    private ResponseContentTypePolicy() { }

    public static Disposition headerDisposition(String header) {
        if (header == null) return Disposition.MISSING;
        if (header.length() > 4096 || header.indexOf('\r') >= 0 || header.indexOf('\n') >= 0) return Disposition.REJECTED;
        int separator = header.indexOf(';');
        String mime = (separator < 0 ? header : header.substring(0, separator)).trim();
        return "text/event-stream".equalsIgnoreCase(mime) ? Disposition.SSE : Disposition.REJECTED;
    }

    public static boolean mayReadSse(String header) {
        return headerDisposition(header) != Disposition.REJECTED;
    }
}
