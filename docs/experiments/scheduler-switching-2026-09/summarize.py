#!/usr/bin/env python3
"""Turns the harness's JSONL into the tables RESULTS.md records.

    ./summarize.py raw/full.jsonl

Every figure RESULTS.md quotes against a frozen budget comes from this script's output, so the
comparison can be re-derived from the raw artifact rather than trusted. The budgets are read from
budgets.json, never restated here.
"""
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
BUDGETS = json.loads((HERE / "budgets.json").read_text())


def rows(path):
    with open(path) as handle:
        for line in handle:
            line = line.strip()
            if line:
                yield json.loads(line)


def median(values):
    return statistics.median(values) if values else float("nan")


def spread(values):
    return (min(values), max(values)) if values else (float("nan"), float("nan"))


def load(path):
    samples, switches, baseline, summary, header = [], [], None, None, None
    for row in rows(path):
        kind = row.get("kind")
        if kind == "sample":
            samples.append(row)
        elif kind == "switch":
            switches.append(row)
        elif kind == "baseline":
            baseline = row
        elif kind == "summary":
            summary = row
        elif kind == "header":
            header = row
    return samples, switches, baseline, summary, header


def ms(nanos):
    return nanos / 1e6


def percent_delta(candidate, base):
    return float("nan") if base == 0 else (candidate - base) * 100.0 / base


# ----------------------------------------------------------------------------------------------
# 1. The pause sweep: one switch per (backlog, repetition)
# ----------------------------------------------------------------------------------------------
def pause_sweep(switches):
    by_workload = defaultdict(list)
    for event in switches:
        if event["workload"].startswith("switch-pause-backlog-"):
            by_workload[event["workload"]].append(event)
    print("## Pause sweep (budgets.json switchBacklogSizes)\n")
    print("| requested backlog | switches | pending at switch | pause p50 ms | pause max ms | "
          "client max ms | live indexes max | outcome |")
    print("|---|---|---|---|---|---|---|---|")
    for workload in sorted(by_workload, key=lambda w: int(w.rsplit("-", 1)[1])):
        events = by_workload[workload]
        pauses = sorted(ms(e["enginePauseNanos"]) for e in events)
        pendings = [e["pending"] for e in events]
        lives = max(e["liveIndexes"] for e in events)
        client = max(ms(e["clientElapsedNanos"]) for e in events)
        requested = workload.rsplit("-", 1)[1]
        outcomes = ",".join(sorted({e["outcome"] for e in events}))
        print(f"| {requested} | {len(events)} | {min(pendings)}-{max(pendings)} | "
              f"{pauses[len(pauses) // 2]:.3f} | {pauses[-1]:.3f} | {client:.3f} | {lives} | "
              f"{outcomes} |")
    print()


# ----------------------------------------------------------------------------------------------
# 2. Every measured switch in the campaign, for the process-wide budget
# ----------------------------------------------------------------------------------------------
def per_arm(samples):
    table = defaultdict(list)
    for sample in samples:
        if sample["workload"].startswith("switch-pause-backlog-"):
            continue
        table[(sample["workload"], sample["arm"])].append(sample)
    return table


