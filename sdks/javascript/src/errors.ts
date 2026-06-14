import type { ErrorInfo } from "./types.js";

export class NanofaasError extends Error {
    readonly code: string;

    constructor(code: string, message: string) {
        super(message);
        this.name = "NanofaasError";
        this.code = code;
    }
}

export class TimeoutError extends Error {
    constructor(message = "Handler execution timed out") {
        super(message);
        this.name = "TimeoutError";
    }
}

export function toErrorInfo(error: unknown): ErrorInfo {
    if (error instanceof NanofaasError) {
        return {
            code: error.code,
            message: error.message,
        };
    }
    if (error instanceof TimeoutError) {
        return {
            code: "HANDLER_TIMEOUT",
            message: error.message,
        };
    }
    if (error instanceof Error) {
        return {
            code: "UNHANDLED_ERROR",
            message: error.message,
        };
    }
    return {
        code: "UNHANDLED_ERROR",
        message: "Unknown error",
    };
}
