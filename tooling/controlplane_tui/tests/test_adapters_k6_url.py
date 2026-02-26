from pathlib import Path

from controlplane_tool.adapters import AdapterResult, ShellCommandAdapter
from controlplane_tool.models import ControlPlaneConfig, MetricsConfig, Profile, TestsConfig


class RecordingAdapter(ShellCommandAdapter):
    def __init__(self, repo_root: Path) -> None:
        super().__init__(repo_root=repo_root)
        self.commands: list[list[str]] = []

    def _run(self, command: list[str], run_dir: Path, log_name: str) -> AdapterResult:  # noqa: ARG002
        self.commands.append(command)
        return AdapterResult(ok=True, detail="ok")


def _prepare_fake_repo(root: Path) -> None:
    metrics_java = (
        root
        / "control-plane"
        / "src"
        / "main"
        / "java"
        / "it"
        / "unimib"
        / "datai"
        / "nanofaas"
        / "controlplane"
        / "service"
        / "Metrics.java"
    )
    metrics_java.parent.mkdir(parents=True, exist_ok=True)
    metrics_java.write_text(
        'class Metrics { void x(){ "function_dispatch_total".toString(); "function_latency_ms".toString(); "function_e2e_latency_ms".toString(); } }',
        encoding="utf-8",
    )
    k6_script = root / "experiments" / "k6" / "word-stats-java.js"
    k6_script.parent.mkdir(parents=True, exist_ok=True)
    k6_script.write_text("export default function(){}", encoding="utf-8")


def test_metrics_k6_uses_control_plane_base_url(tmp_path: Path) -> None:
    _prepare_fake_repo(tmp_path)
    run_dir = tmp_path / "run"
    run_dir.mkdir(parents=True)

    profile = Profile(
        name="qa",
        control_plane=ControlPlaneConfig(implementation="java", build_mode="jvm"),
        modules=[],
        tests=TestsConfig(enabled=True, api=False, e2e_mockk8s=False, metrics=True),
        metrics=MetricsConfig(
            required=[
                "function_dispatch_total",
                "function_latency_ms",
                "function_e2e_latency_ms",
            ]
        ),
    )

    adapter = RecordingAdapter(repo_root=tmp_path)
    adapter.run_metrics_tests(profile, run_dir)

    k6_command = next(command for command in adapter.commands if command and command[0] == "k6")
    assert "NANOFAAS_URL=http://localhost:8080" in k6_command