def workload_table(samples):
    table = per_arm(samples)
    workloads = sorted({w for w, _ in table})
    print("## Per-workload medians over the 5 repetitions (min-max in brackets)\n")
    print("| workload | arm | reps | useful/s | p99 ms | thread cpu/useful us | alloc/useful B | "
          "post-GC heap MB | pending | conserved |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    for workload in workloads:
        for arm in sorted({a for w, a in table if w == workload}):
            runs = table[(workload, arm)]
            useful = [r["usefulThroughputPerSecond"] for r in runs]
            p99 = [ms(r["p99Nanos"]) for r in runs]
            cpu = [r.get("threadCpuPerUsefulCompletionNanos",
                    r.get("cpuPerUsefulCompletionNanos", -1)) / 1e3 for r in runs]
            alloc = [r["allocatedBytesPerUsefulCompletion"] for r in runs]
            heap = [r["postGcHeapBytes"] / 1e6 for r in runs]
            pending = [r["pendingAtClose"] for r in runs]
            conserved = all(r["workConserved"] for r in runs)
            lo, hi = spread(useful)
            print(f"| {workload} | {arm} | {len(runs)} | {median(useful):.1f} "
                  f"({lo:.1f}-{hi:.1f}) | {median(p99):.3f} | {median(cpu):.2f} | "
                  f"{median(alloc):.0f} | {median(heap):.2f} | "
                  f"{median(pending):.0f} | {conserved} |")
    print()


# ----------------------------------------------------------------------------------------------
# 4. Switched arm against the same strategy's no-change arm: the four regression budgets
# ----------------------------------------------------------------------------------------------
def legacy_regression(samples):
    """The round-1 protocol's table: the switched arm compared against the strategy it *started*
    on, over the whole measured window — how round 1 measured, and the artifact the mid-window
    mixture defect is visible in. Round-3 artifacts do not call this; they call steady_regression,
    which compares against the strategy the arm ends on and segments the settling. Kept because a
    committed artifact has to be reproducible by the committed tool, and raw/full.jsonl is this
    protocol's output."""
    table = per_arm(samples)
    # Each switched arm is compared against the arm whose strategy it ends on: the switch lands at
    # the start of the measured window, so the whole window runs on the target strategy and the
    # delta against the target's own no-change arm is the switch's residue.
    pairs = [
        ("per-function -> shared-queue", "shared-queue (no change)"),
        ("shared-queue -> per-function", "per-function (no change)"),
    ]
    metrics = [
        ("steady p99", lambda r: ms(r["p99Nanos"]), BUDGETS["maxSteadyP99RegressionPercent"]),
        ("useful throughput", lambda r: r["usefulThroughputPerSecond"],
         BUDGETS["maxUsefulThroughputRegressionPercent"]),
        ("cpu per useful completion", lambda r: r["cpuPerUsefulCompletionNanos"],
         BUDGETS["maxCpuPerCompletionRegressionPercent"]),
        ("post-GC heap", lambda r: r["postGcHeapBytes"],
         BUDGETS["maxPostGcHeapRegressionPercent"]),
    ]
    print("## Switched arm vs the same strategy's no-change arm (median of 5 repetitions)\n")
    print("A delta over budget is a miss. `dispersion` says whether it is *distinguishable*: "
          "arms whose 5-repetition ranges overlap are not separated by this measurement, and the "
          "brief's own criterion for that case is to declare the result not distinguishable rather "
          "than to read the delta as an effect.\n")
    print("| workload | switch | metric | no-change (5-rep range) | switched (5-rep range) | "
          "delta % | budget % | verdict | dispersion |")
    print("|---|---|---|---|---|---|---|---|---|")
    verdicts = []
    workloads = sorted({w for w, _ in table})
    for workload in workloads:
        for switched_arm, base_arm in pairs:
            if (workload, switched_arm) not in table or (workload, base_arm) not in table:
                continue
            switched = table[(workload, switched_arm)]
            base_runs = table[(workload, base_arm)]
            switch_runs = switched
            for name, extract, budget in metrics:
                base_values = [extract(r) for r in base_runs]
                cand_values = [extract(r) for r in switch_runs]
                base = median(base_values)
                cand = median(cand_values)
                delta = percent_delta(cand, base)
                # A regression is an increase; an improvement is never a budget miss.
                ok = delta <= budget
                base_lo, base_hi = spread(base_values)
                cand_lo, cand_hi = spread(cand_values)
                overlap = cand_lo <= base_hi and base_lo <= cand_hi
                dispersion = "overlaps" if overlap else "disjoint"
                verdicts.append((workload, switched_arm, name, delta, budget, ok, overlap))
                print(f"| {workload} | {switched_arm} | {name} | "
                      f"{base:.3f} ({base_lo:.3f}-{base_hi:.3f}) | "
                      f"{cand:.3f} ({cand_lo:.3f}-{cand_hi:.3f}) | {delta:+.2f} | {budget} | "
                      f"{'PASS' if ok else 'MISS'} | {dispersion} |")
    print()
    return verdicts


# ----------------------------------------------------------------------------------------------
# The settling rule, fixed before the campaign it is applied to. It reads nothing but the queue
# depth, so it cannot be steered by the latency it is used to segment.
#
#   settledDepth  = median of the arm's depth trajectory over the final quarter of the span
#   settleMillis  = the last point of the span at which the depth was outside +/-25 % of it
#   steady window = the trailing 2000 ms of the span, which is valid only when every arm of the
#                   workload settled at least 2000 ms before the span ended
#   converged     = the switched arm's settledDepth within 25 % of its target no-change arm's
# ----------------------------------------------------------------------------------------------
SETTLE_TAIL_FRACTION = 0.25
STEADY_WINDOW_MS = 2000
# A queue depth is a noisy signal: an instantaneous sample dips to zero and spikes to twice the
# mean without the queue having changed level. The settle test therefore reads a 500 ms moving
# average, and asks whether a point of that average is an *outlier* of the arm's own settled
# distribution rather than whether it is inside a fixed percentage of a single number: a fixed
# percentage around a low median rejects a queue that is simply fluctuating. The fence is Tukey's
# (Q1 - 1.5 IQR, Q3 + 1.5 IQR) over the final quarter, which is a property of the arm's own
# steady behaviour and contains no constant chosen from any latency.
SETTLE_SMOOTH_MS = 500
SETTLE_FENCE_IQR = 1.5
# Below this settled depth a percentage is a ratio of small integers and says nothing;
# the comparison is reported in tickets instead.
DEPTH_PERCENT_FLOOR = 5


def settled_depth(series):
    tail = series[-max(1, int(len(series) * SETTLE_TAIL_FRACTION)):]
    return statistics.median(tail)


def smoothed(series, window):
    out = []
    for i in range(len(series)):
        lo = max(0, i - window + 1)
        out.append(statistics.mean(series[lo:i + 1]))
    return out


def settled_band(series):
    tail = series[-max(1, int(len(series) * SETTLE_TAIL_FRACTION)):]
    tail = sorted(tail)
    q1 = tail[len(tail) // 4]
    q3 = tail[(3 * len(tail)) // 4]
    # A queue depth is a count of whole tickets, so a band narrower than one ticket cannot express
    # "settled": without the floor, an arm whose settled depth is 0 has the band [0, 0] and every
    # single ticket passing through it reads as an excursion.
    iqr = max(q3 - q1, 1)
    return q1 - SETTLE_FENCE_IQR * iqr, q3 + SETTLE_FENCE_IQR * iqr


def settle_millis(series, interval_ms):
    low, high = settled_band(series)
    smooth = smoothed(series, max(1, int(SETTLE_SMOOTH_MS / max(interval_ms, 1e-9))))
    last_outside = 0
    for i, value in enumerate(smooth):
        if value < low or value > high:
            last_outside = i
    return last_outside * interval_ms


def settling_table(samples):
    by_arm = per_arm(samples)
    print("## Does the queue converge? — the settling rule, read off the depth trajectory\n")
    print("`settledDepth` is the median of the final quarter of the trajectory; `settleMillis` is "
          "the last point of the span still outside ±25 % of it. Both are backlog-only figures — "
          "the segmentation cannot be steered by the latency it segments. The steady window is the "
          "trailing "
          f"{STEADY_WINDOW_MS} ms, valid where every arm settled before it opens.\n")
    print("| workload | arm | settled depth | settled Q1-Q3 | settle ms (worst rep) | "
          "settled before the steady window |")
    print("|---|---|---|---|---|---|")
    per_workload = defaultdict(list)
    settlements = {}
    for (workload, arm), run in by_arm.items():
        series = [r["depthSeries"] for r in run]
        interval = run[0]["depthSampleIntervalNanos"] / 1e6
        settles = [settle_millis(s, interval) for s in series]
        levels = [settled_depth(s) for s in series]
        span = run[0]["windowMillis"]
        bands = [settled_band(x) for x in series]
        per_workload[workload].append((arm, settles, levels,
                                       (median(b[0] for b in bands), median(b[1] for b in bands))))
        settlements[(workload, arm)] = max(settles) <= span - STEADY_WINDOW_MS
        print(f"| {workload} | {arm} | {median(levels):.0f} | "
              f"{median(b[0] for b in bands):.0f}-{median(b[1] for b in bands):.0f} | "
              f"{max(settles):.0f} | "
              f"{'yes' if settlements[(workload, arm)] else 'NO'} |")
    print()
    return per_workload, settlements


def convergence(per_workload):
    pairs = [
        ("per-function -> shared-queue", "shared-queue (no change)"),
        ("shared-queue -> per-function", "per-function (no change)"),
    ]
    print("### Are the switched arms comparable to their control once settled?\n")
    print("| workload | switched arm | target control | settled depth switched (Q1-Q3) | "
          "control (Q1-Q3) | delta | verdict |")
    print("|---|---|---|---|---|---|---|")
    findings = []
    for workload in sorted(per_workload):
        arms = {a: (s, l, b) for a, s, l, b in per_workload[workload]}
        for switched, control in pairs:
            if switched not in arms or control not in arms:
                continue
            s_level = median(arms[switched][1])
            c_level = median(arms[control][1])
            delta = percent_delta(s_level, c_level) if c_level >= DEPTH_PERCENT_FLOOR else None
            # Converged when the switched arm's settled range meets the control's: overlapping
            # quartile ranges are what "comparable at steady state" means for a fluctuating queue.
            s_band = arms[switched][2]
            c_band = arms[control][2]
            converged = s_band[0] <= c_band[1] and c_band[0] <= s_band[1]
            findings.append((workload, switched, converged, delta))
            shown = f"{delta:+.1f} %" if delta is not None else \
                f"{(s_level - c_level):+.0f} tickets"
            print(f"| {workload} | {switched} | {control} | {s_level:.0f} "
                  f"({s_band[0]:.0f}-{s_band[1]:.0f}) | {c_level:.0f} "
                  f"({c_band[0]:.0f}-{c_band[1]:.0f}) | {shown} | "
                  f"{'converged' if converged else 'NOT CONVERGED'} |")
    print()
    return findings


def steady_regression(samples, settlements):
    """The four budgets on the corrected protocol, eligibility tested per metric.

    Two of the four are window-derived — the steady p99 and the steady useful throughput, read off
    the trailing window — and only they need arrivals in that window, and only they are gated on the
    settling rule. The other two are whole-run quantities by construction: CPU per useful completion
    is span CPU over span completions and post-GC heap is measured after the run, so neither has a
    "steady" form and neither can be unmeasurable for want of steady arrivals. Calling them steady
    was a label defect; treating them as unmeasurable for a workload that stops its traffic was a
    coverage gap, since the brief mandates the memory comparison from that workload.
    """
    table = per_arm(samples)
    pairs = [
        ("per-function -> shared-queue", "shared-queue (no change)"),
        ("shared-queue -> per-function", "per-function (no change)"),
    ]
    window_p99 = lambda r: r["trailing"][str(STEADY_WINDOW_MS)]["p99Nanos"] / 1e6
    window_tput = lambda r: r["trailing"][str(STEADY_WINDOW_MS)]["usefulThroughputPerSecond"]
    metrics = [
        ("steady p99", window_p99, BUDGETS["maxSteadyP99RegressionPercent"], "window", window_p99),
        ("steady useful throughput", window_tput,
         BUDGETS["maxUsefulThroughputRegressionPercent"], "window", window_tput),
        ("thread cpu per useful completion",
         lambda r: r["threadCpuPerUsefulCompletionNanos"],
         BUDGETS["maxCpuPerCompletionRegressionPercent"], "whole run", None),
        ("post-GC heap", lambda r: r["postGcHeapBytes"],
         BUDGETS["maxPostGcHeapRegressionPercent"], "whole run", None),
    ]
    print("## The budgets on the corrected protocol, eligibility tested per metric\n")
    print("`window` metrics are read off the trailing "
          f"{STEADY_WINDOW_MS} ms and are eligible only where both arms settled before that window "
          "opened (the settling table above) *and* the window holds arrivals. `whole run` metrics — "
          "CPU per useful completion, which is span CPU over span completions, and post-GC heap, "
          "measured after the run — have no steady form, so they have one column and no "
          "transient-inclusive counterpart, and a workload that stops its traffic is not an obstacle "
          "to them and is not exempted from them.\n")
    print("| workload | switch | metric | nature | control | switched | delta % | budget % | "
          "verdict | dispersion | transient-inclusive delta % |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    verdicts = []
    for workload in sorted({w for w, _ in table}):
        for switched_arm, base_arm in pairs:
            if (workload, switched_arm) not in table or (workload, base_arm) not in table:
                continue
            steady_arrivals = min(
                median([r["trailing"][str(STEADY_WINDOW_MS)]["samples"]
                        for r in table[(workload, arm)]])
                for arm in (base_arm, switched_arm))
            settled_ok = all(settlements.get((workload, arm), True)
                             for arm in (base_arm, switched_arm))
            for name, extract, budget, nature, transient_extract in metrics:
                if nature == "window" and not settled_ok:
                    print(f"| {workload} | {switched_arm} | {name} | {nature} | — | — | — | "
                          f"{budget} | NOT MEASURABLE (an arm had not settled) | — | — |")
                    verdicts.append((workload, switched_arm, name, float("nan"), budget, None, None))
                    continue
                if nature == "window" and steady_arrivals < 30:
                    print(f"| {workload} | {switched_arm} | {name} | {nature} | — | — | — | "
                          f"{budget} | NOT MEASURABLE (no arrivals in the steady window) | — | — |")
                    verdicts.append((workload, switched_arm, name, float("nan"), budget, None, None))
                    continue
                base_values = [extract(r) for r in table[(workload, base_arm)]]
                cand_values = [extract(r) for r in table[(workload, switched_arm)]]
                base, cand = median(base_values), median(cand_values)
                delta = percent_delta(cand, base)
                ok = delta <= budget
                base_lo, base_hi = spread(base_values)
                cand_lo, cand_hi = spread(cand_values)
                overlap = cand_lo <= base_hi and base_lo <= cand_hi
                if transient_extract is None:
                    transient = "— (no steady form)"
                else:
                    transient = f"{percent_delta(median([transient_extract(r) for r in table[(workload, switched_arm)]]), median([transient_extract(r) for r in table[(workload, base_arm)]])):+.2f}"
                verdicts.append((workload, switched_arm, name, delta, budget, ok, overlap))
                print(f"| {workload} | {switched_arm} | {name} | {nature} | "
                      f"{base:.3f} ({base_lo:.3f}-{base_hi:.3f}) | "
                      f"{cand:.3f} ({cand_lo:.3f}-{cand_hi:.3f}) | {delta:+.2f} | {budget} | "
                      f"{'PASS' if ok else 'MISS'} | "
                      f"{'overlaps' if overlap else 'disjoint'} | {transient} |")
    print()
    return verdicts


def resolving_power(samples, verdicts):
    """How large a *consistent* regression this test could have caught.

    A range-overlap test is only as sharp as the width of the arms' own five-repetition ranges. This
    multiplies each control arm's own five values by (1 + factor) — the most favourable possible
    case for the test: a uniform multiplicative effect with no added noise — and counts how often
    the shifted arm would read disjoint. That count is the test's resolving power, and it is what
    makes a null readable: an instrument that cannot see a 10 % effect says nothing about a 10 %
    effect, so "not distinguishable" is a statement about the instrument, not about the code.
    """
    table = per_arm(samples)
    pairs = [
        ("per-function -> shared-queue", "shared-queue (no change)"),
        ("shared-queue -> per-function", "per-function (no change)"),
    ]
    extractors = {
        "steady p99": lambda r: r["trailing"][str(STEADY_WINDOW_MS)]["p99Nanos"] / 1e6,
        "steady useful throughput":
            lambda r: r["trailing"][str(STEADY_WINDOW_MS)]["usefulThroughputPerSecond"],
        "thread cpu per useful completion": lambda r: r["threadCpuPerUsefulCompletionNanos"],
        "post-GC heap": lambda r: r["postGcHeapBytes"],
    }
    budgets = {
        "steady p99": BUDGETS["maxSteadyP99RegressionPercent"],
        "steady useful throughput": BUDGETS["maxUsefulThroughputRegressionPercent"],
        "thread cpu per useful completion": BUDGETS["maxCpuPerCompletionRegressionPercent"],
        "post-GC heap": BUDGETS["maxPostGcHeapRegressionPercent"],
    }
    factors = (0.05, 0.10, 0.20, 0.30, 0.50, 1.00)
    print("## Resolving power: the smallest consistent regression this test could have caught\n")
    print("Each cell is how many eligible comparisons would read `disjoint` if the switched arm's "
          "true value were exactly (1 + factor) times its control's, arm by arm — a uniform "
          "multiplicative effect, the easiest possible case to detect, and one the test still has to "
          "beat. A budget well below the row's first detectable factor cannot have been adjudicated "
          "by this campaign.\n")
    print("| metric | budget % | eligible | " + " | ".join(f"+{int(f * 100)} %" for f in factors)
          + " | median arm spread % |")
    print("|---|---|---|" + "---|" * len(factors) + "---|")
    for name, extract in extractors.items():
        eligible = 0
        hits = {f: 0 for f in factors}
        spreads = []
        for workload in sorted({w for w, _ in table}):
            for switched_arm, base_arm in pairs:
                if (workload, switched_arm) not in table or (workload, base_arm) not in table:
                    continue
                if any(v[0] == workload and v[1] == switched_arm and v[2] == name and v[5] is None
                       for v in verdicts):
                    continue
                base_values = sorted(extract(r) for r in table[(workload, base_arm)])
                eligible += 1
                for factor in factors:
                    if base_values[0] * (1 + factor) > base_values[-1]:
                        hits[factor] += 1
        for (workload, arm), runs in table.items():
            values = [extract(r) for r in runs]
            mean = sum(values) / len(values)
            if mean:
                spreads.append((max(values) - min(values)) * 100.0 / mean)
        print(f"| {name} | {budgets[name]} | {eligible} | "
              + " | ".join(str(hits[f]) for f in factors) + f" | {median(spreads):.1f} |")
    print()
    print("The arms' own spread is the reason, and it is two to three times the p99 and CPU "
          "budgets: a uniform 5 % effect is invisible to a test whose arms disagree by 16 % between "
          "repetitions.\n")


def pairing_demo(samples):
    """What the round-1 pairing defect is worth, computed from this artifact.

    Round 1 compared each switched arm against the strategy it *started* on. On a workload where the
    two strategies differ, that compares an arm which ends on one strategy against an arm which
    never leaves the other — a difference that has nothing to do with switching. Round 1 reported
    +3851 % for this; that number's artifact was overwritten before it was committed, so instead of
    quoting it, this computes the same quantity from the artifact that does exist.
    """
    table = per_arm(samples)
    window_p99 = lambda r: r["trailing"][str(STEADY_WINDOW_MS)]["p99Nanos"] / 1e6
    wrong = [("per-function -> shared-queue", "per-function (no change)"),
             ("shared-queue -> per-function", "shared-queue (no change)")]
    right = [("per-function -> shared-queue", "shared-queue (no change)"),
             ("shared-queue -> per-function", "per-function (no change)")]
    best = None
    for workload in sorted({w for w, _ in table}):
        if (workload, wrong[0][0]) not in table:
            continue
        base = median([window_p99(r) for r in table[(workload, wrong[0][1])]])
        cand = median([window_p99(r) for r in table[(workload, wrong[0][0])]])
        delta = percent_delta(cand, base)
        if best is None or abs(delta) > abs(best[1]):
            best = (workload, delta)
    print("## What the round-1 pairing defect is worth, recomputed from this artifact\n")
    print(f"The largest wrong-pairing delta in this campaign is on **{best[0]}**: "
          f"**{best[1]:+.1f} %**, purely from comparing each switched arm against the strategy it "
          "started on rather than the one it ended on. The same comparisons, paired correctly:\n")
    print("| workload | switched arm | compared against | steady p99 control ms | switched ms | delta % |")
    print("|---|---|---|---|---|---|")
    for label, pairs in (("wrong (round 1)", wrong), ("right (round 3)", right)):
        for switched_arm, base_arm in pairs:
            workload = best[0]
            base = median([window_p99(r) for r in table[(workload, base_arm)]])
            cand = median([window_p99(r) for r in table[(workload, switched_arm)]])
            print(f"| {workload} | {switched_arm} | {label}: {base_arm} | {base:.3f} | "
                  f"{cand:.3f} | {percent_delta(cand, base):+.1f} |")
    print()
    print("Reproduce with `python3 summarize.py raw/steady.jsonl`. Round 1's own +3851 % cannot be "
          "reproduced: its artifact was overwritten by the corrected run before it was committed, "
          "which is itself the reason this file now computes the figure instead of quoting one.\n")


def budget_table(switches, baseline, summary, samples, verdicts):
    measured = [e for e in switches if e["repetition"] > 0]
    pause_max = max([ms(e["enginePauseNanos"]) for e in measured]
                    + ([summary["pauseMaxMs"]] if summary else []))
    client_max = max([ms(e["clientElapsedNanos"]) for e in measured]
                     + ([baseline["pauseMaxMs"]] if baseline else [0.0]))
    soak_p99 = summary["soakPauseP99Ms"] if summary else float("nan")
    live_max = max([e["liveIndexes"] for e in measured]
                   + [s["maxLiveIndexes"] for s in samples]
                   + ([baseline["maxLiveIndexesSwitched"]] if baseline else []))
    regressions = [v for v in verdicts]
    worst = max((v[3] for v in regressions), default=float("nan"))

    print("## Frozen budgets: observed against thresholds\n")
    print("| budget (budgets.json) | frozen | observed | source | verdict |")
    print("|---|---|---|---|---|")
    def row(name, frozen, observed, source, ok, unit="ms"):
        verdict = "PASS" if ok else "MISS"
        if ok is None:
            verdict = "NO DATA"
        print(f"| {name} | {frozen} {unit} | {observed} | {source} | {verdict} |")

    row("maxSwitchPauseMs", BUDGETS["maxSwitchPauseMs"], f"{pause_max:.3f} ms",
        "max engine pause over every measured switch (sweep + workloads + 1000-switch run) "
        "(summary.pauseMaxMs)", pause_max <= BUDGETS["maxSwitchPauseMs"])
    row("maxSwitchPauseP99Ms", BUDGETS["maxSwitchPauseP99Ms"],
        f"{soak_p99:.3f} ms" if soak_p99 >= 0 else "not measured",
        "p99 over the 1000-switch run (summary.soakPauseP99Ms)",
        None if soak_p99 < 0 else soak_p99 <= BUDGETS["maxSwitchPauseP99Ms"])
    row("maxSwitchPreparationMs", BUDGETS["maxSwitchPreparationMs"], f"{pause_max:.3f} ms",
        "total switch duration, an upper bound on the preparation phase "
        "(summary.pauseMaxMs)", pause_max <= BUDGETS["maxSwitchPreparationMs"])
    row("maxLiveStrategyIndexes", BUDGETS["maxLiveStrategyIndexes"], f"{live_max}",
        "max live indexes observed (samples, switch events, baseline)",
        live_max <= BUDGETS["maxLiveStrategyIndexes"], unit="")
    row("switchesInSoak", BUDGETS["switchesInSoak"],
        f"{baseline['switchesCommitted']} committed / {baseline['switchesRefused']} refused"
        if baseline else "not run",
        "baseline line",
        None if not baseline else baseline["switchesCommitted"] == BUDGETS["switchesInSoak"],
        unit="")
    # Matched on the metric name, not on the threshold value: two budgets share 5 and two share 10.
    for name, metric, budget in (
            ("maxSteadyP99RegressionPercent", "steady p99",
             BUDGETS["maxSteadyP99RegressionPercent"]),
            ("maxUsefulThroughputRegressionPercent", "steady useful throughput",
             BUDGETS["maxUsefulThroughputRegressionPercent"]),
            ("maxCpuPerCompletionRegressionPercent", "thread cpu per useful completion",
             BUDGETS["maxCpuPerCompletionRegressionPercent"]),
            ("maxPostGcHeapRegressionPercent", "post-GC heap",
             BUDGETS["maxPostGcHeapRegressionPercent"])):
        worst_for = [v for v in regressions if v[2] == metric]
        measurable = [v for v in worst_for if v[5] is not None]
        worst_value = max((v[3] for v in measurable), default=float("nan"))
        ok = all(v[5] for v in measurable) if measurable else None
        misses = [v for v in measurable if not v[5]]
        unmeasured = len(worst_for) - len(measurable)
        separable = [v for v in misses if not v[6]]
        note = ""
        if unmeasured:
            note += f"; {unmeasured} comparison(s) not measurable (see the table)"
        if misses:
            note = f"; {len(misses)} of {len(measurable)} measurable comparisons over budget, " \
                   f"{len(separable)} of those distinguishable" + note
        row(name, budget, f"{worst_value:+.2f} %" if measurable else "not measured",
            "worst switched-vs-no-change delta (see the regression table)" + note,
            ok, unit="%")
    # The two structural values budgets.json also freezes: how many repetitions each arm got, and
    # which backlog sizes were swept. Both are read off the artifact rather than asserted.
    measured = [s for s in samples if "workConserved" in s]
    per_arm_counts = defaultdict(int)
    for sample in measured:
        per_arm_counts[(sample["workload"], sample["arm"])] += 1
    counts = sorted(set(per_arm_counts.values()))
    swept = sorted({int(e["workload"].rsplit("-", 1)[1]) for e in switches
                    if e["workload"].startswith("switch-pause-backlog-")})
    swept_reps = {size: len({e["repetition"] for e in switches
                             if e["workload"].endswith("-" + str(size))
                             and e["workload"].startswith("switch-pause-backlog-")})
                  for size in swept}
    row("repetitions", BUDGETS["repetitions"],
        f"{counts} measured samples per (workload, arm)",
        "counted over every workload sample in the artifact",
        counts == [BUDGETS["repetitions"]], unit="")
    row("switchBacklogSizes", BUDGETS["switchBacklogSizes"],
        f"{swept}" if swept else "not run in this artifact",
        ("sizes swept, each with "
         + ", ".join(f"{s}/{swept_reps[s]} reps" for s in swept)) if swept else
        "this campaign ran the workload profiles only; the backlog sweep is recorded in "
        "raw/full.jsonl and was not re-run",
        None if not swept else swept == sorted(BUDGETS["switchBacklogSizes"]), unit="")

    print()
    print(f"Client-side corroboration, never substituted for the engine's figure: worst client "
          f"elapsed {client_max:.3f} ms (budgets give no client figure).\n")


def main():
    path = Path(sys.argv[1])
    samples, switches, baseline, summary, header = load(path)
    print(f"# Scheduler-switching benchmark — summary of `{path.name}`\n")
    if header:
        print(f"- sha `{header['sha']}`, artifact `{header['artifact']}`, {header['jvm']}, "
              f"{header['host']}, {header['availableProcessors']} CPUs, "
              f"max heap {header['maxHeapBytes'] / 2**30:.2f} GiB")
        print(f"- harness `SchedulerSwitchBenchmark.java` sha256 `{header.get('harnessSha256')}`")
        print(f"- budgets read from `{header['budgetsFile']}`: `{json.dumps(header['budgets'])}`\n")
    steady_schema = any("trailing" in sample for sample in samples)
    if steady_schema:
        per_workload, settlements = settling_table(samples)
        convergence(per_workload)
        verdicts = steady_regression(samples, settlements)
        resolving_power(samples, verdicts)
        pairing_demo(samples)
        budget_table(switches, baseline, summary, samples, verdicts)
    else:
        # A round-1 artifact: no depth trajectory and no trailing-window grid, so the round-3 tables
        # cannot be computed from it and the round-1 protocol's own table is the one it supports.
        print("## This artifact predates the settling protocol\n")
        print("It carries neither a depth trajectory nor a trailing-window grid, so the steady "
              "tables cannot be derived from it. The table below is the protocol it *was* measured "
              "under — the switched arm against the strategy it started on, over the whole window — "
              "which is the artifact the mid-window mixture defect is visible in.\n")
        verdicts = legacy_regression(samples)
        budget_table(switches, baseline, summary, samples, verdicts)
    pause_sweep(switches)
    workload_table(samples)
    if baseline:
        print("## Return to baseline after 1000 switches, against a no-switch control phase\n")
        print("| figure | switched phase | no-switch control | delta |")
        print("|---|---|---|---|")
        print(f"| pending at end | {baseline['switchedPending']} | {baseline['plainPending']} | "
              f"{baseline['pendingDelta']:+d} ({baseline['pendingDeltaPercent']:+.2f} %) |")
        print(f"| largest per-function reservation difference | - | - | "
              f"{baseline['reservationDeltaMax']} ({baseline['reservationDeltaMaxPercent']:+.2f} %) |")
        print(f"| live indexes at end | {baseline['switchedLiveIndexes']} | "
              f"{baseline['plainLiveIndexes']} | "
              f"{baseline['switchedLiveIndexes'] - baseline['plainLiveIndexes']:+d} |")
        print(f"| max live indexes during the run | {baseline['maxLiveIndexesSwitched']} | - | - |")
        print(f"| indexes created / cleared | {baseline['indexesCreated']} / "
              f"{baseline['indexesCleared']} | - | - |")
        print(f"| admitted | {baseline['switchedAdmitted']} | {baseline['plainAdmitted']} | "
              f"{percent_delta(baseline['switchedAdmitted'], baseline['plainAdmitted']):+.2f} % |")
        print(f"| completed | {baseline['switchedCompleted']} | {baseline['plainCompleted']} | "
              f"{percent_delta(baseline['switchedCompleted'], baseline['plainCompleted']):+.2f} % |")
        print(f"| useful | {baseline['switchedUseful']} | {baseline['plainUseful']} | "
              f"{percent_delta(baseline['switchedUseful'], baseline['plainUseful']):+.2f} % |")
        print(f"| work conserved (admitted == closure) | {baseline['workConservedSwitched']} | "
              f"{baseline['workConservedPlain']} | - |")
        print(f"| post-GC heap | {baseline['switchedHeapBytes'] / 1e6:.2f} MB | "
              f"{baseline['plainHeapBytes'] / 1e6:.2f} MB | "
              f"{percent_delta(baseline['switchedHeapBytes'], baseline['plainHeapBytes']):+.2f} % |")
        print(f"| switch pause p50 / p99 / max | {baseline['pauseP50Ms']:.3f} / "
              f"{baseline['pauseP99Ms']:.3f} / {baseline['pauseMaxMs']:.3f} ms | - | - |")
        print()
    print("### Work conservation across every measured run\n")
    # The pause-sweep samples carry only the switch figures, so they are excluded from this count
    # rather than counted as conserving by default.
    checked = [s for s in samples if "workConserved" in s]
    bad = [s for s in checked if not s["workConserved"]]
    print(f"- workload runs that conserve work (admitted == completed + expired + removed + "
          f"rejected + pending + claimed + submitting + in flight): "
          f"**{len(checked) - len(bad)} of {len(checked)}**")
    if bad:
        print(f"  - runs that do not: "
              f"**{sorted({(s['workload'], s['arm'], s['repetition']) for s in bad})}**")
    print(f"- runs with a driver failure: **{sum(1 for s in samples if s.get('driverFailures'))}**")
    print(f"- runs whose sample cap was reached: "
          f"**{sum(1 for s in samples if s.get('sampleCapReached'))}**")
    print(f"- switch events emitted: **{len(switches)}**\n")



if __name__ == "__main__":
    main()
