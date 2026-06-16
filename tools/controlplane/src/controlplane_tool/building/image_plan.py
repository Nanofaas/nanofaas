from __future__ import annotations

import os
import re
import shlex
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Literal

from shellcraft.runners import PlannedCommand

REGISTRY = "ghcr.io"
GH_OWNER = "miciav"
GH_REPO = "nanofaas"
BASE = f"{REGISTRY}/{GH_OWNER}/{GH_REPO}"
OCI_SOURCE = f"https://github.com/{GH_OWNER}/{GH_REPO}"

ImageArch = Literal["amd64", "arm64"]
ImageFlavor = Literal["jvm", "native", "default"]
FailurePolicy = Literal["fail-fast", "keep-going"]

DEFAULT_ARCHES: tuple[ImageArch, ImageArch] = ("amd64", "arm64")
DEFAULT_FLAVORS: tuple[Literal["jvm"], Literal["native"]] = ("jvm", "native")


@dataclass(frozen=True)
class ImageTargetSpec:
    name: str
    group: str
    kind: Literal["gradle", "docker"]
    flavors: tuple[ImageFlavor, ...]
    gradle_task: str | None = None
    image_param: str | None = None
    dockerfile: str | None = None
    context: str = "."
    jvm_artifact_tasks: tuple[str, ...] = ()
    profile_aware: bool = False


@dataclass(frozen=True)
class ImageMatrixCell:
    target: str
    arch: ImageArch
    flavor: ImageFlavor
    image: str
    build_command: PlannedCommand
    push_command: PlannedCommand | None


@dataclass(frozen=True)
class ImageMatrixPlan:
    tag: str
    cells: tuple[ImageMatrixCell, ...]


def _target(
    name: str,
    group: str,
    kind: Literal["gradle", "docker"],
    flavors: tuple[ImageFlavor, ...],
    *,
    gradle_task: str | None = None,
    image_param: str | None = None,
    dockerfile: str | None = None,
    context: str = ".",
    jvm_artifact_tasks: tuple[str, ...] = (),
    profile_aware: bool = False,
) -> ImageTargetSpec:
    return ImageTargetSpec(
        name=name,
        group=group,
        kind=kind,
        flavors=flavors,
        gradle_task=gradle_task,
        image_param=image_param,
        dockerfile=dockerfile,
        context=context,
        jvm_artifact_tasks=jvm_artifact_tasks,
        profile_aware=profile_aware,
    )


IMAGE_TARGETS: dict[str, ImageTargetSpec] = {
    target.name: target
    for target in (
        _target(
            "control-plane",
            "Core",
            "gradle",
            ("jvm", "native"),
            gradle_task=":control-plane:bootBuildImage",
            image_param="controlPlaneImage",
            dockerfile="platform/control-plane/Dockerfile",
            context="platform/control-plane",
            jvm_artifact_tasks=(":control-plane:bootJar",),
            profile_aware=True,
        ),
        _target(
            "function-runtime",
            "Core",
            "gradle",
            ("jvm", "native"),
            gradle_task=":function-runtime:bootBuildImage",
            image_param="functionRuntimeImage",
            dockerfile="platform/function-runtime/Dockerfile",
            context="platform/function-runtime",
            jvm_artifact_tasks=(":function-runtime:bootJar",),
        ),
        _target(
            "java-word-stats",
            "Java Functions",
            "gradle",
            ("jvm", "native"),
            gradle_task=":functions:java:word-stats:bootBuildImage",
            image_param="functionImage",
            dockerfile="functions/java/word-stats/Dockerfile",
            context="functions/java/word-stats",
            jvm_artifact_tasks=(":functions:java:word-stats:bootJar",),
        ),
        _target(
            "java-json-transform",
            "Java Functions",
            "gradle",
            ("jvm", "native"),
            gradle_task=":functions:java:json-transform:bootBuildImage",
            image_param="functionImage",
            dockerfile="functions/java/json-transform/Dockerfile",
            context="functions/java/json-transform",
            jvm_artifact_tasks=(":functions:java:json-transform:bootJar",),
        ),
        _target(
            "java-lite-word-stats",
            "Java Lite Functions",
            "docker",
            ("native",),
            dockerfile="functions/java/word-stats-lite/Dockerfile",
        ),
        _target(
            "java-lite-json-transform",
            "Java Lite Functions",
            "docker",
            ("native",),
            dockerfile="functions/java/json-transform-lite/Dockerfile",
        ),
        _target("go-word-stats", "Go Functions", "docker", ("default",), dockerfile="functions/go/word-stats/Dockerfile"),
        _target("go-json-transform", "Go Functions", "docker", ("default",), dockerfile="functions/go/json-transform/Dockerfile"),
        _target("python-word-stats", "Python Functions", "docker", ("default",), dockerfile="functions/python/word-stats/Dockerfile"),
        _target("python-json-transform", "Python Functions", "docker", ("default",), dockerfile="functions/python/json-transform/Dockerfile"),
        _target(
            "javascript-word-stats",
            "JavaScript Functions",
            "docker",
            ("default",),
            dockerfile="functions/javascript/word-stats/Dockerfile",
        ),
        _target(
            "javascript-json-transform",
            "JavaScript Functions",
            "docker",
            ("default",),
            dockerfile="functions/javascript/json-transform/Dockerfile",
        ),
        _target("watchdog", "Runtime", "docker", ("default",), dockerfile="watchdog/Dockerfile", context="watchdog"),
        _target("bash-word-stats", "Bash Functions", "docker", ("default",), dockerfile="functions/bash/word-stats/Dockerfile"),
        _target("bash-json-transform", "Bash Functions", "docker", ("default",), dockerfile="functions/bash/json-transform/Dockerfile"),
    )
}


