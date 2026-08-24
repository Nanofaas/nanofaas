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
    def test_separates_admitted_rate_from_offered_http_traffic(self) -> None:
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
                    "http_reqs": {"count": 95, "rate": 9.5},
                    "http_req_duration": {},
                }
            }
            (run / "k6-summary.json").write_text(json.dumps(k6))

            result = tables.cell(metrics / "prometheus-snapshot.json")

        self.assertEqual(3.0, result["richieste_accettate_s"])
        self.assertEqual(9.5, result["richieste_http_offerte_s"])
        self.assertEqual(95, result["richieste_http_offerte"])
        self.assertIn(("richieste accettate/s", "richieste_accettate_s"), tables.ROWS)
        self.assertIn(("richieste HTTP offerte/s", "richieste_http_offerte_s"), tables.ROWS)
        self.assertIn(("richieste HTTP offerte", "richieste_http_offerte"), tables.ROWS)


if __name__ == "__main__":
    unittest.main()
