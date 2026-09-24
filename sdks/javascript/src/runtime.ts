import {
    createServer,
    validateHeaderName,
    validateHeaderValue,
    type IncomingMessage,
    type Server,
    type ServerResponse,
} from "node:http";
import type { Socket } from "node:net";
import { URL } from "node:url";

import { runWithContext } from "./context.js";
import { NanofaasError, toErrorInfo } from "./errors.js";
import { createLogger } from "./logger.js";
import { createMetrics, type RuntimeMetrics } from "./metrics.js";
import { HandlerResponse, filterAllowedHeaders, isStatusCodeValid } from "./response.js";
import type {
    CallbackPayload, ErrorInfo, Handler, HandlerContext, InvocationRequest, JsonObject, JsonValue,
    Runtime, RuntimeOptions,
} from "./types.js";

const DEFAULT_HANDLER_TIMEOUT_MS = 30_000;
const DEFAULT_CALLBACK_QUEUE_SIZE = 128;
const DEFAULT_MAX_CONCURRENT_HANDLERS = 32;
const DEFAULT_MAX_INPUT_BYTES = 1024 * 1024;
const DEFAULT_MAX_OUTPUT_BYTES = 1024 * 1024;
const DEFAULT_MAX_CALLBACK_PAYLOAD_BYTES = 2 * 1024 * 1024;
const DEFAULT_MAX_PENDING_CALLBACK_BYTES = 16 * 1024 * 1024;
const DEFAULT_BODY_READ_TIMEOUT_MS = 5_000;
const DEFAULT_CALLBACK_ATTEMPT_TIMEOUT_MS = 5_000;
const DEFAULT_CALLBACK_MAX_ATTEMPTS = 3;
const DEFAULT_SHUTDOWN_TIMEOUT_MS = 5_000;
const CALLBACK_RETRY_DELAYS_MS = [100, 500, 2_000];

type ResolvedRuntimeOptions = RuntimeOptions & Required<Pick<RuntimeOptions,
    "handlerTimeoutMs" | "callbackQueueSize" | "maxConcurrentHandlers" | "maxInputBytes"
    | "maxOutputBytes" | "maxCallbackPayloadBytes" | "maxPendingCallbackBytes"
    | "bodyReadTimeoutMs" | "callbackAttemptTimeoutMs" | "callbackMaxAttempts"
    | "shutdownTimeoutMs"
>>;

type HandlerOutcome =
    | { kind: "success"; value: JsonValue | HandlerResponse }
    | { kind: "failure"; error: unknown };
type WaitOutcome = HandlerOutcome | { kind: "timeout" } | { kind: "cancelled" };
type CallbackReservation = { bytes: number; released: boolean };
type InputReservation = { bytes: number };
type CallbackTarget = {
    callbackUrl: string | undefined;
    executionId: string;
    traceId: string | undefined;
    dispatchAttempt: string | undefined;
};

type RuntimeState = {
    options: ResolvedRuntimeOptions;
    handlers: Map<string, Handler>;
    server: Server | undefined;
    serverConnections: Set<Socket>;
    port: number | undefined;
    firstInvocation: boolean;
    startedAt: number;
    metrics: RuntimeMetrics;
    logger: ReturnType<typeof createLogger>;
    callbackController: AbortController;
    callbacks: Set<Promise<void>>;
    handlerTasks: Set<Promise<HandlerOutcome>>;
    requestControllers: Set<AbortController>;
    handlerReservations: number;
    inputBytes: number;
    outputBytes: number;
    pendingCallbacks: number;
    pendingCallbackBytes: number;
    serializedCallbackBytes: number;
    stopping: boolean;
    startPromise: Promise<void> | undefined;
    stopPromise: Promise<void> | undefined;
};

const INPUT_TOO_LARGE: ErrorInfo = {
    code: "RUNTIME_INPUT_TOO_LARGE",
    message: "Runtime input exceeds configured byte limit",
};
const OUTPUT_TOO_LARGE: ErrorInfo = {
    code: "RUNTIME_OUTPUT_TOO_LARGE",
    message: "Runtime output exceeds configured byte limit",
};
const CALLBACK_SATURATED: ErrorInfo = {
    code: "RUNTIME_CALLBACK_SATURATED",
    message: "Runtime callback capacity exhausted",
};
const HANDLER_SATURATED: ErrorInfo = {
    code: "RUNTIME_HANDLER_SATURATED",
    message: "Runtime handler capacity exhausted",
};
const RUNTIME_STOPPING: ErrorInfo = { code: "RUNTIME_STOPPING", message: "Runtime is stopping" };
const BODY_TIMEOUT: ErrorInfo = {
    code: "RUNTIME_BODY_TIMEOUT",
    message: "Runtime request body read timed out",
};
const HANDLER_TIMEOUT: ErrorInfo = {
    code: "HANDLER_TIMEOUT",
    message: "Handler exceeded configured timeout",
};
const INVOCATION_CANCELLED: ErrorInfo = {
    code: "INVOCATION_CANCELLED",
    message: "Invocation cancelled",
};
const HANDLER_ERROR: ErrorInfo = { code: "HANDLER_ERROR", message: "Handler failed" };
const INVALID_RESPONSE_HEADERS: ErrorInfo = {
    code: "OUTPUT_SERIALIZATION_ERROR",
    message: "Handler response headers are invalid",
};
const INVALID_RESPONSE_STATUS: ErrorInfo = {
    code: "OUTPUT_SERIALIZATION_ERROR",
    message: "Handler response status is invalid",
};
const CANONICAL_RUNTIME_ERRORS: ErrorInfo[] = [
    INPUT_TOO_LARGE,
    OUTPUT_TOO_LARGE,
    CALLBACK_SATURATED,
    HANDLER_SATURATED,
    RUNTIME_STOPPING,
    BODY_TIMEOUT,
    HANDLER_TIMEOUT,
    INVOCATION_CANCELLED,
    HANDLER_ERROR,
    INVALID_RESPONSE_HEADERS,
    INVALID_RESPONSE_STATUS,
    { code: "EXECUTION_ID_REQUIRED", message: "Execution ID required" },
    { code: "INVALID_JSON", message: "Request body must be valid JSON" },
    { code: "INVALID_REQUEST", message: "Invocation metadata must be a string map" },
    { code: "INVALID_REQUEST", message: "Invocation headers must be a string map" },
    { code: "NOT_FOUND", message: "Endpoint not found" },
    { code: "UNHANDLED_ERROR", message: "Internal server error" },
];
const MIN_OUTPUT_PAYLOAD_BYTES = Math.max(...CANONICAL_RUNTIME_ERRORS.map(
    (error) => Buffer.byteLength(JSON.stringify({ error }), "utf8"),
));
const MIN_CALLBACK_PAYLOAD_BYTES = Math.max(...CANONICAL_RUNTIME_ERRORS.map(
    (error) => Buffer.byteLength(JSON.stringify({
    success: false,
    output: null,
    error,
    }), "utf8"),
));

