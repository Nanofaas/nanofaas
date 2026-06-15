from __future__ import annotations

from dataclasses import dataclass
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
        _target("watchdog", "Runtime", "docker", ("default",), dockerfile="watchdog/Dockerfile"),
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
