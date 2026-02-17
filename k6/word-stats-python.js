import http from 'k6/http';
import { sleep } from 'k6';
import { checkInvocationResponse, invocationPath } from './common.js';

const FN = 'word-stats-python';

export const options = {
    stages: [
        { duration: '10s', target: 5 },
        { duration: '30s', target: 10 },
        { duration: '30s', target: 20 },
        { duration: '30s', target: 20 },
        { duration: '10s', target: 0 },
    ],
    thresholds: {
        http_req_duration: ['p(95)<3000', 'p(99)<5000'],
        http_req_failed: ['rate<0.15'],
    },
};

const TEXTS = [
    'The quick brown fox jumps over the lazy dog. The dog barked at the fox while the fox ran away quickly.',
    'Lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.',
    'To be or not to be that is the question whether tis nobler in the mind to suffer the slings and arrows of outrageous fortune.',
    'It was the best of times it was the worst of times it was the age of wisdom it was the age of foolishness.',
    'In the beginning God created the heaven and the earth and the earth was without form and void.',
];

export default function () {
    const text = TEXTS[Math.floor(Math.random() * TEXTS.length)];
    const payload = JSON.stringify({
        input: { text: text, topN: 5 },
    });

    const res = http.post(invocationPath(FN), payload, {
        headers: { 'Content-Type': 'application/json' },
        timeout: '30s',
    });

    checkInvocationResponse(res, (body) => {
        return body.output && body.output.wordCount > 0;
    });

    sleep(0.1);
}