function readEnvString(name: string): string | undefined {
    const value = process.env[name]?.trim();
    return value || undefined;
}

function resolvePort(port?: number): number {
    if (port !== undefined) return port;
    const raw = readEnvString("PORT");
    if (!raw) return 8080;
    const parsed = Number(raw);
    if (!Number.isInteger(parsed) || parsed < 0) throw new Error(`Invalid PORT value: ${raw}`);
    return parsed;
}

function resolveHandlerTimeoutMs(timeout?: number): number {
    if (timeout !== undefined) return timeout;
    const raw = readEnvString("NANOFAAS_HANDLER_TIMEOUT");
    if (!raw) return DEFAULT_HANDLER_TIMEOUT_MS;
    const parsed = Number(raw);
    if (!Number.isFinite(parsed) || parsed <= 0) {
        throw new Error(`Invalid NANOFAAS_HANDLER_TIMEOUT value: ${raw}`);
    }
    return parsed;
}

function finitePositive(name: string, value: number, integer = false): number {
    if (!Number.isFinite(value) || value <= 0 || (integer && !Number.isInteger(value))) {
        throw new Error(`${name} must be a finite positive ${integer ? "integer" : "number"}`);
    }
    return value;
}

function resolveOptions(options: RuntimeOptions): ResolvedRuntimeOptions {
    const callbackQueueSize = finitePositive(
        "callbackQueueSize", options.callbackQueueSize ?? DEFAULT_CALLBACK_QUEUE_SIZE, true,
    );
    const maxCallbackPayloadBytes = finitePositive(
        "maxCallbackPayloadBytes",
        options.maxCallbackPayloadBytes ?? DEFAULT_MAX_CALLBACK_PAYLOAD_BYTES,
        true,
    );
    if (maxCallbackPayloadBytes < MIN_CALLBACK_PAYLOAD_BYTES) {
        throw new Error(
            `maxCallbackPayloadBytes must be at least ${MIN_CALLBACK_PAYLOAD_BYTES} bytes`,
        );
    }
    const maxOutputBytes = finitePositive(
        "maxOutputBytes", options.maxOutputBytes ?? DEFAULT_MAX_OUTPUT_BYTES, true,
    );
    if (maxOutputBytes < MIN_OUTPUT_PAYLOAD_BYTES) {
        throw new Error(`maxOutputBytes must be at least ${MIN_OUTPUT_PAYLOAD_BYTES} bytes`);
    }
    const resolved: ResolvedRuntimeOptions = {
        ...options,
        handlerTimeoutMs: finitePositive(
            "handlerTimeoutMs", resolveHandlerTimeoutMs(options.handlerTimeoutMs),
        ),
        callbackQueueSize,
        maxConcurrentHandlers: finitePositive(
            "maxConcurrentHandlers",
            options.maxConcurrentHandlers ?? DEFAULT_MAX_CONCURRENT_HANDLERS,
            true,
        ),
        maxInputBytes: finitePositive(
            "maxInputBytes", options.maxInputBytes ?? DEFAULT_MAX_INPUT_BYTES, true,
        ),
        maxOutputBytes,
        maxCallbackPayloadBytes,
        maxPendingCallbackBytes: finitePositive(
            "maxPendingCallbackBytes",
            options.maxPendingCallbackBytes ?? DEFAULT_MAX_PENDING_CALLBACK_BYTES,
            true,
        ),
        bodyReadTimeoutMs: finitePositive(
            "bodyReadTimeoutMs", options.bodyReadTimeoutMs ?? DEFAULT_BODY_READ_TIMEOUT_MS,
        ),
        callbackAttemptTimeoutMs: finitePositive(
            "callbackAttemptTimeoutMs",
            options.callbackAttemptTimeoutMs ?? DEFAULT_CALLBACK_ATTEMPT_TIMEOUT_MS,
        ),
        callbackMaxAttempts: finitePositive(
            "callbackMaxAttempts", options.callbackMaxAttempts ?? DEFAULT_CALLBACK_MAX_ATTEMPTS, true,
        ),
        shutdownTimeoutMs: finitePositive(
            "shutdownTimeoutMs", options.shutdownTimeoutMs ?? DEFAULT_SHUTDOWN_TIMEOUT_MS,
        ),
    };
    if (resolved.maxPendingCallbackBytes < resolved.maxCallbackPayloadBytes) {
        throw new Error("maxPendingCallbackBytes must be at least maxCallbackPayloadBytes");
    }
    return resolved;
}

