import { check } from 'k6';
import exec from 'k6/execution';
import { Trend } from 'k6/metrics';
import {
    hasExpectedOutput,
    selectPayload as selectPayloadPure,
} from './payload-model.js';

export const BASE_URL = __ENV.NANOFAAS_URL || 'http://localhost:30080';
export const INVOCATION_MODE = ((__ENV.INVOCATION_MODE || 'sync').toLowerCase() === 'async') ? 'async' : 'sync';
const payloadSizeBytes = new Trend('payload_size_bytes');

export function invocationPath(functionName) {
    const suffix = INVOCATION_MODE === 'async' ? 'enqueue' : 'invoke';
    return `${BASE_URL}/v1/functions/${functionName}:${suffix}`;
}

export function selectCorpusPayload(corpus, mode = 'sequential') {
    return selectPayloadPure(corpus, mode, exec.scenario.iterationInTest, Math.random);
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

export function checkFunctionResponse(res, family) {
    return checkInvocationResponse(res, (body) => hasExpectedOutput(family, body));
}
