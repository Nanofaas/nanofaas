from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "experiments" / "e2e-memory-ab.sh"


def test_memory_ab_script_exposes_epoch_toggle_and_reports():
    script = SCRIPT.read_text(encoding="utf-8")
    assert "CONTROL_PLANE_EPOCH_MILLIS_ENABLED" in script
    assert "NANOFAAS_OPTIMIZATIONS_EPOCH_MILLIS_ENABLED" not in script
    assert "RUN_BASELINE" in script
    assert "RUN_EPOCH_ON" in script
    assert "jvm-samples.jsonl" in script
    assert "comparison.md" in script
    assert "comparison.json" in script
    assert "RESULTS_DIR_OVERRIDE" in script
    assert "K6_STAGE_SEQUENCE" in script


def test_memory_ab_script_uses_noninteractive_deploy_and_loadtest():
    script = SCRIPT.read_text(encoding="utf-8")
    assert "E2E_K3S_HELM_NONINTERACTIVE=true" in script
    assert "bash \"${PROJECT_ROOT}/scripts/e2e-k3s-helm.sh\"" in script
    assert "bash \"${PROJECT_ROOT}/experiments/e2e-loadtest.sh\"" in script


def test_memory_ab_aggregates_fail_rate_with_shared_k6_parser():
    script = SCRIPT.read_text(encoding="utf-8")
    assert "from k6_summary import resolve_http_req_failed_count, resolve_http_req_failed_ratio" in script
    assert "fail_ratio = resolve_http_req_failed_ratio(failed, reqs)" in script
