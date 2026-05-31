from __future__ import annotations

from pathlib import Path

from workflow_tasks.loadtest.remote_k6 import RemoteK6RunConfig, build_k6_command


def _cfg(**kw) -> RemoteK6RunConfig:
    base = dict(
        script_path=Path("/k6/script.js"),
        summary_path=Path("/k6/summary.json"),
        control_plane_url="http://cp:30080",
        function_name="echo",
    )
    base.update(kw)
    return RemoteK6RunConfig(**base)


def test_build_k6_command_uses_stages_by_default() -> None:
    cmd = build_k6_command(_cfg(stages=(("30s", 10), ("1m", 50))))
    assert cmd[0:2] == ("k6", "run")
    assert "--summary-export" in cmd
    assert "--stage" in cmd
    joined = " ".join(cmd)
    assert "30s:10" in joined
    assert "1m:50" in joined
    assert "NANOFAAS_URL=http://cp:30080" in cmd
    assert "NANOFAAS_FUNCTION=echo" in cmd
    assert cmd[-1] == "/k6/script.js"


def test_build_k6_command_vus_duration_override_stages() -> None:
    cmd = build_k6_command(_cfg(vus=5, duration="2m", stages=(("30s", 10),)))
    assert "--vus" in cmd and "5" in cmd
    assert "--duration" in cmd and "2m" in cmd
    assert "--stage" not in cmd


def test_build_k6_command_includes_payload_when_present() -> None:
    cmd = build_k6_command(_cfg(payload_path=Path("/k6/payload.json")))
    assert "NANOFAAS_PAYLOAD=/k6/payload.json" in cmd


def test_build_k6_command_custom_script_skips_stages() -> None:
    cmd = build_k6_command(_cfg(custom_script=True, stages=(("30s", 10),)))
    assert "--stage" not in cmd
