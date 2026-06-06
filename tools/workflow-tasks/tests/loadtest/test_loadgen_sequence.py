from __future__ import annotations

from pathlib import Path
from types import SimpleNamespace

from workflow_tasks.loadtest.loadgen_sequence import make_loadtest_k6_config


def _remote_paths(payload: str | None = None) -> SimpleNamespace:
    return SimpleNamespace(
        script_path="/home/ubuntu/run/script.js",
        summary_path="/home/ubuntu/run/k6-summary.json",
        payload_path=payload,
    )


def test_make_k6_config_maps_fields_and_env() -> None:
    cfg = make_loadtest_k6_config(
        remote_paths=_remote_paths(),
        control_plane_url="http://10.0.0.5:8080",
        target_function="echo",
        stages=[("30s", 5), ("1m", 10)],
        vus=None,
        duration=None,
    )
    assert cfg.script_path == Path("/home/ubuntu/run/script.js")
    assert cfg.target_url == "http://10.0.0.5:8080"
    assert cfg.summary_output_path == Path("/home/ubuntu/run/k6-summary.json")
    assert [(s.duration, s.target) for s in cfg.stages] == [("30s", 5), ("1m", 10)]
    assert cfg.env["NANOFAAS_URL"] == "http://10.0.0.5:8080"
    assert cfg.env["NANOFAAS_FUNCTION"] == "echo"
    assert "NANOFAAS_PAYLOAD" not in cfg.env
    assert cfg.payload_path is None


def test_make_k6_config_includes_payload_when_present() -> None:
    cfg = make_loadtest_k6_config(
        remote_paths=_remote_paths(payload="/home/ubuntu/run/payload.json"),
        control_plane_url="http://h:8080",
        target_function="echo",
        stages=[],
        vus=4,
        duration="2m",
    )
    assert cfg.env["NANOFAAS_PAYLOAD"] == "/home/ubuntu/run/payload.json"
    assert cfg.payload_path == Path("/home/ubuntu/run/payload.json")
    assert cfg.vus == 4
    assert cfg.duration == "2m"
