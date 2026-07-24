"""Azure release planning and execution through the ARM64 functional gate."""

from __future__ import annotations

from collections.abc import Callable, Iterable, Iterator, Mapping
from contextlib import AbstractContextManager, contextmanager
from dataclasses import asdict, dataclass, field
import fcntl
import hashlib
import ipaddress
import json
import math
import os
import shlex
import subprocess
import sys
from pathlib import Path
import tempfile
import time
from typing import Any

import yaml

from controlplane_tool.config import EnvironmentConfig, ScenarioConfig
from controlplane_tool.config.environment import ExecutionRole
from controlplane_tool.cli.execution import build_role_bindings
from controlplane_tool.cli.provisioning import provision_environment
from controlplane_tool.cli.vm_provider import (
    vm_provider_for_environment,
    vm_request_for_role,
)
from controlplane_tool.functions.catalog import resolve_function_definition
from controlplane_tool.images.bake import render_bake_json
from controlplane_tool.images.plan import DEFAULT_REGISTRY, ImagePlan, build_image_plan
from controlplane_tool.plans.loadtest import build_loadtest_plan
from controlplane_tool.release import arm
from controlplane_tool.release.environment import validate_release_environment
from controlplane_tool.release.metrics import (
    PerformanceAggregate,
    PerformanceProfile,
    RegressionDecision,
    RegressionPolicy,
    aggregate_runs,
    build_release_record,
    evaluate_regression,
    newest_comparable_record,
)
from controlplane_tool.release import attest, publish
from controlplane_tool.release.secrets import (
    stage_cosign_credentials,
    stage_ghcr_credentials,
    validate_secret_file,
)
from controlplane_tool.release.state import (
    ArtifactEvidence,
    ReleaseIdentity,
    ReleaseJournal,
    digest_path,
)
from controlplane_tool.release.versioning import normalize_version
from workflow_tasks.infra.ansible import AnsibleAdapter
from workflow_tasks.loadtest.adapters import HttpPrometheusClient
from workflow_tasks.tasks.models import CommandTaskSpec
from workflow_tasks.vm.models import VmRequest, vm_remote_home


AMD64_PHASES = (
    "source-tests",
    "amd64-build",
    "local-registry-push",
    "benchmark-1",
    "benchmark-2",
    "benchmark-3",
    "aggregate",
    "regression-gate",
)
RELEASE_PHASES = AMD64_PHASES + arm.ARM64_PHASES + publish.PUBLISH_PHASES + attest.ATTEST_PHASES
_GO_TOOLCHAIN = (
    "golang:1.24-alpine@sha256:757779acac4af1b349a20f357c7296097b4a0b89da4ad0e370b339060077282a"
)
_NODE_TOOLCHAIN = (
    # >= 22: the JS SDK test script relies on node --test native globs (node >= 21).
    "node:22-alpine@sha256:16e22a550f3863206a3f701448c45f7912c6896a62de43add43bb9c86130c3e2"
)
_RUST_TOOLCHAIN = (
    "rust:1.97.1-alpine3.21@sha256:e5c73e7a712b368eb90b1190c6e1c4a01a3ebb0fe0abfff68c3bcd2df26ecc41"
)


@dataclass(frozen=True, slots=True)
class GitState:
    commit: str
    clean: bool


@dataclass(frozen=True, slots=True)
class CredentialFiles:
    ghcr_token: Path = field(repr=False)
    cosign_key: Path = field(repr=False)
    cosign_password: Path | None = field(repr=False)

    def validate(self, *, repo_root: Path | None = None) -> "CredentialFiles":
        if self.cosign_password is None:
            raise ValueError("cosign password file is required")
        paths = (self.ghcr_token, self.cosign_key, self.cosign_password)
        for path in paths:
            validate_secret_file(path)
        if repo_root is not None:
            root = Path(repo_root).resolve(strict=True)
            for path in paths:
                try:
                    path.resolve(strict=True).relative_to(root)
                except ValueError:
                    continue
                raise ValueError("release credential files must be outside the repository")
        return self


@dataclass(frozen=True, slots=True)
class BuilderConfiguration:
    name: str
    max_parallelism: int


@dataclass(frozen=True, slots=True)
class ReleaseSettings:
    max_parallelism: int
    scenario: Path
    benchmark_runs: int
    profile: str
    throughput_max_loss_percent: float
    p95_max_increase_percent: float
    error_rate_max: float


@dataclass(frozen=True, slots=True)
class Amd64ReleasePlan:
    repo_root: Path
    run_dir: Path
    version: str
    identity: ReleaseIdentity
    environment: EnvironmentConfig
    scenario: ScenarioConfig
    settings: ReleaseSettings
    image_plan: ImagePlan
    builder: BuilderConfiguration
    bake_file: Path
    buildkit_config: Path
    performance_root: Path
    credentials: CredentialFiles | None = field(repr=False)

    @property
    def phase_names(self) -> tuple[str, ...]:
        return RELEASE_PHASES

    @property
    def journal_root(self) -> Path:
        release_parent = self.run_dir.parent
        if release_parent.name == "releases" and self.run_dir.name == self.version:
            return release_parent.parent
        return self.run_dir

    @property
    def state_directory(self) -> Path:
        return self.journal_root / "releases" / self.version / "state"

    def render(self) -> str:
        lines = [
            f"release {self.version} ({self.identity.source_commit})",
            "provider azure: stack + loadgen",
            (
                f"buildx {self.builder.name}: docker-container, "
                f"maxParallelism={self.builder.max_parallelism}"
            ),
            f"images: {len(self.image_plan.cells)} AMD64 + 26 ARM64 via Bake + native",
            "ARM64: digest-pinned QEMU after regression-gate",
            (
                "credentials: 3 private files validated; transfer deferred"
                if self.credentials is not None
                else "credentials: deferred for offline plan"
            ),
        ]
        lines.extend(f"{index:02d}  {phase}" for index, phase in enumerate(self.phase_names, 1))
        return "\n".join(lines) + "\n"


