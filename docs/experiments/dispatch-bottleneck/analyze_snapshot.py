#!/usr/bin/env python3
import argparse
import json
import math
import sys
from collections.abc import Callable
from datetime import datetime
from pathlib import Path
from statistics import mean, median


PHASES = (
    ("warm40", 0, 30),
    ("climb200", 30, 90),
    ("hold200", 90, 150),
    ("ramp600", 150, 160),
    ("spike600", 160, 190),
    ("rampDown200", 190, 200),
    ("recover200", 200, 260),
    ("climb350", 260, 320),
    ("hold350", 320, 365),
    ("ramp900", 365, 375),
    ("peak900", 375, 405),
    ("drain40", 405, 450),
)


def seconds(timestamp: str, start: datetime) -> float:
    return (datetime.fromisoformat(timestamp) - start).total_seconds()


def nearest(points: list[dict[str, object]], target: float, start: datetime) -> float:
    point = min(points, key=lambda item: abs(seconds(str(item["timestamp"]), start) - target))
    return float(point["value"])


def delta(queries: dict[str, dict[str, object]], name: str, a: float, b: float, start: datetime) -> float:
    points = queries[name]["points"]
    return nearest(points, b, start) - nearest(points, a, start)


def monotonic_delta(
    queries: dict[str, dict[str, object]],
    name: str,
    a: float,
    b: float,
    start: datetime,
    error_message: str,
) -> float:
    points = queries[name]["points"]
    first = min(
        range(len(points)),
        key=lambda index: abs(seconds(str(points[index]["timestamp"]), start) - a),
    )
    last = min(
        range(len(points)),
        key=lambda index: abs(seconds(str(points[index]["timestamp"]), start) - b),
    )
    if last < first:
        raise ValueError(f"{name} timestamps are out of order")
    values = [float(point["value"]) for point in points[first : last + 1]]
    if any(current < previous for previous, current in zip(values, values[1:])):
        raise ValueError(error_message)
    return values[-1] - values[0]


def timer_ms(queries: dict[str, dict[str, object]], prefix: str, a: float, b: float, start: datetime) -> float:
    count = delta(queries, f"{prefix}_count", a, b, start)
    total = delta(queries, f"{prefix}_sum", a, b, start)
    return total / count * 1_000 if count else 0.0


def optional_delta(
    queries: dict[str, dict[str, object]], name: str, a: float, b: float, start: datetime
) -> float | None:
    return delta(queries, name, a, b, start) if name in queries else None


def optional_timer_ms(
    queries: dict[str, dict[str, object]], prefix: str, a: float, b: float, start: datetime
) -> float | None:
    presence = (f"{prefix}_count" in queries, f"{prefix}_sum" in queries)
    if any(presence) and not all(presence):
        raise ValueError(f"{prefix} must provide a complete pair")
    return timer_ms(queries, prefix, a, b, start) if all(presence) else None


def slot_hold_ms(
    queries: dict[str, dict[str, object]], a: float, b: float, start: datetime
) -> float:
    seconds_name = "function_dispatch_slot_hold_seconds_total"
    events_name = "function_dispatch_slot_hold_events_total"
    historic_sum_name = "function_dispatch_slot_hold_duration_sum"
    historic_count_name = "function_dispatch_slot_hold_duration_count"
    counter_presence = (seconds_name in queries, events_name in queries)
    historic_presence = (historic_sum_name in queries, historic_count_name in queries)

    if any(counter_presence):
        if not all(counter_presence) or any(historic_presence):
            raise ValueError("slot hold metrics must provide a complete pair without mixing formats")
        events = monotonic_delta(
            queries, events_name, a, b, start, "slot hold counters decreased"
        )
        seconds = monotonic_delta(
            queries, seconds_name, a, b, start, "slot hold counters decreased"
        )
        if seconds < 0 or events < 0:
            raise ValueError("slot hold counters decreased")
        if not events:
            if seconds:
                raise ValueError("slot hold seconds changed without events")
            return 0.0
        return seconds / events * 1_000

    if not all(historic_presence):
        raise ValueError("slot hold metrics must provide a complete pair")
    count = monotonic_delta(
        queries, historic_count_name, a, b, start, "historic slot hold timer decreased"
    )
    total = monotonic_delta(
        queries, historic_sum_name, a, b, start, "historic slot hold timer decreased"
    )
    if total < 0 or count < 0:
        raise ValueError("historic slot hold timer decreased")
    if not count:
        if total:
            raise ValueError("historic slot hold sum changed without events")
        return 0.0
    return total / count * 1_000


