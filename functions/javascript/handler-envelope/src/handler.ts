import type { Handler, JsonObject } from "nanofaas-function-sdk";

function object(value: unknown): JsonObject { return typeof value === "object" && value !== null && !Array.isArray(value) ? value as JsonObject : {}; }

export const handle: Handler = async (_ctx, request) => {
    const input = object(request.input);
    return { body: typeof input.message === "string" ? input.message : "", header: request.headers?.["x-e2e-token"] ?? "" };
};
