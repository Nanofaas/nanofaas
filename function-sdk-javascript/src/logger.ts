import { getExecutionId, getTraceId } from "./context.js";
import type { JsonValue, Logger } from "./types.js";

type Level = "debug" | "info" | "warn" | "error";

function write(level: Level, name: string, message: string, fields?: Record<string, JsonValue>): void {
    const entry = {
        timestamp: new Date().toISOString(),
        level,
        logger: name,
        message,
        executionId: getExecutionId() ?? null,
        traceId: getTraceId() ?? null,
        ...(fields ?? {}),
    };
    const line = JSON.stringify(entry);
    if (level === "error") {
        console.error(line);
        return;
    }
    console.log(line);
}

export function createLogger(name = "nanofaas"): Logger {
    return {
        debug(message, fields) {
            write("debug", name, message, fields);
        },
        info(message, fields) {
            write("info", name, message, fields);
        },
        warn(message, fields) {
            write("warn", name, message, fields);
        },
        error(message, fields) {
            write("error", name, message, fields);
        },
    };
}

export function getLogger(name = "nanofaas"): Logger {
    return createLogger(name);
}
