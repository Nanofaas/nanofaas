#!/usr/bin/env python3
import importlib.util
import json
import sys
import tempfile
import types
import unittest
from pathlib import Path


HERE = Path(__file__).parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


comparison_report = types.ModuleType("sonata_tasks.loadtest.comparison_report")
comparison_report._fmt = comparison_report._spread = lambda *args, **kwargs: None
comparison_report.aggregate_table = comparison_report.read_cell = lambda *args, **kwargs: None
sys.modules["sonata_tasks"] = types.ModuleType("sonata_tasks")
sys.modules["sonata_tasks.loadtest"] = types.ModuleType("sonata_tasks.loadtest")
sys.modules["sonata_tasks.loadtest.comparison_report"] = comparison_report

tables = load("baseline_tables", HERE / "tables.py")
sintesi = load("baseline_sintesi", HERE / "sintesi.py")


class ReportingTest(unittest.TestCase):
    def test_queue_depth_combines_aligned_function_series(self):
        queries = {
            "function_queue_depth_sync": {
                "points": [
                    {"timestamp": "2026-08-25T00:00:00+00:00", "value": 5},
                    {"timestamp": "2026-08-25T00:00:01+00:00", "value": 4},
                ]
            },
            "function_queue_depth_sync@word-stats-javascript": {
                "points": [
                    {"timestamp": "2026-08-25T00:00:00+00:00", "value": 3},
                    {"timestamp": "2026-08-25T00:00:01+00:00", "value": 6},
                ]
            },
        }

        self.assertEqual(tables._platform_series(queries, "function_queue_depth_sync"), [8, 10])

    def test_run_date_comes_from_the_archived_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory)
            (archive / "comparison-manifest.json").write_text(
                json.dumps({"started_at": "2026-08-24T16:23:12.795487+00:00"})
            )

            self.assertEqual(sintesi._started_at(archive), "2026-08-24 16:23")


if __name__ == "__main__":
    unittest.main()
