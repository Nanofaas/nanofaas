#!/usr/bin/env python3
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from datetime import datetime, timedelta
from pathlib import Path
from unittest.mock import patch

import analyze_snapshot


class AnalyzeSnapshotTest(unittest.TestCase):
    def setUp(self) -> None:
        self.start = datetime.fromisoformat("2026-01-01T00:00:00+00:00")

    def series(
        self, first: float, second: float, first_at: float = 0, second_at: float = 10
    ) -> dict[str, object]:
        midpoint_at = (first_at + second_at) / 2
        midpoint = (first + second) / 2
        return {
            "points": [
                {
                    "timestamp": (self.start + timedelta(seconds=first_at)).isoformat(),
                    "value": first,
                },
                {
                    "timestamp": (
                        self.start + timedelta(seconds=midpoint_at)
                    ).isoformat(),
                    "value": midpoint,
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

    def valid_validation_inputs(self) -> tuple[dict[str, object], dict[str, object]]:
        queries = {
            "function_dispatch_total": self.series(0, 10),
            "scheduler_visit_duration_sum": self.series(0, 2),
            "scheduler_idle_duration_sum": self.series(0, 7.9),
            "process_uptime_seconds": self.sampled_series((0, 10), (5, 15), (10, 20)),
            "function_scheduler_dispatch_submit_duration_count": self.series(0, 10),
            "function_inFlight": self.series(0, 0),
            "function_dispatch_slot_hold_events_total": self.series(0, 10),
            "function_dispatch_slot_hold_seconds_total": self.series(0, 1),
            "function_dispatch_slot_hold_distribution_series": self.series(0, 0),
            "function_timeout_total": self.series(0, 0),
        }
        return (
            {"start": self.start.isoformat(), "queries": queries},
            {"metrics": {"checks": {"passes": 10, "fails": 0}}},
        )

    def full_validation_inputs(
        self, end: int = 450, step: int = 5
    ) -> tuple[dict[str, object], dict[str, object]]:
        snapshot, k6 = self.valid_validation_inputs()
        samples = range(0, end + 1, step)
        values = {
            "function_dispatch_total": lambda at: at,
            "scheduler_visit_duration_sum": lambda at: at * 0.20,
            "scheduler_idle_duration_sum": lambda at: at * 0.79,
            "process_uptime_seconds": lambda at: 10 + at,
            "function_scheduler_dispatch_submit_duration_count": lambda at: at,
            "function_inFlight": lambda _at: 0,
            "function_dispatch_slot_hold_events_total": lambda at: at,
            "function_dispatch_slot_hold_seconds_total": lambda at: at / 1_000,
            "function_dispatch_slot_hold_distribution_series": lambda _at: 0,
            "function_timeout_total": lambda _at: 0,
        }
        snapshot["queries"] = {
            name: self.sampled_series(*((at, value(at)) for at in samples))
            for name, value in values.items()
        }
        return snapshot, k6

    def failed_criteria(
        self, snapshot: dict[str, object], k6: dict[str, object]
    ) -> set[str]:
        with patch.object(analyze_snapshot, "PHASES", (("traffic", 0, 10),)):
            return {
                name
                for name, passed, _detail in analyze_snapshot.validation_results(snapshot, k6)
                if not passed
            }

    def run_validation_cli(
        self,
        snapshot: dict[str, object] | bytes,
        k6: dict[str, object] | bytes,
    ) -> tuple[int, str]:
        output = io.StringIO()
        with tempfile.TemporaryDirectory() as directory:
            snapshot_path = Path(directory) / "prometheus-snapshot.json"
            k6_path = Path(directory) / "k6-summary.json"
            run_summary_path = Path(directory) / "summary.json"
            snapshot_path.write_bytes(
                snapshot if isinstance(snapshot, bytes) else json.dumps(snapshot).encode()
            )
            k6_path.write_bytes(k6 if isinstance(k6, bytes) else json.dumps(k6).encode())
            run_summary_path.write_text(json.dumps({"schema_version": 1}), encoding="utf-8")
            with (
                patch.object(analyze_snapshot, "PHASES", (("traffic", 0, 10),)),
                patch.object(
                    sys,
                    "argv",
                    [
                        "analyze_snapshot.py",
                        str(snapshot_path),
                        "--validate",
                        "--k6-summary",
                        str(k6_path),
                        "--run-summary",
                        str(run_summary_path),
                    ],
                ),
                redirect_stdout(output),
            ):
                exit_code = analyze_snapshot.main()
        return exit_code, output.getvalue()

    def test_validation_accepts_a_valid_run(self) -> None:
        snapshot, k6 = self.valid_validation_inputs()

        self.assertEqual(self.failed_criteria(snapshot, k6), set())

    def test_validation_rejects_accounting_below_98_percent(self) -> None:
        snapshot, k6 = self.valid_validation_inputs()
        snapshot["queries"]["scheduler_idle_duration_sum"] = self.series(0, 7.7)

        self.assertIn("wall-clock accounting", self.failed_criteria(snapshot, k6))

    def test_validation_rejects_accounting_above_100_5_percent(self) -> None:
        snapshot, k6 = self.valid_validation_inputs()
        snapshot["queries"]["scheduler_idle_duration_sum"] = self.series(0, 8.06)

        self.assertIn("wall-clock accounting", self.failed_criteria(snapshot, k6))

    def test_validation_rejects_an_internal_uptime_decrease(self) -> None:
        snapshot, k6 = self.valid_validation_inputs()
        snapshot["queries"]["process_uptime_seconds"] = self.sampled_series(
            (0, 10), (5, 1), (10, 20)
        )

        self.assertIn("process uptime", self.failed_criteria(snapshot, k6))

    def test_validation_rejects_slot_events_that_do_not_match_releases(self) -> None:
        snapshot, k6 = self.valid_validation_inputs()
        snapshot["queries"]["function_dispatch_slot_hold_events_total"] = self.series(0, 9)

        self.assertIn("slot hold releases", self.failed_criteria(snapshot, k6))

    def test_release_formula_accounts_for_boundary_inflight(self) -> None:
        snapshot, k6 = self.valid_validation_inputs()
        snapshot["queries"]["function_scheduler_dispatch_submit_duration_count"] = (
            self.series(10, 414)
        )
        snapshot["queries"]["function_inFlight"] = self.series(2, 1)
        snapshot["queries"]["function_dispatch_slot_hold_events_total"] = self.series(0, 405)

        self.assertNotIn("slot hold releases", self.failed_criteria(snapshot, k6))

    def test_release_formula_rejects_wrong_boundary_total(self) -> None:
        snapshot, k6 = self.valid_validation_inputs()
        snapshot["queries"]["function_scheduler_dispatch_submit_duration_count"] = (
            self.series(10, 414)
        )
        snapshot["queries"]["function_inFlight"] = self.series(2, 1)
        snapshot["queries"]["function_dispatch_slot_hold_events_total"] = self.series(0, 404)

        self.assertIn("slot hold releases", self.failed_criteria(snapshot, k6))

    def test_validation_rejects_zero_slot_seconds_when_releases_exist(self) -> None:
        snapshot, k6 = self.valid_validation_inputs()
        snapshot["queries"]["function_scheduler_dispatch_submit_duration_count"] = (
            self.series(10, 414)
        )
        snapshot["queries"]["function_inFlight"] = self.series(2, 1)
        snapshot["queries"]["function_dispatch_slot_hold_events_total"] = self.series(0, 405)
        snapshot["queries"]["function_dispatch_slot_hold_seconds_total"] = self.series(0, 0)

        failed = self.failed_criteria(snapshot, k6)
        self.assertNotIn("slot hold releases", failed)
        self.assertIn("slot hold seconds", failed)

    def test_validation_rejects_negative_slot_seconds(self) -> None:
        snapshot, k6 = self.valid_validation_inputs()
        snapshot["queries"]["function_dispatch_slot_hold_seconds_total"] = self.series(-1, 1)

        self.assertIn("slot hold seconds", self.failed_criteria(snapshot, k6))

    def test_validation_rejects_an_internal_slot_seconds_reset(self) -> None:
        snapshot, k6 = self.valid_validation_inputs()
        snapshot["queries"]["function_dispatch_slot_hold_seconds_total"] = self.sampled_series(
            (0, 0), (5, 1), (10, 0.5)
        )

        self.assertIn("slot hold seconds", self.failed_criteria(snapshot, k6))

    def test_validation_rejects_failed_k6_checks(self) -> None:
        snapshot, k6 = self.valid_validation_inputs()
        k6["metrics"]["checks"]["fails"] = 1

        self.assertIn("k6 checks", self.failed_criteria(snapshot, k6))

    def test_validation_rejects_function_timeouts(self) -> None:
        snapshot, k6 = self.valid_validation_inputs()
        snapshot["queries"]["function_timeout_total"] = self.series(0, 1)

        self.assertIn("function timeouts", self.failed_criteria(snapshot, k6))

    def test_validation_rejects_slot_hold_distribution_series(self) -> None:
        snapshot, k6 = self.valid_validation_inputs()
        snapshot["queries"]["function_dispatch_slot_hold_distribution_series"] = self.series(1, 1)

        self.assertIn("slot hold distribution", self.failed_criteria(snapshot, k6))

    def test_validation_rejects_reversed_timestamps_as_schema(self) -> None:
        snapshot, k6 = self.valid_validation_inputs()
        snapshot["queries"]["scheduler_visit_duration_sum"]["points"].reverse()

        with patch.object(analyze_snapshot, "PHASES", (("traffic", 0, 10),)):
            results = analyze_snapshot.validation_results(snapshot, k6)

        self.assertIn("schema", {name for name, passed, _ in results if not passed})

    def test_validation_rejects_missing_k6_checks_as_schema(self) -> None:
        snapshot, k6 = self.valid_validation_inputs()
        del k6["metrics"]["checks"]

        self.assertIn("schema", self.failed_criteria(snapshot, k6))

    def test_validate_mode_rejects_mixed_timestamp_awareness_without_traceback(self) -> None:
        snapshot, k6 = self.valid_validation_inputs()
        for query in snapshot["queries"].values():
            for point in query["points"]:
                point["timestamp"] = datetime.fromisoformat(point["timestamp"]).replace(
                    tzinfo=None
                ).isoformat()

        exit_code, output = self.run_validation_cli(snapshot, k6)

        self.assertEqual(exit_code, 1)
        self.assertIn("schema: FAIL", output)

    def test_validation_rejects_non_finite_query_values_as_schema(self) -> None:
        for value in (float("nan"), float("inf"), float("-inf")):
            with self.subTest(value=value):
                snapshot, k6 = self.valid_validation_inputs()
                snapshot["queries"]["function_dispatch_slot_hold_seconds_total"][
                    "points"
                ][1]["value"] = value

                self.assertIn("schema", self.failed_criteria(snapshot, k6))

    def test_validation_rejects_non_finite_k6_failures_as_schema(self) -> None:
        for value in (float("nan"), float("inf"), float("-inf")):
            with self.subTest(value=value):
                snapshot, k6 = self.valid_validation_inputs()
                k6["metrics"]["checks"]["fails"] = value

                self.assertIn("schema", self.failed_criteria(snapshot, k6))

    def test_validation_rejects_a_snapshot_truncated_at_405_seconds(self) -> None:
        snapshot, k6 = self.full_validation_inputs(end=405)

        results = analyze_snapshot.validation_results(snapshot, k6)

        self.assertIn("schema", {name for name, passed, _ in results if not passed})

    def test_validation_accepts_regular_five_second_cadence(self) -> None:
        snapshot, k6 = self.full_validation_inputs()

        self.assertEqual(
            {
                name
                for name, passed, _detail in analyze_snapshot.validation_results(
                    snapshot, k6
                )
                if not passed
            },
            set(),
        )

    def test_validation_rejects_internal_sampling_gap(self) -> None:
        snapshot, k6 = self.full_validation_inputs()
        points = snapshot["queries"]["function_inFlight"]["points"]
        snapshot["queries"]["function_inFlight"]["points"] = [
            point
            for point in points
            if not 100
            <= (datetime.fromisoformat(point["timestamp"]) - self.start).total_seconds()
            <= 200
        ]

        self.assertIn(
            "schema",
            {
                name
                for name, passed, _detail in analyze_snapshot.validation_results(
                    snapshot, k6
                )
                if not passed
            },
        )

    def test_validation_rejects_series_with_only_endpoints(self) -> None:
        snapshot, k6 = self.full_validation_inputs()
        points = snapshot["queries"]["function_inFlight"]["points"]
        snapshot["queries"]["function_inFlight"]["points"] = [points[0], points[-1]]

        self.assertIn(
            "schema",
            {
                name
                for name, passed, _detail in analyze_snapshot.validation_results(
                    snapshot, k6
                )
                if not passed
            },
        )

    def test_validate_mode_rejects_invalid_utf8_without_traceback(self) -> None:
        snapshot, k6 = self.valid_validation_inputs()
        for invalid_snapshot, invalid_k6 in ((b"\xff", k6), (snapshot, b"\xff")):
            with self.subTest(snapshot=isinstance(invalid_snapshot, bytes)):
                exit_code, output = self.run_validation_cli(invalid_snapshot, invalid_k6)
                self.assertEqual(exit_code, 1)
                self.assertIn("schema: FAIL", output)

    def test_validation_rejects_an_incomplete_nanolab_cell(self) -> None:
        snapshot, _k6 = self.valid_validation_inputs()
        with tempfile.TemporaryDirectory() as directory:
            snapshot_path = Path(directory) / "metrics" / "prometheus-snapshot.json"
            snapshot_path.parent.mkdir()
            snapshot_path.write_text(json.dumps(snapshot), encoding="utf-8")

            results = analyze_snapshot.validation_results_from_files(
                snapshot_path, Path(directory) / "k6-summary.json"
            )

        self.assertIn(
            "completion markers", {name for name, passed, _ in results if not passed}
        )

    def test_validate_mode_prints_every_criterion_and_returns_nonzero(self) -> None:
        snapshot, k6 = self.valid_validation_inputs()
        k6["metrics"]["checks"]["fails"] = 1
        exit_code, output = self.run_validation_cli(snapshot, k6)

        self.assertEqual(exit_code, 1)
        self.assertIn("completion markers: PASS", output)
        self.assertIn("wall-clock accounting: PASS", output)
        self.assertIn("k6 checks: FAIL", output)

    def test_validate_mode_rejects_reversed_timestamps_without_traceback(self) -> None:
        snapshot, k6 = self.valid_validation_inputs()
        snapshot["queries"]["scheduler_visit_duration_sum"]["points"].reverse()
        exit_code, output = self.run_validation_cli(snapshot, k6)

        self.assertEqual(exit_code, 1)
        self.assertIn("schema: FAIL", output)


if __name__ == "__main__":
    unittest.main()
