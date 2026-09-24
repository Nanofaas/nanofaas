#!/usr/bin/env python3
"""Summarize extracted live evidence; Python 3.12+, no dependencies."""
import collections
import json
from pathlib import Path
import statistics

HERE = Path(__file__).resolve().parent


def metric(path, name, function):
    return sum(float(line.rsplit(' ', 1)[1]) for line in path.read_text().splitlines()
               if line.startswith(name + '{') and f'function="{function}"' in line)


summary = {}
for variant in ('baseline', 'candidate'):
    folder = HERE / f'retry-backoff-{variant}-matched'
    phases = {}
    ids = set()
    for phase, before in (('immediate', 'before'), ('ready', 'ready-before'), ('warm', 'warm-before')):
        rows = json.loads((folder / f'{phase}-calls.json').read_text())
        statuses = collections.Counter()
        errors = collections.Counter()
        for row in rows:
            body = json.loads(row['body']) if row['body'].startswith('{') else {}
            statuses[body.get('status', f'HTTP {row["httpStatus"]}')] += 1
            if body.get('error'):
                message = body['error']['message']
                category = ('callback_saturated' if 'RUNTIME_CALLBACK_SATURATED' in message
                            else 'connection_refused' if 'Connection refused' in message
                            else body['error']['code'])
                errors[category] += 1
            assert row['executionId'] and row['executionId'] not in ids
            ids.add(row['executionId'])
        phases[phase] = {'calls': len(rows), 'outcomes': dict(statuses), 'errors': dict(errors),
            'counterDeltas': {name: metric(folder / f'{phase}-after-metrics.txt', f'function_{name}_total', 'retry-backoff-probe')
                             - metric(folder / f'{before}-metrics.txt', f'function_{name}_total', 'retry-backoff-probe')
                             for name in ('retry', 'error', 'success')}}
    ready = None
    for line in (folder / 'readiness.jsonl').read_text().splitlines():
        row = json.loads(line)
        if any(ep.get('conditions', {}).get('ready') is True
               for s in row['slices'] for ep in (s.get('endpoints') or [])):
            ready = row['observedAt']
            break
    folder = HERE / f'retry-hint-{variant}-matched'
    attempts = json.loads((folder / 'attempts.json').read_text())
    calls = json.loads((folder / 'calls.json').read_text())
    groups = collections.defaultdict(list)
    for row in attempts:
        groups[row['executionId']].append(row)
    gaps = []
    for rows in groups.values():
        rows.sort(key=lambda r: r['upstreamReceivedMonotonicNanos'])
        assert len({r['attempt'] for r in rows}) == len(rows)
        gaps.extend((b['upstreamReceivedMonotonicNanos'] - a['upstreamReceivedMonotonicNanos']) / 1e9
                    for a, b in zip(rows, rows[1:]))
    summary[variant] = {'managed': phases, 'distinctManagedIds': len(ids),
        'firstReadyObservedAt': ready,
        'hint': {'distinctIds': len(groups), 'attempts': len(attempts),
                 'outcomes': dict(collections.Counter(json.loads(r['response'][2])['status'] for r in calls)),
                 'retryGapMinSeconds': min(gaps), 'retryGapMedianSeconds': statistics.median(gaps),
                 'retryGapMaxSeconds': max(gaps), 'retryGapsBelowOneSecond': sum(gap < 1 for gap in gaps)}}
print(json.dumps(summary, indent=2))
