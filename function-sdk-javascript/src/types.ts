export type JsonPrimitive = string | number | boolean | null;

export type JsonValue =
    | JsonPrimitive
    | { [key: string]: JsonValue }
    | JsonValue[];

export type JsonObject = { [key: string]: JsonValue };

export type InvocationRequest = {
    input: JsonValue;
    metadata?: Record<string, string>;
};

export type ErrorInfo = {
    code: string;
    message: string;
};

export type CallbackPayload =
    | {
        success: true;
        output: JsonValue;
        error: null;
    }
    | {
        success: false;
        output: null;
        error: ErrorInfo;
    };

export interface Logger {
    debug(message: string, fields?: Record<string, JsonValue>): void;
    info(message: string, fields?: Record<string, JsonValue>): void;
    warn(message: string, fields?: Record<string, JsonValue>): void;
    error(message: string, fields?: Record<string, JsonValue>): void;
}

export type HandlerContext = {
    executionId: string;
    traceId?: string;
    logger: Logger;
    signal: AbortSignal;
    isColdStart: boolean;
};

export type Handler = (
    ctx: HandlerContext,
    req: InvocationRequest,
) => JsonValue | Promise<JsonValue>;

export type RuntimeOptions = {
    port?: number;
    handlerTimeoutMs?: number;
    callbackUrl?: string;
    functionHandler?: string;
};

export interface Runtime {
    register(name: string, handler: Handler): Runtime;
    start(): Promise<void>;
    stop(): Promise<void>;
    readonly port: number;
    readonly baseUrl: string;
}
