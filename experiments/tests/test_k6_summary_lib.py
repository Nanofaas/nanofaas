from pathlib import Path
import sys


REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "experiments" / "lib"))

from k6_summary import (  # noqa: E402
    resolve_http_req_failed_count,
    resolve_http_req_failed_ratio,
)


def test_resolve_http_req_failed_prefers_value_ratio():
    metric = {"value": 0.25, "passes": 999, "fails": 1}
    assert resolve_http_req_failed_count(metric, 20) == 5
    assert resolve_http_req_failed_ratio(metric, 20) == 0.25


def test_resolve_http_req_failed_uses_passes_when_value_missing():
    metric = {"passes": 7, "fails": 13}
    assert resolve_http_req_failed_count(metric, 20) == 7
    assert resolve_http_req_failed_ratio(metric, 20) == 0.35


def test_resolve_http_req_failed_interprets_fails_as_success_samples():
    metric = {"fails": 17}
    assert resolve_http_req_failed_count(metric, 20) == 3
    assert resolve_http_req_failed_ratio(metric, 20) == 0.15


def test_resolve_http_req_failed_handles_invalid_payload_safely():
    metric = {"value": "not-a-number"}
    assert resolve_http_req_failed_count(metric, 10) == 0
    assert resolve_http_req_failed_ratio(metric, 10) == 0.0
