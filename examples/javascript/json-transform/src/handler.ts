import type { Handler, JsonObject, JsonValue } from "nanofaas-function-sdk";

function isJsonObject(value: unknown): value is JsonObject {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function remapObject(data: JsonObject, fieldMap: Record<string, string>): JsonObject {
    const output: JsonObject = {};
    for (const [key, value] of Object.entries(data)) {
        output[fieldMap[key] ?? key] = value as JsonValue;
    }
    return output;
}

export const handleJsonTransform: Handler = async (ctx, req) => {
    ctx.logger.info("processing json transform");

    if (!isJsonObject(req.input)) {
        return { error: "Input must be a JSON object" };
    }

    const data = req.input.data;
    if (!isJsonObject(data)) {
        return { error: "Field 'data' must be a JSON object" };
    }

    const rawFieldMap = req.input.fieldMap;
    const fieldMap: Record<string, string> = isJsonObject(rawFieldMap)
        ? Object.fromEntries(
            Object.entries(rawFieldMap)
                .filter((entry): entry is [string, string] => typeof entry[1] === "string"),
        )
        : {};

    return remapObject(data, fieldMap);
};
