"""ARM64 build and functional-smoke contracts for an Azure release."""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path

from controlplane_tool.images.plan import ImageCell, ImagePlan, build_image_plan
from controlplane_tool.release.state import ArtifactEvidence
from workflow_tasks.tasks.models import CommandTaskSpec


ARM64_PHASES = ("arm64-build", "arm64-smoke")
ARM64_PLATFORM = "linux/arm64"
BINFMT_IMAGE = (
    "tonistiigi/binfmt@sha256:"
    "465d3fdd28d0f2b871ba4b4ec98bd183292e96167f00d9fd40bd249f8632d705"
)
BINFMT_COMMAND = (
    "docker",
    "run",
    "--privileged",
    "--rm",
    BINFMT_IMAGE,
    "--install",
    "arm64",
)


@dataclass(frozen=True, slots=True)
class ServerSmokeSpec:
    cell: ImageCell
    container_name: str
    container_port: int
    health_path: str


def build_arm64_image_plan(
    repo_root: Path,
    version: str,
    *,
    registry: str,
) -> ImagePlan:
    return build_image_plan(
        repo_root,
        version,
        registry=registry,
        architectures=("arm64",),
    )


def arm64_build_commands(
    plan: ImagePlan,
    *,
    builder_name: str,
    remote_bake_file: str,
    remote_source_dir: str,
) -> tuple[CommandTaskSpec, ...]:
    commands = [
        CommandTaskSpec(
            task_id="release.arm64.binfmt",
            summary="Register pinned ARM64 binfmt handler",
            argv=BINFMT_COMMAND,
            role="stack",
            remote_dir=remote_source_dir,
        ),
        CommandTaskSpec(
            task_id="release.arm64.builder",
            summary="Require ARM64 support from the release builder",
            argv=("docker", "buildx", "inspect", builder_name, "--bootstrap"),
            role="stack",
            remote_dir=remote_source_dir,
        ),
    ]
    seen: set[str] = set()
    for cell in plan.bake_cells:
        prerequisite = cell.prerequisite_command
        if prerequisite is None or cell.target.name in seen:
            continue
        seen.add(cell.target.name)
        commands.append(
            CommandTaskSpec(
                task_id=f"release.arm64.prepare.{cell.target.name}",
                summary=f"Prepare {cell.target.name} ARM64 JVM image",
                argv=prerequisite,
                role="stack",
                remote_dir=remote_source_dir,
            )
        )
    commands.append(
        CommandTaskSpec(
            task_id="release.images.bake.arm64",
            summary="Build ARM64 Dockerfile images",
            argv=(
                "docker",
                "buildx",
                "bake",
                "--builder",
                builder_name,
                "--file",
                remote_bake_file,
                "--load",
                "docker-arm64",
            ),
            role="stack",
            remote_dir=remote_source_dir,
        )
    )
    commands.extend(
        CommandTaskSpec(
            task_id=f"release.arm64.native.{cell.target.name}",
            summary=f"Build {cell.target.name} ARM64 native image",
            argv=cell.gradle_command or (),
            role="stack",
            remote_dir=remote_source_dir,
        )
        for cell in plan.gradle_cells
    )
    return tuple(commands)


def require_arm64_builder(output: str) -> None:
    platforms = {
        platform.strip().removesuffix("*")
        for line in output.splitlines()
        if line.startswith("Platforms:")
        for platform in line.removeprefix("Platforms:").split(",")
    }
    if ARM64_PLATFORM not in platforms:
        raise RuntimeError("release Buildx builder does not support linux/arm64")


def require_complete_arm64_evidence(
    plan: ImagePlan,
    artifacts: Iterable[ArtifactEvidence],
) -> None:
    evidence = tuple(artifacts)
    expected = {f"docker://{cell.image}" for cell in plan.cells}
    actual = {
        artifact.reference
        for artifact in evidence
        if artifact.location == "remote"
    }
    if len(evidence) != len(plan.cells) or actual != expected:
        raise RuntimeError(
            f"ARM64 digest evidence does not cover all {len(plan.cells)} planned artifacts"
        )


def server_smoke_specs(plan: ImagePlan) -> tuple[ServerSmokeSpec, ...]:
    cells = tuple(cell for cell in plan.cells if cell.target.name != "watchdog")
    return tuple(
        ServerSmokeSpec(
            cell=cell,
            container_name=f"nanofaas-arm64-smoke-{index}",
            container_port=8081 if cell.target.name == "control-plane" else 8080,
            health_path=(
                "/actuator/health" if cell.target.name == "control-plane" else "/health"
            ),
        )
        for index, cell in enumerate(cells, 1)
    )


def watchdog_cell(plan: ImagePlan) -> ImageCell:
    matches = tuple(cell for cell in plan.cells if cell.target.name == "watchdog")
    if len(matches) != 1:
        raise RuntimeError("ARM64 image plan must contain exactly one watchdog artifact")
    return matches[0]


def require_expected_watchdog_exit(return_code: int, stdout: str, stderr: str) -> None:
    output = f"{stdout}\n{stderr}".lower()
    if "exec format error" in output:
        raise RuntimeError("ARM64 watchdog smoke failed with exec format error")
    if return_code == 0:
        raise RuntimeError("ARM64 watchdog unexpectedly succeeded without its child")
    if return_code != 1 or not (
        "failed to spawn runtime" in output
        and ("no such file or directory" in output or "os error 2" in output)
    ):
        raise RuntimeError("ARM64 watchdog did not produce the expected missing-child exit")
