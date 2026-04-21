const NON_WORD_PATTERN = /[^\p{L}\p{N}\s]/gu;
function toObject(input) {
    if (typeof input === "string") {
        return { text: input };
    }
    if (typeof input === "object" && input !== null && !Array.isArray(input)) {
        return input;
    }
    return {};
}
export const handleWordStats = async (ctx, req) => {
    ctx.logger.info("processing word stats");
    const input = toObject(req.input);
    const text = typeof input.text === "string" ? input.text : "";
    if (text.trim() === "") {
        return {
            error: "Field 'text' is required and must be non-empty",
        };
    }
    const requestedTopN = typeof input.topN === "number" ? Math.floor(input.topN) : 10;
    const topN = requestedTopN > 0 ? requestedTopN : 10;
    const normalized = text
        .toLowerCase()
        .replace(NON_WORD_PATTERN, " ")
        .trim();
    if (normalized === "") {
        return {
            error: "No words found in input",
        };
    }
    const words = normalized.split(/\s+/);
    const frequencies = new Map();
    let totalLength = 0;
    for (const word of words) {
        frequencies.set(word, (frequencies.get(word) ?? 0) + 1);
        totalLength += word.length;
    }
    const topWords = [...frequencies.entries()]
        .sort((left, right) => {
        if (left[1] !== right[1]) {
            return right[1] - left[1];
        }
        return left[0].localeCompare(right[0]);
    })
        .slice(0, topN)
        .map(([word, count]) => ({ word, count }));
    return {
        wordCount: words.length,
        uniqueWords: frequencies.size,
        topWords,
        averageWordLength: Math.round((totalLength / words.length) * 100) / 100,
    };
};
