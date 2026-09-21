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
    print("| workload | arm | reps | useful/s | p99 ms | cpu/useful us | alloc/useful B | "
          "post-GC heap MB | pending | conserved |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    for workload in workloads:
        for arm in sorted({a for w, a in table if w == workload}):
            runs = table[(workload, arm)]
            useful = [r["usefulThroughputPerSecond"] for r in runs]
            p99 = [ms(r["p99Nanos"]) for r in runs]
            cpu = [r["cpuPerUsefulCompletionNanos"] / 1e3 for r in runs]
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
            ("maxUsefulThroughputRegressionPercent", "useful throughput",
             BUDGETS["maxUsefulThroughputRegressionPercent"]),
            ("maxCpuPerCompletionRegressionPercent", "cpu per useful completion",
             BUDGETS["maxCpuPerCompletionRegressionPercent"]),
            ("maxPostGcHeapRegressionPercent", "post-GC heap",
             BUDGETS["maxPostGcHeapRegressionPercent"])):
        worst_for = [v for v in regressions if v[2] == metric]
        worst_value = max((v[3] for v in worst_for), default=float("nan"))
        ok = all(v[5] for v in worst_for) if worst_for else None
        misses = [v for v in worst_for if not v[5]]
        separable = [v for v in misses if not v[6]]
        note = ""
        if misses:
            note = f"; {len(misses)} of {len(worst_for)} comparisons over budget, " \
                   f"{len(separable)} of those distinguishable"
        row(name, budget, f"{worst_value:+.2f} %" if worst_for else "not measured",
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
    row("switchBacklogSizes", BUDGETS["switchBacklogSizes"], f"{swept}",
        "sizes swept, each with "
        + ", ".join(f"{s}/{swept_reps[s]} reps" for s in swept),
        swept == sorted(BUDGETS["switchBacklogSizes"]), unit="")

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
    verdicts = regression(samples)
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