def gauge_mean(queries: dict[str, dict[str, object]], name: str, a: float, b: float, start: datetime) -> float:
    values = [
        float(point["value"])
        for point in queries[name]["points"]
        if a <= seconds(str(point["timestamp"]), start) < b
    ]
    return mean(values) if values else 0.0


ValidationResult = tuple[str, bool, str]
VALIDATION_SERIES = (
    "function_dispatch_total",
    "scheduler_visit_duration_sum",
    "scheduler_idle_duration_sum",
    "process_uptime_seconds",
    "function_scheduler_dispatch_submit_duration_count",
    "function_inFlight",
    "function_dispatch_slot_hold_events_total",
    "function_dispatch_slot_hold_seconds_total",
    "function_dispatch_slot_hold_distribution_series",
    "function_timeout_total",
)


def series_values(queries: dict[str, dict[str, object]], name: str) -> list[float]:
    points = queries[name]["points"]
    values = [float(point["value"]) for point in points]
    if len(values) < 2:
        raise ValueError(f"{name} needs at least two samples")
    return values


def counter_delta(queries: dict[str, dict[str, object]], name: str) -> float:
    values = series_values(queries, name)
    if any(current < previous for previous, current in zip(values, values[1:])):
        raise ValueError(f"{name} decreased")
    return values[-1] - values[0]


def validation_result(name: str, check: Callable[[], str]) -> ValidationResult:
    try:
        detail = check()
        return name, True, str(detail)
    except ValueError as error:
        return name, False, str(error)


def finite_number(value: object, name: str) -> float:
    number = float(value)
    if not math.isfinite(number):
        raise ValueError(f"{name} must be finite")
    return number


def validation_inputs(
    snapshot: dict[str, object],
    k6_summary: dict[str, object],
    run_summary: dict[str, object] | None,
) -> tuple[dict[str, dict[str, object]], datetime]:
    queries = snapshot["queries"]
    if not isinstance(queries, dict):
        raise TypeError("snapshot.queries must be an object")
    start = datetime.fromisoformat(str(snapshot["start"]))
    start_is_aware = start.utcoffset() is not None
    window_start = PHASES[0][1]
    window_end = PHASES[-1][2]
    series_timestamps: dict[str, list[datetime]] = {}
    for name in VALIDATION_SERIES:
        points = queries[name]["points"]
        if not isinstance(points, list) or len(points) < 2:
            raise ValueError(f"{name} needs at least two samples")
        previous: datetime | None = None
        timestamps: list[datetime] = []
        for point in points:
            timestamp = datetime.fromisoformat(str(point["timestamp"]))
            if (timestamp.utcoffset() is not None) != start_is_aware:
                raise ValueError(f"{name} timestamp timezone does not match snapshot.start")
            finite_number(point["value"], f"{name} value")
            if previous is not None and timestamp <= previous:
                raise ValueError(f"{name} timestamps are not strictly increasing")
            previous = timestamp
            timestamps.append(timestamp)
        series_timestamps[name] = timestamps

    sampling_steps = {
        name: median(
            (current - previous).total_seconds()
            for previous, current in zip(timestamps, timestamps[1:])
        )
        for name, timestamps in series_timestamps.items()
    }
    nominal_cadence = median(sampling_steps.values())
    maximum_gap = nominal_cadence * 1.5
    for name, timestamps in series_timestamps.items():
        sampling_step = sampling_steps[name]
        first = (timestamps[0] - start).total_seconds()
        last = (timestamps[-1] - start).total_seconds()
        if first > window_start + sampling_step or last < window_end - sampling_step:
            raise ValueError(
                f"{name} covers {first:g}..{last:g}s, required "
                f"{window_start:g}..{window_end:g}s within {sampling_step:g}s sampling step"
            )
        observed_gap = max(
            (current - previous).total_seconds()
            for previous, current in zip(timestamps, timestamps[1:])
        )
        if observed_gap > maximum_gap:
            raise ValueError(
                f"{name} has {observed_gap:g}s sampling gap; nominal cadence "
                f"{nominal_cadence:g}s permits at most {maximum_gap:g}s"
            )
    finite_number(k6_summary["metrics"]["checks"]["fails"], "k6 checks.fails")
    if run_summary is not None and run_summary["schema_version"] != 1:
        raise ValueError("summary.json has unsupported schema_version")
    return queries, start


