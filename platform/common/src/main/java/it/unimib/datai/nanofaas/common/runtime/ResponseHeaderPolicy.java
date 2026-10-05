package it.unimib.datai.nanofaas.common.runtime;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ResponseHeaderPolicy {
    /** Trusted control-plane metadata; deliberately absent from handler allow-list. */
    /** Internal physical-runtime evidence; never a handler-supplied response header. */
    public static final String HANDLER_EXECUTED_HEADER="X-NanoFaaS-Handler-Executed";
    public static final String EXECUTION_NODE_HEADER="X-NanoFaaS-Execution-Node";
    public static final Set<String> ALLOWED_RESPONSE_HEADERS = Set.of(
            "content-type", "location", "cache-control", "etag",
            "content-disposition", "content-language", "retry-after", "vary");

    private ResponseHeaderPolicy() {
    }

    public static boolean isStatusCodeValid(int statusCode) {
        return statusCode >= 200 && statusCode <= 599;
    }

    /**
     * Filters a handler-supplied response header map down to the allow-list.
     *
     * <p>At most one entry survives per header name, compared case-insensitively: HTTP header names
     * are case-insensitive, so emitting both {@code Content-Type} and {@code content-type} would put
     * two colliding entries on the response. The first occurrence in iteration order wins and keeps
     * its original casing — that casing is part of the public {@code InvocationResponse.headers}
     * contract and callers read it.
     */
    public static Map<String, String> filterAllowedHeaders(Map<String, String> raw) {
        if (raw == null) {
            return Map.of();
        }
        Map<String, String> filtered = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            // Locale.ROOT, not the default locale: under a Turkish/Azerbaijani default,
            // 'I' folds to dotless 'ı' and the allow-list match would silently fail.
            String lowerKey = entry.getKey().toLowerCase(Locale.ROOT);
            if (ALLOWED_RESPONSE_HEADERS.contains(lowerKey) && seen.add(lowerKey)) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered;
    }
}
