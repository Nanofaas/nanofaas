from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Literal

from controlplane_tool.functions.catalog import FunctionDefinition, list_functions
from controlplane_tool.release.versioning import normalize_version


ImageArchitecture = Literal["amd64", "arm64"]
ImageFlavor = Literal["jvm", "native", "default"]
BuildKind = Literal["bake", "gradle"]

DEFAULT_ARCHITECTURES: tuple[ImageArchitecture, ...] = ("amd64", "arm64")
DEFAULT_REGISTRY = "localhost:5000/nanofaas"


@dataclass(frozen=True)
class ImageTarget:
    name: str
    flavors: tuple[ImageFlavor, ...]
    dockerfile: Path
    context: Path
    native_gradle_task: str | None = None
    native_image_property: str | None = None
    native_extra_arguments: tuple[str, ...] = ()
    jvm_prerequisite_arguments: tuple[str, ...] = ()


@dataclass(frozen=True)
class ImageCell:
    target: ImageTarget
    architecture: ImageArchitecture
    flavor: ImageFlavor
    tag: str
    image: str
    build_kind: BuildKind

    @property
    def platform(self) -> str:
        return f"linux/{self.architecture}"

    @property
    def gradle_command(self) -> tuple[str, ...] | None:
        if self.build_kind != "gradle":
            return None
        task = self.target.native_gradle_task
        image_property = self.target.native_image_property
        if task is None or image_property is None:
            raise ValueError(f"missing Gradle image metadata for {self.target.name}")
        architecture_arguments = (
            (
                "-PimageBuilder=dashaun/builder:tiny",
                "-PimageRunImage=paketobuildpacks/run-jammy-tiny:latest",
            )
            if self.architecture == "arm64"
            else ()
        )
        return (
            "./gradlew",
            task,
            f"-P{image_property}={self.image}",
            f"-PimagePlatform={self.platform}",
            *self.target.native_extra_arguments,
            *architecture_arguments,
        )

    @property
    def prerequisite_command(self) -> tuple[str, ...] | None:
        if self.build_kind != "bake" or self.flavor != "jvm":
            return None
        return ("./gradlew", *self.target.jvm_prerequisite_arguments)


@dataclass(frozen=True)
class ImagePlan:
    version: str
    registry: str
    targets: tuple[ImageTarget, ...]
    cells: tuple[ImageCell, ...]

    @property
    def target_names(self) -> frozenset[str]:
        return frozenset(target.name for target in self.targets)

    @property
    def bake_cells(self) -> tuple[ImageCell, ...]:
        return tuple(cell for cell in self.cells if cell.build_kind == "bake")

    @property
    def gradle_cells(self) -> tuple[ImageCell, ...]:
        return tuple(cell for cell in self.cells if cell.build_kind == "gradle")


def build_image_plan(
    repo_root: Path,
    version: str,
    *,
    registry: str = DEFAULT_REGISTRY,
    selectors: Sequence[str] = (),
    architectures: Sequence[ImageArchitecture] = DEFAULT_ARCHITECTURES,
    flavors: Sequence[ImageFlavor] = ("jvm", "native", "default"),
) -> ImagePlan:
    """Expand the live repository catalog into immutable image build cells."""
    repo_root = Path(repo_root).resolve()
    _, version_tag = normalize_version(version)
    registry = registry.rstrip("/")
    if not registry:
        raise ValueError("image registry must not be empty")

    targets = _select_targets(_all_targets(repo_root), selectors)
    selected_flavors = frozenset(flavors)
    cells = tuple(
        _cell(target, architecture, flavor, version_tag, registry)
        for architecture in architectures
        for target in targets
        for flavor in target.flavors
        if flavor in selected_flavors
    )
    return ImagePlan(version=version_tag, registry=registry, targets=targets, cells=cells)


