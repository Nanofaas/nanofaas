import type { JsonValue } from "./types.js";

/**
 * Mirrors ResponseHeaderPolicy.ALLOWED_RESPONSE_HEADERS in platform/common. Keep in sync.
 */
const ALLOWED_RESPONSE_HEADERS = new Set([
    "content-type",
    "location",
    "cache-control",
    "etag",
    "content-disposition",
    "content-language",
    "retry-after",
    "vary",
]);

/**
 * The optional envelope a handler may return instead of a plain value, to control the HTTP status
 * code, response headers, and the base64 encoding marker.
 *
 * This is a class rather than a plain object type on purpose: detection must be nominal. TypeScript
 * erases types at runtime, so a structural check would silently promote any plain object that
 * happened to carry a `statusCode` property.
 */
export class HandlerResponse {
    readonly output: JsonValue;
    readonly statusCode: number;
    readonly headers: Record<string, string>;
    readonly encoding?: string;

    constructor(
        output: JsonValue,
        statusCode: number,
        headers: Record<string, string> = {},
        encoding?: string,
    ) {
        this.output = output;
        this.statusCode = statusCode;
        this.headers = headers;
        if (encoding !== undefined) {
            this.encoding = encoding;
        }
    }
}

export function isStatusCodeValid(statusCode: number): boolean {
    return Number.isInteger(statusCode) && statusCode >= 200 && statusCode <= 599;
}

/**
 * Filters handler-supplied response headers down to the allow-list. At most one entry survives per
 * header name, compared case-insensitively; the first occurrence wins and keeps its original casing.
 */
export function filterAllowedHeaders(raw?: Record<string, string>): Record<string, string> {
    const filtered: Record<string, string> = {};
    const seen = new Set<string>();
    for (const [key, value] of Object.entries(raw ?? {})) {
        const lowerKey = key.toLowerCase();
        if (!ALLOWED_RESPONSE_HEADERS.has(lowerKey) || seen.has(lowerKey)) {
            continue;
        }
        seen.add(lowerKey);
        filtered[key] = value;
    }
    return filtered;
}
