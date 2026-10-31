import type { Handler } from "nanofaas-function-sdk";

const WORD_CHARACTER = /[\p{L}\p{N}]/u;

function compareScalars(a: string, b: string): number {
    const left = Array.from(a, char => char.codePointAt(0)!);
    const right = Array.from(b, char => char.codePointAt(0)!);
    for (let i = 0; i < Math.min(left.length, right.length); i++) {
        if (left[i] !== right[i]) return left[i]! - right[i]!;
    }
    return left.length - right.length;
}

// The shared word-stats-runtime-v1 contract; see docs/word-stats-contract.md.
export const handleWordStats: Handler = async (_ctx, req) => {
    const input = typeof req.input === "object" && req.input !== null && !Array.isArray(req.input)
        ? req.input as Record<string, unknown> : {};
    const text = typeof req.input === "string" ? req.input : input.text;
    if (typeof text !== "string" || text.length === 0) {
        return {error: "Field 'text' is required and must be non-empty"};
    }
    const requested = typeof input.topN === "number" && Number.isFinite(input.topN) ? input.topN : 10;
    const frequencies = new Map<string, number>();
    let word = "", count = 0, totalLength = 0;
    for (const char of text) {
        if (WORD_CHARACTER.test(char)) {
            word += String.fromCodePoint(char.toLowerCase().codePointAt(0)!);
            totalLength++;
        } else if (word) {
            frequencies.set(word, (frequencies.get(word) ?? 0) + 1);
            count++;
            word = "";
        }
    }
    if (word) {
        frequencies.set(word, (frequencies.get(word) ?? 0) + 1);
        count++;
    }
    if (!count) return {error: "No words found in input"};
    const topN = Math.min(frequencies.size, Math.max(0, Math.floor(requested)));
    const topWords = [...frequencies.entries()]
        .sort((a, b) => b[1] - a[1] || compareScalars(a[0], b[0]))
        .slice(0, topN).map(([word, count]) => ({word, count}));
    const cents = Math.floor((200 * totalLength + count) / (2 * count));
    return {wordCount: count, uniqueWords: frequencies.size, topWords, averageWordLength: cents / 100};
};
