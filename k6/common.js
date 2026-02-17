import { check } from 'k6';

export const BASE_URL = __ENV.NANOFAAS_URL || 'http://localhost:30080';
export const INVOCATION_MODE = ((__ENV.INVOCATION_MODE || 'sync').toLowerCase() === 'async') ? 'async' : 'sync';

export function invocationPath(functionName) {
    const suffix = INVOCATION_MODE === 'async' ? 'enqueue' : 'invoke';
    return `${BASE_URL}/v1/functions/${functionName}:${suffix}`;
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
