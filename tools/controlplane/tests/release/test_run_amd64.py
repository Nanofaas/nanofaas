from __future__ import annotations

from pathlib import Path
from contextlib import contextmanager
from dataclasses import asdict, replace
import hashlib
import json
import shlex
import subprocess
import tarfile
from types import SimpleNamespace

import pytest
import yaml

from controlplane_tool.release import arm
from controlplane_tool.release import run as release_run
from controlplane_tool.release.metrics import build_release_record
from controlplane_tool.release.versioning import read_project_version


REPO_ROOT = Path(__file__).resolve().parents[4]
CURRENT_VERSION = read_project_version(REPO_ROOT)
CURRENT_TAG = f"v{CURRENT_VERSION}"
BUILDER_NAME = f"nanofaas-release-{CURRENT_TAG.replace('.', '-')}"
_VERSION_PARTS = tuple(int(part) for part in CURRENT_VERSION.split("."))
MISMATCH_VERSION = ".".join(str(part) for part in (*_VERSION_PARTS[:2], _VERSION_PARTS[2] + 1))


def _secret(path: Path, value: str) -> Path:
    path.write_text(value, encoding="utf-8")
    path.chmod(0o600)
    return path


def _plan(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr(
        release_run,
        "git_state",
        lambda _root: release_run.GitState(commit="a" * 40, clean=True),
    )
    credentials = release_run.CredentialFiles(
        ghcr_token=_secret(tmp_path / "ghcr-token", "fixture-token"),
        cosign_key=_secret(tmp_path / "cosign.key", "fixture-key"),
        cosign_password=_secret(tmp_path / "cosign.password", "fixture-password"),
    )
    return release_run.build_amd64_release_plan(
        repo_root=REPO_ROOT,
        version=CURRENT_TAG,
        environment_path=REPO_ROOT / "tools/controlplane/environments/azure-release.yaml.example",
        release_config_path=REPO_ROOT / "tools/controlplane/release.yaml",
        run_dir=tmp_path / "run",
        credentials=credentials,
        # finalization must never write into the real repository docs in tests
        performance_root=tmp_path / "performance-docs",
    )


def test_plan_is_amd64_only_and_uses_a_named_bounded_buildx_builder(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    plan = _plan(tmp_path, monkeypatch)

    assert plan.identity.source_commit == "a" * 40
    assert plan.version == CURRENT_VERSION
    assert len(plan.image_plan.cells) == 26
    assert {cell.architecture for cell in plan.image_plan.cells} == {"amd64"}
    assert plan.phase_names == release_run.RELEASE_PHASES
    assert plan.builder.name == BUILDER_NAME
    assert plan.builder.max_parallelism == 4
    assert "max-parallelism = 4" in plan.buildkit_config.read_text(encoding="utf-8")
    rendered = plan.render()
    assert "docker-container" in rendered
    assert "26 AMD64 + 26 ARM64" in rendered
    assert "digest-pinned QEMU after regression-gate" in rendered
    assert "ghcr.io" not in rendered
    assert "fixture-token" not in repr(plan)
    assert "fixture-key" not in repr(plan)
    assert "fixture-password" not in repr(plan)


def test_plan_rejects_dirty_source_before_creating_release_files(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(
        release_run,
        "git_state",
        lambda _root: release_run.GitState(commit="a" * 40, clean=False),
    )
    credentials = release_run.CredentialFiles(
        ghcr_token=_secret(tmp_path / "ghcr-token", "token"),
        cosign_key=_secret(tmp_path / "cosign.key", "key"),
        cosign_password=_secret(tmp_path / "cosign.password", "password"),
    )

    with pytest.raises(ValueError, match="clean Git tree"):
        release_run.build_amd64_release_plan(
            repo_root=REPO_ROOT,
            version=CURRENT_VERSION,
            environment_path=REPO_ROOT
            / "tools/controlplane/environments/azure-release.yaml.example",
            release_config_path=REPO_ROOT / "tools/controlplane/release.yaml",
            run_dir=tmp_path / "run",
            credentials=credentials,
        )

    assert not (tmp_path / "run").exists()


def test_plan_requires_all_private_credential_files(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(
        release_run,
        "git_state",
        lambda _root: release_run.GitState(commit="a" * 40, clean=True),
    )
    token = _secret(tmp_path / "ghcr-token", "token")
    key = _secret(tmp_path / "cosign.key", "key")

    with pytest.raises(ValueError, match="cosign password"):
        release_run.CredentialFiles(
            ghcr_token=token,
            cosign_key=key,
            cosign_password=None,
        ).validate()


def test_credentials_reject_path_traversal_into_repository(tmp_path: Path) -> None:
    repo = tmp_path / "repo"
    ignored = repo / "build" / "release-secrets"
    ignored.mkdir(parents=True)
    credentials = release_run.CredentialFiles(
        ghcr_token=_secret(ignored / "ghcr-token", "token"),
        cosign_key=_secret(tmp_path / "cosign.key", "key"),
        cosign_password=_secret(tmp_path / "cosign.password", "password"),
    )
    credentials = replace(
        credentials,
        ghcr_token=repo / "build" / ".." / "build" / "release-secrets" / "ghcr-token",
    )

    with pytest.raises(ValueError, match="outside the repository"):
        credentials.validate(repo_root=repo)


def test_credentials_reject_parent_symlink_into_repository(tmp_path: Path) -> None:
    repo = tmp_path / "repo"
    ignored = repo / "build" / "release-secrets"
    ignored.mkdir(parents=True)
    _secret(ignored / "ghcr-token", "token")
    alias = tmp_path / "outside-looking-alias"
    alias.symlink_to(ignored, target_is_directory=True)
    credentials = release_run.CredentialFiles(
        ghcr_token=alias / "ghcr-token",
        cosign_key=_secret(tmp_path / "cosign.key", "key"),
        cosign_password=_secret(tmp_path / "cosign.password", "password"),
    )

    with pytest.raises(ValueError, match="outside the repository"):
        credentials.validate(repo_root=repo)


def test_plan_rejects_requested_version_that_is_not_prepared(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(
        release_run,
        "git_state",
        lambda _root: release_run.GitState(commit="a" * 40, clean=True),
    )
    credentials = release_run.CredentialFiles(
        ghcr_token=_secret(tmp_path / "ghcr-token", "token"),
        cosign_key=_secret(tmp_path / "cosign.key", "key"),
        cosign_password=_secret(tmp_path / "cosign.password", "password"),
    )

    with pytest.raises(ValueError, match="prepared project version"):
        release_run.build_amd64_release_plan(
            repo_root=REPO_ROOT,
            version=MISMATCH_VERSION,
            environment_path=REPO_ROOT
            / "tools/controlplane/environments/azure-release.yaml.example",
            release_config_path=REPO_ROOT / "tools/controlplane/release.yaml",
            run_dir=tmp_path / "run",
            credentials=credentials,
        )


def test_plan_resolves_relative_release_inputs_against_the_working_directory(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.chdir(REPO_ROOT)
    monkeypatch.setattr(
        release_run,
        "git_state",
        lambda _root: release_run.GitState(commit="a" * 40, clean=True),
    )
    credentials = release_run.CredentialFiles(
        ghcr_token=_secret(tmp_path / "ghcr-token", "token"),
        cosign_key=_secret(tmp_path / "cosign.key", "key"),
        cosign_password=_secret(tmp_path / "cosign.password", "password"),
    )

    plan = release_run.build_amd64_release_plan(
        repo_root=REPO_ROOT,
        version=CURRENT_VERSION,
        environment_path=Path("tools/controlplane/environments/azure-release.yaml.example"),
        release_config_path=Path("tools/controlplane/release.yaml"),
        run_dir=tmp_path / "run",
        credentials=credentials,
        # finalization must never write into the real repository docs in tests
        performance_root=tmp_path / "performance-docs",
    )

    assert plan.settings.scenario == (REPO_ROOT / "tools/controlplane/scenarios-v2/loadtest.yaml")


def _release_config(tmp_path: Path) -> tuple[Path, dict[str, object]]:
    scenario = tmp_path / "scenario.yaml"
    scenario.write_text("workflow: loadtest\nfunctions: [word-stats-java]\n")
    config: dict[str, object] = {
        "schemaVersion": 1,
        "build": {"maxParallelism": 2},
        "benchmark": {
            "scenario": "scenario.yaml",
            "runs": 3,
            "profile": "test-profile",
            "regression": {
                "throughputMaxLossPercent": 10,
                "p95MaxIncreasePercent": 15,
                "errorRateMax": 0.3,
            },
        },
    }
    return tmp_path / "release.yaml", config


@pytest.mark.parametrize("runs", (3.0, True, "3"))
def test_release_settings_require_exact_integer_run_count(runs: object, tmp_path: Path) -> None:
    path, config = _release_config(tmp_path)
    config["benchmark"]["runs"] = runs  # type: ignore[index]
    path.write_text(yaml.safe_dump(config), encoding="utf-8")

    with pytest.raises(ValueError, match="runs.*integer"):
        release_run._release_settings(tmp_path, path)


@pytest.mark.parametrize(
    ("field", "value", "error"),
    (
        ("throughputMaxLossPercent", float("nan"), "finite nonnegative"),
        ("p95MaxIncreasePercent", float("inf"), "finite nonnegative"),
        ("throughputMaxLossPercent", -1, "finite nonnegative"),
        ("errorRateMax", float("nan"), "between 0 and 1"),
        ("errorRateMax", -0.1, "between 0 and 1"),
        ("errorRateMax", 1.1, "between 0 and 1"),
    ),
)
def test_release_settings_reject_nonfinite_or_out_of_range_thresholds(
    field: str, value: object, error: str, tmp_path: Path
) -> None:
    path, config = _release_config(tmp_path)
    config["benchmark"]["regression"][field] = value  # type: ignore[index]
    path.write_text(yaml.safe_dump(config), encoding="utf-8")

    with pytest.raises(ValueError, match=error):
        release_run._release_settings(tmp_path, path)


def test_source_tests_reuse_gradle_and_uv_and_pin_container_toolchains() -> None:
    commands = release_run.source_test_commands(Path("/srv/nanofaas-source"))

    assert commands[0].argv == ("./gradlew", "test")
    python_commands = [command for command in commands if command.argv[:2] == ("uv", "run")]
    assert {command.task_id for command in python_commands} == {
        "release.source.controlplane",
        "release.source.workflow-tasks",
        "release.source.python-sdk",
    }
    by_id = {command.task_id: command.argv for command in python_commands}
    assert by_id["release.source.controlplane"] == (
        "uv",
        "run",
        "--project",
        ".",
        "--locked",
        "pytest",
        "-q",
        "tests",
    )
    assert by_id["release.source.workflow-tasks"] == (
        "uv",
        "run",
        "--project",
        ".",
        "--locked",
        "pytest",
        "-q",
        "tests",
    )
    assert by_id["release.source.python-sdk"][-4:] == (
        "sdks/python/tests",
        "functions/python/word-stats/tests",
        "functions/python/json-transform/tests",
        "functions/python/roman-numeral/tests",
    )
    by_task = {command.task_id: command for command in commands}
    assert by_task["release.source.controlplane"].remote_dir == (
        "/srv/nanofaas-source/tools/controlplane"
    )
    assert by_task["release.source.workflow-tasks"].remote_dir == (
        "/srv/nanofaas-source/tools/workflow-tasks"
    )
    container_commands = [command for command in commands if command.argv[:2] == ("docker", "run")]
    assert {command.task_id for command in container_commands} == {
        "release.source.go",
        "release.source.node",
        "release.source.rust",
        "release.source.bash",
    }
    for command in container_commands:
        assert command.argv[:2] == ("docker", "run")
        mount = command.argv[command.argv.index("-v") + 1]
        assert mount == "/srv/nanofaas-source:/source:ro"
        assert command.argv[command.argv.index("-w") + 1] == "/workspace"
        assert command.argv[-1].startswith("set -eu; cp -a /source/. /workspace && ")
        image = next(value for value in command.argv if "@sha256:" in value)
        assert len(image.rsplit("@sha256:", 1)[1]) == 64
        assert command.remote_dir == "/srv/nanofaas-source"


def test_amd64_build_commands_bind_every_bake_to_the_named_builder(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    plan = _plan(tmp_path, monkeypatch)

    commands = release_run.amd64_build_commands(
        plan,
        remote_bake_file="/srv/release/docker-bake.json",
        remote_buildkit_config="/srv/release/buildkitd.toml",
        remote_source_dir="/srv/source",
    )

    create = next(command for command in commands if command.task_id == "release.buildx.create")
    assert create.argv == (
        "docker",
        "buildx",
        "create",
        "--name",
        BUILDER_NAME,
        "--driver",
        "docker-container",
        "--buildkitd-config",
        "/srv/release/buildkitd.toml",
        "--use",
    )
    bake = next(command for command in commands if command.task_id == "release.images.bake.amd64")
    assert bake.argv == (
        "docker",
        "buildx",
        "bake",
        "--builder",
        BUILDER_NAME,
        "--file",
        "/srv/release/docker-bake.json",
        "--load",
        "docker-amd64",
    )
    assert sum(command.task_id.startswith("release.images.native.") for command in commands) == 5
    assert all("arm64" not in " ".join(command.argv).lower() for command in commands)
    assert all("ghcr.io" not in " ".join(command.argv) for command in commands)


def test_source_archive_contains_only_the_exact_guarded_commit(tmp_path: Path) -> None:
    repo = tmp_path / "repo"
    repo.mkdir()
    subprocess.run(("git", "init", "-q"), cwd=repo, check=True)
    subprocess.run(("git", "config", "user.email", "release@example.test"), cwd=repo, check=True)
    subprocess.run(("git", "config", "user.name", "Release Test"), cwd=repo, check=True)
    (repo / "tracked.txt").write_text("tracked", encoding="utf-8")
    subprocess.run(("git", "add", "tracked.txt"), cwd=repo, check=True)
    subprocess.run(("git", "commit", "-qm", "source"), cwd=repo, check=True)
    commit = subprocess.run(
        ("git", "rev-parse", "HEAD"),
        cwd=repo,
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    archive = tmp_path / "source.tar"

    evidence = release_run.create_source_archive(repo, commit, archive)

    assert evidence.reference == str(archive)
    assert evidence.digest == release_run.digest_path(archive)
    with tarfile.open(archive) as source:
        assert source.getnames() == ["tracked.txt"]


def test_source_archive_rechecks_clean_commit_and_never_overwrites_on_guard_failure(
    tmp_path: Path,
) -> None:
    repo = tmp_path / "repo"
    repo.mkdir()
    subprocess.run(("git", "init", "-q"), cwd=repo, check=True)
    subprocess.run(("git", "config", "user.email", "release@example.test"), cwd=repo, check=True)
    subprocess.run(("git", "config", "user.name", "Release Test"), cwd=repo, check=True)
    (repo / "tracked.txt").write_text("tracked", encoding="utf-8")
    subprocess.run(("git", "add", "tracked.txt"), cwd=repo, check=True)
    subprocess.run(("git", "commit", "-qm", "source"), cwd=repo, check=True)
    archive = tmp_path / "source.tar"
    archive.write_bytes(b"owned")
    (repo / "untracked-secret").write_text("must-not-enter-archive", encoding="utf-8")

    with pytest.raises(ValueError, match="clean Git tree"):
        release_run.create_source_archive(repo, "a" * 40, archive)

    assert archive.read_bytes() == b"owned"


class _TransferResult:
    def __init__(
        self,
        *,
        stdout: str = "",
        stderr: str = "",
        return_code: int = 0,
    ) -> None:
        self.stdout = stdout
        self.stderr = stderr
        self.return_code = return_code


class _ArchiveProvider:
    def __init__(self, digest: str) -> None:
        self.digest = digest
        self.actions: list[tuple[object, ...]] = []

    def transfer_to(self, request: object, *, source: Path, destination: str) -> _TransferResult:
        self.actions.append(("transfer", request, source, destination))
        return _TransferResult()

    def exec_argv(
        self,
        request: object,
        argv: tuple[str, ...],
        *,
        env: dict[str, str] | None,
        cwd: str | None,
        dry_run: bool,
    ) -> _TransferResult:
        self.actions.append(("exec", request, argv, env, cwd, dry_run))
        if argv[0] == "sha256sum":
            return _TransferResult(stdout=f"{self.digest.removeprefix('sha256:')}  {argv[1]}\n")
        return _TransferResult()


class _FlakyProvider:
    """Records exec attempts; each entry is a (return_code|exception) script."""

    def __init__(self, outcomes: list[object]) -> None:
        self._outcomes = outcomes
        self.calls = 0

    def exec_argv(self, request, argv, *, env, cwd, dry_run):
        outcome = self._outcomes[self.calls]
        self.calls += 1
        if isinstance(outcome, Exception):
            raise outcome
        return SimpleNamespace(return_code=outcome, stdout="", stderr="")


def test_provider_exec_retries_on_dropped_connection(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(release_run.time, "sleep", lambda _s: None)
    # dropped mid-command (-1), then a connect-time exception, then success
    provider = _FlakyProvider([-1, ConnectionError("reset"), 0])

    release_run._provider_exec(provider, object(), ("docker", "push", "img"))

    assert provider.calls == 3


def test_provider_exec_does_not_retry_a_real_nonzero_exit(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(release_run.time, "sleep", lambda _s: None)
    # a genuine command failure (exit 1) must surface immediately, no retry
    provider = _FlakyProvider([1, 0])

    with pytest.raises(RuntimeError):
        release_run._provider_exec(provider, object(), ("false",))

    assert provider.calls == 1


def test_provider_exec_gives_up_after_exhausting_retries(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(release_run.time, "sleep", lambda _s: None)
    provider = _FlakyProvider([-1, -1, -1, -1])

    with pytest.raises(RuntimeError):
        release_run._provider_exec(provider, object(), ("docker", "push", "img"))

    assert provider.calls == 4


class _FlakyTransferProvider:
    def __init__(self, outcomes: list[int]) -> None:
        self._outcomes = outcomes
        self.calls = 0

    def transfer_to(self, request, *, source, destination):
        outcome = self._outcomes[self.calls]
        self.calls += 1
        return SimpleNamespace(return_code=outcome, stdout="", stderr="")


def test_provider_transfer_to_retries_on_dropped_connection(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.setattr(release_run.time, "sleep", lambda _s: None)
    provider = _FlakyTransferProvider([-1, 0])
    source = tmp_path / "source.tar"
    source.write_bytes(b"x")

    release_run._provider_transfer_to(
        provider, object(), source=source, destination="/srv/source.tar", action="upload"
    )

    assert provider.calls == 2


def test_source_transfer_verifies_checksum_before_extracting(tmp_path: Path) -> None:
    archive = tmp_path / "source.tar"
    archive.write_bytes(b"source")
    digest = release_run.digest_path(archive)
    provider = _ArchiveProvider(digest)
    request = object()

    release_run.stage_source_archive(
        provider,
        request,
        archive=archive,
        remote_archive="/srv/release/source.tar",
        remote_source_dir="/srv/release/source",
    )

    kinds = [action[0] for action in provider.actions]
    assert kinds == ["exec", "exec", "transfer", "exec", "exec"]
    assert provider.actions[-2][2] == ("sha256sum", "/srv/release/source.tar")
    assert provider.actions[-1][2] == (
        "tar",
        "-xf",
        "/srv/release/source.tar",
        "-C",
        "/srv/release/source",
    )


def test_source_transfer_rejects_checksum_mismatch_before_extracting(tmp_path: Path) -> None:
    archive = tmp_path / "source.tar"
    archive.write_bytes(b"source")
    provider = _ArchiveProvider("sha256:" + "f" * 64)

    with pytest.raises(RuntimeError, match="source archive checksum mismatch"):
        release_run.stage_source_archive(
            provider,
            object(),
            archive=archive,
            remote_archive="/srv/release/source.tar",
            remote_source_dir="/srv/release/source",
        )

    assert not any(
        action[0] == "exec" and isinstance(action[2], tuple) and action[2][0] == "tar"
        for action in provider.actions
    )


def _summary(value: float) -> dict[str, object]:
    return {
        "k6": {
            "http_reqs": {"values": {"rate": value}},
            "http_req_failed": {"values": {"rate": 0.0}},
            "http_req_duration": {
                "values": {"p(50)": value + 1, "p(95)": value + 2, "p(99)": value + 3}
            },
        },
        "prometheus": {
            "function_queue_wait_count": {"delta": 10},
            "function_queue_wait_sum": {"delta": value * 10},
            "function_cold_start_total": {"delta": value + 4},
            "process_cpu_usage": {"max": value / 100},
            "jvm_heap_used_bytes": {"max": value * 1024},
        },
        "autoscaling": {"max_replicas_observed": 5, "final_desired_replicas": 0},
    }


def _registry_digest(reference: str) -> str:
    return "sha256:" + hashlib.sha256(f"registry:{reference}".encode()).hexdigest()


def _unwrap_bounded(argv: tuple[str, ...]) -> tuple[str, ...]:
    # _provider_exec(bounded=True) wraps bulk commands in a bounded-output
    # shell; recover the original argv so dispatch and event strings stay
    # stable.
    if len(argv) == 3 and argv[:2] == ("sh", "-c") and "/tmp/release-cmd.log" in argv[2]:
        inner = argv[2].split("{ ", 1)[1].rsplit(" ; }", 1)[0]
        return tuple(shlex.split(inner))
    return argv


class _ReleaseProvider(_ArchiveProvider):
    def __init__(self, events: list[str]) -> None:
        super().__init__("sha256:" + "0" * 64)
        self.events = events
        self.local_digests: dict[str, str] = {}
        self.remote_source_mutated = False
        self.remote_digests: dict[str, str] = {}
        self.registry_digests: dict[str, str] = {}
        self.fact_overrides: dict[str, dict[str, object]] = {}
        self.restrictions: list[tuple[object, tuple[int, ...], tuple[str, ...]]] = []

    def teardown(self, request: object) -> _TransferResult:
        self.events.append(f"teardown:{getattr(request, 'name', None)}")
        return _TransferResult()

    def release_vm_facts(self, request: object) -> SimpleNamespace:
        name = str(getattr(request, "name", ""))
        loadgen = name.endswith("-loadgen")
        arm_builder = name.endswith("-arm")
        if loadgen:
            size, disk = "Standard_D2s_v5", 30
        elif arm_builder:
            size, disk = "Standard_D8ps_v5", 64
        else:
            size, disk = "Standard_D8s_v5", 128
        values: dict[str, object] = {
            "location": "westeurope",
            "vm_size": size,
            "disk_size_gb": disk,
            "image_urn": (
                "Canonical:ubuntu-24_04-lts:server-arm64:24.04.202607140"
                if arm_builder
                else "Canonical:ubuntu-24_04-lts:server:24.04.202607140"
            ),
        }
        values.update(self.fact_overrides.get(name, {}))
        self.events.append(f"facts:{name}")
        return SimpleNamespace(**values)

    def restrict_inbound_sources(
        self,
        request: object,
        *,
        ports: tuple[int, ...],
        source_cidrs: tuple[str, ...],
        priority_base: int = 1010,
    ) -> None:
        self.events.append(f"restrict:{getattr(request, 'name', None)}")
        self.restrictions.append((request, ports, source_cidrs))
        assert 100 <= priority_base + len(ports) - 1 <= 4096

    def transfer_to(self, request: object, *, source: Path, destination: str) -> _TransferResult:
        self.events.append(f"transfer:{source.name}")
        if source.name == "source.tar":
            self.remote_source_mutated = False
        self.remote_digests[destination] = release_run.digest_path(source)
        return super().transfer_to(request, source=source, destination=destination)

    def exec_argv(
        self,
        request: object,
        argv: tuple[str, ...],
        *,
        env: dict[str, str] | None,
        cwd: str | None,
        dry_run: bool,
    ) -> _TransferResult:
        del request, env, dry_run
        argv = _unwrap_bounded(argv)
        self.actions.append(("exec", object(), argv, None, cwd, False))
        self.events.append("exec:" + " ".join(argv))
        if argv[:3] == ("docker", "buildx", "create") and self.remote_source_mutated:
            return _TransferResult(return_code=1)
        if argv[:3] == ("docker", "buildx", "inspect") and "--bootstrap" in argv:
            return _TransferResult(stdout="Platforms: linux/amd64, linux/arm64\n")
        if argv[0] == "sha256sum":
            digest = self.remote_digests[argv[1]].removeprefix("sha256:")
            return _TransferResult(stdout=f"{digest}  {argv[1]}\n")
        if argv[:3] == ("docker", "image", "inspect"):
            if argv[3] == "--format={{.Architecture}}":
                return _TransferResult(stdout="arm64\n")
            reference = argv[-1]
            digest = self.local_digests.get(reference)
            if digest is None:
                digest = "sha256:" + hashlib.sha256(reference.encode()).hexdigest()
            return _TransferResult(stdout=f"{digest}\n")
        if argv[:2] == ("docker", "push"):
            self.registry_digests[argv[-1]] = _registry_digest(argv[-1])
            return _TransferResult()
        if argv[:2] == ("skopeo", "inspect"):
            reference = argv[-1].removeprefix("docker://")
            digest = self.registry_digests.get(reference)
            return (
                _TransferResult(stdout=f"{digest}\n")
                if digest is not None
                else _TransferResult(return_code=1)
            )
        if argv[:2] == ("mktemp", "-d"):
            return _TransferResult(stdout="/tmp/nanofaas-release-credentials.fake01\n")
        if argv[:2] == ("skopeo", "copy"):
            source = argv[-2].removeprefix("docker://")
            destination = argv[-1].removeprefix("docker://")
            self.registry_digests[destination] = self.registry_digests[source]
            return _TransferResult()
        if argv[:4] == ("docker", "buildx", "imagetools", "create"):
            tag = argv[argv.index("--tag") + 1]
            sources = argv[argv.index("--tag") + 2 :]
            if len(sources) == 1 and "@sha256:" in sources[0]:
                self.registry_digests[tag] = "sha256:" + sources[0].rsplit("@sha256:", 1)[1]
            elif len(sources) == 1 and sources[0] in self.registry_digests:
                self.registry_digests[tag] = self.registry_digests[sources[0]]
            else:
                self.registry_digests[tag] = _registry_digest(",".join(sorted(sources)))
            return _TransferResult()
        if argv[:4] == ("docker", "buildx", "imagetools", "inspect"):
            if argv[-1] not in self.registry_digests:
                return _TransferResult(return_code=1)
            return _TransferResult(
                stdout=(
                    "Manifests:\n"
                    "  Platform:    linux/amd64\n"
                    "  Platform:    linux/arm64\n"
                )
            )
        if argv[:2] == ("docker", "port"):
            return _TransferResult(stdout="127.0.0.1:32768\n")
        if (
            argv[:2] == ("docker", "run")
            and "WATCHDOG_CMD=/nanofaas-arm64-smoke-missing-child" in argv
        ):
            return _TransferResult(
                stderr="Failed to spawn runtime: No such file or directory (os error 2)",
                return_code=1,
            )
        return _TransferResult()

    def connection_host(self, request: object) -> str:
        name = str(getattr(request, "name", ""))
        return "198.51.100.42" if name.endswith("-loadgen") else "203.0.113.10"


class _RecreatedReleaseProvider(_ReleaseProvider):
    def __init__(self, events: list[str]) -> None:
        super().__init__(events)
        self.images_available = False

    def exec_argv(
        self,
        request: object,
        argv: tuple[str, ...],
        *,
        env: dict[str, str] | None,
        cwd: str | None,
        dry_run: bool,
    ) -> _TransferResult:
        argv = _unwrap_bounded(argv)
        if argv[:3] == ("docker", "image", "inspect") and not self.images_available:
            self.events.append("exec:" + " ".join(argv))
            return _TransferResult(return_code=1)
        if argv[:3] == ("docker", "buildx", "bake"):
            self.images_available = True
        return super().exec_argv(
            request,
            argv,
            env=env,
            cwd=cwd,
            dry_run=dry_run,
        )


class _RegistryMutatesAfterEvidenceProvider(_ReleaseProvider):
    def __init__(self, events: list[str], target: str) -> None:
        super().__init__(events)
        self.target = target
        self.mutated = False

    def exec_argv(
        self,
        request: object,
        argv: tuple[str, ...],
        *,
        env: dict[str, str] | None,
        cwd: str | None,
        dry_run: bool,
    ) -> _TransferResult:
        result = super().exec_argv(
            request,
            argv,
            env=env,
            cwd=cwd,
            dry_run=dry_run,
        )
        if (
            argv[:2] == ("skopeo", "inspect")
            and argv[-1] == f"docker://{self.target}"
            and not self.mutated
        ):
            self.registry_digests[self.target] = "sha256:" + "f" * 64
            self.mutated = True
        return result


class _ArmFailureProvider(_ReleaseProvider):
    def __init__(self, events: list[str], failure: str) -> None:
        super().__init__(events)
        self.failure = failure

    def exec_argv(
        self,
        request: object,
        argv: tuple[str, ...],
        *,
        env: dict[str, str] | None,
        cwd: str | None,
        dry_run: bool,
    ) -> _TransferResult:
        argv = _unwrap_bounded(argv)
        result = super().exec_argv(
            request,
            argv,
            env=env,
            cwd=cwd,
            dry_run=dry_run,
        )
        if (
            self.failure == "builder"
            and argv[:3] == ("docker", "buildx", "inspect")
            and "--bootstrap" in argv
        ):
            return _TransferResult(stdout="Platforms: linux/amd64\n")
        if (
            self.failure == "bake"
            and argv[:3] == ("docker", "buildx", "bake")
            and "docker-arm64" in argv
        ):
            return _TransferResult(stderr="arm bake failed", return_code=1)
        if (
            self.failure == "architecture"
            and argv[:3] == ("docker", "image", "inspect")
            and argv[3] == "--format={{.Architecture}}"
        ):
            return _TransferResult(stdout="amd64\n")
        if (
            self.failure == "digest"
            and argv[:3] == ("docker", "image", "inspect")
            and argv[3] == "--format={{.Id}}"
            and "-arm64" in argv[-1]
        ):
            return _TransferResult(stdout="missing\n")
        if self.failure == "push" and argv[:2] == ("docker", "push") and "-arm64" in argv[-1]:
            return _TransferResult(stderr="arm push failed", return_code=1)
        if (
            self.failure == "registry"
            and argv[:2] == ("skopeo", "inspect")
            and "-arm64" in argv[-1]
        ):
            return _TransferResult(stdout="missing\n")
        if self.failure == "start" and argv[:3] == ("docker", "run", "--detach"):
            return _TransferResult(stderr="arm server failed", return_code=1)
        if self.failure in {"health", "health-cleanup", "health-cleanup-raises"} and argv[0] == "curl":
            return _TransferResult(stderr="arm health failed", return_code=1)
        if (
            self.failure == "health-cleanup"
            and argv[:3] == ("docker", "rm", "--force")
        ):
            return _TransferResult(stderr="cleanup failed", return_code=1)
        if (
            self.failure == "health-cleanup-raises"
            and argv[:3] == ("docker", "rm", "--force")
        ):
            raise RuntimeError("cleanup exploded")
        if (
            self.failure == "watchdog"
            and argv[:2] == ("docker", "run")
            and "WATCHDOG_CMD=/nanofaas-arm64-smoke-missing-child" in argv
        ):
            return _TransferResult(stderr="exec format error", return_code=1)
        return result


class _LoadtestWorkflow:
    def __init__(self, run_dir: Path, value: float, events: list[str]) -> None:
        self.run_dir = run_dir
        self.value = value
        self.events = events

    def run(self) -> None:
        self.events.append(f"loadtest:{self.run_dir.name}")
        self.run_dir.mkdir(parents=True, exist_ok=True)
        (self.run_dir / "summary.json").write_text(
            json.dumps(_summary(self.value)), encoding="utf-8"
        )


def _runtime_fakes(plan, events: list[str], provider=None):
    provider = provider or _ReleaseProvider(events)

    @contextmanager
    def provisioner(*args, **kwargs):
        del args
        verifier = kwargs.pop("post_ensure_verifier", None)
        assert not kwargs.keys() - {"repo_root", "orchestrator_factory", "keep"}
        provider.events.append("provision:enter")
        try:
            if verifier is not None:
                verifier(
                    "stack",
                    release_run.vm_request_for_role(
                        plan.environment, "stack", loadtest=True
                    ),
                )
                verifier(
                    "loadgen",
                    release_run.vm_request_for_role(
                        plan.environment, "loadgen", loadtest=True
                    ),
                )
                verifier(
                    "arm-builder",
                    release_run.vm_request_for_role(plan.environment, "arm-builder"),
                )
            yield
        finally:
            provider.events.append("provision:exit")

    def builder_provisioner(provider_arg, request, repo_root):
        del repo_root
        assert provider_arg is provider
        provider.events.append(f"release-builder:{getattr(request, 'name', '?')}")

    loadtest_calls: list[dict[str, object]] = []

    def loadtest_builder(*args, **kwargs):
        del args
        loadtest_calls.append(kwargs)
        return _LoadtestWorkflow(kwargs["run_dir"], float(len(loadtest_calls) * 10), events)

    def archive_builder(repo_root: Path, commit: str, destination: Path):
        del repo_root, commit
        destination.write_bytes(b"exact-source")
        return release_run.ArtifactEvidence(
            "local", str(destination), release_run.digest_path(destination)
        )

    return (
        provider,
        provisioner,
        builder_provisioner,
        loadtest_builder,
        archive_builder,
        loadtest_calls,
    )


def _provisioner_with_recorded_rsync(plan, provider, wildcard_present):
    @contextmanager
    def provisioner(*args, **kwargs):
        del args
        verifier = kwargs["post_ensure_verifier"]
        provider.events.append("provision:enter")
        try:
            for role in ("stack", "loadgen"):
                verifier(
                    role,
                    release_run.vm_request_for_role(
                        plan.environment, role, loadtest=True
                    ),
                )
            verifier(
                "arm-builder",
                release_run.vm_request_for_role(plan.environment, "arm-builder"),
            )
            for role in ("stack", "loadgen"):
                provider.events.append(
                    f"rsync:{role}:wildcard={wildcard_present()}"
                )
            yield
        finally:
            provider.events.append("provision:exit")

    return provisioner


def test_run_composes_amd64_gate_and_defers_all_credentials_and_publication(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    events: list[str] = []
    provider, provisioner, builder, loadtest, archive, loadtest_calls = _runtime_fakes(plan, events)

    decision = release_run.run_amd64_release(
        plan,
        provider_factory=lambda _environment, _root: provider,
        provisioner=provisioner,
        builder_provisioner=builder,
        loadtest_builder=loadtest,
        archive_builder=archive,
    )

    assert decision.passed is True
    assert events[:11] == [
        "teardown:nanofaas-azure-release",
        "teardown:nanofaas-azure-release-loadgen",
        "teardown:nanofaas-azure-release-arm",
        "provision:enter",
        "facts:nanofaas-azure-release",
        "restrict:nanofaas-azure-release",
        "facts:nanofaas-azure-release-loadgen",
        "restrict:nanofaas-azure-release",
        "facts:nanofaas-azure-release-arm",
        "restrict:nanofaas-azure-release",
        "release-builder:nanofaas-azure-release",
    ]
    assert events[-1] == "provision:exit"
    reset = events.index(f"exec:docker buildx rm --force {BUILDER_NAME}")
    create = events.index(
        f"exec:docker buildx create --name {BUILDER_NAME} --driver "
        "docker-container --buildkitd-config "
        f"/home/azureuser/nanofaas-release/{CURRENT_VERSION}/"
        f"{plan.buildkit_config.name} --use"
    )
    source_test = events.index("exec:./gradlew test")
    source_restages = [
        index for index, event in enumerate(events) if event == "transfer:source.tar"
    ]
    assert len(source_restages) == 3
    assert source_test < source_restages[1] < create
    tunnel = next(
        index for index, event in enumerate(events) if "TCP-LISTEN:5000" in event
    )
    assert source_restages[2] < tunnel
    assert reset < create
    assert [
        (getattr(request, "name"), ports, sources)
        for request, ports, sources in provider.restrictions
    ] == [
        (
            "nanofaas-azure-release",
            (30080, 30081, 30090),
            ("203.0.113.0/24",),
        ),
        (
            "nanofaas-azure-release",
            (30080, 30081, 30090),
            ("198.51.100.42/32", "203.0.113.0/24"),
        ),
        (
            "nanofaas-azure-release",
            (5000,),
            ("203.0.113.10/32",),
        ),
    ]
    assert [call["run_dir"] for call in loadtest_calls] == [
        plan.run_dir / "run-1",
        plan.run_dir / "run-2",
        plan.run_dir / "run-3",
    ]
    for call in loadtest_calls:
        assert call["control_plane_url"] == "http://203.0.113.10:30080"
        assert getattr(call["prometheus_client"], "_url") == "http://203.0.113.10:30090"
        control_plane_tag = f"localhost:5000/nanofaas/control-plane:{CURRENT_TAG}-amd64-native"
        assert call["prebuilt_control_plane_image"] == (
            f"localhost:5000/nanofaas/control-plane@{_registry_digest(control_plane_tag)}"
        )
        function_tag = f"localhost:5000/nanofaas/java-word-stats:{CURRENT_TAG}-amd64-native"
        assert call["prebuilt_function_images"] == {
            "word-stats-java": (
                f"localhost:5000/nanofaas/java-word-stats@{_registry_digest(function_tag)}"
            )
        }
    rendered = "\n".join(events).lower()
    assert "arm64" in rendered
    first_ghcr = next(index for index, event in enumerate(events) if "ghcr.io" in event.lower())
    last_smoke_cleanup = max(
        index
        for index, event in enumerate(events)
        if event.startswith("exec:docker rm --force nanofaas-arm64-smoke")
    )
    assert last_smoke_cleanup < first_ghcr
    login = events.index(
        'exec:sh -c exec docker login "$1" --username "$2" --password-stdin < "$3" '
        "nanofaas-release-login ghcr.io miciav "
        "/tmp/nanofaas-release-credentials.fake01/ghcr-token"
    )
    assert last_smoke_cleanup < login < first_ghcr or login == first_ghcr
    assert "exec:rm -rf -- /tmp/nanofaas-release-credentials.fake01" in events
    creates = [
        (index, event.split("--tag ", 1)[1].split(" ")[1:])
        for index, event in enumerate(events)
        if "imagetools create" in event
    ]
    # manifests merge two pinned architecture sources; aliases retag one manifest
    manifests = [index for index, sources in creates if len(sources) == 2]
    aliases = [index for index, sources in creates if len(sources) == 1]
    assert aliases and manifests
    assert max(manifests) < min(aliases)
    for index, sources in creates:
        assert all("@sha256:" in source for source in sources)
    assert plan.credentials is not None
    secret_paths = {
        plan.credentials.ghcr_token,
        plan.credentials.cosign_key,
        plan.credentials.cosign_password,
    }
    transferred = {action[2] for action in provider.actions if action[0] == "transfer"}
    assert transferred.isdisjoint(secret_paths)
    push_actions = [
        action
        for action in provider.actions
        if action[0] == "exec"
        and isinstance(action[2], tuple)
        and action[2][:2] == ("docker", "push")
    ]
    assert push_actions
    assert all(action[4] is None for action in push_actions)
    payloads = [
        json.loads(path.read_text(encoding="utf-8"))
        for path in sorted(plan.state_directory.glob("*.json"))
    ]
    assert [payload["phase"] for payload in payloads] == list(release_run.RELEASE_PHASES)
    assert all(payload["outcome"] == "passed" for payload in payloads)


def test_release_runs_arm64_only_after_the_passed_amd64_gate(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    events: list[str] = []
    provider, provisioner, builder, loadtest, archive, _ = _runtime_fakes(plan, events)

    def record_boundary(boundary: str) -> None:
        events.append(f"phase:{boundary}")

    decision = release_run.run_amd64_release(
        plan,
        provider_factory=lambda _environment, _root: provider,
        provisioner=provisioner,
        builder_provisioner=builder,
        loadtest_builder=loadtest,
        archive_builder=archive,
        failure_injector=record_boundary,
    )

    assert decision.passed is True
    gate = events.index("phase:regression-gate:after-action")
    tunnel = next(
        index for index, event in enumerate(events) if "TCP-LISTEN:5000" in event
    )
    assert gate < tunnel
    arm_pushes = [event for event in events if event.startswith("exec:docker push") and "-arm64" in event]
    architecture_inspects = [
        event
        for event in events
        if event.startswith("exec:docker image inspect --format={{.Architecture}}")
    ]
    server_runs = [
        event
        for event in events
        if event.startswith("exec:docker run --detach --rm --name nanofaas-arm64-smoke-")
    ]
    server_removals = [
        event
        for event in events
        if event.startswith("exec:docker rm --force nanofaas-arm64-smoke-")
    ]
    assert len(arm_pushes) == 26
    assert len(architecture_inspects) == 26
    assert len(server_runs) == 25
    assert len(server_removals) == 25
    assert all("--platform linux/arm64" in event for event in server_runs)
    assert sum(event.startswith("exec:docker buildx create") for event in events) == 1
    health_checks = [event for event in events if event.startswith("exec:curl")]
    assert len(health_checks) == 25
    assert all("--retry-max-time 120" in event for event in health_checks)
    payloads = [
        json.loads(path.read_text(encoding="utf-8"))
        for path in sorted(plan.state_directory.glob("*.json"))
    ]
    assert [payload["phase"] for payload in payloads] == list(release_run.RELEASE_PHASES)


def _run_with_arm_failure(
    plan: release_run.Amd64ReleasePlan,
    events: list[str],
    provider: _ArmFailureProvider,
) -> None:
    _, provisioner, builder, loadtest, archive, _ = _runtime_fakes(plan, events, provider)
    release_run.run_amd64_release(
        plan,
        provider_factory=lambda _environment, _root: provider,
        provisioner=provisioner,
        builder_provisioner=builder,
        loadtest_builder=loadtest,
        archive_builder=archive,
    )


def test_failed_arm64_server_start_still_attempts_container_removal(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    events: list[str] = []
    provider = _ArmFailureProvider(events, "start")

    with pytest.raises(RuntimeError, match="arm server failed"):
        _run_with_arm_failure(plan, events, provider)

    assert "exec:docker rm --force nanofaas-arm64-smoke-1" in events


@pytest.mark.parametrize(
    ("failure", "error", "failed_phase"),
    (
        ("builder", "does not support linux/arm64", "arm64-build"),
        ("bake", "arm bake failed", "arm64-build"),
        ("architecture", "image architecture mismatch", "arm64-build"),
        ("digest", "invalid image digest", "arm64-build"),
        ("push", "arm push failed", "arm64-build"),
        ("registry", "invalid registry digest", "arm64-build"),
        ("health", "arm health failed", "arm64-smoke"),
        ("watchdog", "exec format error", "arm64-smoke"),
    ),
)
def test_arm64_failures_are_journaled_and_cannot_reach_publication(
    failure: str,
    error: str,
    failed_phase: str,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    events: list[str] = []
    provider = _ArmFailureProvider(events, failure)

    with pytest.raises(RuntimeError, match=error):
        _run_with_arm_failure(plan, events, provider)

    payloads = [
        json.loads(path.read_text(encoding="utf-8"))
        for path in sorted(plan.state_directory.glob("*.json"))
    ]
    assert payloads[-1]["phase"] == failed_phase
    assert payloads[-1]["outcome"] == "failed"
    rendered = "\n".join(events).lower()
    assert "ghcr.io" not in rendered
    assert "docker login" not in rendered
    assert "skopeo copy" not in rendered
    assert "imagetools" not in rendered


def test_arm64_health_failure_is_preserved_when_container_cleanup_also_fails(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    events: list[str] = []
    provider = _ArmFailureProvider(events, "health-cleanup")

    with pytest.raises(RuntimeError, match="arm health failed"):
        _run_with_arm_failure(plan, events, provider)

    assert "exec:docker rm --force nanofaas-arm64-smoke-1" in events


def test_arm64_health_failure_is_preserved_when_cleanup_executor_raises(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    events: list[str] = []
    provider = _ArmFailureProvider(events, "health-cleanup-raises")

    with pytest.raises(RuntimeError, match="arm health failed"):
        _run_with_arm_failure(plan, events, provider)

    assert "exec:docker rm --force nanofaas-arm64-smoke-1" in events


@pytest.mark.parametrize("failed_phase", arm.ARM64_PHASES)
@pytest.mark.parametrize("boundary_suffix", ("", ":after-action"))
def test_injected_arm64_phase_failure_never_reaches_publication(
    failed_phase: str,
    boundary_suffix: str,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    events: list[str] = []
    provider, provisioner, builder, loadtest, archive, _ = _runtime_fakes(plan, events)

    def fail(boundary: str) -> None:
        if boundary == f"{failed_phase}{boundary_suffix}":
            raise RuntimeError(f"injected:{boundary}")

    with pytest.raises(RuntimeError, match="injected"):
        release_run.run_amd64_release(
            plan,
            provider_factory=lambda _environment, _root: provider,
            provisioner=provisioner,
            builder_provisioner=builder,
            loadtest_builder=loadtest,
            archive_builder=archive,
            failure_injector=fail,
        )

    payloads = [
        json.loads(path.read_text(encoding="utf-8"))
        for path in sorted(plan.state_directory.glob("*.json"))
    ]
    assert payloads[-1]["phase"] == failed_phase
    assert payloads[-1]["outcome"] == "failed"
    rendered = "\n".join(events).lower()
    assert "ghcr.io" not in rendered
    assert "docker login" not in rendered
    assert "skopeo copy" not in rendered
    assert "imagetools" not in rendered


@pytest.mark.parametrize(
    ("failed_phase", "expect_manifests"),
    (
        ("publish-architectures", False),
        ("publish-manifests", False),
        ("publish-aliases", True),
    ),
)
def test_injected_publish_phase_failures_stop_downstream_publication(
    failed_phase: str,
    expect_manifests: bool,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    events: list[str] = []
    provider, provisioner, builder, loadtest, archive, _ = _runtime_fakes(plan, events)

    def fail(boundary: str) -> None:
        if boundary == failed_phase:
            raise RuntimeError(f"injected:{boundary}")

    with pytest.raises(RuntimeError, match="injected"):
        release_run.run_amd64_release(
            plan,
            provider_factory=lambda _environment, _root: provider,
            provisioner=provisioner,
            builder_provisioner=builder,
            loadtest_builder=loadtest,
            archive_builder=archive,
            failure_injector=fail,
        )

    payloads = [
        json.loads(path.read_text(encoding="utf-8"))
        for path in sorted(plan.state_directory.glob("*.json"))
    ]
    assert payloads[-1]["phase"] == failed_phase
    assert payloads[-1]["outcome"] == "failed"
    creates = [event for event in events if "imagetools create" in event]
    if failed_phase == "publish-architectures":
        assert not any("skopeo copy" in event for event in events)
        assert not creates
    if failed_phase == "publish-manifests":
        assert any("skopeo copy" in event for event in events)
        assert not creates
    if failed_phase == "publish-aliases":
        assert creates
        alias_creates = [
            event for event in creates if len(event.split("--tag ", 1)[1].split(" ")) == 2
        ]
        assert not alias_creates
    # credentials directory is always cleaned, even on failure
    assert "exec:rm -rf -- /tmp/nanofaas-release-credentials.fake01" in events


@pytest.mark.parametrize(
    ("vm_name", "field", "actual"),
    (
        ("nanofaas-azure-release", "location", "eastus"),
        ("nanofaas-azure-release", "vm_size", "Standard_D4s_v5"),
        ("nanofaas-azure-release", "disk_size_gb", 64),
        (
            "nanofaas-azure-release",
            "image_urn",
            "Canonical:ubuntu-24_04-lts:server:24.04.202505281",
        ),
        ("nanofaas-azure-release-loadgen", "vm_size", "Standard_B2s"),
    ),
)
def test_post_provision_vm_fact_mismatch_stops_before_source_tests_or_builds(
    vm_name: str,
    field: str,
    actual: object,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    events: list[str] = []
    provider, provisioner, builder, loadtest, archive, _ = _runtime_fakes(plan, events)
    provider.fact_overrides[vm_name] = {field: actual}

    with pytest.raises(RuntimeError, match="Azure release VM facts mismatch"):
        release_run.run_amd64_release(
            plan,
            provider_factory=lambda _environment, _root: provider,
            provisioner=provisioner,
            builder_provisioner=builder,
            loadtest_builder=loadtest,
            archive_builder=archive,
        )

    assert events.index("provision:enter") < events.index(f"facts:{vm_name}")
    assert not any(
        event.startswith("release-builder:")
        or "./gradlew test" in event
        or "docker buildx" in event
        for event in events
    )


def test_real_regression_failure_stops_the_release_at_the_amd64_gate(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    profile = release_run._performance_profile(plan)
    baseline = release_run.aggregate_runs(
        profile,
        (_summary(100), _summary(100), _summary(100)),
    )
    baseline_record = build_release_record(
        version="0.0.0",
        source_commit="b" * 40,
        image_digests={},
        aggregate=baseline,
        policy=release_run._regression_policy(plan),
    )
    monkeypatch.setattr(release_run, "_release_records", lambda _directory: (baseline_record,))
    events: list[str] = []
    provider, provisioner, builder, loadtest, archive, _ = _runtime_fakes(plan, events)

    with pytest.raises(RuntimeError, match="throughput loss"):
        release_run.run_amd64_release(
            plan,
            provider_factory=lambda _environment, _root: provider,
            provisioner=provisioner,
            builder_provisioner=builder,
            loadtest_builder=loadtest,
            archive_builder=archive,
        )

    decision = json.loads((plan.run_dir / "regression-decision.json").read_text(encoding="utf-8"))
    assert decision["passed"] is False
    journal = [
        json.loads(path.read_text(encoding="utf-8"))
        for path in sorted(plan.state_directory.glob("*.json"))
    ]
    assert journal[-1]["phase"] == "regression-gate"
    assert journal[-1]["outcome"] == "failed"
    rendered = "\n".join(events).lower()
    assert "arm64" not in rendered
    assert "ghcr.io" not in rendered


def test_mutated_benchmark_evidence_is_rejected_before_aggregation(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    events: list[str] = []
    provider, provisioner, builder, loadtest, archive, _ = _runtime_fakes(plan, events)

    def mutate(boundary: str) -> None:
        if boundary == "benchmark-2:after-action":
            (plan.run_dir / "run-2" / "summary.json").write_text(
                json.dumps(_summary(200)), encoding="utf-8"
            )

    with pytest.raises(RuntimeError, match="benchmark-2 evidence changed"):
        release_run.run_amd64_release(
            plan,
            provider_factory=lambda _environment, _root: provider,
            provisioner=provisioner,
            builder_provisioner=builder,
            loadtest_builder=loadtest,
            archive_builder=archive,
            failure_injector=mutate,
        )


def test_mutated_source_archive_is_rejected_before_the_clean_build_restage(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    events: list[str] = []
    provider, provisioner, builder, loadtest, archive, _ = _runtime_fakes(plan, events)

    def mutate(boundary: str) -> None:
        if boundary == "source-tests:after-action":
            (plan.run_dir / "source.tar").write_bytes(b"mutated-source")

    with pytest.raises(RuntimeError, match="source-tests evidence changed"):
        release_run.run_amd64_release(
            plan,
            provider_factory=lambda _environment, _root: provider,
            provisioner=provisioner,
            builder_provisioner=builder,
            loadtest_builder=loadtest,
            archive_builder=archive,
            failure_injector=mutate,
        )

    assert not any("docker buildx create" in event for event in events)


def test_amd64_pre_action_remote_source_mutation_is_overwritten_by_clean_restage(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    events: list[str] = []
    provider, provisioner, builder, loadtest, archive, _ = _runtime_fakes(plan, events)

    def mutate(boundary: str) -> None:
        if boundary == "amd64-build":
            provider.remote_source_mutated = True

    decision = release_run.run_amd64_release(
        plan,
        provider_factory=lambda _environment, _root: provider,
        provisioner=provisioner,
        builder_provisioner=builder,
        loadtest_builder=loadtest,
        archive_builder=archive,
        failure_injector=mutate,
    )

    assert decision.passed is True
    assert provider.remote_source_mutated is False


def test_mutated_amd64_image_evidence_is_rejected_before_any_push(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    events: list[str] = []
    provider, provisioner, builder, loadtest, archive, _ = _runtime_fakes(plan, events)
    mutated_image = plan.image_plan.cells[0].image

    def mutate(boundary: str) -> None:
        if boundary == "amd64-build:after-action":
            provider.local_digests[mutated_image] = "sha256:" + "f" * 64

    with pytest.raises(RuntimeError, match="amd64-build evidence changed"):
        release_run.run_amd64_release(
            plan,
            provider_factory=lambda _environment, _root: provider,
            provisioner=provisioner,
            builder_provisioner=builder,
            loadtest_builder=loadtest,
            archive_builder=archive,
            failure_injector=mutate,
        )

    assert not any(event.startswith("exec:docker push") for event in events)


def test_mutated_aggregate_cannot_turn_a_real_regression_into_a_pass(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    profile = release_run._performance_profile(plan)
    baseline = release_run.aggregate_runs(
        profile,
        (_summary(100), _summary(100), _summary(100)),
    )
    baseline_record = build_release_record(
        version="0.0.0",
        source_commit="b" * 40,
        image_digests={},
        aggregate=baseline,
        policy=release_run._regression_policy(plan),
    )
    monkeypatch.setattr(release_run, "_release_records", lambda _directory: (baseline_record,))
    events: list[str] = []
    provider, provisioner, builder, loadtest, archive, _ = _runtime_fakes(plan, events)

    def mutate(boundary: str) -> None:
        if boundary == "aggregate:after-action":
            (plan.run_dir / "aggregate.json").write_text(
                json.dumps(asdict(baseline)), encoding="utf-8"
            )

    with pytest.raises(RuntimeError, match="aggregate evidence changed"):
        release_run.run_amd64_release(
            plan,
            provider_factory=lambda _environment, _root: provider,
            provisioner=provisioner,
            builder_provisioner=builder,
            loadtest_builder=loadtest,
            archive_builder=archive,
            failure_injector=mutate,
        )


def test_existing_journal_requires_explicit_resume_before_provisioning(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    events: list[str] = []
    provider, provisioner, builder, loadtest, archive, _ = _runtime_fakes(plan, events)
    kwargs = {
        "provider_factory": lambda _environment, _root: provider,
        "provisioner": provisioner,
        "builder_provisioner": builder,
        "loadtest_builder": loadtest,
        "archive_builder": archive,
    }
    release_run.run_amd64_release(plan, **kwargs)
    events.clear()

    with pytest.raises(ValueError, match="--resume"):
        release_run.run_amd64_release(
            plan,
            provider_factory=lambda *_args: (_ for _ in ()).throw(
                AssertionError("must reject before provider creation")
            ),
            provisioner=provisioner,
            builder_provisioner=builder,
            loadtest_builder=loadtest,
            archive_builder=archive,
        )

    assert events == []


def test_resume_requires_existing_journal_before_provider_creation(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    plan = _plan(tmp_path, monkeypatch)

    with pytest.raises(ValueError, match="--resume requires an existing"):
        release_run.run_amd64_release(
            plan,
            resume=True,
            provider_factory=lambda *_args: (_ for _ in ()).throw(
                AssertionError("must reject before provider creation")
            ),
        )


def test_run_rejects_repository_credentials_before_provider_creation(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    repo = tmp_path / "source-repo"
    secret_dir = repo / "build" / "release-secrets"
    secret_dir.mkdir(parents=True)
    assert plan.credentials is not None
    unsafe = replace(
        plan,
        repo_root=repo,
        credentials=replace(
            plan.credentials,
            ghcr_token=_secret(secret_dir / "ghcr-token", "token"),
        ),
    )

    with pytest.raises(ValueError, match="outside the repository"):
        release_run.run_amd64_release(
            unsafe,
            provider_factory=lambda *_args: (_ for _ in ()).throw(
                AssertionError("must reject before provider creation")
            ),
        )


def test_release_run_lock_rejects_a_second_coordinator_before_provider_creation(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    plan = _plan(tmp_path, monkeypatch)

    with release_run._release_run_lock(release_run._release_lock_path(plan)):
        with pytest.raises(RuntimeError, match="already in progress"):
            release_run.run_amd64_release(
                plan,
                provider_factory=lambda *_args: (_ for _ in ()).throw(
                    AssertionError("must reject before provider creation")
                ),
            )


def test_release_lock_collides_across_different_run_directories(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    first_root = tmp_path / "first"
    second_root = tmp_path / "second"
    first_root.mkdir()
    second_root.mkdir()
    first = _plan(first_root, monkeypatch)
    second = _plan(second_root, monkeypatch)
    assert first.run_dir != second.run_dir
    assert release_run._release_lock_path(first) == release_run._release_lock_path(second)
    assert not release_run._release_lock_path(first).is_relative_to(first.run_dir)
    assert not release_run._release_lock_path(second).is_relative_to(second.run_dir)

    with release_run._release_run_lock(release_run._release_lock_path(first)):
        with pytest.raises(RuntimeError, match="already in progress"):
            release_run.run_amd64_release(
                second,
                provider_factory=lambda *_args: (_ for _ in ()).throw(
                    AssertionError("must reject before provider creation")
                ),
            )


def test_release_lock_collides_across_versions_for_shared_azure_resources(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    first = replace(_plan(tmp_path, monkeypatch), version="0.18.0")
    second = replace(first, version="0.19.0")

    assert release_run._release_lock_path(first) == release_run._release_lock_path(second)


def test_release_lock_differs_for_distinct_azure_resource_groups(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    first = _plan(tmp_path, monkeypatch)
    other_environment = first.environment.model_copy(deep=True)
    assert other_environment.azure is not None
    other_environment.azure.resource_group = "other-release-rg"
    second = replace(first, environment=other_environment)

    assert release_run._release_lock_path(first) != release_run._release_lock_path(second)


def test_release_lock_normalizes_case_insensitive_azure_resource_names(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    first = _plan(tmp_path, monkeypatch)
    case_variant_environment = first.environment.model_copy(deep=True)
    assert case_variant_environment.azure is not None
    case_variant_environment.azure.resource_group = "NANOFAAS-RG"
    case_variant_environment.roles["stack"].name = "NANOFAAS-AZURE-RELEASE"
    case_variant_environment.roles["loadgen"].name = (
        "NANOFAAS-AZURE-RELEASE-LOADGEN"
    )
    second = replace(first, environment=case_variant_environment)

    assert release_run._release_lock_path(first) == release_run._release_lock_path(second)


def test_release_lock_ignores_location_for_the_same_azure_resources(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    first = _plan(tmp_path, monkeypatch)
    other_location_environment = first.environment.model_copy(deep=True)
    assert other_location_environment.azure is not None
    other_location_environment.azure.location = "eastus"
    second = replace(first, environment=other_location_environment)

    assert release_run._release_lock_path(first) == release_run._release_lock_path(second)


def test_run_rechecks_the_guarded_commit_before_provider_creation(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    monkeypatch.setattr(
        release_run,
        "git_state",
        lambda _root: release_run.GitState(commit="b" * 40, clean=True),
    )

    with pytest.raises(ValueError, match="source commit changed"):
        release_run.run_amd64_release(
            plan,
            provider_factory=lambda *_args: (_ for _ in ()).throw(
                AssertionError("must reject before provider creation")
            ),
        )


def test_run_rechecks_the_guarded_commit_immediately_before_the_gate(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    checks = 0

    def moving_source(_root: Path) -> release_run.GitState:
        nonlocal checks
        checks += 1
        commit = "a" * 40 if checks == 1 else "b" * 40
        return release_run.GitState(commit=commit, clean=True)

    monkeypatch.setattr(release_run, "git_state", moving_source)
    events: list[str] = []
    provider, provisioner, builder, loadtest, archive, _ = _runtime_fakes(plan, events)

    with pytest.raises(ValueError, match="source commit changed"):
        release_run.run_amd64_release(
            plan,
            provider_factory=lambda _environment, _root: provider,
            provisioner=provisioner,
            builder_provisioner=builder,
            loadtest_builder=loadtest,
            archive_builder=archive,
        )

    journal = [
        json.loads(path.read_text(encoding="utf-8"))
        for path in sorted(plan.state_directory.glob("*.json"))
    ]
    assert journal[-1]["phase"] == "regression-gate"
    assert journal[-1]["outcome"] == "failed"


def test_run_rechecks_the_guarded_commit_immediately_before_arm64(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    checks = 0

    def moving_source(_root: Path) -> release_run.GitState:
        nonlocal checks
        checks += 1
        commit = "a" * 40 if checks < 3 else "b" * 40
        return release_run.GitState(commit=commit, clean=True)

    monkeypatch.setattr(release_run, "git_state", moving_source)
    events: list[str] = []
    provider, provisioner, builder, loadtest, archive, _ = _runtime_fakes(plan, events)

    with pytest.raises(ValueError, match="source commit changed"):
        release_run.run_amd64_release(
            plan,
            provider_factory=lambda _environment, _root: provider,
            provisioner=provisioner,
            builder_provisioner=builder,
            loadtest_builder=loadtest,
            archive_builder=archive,
        )

    assert not any("TCP-LISTEN:5000" in event for event in events)
    journal = [
        json.loads(path.read_text(encoding="utf-8"))
        for path in sorted(plan.state_directory.glob("*.json"))
    ]
    assert journal[-1]["phase"] == "arm64-build"
    assert journal[-1]["outcome"] == "failed"


def test_run_rechecks_the_guarded_commit_immediately_before_arm64_smoke(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    checks = 0

    def moving_source(_root: Path) -> release_run.GitState:
        nonlocal checks
        checks += 1
        commit = "a" * 40 if checks < 4 else "b" * 40
        return release_run.GitState(commit=commit, clean=True)

    monkeypatch.setattr(release_run, "git_state", moving_source)
    events: list[str] = []
    provider, provisioner, builder, loadtest, archive, _ = _runtime_fakes(plan, events)

    with pytest.raises(ValueError, match="source commit changed"):
        release_run.run_amd64_release(
            plan,
            provider_factory=lambda _environment, _root: provider,
            provisioner=provisioner,
            builder_provisioner=builder,
            loadtest_builder=loadtest,
            archive_builder=archive,
        )

    assert not any("docker run --detach" in event for event in events)
    journal = [
        json.loads(path.read_text(encoding="utf-8"))
        for path in sorted(plan.state_directory.glob("*.json"))
    ]
    assert journal[-1]["phase"] == "arm64-smoke"
    assert journal[-1]["outcome"] == "failed"


@pytest.mark.parametrize("input_name", ("bake_file", "buildkit_config"))
def test_amd64_build_rejects_mutated_generated_inputs_before_remote_actions(
    input_name: str, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    generated_input = getattr(plan, input_name)
    assert isinstance(generated_input, Path)
    generated_input.write_text("mutated\n", encoding="utf-8")
    provider = _ReleaseProvider([])

    with pytest.raises(ValueError, match="generated build input changed"):
        release_run._build_amd64_images(
            plan,
            provider,
            object(),
            "/srv/release/docker-bake.json",
            "/srv/release/buildkitd.toml",
            "/srv/release/source",
        )

    assert provider.actions == []


def test_verified_resume_reuses_every_amd64_phase(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    first_events: list[str] = []
    provider, provisioner, builder, loadtest, archive, _ = _runtime_fakes(plan, first_events)
    release_run.run_amd64_release(
        plan,
        provider_factory=lambda _environment, _root: provider,
        provisioner=provisioner,
        builder_provisioner=builder,
        loadtest_builder=loadtest,
        archive_builder=archive,
    )
    second_events: list[str] = []
    provider.events = second_events
    _, _, _, second_loadtest, _, calls = _runtime_fakes(plan, second_events)

    decision = release_run.run_amd64_release(
        plan,
        resume=True,
        provider_factory=lambda _environment, _root: provider,
        provisioner=provisioner,
        builder_provisioner=builder,
        loadtest_builder=second_loadtest,
        archive_builder=archive,
    )

    assert decision.passed is True
    assert calls == []
    assert not any(event.startswith("teardown:") for event in second_events)
    assert second_events[:9] == [
        "provision:enter",
        "facts:nanofaas-azure-release",
        "restrict:nanofaas-azure-release",
        "facts:nanofaas-azure-release-loadgen",
        "restrict:nanofaas-azure-release",
        "facts:nanofaas-azure-release-arm",
        "restrict:nanofaas-azure-release",
        "release-builder:nanofaas-azure-release",
        "release-builder:nanofaas-azure-release-arm",
    ]
    assert not any("buildx" in event or "docker push" in event for event in second_events)


def test_resume_invalidates_arm_build_and_smoke_when_arm_digest_changes(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    first_events: list[str] = []
    provider, provisioner, builder, loadtest, archive, _ = _runtime_fakes(plan, first_events)
    release_run.run_amd64_release(
        plan,
        provider_factory=lambda _environment, _root: provider,
        provisioner=provisioner,
        builder_provisioner=builder,
        loadtest_builder=loadtest,
        archive_builder=archive,
    )
    arm_plan = arm.build_arm64_image_plan(
        plan.repo_root,
        plan.version,
        registry=plan.image_plan.registry,
    )
    provider.registry_digests[arm_plan.cells[0].image] = "sha256:" + "f" * 64
    second_events: list[str] = []
    provider.events = second_events
    _, _, _, resumed_loadtest, _, calls = _runtime_fakes(plan, second_events)

    decision = release_run.run_amd64_release(
        plan,
        resume=True,
        provider_factory=lambda _environment, _root: provider,
        provisioner=provisioner,
        builder_provisioner=builder,
        loadtest_builder=resumed_loadtest,
        archive_builder=archive,
    )

    assert decision.passed is True
    assert calls == []
    assert any("TCP-LISTEN:5000" in event for event in second_events)
    payloads = [
        json.loads(path.read_text(encoding="utf-8"))
        for path in sorted(plan.state_directory.glob("*.json"))
    ]
    invalidation = next(
        payload
        for payload in payloads
        if payload.get("kind") == "invalidation"
        and payload.get("invalidateFrom") == "arm64-build"
    )
    assert invalidation["affectedPhases"] == [
        "arm64-build",
        "arm64-smoke",
        "publish-architectures",
        "publish-manifests",
        "publish-aliases",
        "attest",
        "finalize",
    ]


def test_resume_repeats_only_arm_smoke_when_its_local_marker_changes(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    first_events: list[str] = []
    provider, provisioner, builder, loadtest, archive, _ = _runtime_fakes(plan, first_events)
    release_run.run_amd64_release(
        plan,
        provider_factory=lambda _environment, _root: provider,
        provisioner=provisioner,
        builder_provisioner=builder,
        loadtest_builder=loadtest,
        archive_builder=archive,
    )
    (plan.run_dir / "arm64-smoke.json").write_text("{}\n", encoding="utf-8")
    second_events: list[str] = []
    provider.events = second_events
    _, _, _, resumed_loadtest, _, calls = _runtime_fakes(plan, second_events)

    decision = release_run.run_amd64_release(
        plan,
        resume=True,
        provider_factory=lambda _environment, _root: provider,
        provisioner=provisioner,
        builder_provisioner=builder,
        loadtest_builder=resumed_loadtest,
        archive_builder=archive,
    )

    assert decision.passed is True
    assert calls == []
    assert any("TCP-LISTEN:5000" in event for event in second_events)
    assert not any("docker push" in event for event in second_events)
    assert any("docker run --detach" in event for event in second_events)
    payloads = [
        json.loads(path.read_text(encoding="utf-8"))
        for path in sorted(plan.state_directory.glob("*.json"))
    ]
    invalidation = next(
        payload
        for payload in payloads
        if payload.get("kind") == "invalidation"
        and payload.get("invalidateFrom") == "arm64-smoke"
    )
    assert invalidation["affectedPhases"] == [
        "arm64-smoke",
        "publish-architectures",
        "publish-manifests",
        "publish-aliases",
        "attest",
        "finalize",
    ]


def test_resume_restricts_legacy_wildcard_ingress_before_bootstrap_rsync(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    first_events: list[str] = []
    provider, provisioner, builder, loadtest, archive, _ = _runtime_fakes(
        plan, first_events
    )
    release_run.run_amd64_release(
        plan,
        provider_factory=lambda _environment, _root: provider,
        provisioner=provisioner,
        builder_provisioner=builder,
        loadtest_builder=loadtest,
        archive_builder=archive,
    )
    events: list[str] = []
    provider.events = events
    provider.restrictions.clear()
    legacy = {"wildcard": True}
    original_restrict = provider.restrict_inbound_sources

    def restrict(request, *, ports, source_cidrs, priority_base=1010):
        original_restrict(
            request, ports=ports, source_cidrs=source_cidrs, priority_base=priority_base
        )
        legacy["wildcard"] = False

    monkeypatch.setattr(provider, "restrict_inbound_sources", restrict)

    decision = release_run.run_amd64_release(
        plan,
        resume=True,
        provider_factory=lambda _environment, _root: provider,
        provisioner=_provisioner_with_recorded_rsync(
            plan, provider, lambda: legacy["wildcard"]
        ),
        builder_provisioner=builder,
        loadtest_builder=loadtest,
        archive_builder=archive,
    )

    assert decision.passed is True
    assert "rsync:stack:wildcard=False" in events
    assert "rsync:loadgen:wildcard=False" in events
    assert [sources for _request, _ports, sources in provider.restrictions] == [
        ("203.0.113.0/24",),
        ("198.51.100.42/32", "203.0.113.0/24"),
        ("203.0.113.10/32",),
    ]


def test_resume_nsg_restriction_failure_stops_before_any_bootstrap_rsync(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    first_events: list[str] = []
    provider, provisioner, builder, loadtest, archive, _ = _runtime_fakes(
        plan, first_events
    )
    release_run.run_amd64_release(
        plan,
        provider_factory=lambda _environment, _root: provider,
        provisioner=provisioner,
        builder_provisioner=builder,
        loadtest_builder=loadtest,
        archive_builder=archive,
    )
    events: list[str] = []
    provider.events = events
    provider.actions.clear()
    provider.restrictions.clear()
    restriction_calls = 0
    original_restrict = provider.restrict_inbound_sources

    def fail_final_restriction(request, *, ports, source_cidrs, priority_base=1010):
        nonlocal restriction_calls
        restriction_calls += 1
        events.append(f"restrict:attempt:{restriction_calls}")
        if restriction_calls == 2:
            raise RuntimeError("cannot apply final benchmark ingress")
        original_restrict(
            request, ports=ports, source_cidrs=source_cidrs, priority_base=priority_base
        )

    monkeypatch.setattr(
        provider, "restrict_inbound_sources", fail_final_restriction
    )

    with pytest.raises(RuntimeError, match="cannot apply final benchmark ingress"):
        release_run.run_amd64_release(
            plan,
            resume=True,
            provider_factory=lambda _environment, _root: provider,
            provisioner=_provisioner_with_recorded_rsync(
                plan, provider, lambda: True
            ),
            builder_provisioner=builder,
            loadtest_builder=loadtest,
            archive_builder=archive,
        )

    assert restriction_calls == 2
    assert not any(event.startswith("rsync:") for event in events)
    assert provider.actions == []
    assert not any(event.startswith("release-builder:") for event in events)


def test_resume_provisions_before_verification_and_invalidates_from_changed_evidence(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    first_events: list[str] = []
    provider, provisioner, builder, loadtest, archive, _ = _runtime_fakes(plan, first_events)
    release_run.run_amd64_release(
        plan,
        provider_factory=lambda _environment, _root: provider,
        provisioner=provisioner,
        builder_provisioner=builder,
        loadtest_builder=loadtest,
        archive_builder=archive,
    )
    (plan.run_dir / "run-2" / "summary.json").write_text(
        json.dumps(_summary(999)), encoding="utf-8"
    )
    second_events: list[str] = []
    provider.events = second_events
    _, _, _, second_loadtest, _, calls = _runtime_fakes(plan, second_events)

    decision = release_run.run_amd64_release(
        plan,
        resume=True,
        provider_factory=lambda _environment, _root: provider,
        provisioner=provisioner,
        builder_provisioner=builder,
        loadtest_builder=second_loadtest,
        archive_builder=archive,
    )

    assert decision.passed is True
    assert second_events[:9] == [
        "provision:enter",
        "facts:nanofaas-azure-release",
        "restrict:nanofaas-azure-release",
        "facts:nanofaas-azure-release-loadgen",
        "restrict:nanofaas-azure-release",
        "facts:nanofaas-azure-release-arm",
        "restrict:nanofaas-azure-release",
        "release-builder:nanofaas-azure-release",
        "release-builder:nanofaas-azure-release-arm",
    ]
    assert [call["run_dir"] for call in calls] == [
        plan.run_dir / "run-2",
        plan.run_dir / "run-3",
    ]
    payloads = [
        json.loads(path.read_text(encoding="utf-8"))
        for path in sorted(plan.state_directory.glob("*.json"))
    ]
    invalidation = next(payload for payload in payloads if payload["kind"] == "invalidation")
    assert invalidation["invalidateFrom"] == "benchmark-2"
    assert invalidation["affectedPhases"] == [
        "benchmark-2",
        "benchmark-3",
        "aggregate",
        "regression-gate",
        "arm64-build",
        "arm64-smoke",
        "publish-architectures",
        "publish-manifests",
        "publish-aliases",
        "attest",
        "finalize",
    ]


def test_resume_on_recreated_vm_restages_verified_source_before_rebuilding(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    first_events: list[str] = []
    provider, provisioner, builder, loadtest, archive, _ = _runtime_fakes(plan, first_events)
    release_run.run_amd64_release(
        plan,
        provider_factory=lambda _environment, _root: provider,
        provisioner=provisioner,
        builder_provisioner=builder,
        loadtest_builder=loadtest,
        archive_builder=archive,
    )
    second_events: list[str] = []
    recreated = _RecreatedReleaseProvider(second_events)
    recreated, provisioner, builder, loadtest, archive, _ = _runtime_fakes(
        plan, second_events, recreated
    )

    decision = release_run.run_amd64_release(
        plan,
        resume=True,
        provider_factory=lambda _environment, _root: recreated,
        provisioner=provisioner,
        builder_provisioner=builder,
        loadtest_builder=loadtest,
        archive_builder=archive,
    )

    assert decision.passed is True
    restage = second_events.index("transfer:source.tar")
    rebuild = next(
        index for index, event in enumerate(second_events) if "docker buildx create" in event
    )
    assert restage < rebuild
    assert not any("./gradlew test" in event for event in second_events)
    payloads = [
        json.loads(path.read_text(encoding="utf-8"))
        for path in sorted(plan.state_directory.glob("*.json"))
    ]
    invalidation = next(payload for payload in payloads if payload["kind"] == "invalidation")
    assert invalidation["invalidateFrom"] == "amd64-build"


def test_registry_digest_change_invalidates_push_and_benchmarks_use_digest_refs(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    first_events: list[str] = []
    provider, provisioner, builder, loadtest, archive, _ = _runtime_fakes(plan, first_events)
    release_run.run_amd64_release(
        plan,
        provider_factory=lambda _environment, _root: provider,
        provisioner=provisioner,
        builder_provisioner=builder,
        loadtest_builder=loadtest,
        archive_builder=archive,
    )
    control_plane = f"localhost:5000/nanofaas/control-plane:{CURRENT_TAG}-amd64-native"
    provider.registry_digests[control_plane] = "sha256:" + "f" * 64
    second_events: list[str] = []
    provider.events = second_events
    _, _, _, second_loadtest, _, calls = _runtime_fakes(plan, second_events)

    decision = release_run.run_amd64_release(
        plan,
        resume=True,
        provider_factory=lambda _environment, _root: provider,
        provisioner=provisioner,
        builder_provisioner=builder,
        loadtest_builder=second_loadtest,
        archive_builder=archive,
    )

    assert decision.passed is True
    assert len(calls) == 3
    assert all("@sha256:" in str(call["prebuilt_control_plane_image"]) for call in calls)
    payloads = [
        json.loads(path.read_text(encoding="utf-8"))
        for path in sorted(plan.state_directory.glob("*.json"))
    ]
    invalidation = next(payload for payload in payloads if payload["kind"] == "invalidation")
    assert invalidation["invalidateFrom"] == "local-registry-push"


def test_registry_mutation_after_push_evidence_fails_before_any_benchmark(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    control_plane = f"localhost:5000/nanofaas/control-plane:{CURRENT_TAG}-amd64-native"
    events: list[str] = []
    provider = _RegistryMutatesAfterEvidenceProvider(events, control_plane)
    provider, provisioner, builder, loadtest, archive, calls = _runtime_fakes(
        plan, events, provider
    )

    with pytest.raises(RuntimeError, match="registry image changed"):
        release_run.run_amd64_release(
            plan,
            provider_factory=lambda _environment, _root: provider,
            provisioner=provisioner,
            builder_provisioner=builder,
            loadtest_builder=loadtest,
            archive_builder=archive,
        )

    assert calls == []
    payloads = [
        json.loads(path.read_text(encoding="utf-8"))
        for path in sorted(plan.state_directory.glob("*.json"))
    ]
    push = next(payload for payload in payloads if payload.get("phase") == "local-registry-push")
    benchmark = payloads[-1]
    assert push["outcome"] == "passed"
    assert benchmark["phase"] == "benchmark-1"
    assert benchmark["outcome"] == "failed"


@pytest.mark.parametrize("failed_phase", release_run.AMD64_PHASES)
def test_each_phase_failure_stops_before_arm_or_publication_and_is_journaled(
    failed_phase: str, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    events: list[str] = []
    provider, provisioner, builder, loadtest, archive, _ = _runtime_fakes(plan, events)

    def fail(phase: str) -> None:
        if phase == failed_phase:
            raise RuntimeError(f"injected:{phase}")

    with pytest.raises(RuntimeError, match=f"injected:{failed_phase}"):
        release_run.run_amd64_release(
            plan,
            provider_factory=lambda _environment, _root: provider,
            provisioner=provisioner,
            builder_provisioner=builder,
            loadtest_builder=loadtest,
            archive_builder=archive,
            failure_injector=fail,
        )

    payloads = [
        json.loads(path.read_text(encoding="utf-8"))
        for path in sorted(plan.state_directory.glob("*.json"))
    ]
    assert payloads[-1]["phase"] == failed_phase
    assert payloads[-1]["outcome"] == "failed"
    rendered = "\n".join(events).lower()
    assert "arm64" not in rendered
    assert "ghcr.io" not in rendered


@pytest.mark.parametrize("failed_phase", release_run.AMD64_PHASES)
def test_each_post_action_failure_is_journaled_before_later_phases(
    failed_phase: str, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    plan = _plan(tmp_path, monkeypatch)
    events: list[str] = []
    provider, provisioner, builder, loadtest, archive, _ = _runtime_fakes(plan, events)

    def fail(boundary: str) -> None:
        if boundary == f"{failed_phase}:after-action":
            raise RuntimeError(f"injected-after:{failed_phase}")

    with pytest.raises(RuntimeError, match=f"injected-after:{failed_phase}"):
        release_run.run_amd64_release(
            plan,
            provider_factory=lambda _environment, _root: provider,
            provisioner=provisioner,
            builder_provisioner=builder,
            loadtest_builder=loadtest,
            archive_builder=archive,
            failure_injector=fail,
        )

    payloads = [
        json.loads(path.read_text(encoding="utf-8"))
        for path in sorted(plan.state_directory.glob("*.json"))
    ]
    assert payloads[-1]["phase"] == failed_phase
    assert payloads[-1]["outcome"] == "failed"
    later = release_run.AMD64_PHASES[release_run.AMD64_PHASES.index(failed_phase) + 1 :]
    assert not any(payload.get("phase") in later for payload in payloads)
    rendered = "\n".join(events).lower()
    assert "arm64" not in rendered
    assert "ghcr.io" not in rendered
