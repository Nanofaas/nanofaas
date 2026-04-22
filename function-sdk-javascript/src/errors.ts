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

export class InvalidJsonError extends Error {
    constructor(message = "Request body must be valid JSON") {
        super(message);
        this.name = "InvalidJsonError";
    }
}

export class InvalidRequestError extends Error {
    constructor(message: string) {
        super(message);
        this.name = "InvalidRequestError";
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
    if (error instanceof InvalidJsonError) {
        return {
            code: "INVALID_JSON",
            message: error.message,
        };
    }
    if (error instanceof InvalidRequestError) {
        return {
            code: "INVALID_REQUEST",
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
