// k6 load script for autoscaling E2E test
//
// Generates sustained load to trigger InternalScaler scale-up,
// then ramps down so scale-down to zero can be verified.
//
// Usage:
//   k6 run --env NANOFAAS_URL=http://<IP>:30080 k6/autoscaling.js
//   k6 run --env NANOFAAS_URL=http://<IP>:30080 --env FUNCTION_NAME=my-fn k6/autoscaling.js

import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.NANOFAAS_URL || 'http://localhost:30080';
const FN = __ENV.FUNCTION_NAME || 'word-stats-java';

export const options = {
    stages: [
        { duration: '10s', target: 10 },
        { duration: '20s', target: 20 },
        { duration: '90s', target: 20 },   // sustained peak — 2+ scale-up cycles
        { duration: '10s', target: 0 },
    ],
    thresholds: {
        http_req_failed: ['rate<0.30'],    // lenient — scaling causes transient errors
    },
};

const TEXTS = [
    'The quick brown fox jumps over the lazy dog. The dog barked at the fox while the fox ran away quickly.',
    'Lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.',
    'To be or not to be that is the question whether tis nobler in the mind to suffer the slings and arrows of outrageous fortune.',
    'It was the best of times it was the worst of times it was the age of wisdom it was the age of foolishness.',
];

export default function () {
    const text = TEXTS[Math.floor(Math.random() * TEXTS.length)];
    const payload = JSON.stringify({
        input: { text: text, topN: 5 },
    });

    const res = http.post(`${BASE_URL}/v1/functions/${FN}:invoke`, payload, {
        headers: { 'Content-Type': 'application/json' },
        timeout: '30s',
    });

    check(res, {
        'status is 200': (r) => r.status === 200,
    });

    sleep(0.05);
}
