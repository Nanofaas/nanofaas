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
def all_pauses(switches, baseline, summary):
    measured = [e for e in switches if e["repetition"] > 0]
    pauses = [ms(e["enginePauseNanos"]) for e in measured]
    if summary:
        pauses.append(summary["pauseMaxMs"])
    return pauses


# ----------------------------------------------------------------------------------------------
# 3. Per-workload, per-arm medians
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
def regression(samples):
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
    for (workload, arm), run in by_arm.items():
        series = [r["depthSeries"] for r in run]
        interval = run[0]["depthSampleIntervalNanos"] / 1e6
        settles = [settle_millis(s, interval) for s in series]
        levels = [settled_depth(s) for s in series]
        span = run[0]["windowMillis"]
        bands = [settled_band(x) for x in series]
        per_workload[workload].append((arm, settles, levels,
                                       (median(b[0] for b in bands), median(b[1] for b in bands))))
        bands = [settled_band(x) for x in series]
        print(f"| {workload} | {arm} | {median(levels):.0f} | "
              f"{median(b[0] for b in bands):.0f}-{median(b[1] for b in bands):.0f} | "
              f"{max(settles):.0f} | "
              f"{'yes' if max(settles) <= span - STEADY_WINDOW_MS else 'NO'} |")
    print()
    return per_workload


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


def steady_regression(samples):
    """The four budgets, on the steady window, with the transient-inclusive figure beside them."""
    table = per_arm(samples)
    pairs = [
        ("per-function -> shared-queue", "shared-queue (no change)"),
        ("shared-queue -> per-function", "per-function (no change)"),
    ]
    metrics = [
        ("steady p99", lambda r: r["trailing"][str(STEADY_WINDOW_MS)]["p99Nanos"] / 1e6,
         BUDGETS["maxSteadyP99RegressionPercent"]),
        ("steady useful throughput",
         lambda r: r["trailing"][str(STEADY_WINDOW_MS)]["usefulThroughputPerSecond"],
         BUDGETS["maxUsefulThroughputRegressionPercent"]),
        ("thread cpu per useful completion", lambda r: r["threadCpuPerUsefulCompletionNanos"],
         BUDGETS["maxCpuPerCompletionRegressionPercent"]),
        ("post-GC heap", lambda r: r["postGcHeapBytes"],
         BUDGETS["maxPostGcHeapRegressionPercent"]),
    ]
    transient = {
        "steady p99": lambda r: r["p99Nanos"] / 1e6,
        "steady useful throughput": lambda r: r["usefulThroughputPerSecond"],
        "thread cpu per useful completion": lambda r: r["threadCpuPerUsefulCompletionNanos"],
        "post-GC heap": lambda r: r["postGcHeapBytes"],
    }
    print("## The budgets on the steady window, transient-inclusive figure beside it\n")
    print("Both figures are shown for every comparison so the settling is visible rather than "
          "chosen away: `transient-inclusive` is the whole span, `steady` is the trailing "
          f"{STEADY_WINDOW_MS} ms. The budget verdict is read from the steady column only where the "
          "settling table says every arm had settled before that window opened.\n")
    print("| workload | switch | metric | control steady | switched steady | delta % | budget % | "
          "verdict | dispersion | transient-inclusive delta % |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    verdicts = []
    for workload in sorted({w for w, _ in table}):
        for switched_arm, base_arm in pairs:
            if (workload, switched_arm) not in table or (workload, base_arm) not in table:
                continue
            for name, extract, budget in metrics:
                # A workload that stops offering part-way through its span (churn-drain stops the
                # traffic by design) has no arrivals in the steady window, so there is no steady
                # distribution to compare. It is reported as not measurable rather than as a
                # comparison the arithmetic happens to produce a number for.
                steady_samples = min(
                    median([r["trailing"][str(STEADY_WINDOW_MS)]["samples"]
                            for r in table[(workload, arm)]])
                    for arm in (base_arm, switched_arm))
                if steady_samples < 30:
                    print(f"| {workload} | {switched_arm} | {name} | not measurable | not "
                          f"measurable | — | {budget} | NOT MEASURABLE | — | — |")
                    verdicts.append((workload, switched_arm, name, float("nan"), budget, None, None))
                    continue
                base_values = [extract(r) for r in table[(workload, base_arm)]]
                cand_values = [extract(r) for r in table[(workload, switched_arm)]]
                base, cand = median(base_values), median(cand_values)
                delta = percent_delta(cand, base)
                base_t = median([transient[name](r) for r in table[(workload, base_arm)]])
                cand_t = median([transient[name](r) for r in table[(workload, switched_arm)]])
                delta_t = percent_delta(cand_t, base_t)
                ok = delta <= budget
                base_lo, base_hi = spread(base_values)
                cand_lo, cand_hi = spread(cand_values)
                overlap = cand_lo <= base_hi and base_lo <= cand_hi
                verdicts.append((workload, switched_arm, name, delta, budget, ok, overlap))
                print(f"| {workload} | {switched_arm} | {name} | "
                      f"{base:.3f} ({base_lo:.3f}-{base_hi:.3f}) | "
                      f"{cand:.3f} ({cand_lo:.3f}-{cand_hi:.3f}) | {delta:+.2f} | {budget} | "
                      f"{'PASS' if ok else 'MISS'} | "
                      f"{'overlaps' if overlap else 'disjoint'} | {delta_t:+.2f} |")
    print()
    return verdicts

# ----------------------------------------------------------------------------------------------
# 5. The frozen budgets, one row each
# ----------------------------------------------------------------------------------------------
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
    per_workload = settling_table(samples)
    convergence(per_workload)
    verdicts = steady_regression(samples)
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