def validation_results(
    snapshot: dict[str, object],
    k6_summary: dict[str, object],
    run_summary: dict[str, object] | None = None,
) -> list[ValidationResult]:
    try:
        queries, start = validation_inputs(snapshot, k6_summary, run_summary)
    except (KeyError, IndexError, TypeError, ValueError) as error:
        return [("schema", False, str(error))]

    def accounting() -> str:
        missing_traffic = [
            phase
            for phase, a, b in PHASES
            if delta(queries, "function_dispatch_total", a, b, start) <= 0
        ]
        if missing_traffic:
            raise ValueError(
                "no dispatch traffic in phases: " + ", ".join(missing_traffic)
            )
        checked: list[str] = []
        accounted_seconds = 0.0
        window_seconds = 0.0
        for phase, a, b in PHASES:
            visit = monotonic_delta(
                queries,
                "scheduler_visit_duration_sum",
                a,
                b,
                start,
                "scheduler visit duration decreased",
            )
            idle = monotonic_delta(
                queries,
                "scheduler_idle_duration_sum",
                a,
                b,
                start,
                "scheduler idle duration decreased",
            )
            duration = b - a
            accounted_seconds += visit + idle
            window_seconds += duration
            accounted = (visit + idle) / duration * 100
            checked.append(f"{phase}={accounted:.3f}%")
        whole_run = accounted_seconds / window_seconds * 100
        checked.append(f"whole-run={whole_run:.3f}%")
        if not 98.0 <= whole_run <= 100.5:
            raise ValueError(f"{', '.join(checked)} outside whole-run 98.0..100.5")
        return ", ".join(checked)

    def uptime() -> str:
        values = series_values(queries, "process_uptime_seconds")
        if any(current < previous for previous, current in zip(values, values[1:])):
            raise ValueError("process_uptime_seconds decreased")
        return f"{len(values)} samples monotonic"

    def releases() -> str:
        submitted = counter_delta(
            queries, "function_scheduler_dispatch_submit_duration_count"
        )
        in_flight = series_values(queries, "function_inFlight")
        expected = submitted + in_flight[0] - in_flight[-1]
        measured = counter_delta(queries, "function_dispatch_slot_hold_events_total")
        if measured != expected:
            raise ValueError(f"measured {measured:g}, expected {expected:g}")
        return f"measured={measured:g}, expected={expected:g}"

    def slot_seconds() -> str:
        values = series_values(queries, "function_dispatch_slot_hold_seconds_total")
        if any(value < 0 for value in values):
            raise ValueError("slot hold seconds became negative")
        if any(current < previous for previous, current in zip(values, values[1:])):
            raise ValueError("slot hold seconds decreased")
        total = values[-1] - values[0]
        events = counter_delta(queries, "function_dispatch_slot_hold_events_total")
        if events > 0 and total <= 0:
            raise ValueError(f"{events:g} releases recorded with no positive slot hold time")
        if events == 0 and total != 0:
            raise ValueError("slot hold seconds changed without releases")
        return f"delta={total:g}"

    def distribution() -> str:
        values = series_values(
            queries, "function_dispatch_slot_hold_distribution_series"
        )
        if any(value != 0 for value in values):
            raise ValueError(f"distribution series count is {max(values):g}")
        return "0 series"

    def k6_checks() -> str:
        metrics = k6_summary["metrics"]
        checks = metrics["checks"]
        failed = float(checks["fails"])
        if failed != 0:
            raise ValueError(f"{failed:g} failed checks")
        return "0 failed checks"

    def function_timeouts() -> str:
        timed_out = counter_delta(queries, "function_timeout_total")
        if timed_out != 0:
            raise ValueError(f"{timed_out:g} function timeouts")
        return "0 function timeouts"

    checks = (
        ("wall-clock accounting", accounting),
        ("process uptime", uptime),
        ("slot hold releases", releases),
        ("slot hold seconds", slot_seconds),
        ("slot hold distribution", distribution),
        ("k6 checks", k6_checks),
        ("function timeouts", function_timeouts),
    )
    return [validation_result(name, check) for name, check in checks]


