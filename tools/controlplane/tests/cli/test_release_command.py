from __future__ import annotations

from pathlib import Path
from types import SimpleNamespace

from typer.testing import CliRunner

from controlplane_tool.app.main import app
import controlplane_tool.cli.release as release_cli
from controlplane_tool.release import run as release_run
from controlplane_tool.release.metrics import RegressionDecision
from controlplane_tool.release.run import GitState
from controlplane_tool.release.versioning import read_project_version


REPO_ROOT = Path(__file__).resolve().parents[4]
CURRENT_VERSION = read_project_version(REPO_ROOT)


def _file(path: Path, value: str = "fixture") -> Path:
    path.write_text(value, encoding="utf-8")
    path.chmod(0o600)
    return path


def _common_release_args(tmp_path: Path) -> list[str]:
    return [
        CURRENT_VERSION,
        "--environment",
        str(_file(tmp_path / "environment.yaml")),
        "--release-config",
        str(_file(tmp_path / "release.yaml")),
        "--run-dir",
        str(tmp_path / "run"),
    ]


def _credential_args(tmp_path: Path) -> list[str]:
    return [
        "--ghcr-token-file",
        str(_file(tmp_path / "ghcr-token")),
        "--cosign-key-file",
        str(_file(tmp_path / "cosign.key")),
        "--cosign-password-file",
        str(_file(tmp_path / "cosign.password")),
    ]


def test_release_command_surface_is_prepare_plan_and_run() -> None:
    result = CliRunner().invoke(app, ["release", "--help"])

    assert result.exit_code == 0, result.output
    release_group = next(group for group in app.registered_groups if group.name == "release")
    assert release_group.typer_instance is not None
    commands = {command.name for command in release_group.typer_instance.registered_commands}
    assert commands == {"prepare", "plan", "run"}


def test_release_prepare_requires_clean_source_before_version_changes(
    monkeypatch,
) -> None:
    called = False
    monkeypatch.setattr(
        release_cli,
        "git_state",
        lambda _root: GitState(commit="a" * 40, clean=False),
    )

    def prepare(_root, _version):
        nonlocal called
        called = True

    monkeypatch.setattr(release_cli, "prepare_version", prepare)

    result = CliRunner().invoke(app, ["release", "prepare", "0.18.0"])

    assert result.exit_code != 0
    assert "clean Git tree" in result.output
    assert called is False


def test_release_prepare_delegates_to_curated_version_preparation(monkeypatch) -> None:
    seen: list[tuple[Path, str]] = []
    monkeypatch.setattr(
        release_cli,
        "git_state",
        lambda _root: GitState(commit="a" * 40, clean=True),
    )
    monkeypatch.setattr(
        release_cli,
        "prepare_version",
        lambda root, version: seen.append((root, version)) or (root / "build.gradle",),
    )

    result = CliRunner().invoke(app, ["release", "prepare", "v0.18.0"])

    assert result.exit_code == 0, result.output
    assert seen == [(release_cli.default_tool_paths().workspace_root, "v0.18.0")]
    assert "build.gradle" in result.output
    assert "commit" in result.output.lower()


def test_release_plan_only_renders_the_guarded_offline_plan(
    tmp_path: Path,
    monkeypatch,
) -> None:
    seen: list[dict[str, object]] = []
    monkeypatch.setattr(
        release_cli,
        "build_amd64_release_plan",
        lambda **kwargs: (
            seen.append(kwargs) or SimpleNamespace(render=lambda: "offline amd64 release plan\n")
        ),
    )
    monkeypatch.setattr(
        release_cli,
        "run_amd64_release",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(
            AssertionError("plan must not start release execution")
        ),
    )

    result = CliRunner().invoke(app, ["release", "plan", *_common_release_args(tmp_path)])

    assert result.exit_code == 0, result.output
    assert result.output == "offline amd64 release plan\n"
    assert seen[0]["version"] == CURRENT_VERSION
    assert seen[0]["run_dir"] == tmp_path / "run"
    assert seen[0]["credentials"] is None

    help_result = CliRunner().invoke(app, ["release", "plan", "--help"])
    assert "ghcr-token-file" not in help_result.output


def test_release_plan_uses_the_real_planner_without_cloud_or_secrets(
    tmp_path: Path,
    monkeypatch,
) -> None:
    monkeypatch.setattr(
        release_run,
        "git_state",
        lambda _root: GitState(commit="a" * 40, clean=True),
    )

    def reject_cloud(*_args, **_kwargs):
        raise AssertionError("offline plan must not touch cloud execution")

    monkeypatch.setattr(release_run, "vm_provider_for_environment", reject_cloud)
    monkeypatch.setattr(release_run, "provision_environment", reject_cloud)
    monkeypatch.setattr(release_run, "build_loadtest_plan", reject_cloud)

    result = CliRunner().invoke(
        app,
        [
            "release",
            "plan",
            CURRENT_VERSION,
            "--environment",
            str(REPO_ROOT / "tools/controlplane/environments/azure-release.yaml.example"),
            "--release-config",
            str(REPO_ROOT / "tools/controlplane/release.yaml"),
            "--run-dir",
            str(tmp_path / "run"),
        ],
    )

    assert result.exit_code == 0, result.output
    assert "provider azure: stack + loadgen" in result.output
    assert "credentials: deferred for offline plan" in result.output


def test_release_run_forwards_explicit_resume_and_keep(
    tmp_path: Path,
    monkeypatch,
) -> None:
    plan = object()
    calls: list[tuple[object, bool, bool]] = []
    monkeypatch.setattr(release_cli, "build_amd64_release_plan", lambda **_kwargs: plan)
    monkeypatch.setattr(
        release_cli,
        "run_amd64_release",
        lambda value, *, resume, keep: (
            calls.append((value, resume, keep)) or RegressionDecision(True, True, ())
        ),
    )

    result = CliRunner().invoke(
        app,
        [
            "release",
            "run",
            *_common_release_args(tmp_path),
            *_credential_args(tmp_path),
            "--resume",
            "--keep",
        ],
    )

    assert result.exit_code == 0, result.output
    assert calls == [(plan, True, True)]
    assert "passed" in result.output.lower()


def test_fresh_release_run_requires_explicit_provision_acknowledgement(
    tmp_path: Path,
    monkeypatch,
) -> None:
    built = False

    def build(**_kwargs):
        nonlocal built
        built = True

    monkeypatch.setattr(release_cli, "build_amd64_release_plan", build)
    args = [
        "release",
        "run",
        *_common_release_args(tmp_path),
        *_credential_args(tmp_path),
    ]

    rejected = CliRunner().invoke(app, args)

    assert rejected.exit_code != 0
    assert "--provision" in rejected.output
    assert built is False


def test_fresh_release_run_accepts_explicit_provision(
    tmp_path: Path,
    monkeypatch,
) -> None:
    plan = object()
    calls: list[tuple[object, bool, bool]] = []
    monkeypatch.setattr(release_cli, "build_amd64_release_plan", lambda **_kwargs: plan)
    monkeypatch.setattr(
        release_cli,
        "run_amd64_release",
        lambda value, *, resume, keep: (
            calls.append((value, resume, keep)) or RegressionDecision(True, True, ())
        ),
    )

    result = CliRunner().invoke(
        app,
        [
            "release",
            "run",
            *_common_release_args(tmp_path),
            *_credential_args(tmp_path),
            "--provision",
        ],
    )

    assert result.exit_code == 0, result.output
    assert calls == [(plan, False, False)]
