const FAMILIES = new Set(['word-stats', 'json-transform', 'roman-numeral']);
const PROFILES = new Set(['small', 'medium', 'large']);

export function validateCorpus(corpus, family, profile) {
    if (!FAMILIES.has(family) || corpus?.family !== family) {
        throw new Error(`payload corpus family must be ${family}`);
    }
    if (!PROFILES.has(profile) || corpus.profile !== profile) {
        throw new Error(`payload corpus profile must be ${profile}`);
    }
    if (!Array.isArray(corpus.cases) || corpus.cases.length === 0) {
        throw new Error('payload corpus cases must be a non-empty array');
    }
    if (corpus.cases.some((item) => !item || !Object.hasOwn(item, 'input'))) {
        throw new Error('every payload corpus case must contain input');
    }
    return corpus;
}

export function selectPayloadIndex(mode, corpusSize, iterationInTest, randomFn = Math.random) {
    if (!Number.isInteger(corpusSize) || corpusSize < 1) {
        throw new Error('payload corpus must be non-empty');
    }
    if (mode === 'sequential' || mode === 'pool-sequential') {
        return iterationInTest % corpusSize;
    }
    if (mode === 'random' || mode === 'pool-random') {
        const candidate = Math.floor(randomFn() * corpusSize);
        return Math.min(corpusSize - 1, Math.max(0, candidate));
    }
    // Compatibility for the old wrappers, removed with function-benchmark.js.
    if (mode === 'legacy-random') return -1;
    throw new Error(`unknown payload selection mode: ${mode}`);
}

export function selectPayload(corpus, mode, iterationInTest, randomFn = Math.random) {
    const index = selectPayloadIndex(mode, corpus.cases.length, iterationInTest, randomFn);
    return corpus.cases[index].input;
}

export function hasExpectedOutput(family, body) {
    if (!FAMILIES.has(family)) throw new Error(`unknown function family: ${family}`);
    const output = body?.output;
    if (!output || output.error || body.error) return false;
    if (family === 'word-stats') return typeof output.wordCount === 'number';
    if (family === 'json-transform') {
        return output.groups !== null && typeof output.groups === 'object' && !Array.isArray(output.groups);
    }
    return typeof output.roman === 'string' && output.roman.length > 0;
}

// Legacy generators stay only until the generic benchmark replaces all wrappers.
export function parsePositiveInt(rawValue, fallbackValue) {
    const parsed = Number.parseInt(rawValue || `${fallbackValue}`, 10);
    return Number.isNaN(parsed) || parsed < 1 ? fallbackValue : parsed;
}

function pick(items, seed, salt = 0) {
    return items[(seed * 31 + salt * 17) % items.length];
}

function effectiveSeed(index, randomFn) {
    return typeof index === 'number' && index >= 0
        ? index
        : Math.floor(randomFn() * 1_000_000);
}

export function buildWordStatsInput(index, randomFn = Math.random) {
    const seed = effectiveSeed(index, randomFn);
    const adjectives = ['quick', 'silent', 'brisk', 'patient', 'curious', 'bold', 'calm'];
    const nouns = ['fox', 'dog', 'engineer', 'runner', 'team', 'service', 'cluster'];
    const verbs = ['jumps', 'analyzes', 'builds', 'observes', 'tests', 'measures', 'scales'];
    const adverbs = ['quickly', 'carefully', 'daily', 'smoothly', 'loudly', 'correctly', 'safely'];
    const sentenceCount = 3 + (seed % 5);
    const parts = [];
    for (let i = 0; i < sentenceCount; i++) {
        const sentence = `The ${pick(adjectives, seed, i + 1)} ${pick(nouns, seed, i + 7)} ${pick(verbs, seed, i + 13)} ${pick(adverbs, seed, i + 19)}`;
        const repeat = 1 + ((seed + i * 3) % 4);
        for (let j = 0; j < repeat; j++) parts.push(sentence);
    }
    return {
        text: `${parts.join('. ')}.`,
        topN: 3 + (seed % 6),
    };
}

export function buildJsonTransformInput(index, randomFn = Math.random) {
    const seed = effectiveSeed(index, randomFn);
    const operation = pick(['count', 'sum', 'avg', 'min', 'max'], seed);
    const groupBy = pick(['dept', 'region', 'tier'], seed, 3);
    const valueField = pick(['salary', 'age', 'score'], seed, 11);
    const input = {
        data: Array.from({ length: 8 + (seed % 24) }, (_, offset) => ({
            dept: pick(['eng', 'sales', 'hr', 'marketing', 'finance', 'ops'], seed, offset + 1),
            region: pick(['emea', 'na', 'apac', 'latam'], seed, offset + 5),
            tier: pick(['junior', 'mid', 'senior'], seed, offset + 9),
            salary: 40_000 + ((seed * 37 + offset * 173) % 90_000),
            age: 22 + ((seed * 11 + offset * 5) % 35),
            score: 50 + ((seed * 13 + offset * 7) % 51),
        })),
        groupBy,
        operation,
    };
    if (operation !== 'count') input.valueField = valueField;
    return input;
}