def validation_results_from_files(
    snapshot_path: Path,
    k6_summary_path: Path,
    run_summary_path: Path | None = None,
) -> list[ValidationResult]:
    paths = (snapshot_path, k6_summary_path) + (
        (run_summary_path,) if run_summary_path is not None else ()
    )
    missing = [str(path) for path in paths if not path.is_file()]
    if missing:
        return [("completion markers", False, "missing " + ", ".join(missing))]
    try:
        texts = [path.read_text(encoding="utf-8") for path in paths]
    except OSError as error:
        return [("completion markers", False, str(error))]
    except UnicodeDecodeError as error:
        return [
            ("completion markers", True, "required files present"),
            ("schema", False, str(error)),
        ]
    try:
        snapshot, k6_summary, *run_summaries = map(json.loads, texts)
    except json.JSONDecodeError as error:
        return [
            ("completion markers", True, "required files present"),
            ("schema", False, str(error)),
        ]
    run_summary = run_summaries[0] if run_summaries else None
    return [("completion markers", True, "required files present")] + validation_results(
        snapshot, k6_summary, run_summary
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("snapshot", type=Path)
    parser.add_argument("--validate", action="store_true")
    parser.add_argument("--k6-summary", type=Path)
    parser.add_argument("--run-summary", type=Path)
    args = parser.parse_args()
    if args.validate:
        if args.k6_summary is None or args.run_summary is None:
            results = [
                (
                    "completion markers",
                    False,
                    "--k6-summary and --run-summary are required",
                )
            ]
        else:
            results = validation_results_from_files(
                args.snapshot, args.k6_summary, args.run_summary
            )
        for name, passed, detail in results:
            print(f"{name}: {'PASS' if passed else 'FAIL'} - {detail}")
        return 1 if any(not passed for _name, passed, _detail in results) else 0

    snapshot = json.loads(args.snapshot.read_text())
    queries = snapshot["queries"]
    start = datetime.fromisoformat(snapshot["start"])
    direct_probes = "function_scheduler_dispatch_submit_duration_count" in queries
    signal_enqueue_probe = "function_scheduler_signal_enqueue_duration_count" in queries
    wakeup_split_probe = "function_scheduler_poll_delay_count" in queries
    thread_accounting = "scheduler_visit_duration_count" in queries
    header = (
        "phase", "dispatch", "dispatch/s", "slot ms", "latency ms",
        "slot-latency us", "slot util %", "idle ms", "reacq ms",
        "reacq sum/dispatch ms", "reacq<=idle", "pre-active ms", "active ms",
        "reacq/dispatch", "queue ms",
        "inFlight", "queue depth", "wake us", "wake/dispatch", "cpu cores",
    )
    if signal_enqueue_probe:
        header += ("enqueue us",)
    if wakeup_split_probe:
        header += ("poll us", "activation bookkeeping us")
    if direct_probes:
        header += (
            "submit us", "submit all util %", "blocked/dispatch",
            "coalesced/dispatch", "blocked all/dispatch all",
            "coalesced all/dispatch all",
        )
    if thread_accounting:
        header += (
            "visit us", "visits/dispatch", "thread busy %", "thread idle %",
            "accounted %", "accounted<=100",
        )
    print(" | ".join(header))
    print(" | ".join("---" for _ in header))
    for name, a, b in PHASES:
        dispatches = delta(queries, "function_dispatch_total", a, b, start)
        slot = slot_hold_ms(queries, a, b, start)
        latency = timer_ms(queries, "function_latency", a, b, start)
        queue_wait = timer_ms(queries, "function_queue_wait", a, b, start)
        reacquisition = optional_timer_ms(
            queries, "function_dispatch_slot_reacquisition_delay", a, b, start
        )
        active = optional_timer_ms(
            queries, "function_dispatch_slot_reacquisition_active_delay", a, b, start
        )
        reacquisitions = optional_delta(
            queries, "function_dispatch_slot_reacquisition_delay_count", a, b, start
        )
        reacquisition_sum = optional_delta(
            queries, "function_dispatch_slot_reacquisition_delay_sum", a, b, start
        )
        wake = timer_ms(queries, "function_scheduler_wakeup_delay", a, b, start) * 1_000
        wakes = delta(queries, "function_scheduler_wakeup_delay_count", a, b, start)
        rate = dispatches / (b - a)
        concurrency = gauge_mean(queries, "function_effective_concurrency", a, b, start)
        cycle = concurrency / rate * 1_000 if rate else 0.0
        idle = cycle - slot
        reacquisition_per_dispatch = (
            reacquisition_sum / dispatches * 1_000
            if reacquisition_sum is not None and dispatches
            else None
        )
        pre_active = (
            reacquisition - active
            if reacquisition is not None and active is not None
            else None
        )
        row = (
            name,
            f"{dispatches:.0f}",
            f"{rate:.1f}",
            f"{slot:.3f}",
            f"{latency:.3f}",
            f"{(slot - latency) * 1_000:.1f}",
            f"{rate * slot / concurrency / 10:.1f}" if concurrency else "0.0",
            f"{idle:.3f}",
            f"{reacquisition:.3f}" if reacquisition is not None else "N/A",
            f"{reacquisition_per_dispatch:.3f}"
            if reacquisition_per_dispatch is not None else "N/A",
            "N/A" if reacquisition_per_dispatch is None
            else "yes" if reacquisition_per_dispatch <= idle else "NO",
            f"{pre_active:.3f}" if pre_active is not None else "N/A",
            f"{active:.3f}" if active is not None else "N/A",
            "N/A" if reacquisitions is None
            else f"{reacquisitions / dispatches:.2f}" if dispatches else "0.00",
            f"{queue_wait:.3f}",
            f"{gauge_mean(queries, 'function_inFlight', a, b, start):.2f}",
            f"{gauge_mean(queries, 'function_queue_depth', a, b, start):.2f}",
            f"{wake:.1f}",
            f"{wakes / dispatches:.2f}" if dispatches else "0.00",
            f"{gauge_mean(queries, 'container_cpu_cores@control-plane', a, b, start):.2f}",
        )
        if signal_enqueue_probe:
            enqueue = timer_ms(
                queries, "function_scheduler_signal_enqueue_duration", a, b, start
            ) * 1_000
            row += (f"{enqueue:.1f}",)
        if wakeup_split_probe:
            poll = timer_ms(queries, "function_scheduler_poll_delay", a, b, start) * 1_000
            bookkeeping = timer_ms(
                queries, "function_scheduler_activation_bookkeeping_duration", a, b, start
            ) * 1_000
            row += (f"{poll:.1f}", f"{bookkeeping:.1f}")
        if direct_probes:
            submit = timer_ms(
                queries, "function_scheduler_dispatch_submit_duration", a, b, start
            ) * 1_000
            submit_all_sum = delta(
                queries, "function_scheduler_dispatch_submit_duration_all_sum", a, b, start
            )
            submit_all_count = delta(
                queries, "function_scheduler_dispatch_submit_duration_all_count", a, b, start
            )
            blocked = delta(queries, "function_scheduler_slot_blocked_total", a, b, start)
            blocked_all = delta(
                queries, "function_scheduler_slot_blocked_all_total", a, b, start
            )
            coalesced = delta(
                queries, "function_scheduler_signal_coalesced_total", a, b, start
            )
            coalesced_all = delta(
                queries, "function_scheduler_signal_coalesced_all_total", a, b, start
            )
            row += (
                f"{submit:.1f}",
                f"{submit_all_sum / (b - a) * 100:.1f}",
                f"{blocked / dispatches:.2f}" if dispatches else "0.00",
                f"{coalesced / dispatches:.2f}" if dispatches else "0.00",
                f"{blocked_all / submit_all_count:.2f}" if submit_all_count else "0.00",
                f"{coalesced_all / submit_all_count:.2f}" if submit_all_count else "0.00",
            )
        if thread_accounting:
            # One thread, so its visits and its waits partition the window.
            # Over 100% means the probe is measuring something other than what
            # it names -- the check the reacquisition timer never had to pass.
            visit_sum = delta(queries, "scheduler_visit_duration_sum", a, b, start)
            visit_count = delta(queries, "scheduler_visit_duration_count", a, b, start)
            idle_sum = delta(queries, "scheduler_idle_duration_sum", a, b, start)
            window = b - a
            accounted = (visit_sum + idle_sum) / window * 100
            row += (
                f"{visit_sum / visit_count * 1_000_000:.1f}" if visit_count else "0.0",
                f"{visit_count / dispatches:.2f}" if dispatches else "0.00",
                f"{visit_sum / window * 100:.1f}",
                f"{idle_sum / window * 100:.1f}",
                f"{accounted:.1f}",
                "yes" if accounted <= 100.0 else "NO",
            )
        print(" | ".join(row))
    return 0


if __name__ == "__main__":
    sys.exit(main())
