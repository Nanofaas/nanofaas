"""P23 comparison: fresh paired B/C, plus the A/B pair P19 measured on the same protocol.

Compares what the plan asks for — useful successes, admitted work, refusals and payload cost —
not raw request rate, which this protocol bounds at the offered 200/s by design.
"""
import glob
import json
import pathlib
import statistics
import sys

work = pathlib.Path(sys.argv[1])
p19 = pathlib.Path(sys.argv[2])
dossier = p19 / 'dossiers' / '74a0d632f3373724032df4719945ba50e84f827e49773c46b94902b9c4809dc0'


def load(pattern, root):
    out = []
    for path in sorted(glob.glob(str(root / pattern))):
        d = json.load(open(path))
        if d.get('scenario') != 'sync':
            continue
        out.append(d)
    return out


def arm(rows, side):
    sel = [d for d in rows if d['label'].startswith(side) and d['label'][1:].isdigit()]
    if not sel:
        return None
    def med(key, sub=None):
        vals = [(d[key][sub] if sub else d[key]) for d in sel]
        return statistics.median(vals), min(vals), max(vals)
    return {
        'runs': [d['label'] for d in sel],
        'revision': sel[0]['revision'],
        'all_valid': all(d['valid'] for d in sel),
        'accounting_exact': all(d['offered'] == d['admitted'] == d['unique_successes'] for d in sel),
        'offered': sel[0]['offered'],
        'refused': sum(d['refused'] for d in sel),
        'transport_errors': sum(d['transport_errors'] for d in sel),
        'unresolved': sum(d['unresolved'] for d in sel),
        'useful_per_s': med('useful_successes_per_s'),
        'p50_ns': med('success_latency_ns', 'p50'),
        'p95_ns': med('success_latency_ns', 'p95'),
        'p99_ns': med('success_latency_ns', 'p99'),
        'alloc_per_success': med('allocated_bytes_per_success'),
    }


fresh = load('runs/*/run.json', work)
frozen = load('runs/*/run.json', dossier)
result = {'schema': 'p23-comparison-v1',
          'fresh_pair': {s: arm(fresh, s) for s in ('B', 'C')},
          'p19_recorded_pair': {s: arm(frozen, s) for s in ('A', 'B')}}


def pct(new, old):
    return None if not old else (new - old) / old * 100.0


b, c = result['fresh_pair']['B'], result['fresh_pair']['C']
if b and c:
    result['C_vs_B_percent'] = {k: pct(c[k][0], b[k][0])
                               for k in ('useful_per_s', 'p50_ns', 'p95_ns', 'p99_ns', 'alloc_per_success')}
    result['gate'] = {
        'threshold_percent': 5.0,
        'note': 'Throughput is bounded by the offered 200/s, so useful_per_s only proves no work was '
                'lost. Latency percentiles carry the run-to-run spread printed above; allocation per '
                'success is the tight discriminator.',
        'breaches': [k for k, v in result['C_vs_B_percent'].items()
                     if k != 'useful_per_s' and v is not None and v > 5.0],
    }
pathlib.Path(work / 'comparison.json').write_text(json.dumps(result, indent=2, sort_keys=True) + '\n')

for name, pair in (('fresh B/C', result['fresh_pair']), ('P19 recorded A/B', result['p19_recorded_pair'])):
    print(f'--- {name} ---')
    for side, a in pair.items():
        if not a:
            continue
        print(f"  {side} ({a['revision'][:12]}) runs={a['runs']} valid={a['all_valid']} "
              f"accounting_exact={a['accounting_exact']} refused={a['refused']} "
              f"transport_errors={a['transport_errors']} unresolved={a['unresolved']}")
        print(f"      useful/s   {a['useful_per_s'][0]:.2f}   (min {a['useful_per_s'][1]:.2f} max {a['useful_per_s'][2]:.2f})")
        for k, label in (('p50_ns', 'p50 µs'), ('p95_ns', 'p95 µs'), ('p99_ns', 'p99 µs')):
            m, lo, hi = a[k]
            print(f"      {label:9} {m/1000:8.1f}   (min {lo/1000:.1f} max {hi/1000:.1f})")
        m, lo, hi = a['alloc_per_success']
        print(f"      alloc/succ {m:9.0f} B (min {lo:.0f} max {hi:.0f}, spread {100*(hi-lo)/m:.2f}%)")
if 'C_vs_B_percent' in result:
    print('--- C vs B ---')
    for k, v in result['C_vs_B_percent'].items():
        print(f"  {k:20} {v:+.2f}%")
    print(f"  breaches of the 5% gate: {result['gate']['breaches'] or 'none'}")
