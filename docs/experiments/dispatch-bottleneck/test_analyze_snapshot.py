#!/usr/bin/env python3
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from datetime import datetime, timedelta
from unittest.mock import patch

import analyze_snapshot


class AnalyzeSnapshotTest(unittest.TestCase):
    def setUp(self) -> None:
        self.start = datetime.fromisoformat("2026-01-01T00:00:00+00:00")

    def series(
        self, first: float, second: float, first_at: float = 0, second_at: float = 10
    ) -> dict[str, object]:
        return {
            "points": [
                {
                    "timestamp": (self.start + timedelta(seconds=first_at)).isoformat(),
                    "value": first,
                },
                {
                    "timestamp": (self.start + timedelta(seconds=second_at)).isoformat(),
                    "value": second,
                },
            ]
        }

    def sampled_series(self, *samples: tuple[float, float]) -> dict[str, object]:
        return {
            "points": [
                {
                    "timestamp": (self.start + timedelta(seconds=at)).isoformat(),
                    "value": value,
                }
                for at, value in samples
            ]
        }

    def test_slot_hold_uses_phase_deltas_from_aggregate_counters(self) -> None:
        queries = {
            "function_dispatch_slot_hold_seconds_total": self.series(100, 104),
            "function_dispatch_slot_hold_events_total": self.series(10, 12),
        }

        self.assertEqual(analyze_snapshot.slot_hold_ms(queries, 0, 10, self.start), 2_000)

    def test_slot_hold_falls_back_to_historic_timer_series(self) -> None:
        queries = {
            "function_dispatch_slot_hold_duration_sum": self.series(20, 24),
            "function_dispatch_slot_hold_duration_count": self.series(5, 7),
        }

        self.assertEqual(analyze_snapshot.slot_hold_ms(queries, 0, 10, self.start), 2_000)

    def test_slot_hold_rejects_counter_resets(self) -> None:
        queries = {
            "function_dispatch_slot_hold_seconds_total": self.series(5, 0.5),
            "function_dispatch_slot_hold_events_total": self.series(100, 10),
        }

        with self.assertRaisesRegex(ValueError, "slot hold counters decreased"):
            analyze_snapshot.slot_hold_ms(queries, 0, 10, self.start)

    def test_slot_hold_rejects_an_internal_counter_reset(self) -> None:
        queries = {
            "function_dispatch_slot_hold_seconds_total": self.sampled_series(
                (0, 5), (5, 0.1), (10, 6)
            ),
            "function_dispatch_slot_hold_events_total": self.sampled_series(
                (0, 100), (5, 1), (10, 120)
            ),
        }

        with self.assertRaisesRegex(ValueError, "slot hold counters decreased"):
            analyze_snapshot.slot_hold_ms(queries, 0, 10, self.start)

    def test_slot_hold_requires_complete_metric_pairs(self) -> None:
        cases = (
            {"function_dispatch_slot_hold_seconds_total": self.series(0, 1)},
            {"function_dispatch_slot_hold_events_total": self.series(0, 1)},
            {"function_dispatch_slot_hold_duration_sum": self.series(0, 1)},
            {"function_dispatch_slot_hold_duration_count": self.series(0, 1)},
            {
                "function_dispatch_slot_hold_seconds_total": self.series(0, 1),
                "function_dispatch_slot_hold_duration_sum": self.series(0, 1),
                "function_dispatch_slot_hold_duration_count": self.series(0, 1),
            },
        )

        for queries in cases:
            with self.subTest(names=tuple(queries)):
                with self.assertRaisesRegex(ValueError, "complete pair"):
                    analyze_snapshot.slot_hold_ms(queries, 0, 10, self.start)

    def test_slot_hold_rejects_seconds_without_events(self) -> None:
        queries = {
            "function_dispatch_slot_hold_seconds_total": self.series(0, 1),
            "function_dispatch_slot_hold_events_total": self.series(10, 10),
        }

        with self.assertRaisesRegex(ValueError, "seconds changed without events"):
            analyze_snapshot.slot_hold_ms(queries, 0, 10, self.start)

    def test_slot_hold_accepts_an_empty_phase(self) -> None:
        queries = {
            "function_dispatch_slot_hold_seconds_total": self.series(10, 10),
            "function_dispatch_slot_hold_events_total": self.series(5, 5),
        }

        self.assertEqual(analyze_snapshot.slot_hold_ms(queries, 0, 10, self.start), 0)

    def test_historic_slot_hold_rejects_resets(self) -> None:
        queries = {
            "function_dispatch_slot_hold_duration_sum": self.series(20, 10),
            "function_dispatch_slot_hold_duration_count": self.series(5, 7),
        }

        with self.assertRaisesRegex(ValueError, "historic slot hold timer decreased"):
            analyze_snapshot.slot_hold_ms(queries, 0, 10, self.start)

    def test_historic_slot_hold_rejects_an_internal_reset(self) -> None:
        queries = {
            "function_dispatch_slot_hold_duration_sum": self.sampled_series(
                (0, 20), (5, 1), (10, 24)
            ),
            "function_dispatch_slot_hold_duration_count": self.sampled_series(
                (0, 5), (5, 1), (10, 7)
            ),
        }

        with self.assertRaisesRegex(ValueError, "historic slot hold timer decreased"):
            analyze_snapshot.slot_hold_ms(queries, 0, 10, self.start)

    def test_optional_timer_requires_a_complete_pair(self) -> None:
        for queries in (
            {"probe_count": self.series(0, 1)},
            {"probe_sum": self.series(0, 1)},
        ):
            with self.subTest(names=tuple(queries)):
                with self.assertRaisesRegex(ValueError, "complete pair"):
                    analyze_snapshot.optional_timer_ms(queries, "probe", 0, 10, self.start)

    def test_missing_optional_probe_is_not_reported_as_zero(self) -> None:
        self.assertIsNone(analyze_snapshot.optional_timer_ms({}, "missing", 0, 10, self.start))
        self.assertIsNone(analyze_snapshot.optional_delta({}, "missing", 0, 10, self.start))

    def test_main_marks_missing_reacquisition_probes_as_not_available(self) -> None:
        queries = {
            "function_dispatch_total": self.series(0, 10),
            "function_dispatch_slot_hold_seconds_total": self.series(0, 0.01),
            "function_dispatch_slot_hold_events_total": self.series(0, 10),
            "function_latency_count": self.series(0, 10),
            "function_latency_sum": self.series(0, 0.01),
            "function_queue_wait_count": self.series(0, 10),
            "function_queue_wait_sum": self.series(0, 0.01),
            "function_scheduler_wakeup_delay_count": self.series(0, 10),
            "function_scheduler_wakeup_delay_sum": self.series(0, 0.01),
            "function_effective_concurrency": self.series(2, 2),
            "function_inFlight": self.series(0, 0),
            "function_queue_depth": self.series(0, 0),
            "container_cpu_cores@control-plane": self.series(1, 1),
        }
        snapshot = {"start": self.start.isoformat(), "queries": queries}
        output = io.StringIO()

        with tempfile.NamedTemporaryFile(mode="w+", suffix=".json") as snapshot_file:
            json.dump(snapshot, snapshot_file)
            snapshot_file.flush()
            with (
                patch.object(analyze_snapshot, "PHASES", (("phase", 0, 10),)),
                patch.object(sys, "argv", ["analyze_snapshot.py", snapshot_file.name]),
                redirect_stdout(output),
            ):
                analyze_snapshot.main()

        header, _, row = output.getvalue().strip().splitlines()
        values = dict(zip(header.split(" | "), row.split(" | "), strict=True))
        for name in (
            "reacq ms",
            "reacq sum/dispatch ms",
            "reacq<=idle",
            "pre-active ms",
            "active ms",
            "reacq/dispatch",
        ):
            self.assertEqual(values[name], "N/A")

    def test_delta_uses_nearest_samples_when_boundaries_are_not_exact(self) -> None:
        queries = {"counter": self.series(3, 8, first_at=0.2, second_at=9.8)}

        self.assertEqual(analyze_snapshot.delta(queries, "counter", 0, 10, self.start), 5)


if __name__ == "__main__":
    unittest.main()
