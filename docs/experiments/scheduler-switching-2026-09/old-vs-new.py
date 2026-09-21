#!/usr/bin/env python3
"""The old loop against the new engine, per profile (issue #208, Task 12e).

    ./old-vs-new.py raw/old-vs-new.jsonl

The comparison the spec's §11 asks for — «verificano regressioni rispetto alle implementazioni
precedenti» — and that Task 12c could not produce, because it can only swap strategies inside one
engine. The control arm is the OLD loop, the candidate is the new engine: a regression is the new
engine being WORSE, so p99, CPU per useful completion and post-GC heap regress upwards and useful
throughput regresses downwards.

The settlement rule, the JSONL schema, the percentile convention and the "shift the control arm's
own values and see when the arms separate" operation are `summarize.py`'s, imported rather than
restated: a second copy of a rule is a second rule.
"""
import statistics
import sys
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import summarize  # noqa: E402  (the committed analysis module, reused, not modified)

OLD = "old-async (no change)"
NEW = "per-function (no change)"
BUDGETS = summarize.BUDGETS
WINDOW = summarize.STEADY_WINDOW_MS
# Below this many arrivals a trailing window is a ratio of small integers, as in summarize.py.
MIN_STEADY_ARRIVALS = 30
FACTORS = (0.05, 0.10, 0.20, 0.30, 0.50, 1.00)


def by_arm(samples):
    table = defaultdict(list)
    for sample in samples:
        table[(sample["workload"], sample["arm"])].append(sample)
    return table


def load(path):
    samples, switches, baseline, summary, header = summarize.load(path)
    assert not switches, "this artifact carries no switch events"
    return samples, header


# ----------------------------------------------------------------------------------------------
# 1. Coverage, and whether a profile's arms differ by policy as well as by loop
# ----------------------------------------------------------------------------------------------
def coverage(samples, header):
    table = by_arm(samples)
    workloads = sorted({w for w, _ in table})
    print("## Coverage\n")
    print(f"- revision `{header['sha']}`, harness `{header['harnessSha256'][:12]}`, "
          f"committed harness `{header['benchmarkSha256'][:12]}`")
    print(f"- JVM {header['jvm']} on {header['host']}, {header['availableProcessors']} processors")
    print(f"- profiles requested: `{header['profiles']}`, repetitions: {header['repetitions']}")
    print(f"- admission: {header['admissionAlignment']}")
    print(f"- expiry in the old arm: {header['expiryInOldArm']}")
    print()
    print("| workload | old reps | new reps | new expired | old expired | offered old/new | "
          "admitted old/new | policy-confounded |")
    print("|---|---|---|---|---|---|---|---|")
    confounded = {}
    for workload in workloads:
        old = table.get((workload, OLD), [])
        new = table.get((workload, NEW), [])
        if not old or not new:
            continue
        new_exp = statistics.median([r["expired"] for r in new])
        old_exp = statistics.median([r["expired"] for r in old])
        offered = (statistics.median([r["offered"] for r in old]),
                   statistics.median([r["offered"] for r in new]))
        admitted = (statistics.median([r["admitted"] for r in old]),
                    statistics.median([r["admitted"] for r in new]))
        # The old loop has no deadline, so the engine's reaper is the one policy difference the
        # driver cannot equalise. Where it fires, the arms differ by the reaper as well as by the
        # loop, and the row is flagged rather than quietly averaged.
        flag = "yes" if (new_exp > 0 or old_exp > 0) else "no"
        confounded[workload] = flag == "yes"
        print(f"| {workload} | {len(old)} | {len(new)} | {new_exp:.0f} | {old_exp:.0f} | "
              f"{offered[0]:.0f}/{offered[1]:.0f} | {admitted[0]:.0f}/{admitted[1]:.0f} | {flag} |")
    print()
    checked = [s for s in samples if "workConserved" in s]
    bad = [s for s in checked if not s["workConserved"]]
    print(f"- runs that conserve work (admitted == completed + expired + removed + rejected + "
          f"pending + claimed + submitting + in flight): **{len(checked) - len(bad)} of "
          f"{len(checked)}**")
    if bad:
        print(f"  - runs that do not: "
              f"**{sorted({(s['workload'], s['arm'], s['repetition']) for s in bad})}**")
    print(f"- runs with a driver failure: **{sum(1 for s in samples if s.get('driverFailures'))}**")
    print(f"- runs whose sample cap was reached: "
          f"**{sum(1 for s in samples if s.get('sampleCapReached'))}**")
    print(f"- runs whose steady window holds no arrivals on one arm: "
          f"**{not_measurable_count(table)}** of {len(workloads)} workloads\n")
    return workloads, table, confounded