def git_state(repo_root: Path) -> GitState:
    commit = subprocess.run(
        ("git", "rev-parse", "HEAD"),
        cwd=repo_root,
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    status = subprocess.run(
        ("git", "status", "--porcelain=v1"),
        cwd=repo_root,
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    return GitState(commit=commit, clean=not status.strip())


def build_amd64_release_plan(
    *,
    repo_root: Path,
    version: str,
    environment_path: Path,
    release_config_path: Path,
    run_dir: Path,
    credentials: CredentialFiles | None,
    performance_root: Path | None = None,
) -> Amd64ReleasePlan:
    root = Path(repo_root).resolve()
    environment_file = Path(environment_path).resolve()
    release_config_file = Path(release_config_path).resolve()
    source = git_state(root)
    if not source.clean:
        raise ValueError("release requires a clean Git tree")
    if credentials is not None:
        credentials.validate(repo_root=root)
    plain_version, version_tag = normalize_version(version)
    environment = EnvironmentConfig.model_validate(_read_yaml(environment_file))
    validate_release_environment(environment, root, plain_version)
    settings = _release_settings(root, release_config_file)
    scenario = ScenarioConfig.model_validate(_read_yaml(settings.scenario))
    image_plan = build_image_plan(
        root,
        version_tag,
        registry=DEFAULT_REGISTRY,
        architectures=("amd64",),
    )
    if len(image_plan.cells) != 26:
        raise ValueError(f"AMD64 release matrix must contain 26 cells, got {len(image_plan.cells)}")

    destination = Path(run_dir).resolve()
    destination.mkdir(parents=True, exist_ok=True)
    bake_file = destination / "docker-bake.json"
    bake_file.write_text(render_bake_json(image_plan), encoding="utf-8")
    buildkit_config = destination / "buildkitd.toml"
    buildkit_config.write_text(
        f"[worker.oci]\n  max-parallelism = {settings.max_parallelism}\n",
        encoding="utf-8",
    )
    identity = ReleaseIdentity(
        source_commit=source.commit,
        prepared_version=plain_version,
        release_config_digest=digest_path(release_config_file),
        environment_digest=digest_path(environment_file),
    )
    return Amd64ReleasePlan(
        repo_root=root,
        run_dir=destination,
        version=plain_version,
        identity=identity,
        environment=environment,
        scenario=scenario,
        settings=settings,
        image_plan=image_plan,
        builder=BuilderConfiguration(
            name=f"nanofaas-release-{version_tag.replace('.', '-')}",
            max_parallelism=settings.max_parallelism,
        ),
        bake_file=bake_file,
        buildkit_config=buildkit_config,
        performance_root=(
            Path(performance_root).resolve()
            if performance_root is not None
            else attest.performance_root(root)
        ),
        credentials=credentials,
    )


def source_test_commands(remote_source_dir: Path) -> tuple[CommandTaskSpec, ...]:
    source = str(remote_source_dir)
    container_prefix = (
        "docker",
        "run",
        "--rm",
        "-v",
        f"{source}:/source:ro",
        "-w",
        "/workspace",
    )
    copy_source = "set -eu; cp -a /source/. /workspace && "
    return (
        CommandTaskSpec(
            task_id="release.source.gradle",
            summary="Run Java source tests",
            argv=("./gradlew", "test"),
            role="stack",
            remote_dir=source,
        ),
        CommandTaskSpec(
            task_id="release.source.controlplane",
            summary="Run controlplane-tool source tests",
            argv=(
                "uv",
                "run",
                "--project",
                ".",
                "--locked",
                "pytest",
                "-q",
                "tests",
            ),
            role="stack",
            remote_dir=f"{source}/tools/controlplane",
        ),
        CommandTaskSpec(
            task_id="release.source.workflow-tasks",
            summary="Run workflow-tasks source tests",
            argv=(
                "uv",
                "run",
                "--project",
                ".",
                "--locked",
                "pytest",
                "-q",
                "tests",
            ),
            role="stack",
            remote_dir=f"{source}/tools/workflow-tasks",
        ),
        CommandTaskSpec(
            task_id="release.source.python-sdk",
            summary="Run Python SDK and function source tests",
            argv=(
                "uv",
                "run",
                "--project",
                "sdks/python",
                "--extra",
                "test",
                "--locked",
                "pytest",
                "-q",
                "sdks/python/tests",
                "functions/python/word-stats/tests",
                "functions/python/json-transform/tests",
                "functions/python/roman-numeral/tests",
            ),
            role="stack",
            remote_dir=source,
        ),
        CommandTaskSpec(
            task_id="release.source.go",
            summary="Run Go source tests in pinned toolchain",
            argv=(
                *container_prefix,
                _GO_TOOLCHAIN,
                "sh",
                "-c",
                copy_source
                + "for d in sdks/go functions/go/word-stats functions/go/json-transform "
                'functions/go/roman-numeral; do (cd "$d" && go test ./...); done',
            ),
            role="stack",
            remote_dir=source,
        ),
        CommandTaskSpec(
            task_id="release.source.node",
            summary="Run JavaScript source tests in pinned toolchain",
            argv=(
                *container_prefix,
                _NODE_TOOLCHAIN,
                "sh",
                "-c",
                copy_source
                + "npm --prefix sdks/javascript ci && npm --prefix sdks/javascript test && "
                "for d in functions/javascript/word-stats functions/javascript/json-transform "
                'functions/javascript/roman-numeral; do (cd "$d" && npm ci && npm test); done',
            ),
            role="stack",
            remote_dir=source,
        ),
        CommandTaskSpec(
            task_id="release.source.rust",
            summary="Run Rust source tests in pinned toolchain",
            argv=(
                *container_prefix,
                _RUST_TOOLCHAIN,
                "sh",
                "-c",
                copy_source
                + "apk add --no-cache bash curl jq netcat-openbsd python3 >/dev/null && "
                "cargo test --manifest-path runtimes/watchdog/Cargo.toml && "
                "bash runtimes/watchdog/test-local.sh",
            ),
            role="stack",
            remote_dir=source,
        ),
        CommandTaskSpec(
            task_id="release.source.bash",
            summary="Run Bash source tests in pinned toolchain",
            argv=(
                *container_prefix,
                _NODE_TOOLCHAIN,
                "sh",
                "-c",
                copy_source + "apk add --no-cache bash jq >/dev/null && "
                "bash functions/bash/roman-numeral/tests/test_handler.sh",
            ),
            role="stack",
            remote_dir=source,
        ),
    )


def amd64_build_commands(
    plan: Amd64ReleasePlan,
    *,
    remote_bake_file: str,
    remote_buildkit_config: str,
    remote_source_dir: str,
) -> tuple[CommandTaskSpec, ...]:
    commands = [
        CommandTaskSpec(
            task_id="release.buildx.create",
            summary="Create bounded release Buildx builder",
            argv=(
                "docker",
                "buildx",
                "create",
                "--name",
                plan.builder.name,
                "--driver",
                "docker-container",
                "--buildkitd-config",
                remote_buildkit_config,
                "--use",
            ),
            role="stack",
            remote_dir=remote_source_dir,
        ),
        CommandTaskSpec(
            task_id="release.buildx.bootstrap",
            summary="Bootstrap bounded release Buildx builder",
            argv=("docker", "buildx", "inspect", "--builder", plan.builder.name, "--bootstrap"),
            role="stack",
            remote_dir=remote_source_dir,
        ),
    ]
    seen: set[str] = set()
    for cell in plan.image_plan.bake_cells:
        prerequisite = cell.prerequisite_command
        if prerequisite is None or cell.target.name in seen:
            continue
        seen.add(cell.target.name)
        commands.append(
            CommandTaskSpec(
                task_id=f"release.images.prepare.{cell.target.name}",
                summary=f"Prepare {cell.target.name} JVM image",
                argv=prerequisite,
                role="stack",
                remote_dir=remote_source_dir,
            )
        )
    commands.append(
        CommandTaskSpec(
            task_id="release.images.bake.amd64",
            summary="Build AMD64 Dockerfile images",
            argv=(
                "docker",
                "buildx",
                "bake",
                "--builder",
                plan.builder.name,
                "--file",
                remote_bake_file,
                "--load",
                "docker-amd64",
            ),
            role="stack",
            remote_dir=remote_source_dir,
        )
    )
    commands.extend(
        CommandTaskSpec(
            task_id=f"release.images.native.{cell.target.name}",
            summary=f"Build {cell.target.name} AMD64 native image",
            argv=cell.gradle_command or (),
            role="stack",
            remote_dir=remote_source_dir,
        )
        for cell in plan.image_plan.gradle_cells
    )
    return tuple(commands)


def create_source_archive(
    repo_root: Path,
    guarded_commit: str,
    destination: Path,
) -> ArtifactEvidence:
    root = Path(repo_root)
    before = git_state(root)
    if not before.clean:
        raise ValueError("release requires a clean Git tree")
    if before.commit != guarded_commit:
        raise ValueError("release source commit changed after planning")
    output = Path(destination)
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        raise FileExistsError(f"refusing to overwrite source archive: {output}")
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{output.name}.", suffix=".tmp", dir=output.parent
    )
    os.close(descriptor)
    temporary = Path(temporary_name)
    try:
        subprocess.run(
            ("git", "archive", "--format=tar", f"--output={temporary}", guarded_commit),
            cwd=root,
            check=True,
        )
        after = git_state(root)
        if after != before:
            raise ValueError("release source changed while creating archive")
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)
    return ArtifactEvidence("local", str(output), digest_path(output))


def stage_source_archive(
    provider: object,
    request: object,
    *,
    archive: Path,
    remote_archive: str,
    remote_source_dir: str,
    expected_digest: str | None = None,
) -> None:
    local_digest = digest_path(archive)
    if expected_digest is not None and local_digest != expected_digest:
        raise RuntimeError("source-tests evidence changed before consumption")
    _provider_exec(provider, request, ("rm", "-rf", "--", remote_source_dir))
    _provider_exec(provider, request, ("mkdir", "-p", remote_source_dir))
    _provider_transfer_to(
        provider,
        request,
        source=archive,
        destination=remote_archive,
        action="source archive transfer",
    )
    checksum = _provider_exec(provider, request, ("sha256sum", remote_archive))
    actual = str(getattr(checksum, "stdout", "")).split(maxsplit=1)[0]
    expected = (expected_digest or local_digest).removeprefix("sha256:")
    if actual != expected:
        raise RuntimeError("source archive checksum mismatch")
    _provider_exec(
        provider,
        request,
        ("tar", "-xf", remote_archive, "-C", remote_source_dir),
    )


ProviderFactory = Callable[[EnvironmentConfig, Path], object]
Provisioner = Callable[..., AbstractContextManager[None]]
BuilderProvisioner = Callable[[object, object, Path], None]
LoadtestBuilder = Callable[..., object]
ArchiveBuilder = Callable[[Path, str, Path], ArtifactEvidence]
FailureInjector = Callable[[str], None]


