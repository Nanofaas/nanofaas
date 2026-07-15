import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { URL } from "node:url";

import { runWithContext } from "./context.js";
import { NanofaasError, TimeoutError, toErrorInfo } from "./errors.js";
import { createLogger } from "./logger.js";
import { createMetrics, type RuntimeMetrics } from "./metrics.js";
import type {
    CallbackPayload,
    Handler,
    HandlerContext,
    InvocationRequest,
    JsonObject,
    JsonValue,
    Runtime,
    RuntimeOptions,
} from "./types.js";

const DEFAULT_HANDLER_TIMEOUT_MS = 30_000;
const DEFAULT_CALLBACK_QUEUE_SIZE = 128;
const CALLBACK_RETRY_DELAYS_MS = [100, 500, 2_000];

type RuntimeState = {
    options: Required<Pick<RuntimeOptions, "handlerTimeoutMs" | "callbackQueueSize">> & RuntimeOptions;
    handlers: Map<string, Handler>;
    server: Server | undefined;
    port: number | undefined;
    firstInvocation: boolean;
    startedAt: number;
    metrics: RuntimeMetrics;
    logger: ReturnType<typeof createLogger>;
    callbackController: AbortController;
    callbacks: Set<Promise<void>>;
};

function readEnvString(name: string): string | undefined {
    const value = process.env[name]?.trim();
    return value ? value : undefined;
}

function resolvePort(port?: number): number {
    if (port !== undefined) {
        return port;
    }
    const raw = readEnvString("PORT");
    if (!raw) {
        return 8080;
    }
    const parsed = Number(raw);
    if (!Number.isInteger(parsed) || parsed < 0) {
        throw new Error(`Invalid PORT value: ${raw}`);
    }
    return parsed;
}

function resolveHandlerTimeoutMs(timeout?: number): number {
    if (timeout !== undefined) {
        return timeout;
    }
    const raw = readEnvString("NANOFAAS_HANDLER_TIMEOUT");
    if (!raw) {
        return DEFAULT_HANDLER_TIMEOUT_MS;
    }
    const parsed = Number(raw);
    if (!Number.isFinite(parsed) || parsed <= 0) {
        throw new Error(`Invalid NANOFAAS_HANDLER_TIMEOUT value: ${raw}`);
    }
    return parsed;
}

