import test from 'node:test';
import assert from 'node:assert/strict';

import {
    hasExpectedOutput,
    loadBenchmarkConfig,
    selectPayload,
    selectPayloadIndex,
    validateCorpus,
} from '../payload-model.js';

const corpus = {
    family: 'word-stats',
    profile: 'small',
    cases: [
        { name: 'first', input: { text: 'alpha', topN: 1 } },
        { name: 'second', input: { text: 'beta', topN: 1 } },
    ],
};

test('selectPayloadIndex supports sequential and random selection', () => {
    assert.equal(selectPayloadIndex('sequential', 10, 12, () => 0.5), 2);
    assert.equal(selectPayloadIndex('random', 10, 12, () => 0.5), 5);
});

test('selectPayloadIndex rejects invalid configuration', () => {
    assert.throws(() => selectPayloadIndex('invalid', 10, 0), /selection mode/);
    assert.throws(() => selectPayloadIndex('sequential', 0, 0), /non-empty/);
});

test('validateCorpus accepts matching metadata and cases', () => {
    assert.equal(validateCorpus(corpus, 'word-stats', 'small'), corpus);
});

test('validateCorpus rejects invalid metadata and cases', () => {
    assert.throws(
        () => validateCorpus({ ...corpus, family: 'json-transform' }, 'word-stats', 'small'),
        /family/,
    );
    assert.throws(
        () => validateCorpus({ ...corpus, profile: 'large' }, 'word-stats', 'small'),
        /profile/,
    );
    assert.throws(
        () => validateCorpus({ ...corpus, cases: [] }, 'word-stats', 'small'),
        /non-empty/,
    );
    assert.throws(
        () => validateCorpus({ ...corpus, cases: [{ name: 'missing' }] }, 'word-stats', 'small'),
        /input/,
    );
});

test('selectPayload returns a raw input without transport envelope', () => {
    assert.deepEqual(selectPayload(corpus, 'sequential', 1), { text: 'beta', topN: 1 });
});

test('hasExpectedOutput validates the minimal family output shape', () => {
    assert.equal(hasExpectedOutput('word-stats', { output: { wordCount: 1 } }), true);
    assert.equal(hasExpectedOutput('json-transform', { output: { groups: {} } }), true);
    assert.equal(hasExpectedOutput('roman-numeral', { output: { roman: 'IV' } }), true);
    assert.equal(hasExpectedOutput('word-stats', { output: { error: 'bad' } }), false);
    assert.throws(() => hasExpectedOutput('unknown', { output: {} }), /family/);
});

test('loadBenchmarkConfig validates required values and applies defaults', () => {
    assert.deepEqual(
        loadBenchmarkConfig({
            NANOFAAS_FUNCTION: 'word-stats-java',
            NANOFAAS_FAMILY: 'word-stats',
        }),
        {
            functionName: 'word-stats-java',
            family: 'word-stats',
            profile: 'small',
            selection: 'sequential',
        },
    );
});

test('loadBenchmarkConfig rejects missing and unknown values', () => {
    assert.throws(() => loadBenchmarkConfig({ NANOFAAS_FAMILY: 'word-stats' }), /function/i);
    assert.throws(
        () => loadBenchmarkConfig({ NANOFAAS_FUNCTION: 'fn', NANOFAAS_FAMILY: 'unknown' }),
        /family/,
    );
    assert.throws(
        () => loadBenchmarkConfig({
            NANOFAAS_FUNCTION: 'fn',
            NANOFAAS_FAMILY: 'word-stats',
            K6_PAYLOAD_PROFILE: 'huge',
        }),
        /profile/,
    );
    assert.throws(
        () => loadBenchmarkConfig({
            NANOFAAS_FUNCTION: 'fn',
            NANOFAAS_FAMILY: 'word-stats',
            K6_PAYLOAD_SELECTION: 'legacy-random',
        }),
        /selection/,
    );
});
