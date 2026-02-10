// Common k6 configuration and helpers for nanofaas load tests
//
// Usage: import { BASE_URL, invoke, checkResponse } from './common.js';

export const BASE_URL = __ENV.NANOFAAS_URL || 'http://192.168.64.x:30080';

export const STAGES = [
    { duration: '10s', target: 5 },   // ramp up
    { duration: '30s', target: 10 },  // steady
    { duration: '30s', target: 20 },  // increase
    { duration: '30s', target: 20 },  // sustained peak
    { duration: '10s', target: 0 },   // ramp down
];

export const THRESHOLDS = {
    http_req_duration: ['p(95)<2000', 'p(99)<5000'],
    http_req_failed: ['rate<0.10'],
};

export function invokeSync(functionName, payload) {
    const url = `${BASE_URL}/v1/functions/${functionName}:invoke`;
    const params = {
        headers: { 'Content-Type': 'application/json' },
        timeout: '30s',
    };
    return http.post(url, JSON.stringify({ input: payload }), params);
}

import http from 'k6/http';
import { check } from 'k6';

export function checkResponse(res, functionName) {
    const ok = check(res, {
        [`${functionName} status 200`]: (r) => r.status === 200,
        [`${functionName} no error`]: (r) => {
            if (r.status !== 200) return false;
            try {
                const body = JSON.parse(r.body);
                return !body.error;
            } catch (e) {
                return true;
            }
        },
    });
    return ok;
}
