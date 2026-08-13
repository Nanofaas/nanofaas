import QRCode from "qrcode";
import { HandlerResponse, type Handler, type JsonObject } from "nanofaas-function-sdk";

function isJsonObject(value: unknown): value is JsonObject {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

const error = (message: string) => new HandlerResponse({ error: message }, 422);

export const handleQRCode: Handler = async (_, request) => {
    if (!isJsonObject(request.input)) return error("Input must be a JSON object");
    if (!("text" in request.input)) return error("missing required field: text");
    const { text } = request.input;
    if (typeof text !== "string" || !text) return error("field 'text' must be a non-empty string");
    if (Buffer.byteLength(text, "utf8") > 1024) return error("field 'text' must be at most 1024 UTF-8 bytes");
    const rawSize = request.input.size;
    const size = rawSize === undefined ? 256 : rawSize;
    if (typeof size !== "number" || !Number.isInteger(size) || size < 128 || size > 1024) return error("field 'size' must be an integer between 128 and 1024");
    const png = await QRCode.toBuffer(text, { width: size, errorCorrectionLevel: "M" });
    return new HandlerResponse(Buffer.from(png).toString("base64"), 200, { "Content-Type": "image/png" }, "base64");
};