def steady_arrivals(table, workload, arm):
    return statistics.median([r["trailing"][str(WINDOW)]["samples"]
                              for r in table[(workload, arm)]])


def not_measurable_count(table):
    misses = 0
    for workload in sorted({w for w, _ in table}):
        if min(steady_arrivals(table, workload, OLD), steady_arrivals(table, workload, NEW)) \
                < MIN_STEADY_ARRIVALS:
            misses += 1
    return misses


# ----------------------------------------------------------------------------------------------
# 2. Does the old loop converge, on the committed settlement rule?
# ----------------------------------------------------------------------------------------------
def settling(samples, table, workloads):
    print("## Does the old loop settle? — the committed rule, read off its own depth trajectory\n")
    print("`settledDepth` is the median of the final quarter of the trajectory, `settleMillis` "
          "the last point of it still outside that distribution's Tukey fence; both read the "
          "queue-depth series, so the segmentation cannot be steered by the latency it segments. "
          f"The steady window is the trailing {WINDOW} ms.\n")
    print("| workload | arm | settled depth | settle ms (worst rep) | settled before the "
          "steady window | arrivals in the steady window |")
    print("|---|---|---|---|---|---|")
    settlements = {}
    for workload in workloads:
        for arm in (OLD, NEW):
            runs = table.get((workload, arm))
            if not runs:
                continue
            series = [r["depthSeries"] for r in runs]
            interval = runs[0]["depthSampleIntervalNanos"] / 1e6
            settles = [summarize.settle_millis(s, interval) for s in series]
            levels = [summarize.settled_depth(s) for s in series]
            span = runs[0]["windowMillis"]
            settlements[(workload, arm)] = max(settles) <= span - WINDOW
            print(f"| {workload} | {arm} | {min(levels):.0f}-{max(levels):.0f} | "
                  f"{max(settles):.0f} | "
                  f"{'yes' if settlements[(workload, arm)] else 'NO'} | "
                  f"{steady_arrivals(table, workload, arm):.0f} |")
    print()
    return settlements


# ----------------------------------------------------------------------------------------------
# 3. The per-profile comparison, paired
# ----------------------------------------------------------------------------------------------
METRICS = (
    # label, extractor, session, budget key (None = reported, no frozen budget covers it),
    # direction in which the new engine regresses
    ("whole-span p99", lambda r: r["p99Nanos"] / 1e6, "whole",
     "maxSteadyP99RegressionPercent", "up"),
    ("steady p99", lambda r: r["trailing"][str(WINDOW)]["p99Nanos"] / 1e6, "window",
     "maxSteadyP99RegressionPercent", "up"),
    ("whole-span useful throughput", lambda r: r["usefulThroughputPerSecond"], "whole",
     "maxUsefulThroughputRegressionPercent", "down"),
    ("steady useful throughput", lambda r: r["trailing"][str(WINDOW)]["usefulThroughputPerSecond"],
     "window", "maxUsefulThroughputRegressionPercent", "down"),
    ("thread cpu per useful completion", lambda r: r["threadCpuPerUsefulCompletionNanos"], "whole",
     "maxCpuPerCompletionRegressionPercent", "up"),
    ("post-GC heap", lambda r: r["postGcHeapBytes"], "whole",
     "maxPostGcHeapRegressionPercent", "up"),
    # Reported without a budget: no frozen threshold covers allocation, and the window's total
    # thread CPU is the same measurement the CPU budget reads with the useful count cancelled out
    # of the denominator, so a consistent sign across the two is the CPU figure's cross-check.
    ("allocated bytes per useful completion", lambda r: r["allocatedBytesPerUsefulCompletion"],
     "whole", None, "up"),
    ("thread cpu per window", lambda r: r["threadCpuNanos"], "whole", None, "up"),
)