def image_reference(name: str, tag: str, arch: ImageArch, flavor: ImageFlavor) -> str:
    suffix = f"{tag}-{arch}" if flavor == "default" else f"{tag}-{arch}-{flavor}"
    return f"{BASE}/{name}:{suffix}"


def select_image_targets(only: str) -> list[str]:
    if only.strip().lower() == "all":
        return sorted(IMAGE_TARGETS)
    names = [name.strip() for name in only.split(",") if name.strip()]
    unknown = [name for name in names if name not in IMAGE_TARGETS]
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


def _platform(arch: ImageArch) -> str:
    return f"linux/{arch}"


def _selected_flavors(
    target: ImageTargetSpec,
    requested: Sequence[Literal["jvm", "native"]],
) -> tuple[ImageFlavor, ...]:
    if target.flavors == ("default",):
        return ("default",)
    requested_set = set(requested)
    return tuple(flavor for flavor in target.flavors if flavor in requested_set)


def _label_arg() -> str:
    return f"org.opencontainers.image.source={OCI_SOURCE}"


def _shell_join(parts: Sequence[str]) -> str:
    return " ".join(shlex.quote(part) for part in parts)


def _docker_build_command(target: ImageTargetSpec, image: str, arch: ImageArch) -> list[str]:
    if target.dockerfile is None:
        raise ValueError(f"Image target {target.name} does not define a Dockerfile")
    return [
        "docker",
        "build",
        "--platform",
        _platform(arch),
        "--label",
        _label_arg(),
        "-t",
        image,
        "-f",
        target.dockerfile,
        target.context,
    ]


def _plan_native_gradle_build(
    repo_root: Path,
    target: ImageTargetSpec,
    image: str,
    arch: ImageArch,
) -> PlannedCommand:
    if target.gradle_task is None or target.image_param is None:
        raise ValueError(f"Image target {target.name} does not define a Gradle image task")
    command = [
        "./gradlew",
        target.gradle_task,
        f"-P{target.image_param}={image}",
        f"-PimagePlatform={_platform(arch)}",
    ]
    if target.profile_aware:
        command.append("-PcontrolPlaneModules=all")
    if arch == "arm64":
        command.extend(
            [
                "-PimageBuilder=dashaun/builder:tiny",
                "-PimageRunImage=paketobuildpacks/run-jammy-tiny:latest",
            ]
        )
    return PlannedCommand(
        command=command,
        cwd=Path(repo_root),
        env={"NATIVE_IMAGE_BUILD_ARGS": resolve_native_image_build_args(), "BP_OCI_SOURCE": OCI_SOURCE},
    )


def _plan_jvm_docker_build(
    repo_root: Path,
    target: ImageTargetSpec,
    image: str,
    arch: ImageArch,
) -> PlannedCommand:
    if not target.jvm_artifact_tasks:
        raise ValueError(f"Target {target.name} does not define JVM artifact tasks")
    gradle_command = ["./gradlew", *target.jvm_artifact_tasks]
    if target.profile_aware:
        gradle_command.append("-PcontrolPlaneModules=all")
    docker_command = _docker_build_command(target, image, arch)
    return PlannedCommand(
        command=["bash", "-lc", f"{_shell_join(gradle_command)} && {_shell_join(docker_command)}"],
        cwd=Path(repo_root),
        env={},
    )


def _plan_docker_build(
    repo_root: Path,
    target: ImageTargetSpec,
    image: str,
    arch: ImageArch,
) -> PlannedCommand:
    return PlannedCommand(command=_docker_build_command(target, image, arch), cwd=Path(repo_root), env={})


def _plan_build(
    repo_root: Path,
    target: ImageTargetSpec,
    image: str,
    arch: ImageArch,
    flavor: ImageFlavor,
) -> PlannedCommand:
    if flavor == "jvm":
        return _plan_jvm_docker_build(repo_root, target, image, arch)
    if flavor == "native" and target.kind == "gradle":
        return _plan_native_gradle_build(repo_root, target, image, arch)
    return _plan_docker_build(repo_root, target, image, arch)


def _plan_push(repo_root: Path, image: str, *, runtime: str) -> PlannedCommand:
    return PlannedCommand(command=[runtime, "push", image], cwd=Path(repo_root), env={})


def plan_image_matrix(
    *,
    repo_root: Path,
    targets: Sequence[str],
    tag: str,
    arches: Sequence[ImageArch],
    flavors: Sequence[Literal["jvm", "native"]],
    push: bool,
    runtime: str,
) -> ImageMatrixPlan:
    cells: list[ImageMatrixCell] = []
    for name in targets:
        target = IMAGE_TARGETS[name]
        for arch in arches:
            for flavor in _selected_flavors(target, flavors):
                image = image_reference(name, tag, arch, flavor)
                cells.append(
                    ImageMatrixCell(
                        target=name,
                        arch=arch,
                        flavor=flavor,
                        image=image,
                        build_command=_plan_build(repo_root, target, image, arch, flavor),
                        push_command=_plan_push(repo_root, image, runtime=runtime) if push else None,
                    )
                )
    return ImageMatrixPlan(tag=tag, cells=tuple(cells))
