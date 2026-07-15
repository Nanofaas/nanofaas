const FAMILIES = new Set(['word-stats', 'json-transform', 'roman-numeral']);
const PROFILES = new Set(['small', 'medium', 'large']);

export function loadBenchmarkConfig(env) {
    const functionName = env.NANOFAAS_FUNCTION?.trim();
    const family = env.NANOFAAS_FAMILY?.trim().toLowerCase();
    const profile = (env.K6_PAYLOAD_PROFILE || 'small').trim().toLowerCase();
    const selection = (env.K6_PAYLOAD_SELECTION || 'sequential').trim().toLowerCase();
    if (!functionName) throw new Error('NANOFAAS_FUNCTION is required');
    if (!FAMILIES.has(family)) throw new Error(`unknown function family: ${family}`);
    if (!PROFILES.has(profile)) throw new Error(`unknown payload profile: ${profile}`);
    if (!['sequential', 'random'].includes(selection)) {
        throw new Error(`unknown payload selection mode: ${selection}`);
    }
    return { functionName, family, profile, selection };
}

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
    if (mode === 'sequential') {
        return iterationInTest % corpusSize;
    }
    if (mode === 'random') {
        const candidate = Math.floor(randomFn() * corpusSize);
        return Math.min(corpusSize - 1, Math.max(0, candidate));
    }
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