def _all_targets(repo_root: Path) -> tuple[ImageTarget, ...]:
    targets = [
        ImageTarget(
            name="control-plane",
            flavors=("jvm", "native"),
            dockerfile=Path("platform/control-plane/Dockerfile"),
            context=Path("platform/control-plane"),
            native_gradle_task=":control-plane:bootBuildImage",
            native_image_property="controlPlaneImage",
            native_extra_arguments=("-PcontrolPlaneModules=all",),
            jvm_prerequisite_arguments=(
                ":control-plane:bootJar",
                "-PcontrolPlaneModules=all",
            ),
        ),
        ImageTarget(
            name="java-warm-echo",
            flavors=("jvm", "native"),
            dockerfile=Path("services/java/warm-echo/Dockerfile"),
            context=Path("services/java/warm-echo"),
            native_gradle_task=":services:java:warm-echo:bootBuildImage",
            native_image_property="warmEchoImage",
            jvm_prerequisite_arguments=(":services:java:warm-echo:bootJar",),
        ),
        ImageTarget(
            name="watchdog",
            flavors=("default",),
            dockerfile=Path("runtimes/watchdog/Dockerfile"),
            context=Path("runtimes/watchdog"),
        ),
        *(
            _function_target(repo_root, function)
            for function in list_functions()
            if function.example_dir is not None
        ),
    ]
    targets.sort(key=lambda target: target.name)
    _validate_targets(repo_root, targets)
    return tuple(targets)


def _function_target(repo_root: Path, function: FunctionDefinition) -> ImageTarget:
    if function.example_dir is None:
        raise ValueError(f"function has no source directory: {function.key}")
    source_dir = function.example_dir.resolve().relative_to(repo_root)
    prefix = {"exec": "bash", "java-lite": "java-lite"}.get(
        function.runtime, function.runtime
    )
    name = f"{prefix}-{function.family}"

    if function.runtime == "java":
        return ImageTarget(
            name=name,
            flavors=("jvm", "native"),
            dockerfile=source_dir / "Dockerfile",
            context=source_dir,
            native_gradle_task=f":functions:java:{function.family}:bootBuildImage",
            native_image_property="functionImage",
            jvm_prerequisite_arguments=(
                f":functions:java:{function.family}:bootJar",
            ),
        )
    return ImageTarget(
        name=name,
        flavors=("native",) if function.runtime == "java-lite" else ("default",),
        dockerfile=source_dir / "Dockerfile",
        context=Path("."),
    )


def _validate_targets(repo_root: Path, targets: Sequence[ImageTarget]) -> None:
    names = [target.name for target in targets]
    duplicates = sorted(name for name in set(names) if names.count(name) > 1)
    if duplicates:
        raise ValueError(f"duplicate image target: {', '.join(duplicates)}")
    missing = sorted(
        target.dockerfile.as_posix()
        for target in targets
        if not (repo_root / target.dockerfile).is_file()
    )
    if missing:
        raise FileNotFoundError(f"missing image Dockerfile: {', '.join(missing)}")


def _select_targets(
    targets: tuple[ImageTarget, ...], selectors: Sequence[str]
) -> tuple[ImageTarget, ...]:
    if not selectors:
        return targets
    requested = frozenset(selectors)
    known = {target.name for target in targets}
    unknown = sorted(requested - known)
    if unknown:
        raise ValueError(f"unknown image target: {', '.join(unknown)}")
    return tuple(target for target in targets if target.name in requested)


def _cell(
    target: ImageTarget,
    architecture: ImageArchitecture,
    flavor: ImageFlavor,
    version_tag: str,
    registry: str,
) -> ImageCell:
    tag = (
        f"{version_tag}-{architecture}"
        if flavor == "default"
        else f"{version_tag}-{architecture}-{flavor}"
    )
    return ImageCell(
        target=target,
        architecture=architecture,
        flavor=flavor,
        tag=tag,
        image=f"{registry}/{target.name}:{tag}",
        build_kind="gradle" if flavor == "native" and target.native_gradle_task else "bake",
    )
