import test from 'node:test';
import assert from 'node:assert/strict';

import {
    buildJsonTransformInput,
    buildWordStatsInput,
    parsePositiveInt,
    selectPayloadIndex,
} from '../payload-model.js';

test('parsePositiveInt returns fallback for invalid values', () => {
    assert.equal(parsePositiveInt(undefined, 5000), 5000);
    assert.equal(parsePositiveInt('x', 5000), 5000);
    assert.equal(parsePositiveInt('0', 5000), 5000);
    assert.equal(parsePositiveInt('-3', 5000), 5000);
    assert.equal(parsePositiveInt('12', 5000), 12);
});

test('selectPayloadIndex supports sequential and random pool modes', () => {
    assert.equal(selectPayloadIndex('pool-sequential', 5000, 42, () => 0.7), 42);
    assert.equal(selectPayloadIndex('pool-sequential', 10, 42, () => 0.7), 2);
    assert.equal(selectPayloadIndex('pool-random', 5000, 42, () => 0.5), 2500);
    assert.equal(selectPayloadIndex('legacy-random', 5000, 42, () => 0.5), -1);
});

test('buildWordStatsInput is deterministic for same pool index', () => {
    const first = buildWordStatsInput(123);
    const second = buildWordStatsInput(123);
    assert.deepEqual(first, second);
    assert.ok(first.text.length > 60);
    assert.ok(first.topN >= 3 && first.topN <= 8);
});

test('buildWordStatsInput uses random fallback when index is negative', () => {
    const input = buildWordStatsInput(-1, () => 0.321);
    assert.ok(input.text.includes('The'));
    assert.ok(input.topN >= 3 && input.topN <= 8);
});

test('buildJsonTransformInput is deterministic and schema-valid', () => {
    const first = buildJsonTransformInput(77);
    const second = buildJsonTransformInput(77);
    assert.deepEqual(first, second);
    assert.ok(Array.isArray(first.data));
    assert.ok(first.data.length >= 8 && first.data.length <= 31);
    assert.ok(['dept', 'region', 'tier'].includes(first.groupBy));
    assert.ok(['count', 'sum', 'avg', 'min', 'max'].includes(first.operation));
    if (first.operation === 'count') {
        assert.equal(first.valueField, undefined);
    } else {
        assert.ok(['salary', 'age', 'score'].includes(first.valueField));
    }
});
