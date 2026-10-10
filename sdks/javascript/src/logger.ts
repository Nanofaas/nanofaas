import { getExecutionId, getTraceId } from "./context.js";
import type { JsonValue, Logger } from "./types.js";

type Level = "debug" | "info" | "warn" | "error";

function write(level: Level, name: string, message: string, fields?: Record<string, JsonValue>): void {
    const format = process.env.NANOFAAS_LOG_FORMAT ?? "text";
    const executionId = getExecutionId(), traceId = getTraceId();
    let line: string;
    if (format === "json") {
        const entry = {
            schemaVersion: 1,
            timestamp: new Date().toISOString(),
            level: level.toUpperCase(),
            service: process.env.NANOFAAS_LOG_SERVICE || "function-runtime",
            logger: name,
            message,
            ...(executionId ? {executionId} : {}),
            ...(traceId ? {traceId} : {}),
            ...(fields && Object.keys(fields).length ? {attributes: fields} : {}),
        };
        line = JSON.stringify(entry);
    } else {
        line = `${level.toUpperCase()} ${name} [exec=${executionId ?? ""} trace=${traceId ?? ""}] ${message}`;
    }
    if (level === "error") console.error(line); else console.log(line);
}

export function createLogger(name = "nanofaas"): Logger {
    return {
        debug(message, fields) { write("debug", name, message, fields); },
        info(message, fields) { write("info", name, message, fields); },
        warn(message, fields) { write("warn", name, message, fields); },
        error(message, fields) { write("error", name, message, fields); },
    };
}
export function getLogger(name = "nanofaas"): Logger { return createLogger(name); }
