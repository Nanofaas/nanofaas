#!/usr/bin/env python3
import json
import sys
from datetime import datetime
from pathlib import Path
from statistics import mean


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


def timer_ms(queries: dict[str, dict[str, object]], prefix: str, a: float, b: float, start: datetime) -> float:
    count = delta(queries, f"{prefix}_count", a, b, start)
    total = delta(queries, f"{prefix}_sum", a, b, start)
    return total / count * 1_000 if count else 0.0


def gauge_mean(queries: dict[str, dict[str, object]], name: str, a: float, b: float, start: datetime) -> float:
    values = [
        float(point["value"])
        for point in queries[name]["points"]
        if a <= seconds(str(point["timestamp"]), start) < b
    ]
    return mean(values) if values else 0.0


def main() -> None:
    snapshot = json.loads(Path(sys.argv[1]).read_text())
    queries = snapshot["queries"]
    start = datetime.fromisoformat(snapshot["start"])
    header = (
        "phase", "dispatch", "dispatch/s", "slot ms", "latency ms",
        "slot-latency us", "slot util %", "idle ms", "reacq ms", "reacq/dispatch", "queue ms",
        "inFlight", "queue depth", "wake us", "wake/dispatch",
    )
    print(" | ".join(header))
    print(" | ".join("---" for _ in header))
    for name, a, b in PHASES:
        dispatches = delta(queries, "function_dispatch_total", a, b, start)
        slot = timer_ms(queries, "function_dispatch_slot_hold_duration", a, b, start)
        latency = timer_ms(queries, "function_latency", a, b, start)
        queue_wait = timer_ms(queries, "function_queue_wait", a, b, start)
        reacquisition = timer_ms(queries, "function_dispatch_slot_reacquisition_delay", a, b, start)
        reacquisitions = delta(queries, "function_dispatch_slot_reacquisition_delay_count", a, b, start)
        wake = timer_ms(queries, "function_scheduler_wakeup_delay", a, b, start) * 1_000
        wakes = delta(queries, "function_scheduler_wakeup_delay_count", a, b, start)
        rate = dispatches / (b - a)
        concurrency = gauge_mean(queries, "function_effective_concurrency", a, b, start)
        cycle = concurrency / rate * 1_000 if rate else 0.0
        row = (
            name,
            f"{dispatches:.0f}",
            f"{rate:.1f}",
            f"{slot:.3f}",
            f"{latency:.3f}",
            f"{(slot - latency) * 1_000:.1f}",
            f"{rate * slot / concurrency / 10:.1f}" if concurrency else "0.0",
            f"{cycle - slot:.3f}",
            f"{reacquisition:.3f}",
            f"{reacquisitions / dispatches:.2f}" if dispatches else "0.00",
            f"{queue_wait:.3f}",
            f"{gauge_mean(queries, 'function_inFlight', a, b, start):.2f}",
            f"{gauge_mean(queries, 'function_queue_depth', a, b, start):.2f}",
            f"{wake:.1f}",
            f"{wakes / dispatches:.2f}" if dispatches else "0.00",
        )
        print(" | ".join(row))


if __name__ == "__main__":
    main()