def paired(table, workloads, settlements, confounded):
    print("## Old loop against the new engine, repetition by repetition\n")
    print("`paired` is the median of the five within-repetition differences "
          "`(new - old) / old`, which is what running both arms in one process on one arrival "
          "script buys: the host's common mode cancels. `unpaired` is the median-vs-median "
          "difference, for contrast. `separated` says whether all five paired differences agree "
          "in sign, which is the only thing that makes the median a measured effect rather than "
          "one draw of a noisy quantity. A `window` metric is marked NOT MEASURABLE where either "
          f"arm had not settled before the trailing {WINDOW} ms, that window holds fewer than "
          f"{MIN_STEADY_ARRIVALS} arrivals, or the control arm's own value there is zero. A `*` "
          "marks a profile whose arms differ by the expiry policy as well as by the loop.\n")
    print("| workload | metric | session | old | new | unpaired delta % | paired delta % "
          "(5 reps) | paired range | separated | budget % | verdict |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    rows = []
    for workload in workloads:
        for label, extract, session, budget_key, direction in METRICS:
            budget = BUDGETS[budget_key] if budget_key else None
            if session == "window" and (
                    not all(settlements.get((workload, arm), True) for arm in (OLD, NEW))
                    or min(steady_arrivals(table, workload, OLD),
                           steady_arrivals(table, workload, NEW)) < MIN_STEADY_ARRIVALS):
                causes = []
                if not all(settlements.get((workload, arm), True) for arm in (OLD, NEW)):
                    causes.append("an arm had not settled before the steady window")
                if min(steady_arrivals(table, workload, OLD),
                       steady_arrivals(table, workload, NEW)) < MIN_STEADY_ARRIVALS:
                    causes.append(f"fewer than {MIN_STEADY_ARRIVALS} arrivals in the steady window")
                print(f"| {workload} | {label} | {session} | — | — | — | — | — | — | "
                      f"{budget if budget is not None else '—'} | "
                      f"NOT MEASURABLE ({'; '.join(causes)}) |")
                rows.append((workload, label, float("nan"), float("nan"), budget, None))
                continue
            old_values = {r["repetition"]: extract(r) for r in table[(workload, OLD)]}
            new_values = {r["repetition"]: extract(r) for r in table[(workload, NEW)]}
            shared = sorted(set(old_values) & set(new_values))
            old_median = statistics.median(old_values.values())
            if old_median == 0:
                print(f"| {workload} | {label} | {session} | 0 | — | — | — | — | — | "
                      f"{budget if budget is not None else '—'} | NOT MEASURABLE (the control "
                      "arm's own value there is zero) |")
                rows.append((workload, label, float("nan"), float("nan"), budget, None))
                continue
            diffs = [summarize.percent_delta(new_values[k], old_values[k]) for k in shared]
            new_median = statistics.median(new_values.values())
            unpaired_delta = summarize.percent_delta(new_median, old_median)
            paired_delta = statistics.median(diffs)
            separated = min(diffs) > 0 or max(diffs) < 0
            if budget is None:
                verdict = "no budget"
            else:
                # A regression is the new engine being worse, so the sign that means "regression"
                # depends on the metric: throughput regresses downward, the rest upward.
                regressed = paired_delta if direction == "up" else -paired_delta
                verdict = "PASS" if regressed <= budget else "MISS"
            star = " *" if confounded.get(workload) else ""
            print(f"| {workload}{star} | {label} | {session} | {old_median:.3f} | "
                  f"{new_median:.3f} | {unpaired_delta:+.2f} | {paired_delta:+.2f} | "
                  f"{min(diffs):+.2f}…{max(diffs):+.2f} | "
                  f"{'yes' if separated else 'no'} | "
                  f"{budget if budget is not None else '—'} | {verdict} |")
            rows.append((workload, label, paired_delta, unpaired_delta, budget, verdict))
    print()
    print("A `MISS` with `separated: no` is a median that is not a measured effect: five "
          "repetitions put the difference on both sides of zero, so the row says the design did "
          "not resolve that metric on that profile, not that the engine regressed. On a `*` row a "
          "miss is a miss of the *profile*, not of the loop: the arms differ by the engine's "
          "reaper as well as by the loop, and the expiry figure in the coverage table is the size "
          "of that difference.\n")
    return rows


# ----------------------------------------------------------------------------------------------
# 4. Resolving power — what a uniform difference of each size would have looked like
# ----------------------------------------------------------------------------------------------
def resolving_power(table, workloads, settlements):
    print("## Resolving power: the smallest uniform difference this design would have detected\n")
    print("Both operations are deterministic and read the arms, never the observed effect. "
          "**Unpaired** multiplies the old arm's own five values by `(1 + f)` — a perfectly uniform "
          "change with no added noise — and counts how often the shifted arm reads disjoint from "
          "the new arm's range; that is the operation a cross-run design can perform, and the "
          "count is its resolving power. **Paired** asks the smaller question this design can ask: "
          "how large `f` must be before all five within-repetition differences take the sign `f` "
          "implies, which is what makes a paired median an effect rather than one draw. A budget "
          "below the unpaired row's first detectable factor could not have been adjudicated "
          "without the pairing.\n")
    print("| metric | budget % | eligible | " + " | ".join(f"+{int(f * 100)} %" for f in FACTORS)
          + " | median unpaired arm spread % | median paired spread % | "
          "median smallest paired same-signed effect % |")
    print("|---|---|---|" + "---|" * len(FACTORS) + "---|---|---|")
    for label, extract, session, budget_key, _direction in METRICS:
        budget = BUDGETS[budget_key] if budget_key else None
        unpaired_hits = {f: 0 for f in FACTORS}
        unpaired_spreads, paired_spreads, paired_effects = [], [], []
        eligible = 0
        for workload in workloads:
            if session == "window" and (
                    not all(settlements.get((workload, arm), True) for arm in (OLD, NEW))
                    or min(steady_arrivals(table, workload, OLD),
                           steady_arrivals(table, workload, NEW)) < MIN_STEADY_ARRIVALS):
                continue
            old_values = sorted(extract(r) for r in table[(workload, OLD)])
            new_values = sorted(extract(r) for r in table[(workload, NEW)])
            if not old_values or not new_values or old_values[-1] == 0:
                continue
            eligible += 1
            for factor in FACTORS:
                if old_values[0] * (1 + factor) > new_values[-1]:
                    unpaired_hits[factor] += 1
            mean_old = sum(old_values) / len(old_values)
            if mean_old:
                unpaired_spreads.append((old_values[-1] - old_values[0]) * 100.0 / mean_old)
            by_rep_old = {r["repetition"]: extract(r) for r in table[(workload, OLD)]}
            by_rep_new = {r["repetition"]: extract(r) for r in table[(workload, NEW)]}
            shared = sorted(set(by_rep_old) & set(by_rep_new))
            diffs = [summarize.percent_delta(by_rep_new[k], by_rep_old[k]) for k in shared]
            # The paired spread, in the same unit the budgets are stated in: percentage points of
            # the old arm's own value. This is the quantity the pairing shrinks, and the reason the
            # paired column can resolve an effect the unpaired column cannot.
            paired_spreads.append(max(diffs) - min(diffs))
            # The smallest uniform shift that would make every repetition agree in sign: a +f shift
            # needs f > -min(d), a -f one needs f > max(d); 0 means the arms already agree.
            paired_effects.append(min(max(0.0, -min(diffs)), max(0.0, max(diffs))))
        print(f"| {label} | {budget if budget is not None else '—'} | {eligible} | "
              + " | ".join(str(unpaired_hits[f]) for f in FACTORS)
              + f" | {summarize.median(unpaired_spreads):.2f} | "
              f"{summarize.median(paired_spreads):.2f} | "
              f"{summarize.median(paired_effects):.2f} |")
    print()
    print("The arms' own spread is what the unpaired column pays: without the pairing a uniform "
          "5 % effect on p99 or on useful throughput is invisible against arm-to-arm disagreement "
          "several times larger, which is the position Task 12c was left in. The paired column "
          "asks the same question of the quantity this design actually measures, and it is what "
          "makes a null here a statement about the effect rather than about the instrument.\n")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        raise SystemExit(2)
    samples, header = load(sys.argv[1])
    if header is None:
        raise SystemExit(f"{sys.argv[1]}: no header line, cannot attribute the artifact")
    workloads, table, confounded = coverage(samples, header)
    settlements = settling(samples, table, workloads)
    paired(table, workloads, settlements, confounded)
    resolving_power(table, workloads, settlements)


if __name__ == "__main__":
    main()