def _release_lock_path(plan: Amd64ReleasePlan) -> Path:
    azure = plan.environment.azure
    assert azure is not None
    identity = json.dumps(
        (
            azure.resource_group.casefold(),
            (plan.environment.target("stack").name or "").casefold(),
            (plan.environment.target("loadgen").name or "").casefold(),
        ),
        separators=(",", ":"),
    )
    digest = hashlib.sha256(identity.encode("utf-8")).hexdigest()
    return (
        Path(tempfile.gettempdir())
        / f"nanofaas-release-locks-{os.getuid()}"
        / f"{digest}.lock"
    )


@contextmanager
def _release_run_lock(lock_path: Path) -> Iterator[None]:
    lock_path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    descriptor = os.open(
        lock_path,
        os.O_CREAT
        | os.O_RDWR
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0),
        0o600,
    )
    try:
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise RuntimeError("release run is already in progress") from error
        yield
    finally:
        fcntl.flock(descriptor, fcntl.LOCK_UN)
        os.close(descriptor)


def _assert_guarded_source(plan: Amd64ReleasePlan) -> None:
    source = git_state(plan.repo_root)
    if not source.clean:
        raise ValueError("release requires a clean Git tree")
    if source.commit != plan.identity.source_commit:
        raise ValueError("release source commit changed after planning")


def run_amd64_release(
    plan: Amd64ReleasePlan,
    *,
    resume: bool = False,
    keep: bool = False,
    provider_factory: ProviderFactory | None = None,
    provisioner: Provisioner | None = None,
    builder_provisioner: BuilderProvisioner | None = None,
    loadtest_builder: LoadtestBuilder | None = None,
    archive_builder: ArchiveBuilder | None = None,
    failure_injector: FailureInjector | None = None,
) -> RegressionDecision:
    """Run a release through the ARM64 functional gate, before publication."""
    with _release_run_lock(_release_lock_path(plan)):
        return _run_amd64_release_locked(
            plan,
            resume=resume,
            keep=keep,
            provider_factory=provider_factory,
            provisioner=provisioner,
            builder_provisioner=builder_provisioner,
            loadtest_builder=loadtest_builder,
            archive_builder=archive_builder,
            failure_injector=failure_injector,
        )


def _run_amd64_release_locked(
    plan: Amd64ReleasePlan,
    *,
    resume: bool,
    keep: bool,
    provider_factory: ProviderFactory | None,
    provisioner: Provisioner | None,
    builder_provisioner: BuilderProvisioner | None,
    loadtest_builder: LoadtestBuilder | None,
    archive_builder: ArchiveBuilder | None,
    failure_injector: FailureInjector | None,
) -> RegressionDecision:
    if plan.credentials is None:
        raise ValueError("release run requires explicit credential files")
    global _RUN_STARTED
    _RUN_STARTED = time.monotonic()
    plan.credentials.validate(repo_root=plan.repo_root)
    _assert_guarded_source(plan)
    existing_entries = tuple(plan.state_directory.glob("*.json"))
    if existing_entries and not resume:
        raise ValueError("release journal already exists; pass --resume to verify and reuse it")
    if resume and not existing_entries:
        raise ValueError("--resume requires an existing release journal")

    make_provider = provider_factory or vm_provider_for_environment
    provision = provisioner or provision_environment
    provision_builder = builder_provisioner or _provision_release_builder
    make_loadtest = loadtest_builder or build_loadtest_plan
    make_archive = archive_builder or create_source_archive
    provider = make_provider(plan.environment, plan.repo_root)
    stack_request = vm_request_for_role(plan.environment, "stack", loadtest=True)
    loadgen_request = vm_request_for_role(plan.environment, "loadgen", loadtest=True)
    arm_request = vm_request_for_role(plan.environment, "arm-builder")
    if not resume:
        _progress("→ tearing down any previous release VMs")
        for role, request in (
            ("stack", stack_request),
            ("loadgen", loadgen_request),
            ("arm-builder", arm_request),
        ):
            _require_result(
                provider.teardown(request),  # type: ignore[attr-defined]
                f"recreate dedicated release {role} VM",
            )
    ghcr_auth: dict[str, str] = {}
    journal = ReleaseJournal(
        plan.journal_root,
        plan.identity,
        phases=RELEASE_PHASES,
        artifact_digest=lambda location, reference: _remote_image_digest(
            provider,
            stack_request,
            location,
            reference,
            ghcr_authfile=ghcr_auth.get("authfile"),
        ),
    )
    endpoints: tuple[str, str] | None = None

    def verify_and_secure(role: ExecutionRole, request: VmRequest) -> None:
        nonlocal endpoints
        _verify_release_vm_facts(plan, provider, role, request)
        if role == "stack":
            _secure_release_endpoints(plan, provider, stack_request, None)
        elif role == "arm-builder":
            # The ARM builder pushes through a localhost:5000 tunnel into the
            # stack registry: allow only its address on the registry port.
            arm_host = provider.connection_host(request)  # type: ignore[attr-defined]
            provider.restrict_inbound_sources(  # type: ignore[attr-defined]
                stack_request,
                ports=(5000,),
                source_cidrs=(f"{arm_host}/32",),
                # Separate priority band from the 30080/81/90 endpoint rules
                # (1010-1012) so the registry rule never collides.
                priority_base=1020,
            )
        else:
            endpoints = _secure_release_endpoints(
                plan, provider, stack_request, loadgen_request
            )

    _progress("→ provisioning stack + loadgen VMs (this can take several minutes)")
    with provision(
        plan.scenario,
        plan.environment,
        repo_root=plan.repo_root,
        orchestrator_factory=lambda _root: provider,
        post_ensure_verifier=verify_and_secure,
        keep=keep,
    ):
        if endpoints is None:
            raise RuntimeError("release loadgen ingress was not verified before bootstrap")
        control_plane_url, prometheus_url = endpoints
        _progress("→ provisioning buildx release builder")
        provision_builder(provider, stack_request, plan.repo_root)
        provision_builder(provider, arm_request, plan.repo_root)
        if resume:
            _progress("→ resume: verifying journal evidence against GHCR")
            credentials = plan.credentials
            assert credentials is not None
            # published evidence lives on GHCR: verify it authenticated, then
            # drop the staged token again before any build phase runs
            with stage_ghcr_credentials(
                provider,
                stack_request,
                username=publish.ghcr_username(),
                token_file=credentials.ghcr_token,
            ) as resume_auth:
                ghcr_auth["authfile"] = f"{resume_auth.docker_config}/config.json"
                try:
                    reusable = frozenset(journal.resume().reusable_phases)
                finally:
                    ghcr_auth.pop("authfile", None)
        else:
            reusable = frozenset()
        if reusable:
            _progress(f"↷ reusing verified phases: {', '.join(p for p in RELEASE_PHASES if p in reusable)}")
        remote_root = f"{vm_remote_home(stack_request)}/nanofaas-release/{plan.version}"
        source_dir = f"{remote_root}/source"
        source_archive = f"{remote_root}/source.tar"
        remote_bake = f"{remote_root}/{plan.bake_file.name}"
        remote_buildkit = f"{remote_root}/{plan.buildkit_config.name}"

        if "source-tests" not in reusable:
            _record_phase(
                journal,
                "source-tests",
                lambda: _run_source_tests(
                    plan,
                    provider,
                    stack_request,
                    make_archive,
                    source_archive,
                    source_dir,
                ),
                failure_injector,
            )
        if "amd64-build" not in reusable:
            local_archive = plan.run_dir / "source.tar"
            archive_evidence = _journal_artifact(
                journal,
                "source-tests",
                location="local",
                reference=str(local_archive),
            )

            def build_amd64() -> tuple[ArtifactEvidence, ...]:
                stage_source_archive(
                    provider,
                    stack_request,
                    archive=local_archive,
                    remote_archive=source_archive,
                    remote_source_dir=source_dir,
                    expected_digest=archive_evidence.digest,
                )
                return _build_amd64_images(
                    plan,
                    provider,
                    stack_request,
                    remote_bake,
                    remote_buildkit,
                    source_dir,
                )

            _record_phase(
                journal,
                "amd64-build",
                build_amd64,
                failure_injector,
            )
        if "local-registry-push" not in reusable:
            build_evidence = _journal_phase_artifacts(journal, "amd64-build")
            registry_evidence = _record_phase(
                journal,
                "local-registry-push",
                lambda: _push_local_images(
                    plan,
                    provider,
                    stack_request,
                    build_evidence,
                ),
                failure_injector,
            )
        else:
            registry_evidence = _journal_phase_artifacts(journal, "local-registry-push")

        benchmark_phases = tuple(
            f"benchmark-{index}" for index in range(1, plan.settings.benchmark_runs + 1)
        )
        pending_benchmarks = tuple(phase for phase in benchmark_phases if phase not in reusable)
        if pending_benchmarks:
            expected_registry_digests = _registry_digest_map(plan, registry_evidence)
            bindings, fetcher = build_role_bindings(
                plan.environment,
                vm_provider=provider,
                repo_root=plan.repo_root,
            )
        for index in range(1, plan.settings.benchmark_runs + 1):
            phase = f"benchmark-{index}"
            if phase in reusable:
                continue
            _record_phase(
                journal,
                phase,
                lambda index=index: _run_benchmark(
                    plan,
                    index,
                    make_loadtest,
                    bindings,
                    fetcher,
                    control_plane_url,
                    prometheus_url,
                    provider,
                    stack_request,
                    expected_registry_digests,
                ),
                failure_injector,
            )

        if "aggregate" not in reusable:
            _record_phase(
                journal,
                "aggregate",
                lambda: (_write_aggregate(plan, journal),),
                failure_injector,
            )
        if "regression-gate" not in reusable:
            decision_box: list[RegressionDecision] = []

            def gate() -> tuple[ArtifactEvidence, ...]:
                _assert_guarded_source(plan)
                decision, artifact = _evaluate_gate(plan, journal)
                decision_box.append(decision)
                if not decision.passed:
                    raise RuntimeError(
                        "release regression gate failed: " + "; ".join(decision.failures)
                    )
                return (artifact,)

            _record_phase(journal, "regression-gate", gate, failure_injector)
            decision = decision_box[0]
        else:
            decision = _decision_from_payload(
                _read_verified_local_json(
                    journal,
                    "regression-gate",
                    plan.run_dir / "regression-decision.json",
                )
            )
        if not decision.passed:
            raise RuntimeError("release regression gate evidence did not pass")

        arm_plan = arm.build_arm64_image_plan(
            plan.repo_root,
            plan.version,
            registry=plan.image_plan.registry,
        )
        arm_bake = plan.run_dir / "docker-bake-arm64.json"
        remote_arm_bake = f"{remote_root}/{arm_bake.name}"
        if "arm64-build" not in reusable:
            local_archive = plan.run_dir / "source.tar"
            archive_evidence = _journal_artifact(
                journal,
                "source-tests",
                location="local",
                reference=str(local_archive),
            )

            def build_arm64() -> tuple[ArtifactEvidence, ...]:
                _assert_guarded_source(plan)
                stage_source_archive(
                    provider,
                    arm_request,
                    archive=local_archive,
                    remote_archive=source_archive,
                    remote_source_dir=source_dir,
                    expected_digest=archive_evidence.digest,
                )
                return _build_arm64_images(
                    plan,
                    arm_plan,
                    arm_bake,
                    provider,
                    arm_request,
                    remote_arm_bake,
                    source_dir,
                    registry_upstream=provider.connection_host(stack_request),  # type: ignore[attr-defined]
                )

            _record_phase(journal, "arm64-build", build_arm64, failure_injector)
        if "arm64-smoke" not in reusable:
            build_evidence = _journal_phase_artifacts(journal, "arm64-build")
            _record_phase(
                journal,
                "arm64-smoke",
                lambda: _smoke_arm64_images(
                    plan,
                    arm_plan,
                    provider,
                    arm_request,
                    build_evidence,
                    registry_upstream=provider.connection_host(stack_request),  # type: ignore[attr-defined]
                ),
                failure_injector,
            )

        _publish_release(
            plan,
            journal,
            provider,
            stack_request,
            reusable,
            failure_injector,
        )
        _attest_release(
            plan,
            journal,
            provider,
            stack_request,
            remote_root,
            reusable,
            failure_injector,
        )
        return decision


