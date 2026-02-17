import { check } from 'k6';
import exec from 'k6/execution';
import { Trend } from 'k6/metrics';
import {
    buildJsonTransformInput as buildJsonTransformInputPure,
    buildWordStatsInput as buildWordStatsInputPure,
    parsePositiveInt,
    selectPayloadIndex as selectPayloadIndexPure,
} from './payload-model.js';

export const BASE_URL = __ENV.NANOFAAS_URL || 'http://localhost:30080';
export const INVOCATION_MODE = ((__ENV.INVOCATION_MODE || 'sync').toLowerCase() === 'async') ? 'async' : 'sync';
export const K6_PAYLOAD_MODE = (__ENV.K6_PAYLOAD_MODE || 'legacy-random').toLowerCase();
export const K6_PAYLOAD_POOL_SIZE = parsePositiveInt(__ENV.K6_PAYLOAD_POOL_SIZE, 5000);
const payloadSizeBytes = new Trend('payload_size_bytes');

export function invocationPath(functionName) {
    const suffix = INVOCATION_MODE === 'async' ? 'enqueue' : 'invoke';
    return `${BASE_URL}/v1/functions/${functionName}:${suffix}`;
}

export function selectPayloadIndex() {
    return selectPayloadIndexPure(
        K6_PAYLOAD_MODE,
        K6_PAYLOAD_POOL_SIZE,
        exec.scenario.iterationInTest,
        Math.random,
    );
}

export function buildWordStatsInput(index) {
    return buildWordStatsInputPure(index, Math.random);
}

export function buildJsonTransformInput(index) {
    return buildJsonTransformInputPure(index, Math.random);
}

export function buildInvocationPayload(input) {
    const payload = JSON.stringify({ input: input });
    payloadSizeBytes.add(payload.length);
    return payload;
}

function parseJson(body) {
    try {
        return JSON.parse(body);
    } catch (e) {
        return null;
    }
}

export function checkInvocationResponse(res, syncPredicate) {
    if (INVOCATION_MODE === 'async') {
        return check(res, {
            'status is 202': (r) => r.status === 202,
            'has executionId': (r) => {
                const body = parseJson(r.body);
                return !!(body && body.executionId);
            },
        });
    }

    return check(res, {
        'status is 200': (r) => r.status === 200,
        'has expected output': (r) => {
            const body = parseJson(r.body);
            if (!body) return false;
            if (body.error) return false;
            return syncPredicate(body);
        },
    });
}
