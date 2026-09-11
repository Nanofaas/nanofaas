"""Version 1 acceptance accounting; no performance threshold in normal CI."""
import math
import hashlib
import json

REVISIONS = {'A': '61d72e73528db62cf8ca465c6a037981d7ec13b0',
             'B': '6d08303371d803f44187ec5f4e37827d54fec597'}


def terminal_status(response):
    if response is None:
        return 'outcome_unavailable'
    state = response.get('status')
    return state if state in ['success', 'error', 'timeout'] else None


def percentiles(values):
    values = sorted(values)
    return {name: values[max(0, math.ceil(len(values)*q)-1)] if values else None
            for name, q in [('p50', .50), ('p95', .95), ('p99', .99)]} | {'count': len(values)}


def summarize(rows, offered, elapsed_ns):
    if len(rows) != offered or len({r['id'] for r in rows}) != offered:
        raise ValueError('offered work missing or duplicated')
    successful = [r for r in rows if r['success']]
    ids = [r['execution_id'] for r in successful]
    if None in ids or len(set(ids)) != len(ids):
        raise ValueError('duplicate or missing terminal execution identity')
    if any(r['success'] and not r['admitted'] for r in rows):
        raise ValueError('success without admission')
    admitted = sum(r['admitted'] for r in rows)
    return dict(offered=offered, admitted=admitted, refused=sum(not r['admitted'] and r.get('status') is not None for r in rows),
                transport_errors=sum(r.get('transport_error') is not None for r in rows),
                unique_successes=len(ids), terminal_failures=sum(r.get('terminal_failure', False) for r in rows),
                unresolved=admitted-len(ids)-sum(r.get('terminal_failure', False) for r in rows),
                useful_successes_per_s=len(ids)*1e9/elapsed_ns,
                success_latency_ns=percentiles([r['latency_ns'] for r in successful]),
                refusal_latency_ns=percentiles([r['latency_ns'] for r in rows if not r['admitted']]))


def validate_pairs(runs, bindings=None):
    if [r['label'] for r in runs] != [f'{s}{i}' for i in range(1, 4) for s in 'AB']:
        raise ValueError('require fresh alternating A1/B1/A2/B2/A3/B3')
    for field in ['workload', 'config', 'offered', 'admitted', 'unique_successes']:
        if len({r[field] for r in runs}) != 1:
            raise ValueError('non-equivalent ' + field)
    if any(r['admitted'] != r['unique_successes'] or r['offered'] != r['admitted'] for r in runs):
        raise ValueError('unaccounted admissions or unequal supported work')
    binaries = {side: {r.get('jar_sha256') for r in runs if r['label'].startswith(side)} for side in 'AB'}
    if any(len(values) != 1 or None in values for values in binaries.values()) or binaries['A'] == binaries['B']:
        raise ValueError('require distinct stable A/B binary artifact identities')
    previous_end = -1
    processes = set()
    for run in runs:
        side = run['label'][0]
        if run.get('revision') != REVISIONS[side]:
            raise ValueError('revision does not match A/B binding')
        if bindings is not None and run['jar_sha256'] != bindings[side]['sha256']:
            raise ValueError('binary artifact does not match build binding')
        for field in ['config', 'workload']:
            document = run.get(field + '_document')
            actual = hashlib.sha256(json.dumps(document, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
            if document is None or actual != run[field]:
                raise ValueError(field + ' document/digest mismatch')
        if run['workload_document']['offered'] != run['offered']:
            raise ValueError('workload offered count mismatch')
        start, end = run.get('process_start_ns', -1), run.get('process_end_ns', -1)
        if not previous_end < start <= run.get('start_ns', -1) < run.get('end_ns', -1) <= end:
            raise ValueError('process chronology overlaps or is incomplete')
        previous_end = end
        for role in ['generator', 'server', 'backend']:
            owner = run.get('processes', {}).get(role, {})
            identity = (owner.get('boot_id'), owner.get('pid'), owner.get('start_ticks'))
            if None in identity or identity in processes:
                raise ValueError('missing or non-fresh process evidence')
            processes.add(identity)
        if not all(run.get('shutdown', {}).get(role + '_proc_absent') is True for role in ['server', 'backend']):
            raise ValueError('process exit/drain not observed')