def _publish_release(
    plan: Amd64ReleasePlan,
    journal: ReleaseJournal,
    provider: object,
    stack_request: VmRequest,
    reusable: frozenset[str],
    failure_injector: FailureInjector | None,
) -> None:
    """Promote the verified 52-cell matrix to GHCR: immutable architecture
    tags first, verified version manifests next, mutable aliases last. The
    GHCR token is transferred only for this window and always cleaned up."""
    pending = tuple(phase for phase in publish.PUBLISH_PHASES if phase not in reusable)
    if not pending:
        return
    assert plan.credentials is not None
    _assert_guarded_source(plan)
    # both gates must hold verified evidence before any publication is planned
    _journal_phase_artifacts(journal, "regression-gate")
    _journal_phase_artifacts(journal, "arm64-smoke")
    publish_plan = publish.build_publish_plan(
        plan.repo_root,
        plan.version,
        local_registry=plan.image_plan.registry,
    )
    source_digests = publish.require_publication_evidence(
        publish_plan,
        _journal_phase_artifacts(journal, "local-registry-push")
        + _journal_phase_artifacts(journal, "arm64-build"),
    )
    with stage_ghcr_credentials(
        provider,
        stack_request,
        username=publish.ghcr_username(),
        token_file=plan.credentials.ghcr_token,
    ) as docker:
        authfile = f"{docker.docker_config}/config.json"
        if "publish-architectures" not in reusable:
            _record_phase(
                journal,
                "publish-architectures",
                lambda: publish.publish_architecture_images(
                    provider,
                    stack_request,
                    publish_plan,
                    source_digests,
                    authfile=authfile,
                ),
                failure_injector,
            )
        if "publish-manifests" not in reusable:
            architecture_digests = {
                artifact.reference.removeprefix("docker://"): artifact.digest
                for artifact in _journal_phase_artifacts(journal, "publish-architectures")
            }
            manifest_evidence = _record_phase(
                journal,
                "publish-manifests",
                lambda: publish.publish_manifests(
                    provider,
                    stack_request,
                    publish_plan,
                    architecture_digests,
                    docker_config=docker.docker_config,
                ),
                failure_injector,
            )
        else:
            manifest_evidence = _journal_phase_artifacts(journal, "publish-manifests")
        if "publish-aliases" not in reusable:
            manifest_digests = {
                artifact.reference.removeprefix("docker://"): artifact.digest
                for artifact in manifest_evidence
            }
            _record_phase(
                journal,
                "publish-aliases",
                lambda: publish.publish_aliases(
                    provider,
                    stack_request,
                    publish_plan,
                    manifest_digests,
                    docker_config=docker.docker_config,
                ),
                failure_injector,
            )


def _attest_release(
    plan: Amd64ReleasePlan,
    journal: ReleaseJournal,
    provider: object,
    stack_request: VmRequest,
    remote_root: str,
    reusable: frozenset[str],
    failure_injector: FailureInjector | None,
) -> None:
    """SBOM, sign, and verify the published digests, then finalize records.

    Signing material reaches the VM only for this window. Published
    performance history changes only after verification succeeds; a
    documentation failure appends no final journal record so --resume can
    retry finalization without rebuilding verified images."""
    pending = tuple(phase for phase in attest.ATTEST_PHASES if phase not in reusable)
    if not pending:
        return
    credentials = plan.credentials
    assert credentials is not None
    _assert_guarded_source(plan)
    published = (
        _journal_phase_artifacts(journal, "publish-architectures")
        + _journal_phase_artifacts(journal, "publish-manifests")
        + _journal_phase_artifacts(journal, "publish-aliases")
    )
    images = {
        artifact.reference.removeprefix("docker://"): artifact.digest
        for artifact in published
    }
    benchmark_digest = _journal_phase_artifacts(journal, "aggregate")[0].digest
    record = build_release_record(
        version=plan.version,
        source_commit=plan.identity.source_commit,
        image_digests=images,
        aggregate=_aggregate_from_payload(
            _read_verified_local_json(journal, "aggregate", plan.run_dir / "aggregate.json")
        ),
        policy=_regression_policy(plan),
    )
    if "attest" not in reusable:
        predicate = attest.build_release_predicate(
            version=plan.version,
            source_commit=plan.identity.source_commit,
            azure_profile=plan.settings.profile,
            benchmark_record_digest=benchmark_digest,
            image_digests=images,
        )
        predicate_file = plan.run_dir / "predicate.json"
        predicate_file.write_text(attest.render_predicate(predicate), encoding="utf-8")
        remote_predicate = f"{remote_root}/predicate.json"

        def sign() -> tuple[ArtifactEvidence, ...]:
            _provider_exec(provider, stack_request, ("mkdir", "-p", remote_root))
            _provider_transfer_to(
                provider,
                stack_request,
                source=predicate_file,
                destination=remote_predicate,
                action="transfer release predicate",
            )
            with stage_ghcr_credentials(
                provider,
                stack_request,
                username=publish.ghcr_username(),
                token_file=credentials.ghcr_token,
            ) as docker, stage_cosign_credentials(
                provider,
                stack_request,
                key_file=credentials.cosign_key,
                password_file=credentials.cosign_password,
            ) as cosign_files:
                attest.attest_release_images(
                    provider,
                    stack_request,
                    images=images,
                    predicate_remote=remote_predicate,
                    sbom_dir_remote=f"{remote_root}/sboms",
                    cosign=cosign_files,
                    docker_config=docker.docker_config,
                )
            return (
                ArtifactEvidence("local", str(predicate_file), digest_path(predicate_file)),
            )

        _record_phase(journal, "attest", sign, failure_injector)
    if "finalize" not in reusable:
        # deliberately outside _record_phase: a documentation failure must
        # leave the journal without any finalize entry, not a failed one
        if failure_injector is not None:
            failure_injector("finalize")
        attest.finalize_release(
            journal,
            record=record,
            performance_root=plan.performance_root,
        )


