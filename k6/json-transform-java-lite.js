import http from 'k6/http';
import { sleep } from 'k6';
import { checkInvocationResponse, invocationPath } from './common.js';

const FN = 'json-transform-java-lite';

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

const OPERATIONS = ['count', 'sum', 'avg', 'min', 'max'];
const DEPTS = ['eng', 'sales', 'hr', 'marketing', 'finance'];

function generateData(size) {
    const data = [];
    for (let i = 0; i < size; i++) {
        data.push({
            dept: DEPTS[Math.floor(Math.random() * DEPTS.length)],
            salary: Math.floor(Math.random() * 100000) + 40000,
            age: Math.floor(Math.random() * 40) + 22,
        });
    }
    return data;
}

export default function () {
    const op = OPERATIONS[Math.floor(Math.random() * OPERATIONS.length)];
    const input = {
        data: generateData(20),
        groupBy: 'dept',
        operation: op,
    };
    if (op !== 'count') {
        input.valueField = 'salary';
    }

    const payload = JSON.stringify({ input: input });

    const res = http.post(invocationPath(FN), payload, {
        headers: { 'Content-Type': 'application/json' },
        timeout: '30s',
    });

    checkInvocationResponse(res, (body) => {
        return body.output && body.output.groups !== undefined;
    });

    sleep(0.1);
}
