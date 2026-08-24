import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

MODULE = importlib.util.spec_from_file_location(
    "mixed_workload_tables", Path(__file__).with_name("tables.py")
)
assert MODULE and MODULE.loader
tables = importlib.util.module_from_spec(MODULE)
MODULE.loader.exec_module(tables)


class TablesTest(unittest.TestCase):
    def _cell(
        self, *, include_async_workload: bool = True, include_async_rate: bool = True
    ) -> dict[str, float | bool | None]:
        with tempfile.TemporaryDirectory() as tmp:
            run = Path(tmp) / "variant" / "run-1"
            metrics = run / "metrics"
            metrics.mkdir(parents=True)
            def points(start: int, end: int) -> list[dict[str, object]]:
                return [
                    {"timestamp": "2026-08-24T10:00:00+00:00", "value": start},
                    {"timestamp": "2026-08-24T10:00:10+00:00", "value": end},
                ]
            snapshot = {
                "queries": {
                    "function_dispatch_total": {"points": points(100, 130)},
                    "function_admitted_sync": {"points": points(10, 30)},
                    "function_refused_sync": {"points": points(5, 10)},
                    "function_admitted_async": {"points": points(20, 30)},
                    "function_refused_async": {"points": points(2, 7)},
                }
            }
            (metrics / "prometheus-snapshot.json").write_text(json.dumps(snapshot))
            k6 = {
                "metrics": {
                    "http_reqs": {"count": 120, "rate": 12.0},
                    "mixed_sync_duration": {"med": 1.0},
                    "mixed_sync_refused": {"passes": 10, "fails": 70},
                }
            }
            if include_async_workload:
                k6["metrics"]["mixed_async_ack_duration"] = {"med": 1.0}
            if include_async_workload and include_async_rate:
                k6["metrics"]["mixed_async_refused"] = {"passes": 10, "fails": 20}
            (run / "k6-summary.json").write_text(json.dumps(k6))

            return tables.cell(metrics / "prometheus-snapshot.json")

    def test_uses_k6_java_and_javascript_workload_acceptance(self) -> None:
        result = self._cell()

        self.assertEqual(9.0, result["workload_accettato_s"])
        self.assertEqual(12.0, result["richieste_http_offerte_s"])
        self.assertEqual(120, result["richieste_http_offerte"])
        self.assertNotIn("richieste_accettate_s", result)
        self.assertIn(
            ("workload accettato/s (Java+JS; sync completate + ACK async)", "workload_accettato_s"),
            tables.ROWS,
        )
        self.assertIn(
            ("HTTP totali offerti/s (incl. probe management)", "richieste_http_offerte_s"),
            tables.ROWS,
        )
        self.assertIn(
            ("HTTP totali offerti (incl. probe management)", "richieste_http_offerte"),
            tables.ROWS,
        )

    def test_missing_required_custom_rate_is_not_reported_as_zero(self) -> None:
        result = self._cell(include_async_rate=False)

        value = result["workload_accettato_s"]
        self.assertNotEqual(value, value)

    def test_sync_only_workload_does_not_require_an_async_rate(self) -> None:
        result = self._cell(include_async_workload=False)

        self.assertEqual(7.0, result["workload_accettato_s"])

    def test_missing_dropped_iterations_is_not_reported_as_observed_zero(self) -> None:
        result = self._cell()

        value = result["scartati_gen"]
        self.assertNotEqual(value, value)

    def test_java_only_prometheus_rows_are_labelled(self) -> None:
        java_only = {
            "quota_async_%",
            "rifiuti_%",
            "rifiuti_sync_%",
            "rifiuti_async_%",
            "servizio_ms",
            "dispatch_s",
            "coda",
            "coda_sync",
            "coda_async",
            "errori",
            "timeout",
            "ritentativi",
        }

        labels = {key: label for label, key in tables.ROWS}
        self.assertTrue(all(labels[key].endswith("(solo word-stats-java)") for key in java_only))


if __name__ == "__main__":
    unittest.main()