def _verify_release_vm_facts(
    plan: Amd64ReleasePlan,
    provider: object,
    role: ExecutionRole,
    request: VmRequest,
) -> None:
    azure = plan.environment.azure
    assert azure is not None
    facts = provider.release_vm_facts(request)  # type: ignore[attr-defined]
    target = plan.environment.target(role)
    if role == "loadgen":
        expected_size = azure.loadgen_vm_size
    elif role == "arm-builder":
        expected_size = azure.arm_vm_size
    else:
        expected_size = azure.vm_size
    expected = {
        "location": azure.location,
        "vm_size": expected_size,
        "disk_size_gb": int(target.disk.removesuffix("G")),
        "image_urn": azure.arm_image_urn if role == "arm-builder" else azure.image_urn,
    }
    mismatches = tuple(
        name for name, value in expected.items() if getattr(facts, name, None) != value
    )
    if mismatches:
        raise RuntimeError(
            f"Azure release VM facts mismatch for {role}: {', '.join(mismatches)}"
        )


def _secure_release_endpoints(
    plan: Amd64ReleasePlan,
    provider: object,
    stack_request: VmRequest,
    loadgen_request: VmRequest | None,
) -> tuple[str, str]:
    azure = plan.environment.azure
    assert azure is not None and azure.operator_source_cidr is not None
    stack_host = provider.connection_host(stack_request)  # type: ignore[attr-defined]
    sources = (azure.operator_source_cidr,)
    if loadgen_request is not None:
        loadgen_host = provider.connection_host(loadgen_request)  # type: ignore[attr-defined]
        loadgen_address = ipaddress.ip_address(loadgen_host)
        loadgen_cidr = f"{loadgen_address}/{loadgen_address.max_prefixlen}"
        sources = tuple(dict.fromkeys((loadgen_cidr, *sources)))
    provider.restrict_inbound_sources(  # type: ignore[attr-defined]
        stack_request,
        ports=(30080, 30081, 30090),
        source_cidrs=sources,
    )
    return f"http://{stack_host}:30080", f"http://{stack_host}:30090"


_RUN_STARTED: float | None = None


def _elapsed_total() -> str:
    if _RUN_STARTED is None:
        return ""
    total = int(time.monotonic() - _RUN_STARTED)
    return f" [tot {total // 3600:02d}:{total % 3600 // 60:02d}:{total % 60:02d}]"


def _progress(message: str) -> None:
    print(f"{message}{_elapsed_total()}", file=sys.stderr, flush=True)


def _phase_banner(phase: str) -> str:
    if phase not in RELEASE_PHASES:
        return phase
    index = RELEASE_PHASES.index(phase) + 1
    total = len(RELEASE_PHASES)
    filled = round(20 * index / total)
    return f"[{index:2d}/{total}] {'█' * filled}{'░' * (20 - filled)} {phase}"


def _record_phase(
    journal: ReleaseJournal,
    phase: str,
    action: Callable[[], Iterable[ArtifactEvidence]],
    failure_injector: FailureInjector | None,
) -> tuple[ArtifactEvidence, ...]:
    _progress(f"→ {_phase_banner(phase)} ...")
    started = time.monotonic()
    try:
        if failure_injector is not None:
            failure_injector(phase)
        artifacts = tuple(action())
        if failure_injector is not None:
            failure_injector(f"{phase}:after-action")
    except BaseException:
        journal.record(phase, outcome="failed")
        _progress(f"✗ {phase} failed after {int(time.monotonic() - started)}s")
        raise
    journal.record(phase, artifacts=artifacts)
    _progress(f"✓ {phase} done in {int(time.monotonic() - started)}s")
    return artifacts


def _journal_phase_artifacts(
    journal: ReleaseJournal,
    phase: str,
) -> tuple[ArtifactEvidence, ...]:
    for entry in reversed(journal.entries()):
        if entry.get("kind") != "phase" or entry.get("phase") != phase:
            continue
        if entry.get("outcome") != "passed":
            break
        return tuple(
            ArtifactEvidence(
                str(artifact["location"]),
                str(artifact["reference"]),
                str(artifact["digest"]),
            )
            for artifact in entry["artifacts"]
        )
    raise ValueError(f"release journal has no reusable evidence for {phase}")


def _journal_artifact(
    journal: ReleaseJournal,
    phase: str,
    *,
    location: str,
    reference: str,
) -> ArtifactEvidence:
    matches = tuple(
        artifact
        for artifact in _journal_phase_artifacts(journal, phase)
        if artifact.location == location and artifact.reference == reference
    )
    if len(matches) != 1:
        raise RuntimeError(f"{phase} evidence changed before consumption")
    return matches[0]


def _read_verified_local_json(
    journal: ReleaseJournal,
    phase: str,
    path: Path,
) -> dict[str, Any]:
    artifact = _journal_artifact(
        journal,
        phase,
        location="local",
        reference=str(path),
    )
    try:
        content = path.read_bytes()
    except OSError as error:
        raise RuntimeError(f"{phase} evidence changed before consumption") from error
    actual_digest = f"sha256:{hashlib.sha256(content).hexdigest()}"
    if actual_digest != artifact.digest:
        raise RuntimeError(f"{phase} evidence changed before consumption")
    payload = json.loads(content)
    if not isinstance(payload, dict):
        raise ValueError(f"JSON evidence must be an object: {path}")
    return payload


def _run_source_tests(
    plan: Amd64ReleasePlan,
    provider: object,
    request: object,
    archive_builder: ArchiveBuilder,
    remote_archive: str,
    remote_source_dir: str,
) -> tuple[ArtifactEvidence, ...]:
    archive = plan.run_dir / "source.tar"
    archive.unlink(missing_ok=True)
    archive_evidence = archive_builder(plan.repo_root, plan.identity.source_commit, archive)
    stage_source_archive(
        provider,
        request,
        archive=archive,
        remote_archive=remote_archive,
        remote_source_dir=remote_source_dir,
    )
    for command in source_test_commands(Path(remote_source_dir)):
        _provider_exec(
            provider,
            request,
            command.argv,
            cwd=command.remote_dir,
            bounded=True,
        )
    marker = _write_json(
        plan.run_dir / "source-tests.json",
        {"sourceCommit": plan.identity.source_commit, "passed": True},
    )
    return archive_evidence, marker


def _build_amd64_images(
    plan: Amd64ReleasePlan,
    provider: object,
    request: object,
    remote_bake: str,
    remote_buildkit: str,
    remote_source_dir: str,
) -> tuple[ArtifactEvidence, ...]:
    _verify_generated_build_inputs(plan)
    _provider_exec(provider, request, ("mkdir", "-p", str(Path(remote_bake).parent)))
    for source, destination in (
        (plan.bake_file, remote_bake),
        (plan.buildkit_config, remote_buildkit),
    ):
        _provider_transfer_to(
            provider,
            request,
            source=source,
            destination=destination,
            action=f"transfer {source.name}",
        )
    _reset_named_builder(plan, provider, request)
    for command in amd64_build_commands(
        plan,
        remote_bake_file=remote_bake,
        remote_buildkit_config=remote_buildkit,
        remote_source_dir=remote_source_dir,
    ):
        _provider_exec(provider, request, command.argv, cwd=command.remote_dir, bounded=True)
    return _local_image_evidence(plan, provider, request)


