from __future__ import annotations

import os
import re
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path

from shellcraft.runners import CommandRunner, PlannedCommand

REGISTRY = "ghcr.io"
GH_OWNER = "miciav"
GH_REPO = "nanofaas"
BASE = f"{REGISTRY}/{GH_OWNER}/{GH_REPO}"
OCI_SOURCE = f"https://github.com/{GH_OWNER}/{GH_REPO}"


@dataclass(frozen=True)
class ImageTarget:
    name: str
    kind: str  # "gradle" | "docker"
    group: str
    task: str | None = None
    image_param: str | None = None
    dockerfile: str | None = None
    context: str = "."
    profile_aware: bool = False  # control-plane reuses the profile-aware building.image flow (deferred)


def _gradle(name: str, task: str, image_param: str, group: str, *, profile_aware: bool = False) -> ImageTarget:
    return ImageTarget(name=name, kind="gradle", group=group, task=task,
                       image_param=image_param, profile_aware=profile_aware)


def _docker(name: str, dockerfile: str, group: str) -> ImageTarget:
    return ImageTarget(name=name, kind="docker", group=group, dockerfile=dockerfile)


IMAGE_MATRIX: dict[str, ImageTarget] = {t.name: t for t in [
    _gradle("control-plane", ":control-plane:bootBuildImage", "controlPlaneImage", "Core", profile_aware=True),
    _gradle("function-runtime", ":function-runtime:bootBuildImage", "functionRuntimeImage", "Core"),
    _gradle("java-word-stats", ":examples:java:word-stats:bootBuildImage", "functionImage", "Java Functions"),
    _gradle("java-json-transform", ":examples:java:json-transform:bootBuildImage", "functionImage", "Java Functions"),
    _docker("java-lite-word-stats", "examples/java/word-stats-lite/Dockerfile", "Java Lite Functions"),
    _docker("java-lite-json-transform", "examples/java/json-transform-lite/Dockerfile", "Java Lite Functions"),
    _docker("go-word-stats", "examples/go/word-stats/Dockerfile", "Go Functions"),
    _docker("go-json-transform", "examples/go/json-transform/Dockerfile", "Go Functions"),
    _docker("python-word-stats", "examples/python/word-stats/Dockerfile", "Python Functions"),
    _docker("python-json-transform", "examples/python/json-transform/Dockerfile", "Python Functions"),
    _docker("javascript-word-stats", "examples/javascript/word-stats/Dockerfile", "JavaScript Functions"),
    _docker("javascript-json-transform", "examples/javascript/json-transform/Dockerfile", "JavaScript Functions"),
    _docker("watchdog", "watchdog/Dockerfile", "Runtime"),
    _docker("bash-word-stats", "examples/bash/word-stats/Dockerfile", "Bash Functions"),
    _docker("bash-json-transform", "examples/bash/json-transform/Dockerfile", "Bash Functions"),
]}


def select_targets(only: str) -> list[str]:
    if only.strip().lower() == "all":
        return sorted(IMAGE_MATRIX)
    names = [n.strip() for n in only.split(",") if n.strip()]
    unknown = [n for n in names if n not in IMAGE_MATRIX]
    if unknown:
        raise ValueError(f"Unknown image target(s): {', '.join(unknown)}")
    return names


def resolve_current_version(repo_root: Path) -> str:
    content = (Path(repo_root) / "build.gradle").read_text(encoding="utf-8")
    match = re.search(r"version\s*=\s*'([^']+)'", content)
    if not match:
        raise ValueError("Could not find version in build.gradle")
    return match.group(1)


def resolve_native_active_processors() -> str:
    raw = os.getenv("NATIVE_ACTIVE_PROCESSORS", "").strip()
    if raw:
        try:
            parsed = int(raw)
            if parsed >= 1:
                return str(parsed)
        except ValueError:
            pass
    detected = os.cpu_count() or 4
    return str(detected if detected >= 1 else 4)


def resolve_native_image_build_args() -> str:
    explicit = os.getenv("NATIVE_IMAGE_BUILD_ARGS", "").strip()
    if explicit:
        return explicit
    xmx = os.getenv("NATIVE_IMAGE_XMX", "8g").strip() or "8g"
    return f"-H:+AddAllCharsets -J-Xmx{xmx} -J-XX:ActiveProcessorCount={resolve_native_active_processors()}"


def image_reference(name: str, tag: str, arch: str, *, use_arch_suffix: bool) -> str:
    suffix = "" if (arch == "multi" or not use_arch_suffix) else f"-{arch}"
    return f"{BASE}/{name}:{tag}{suffix}"


def _platform(arch: str) -> str:
    return "linux/arm64,linux/amd64" if arch == "multi" else f"linux/{arch}"


def plan_build_command(repo_root: Path, name: str, full_image: str, arch: str) -> PlannedCommand:
    target = IMAGE_MATRIX[name]
    repo_root = Path(repo_root)
    if target.kind == "gradle":
        command = [
            "./gradlew", target.task,
            f"-P{target.image_param}={full_image}",
            f"-PimagePlatform={_platform(arch)}",
        ]
        if target.profile_aware:
            # Mirror `controlplane-tool image --profile all`: select all optional
            # control-plane modules into the image (a bare bootBuildImage would use
            # gradle's defaults).
            command.append("-PcontrolPlaneModules=all")
        if arch == "arm64":
            command += [
                "-PimageBuilder=dashaun/builder:tiny",
                "-PimageRunImage=paketobuildpacks/run-jammy-tiny:latest",
            ]
        env = {"NATIVE_IMAGE_BUILD_ARGS": resolve_native_image_build_args(), "BP_OCI_SOURCE": OCI_SOURCE}
        return PlannedCommand(command=command, cwd=repo_root, env=env)

    label = f"org.opencontainers.image.source={OCI_SOURCE}"
    if arch == "multi":
        command = ["docker", "buildx", "build", "--platform", _platform(arch),
                   "--label", label, "-t", full_image, "-f", target.dockerfile, target.context]
    else:
        command = ["docker", "build", "--platform", _platform(arch),
                   "--label", label, "-t", full_image, "-f", target.dockerfile, target.context]
    return PlannedCommand(command=command, cwd=repo_root, env={})


def plan_push_command(repo_root: Path, full_image: str, *, runtime: str = "docker") -> PlannedCommand:
    return PlannedCommand(command=[runtime, "push", full_image], cwd=Path(repo_root), env={})


def run_image_matrix(
    *,
    runner: CommandRunner,
    repo_root: Path,
    targets: Sequence[str],
    tag: str,
    arch: str,
    use_arch_suffix: bool,
    push: bool,
    runtime: str,
    dry_run: bool,
) -> list[str]:
    """Build (and optionally push) each target. Returns the built image references."""
    built: list[str] = []
    for name in targets:
        full_image = image_reference(name, tag, arch, use_arch_suffix=use_arch_suffix)
        build = plan_build_command(repo_root, name, full_image, arch)
        result = build.run(runner, dry_run=dry_run)
        if result.return_code != 0:
            raise RuntimeError(f"build failed for {name} (exit {result.return_code})")
        built.append(full_image)
        if push:
            push_cmd = plan_push_command(repo_root, full_image, runtime=runtime)
            push_result = push_cmd.run(runner, dry_run=dry_run)
            if push_result.return_code != 0:
                raise RuntimeError(f"push failed for {full_image} (exit {push_result.return_code})")
    return built