function isJsonObject(value: unknown): value is JsonObject {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isMetadataRecord(value: unknown): value is Record<string, string> {
    if (!isJsonObject(value)) {
        return false;
    }
    return Object.values(value).every((entry) => typeof entry === "string");
}

async function readJson(req: IncomingMessage): Promise<unknown> {
    const chunks: Buffer[] = [];
    for await (const chunk of req) {
        chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
    }
    if (chunks.length === 0) {
        return {};
    }
    try {
        return JSON.parse(Buffer.concat(chunks).toString("utf8"));
    } catch {
        throw new NanofaasError("INVALID_JSON", "Request body must be valid JSON");
    }
}

function normalizeInvocationRequest(payload: unknown): InvocationRequest {
    if (isJsonObject(payload)) {
        const input = "input" in payload ? (payload.input as JsonValue) : (payload as JsonValue);
        if (payload.metadata !== undefined && payload.metadata !== null && !isMetadataRecord(payload.metadata)) {
            throw new NanofaasError("INVALID_REQUEST", "Invocation metadata must be a string map");
        }
        const metadata = isMetadataRecord(payload.metadata) ? payload.metadata : undefined;
        return metadata === undefined ? { input } : { input, metadata };
    }
    return { input: payload as JsonValue };
}

function writeJson(
    res: ServerResponse,
    statusCode: number,
    payload: JsonValue,
    headers?: Record<string, string>,
): void {
    res.statusCode = statusCode;
    res.setHeader("content-type", "application/json; charset=utf-8");
    for (const [key, value] of Object.entries(headers ?? {})) {
        res.setHeader(key, value);
    }
    res.end(JSON.stringify(payload));
}

function selectHandler(state: RuntimeState): Handler {
    if (state.handlers.size === 0) {
        throw new Error("No handlers registered");
    }

    const selectedName = state.options.functionHandler ?? readEnvString("FUNCTION_HANDLER");
    if (selectedName) {
        const handler = state.handlers.get(selectedName);
        if (!handler) {
            throw new Error(`Configured handler "${selectedName}" was not registered`);
        }
        return handler;
    }

    if (state.handlers.size > 1) {
        throw new Error("Found multiple handlers but FUNCTION_HANDLER is not set");
    }

    return state.handlers.values().next().value as Handler;
}

function callbackUrlForRequest(state: RuntimeState, req: IncomingMessage): string | undefined {
    const header = req.headers["x-callback-url"];
    if (typeof header === "string" && header.trim() !== "") {
        return header.trim();
    }
    return state.options.callbackUrl ?? readEnvString("CALLBACK_URL");
}

function buildCallbackUrl(baseUrl: string, executionId: string): string {
    return `${baseUrl.replace(/\/+$/, "")}/${encodeURIComponent(executionId)}:complete`;
}

async function sendCallback(
    state: RuntimeState,
    callbackUrl: string | undefined,
    executionId: string,
    traceId: string | undefined,
    dispatchAttempt: string | undefined,
    payload: CallbackPayload,
    signal: AbortSignal,
): Promise<void> {
    if (!callbackUrl) {
        return;
    }

    const url = buildCallbackUrl(callbackUrl, executionId);
    const headers: Record<string, string> = {
        "content-type": "application/json",
    };
    if (traceId) {
        headers["x-trace-id"] = traceId;
    }
    if (dispatchAttempt) {
        headers["x-dispatch-attempt"] = dispatchAttempt;
    }

    for (let attempt = 0; attempt < CALLBACK_RETRY_DELAYS_MS.length; attempt += 1) {
        try {
            const response = await fetch(url, {
                method: "POST",
                headers,
                body: JSON.stringify(payload),
                signal,
            });
            if (response.ok) return;
            const permanent = response.status >= 400
                && response.status < 500
                && response.status !== 408
                && response.status !== 429;
            if (permanent || attempt === CALLBACK_RETRY_DELAYS_MS.length - 1) {
                state.metrics.callbackFailures.inc();
                state.logger.warn("callback delivery failed", { executionId, statusCode: response.status });
                return;
            }
        } catch (error) {
            if (signal.aborted) return;
            if (attempt === CALLBACK_RETRY_DELAYS_MS.length - 1) {
                state.metrics.callbackFailures.inc();
                state.logger.warn("callback delivery failed", {
                    executionId,
                    error: toErrorInfo(error).message,
                });
                return;
            }
        }

        await new Promise<void>((resolve) => {
            const timer = setTimeout(resolve, CALLBACK_RETRY_DELAYS_MS[attempt]);
            signal.addEventListener("abort", () => {
                clearTimeout(timer);
                resolve();
            }, { once: true });
        });
        if (signal.aborted) return;
    }
}

function dispatchCallback(
    state: RuntimeState,
    callbackUrl: string | undefined,
    executionId: string,
    traceId: string | undefined,
    dispatchAttempt: string | undefined,
    payload: CallbackPayload,
): void {
    if (!callbackUrl) return;
    if (state.callbacks.size >= state.options.callbackQueueSize) {
        state.metrics.callbackFailures.inc();
        return;
    }
    const callback = sendCallback(
        state,
        callbackUrl,
        executionId,
        traceId,
        dispatchAttempt,
        payload,
        state.callbackController.signal,
    );
    state.callbacks.add(callback);
    void callback.finally(() => state.callbacks.delete(callback));
}

async function invokeHandler(
    state: RuntimeState,
    handler: Handler,
    ctx: HandlerContext,
    request: InvocationRequest,
): Promise<JsonValue> {
    const timeoutController = new AbortController();
    const timer = setTimeout(() => {
        timeoutController.abort();
    }, state.options.handlerTimeoutMs);

    const mergedSignal = AbortSignal.any([ctx.signal, timeoutController.signal]);
    const effectiveContext: HandlerContext = {
        ...ctx,
        signal: mergedSignal,
    };

    try {
        const output = await Promise.race([
            runWithContext(
                {
                    executionId: ctx.executionId,
                    ...(ctx.traceId === undefined ? {} : { traceId: ctx.traceId }),
                },
                () => Promise.resolve(handler(effectiveContext, request)),
            ),
            new Promise<JsonValue>((_, reject) => {
                mergedSignal.addEventListener(
                    "abort",
                    () => reject(new TimeoutError()),
                    { once: true },
                );
            }),
        ]);
        return output;
    } finally {
        clearTimeout(timer);
    }
}

async function handleInvoke(state: RuntimeState, req: IncomingMessage, res: ServerResponse): Promise<void> {
    const handler = selectHandler(state);
    const executionIdHeader = req.headers["x-execution-id"];
    const traceIdHeader = req.headers["x-trace-id"];
    const dispatchAttemptHeader = req.headers["x-dispatch-attempt"];
    const executionId = typeof executionIdHeader === "string" && executionIdHeader.trim() !== ""
        ? executionIdHeader.trim()
        : readEnvString("EXECUTION_ID");
    const traceId = typeof traceIdHeader === "string" && traceIdHeader.trim() !== ""
        ? traceIdHeader.trim()
        : readEnvString("TRACE_ID");
    const dispatchAttempt = typeof dispatchAttemptHeader === "string" && dispatchAttemptHeader.trim() !== ""
        ? dispatchAttemptHeader.trim()
        : undefined;

    if (!executionId) {
        writeJson(res, 400, {
            error: {
                code: "EXECUTION_ID_REQUIRED",
                message: "Execution ID required",
            },
        });
        return;
    }

    const callbackUrl = callbackUrlForRequest(state, req);
    const coldStart = state.firstInvocation;
    state.firstInvocation = false;
    if (coldStart) {
        state.metrics.coldStarts.inc();
    }

    state.metrics.inFlight.inc();
    const timer = state.metrics.duration.startTimer();
    const requestSignal = new AbortController();

    try {
        const payload = normalizeInvocationRequest(await readJson(req));
        const ctx: HandlerContext = {
            executionId,
            logger: createLogger("nanofaas.handler"),
            signal: requestSignal.signal,
            isColdStart: coldStart,
            ...(traceId === undefined ? {} : { traceId }),
        };

        const output = await invokeHandler(state, handler, ctx, payload);
        state.metrics.invocations.inc({ success: "true" });

        const responseHeaders: Record<string, string> = {};
        if (coldStart) {
            responseHeaders["x-cold-start"] = "true";
            responseHeaders["x-init-duration-ms"] = String(Date.now() - state.startedAt);
        }

        dispatchCallback(state, callbackUrl, executionId, traceId, dispatchAttempt, {
            success: true,
            output,
            error: null,
        });

        writeJson(res, 200, output, responseHeaders);
    } catch (error) {
        const info = toErrorInfo(error);
        const status = info.code === "HANDLER_TIMEOUT"
            ? 504
            : info.code === "INVALID_JSON" || info.code === "INVALID_REQUEST"
                ? 400
                : 500;
        state.metrics.invocations.inc({ success: "false" });

        dispatchCallback(state, callbackUrl, executionId, traceId, dispatchAttempt, {
            success: false,
            output: null,
            error: info,
        });

        writeJson(res, status, {
            error: info,
        });
    } finally {
        requestSignal.abort();
        timer();
        state.metrics.inFlight.dec();
    }
}

async function routeRequest(state: RuntimeState, req: IncomingMessage, res: ServerResponse): Promise<void> {
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

    writeJson(res, 404, {
        error: {
            code: "NOT_FOUND",
            message: "Endpoint not found",
        },
    });
}

export function createRuntime(options: RuntimeOptions = {}): Runtime {
    const state: RuntimeState = {
        options: {
            ...options,
            handlerTimeoutMs: resolveHandlerTimeoutMs(options.handlerTimeoutMs),
            callbackQueueSize: options.callbackQueueSize ?? DEFAULT_CALLBACK_QUEUE_SIZE,
        },
        handlers: new Map<string, Handler>(),
        server: undefined,
        port: undefined,
        firstInvocation: true,
        startedAt: Date.now(),
        metrics: createMetrics(),
        logger: createLogger("nanofaas.runtime"),
        callbackController: new AbortController(),
        callbacks: new Set<Promise<void>>(),
    };

    return {
        register(name: string, handler: Handler): Runtime {
            state.handlers.set(name, handler);
            return this;
        },

        async start(): Promise<void> {
            selectHandler(state);
            if (state.server) {
                return;
            }

            if (state.callbackController.signal.aborted) {
                state.callbackController = new AbortController();
            }

            const requestedPort = resolvePort(options.port);
            state.server = createServer((req, res) => {
                void routeRequest(state, req, res).catch((error) => {
                    state.logger.error("request handling failed", {
                        error: toErrorInfo(error).message,
                    });
                    if (!res.headersSent) {
                        writeJson(res, 500, {
                            error: {
                                code: "UNHANDLED_ERROR",
                                message: "Internal server error",
                            },
                        });
                    } else {
                        res.destroy(error instanceof Error ? error : undefined);
                    }
                });
            });

            await new Promise<void>((resolve, reject) => {
                state.server?.once("error", reject);
                state.server?.listen(requestedPort, "0.0.0.0", () => {
                    state.server?.off("error", reject);
                    resolve();
                });
            });

            const address = state.server.address();
            if (!address || typeof address === "string") {
                throw new Error("Runtime did not bind to a TCP port");
            }
            state.port = address.port;
        },

        async stop(): Promise<void> {
            state.callbackController.abort();
            await Promise.allSettled(state.callbacks);
            if (!state.server) {
                return;
            }
            const server = state.server;
            state.server = undefined;
            await new Promise<void>((resolve, reject) => {
                server.close((error) => {
                    if (error) {
                        reject(error);
                        return;
                    }
                    resolve();
                });
            });
        },

        get port(): number {
            if (state.port === undefined) {
                throw new Error("Runtime has not started yet");
            }
            return state.port;
        },

        get baseUrl(): string {
            return `http://127.0.0.1:${this.port}`;
        },
    };
}
