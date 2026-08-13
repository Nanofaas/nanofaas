package it.unimib.datai.nanofaas.common.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class ResponseHeaderPolicy {
    public static final Set<String> ALLOWED_RESPONSE_HEADERS = Set.of(
            "content-type", "location", "cache-control", "etag",
            "content-disposition", "content-language", "retry-after", "vary");

    private ResponseHeaderPolicy() {
    }

    public static boolean isStatusCodeValid(int statusCode) {
        return statusCode >= 200 && statusCode <= 599;
    }

    public static Map<String, String> filterAllowedHeaders(Map<String, String> raw) {
        if (raw == null) {
            return Map.of();
        }
        Map<String, String> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            if (ALLOWED_RESPONSE_HEADERS.contains(entry.getKey().toLowerCase())) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered;
    }
}