function isJsonObject(value: unknown): value is JsonObject {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isMetadataRecord(value: unknown): value is Record<string, string> {
    return isJsonObject(value) && Object.values(value).every((entry) => typeof entry === "string");
}

async function readJson(
    state: RuntimeState,
    req: IncomingMessage,
    res: ServerResponse,
    reservation: InputReservation,
    cancellationSignal: AbortSignal,
): Promise<unknown> {
    return new Promise((resolve, reject) => {
        const chunks: Buffer[] = [];
        let bytes = 0;
        let settled = false;

        const cleanup = (): void => {
            clearTimeout(timer);
            req.off("data", onData);
            req.off("end", onEnd);
            req.off("error", onError);
            req.off("aborted", onAborted);
            cancellationSignal.removeEventListener("abort", onCancellation);
        };
        const finish = (action: () => void): void => {
            if (settled) return;
            settled = true;
            cleanup();
            action();
        };
        const onData = (chunk: Buffer | string): void => {
            const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
            if (bytes + buffer.length > state.options.maxInputBytes) {
                finish(() => {
                    closeRequestAfterResponse(req, res);
                    reject(new NanofaasError(INPUT_TOO_LARGE.code, INPUT_TOO_LARGE.message));
                });
                return;
            }
            bytes += buffer.length;
            reservation.bytes += buffer.length;
            state.inputBytes += buffer.length;
            state.metrics.inputBytes.inc(buffer.length);
            chunks.push(buffer);
        };
        const onEnd = (): void => finish(() => {
            if (chunks.length === 0) {
                resolve({});
                return;
            }
            try {
                resolve(JSON.parse(Buffer.concat(chunks, bytes).toString("utf8")));
            } catch {
                reject(new NanofaasError("INVALID_JSON", "Request body must be valid JSON"));
            }
        });
        const onError = (error: Error): void => finish(() => reject(error));
        const onAborted = (): void => finish(() => reject(
            new NanofaasError(INVOCATION_CANCELLED.code, INVOCATION_CANCELLED.message),
        ));
        const onCancellation = (): void => finish(() => {
            const info = state.stopping ? RUNTIME_STOPPING : INVOCATION_CANCELLED;
            reject(new NanofaasError(info.code, info.message));
        });
        const timer = setTimeout(() => finish(() => {
            closeRequestAfterResponse(req, res);
            reject(new NanofaasError(BODY_TIMEOUT.code, BODY_TIMEOUT.message));
        }), state.options.bodyReadTimeoutMs);

        req.on("data", onData);
        req.once("end", onEnd);
        req.once("error", onError);
        req.once("aborted", onAborted);
        if (cancellationSignal.aborted) onCancellation();
        else cancellationSignal.addEventListener("abort", onCancellation, { once: true });
    });
}

function resolveOptionalMetadata(
    value: unknown,
    message: string,
): Record<string, string> | undefined {
    if (value === undefined || value === null) return undefined;
    if (!isMetadataRecord(value)) throw new NanofaasError("INVALID_REQUEST", message);
    return value;
}

function normalizeInvocationRequest(payload: unknown): InvocationRequest {
    if (isJsonObject(payload)) {
        const input = "input" in payload ? (payload.input as JsonValue) : (payload as JsonValue);
        const metadata = resolveOptionalMetadata(
            payload.metadata, "Invocation metadata must be a string map",
        );
        const headers = resolveOptionalMetadata(
            payload.headers, "Invocation headers must be a string map",
        );
        return {
            input,
            ...(metadata === undefined ? {} : { metadata }),
            ...(headers === undefined ? {} : { headers }),
        };
    }
    return { input: payload as JsonValue };
}

function writeJson(
    res: ServerResponse,
    statusCode: number,
    payload: JsonValue,
    headers?: Record<string, string>,
): void {
    writeSerializedJson(res, statusCode, JSON.stringify(payload), headers);
}

function writeSerializedJson(
    res: ServerResponse,
    statusCode: number,
    payload: string,
    headers?: Record<string, string>,
): boolean {
    if (res.destroyed || res.writableEnded) return false;
    res.statusCode = statusCode;
    res.setHeader("content-type", "application/json; charset=utf-8");
    for (const [key, value] of Object.entries(headers ?? {})) res.setHeader(key, value);
    res.end(payload);
    return true;
}

function closeRequestAfterResponse(req: IncomingMessage, res: ServerResponse): void {
    req.pause();
    res.setHeader("connection", "close");
    let closed = false;
    const close = (): void => {
        if (closed) return;
        closed = true;
        res.off("finish", close);
        res.off("close", close);
        req.destroy();
    };
    if (res.writableFinished || res.destroyed) close();
    else {
        res.once("finish", close);
        res.once("close", close);
    }
}

function writeEarlyRejection(
    req: IncomingMessage,
    res: ServerResponse,
    statusCode: number,
    payload: JsonValue,
    headers?: Record<string, string>,
): void {
    closeRequestAfterResponse(req, res);
    writeJson(res, statusCode, payload, headers);
}

function responseHeadersAreValid(headers: Record<string, string>): boolean {
    try {
        for (const [name, value] of Object.entries(headers)) {
            validateHeaderName(name);
            validateHeaderValue(name, value);
        }
        return true;
    } catch {
        return false;
    }
}

function serializeJsonBounded(value: JsonValue, maxBytes: number): string {
    const parts: string[] = [];
    const ancestors = new Set<object>();
    let bytes = 0;
    const append = (part: string): void => {
        bytes += Buffer.byteLength(part, "utf8");
        if (bytes > maxBytes) {
            throw new NanofaasError(OUTPUT_TOO_LARGE.code, OUTPUT_TOO_LARGE.message);
        }
        parts.push(part);
    };
    const appendString = (text: string): void => {
        append('"');
        for (let index = 0; index < text.length; index += 1) {
            const code = text.charCodeAt(index); // NOSONAR (typescript:S7758): escaping walks UTF-16 code units
            if (code === 0x22) append(String.raw`\"`);
            else if (code === 0x5c) append(String.raw`\\`);
            else if (code === 0x08) append(String.raw`\b`);
            else if (code === 0x0c) append(String.raw`\f`);
            else if (code === 0x0a) append(String.raw`\n`);
            else if (code === 0x0d) append(String.raw`\r`);
            else if (code === 0x09) append(String.raw`\t`);
            else if (
                code < 0x20
                || (code >= 0xd800 && code <= 0xdfff && !(
                    code <= 0xdbff
                    && index + 1 < text.length
                    && text.charCodeAt(index + 1) >= 0xdc00 // NOSONAR (typescript:S7758): UTF-16 code units
                    && text.charCodeAt(index + 1) <= 0xdfff // NOSONAR (typescript:S7758): UTF-16 code units
                ))
            ) append(String.raw`\u${code.toString(16).padStart(4, "0")}`);
            else if (code >= 0xd800 && code <= 0xdbff) {
                append(text.slice(index, index + 2));
                index += 1;
            } else append(text[index]!);
        }
        append('"');
    };
    const visit = (entry: JsonValue): void => {
        if (entry === null) append("null");
        else if (typeof entry === "string") appendString(entry);
        else if (typeof entry === "number") append(Number.isFinite(entry) ? String(entry) : "null");
        else if (typeof entry === "boolean") append(entry ? "true" : "false");
        else if (Array.isArray(entry)) {
            if (ancestors.has(entry)) throw new TypeError("Converting circular structure to JSON");
            ancestors.add(entry);
            append("[");
            entry.forEach((item, index) => {
                if (index > 0) append(",");
                visit(item);
            });
            append("]");
            ancestors.delete(entry);
        } else {
            if (ancestors.has(entry)) throw new TypeError("Converting circular structure to JSON");
            ancestors.add(entry);
            append("{");
            Object.entries(entry).forEach(([key, item], index) => {
                if (index > 0) append(",");
                appendString(key);
                append(":");
                visit(item);
            });
            append("}");
            ancestors.delete(entry);
        }
    };
    visit(value);
    return parts.join("");
}

function selectHandler(state: RuntimeState): Handler {
    if (state.handlers.size === 0) throw new Error("No handlers registered");
    const selectedName = state.options.functionHandler ?? readEnvString("FUNCTION_HANDLER");
    if (selectedName) {
        const handler = state.handlers.get(selectedName);
        if (!handler) throw new Error(`Configured handler "${selectedName}" was not registered`);
        return handler;
    }
    if (state.handlers.size > 1) {
        throw new Error("Found multiple handlers but FUNCTION_HANDLER is not set");
    }
    return state.handlers.values().next().value as Handler;
}

function callbackUrlForRequest(state: RuntimeState, req: IncomingMessage): string | undefined {
    const header = req.headers["x-callback-url"];
    if (typeof header === "string" && header.trim() !== "") return header.trim();
    return state.options.callbackUrl ?? readEnvString("CALLBACK_URL");
}

function buildCallbackUrl(baseUrl: string, executionId: string): string {
    let base = baseUrl;
    while (base.endsWith("/")) base = base.slice(0, -1);
    return `${base}/${encodeURIComponent(executionId)}:complete`;
}

function isPermanentStatus(status: number): boolean {
    return status >= 400 && status < 500 && status !== 408 && status !== 429;
}

function recordCallbackFailure(
    state: RuntimeState,
    executionId: string,
    details: Record<string, JsonValue>,
): void {
    state.metrics.callbackFailures.inc();
    state.logger.warn("callback delivery failed", { executionId, ...details });
}

async function sleepWithAbort(signal: AbortSignal, delayMs: number): Promise<boolean> {
    if (signal.aborted) return false;
    return new Promise((resolve) => {
        const done = (value: boolean): void => {
            clearTimeout(timer);
            signal.removeEventListener("abort", onAbort);
            resolve(value);
        };
        const onAbort = (): void => done(false);
        const timer = setTimeout(() => done(true), delayMs);
        signal.addEventListener("abort", onAbort, { once: true });
    });
}

async function deliverCallbackOnce(
    state: RuntimeState,
    url: string,
    headers: Record<string, string>,
    body: string,
    signal: AbortSignal,
    attempt: number,
    executionId: string,
): Promise<boolean> {
    const attemptController = new AbortController();
    const timer = setTimeout(
        () => attemptController.abort(),
        state.options.callbackAttemptTimeoutMs,
    );
    try {
        const response = await fetch(url, {
            method: "POST",
            headers,
            body,
            signal: AbortSignal.any([signal, attemptController.signal]),
        });
        if (response.body) {
            const reader = response.body.getReader();
            try {
                while (!(await reader.read()).done) {
                    // Discard each bounded transport chunk without accumulating the response body.
                }
            } finally {
                reader.releaseLock();
            }
        }
        if (response.ok) return false;
        if (isPermanentStatus(response.status) || attempt === state.options.callbackMaxAttempts - 1) {
            recordCallbackFailure(state, executionId, { statusCode: response.status });
            return false;
        }
    } catch (error) {
        if (signal.aborted) return false;
        if (attempt === state.options.callbackMaxAttempts - 1) {
            recordCallbackFailure(state, executionId, { error: toErrorInfo(error).message });
            return false;
        }
    } finally {
        clearTimeout(timer);
    }
    return true;
}

async function sendCallback(
    state: RuntimeState,
    target: CallbackTarget,
    body: string,
    signal: AbortSignal,
): Promise<void> {
    if (!target.callbackUrl) return;
    const headers: Record<string, string> = { "content-type": "application/json" };
    if (target.traceId) headers["x-trace-id"] = target.traceId;
    if (target.dispatchAttempt) headers["x-dispatch-attempt"] = target.dispatchAttempt;
    const url = buildCallbackUrl(target.callbackUrl, target.executionId);
    for (let attempt = 0; attempt < state.options.callbackMaxAttempts; attempt += 1) {
        if (!(await deliverCallbackOnce(
            state, url, headers, body, signal, attempt, target.executionId,
        ))) return;
        const configuredDelay = CALLBACK_RETRY_DELAYS_MS[
            Math.min(attempt, CALLBACK_RETRY_DELAYS_MS.length - 1)
        ]!;
        const delay = Math.min(configuredDelay, state.options.callbackAttemptTimeoutMs);
        if (!(await sleepWithAbort(signal, delay))) return;
    }
}

function reserveCallback(
    state: RuntimeState,
    callbackUrl: string | undefined,
): CallbackReservation | undefined | false {
    if (!callbackUrl) return undefined;
    const bytes = state.options.maxCallbackPayloadBytes;
    if (
        state.pendingCallbacks >= state.options.callbackQueueSize
        || state.pendingCallbackBytes + bytes > state.options.maxPendingCallbackBytes
    ) return false;
    state.pendingCallbacks += 1;
    state.pendingCallbackBytes += bytes;
    state.metrics.pendingCallbacks.inc();
    state.metrics.pendingCallbackBytes.inc(bytes);
    return { bytes, released: false };
}

function releaseCallback(
    state: RuntimeState,
    reservation: CallbackReservation | undefined,
): void {
    if (!reservation || reservation.released) return;
    reservation.released = true;
    state.pendingCallbacks -= 1;
    state.pendingCallbackBytes -= reservation.bytes;
    state.metrics.pendingCallbacks.dec();
    state.metrics.pendingCallbackBytes.dec(reservation.bytes);
}

// Admission reserved the largest payload, the output being unknown. Held until delivery, that
// capped pending callbacks at maxPendingCallbackBytes / maxCallbackPayloadBytes rather than
// callbackQueueSize; once serialized, the callback keeps only its own size. Never grows.
function shrinkCallback(state: RuntimeState, reservation: CallbackReservation, bytes: number): void {
    if (reservation.released || bytes >= reservation.bytes) return;
    const returned = reservation.bytes - bytes;
    reservation.bytes = bytes;
    state.pendingCallbackBytes -= returned;
    state.metrics.pendingCallbackBytes.dec(returned);
}

function dispatchCallback(
    state: RuntimeState,
    reservation: CallbackReservation | undefined,
    target: CallbackTarget,
    payload: CallbackPayload,
): boolean {
    if (!target.callbackUrl) return true;
    if (!reservation) return false;
    let body: string;
    try {
        body = serializeJsonBounded(payload, state.options.maxCallbackPayloadBytes);
    } catch {
        return false;
    }
    const bodyBytes = Buffer.byteLength(body, "utf8");
    shrinkCallback(state, reservation, bodyBytes);
    state.serializedCallbackBytes += bodyBytes;
    state.metrics.serializedCallbackBytes.inc(bodyBytes);
    const callback = sendCallback(state, target, body, state.callbackController.signal);
    state.callbacks.add(callback);
    void callback.then(() => {
        state.callbacks.delete(callback);
        state.serializedCallbackBytes -= bodyBytes;
        state.metrics.serializedCallbackBytes.dec(bodyBytes);
        releaseCallback(state, reservation);
    }, (error) => {
        state.callbacks.delete(callback);
        state.serializedCallbackBytes -= bodyBytes;
        state.metrics.serializedCallbackBytes.dec(bodyBytes);
        releaseCallback(state, reservation);
        recordCallbackFailure(state, target.executionId, { error: toErrorInfo(error).message });
    });
    return true;
}

function reserveHandler(state: RuntimeState): boolean {
    if (state.handlerReservations >= state.options.maxConcurrentHandlers) return false;
    state.handlerReservations += 1;
    return true;
}

function releaseHandler(state: RuntimeState): void {
    state.handlerReservations -= 1;
}

function startHandler(
    state: RuntimeState,
    handler: Handler,
    ctx: HandlerContext,
    request: InvocationRequest,
    inputBytes: number,
): { wait: Promise<WaitOutcome>; started: boolean } {
    const timeoutController = new AbortController();
    const mergedSignal = AbortSignal.any([ctx.signal, timeoutController.signal]);
    if (mergedSignal.aborted) {
        return { wait: Promise.resolve({ kind: "cancelled" }), started: false };
    }
    const effectiveContext: HandlerContext = { ...ctx, signal: mergedSignal };
    let timer: NodeJS.Timeout;
    let onInterrupted: () => void;
    const interrupted = new Promise<WaitOutcome>((resolve) => {
        onInterrupted = () => {
            resolve(timeoutController.signal.aborted ? { kind: "timeout" } : { kind: "cancelled" });
        };
        mergedSignal.addEventListener("abort", onInterrupted, { once: true });
        timer = setTimeout(() => timeoutController.abort(), state.options.handlerTimeoutMs);
    });
    const task = runWithContext(
        {
            executionId: ctx.executionId,
            ...(ctx.traceId === undefined ? {} : { traceId: ctx.traceId }),
        },
        () => Promise.resolve().then(() => handler(effectiveContext, request)),
    ).then<HandlerOutcome, HandlerOutcome>(
        (value) => ({ kind: "success", value }),
        (error: unknown) => ({ kind: "failure", error }),
    );
    state.metrics.activeHandlers.inc();
    state.handlerTasks.add(task);
    void task.then(() => {
        clearTimeout(timer);
        // Composite signals with abort listeners are rooted by Node until abort or listener
        // removal. Successful handlers never abort, so once:true alone retains each request.
        mergedSignal.removeEventListener("abort", onInterrupted);
        state.handlerTasks.delete(task);
        state.metrics.activeHandlers.dec();
        state.inputBytes -= inputBytes;
        state.metrics.inputBytes.dec(inputBytes);
        releaseHandler(state);
    });
    return { wait: Promise.race([task, interrupted]), started: true };
}

function resolveRequestHeader(header: string | string[] | undefined): string | undefined {
    return typeof header === "string" && header.trim() !== "" ? header.trim() : undefined;
}

function statusCodeForError(info: ErrorInfo): number {
    if (info.code === HANDLER_TIMEOUT.code) return 504;
    if (info.code === INPUT_TOO_LARGE.code) return 413;
    if (info.code === BODY_TIMEOUT.code) return 408;
    if (info.code === RUNTIME_STOPPING.code) return 503;
    if (info.code === "INVALID_JSON" || info.code === "INVALID_REQUEST") return 400;
    return 500;
}

function failurePayload(error: ErrorInfo): CallbackPayload {
    return { success: false, output: null, error };
}

type Admission = {
    handler: Handler;
    target: CallbackTarget;
    callbackReservation: CallbackReservation | undefined;
};

/** Tracks whether the callback reservation was handed off, as soon as it happens. */
type InvocationProgress = { callbackDispatched: boolean };

/** What the outcome writers need about one admitted invocation. */
type InvocationScope = {
    req: IncomingMessage;
    res: ServerResponse;
    target: CallbackTarget;
    callbackReservation: CallbackReservation | undefined;
    progress: InvocationProgress;
};

function admitInvocation(
    state: RuntimeState,
    req: IncomingMessage,
    res: ServerResponse,
): Admission | undefined {
    if (state.stopping) {
        writeEarlyRejection(req, res, 503, { error: RUNTIME_STOPPING }, { "retry-after": "1" });
        return undefined;
    }
    const handler = selectHandler(state);
    const executionId = resolveRequestHeader(req.headers["x-execution-id"])
        ?? readEnvString("EXECUTION_ID");
    const traceId = resolveRequestHeader(req.headers["x-trace-id"]) ?? readEnvString("TRACE_ID");
    const dispatchAttempt = resolveRequestHeader(req.headers["x-dispatch-attempt"]);
    if (!executionId) {
        writeEarlyRejection(req, res, 400, {
            error: { code: "EXECUTION_ID_REQUIRED", message: "Execution ID required" },
        });
        return undefined;
    }
    if (!reserveHandler(state)) {
        writeEarlyRejection(req, res, 429, { error: HANDLER_SATURATED }, { "retry-after": "1" });
        return undefined;
    }
    const callbackUrl = callbackUrlForRequest(state, req);
    const callbackReservation = reserveCallback(state, callbackUrl);
    if (callbackReservation === false) {
        releaseHandler(state);
        writeEarlyRejection(req, res, 429, { error: CALLBACK_SATURATED }, { "retry-after": "1" });
        return undefined;
    }
    return {
        handler,
        target: { callbackUrl, executionId, traceId, dispatchAttempt },
        callbackReservation,
    };
}

async function handleInvoke(
    state: RuntimeState,
    req: IncomingMessage,
    res: ServerResponse,
): Promise<void> {
    const admission = admitInvocation(state, req, res);
    if (!admission) return;
    const { handler, target, callbackReservation } = admission;
    const { executionId, traceId } = target;

    const coldStart = state.firstInvocation;
    state.firstInvocation = false;
    if (coldStart) state.metrics.coldStarts.inc();
    const inputReservation: InputReservation = { bytes: 0 };
    let handlerStarted = false;
    const progress: InvocationProgress = { callbackDispatched: false };
    const scope: InvocationScope = { req, res, target, callbackReservation, progress };
    const requestController = new AbortController();
    state.requestControllers.add(requestController);
    state.metrics.inFlight.inc();
    const durationTimer = state.metrics.duration.startTimer();
    const cancelRequest = (): void => {
        if (!res.writableEnded) requestController.abort();
    };
    req.once("aborted", cancelRequest);
    res.once("close", cancelRequest);

    try {
        const body = await readJson(state, req, res, inputReservation, requestController.signal);
        if (state.stopping) {
            throw new NanofaasError(RUNTIME_STOPPING.code, RUNTIME_STOPPING.message);
        }
        const payload = normalizeInvocationRequest(body);
        const context: HandlerContext = {
            executionId,
            logger: createLogger("nanofaas.handler"),
            signal: requestController.signal,
            isColdStart: coldStart,
            ...(traceId === undefined ? {} : { traceId }),
        };
        const running = startHandler(state, handler, context, payload, inputReservation.bytes);
        handlerStarted = running.started;
        const outcome = await running.wait;
        writeOutcome(state, scope, outcome, coldStart);
    } catch (error) {
        writeInvocationError(state, scope, error);
    } finally {
        req.off("aborted", cancelRequest);
        res.off("close", cancelRequest);
        state.requestControllers.delete(requestController);
        if (!handlerStarted) {
            state.inputBytes -= inputReservation.bytes;
            state.metrics.inputBytes.dec(inputReservation.bytes);
            releaseHandler(state);
        }
        if (!progress.callbackDispatched) releaseCallback(state, callbackReservation);
        durationTimer();
        state.metrics.inFlight.dec();
    }
}

function writeOutcome(
    state: RuntimeState,
    scope: InvocationScope,
    outcome: WaitOutcome,
    coldStart: boolean,
): void {
    const { req, res, target, callbackReservation, progress } = scope;
    switch (outcome.kind) {
        case "cancelled":
            state.metrics.invocations.inc({ success: "false" });
            if (state.stopping) {
                writeEarlyRejection(req, res, 503, { error: RUNTIME_STOPPING }, { "retry-after": "1" });
                return;
            }
            progress.callbackDispatched = dispatchCallback(
                state, callbackReservation, target, failurePayload(INVOCATION_CANCELLED),
            );
            return;
        case "timeout":
            state.metrics.invocations.inc({ success: "false" });
            progress.callbackDispatched = dispatchCallback(
                state, callbackReservation, target, failurePayload(HANDLER_TIMEOUT),
            );
            writeJson(res, 504, { error: HANDLER_TIMEOUT });
            return;
        case "failure":
            writeHandlerFailure(state, scope, outcome.error);
            return;
        default:
            progress.callbackDispatched = writeInvokeResult(
                state, res, outcome.value, target, callbackReservation, coldStart,
            );
    }
}

function writeHandlerFailure(state: RuntimeState, scope: InvocationScope, error: unknown): void {
    const { res, target, callbackReservation, progress } = scope;
    const converted = toErrorInfo(error);
    let info = error instanceof NanofaasError ? converted : HANDLER_ERROR;
    try {
        serializeJsonBounded({ error: info }, state.options.maxOutputBytes);
    } catch {
        info = HANDLER_ERROR;
    }
    state.metrics.invocations.inc({ success: "false" });
    progress.callbackDispatched = dispatchCallback(
        state, callbackReservation, target, failurePayload(info),
    );
    if (!progress.callbackDispatched && target.callbackUrl) {
        info = HANDLER_ERROR;
        progress.callbackDispatched = dispatchCallback(
            state, callbackReservation, target, failurePayload(info),
        );
    }
    writeJson(res, statusCodeForError(info), { error: info });
}

/** Errors the runtime reports itself rather than as a handler callback. */
const LOCAL_ONLY_ERROR_CODES = new Set([
    INPUT_TOO_LARGE.code, BODY_TIMEOUT.code, INVOCATION_CANCELLED.code, RUNTIME_STOPPING.code,
]);

function writeInvocationError(state: RuntimeState, scope: InvocationScope, error: unknown): void {
    const { req, res, target, callbackReservation, progress } = scope;
    const info = toErrorInfo(error);
    state.metrics.invocations.inc({ success: "false" });
    if (!LOCAL_ONLY_ERROR_CODES.has(info.code)) {
        progress.callbackDispatched = dispatchCallback(
            state, callbackReservation, target, failurePayload(info),
        );
    }
    if (info.code === RUNTIME_STOPPING.code) {
        writeEarlyRejection(req, res, 503, { error: info }, { "retry-after": "1" });
    } else {
        writeJson(res, statusCodeForError(info), { error: info });
    }
}

function coldStartHeaders(state: RuntimeState, coldStart: boolean): Record<string, string> {
    if (!coldStart) return {};
    return {
        "x-cold-start": "true",
        "x-init-duration-ms": String(Date.now() - state.startedAt),
    };
}

/** Adds an envelope's allowed headers to the response and returns its status and callback. */
function applyEnvelope(
    state: RuntimeState,
    result: HandlerResponse,
    target: CallbackTarget,
    responseHeaders: Record<string, string>,
): { statusCode: number; callbackPayload: CallbackPayload } {
    const allowed = filterAllowedHeaders(result.headers);
    const dropped = Object.keys(result.headers).filter((key) => !(key in allowed));
    if (dropped.length > 0) {
        state.logger.warn("dropped response header(s)", {
            executionId: target.executionId,
            dropped: dropped.join(", "),
        });
    }
    Object.assign(responseHeaders, allowed);
    responseHeaders["x-nanofaas-function-status"] = "true";
    if (result.encoding !== undefined) {
        responseHeaders["x-nanofaas-encoding"] = result.encoding;
    }
    return {
        statusCode: result.statusCode,
        callbackPayload: {
            success: true,
            output: result.output,
            error: null,
            statusCode: result.statusCode,
            headers: allowed,
            ...(result.encoding === undefined ? {} : { encoding: result.encoding }),
        },
    };
}

function writeInvokeResult(
    state: RuntimeState,
    res: ServerResponse,
    result: JsonValue | HandlerResponse,
    target: CallbackTarget,
    callbackReservation: CallbackReservation | undefined,
    coldStart: boolean,
): boolean {
    const isEnvelope = result instanceof HandlerResponse;
    if (isEnvelope && !isStatusCodeValid(result.statusCode)) {
        state.logger.warn("Handler returned invalid statusCode", {
            executionId: target.executionId,
        });
        state.metrics.invocations.inc({ success: "false" });
        const dispatched = dispatchCallback(
            state, callbackReservation, target, failurePayload(INVALID_RESPONSE_STATUS),
        );
        writeJson(res, 500, { error: INVALID_RESPONSE_STATUS });
        return dispatched;
    }

    const output = isEnvelope ? result.output : result;
    let serializedOutput: string;
    try {
        serializedOutput = serializeJsonBounded(output, state.options.maxOutputBytes);
    } catch {
        state.metrics.invocations.inc({ success: "false" });
        const dispatched = dispatchCallback(
            state, callbackReservation, target, failurePayload(OUTPUT_TOO_LARGE),
        );
        writeJson(res, 500, { error: OUTPUT_TOO_LARGE });
        return dispatched;
    }
    const outputBytes = Buffer.byteLength(serializedOutput, "utf8");
    state.outputBytes += outputBytes;
    state.metrics.outputBytes.inc(outputBytes);
    const responseHeaders = coldStartHeaders(state, coldStart);
    const { statusCode, callbackPayload }: { statusCode: number; callbackPayload: CallbackPayload } = isEnvelope
        ? applyEnvelope(state, result, target, responseHeaders)
        : { statusCode: 200, callbackPayload: { success: true, output: result, error: null } };

    if (!responseHeadersAreValid(responseHeaders)) {
        state.outputBytes -= outputBytes;
        state.metrics.outputBytes.dec(outputBytes);
        state.metrics.invocations.inc({ success: "false" });
        const dispatched = dispatchCallback(
            state, callbackReservation, target, failurePayload(INVALID_RESPONSE_HEADERS),
        );
        writeJson(res, 500, { error: INVALID_RESPONSE_HEADERS });
        return dispatched;
    }

    let dispatched = dispatchCallback(state, callbackReservation, target, callbackPayload);
    if (!dispatched && target.callbackUrl) {
        dispatched = dispatchCallback(
            state, callbackReservation, target, failurePayload(OUTPUT_TOO_LARGE),
        );
        state.outputBytes -= outputBytes;
        state.metrics.outputBytes.dec(outputBytes);
        state.metrics.invocations.inc({ success: "false" });
        writeJson(res, 500, { error: OUTPUT_TOO_LARGE });
        return dispatched;
    }
    state.metrics.invocations.inc({ success: "true" });
    let released = false;
    const releaseOutput = (): void => {
        if (released) return;
        released = true;
        res.off("finish", releaseOutput);
        res.off("close", releaseOutput);
        state.outputBytes -= outputBytes;
        state.metrics.outputBytes.dec(outputBytes);
    };
    res.once("finish", releaseOutput);
    res.once("close", releaseOutput);
    let written = false;
    try {
        written = writeSerializedJson(res, statusCode, serializedOutput, responseHeaders);
    } finally {
        if (!written) releaseOutput();
    }
    return dispatched;
}

async function routeRequest(
    state: RuntimeState,
    req: IncomingMessage,
    res: ServerResponse,
): Promise<void> {
    const pathname = new URL(req.url ?? "/", "http://127.0.0.1").pathname;
    if (req.method === "GET" && pathname === "/health") {
        writeJson(res, 200, { status: "ok" });
        return;
    }
    if (req.method === "GET" && pathname === "/metrics") {
        res.statusCode = 200;
        res.setHeader("content-type", state.metrics.registry.contentType);
        res.end(await state.metrics.registry.metrics());
        return;
    }
    if (req.method === "POST" && pathname === "/invoke") {
        await handleInvoke(state, req, res);
        return;
    }
    writeEarlyRejection(
        req, res, 404, { error: { code: "NOT_FOUND", message: "Endpoint not found" } },
    );
}

function createRuntimeServer(state: RuntimeState): Server {
    const server = createServer((req, res) => {
        void routeRequest(state, req, res).catch((error) => reportRequestFailure(state, res, error));
    });
    server.on("connection", (socket) => trackConnection(state, socket));
    return server;
}

function reportRequestFailure(state: RuntimeState, res: ServerResponse, error: unknown): void {
    state.logger.error("request handling failed", { error: toErrorInfo(error).message });
    if (!res.headersSent) {
        writeJson(res, 500, { error: { code: "UNHANDLED_ERROR", message: "Internal server error" } });
    } else {
        res.destroy(error instanceof Error ? error : undefined);
    }
}

function trackConnection(state: RuntimeState, socket: Socket): void {
    state.serverConnections.add(socket);
    socket.once("close", () => state.serverConnections.delete(socket));
}

function listen(server: Server, port: number): Promise<void> {
    return new Promise<void>((resolve, reject) => {
        server.once("error", reject);
        server.listen(port, "0.0.0.0", () => {
            server.off("error", reject);
            resolve();
        });
    });
}

function socketClosed(socket: Socket): Promise<void> {
    return socket.destroyed
        ? Promise.resolve()
        : new Promise<void>((resolve) => socket.once("close", resolve));
}

function closeServer(server: Server | undefined): Promise<void> {
    if (!server) return Promise.resolve();
    return new Promise<void>((resolve) => {
        server.close(() => resolve());
        server.closeIdleConnections();
    });
}

async function settleWithin(promises: Iterable<Promise<unknown>>, timeoutMs: number): Promise<void> {
    let timer: NodeJS.Timeout | undefined;
    await Promise.race([
        Promise.allSettled(promises).then(() => undefined),
        new Promise<void>((resolve) => { timer = setTimeout(resolve, timeoutMs); }),
    ]);
    if (timer) clearTimeout(timer);
}

export function createRuntime(options: RuntimeOptions = {}): Runtime {
    const state: RuntimeState = {
        options: resolveOptions(options),
        handlers: new Map(),
        server: undefined,
        serverConnections: new Set(),
        port: undefined,
        firstInvocation: true,
        startedAt: Date.now(),
        metrics: createMetrics(),
        logger: createLogger("nanofaas.runtime"),
        callbackController: new AbortController(),
        callbacks: new Set(),
        handlerTasks: new Set(),
        requestControllers: new Set(),
        handlerReservations: 0,
        inputBytes: 0,
        outputBytes: 0,
        pendingCallbacks: 0,
        pendingCallbackBytes: 0,
        serializedCallbackBytes: 0,
        stopping: false,
        startPromise: undefined,
        stopPromise: undefined,
    };

    const runtime: Runtime = {
        register(name: string, handler: Handler): Runtime {
            state.handlers.set(name, handler);
            return this;
        },
        async start(): Promise<void> {
            selectHandler(state);
            if (state.stopPromise) await state.stopPromise;
            if (state.startPromise) return state.startPromise;
            const startPromise = (async (): Promise<void> => {
                if (state.server) return;
                state.stopping = false;
                if (state.callbackController.signal.aborted) {
                    state.callbackController = new AbortController();
                }
                const server = createRuntimeServer(state);
                state.server = server;
                try {
                    await listen(server, resolvePort(options.port));
                    const address = server.address();
                    if (!address || typeof address === "string") {
                        throw new Error("Runtime did not bind to a TCP port");
                    }
                    state.port = address.port;
                } catch (error) {
                    state.server = undefined;
                    state.port = undefined;
                    server.closeAllConnections();
                    throw error;
                }
            })();
            state.startPromise = startPromise;
            try {
                await startPromise;
            } finally {
                if (state.startPromise === startPromise) state.startPromise = undefined;
            }
        },
        async stop(): Promise<void> {
            if (state.stopPromise) return state.stopPromise;
            state.stopping = true;
            state.callbackController.abort();
            for (const controller of state.requestControllers) controller.abort();
            const pendingStart = state.startPromise;
            const shutdownDeadline = Date.now() + state.options.shutdownTimeoutMs;
            state.stopPromise = (async () => {
                if (pendingStart) await pendingStart.catch(() => undefined);
                await new Promise<void>((resolve) => setImmediate(resolve));
                await new Promise<void>((resolve) => setImmediate(resolve));
                const server = state.server;
                const connections = [...state.serverConnections];
                const connectionsClosed = connections.map(socketClosed);
                state.server = undefined;
                const closePromise = closeServer(server);
                await settleWithin(
                    [closePromise, ...state.callbacks, ...state.handlerTasks],
                    Math.max(0, shutdownDeadline - Date.now()),
                );
                for (const socket of connections) socket.destroy();
                server?.closeAllConnections();
                await Promise.all(connectionsClosed);
                await new Promise<void>((resolve) => setImmediate(resolve));
                await new Promise<void>((resolve) => setImmediate(resolve));
                state.port = undefined;
            })().finally(() => {
                state.stopPromise = undefined;
            });
            return state.stopPromise;
        },
        get port(): number {
            if (state.port === undefined) throw new Error("Runtime has not started yet");
            return state.port;
        },
        get baseUrl(): string {
            return `http://127.0.0.1:${this.port}`;
        },
    };
    return runtime;
}
