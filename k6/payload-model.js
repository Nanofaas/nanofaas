export function parsePositiveInt(rawValue, fallbackValue) {
    const parsed = Number.parseInt(rawValue || `${fallbackValue}`, 10);
    if (Number.isNaN(parsed) || parsed < 1) {
        return fallbackValue;
    }
    return parsed;
}

function pick(items, seed, salt = 0) {
    return items[(seed * 31 + salt * 17) % items.length];
}

function effectiveSeed(index, randomFn) {
    if (typeof index === 'number' && index >= 0) {
        return index;
    }
    return Math.floor(randomFn() * 1_000_000);
}

export function selectPayloadIndex(mode, poolSize, iterationInTest, randomFn = Math.random) {
    if (mode === 'pool-sequential') {
        return iterationInTest % poolSize;
    }
    if (mode === 'pool-random') {
        const candidate = Math.floor(randomFn() * poolSize);
        return Math.min(poolSize - 1, Math.max(0, candidate));
    }
    return -1;
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
        const adjective = pick(adjectives, seed, i + 1);
        const noun = pick(nouns, seed, i + 7);
        const verb = pick(verbs, seed, i + 13);
        const adverb = pick(adverbs, seed, i + 19);
        const repeat = 1 + ((seed + i * 3) % 4);
        for (let j = 0; j < repeat; j++) {
            parts.push(`The ${adjective} ${noun} ${verb} ${adverb}`);
        }
    }

    return {
        text: `${parts.join('. ')}.`,
        topN: 3 + (seed % 6),
    };
}

export function buildJsonTransformInput(index, randomFn = Math.random) {
    const seed = effectiveSeed(index, randomFn);
    const operations = ['count', 'sum', 'avg', 'min', 'max'];
    const groupFields = ['dept', 'region', 'tier'];
    const departments = ['eng', 'sales', 'hr', 'marketing', 'finance', 'ops'];
    const regions = ['emea', 'na', 'apac', 'latam'];
    const tiers = ['junior', 'mid', 'senior'];

    const operation = pick(operations, seed);
    const groupBy = pick(groupFields, seed, 3);
    const valueField = pick(['salary', 'age', 'score'], seed, 11);
    const rows = 8 + (seed % 24);

    const data = [];
    for (let i = 0; i < rows; i++) {
        data.push({
            dept: pick(departments, seed, i + 1),
            region: pick(regions, seed, i + 5),
            tier: pick(tiers, seed, i + 9),
            salary: 40000 + ((seed * 37 + i * 173) % 90000),
            age: 22 + ((seed * 11 + i * 5) % 35),
            score: 50 + ((seed * 13 + i * 7) % 51),
        });
    }

    const input = { data: data, groupBy: groupBy, operation: operation };
    if (operation !== 'count') {
        input.valueField = valueField;
    }
    return input;
}
