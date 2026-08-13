import { HandlerResponse, type Handler, type JsonObject } from "nanofaas-function-sdk";

const ROMAN_TABLE = [
    [1000, "M"], [900, "CM"], [500, "D"], [400, "CD"],
    [100, "C"], [90, "XC"], [50, "L"], [40, "XL"],
    [10, "X"], [9, "IX"], [5, "V"], [4, "IV"], [1, "I"],
] as const;

function isJsonObject(value: unknown): value is JsonObject {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function toRoman(number: number): string {
    let remaining = number;
    let roman = "";
    for (const [value, symbol] of ROMAN_TABLE) {
        while (remaining >= value) {
            roman += symbol;
            remaining -= value;
        }
    }
    return roman;
}

export const handleRomanNumeral: Handler = async (ctx, req) => {
    ctx.logger.info("roman-numeral invoked");

    if (!isJsonObject(req.input)) {
        return new HandlerResponse({ error: "Input must be a JSON object" }, 422);
    }
    if (!("number" in req.input)) {
        return new HandlerResponse({ error: "missing required field: number" }, 422);
    }

    const rawNumber = req.input.number;
    if (typeof rawNumber !== "number" || !Number.isFinite(rawNumber)) {
        return new HandlerResponse({ error: "field 'number' must be an integer" }, 422);
    }

    const number = Math.trunc(rawNumber);
    if (number < 1 || number > 3999) {
        return new HandlerResponse({ error: `number must be between 1 and 3999, got: ${number}` }, 422);
    }

    return { roman: toRoman(number) };
};
