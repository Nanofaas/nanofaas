from __future__ import annotations

from pathlib import Path

from controlplane_tool.loadtest.remote_k6 import RemoteK6RunConfig, build_k6_command


def test_default_two_vm_k6_script_reads_payload_in_init_context() -> None:
    script = Path("tools/controlplane/assets/k6/two-vm-function-invoke.js").read_text(
        encoding="utf-8"
    )

    assert "const PAYLOAD_BODY = PAYLOAD_PATH ? open(PAYLOAD_PATH) : '';" in script
    assert script.index("open(PAYLOAD_PATH)") < script.index("export default function")
    assert "return PAYLOAD_BODY;" in script


def test_default_script_gets_stage_profile_and_env() -> None:
    config = RemoteK6RunConfig(
        script_path=Path("/remote/default.js"),
        summary_path=Path("/remote/k6-summary.json"),
        control_plane_url="http://10.0.0.1:8080",
        function_name="word-stats-java",
        payload_path=None,
        stages=(("15s", 1), ("30s", 3)),
        custom_script=False,
    )

    command = build_k6_command(config)

    assert command[:4] == ("k6", "run", "--summary-export", "/remote/k6-summary.json")
    assert "--stage" in command
    assert "15s:1" in command
    assert "30s:3" in command
    assert "NANOFAAS_URL=http://10.0.0.1:8080" in command
    assert "NANOFAAS_FUNCTION=word-stats-java" in command
    assert command[-1] == "/remote/default.js"


def test_custom_script_omits_default_stages_without_cli_overrides() -> None:
    config = RemoteK6RunConfig(
        script_path=Path("/remote/custom.js"),
        summary_path=Path("/remote/k6-summary.json"),
        control_plane_url="http://10.0.0.1:8080",
        function_name="word-stats-java",
        payload_path=Path("/remote/payload.json"),
        stages=(("15s", 1),),
        custom_script=True,
    )

    command = build_k6_command(config)

    assert "--stage" not in command
    assert "--vus" not in command
    assert "--duration" not in command
    assert "NANOFAAS_PAYLOAD=/remote/payload.json" in command


def test_explicit_vus_and_duration_override_custom_script_options() -> None:
    config = RemoteK6RunConfig(
        script_path=Path("/remote/custom.js"),
        summary_path=Path("/remote/k6-summary.json"),
        control_plane_url="http://10.0.0.1:8080",
        function_name="word-stats-java",
        custom_script=True,
        vus=25,
        duration="2m",
    )

    command = build_k6_command(config)

    assert "--vus" in command
    assert "25" in command
    assert "--duration" in command
    assert "2m" in command


def test_default_script_omits_stages_when_explicit_vus_or_duration_are_set() -> None:
    config = RemoteK6RunConfig(
        script_path=Path("/remote/default.js"),
        summary_path=Path("/remote/k6-summary.json"),
        control_plane_url="http://10.0.0.1:8080",
        function_name="word-stats-java",
        stages=(("15s", 1),),
        custom_script=False,
        vus=10,
    )

    command = build_k6_command(config)

    assert "--stage" not in command
    assert "--vus" in command
    assert "10" in command
