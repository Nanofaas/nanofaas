import type { Handler, JsonObject } from "nanofaas-function-sdk";

function isJsonObject(value: unknown): value is JsonObject {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function aggregate(items: JsonObject[], field: string, operation: string): number {
    const values = items
        .map((item) => item[field])
        .filter((value): value is number => typeof value === "number");
    if (values.length === 0) {
        return 0;
    }
    if (operation === "sum") return values.reduce((total, value) => total + value, 0);
    if (operation === "avg") return values.reduce((total, value) => total + value, 0) / values.length;
    if (operation === "min") return Math.min(...values);
    return Math.max(...values);
}

export const handleJsonTransform: Handler = async (ctx, req) => {
    ctx.logger.info("processing json transform");

    if (!isJsonObject(req.input)) {
        return { error: "Input must be a JSON object" };
    }

    const data = req.input.data;
    const groupBy = req.input.groupBy;
    const operation = typeof req.input.operation === "string" ? req.input.operation : "count";
    const valueField = req.input.valueField;
    if (!Array.isArray(data) || typeof groupBy !== "string") {
        return { error: "Fields 'data' (array) and 'groupBy' (string) are required" };
    }
    if (operation.toLowerCase() !== "count" && typeof valueField !== "string") {
        return { error: `Field 'valueField' is required for operation: ${operation}` };
    }

    const groups = new Map<string, JsonObject[]>();
    for (const item of data) {
        if (!isJsonObject(item)) continue;
        const rawKey = item[groupBy];
        const key = rawKey == null ? "null" : String(rawKey);
        groups.set(key, [...(groups.get(key) ?? []), item]);
    }

    const result: JsonObject = {};
    for (const [key, items] of groups) {
        const normalized = operation.toLowerCase();
        result[key] = normalized === "count"
            ? items.length
            : ["sum", "avg", "min", "max"].includes(normalized)
                ? aggregate(items, valueField as string, normalized)
                : `unknown operation: ${operation}`;
    }

    return { groupBy, operation, groups: result };
};
