import http from 'k6/http';
import { SharedArray } from 'k6/data';
import { sleep } from 'k6';

import {
    buildInvocationPayload,
    checkFunctionResponse,
    invocationPath,
    selectCorpusPayload,
} from './common.js';
import { loadBenchmarkConfig, validateCorpus } from './payload-model.js';

const config = loadBenchmarkConfig(__ENV);

function loadCorpus(family, profile) {
    const key = `${family}/${profile}`;
    if (key === 'word-stats/small') return JSON.parse(open('../../functions/test-data/word-stats/performance-small.json'));
    if (key === 'word-stats/medium') return JSON.parse(open('../../functions/test-data/word-stats/performance-medium.json'));
    if (key === 'word-stats/large') return JSON.parse(open('../../functions/test-data/word-stats/performance-large.json'));
    if (key === 'json-transform/small') return JSON.parse(open('../../functions/test-data/json-transform/performance-small.json'));
    if (key === 'json-transform/medium') return JSON.parse(open('../../functions/test-data/json-transform/performance-medium.json'));
    if (key === 'json-transform/large') return JSON.parse(open('../../functions/test-data/json-transform/performance-large.json'));
    if (key === 'roman-numeral/small') return JSON.parse(open('../../functions/test-data/roman-numeral/performance-small.json'));
    if (key === 'roman-numeral/medium') return JSON.parse(open('../../functions/test-data/roman-numeral/performance-medium.json'));
    if (key === 'roman-numeral/large') return JSON.parse(open('../../functions/test-data/roman-numeral/performance-large.json'));
    throw new Error(`unsupported payload corpus: ${key}`);
}

const cases = new SharedArray(`payload-${config.family}-${config.profile}`, () => {
    return validateCorpus(
        loadCorpus(config.family, config.profile),
        config.family,
        config.profile,
    ).cases;
});
const corpus = { family: config.family, profile: config.profile, cases };

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

export default function () {
    const input = selectCorpusPayload(corpus, config.selection);
    const response = http.post(
        invocationPath(config.functionName),
        buildInvocationPayload(input),
        { headers: { 'Content-Type': 'application/json' }, timeout: '30s' },
    );
    checkFunctionResponse(response, config.family);
    sleep(0.1);
}
