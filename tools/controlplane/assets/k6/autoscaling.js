import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.NANOFAAS_URL || 'http://localhost:30080';
const FN = __ENV.NANOFAAS_FUNCTION || __ENV.FUNCTION_NAME || 'word-stats-java';

export const options = {
    // Load profile is injected by the workflow via `k6 run --stage ...` (see
    // K6Config in one_vm_loadtest_adapter.py); CLI flags override script
    // options, so it is deliberately NOT duplicated here.
    thresholds: {
        // Generous on purpose: scale-from-zero means the first wave of requests
        // hits cold starts and may time out before replicas come up.
        http_req_failed: ['rate<0.30'],
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