def _build_arm64_images(
    plan: Amd64ReleasePlan,
    image_plan: ImagePlan,
    bake_file: Path,
    provider: object,
    request: object,
    remote_bake: str,
    remote_source_dir: str,
    *,
    registry_upstream: str,
) -> tuple[ArtifactEvidence, ...]:
    bake_file.write_text(render_bake_json(image_plan), encoding="utf-8")
    _provider_exec(provider, request, ("mkdir", "-p", str(Path(remote_bake).parent)))
    _provider_transfer_to(
        provider,
        request,
        source=bake_file,
        destination=remote_bake,
        action=f"transfer {bake_file.name}",
    )
    for command in arm.arm64_build_commands(
        image_plan,
        builder_name=plan.builder.name,
        remote_bake_file=remote_bake,
        remote_source_dir=remote_source_dir,
        registry_upstream=registry_upstream,
    ):
        result = _provider_exec(
            provider,
            request,
            command.argv,
            cwd=command.remote_dir,
            # The builder task's stdout is parsed below — keep it clean.
            bounded=command.task_id != "release.arm64.builder",
        )
        if command.task_id == "release.arm64.builder":
            arm.require_arm64_builder(str(getattr(result, "stdout", "")))

    for cell in image_plan.cells:
        _require_image_architecture(provider, request, cell.image, "arm64")
        _inspect_image_digest(provider, request, cell.image)
    for cell in image_plan.cells:
        _provider_exec(provider, request, ("docker", "push", cell.image), bounded=True)
    evidence = tuple(
        ArtifactEvidence(
            "remote",
            f"docker://{cell.image}",
            _inspect_registry_digest(provider, request, cell.image),
        )
        for cell in image_plan.cells
    )
    arm.require_complete_arm64_evidence(image_plan, evidence)
    return evidence


def _smoke_arm64_images(
    plan: Amd64ReleasePlan,
    image_plan: ImagePlan,
    provider: object,
    request: object,
    expected_build_evidence: Iterable[ArtifactEvidence],
    *,
    registry_upstream: str,
) -> tuple[ArtifactEvidence, ...]:
    _assert_guarded_source(plan)
    _provider_exec(provider, request, arm.registry_tunnel_command(registry_upstream))
    expected = tuple(expected_build_evidence)
    arm.require_complete_arm64_evidence(image_plan, expected)
    current = tuple(
        ArtifactEvidence(
            "remote",
            f"docker://{cell.image}",
            _inspect_registry_digest(provider, request, cell.image),
        )
        for cell in image_plan.cells
    )
    if _evidence_map(current) != _evidence_map(expected):
        raise RuntimeError("arm64-build evidence changed before smoke")
    digests = {artifact.reference: artifact.digest for artifact in expected}
    checked_servers: list[str] = []
    for smoke in arm.server_smoke_specs(image_plan):
        digest = digests[f"docker://{smoke.cell.image}"]
        _smoke_arm64_server(provider, request, smoke, _pinned_image(smoke.cell.image, digest))
        checked_servers.append(smoke.cell.image)

    watchdog = arm.watchdog_cell(image_plan)
    watchdog_digest = digests[f"docker://{watchdog.image}"]
    watchdog_result = provider.exec_argv(  # type: ignore[attr-defined]
        request,
        (
            "docker",
            "run",
            "--rm",
            "--platform",
            arm.ARM64_PLATFORM,
            "--env",
            "WARM=true",
            "--env",
            "WATCHDOG_CMD=/nanofaas-arm64-smoke-missing-child",
            _pinned_image(watchdog.image, watchdog_digest),
        ),
        env=None,
        cwd=None,
        dry_run=False,
    )
    arm.require_expected_watchdog_exit(
        int(getattr(watchdog_result, "return_code", 0)),
        str(getattr(watchdog_result, "stdout", "")),
        str(getattr(watchdog_result, "stderr", "")),
    )
    marker = _write_json(
        plan.run_dir / "arm64-smoke.json",
        {
            "architecture": arm.ARM64_PLATFORM,
            "images": {
                cell.image: digests[f"docker://{cell.image}"] for cell in image_plan.cells
            },
            "serverHealthChecks": checked_servers,
            "watchdog": {
                "image": watchdog.image,
                "expectedExitCode": 1,
                "expectedFailure": "missing child executable",
            },
        },
    )
    return (marker,)


def _require_image_architecture(
    provider: object,
    request: object,
    reference: str,
    expected: str,
) -> None:
    result = _provider_exec(
        provider,
        request,
        ("docker", "image", "inspect", "--format={{.Architecture}}", reference),
    )
    actual = str(getattr(result, "stdout", "")).strip()
    if actual != expected:
        raise RuntimeError(
            f"image architecture mismatch for {reference}: expected {expected}, got {actual or 'empty'}"
        )


def _smoke_arm64_server(
    provider: object,
    request: object,
    smoke: arm.ServerSmokeSpec,
    image: str,
) -> None:
    try:
        _provider_exec(
            provider,
            request,
            (
                "docker",
                "run",
                "--detach",
                "--rm",
                "--name",
                smoke.container_name,
                "--platform",
                arm.ARM64_PLATFORM,
                "--publish",
                f"127.0.0.1::{smoke.container_port}",
                image,
            ),
        )
        port = _provider_exec(
            provider,
            request,
            ("docker", "port", smoke.container_name, f"{smoke.container_port}/tcp"),
        )
        endpoint = str(getattr(port, "stdout", "")).strip()
        host, separator, value = endpoint.rpartition(":")
        if host != "127.0.0.1" or separator != ":" or not value.isdigit():
            raise RuntimeError(f"invalid ARM64 smoke port mapping: {endpoint or 'empty'}")
        _provider_exec(
            provider,
            request,
            (
                "curl",
                "--fail",
                "--silent",
                "--show-error",
                "--connect-timeout",
                "2",
                "--max-time",
                "2",
                "--retry",
                "59",
                "--retry-delay",
                "2",
                "--retry-all-errors",
                "--retry-max-time",
                "120",
                f"http://{endpoint}{smoke.health_path}",
            ),
        )
    except BaseException:
        try:
            provider.exec_argv(  # type: ignore[attr-defined]
                request,
                ("docker", "rm", "--force", smoke.container_name),
                env=None,
                cwd=None,
                dry_run=False,
            )
        except BaseException:
            pass
        raise
    _provider_exec(
        provider,
        request,
        ("docker", "rm", "--force", smoke.container_name),
    )


def _pinned_image(tagged: str, digest: str) -> str:
    repository, _ = tagged.rsplit(":", 1)
    return f"{repository}@{digest}"


def _verify_generated_build_inputs(plan: Amd64ReleasePlan) -> None:
    expected_inputs = (
        (plan.bake_file, render_bake_json(plan.image_plan)),
        (
            plan.buildkit_config,
            f"[worker.oci]\n  max-parallelism = {plan.builder.max_parallelism}\n",
        ),
    )
    for path, expected in expected_inputs:
        try:
            actual = path.read_text(encoding="utf-8")
        except OSError as error:
            raise ValueError(f"generated build input changed: {path.name}") from error
        if actual != expected:
            raise ValueError(f"generated build input changed: {path.name}")


def _reset_named_builder(
    plan: Amd64ReleasePlan,
    provider: object,
    request: object,
) -> None:
    result = provider.exec_argv(  # type: ignore[attr-defined]
        request,
        ("docker", "buildx", "inspect", plan.builder.name),
        env=None,
        cwd=None,
        dry_run=False,
    )
    if int(getattr(result, "return_code", 0)) == 0:
        _provider_exec(
            provider,
            request,
            ("docker", "buildx", "rm", "--force", plan.builder.name),
        )


def _push_local_images(
    plan: Amd64ReleasePlan,
    provider: object,
    request: object,
    expected_build_evidence: Iterable[ArtifactEvidence],
) -> tuple[ArtifactEvidence, ...]:
    if _evidence_map(_local_image_evidence(plan, provider, request)) != _evidence_map(
        expected_build_evidence
    ):
        raise RuntimeError("amd64-build evidence changed before consumption")
    for cell in plan.image_plan.cells:
        _provider_exec(
            provider,
            request,
            ("docker", "push", cell.image),
            bounded=True,
        )
    return _registry_image_evidence(plan, provider, request)


