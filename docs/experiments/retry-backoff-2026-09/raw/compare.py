#!/usr/bin/env python3
"""Compare identical arms across revisions; Python 3.12+, standard library only."""
import json
import statistics
from pathlib import Path

HERE = Path(__file__).resolve().parent
BUDGETS = json.loads((HERE.parent.parent / 'scheduler-switching-2026-09/budgets.json').read_text())


def read(name):
    return [json.loads(line) for line in (HERE / name).read_text().splitlines()]


baseline = read('retry-backoff-baseline-steady.jsonl')
candidate = read('retry-backoff-candidate-steady.jsonl')
metrics = [
    ('steady p99 ms', lambda r: r['trailing']['2000']['p99Nanos'] / 1e6,
     BUDGETS['maxSteadyP99RegressionPercent'], 1),
    ('steady useful/s', lambda r: r['trailing']['2000']['usefulThroughputPerSecond'],
     BUDGETS['maxUsefulThroughputRegressionPercent'], -1),
    ('thread CPU/completion us', lambda r: r['threadCpuPerUsefulCompletionNanos'] / 1000,
     BUDGETS['maxCpuPerCompletionRegressionPercent'], 1),
    ('post-GC heap MiB', lambda r: r['postGcHeapBytes'] / 2**20,
     BUDGETS['maxPostGcHeapRegressionPercent'], 1),
]
print('| arm | metric | baseline median | candidate median | change % | budget % | verdict |')
print('| --- | --- | ---: | ---: | ---: | ---: | --- |')
for arm in sorted({r['arm'] for r in baseline if r.get('workload') == 'low-load'}):
    runs = [[r for r in data if r.get('kind') == 'sample'
             and r.get('workload') == 'low-load' and r['arm'] == arm]
            for data in (baseline, candidate)]
    assert all(len(rs) == 5 for rs in runs), 'five repetitions required'
    assert all(r['workConserved'] and r['driverFailures'] == 0 for rs in runs for r in rs)
    for name, value, budget, direction in metrics:
        b, c = (statistics.median(value(r) for r in rs) for rs in runs)
        delta = (c / b - 1) * 100
        verdict = 'PASS' if delta * direction <= budget else 'FAIL'
        print(f'| {arm} | {name} | {b:.4f} | {c:.4f} | {delta:+.2f} | {budget} | {verdict} |')