def _evidence_map(
    artifacts: Iterable[ArtifactEvidence],
) -> dict[tuple[str, str], str]:
    return {(artifact.location, artifact.reference): artifact.digest for artifact in artifacts}


def _local_image_evidence(
    plan: Amd64ReleasePlan,
    provider: object,
    request: object,
) -> tuple[ArtifactEvidence, ...]:
    return tuple(
        ArtifactEvidence(
            "remote",
            f"docker-daemon:{cell.image}",
            _inspect_image_digest(provider, request, cell.image),
        )
        for cell in plan.image_plan.cells
    )


def _registry_image_evidence(
    plan: Amd64ReleasePlan,
    provider: object,
    request: object,
) -> tuple[ArtifactEvidence, ...]:
    return tuple(
        ArtifactEvidence(
            "remote",
            f"docker://{cell.image}",
            _inspect_registry_digest(provider, request, cell.image),
        )
        for cell in plan.image_plan.cells
    )


def _inspect_image_digest(provider: object, request: object, reference: str) -> str:
    result = _provider_exec(
        provider,
        request,
        ("docker", "image", "inspect", "--format={{.Id}}", reference),
    )
    digest = str(getattr(result, "stdout", "")).strip()
    if not digest.startswith("sha256:") or len(digest) != 71:
        raise RuntimeError(f"invalid image digest for {reference}")
    return digest


def _remote_image_digest(
    provider: object,
    request: object,
    location: str,
    reference: str,
    *,
    ghcr_authfile: str | None = None,
) -> str | None:
    if location != "remote":
        return None
    try:
        if reference.startswith("docker-daemon:"):
            return _inspect_image_digest(
                provider, request, reference.removeprefix("docker-daemon:")
            )
        if reference.startswith("docker://"):
            registry_reference = reference.removeprefix("docker://")
            if registry_reference.startswith("ghcr.io/"):
                if ghcr_authfile is None:
                    # fail closed: unverifiable GHCR evidence is never reused
                    return None
                return _inspect_ghcr_digest(
                    provider, request, registry_reference, authfile=ghcr_authfile
                )
            return _inspect_registry_digest(provider, request, registry_reference)
        return None
    except Exception:
        return None


def _inspect_ghcr_digest(
    provider: object,
    request: object,
    reference: str,
    *,
    authfile: str,
) -> str:
    result = _provider_exec(
        provider,
        request,
        (
            "skopeo",
            "inspect",
            f"--authfile={authfile}",
            "--format={{.Digest}}",
            f"docker://{reference}",
        ),
    )
    digest = str(getattr(result, "stdout", "")).strip()
    if not digest.startswith("sha256:") or len(digest) != 71:
        raise RuntimeError(f"invalid registry digest for {reference}")
    return digest


def _inspect_registry_digest(provider: object, request: object, reference: str) -> str:
    result = _provider_exec(
        provider,
        request,
        (
            "skopeo",
            "inspect",
            "--tls-verify=false",
            "--format={{.Digest}}",
            f"docker://{reference}",
        ),
    )
    digest = str(getattr(result, "stdout", "")).strip()
    if not digest.startswith("sha256:") or len(digest) != 71:
        raise RuntimeError(f"invalid registry digest for {reference}")
    return digest


def _registry_digest_map(
    plan: Amd64ReleasePlan,
    artifacts: Iterable[ArtifactEvidence],
) -> dict[str, str]:
    by_reference = {artifact.reference: artifact.digest for artifact in artifacts}
    expected = {f"docker://{cell.image}" for cell in plan.image_plan.cells}
    if set(by_reference) != expected:
        raise ValueError("local-registry-push evidence does not cover the image matrix")
    return {cell.image: by_reference[f"docker://{cell.image}"] for cell in plan.image_plan.cells}


def _run_benchmark(
    plan: Amd64ReleasePlan,
    index: int,
    loadtest_builder: LoadtestBuilder,
    bindings: object,
    fetcher: object | None,
    control_plane_url: str,
    prometheus_url: str,
    provider: object,
    request: object,
    expected_registry_digests: Mapping[str, str],
) -> tuple[ArtifactEvidence, ...]:
    run_dir = plan.run_dir / f"run-{index}"
    workflow = loadtest_builder(
        plan.scenario,
        plan.environment,
        bindings,
        control_plane_url=control_plane_url,
        prometheus_client=HttpPrometheusClient(prometheus_url),
        run_dir=run_dir,
        fetcher=fetcher,
        repo_root=plan.repo_root,
        prebuilt_control_plane_image=_pinned_native_image(
            plan,
            "control-plane",
            provider,
            request,
            expected_registry_digests,
        ),
        prebuilt_function_images={
            function: _pinned_native_image(
                plan,
                _function_target_name(function),
                provider,
                request,
                expected_registry_digests,
            )
            for function in plan.scenario.functions
        },
    )
    workflow.run()  # type: ignore[attr-defined]
    summary = run_dir / "summary.json"
    if not summary.is_file():
        raise RuntimeError(f"load-test summary was not written: {summary}")
    return (ArtifactEvidence("local", str(summary), digest_path(summary)),)


def _native_image(plan: Amd64ReleasePlan, target_name: str) -> str:
    for cell in plan.image_plan.cells:
        if cell.target.name == target_name and cell.flavor == "native":
            return cell.image
    raise ValueError(f"release image plan has no AMD64 native image for {target_name}")


def _pinned_native_image(
    plan: Amd64ReleasePlan,
    target_name: str,
    provider: object,
    request: object,
    expected_registry_digests: Mapping[str, str],
) -> str:
    tagged = _native_image(plan, target_name)
    expected = expected_registry_digests[tagged]
    actual = _inspect_registry_digest(provider, request, tagged)
    if actual != expected:
        raise RuntimeError(f"registry image changed before benchmark: {target_name}")
    repository, _ = tagged.rsplit(":", 1)
    return f"{repository}@{expected}"


def _function_target_name(function_key: str) -> str:
    function = resolve_function_definition(function_key)
    prefix = {"exec": "bash", "java-lite": "java-lite"}.get(function.runtime, function.runtime)
    return f"{prefix}-{function.family}"


def _write_aggregate(
    plan: Amd64ReleasePlan,
    journal: ReleaseJournal,
) -> ArtifactEvidence:
    summaries = tuple(
        _read_verified_local_json(
            journal,
            f"benchmark-{index}",
            plan.run_dir / f"run-{index}" / "summary.json",
        )
        for index in range(1, plan.settings.benchmark_runs + 1)
    )
    aggregate = aggregate_runs(_performance_profile(plan), summaries)
    return _write_json(plan.run_dir / "aggregate.json", asdict(aggregate))


def _evaluate_gate(
    plan: Amd64ReleasePlan,
    journal: ReleaseJournal,
) -> tuple[RegressionDecision, ArtifactEvidence]:
    aggregate = _aggregate_from_payload(
        _read_verified_local_json(
            journal,
            "aggregate",
            plan.run_dir / "aggregate.json",
        )
    )
    baseline_record = newest_comparable_record(
        tuple(
            record
            for record in _release_records(plan.performance_root / "releases")
            # a re-release of this version must not use itself as baseline
            if str(record.get("version")) != plan.version
        ),
        aggregate.profile,
    )
    baseline = _aggregate_from_record(baseline_record) if baseline_record is not None else None
    decision = evaluate_regression(
        aggregate,
        baseline,
        _regression_policy(plan),
        k6_passed=True,
        autoscaling_passed=True,
    )
    artifact = _write_json(plan.run_dir / "regression-decision.json", asdict(decision))
    return decision, artifact


def _performance_profile(plan: Amd64ReleasePlan) -> PerformanceProfile:
    azure = plan.environment.azure
    assert azure is not None
    return PerformanceProfile(
        name=plan.settings.profile,
        provider="azure",
        stack_vm=azure.vm_size,
        loadgen_vm=azure.loadgen_vm_size,
        architecture="amd64",
        flavor="native",
        scenario=plan.settings.scenario.relative_to(plan.repo_root).as_posix(),
    )


def _regression_policy(plan: Amd64ReleasePlan) -> RegressionPolicy:
    return RegressionPolicy(
        throughput_max_loss_percent=plan.settings.throughput_max_loss_percent,
        p95_max_increase_percent=plan.settings.p95_max_increase_percent,
        error_rate_max=plan.settings.error_rate_max,
    )


def _release_records(directory: Path) -> tuple[Mapping[str, Any], ...]:
    if not directory.is_dir():
        return ()
    return tuple(_read_json_file(path) for path in sorted(directory.glob("*.json")))


def _aggregate_from_record(record: Mapping[str, Any]) -> PerformanceAggregate:
    profile = record["profile"]
    if not isinstance(profile, Mapping):
        raise ValueError("release performance profile must be an object")
    metrics = record["aggregates"]
    if not isinstance(metrics, Mapping):
        raise ValueError("release aggregates must be an object")
    return PerformanceAggregate(
        profile=PerformanceProfile(
            name=str(profile["name"]),
            provider=str(profile["provider"]),
            stack_vm=str(profile["stackVm"]),
            loadgen_vm=str(profile["loadgenVm"]),
            architecture=str(profile["architecture"]),
            flavor=str(profile["flavor"]),
            scenario=str(profile["scenario"]),
        ),
        run_count=int(record["runCount"]),
        metrics={str(name): float(value) for name, value in metrics.items()},
    )


def _aggregate_from_payload(payload: Mapping[str, Any]) -> PerformanceAggregate:
    profile = payload["profile"]
    metrics = payload["metrics"]
    if not isinstance(profile, Mapping) or not isinstance(metrics, Mapping):
        raise ValueError("aggregate evidence is invalid")
    return PerformanceAggregate(
        profile=PerformanceProfile(**{str(key): str(value) for key, value in profile.items()}),
        run_count=int(payload["run_count"]),
        metrics={str(key): float(value) for key, value in metrics.items()},
    )


def _decision_from_payload(payload: Mapping[str, Any]) -> RegressionDecision:
    failures = payload.get("failures")
    if not isinstance(failures, list):
        raise ValueError("regression decision evidence is invalid")
    return RegressionDecision(
        passed=bool(payload["passed"]),
        establishes_baseline=bool(payload["establishes_baseline"]),
        failures=tuple(str(value) for value in failures),
    )


def _read_json_file(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"JSON evidence must be an object: {path}")
    return payload


def _write_json(path: Path, payload: Mapping[str, Any]) -> ArtifactEvidence:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, indent=2, sort_keys=True)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)
    return ArtifactEvidence("local", str(path), digest_path(path))


def _provision_release_builder(provider: object, request: object, repo_root: Path) -> None:
    private_key = provider.ssh_private_key_path(request)  # type: ignore[attr-defined]
    adapter = AnsibleAdapter(
        repo_root,
        host_resolver=lambda target, dry_run=False: provider.connection_host(target),  # type: ignore[attr-defined]
        private_key_path=private_key,
    )
    result = adapter.provision_release_builder(request)  # type: ignore[arg-type]
    _require_result(result, "release builder provisioning")


def _provider_exec(
    provider: object,
    request: object,
    argv: tuple[str, ...],
    *,
    cwd: str | None = None,
    env: dict[str, str] | None = None,
    bounded: bool = False,
) -> object:
    if bounded:
        # The SSH executor waits for the exit status before draining output,
        # so a command whose output exceeds the channel window (~2MB)
        # deadlocks: the remote writer blocks and the command never exits.
        # Bulk commands (tests, image builds, pushes) buffer output remotely
        # and return only a 64KB tail — plenty for diagnosis, and stderr is
        # folded into stdout. Commands whose stdout gets parsed must NOT be
        # bounded.
        script = shlex.join(argv)
        argv = (
            "sh",
            "-c",
            "{ " + script + " ; } >/tmp/release-cmd.log 2>&1; "
            "ec=$?; tail -c 65536 /tmp/release-cmd.log; exit $ec",
        )
    result = _retry_on_connection_death(
        lambda: provider.exec_argv(  # type: ignore[attr-defined]
            request, argv, env=env, cwd=cwd, dry_run=False
        ),
        describe="remote command",
    )
    return _require_result(result, "remote release command")


def _provider_transfer_to(
    provider: object,
    request: object,
    *,
    source: Path,
    destination: str,
    action: str,
) -> object:
    result = _retry_on_connection_death(
        lambda: provider.transfer_to(  # type: ignore[attr-defined]
            request, source=source, destination=destination
        ),
        describe=f"transfer {source.name}",
    )
    return _require_result(result, action)


def _retry_on_connection_death(operation: Callable[[], object], *, describe: str) -> object:
    """Run a remote operation, retrying only when the connection dies.

    Every release remote operation is idempotent (tests, digest-pinned
    builds/pushes/transfers, mkdir -p), so a dropped connection mid-operation
    is safe to re-run. A return_code of -1 is paramiko's "channel closed
    without an exit status" sentinel — never a real shell exit code — and a
    raised exception is a connect-time failure; both mean the connection
    failed, not the operation. Real non-zero exits are NOT retried.
    """
    attempts = 4
    for attempt in range(1, attempts + 1):
        last = attempt == attempts
        try:
            result = operation()
        except Exception as error:  # noqa: BLE001 - reconnect on any transport error
            if last:
                raise
            _progress(f"  ⟳ {describe} connection error ({error}); retry {attempt}/{attempts - 1}")
            time.sleep(min(5 * attempt, 30))
            continue
        if int(getattr(result, "return_code", 0)) == -1 and not last:
            _progress(f"  ⟳ {describe} connection dropped; retry {attempt}/{attempts - 1}")
            time.sleep(min(5 * attempt, 30))
            continue
        return result
    raise AssertionError("unreachable")  # pragma: no cover


def _require_result(result: object, action: str) -> object:
    return_code = int(getattr(result, "return_code", 0))
    if return_code != 0:
        detail = str(getattr(result, "stderr", "") or getattr(result, "stdout", ""))
        raise RuntimeError(detail or f"{action} failed (exit {return_code})")
    return result


def _read_yaml(path: Path) -> dict[str, Any]:
    value = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"configuration must be an object: {path}")
    return value


def _release_settings(repo_root: Path, path: Path) -> ReleaseSettings:
    data = _read_yaml(path)
    try:
        if data["schemaVersion"] != 1:
            raise ValueError("release configuration schemaVersion must be 1")
        build = data["build"]
        benchmark = data["benchmark"]
        regression = benchmark["regression"]
        max_parallelism = build["maxParallelism"]
        runs = benchmark["runs"]
        if (
            not isinstance(max_parallelism, int)
            or isinstance(max_parallelism, bool)
            or max_parallelism < 1
        ):
            raise ValueError("build.maxParallelism must be a positive integer")
        if not isinstance(runs, int) or isinstance(runs, bool) or runs != 3:
            raise ValueError("benchmark.runs must be the integer 3")
        scenario = Path(str(benchmark["scenario"]))
        scenario_source = (Path(path).parent / scenario).resolve()
        try:
            scenario_source.relative_to(repo_root)
        except ValueError:
            raise ValueError("benchmark scenario must be a repository-relative file") from None
        if scenario.is_absolute() or not scenario_source.is_file():
            raise ValueError("benchmark scenario must be a repository-relative file")
        throughput_loss = _finite_nonnegative(
            regression["throughputMaxLossPercent"],
            "benchmark.regression.throughputMaxLossPercent",
        )
        p95_increase = _finite_nonnegative(
            regression["p95MaxIncreasePercent"],
            "benchmark.regression.p95MaxIncreasePercent",
        )
        try:
            error_rate = _number(regression["errorRateMax"], "benchmark.regression.errorRateMax")
        except ValueError as error:
            raise ValueError("benchmark.regression.errorRateMax must be between 0 and 1") from error
        if not 0 <= error_rate <= 1:
            raise ValueError("benchmark.regression.errorRateMax must be between 0 and 1")
        return ReleaseSettings(
            max_parallelism=max_parallelism,
            scenario=scenario_source,
            benchmark_runs=runs,
            profile=str(benchmark["profile"]),
            throughput_max_loss_percent=throughput_loss,
            p95_max_increase_percent=p95_increase,
            error_rate_max=error_rate,
        )
    except (KeyError, TypeError) as error:
        raise ValueError("release configuration is incomplete") from error


def _number(value: object, name: str) -> float:
    if isinstance(value, bool):
        raise ValueError(f"{name} must be a finite number")
    try:
        number = float(value)  # type: ignore[arg-type]
    except (TypeError, ValueError) as error:
        raise ValueError(f"{name} must be a finite number") from error
    if not math.isfinite(number):
        raise ValueError(f"{name} must be a finite number")
    return number


def _finite_nonnegative(value: object, name: str) -> float:
    try:
        number = _number(value, name)
    except ValueError as error:
        raise ValueError(f"{name} must be finite nonnegative") from error
    if number < 0:
        raise ValueError(f"{name} must be finite nonnegative")
    return number
