# Image Matrix Publish TUI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current `images` command with a complete, observable image matrix publisher that builds and pushes all current components across `amd64`/`arm64` and official JVM/native flavors, with explicit tags and a dedicated TUI entry.

**Architecture:** Introduce a pure image matrix planner that expands target, architecture, and flavor into executable cells with explicit image references. Keep planning separate from execution so CLI dry-run, TUI live progress, CI, and e2e consumers can share the same semantics. Remove old compatibility flags and delete the release manager after CI/e2e consumers are moved.

**Tech Stack:** Python 3.11+, Typer CLI, Rich/questionary TUI, shellcraft `PlannedCommand`/`CommandRunner`, Gradle/Spring Boot buildpacks, Docker/buildx, pytest, GitNexus.

---

## Scope Decisions

- No backward compatibility is required for the old `images` API.
- Remove `--arch-suffix`.
- Remove `--arch multi` as a primary build mode.
- `--arch` becomes `amd64 | arm64 | all`.
- `--flavor` becomes `jvm | native | all`.
- JVM images are official release artifacts and must be published.
- Native images are official release artifacts and must be published.
- `java-lite-*` is native-only.
- Non-Java targets do not receive `jvm` or `native` suffixes.
- The TUI entry is required and must live in the Build menu with a clear name.
- The release manager is removed at the end; it is not migrated as a supported consumer.
- Consumers, especially e2e/scenarios and CI/GitOps, are fixed as explicit phases of this plan.

## Tag Contract

Use `TAG` below as the user-provided base tag.

| Target family | Published tags |
| --- | --- |
| `control-plane`, `function-runtime`, `java-word-stats`, `java-json-transform` | `TAG-amd64-jvm`, `TAG-arm64-jvm`, `TAG-amd64-native`, `TAG-arm64-native` |
| `java-lite-word-stats`, `java-lite-json-transform` | `TAG-amd64-native`, `TAG-arm64-native` |
| `go-*`, `python-*`, `javascript-*`, `bash-*`, `watchdog` | `TAG-amd64`, `TAG-arm64` |

Expected total with current components:

- Core: 2 targets x 2 arch x 2 flavor = 8 tags
- Java Spring functions: 2 targets x 2 arch x 2 flavor = 8 tags
- Java-lite functions: 2 targets x 2 arch x 1 flavor = 4 tags
- Non-Java/default targets: 9 targets x 2 arch = 18 tags
- Total: 38 tags

## File Structure

### New Files

- `tools/controlplane/src/controlplane_tool/building/image_plan.py`
  - Owns matrix data types, tag generation, applicability rules, and pure command planning.
  - Does not execute commands.
  - Exports `ImageArch`, `ImageFlavor`, `ImageMatrixCell`, `ImageMatrixPlan`, `ImageTargetSpec`, `plan_image_matrix`, and `select_image_targets`.

- `tools/controlplane/src/controlplane_tool/building/image_workflow.py`
  - Owns execution of `ImageMatrixPlan`.
  - Emits workflow events for build/push state.
  - Provides one runner function shared by CLI and TUI.

- `tools/controlplane/tests/test_image_plan.py`
  - Unit tests for target catalog, tag generation, flavor applicability, command planning, and dry-run shape.

- `tools/controlplane/tests/test_image_workflow.py`
  - Unit tests for sequential execution, failure handling, dry-run behavior, and emitted events.

### Modified Files

- `tools/controlplane/src/controlplane_tool/building/image_matrix.py`
  - Either remove after call sites are migrated, or leave only a thin import shim if tests still need a transition during task execution.
  - Final state should not keep old `--arch-suffix` or `multi` behavior.

- `tools/controlplane/src/controlplane_tool/cli/commands.py`
  - Replace the old `images_command` implementation with the new planner/executor API.
  - CLI surface becomes `--tag`, `--only`, `--arch`, `--flavor`, `--push/--no-push`, `--runtime`, `--dry-run`, `--fail-fast/--keep-going`.

- `tools/controlplane/src/controlplane_tool/tui/app.py`
  - Add a Build menu entry named `publish-images — build & publish image matrix`.
  - Add a TUI workflow method that prompts for tag, arch, flavor, targets, push, dry-run, and failure policy.

- `tools/controlplane/tests/test_images_command.py`
  - Replace old contract tests for `--arch-suffix` and `--arch multi` with new CLI tests.

- `tools/controlplane/tests/test_tui_choices.py`
  - Add tests proving the Build menu contains the new entry and routes to the image matrix workflow.

- `tools/controlplane/tests/test_tui_product_contract.py`
  - Add or update product contract checks for the required TUI entry name and description.

- `platform/control-plane/Dockerfile`
  - Update comments from local-dev-only to official JVM image path.

- `platform/function-runtime/Dockerfile`
  - Add explicit official JVM image comments.

- `functions/java/word-stats/Dockerfile`
  - Update comments from local-dev-only to official JVM image path.

- `functions/java/json-transform/Dockerfile`
  - Update comments from local-dev-only to official JVM image path.

- `.github/workflows/gitops.yml`
  - Replace manual image build/push steps with the new image matrix command or a matrix that uses the same command semantics.

- `tools/workflow-tasks/src/workflow_tasks/components/images.py`
  - Migrate e2e image planning only if scenarios need release tag semantics.
  - Keep local `:e2e` tags for VM scenarios when they are intentionally local test artifacts.

- `tools/controlplane/src/controlplane_tool/e2e/container_local_runner.py`
  - Audit and adjust any image naming/build assumptions that conflict with official JVM/native publishing.

- `tools/controlplane/src/controlplane_tool/scenario/scenario_tasks.py`
  - Audit and adjust any image build scripts that should share planner semantics.

- `scripts/release-manager/release.py`
  - Delete.

- `scripts/release-manager/README.md`
  - Delete.

- `scripts/tests/test_release_manager_javascript_sdk.py`
  - Delete or replace with CI/images tests if it still covers live behavior.

- Docs that mention release manager or old images syntax:
  - Update live docs only. Do not edit historical `docs/superpowers/specs/*` or old plan archives unless a test requires it.

## GitNexus Requirements

Before modifying any function, method, or class, run `gitnexus_impact` on the target symbol and record the blast radius in the implementation notes or commit message.

Required impact checks before edits:

```text
gitnexus_impact({target: "ImageTarget", direction: "upstream", repo: "mcFaas"})
gitnexus_impact({target: "image_reference", direction: "upstream", repo: "mcFaas"})
gitnexus_impact({target: "plan_build_command", direction: "upstream", repo: "mcFaas"})
gitnexus_impact({target: "run_image_matrix", direction: "upstream", repo: "mcFaas"})
gitnexus_impact({target: "images_command", direction: "upstream", repo: "mcFaas"})
gitnexus_impact({target: "NanofaasTUI._build_menu", direction: "upstream", repo: "mcFaas"})
```

Before final completion:

```text
gitnexus_detect_changes({scope: "all", repo: "mcFaas"})
```

---

### Task 1: Pure Image Matrix Model and Tagging

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/building/image_plan.py`
- Create: `tools/controlplane/tests/test_image_plan.py`
- Modify: `tools/controlplane/src/controlplane_tool/building/__init__.py` only if needed for package exports

- [ ] **Step 1: Run GitNexus impact checks**

Run these before editing existing symbols:

```text
gitnexus_impact({target: "ImageTarget", direction: "upstream", repo: "mcFaas"})
gitnexus_impact({target: "image_reference", direction: "upstream", repo: "mcFaas"})
gitnexus_impact({target: "plan_build_command", direction: "upstream", repo: "mcFaas"})
gitnexus_impact({target: "run_image_matrix", direction: "upstream", repo: "mcFaas"})
```

Expected: LOW or MEDIUM risk, with direct callers mostly limited to `images_command` and image tests. If risk is HIGH or CRITICAL, stop and report the blast radius before editing.

- [ ] **Step 2: Write failing tests for target/flavor applicability**

Create `tools/controlplane/tests/test_image_plan.py` with this initial content:

```python
from __future__ import annotations

from pathlib import Path

import pytest

from controlplane_tool.building.image_plan import (
    DEFAULT_ARCHES,
    ImageArch,
    ImageFlavor,
    image_reference,
    plan_image_matrix,
    select_image_targets,
)


def test_select_targets_all_returns_current_catalog() -> None:
    assert set(select_image_targets("all")) == {
        "control-plane",
        "function-runtime",
        "java-word-stats",
        "java-json-transform",
        "java-lite-word-stats",
        "java-lite-json-transform",
        "go-word-stats",
        "go-json-transform",
        "python-word-stats",
        "python-json-transform",
        "javascript-word-stats",
        "javascript-json-transform",
        "bash-word-stats",
        "bash-json-transform",
        "watchdog",
    }


def test_select_targets_csv_preserves_order() -> None:
    assert select_image_targets("watchdog,control-plane") == ["watchdog", "control-plane"]


def test_select_targets_rejects_unknown_target() -> None:
    with pytest.raises(ValueError, match="Unknown image target"):
        select_image_targets("watchdog,nope")


def test_default_arches_are_amd64_then_arm64() -> None:
    assert DEFAULT_ARCHES == ("amd64", "arm64")
```

- [ ] **Step 3: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests/test_image_plan.py -q
```

Expected: FAIL during import with `ModuleNotFoundError: No module named 'controlplane_tool.building.image_plan'`.

- [ ] **Step 4: Create the planner module with catalog and selectors**

Create `tools/controlplane/src/controlplane_tool/building/image_plan.py`:

```python
from __future__ import annotations

import os
import re
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


IMAGE_TARGETS: dict[str, ImageTargetSpec] = {target.name: target for target in (
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
    _target("javascript-word-stats", "JavaScript Functions", "docker", ("default",), dockerfile="functions/javascript/word-stats/Dockerfile"),
    _target("javascript-json-transform", "JavaScript Functions", "docker", ("default",), dockerfile="functions/javascript/json-transform/Dockerfile"),
    _target("watchdog", "Runtime", "docker", ("default",), dockerfile="watchdog/Dockerfile"),
    _target("bash-word-stats", "Bash Functions", "docker", ("default",), dockerfile="functions/bash/word-stats/Dockerfile"),
    _target("bash-json-transform", "Bash Functions", "docker", ("default",), dockerfile="functions/bash/json-transform/Dockerfile"),
)}


def select_image_targets(only: str) -> list[str]:
    if only.strip().lower() == "all":
        return sorted(IMAGE_TARGETS)
    names = [name.strip() for name in only.split(",") if name.strip()]
    unknown = [name for name in names if name not in IMAGE_TARGETS]
    if unknown:
        raise ValueError(f"Unknown image target(s): {', '.join(unknown)}")
    return names
```

- [ ] **Step 5: Run tests to verify selector behavior passes**

Run:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests/test_image_plan.py -q
```

Expected: FAIL only for missing imported functions that have not been implemented yet, or PASS if the file currently tests selectors only.

- [ ] **Step 6: Commit selector foundation**

Run:

```bash
git add tools/controlplane/src/controlplane_tool/building/image_plan.py tools/controlplane/tests/test_image_plan.py
git commit -m "Add image matrix planner foundation"
```

Expected: commit succeeds.

### Task 2: Explicit Tag Generation

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/building/image_plan.py`
- Modify: `tools/controlplane/tests/test_image_plan.py`

- [ ] **Step 1: Write failing tests for explicit tag generation**

Append to `tools/controlplane/tests/test_image_plan.py`:

```python

def test_image_reference_for_native_cell() -> None:
    assert (
        image_reference("control-plane", "v1.2.3", "amd64", "native")
        == "ghcr.io/miciav/nanofaas/control-plane:v1.2.3-amd64-native"
    )


def test_image_reference_for_jvm_cell() -> None:
    assert (
        image_reference("java-word-stats", "v1.2.3", "arm64", "jvm")
        == "ghcr.io/miciav/nanofaas/java-word-stats:v1.2.3-arm64-jvm"
    )


def test_image_reference_for_default_cell_omits_flavor() -> None:
    assert (
        image_reference("python-word-stats", "v1.2.3", "arm64", "default")
        == "ghcr.io/miciav/nanofaas/python-word-stats:v1.2.3-arm64"
    )
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests/test_image_plan.py::test_image_reference_for_native_cell tools/controlplane/tests/test_image_plan.py::test_image_reference_for_jvm_cell tools/controlplane/tests/test_image_plan.py::test_image_reference_for_default_cell_omits_flavor -q
```

Expected: FAIL with `ImportError` or `NameError` for `image_reference`.

- [ ] **Step 3: Implement explicit tag generation**

Append this function to `tools/controlplane/src/controlplane_tool/building/image_plan.py`:

```python

def image_reference(name: str, tag: str, arch: ImageArch, flavor: ImageFlavor) -> str:
    suffix = f"{tag}-{arch}" if flavor == "default" else f"{tag}-{arch}-{flavor}"
    return f"{BASE}/{name}:{suffix}"
```

- [ ] **Step 4: Run tests to verify explicit tags pass**

Run:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests/test_image_plan.py -q
```

Expected: PASS for all current `test_image_plan.py` tests.

- [ ] **Step 5: Commit tag generation**

Run:

```bash
git add tools/controlplane/src/controlplane_tool/building/image_plan.py tools/controlplane/tests/test_image_plan.py
git commit -m "Add explicit image matrix tags"
```

Expected: commit succeeds.

### Task 3: Matrix Expansion Rules

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/building/image_plan.py`
- Modify: `tools/controlplane/tests/test_image_plan.py`

- [ ] **Step 1: Write failing tests for arch/flavor expansion**

Append to `tools/controlplane/tests/test_image_plan.py`:

```python

def test_plan_all_expands_to_38_cells() -> None:
    plan = plan_image_matrix(
        repo_root=Path("/repo"),
        targets=select_image_targets("all"),
        tag="v1.2.3",
        arches=("amd64", "arm64"),
        flavors=("jvm", "native"),
        push=True,
        runtime="docker",
    )
    assert len(plan.cells) == 38


def test_java_lite_only_expands_native_cells() -> None:
    plan = plan_image_matrix(
        repo_root=Path("/repo"),
        targets=["java-lite-word-stats"],
        tag="v1.2.3",
        arches=("amd64", "arm64"),
        flavors=("jvm", "native"),
        push=True,
        runtime="docker",
    )
    assert [(cell.arch, cell.flavor, cell.image) for cell in plan.cells] == [
        ("amd64", "native", "ghcr.io/miciav/nanofaas/java-lite-word-stats:v1.2.3-amd64-native"),
        ("arm64", "native", "ghcr.io/miciav/nanofaas/java-lite-word-stats:v1.2.3-arm64-native"),
    ]


def test_default_targets_ignore_jvm_native_selector_and_use_default_flavor() -> None:
    plan = plan_image_matrix(
        repo_root=Path("/repo"),
        targets=["watchdog"],
        tag="v1.2.3",
        arches=("amd64", "arm64"),
        flavors=("jvm", "native"),
        push=True,
        runtime="docker",
    )
    assert [(cell.arch, cell.flavor, cell.image) for cell in plan.cells] == [
        ("amd64", "default", "ghcr.io/miciav/nanofaas/watchdog:v1.2.3-amd64"),
        ("arm64", "default", "ghcr.io/miciav/nanofaas/watchdog:v1.2.3-arm64"),
    ]
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests/test_image_plan.py::test_plan_all_expands_to_38_cells tools/controlplane/tests/test_image_plan.py::test_java_lite_only_expands_native_cells tools/controlplane/tests/test_image_plan.py::test_default_targets_ignore_jvm_native_selector_and_use_default_flavor -q
```

Expected: FAIL with `NameError` or `ImportError` for `plan_image_matrix`.

- [ ] **Step 3: Implement matrix expansion with temporary no-op commands**

Append these functions to `tools/controlplane/src/controlplane_tool/building/image_plan.py`:

```python

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


def _selected_flavors(target: ImageTargetSpec, requested: Sequence[Literal["jvm", "native"]]) -> tuple[ImageFlavor, ...]:
    if target.flavors == ("default",):
        return ("default",)
    return tuple(flavor for flavor in requested if flavor in target.flavors)


def _plan_noop_build(repo_root: Path, image: str) -> PlannedCommand:
    return PlannedCommand(command=["true", image], cwd=Path(repo_root), env={})


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
    for target_name in targets:
        target = IMAGE_TARGETS[target_name]
        for arch in arches:
            for flavor in _selected_flavors(target, flavors):
                image = image_reference(target.name, tag, arch, flavor)
                cells.append(
                    ImageMatrixCell(
                        target=target.name,
                        arch=arch,
                        flavor=flavor,
                        image=image,
                        build_command=_plan_noop_build(repo_root, image),
                        push_command=_plan_push(repo_root, image, runtime=runtime) if push else None,
                    )
                )
    return ImageMatrixPlan(tag=tag, cells=tuple(cells))
```

- [ ] **Step 4: Run expansion tests**

Run:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests/test_image_plan.py -q
```

Expected: PASS. This task proves the matrix shape first; Task 4 replaces the no-op build command with the real Gradle/Docker command planning.

- [ ] **Step 5: Commit matrix expansion**

Run:

```bash
git add tools/controlplane/src/controlplane_tool/building/image_plan.py tools/controlplane/tests/test_image_plan.py
git commit -m "Expand image publish matrix"
```

Expected: commit succeeds.

### Task 4: Build Command Planning for Native, JVM, and Default Targets

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/building/image_plan.py`
- Modify: `tools/controlplane/tests/test_image_plan.py`

- [ ] **Step 1: Write failing tests for command planning**

Append to `tools/controlplane/tests/test_image_plan.py`:

```python

def _single_cell(target: str, flavor: ImageFlavor, arch: ImageArch = "amd64"):
    plan = plan_image_matrix(
        repo_root=Path("/repo"),
        targets=[target],
        tag="v1.2.3",
        arches=(arch,),
        flavors=("jvm", "native"),
        push=True,
        runtime="docker",
    )
    matches = [cell for cell in plan.cells if cell.flavor == flavor]
    assert len(matches) == 1
    return matches[0]


def test_native_gradle_cell_uses_boot_build_image() -> None:
    cell = _single_cell("control-plane", "native")
    assert cell.build_command.command[:2] == ["./gradlew", ":control-plane:bootBuildImage"]
    assert "-PcontrolPlaneImage=ghcr.io/miciav/nanofaas/control-plane:v1.2.3-amd64-native" in cell.build_command.command
    assert "-PimagePlatform=linux/amd64" in cell.build_command.command
    assert "-PcontrolPlaneModules=all" in cell.build_command.command
    assert cell.build_command.env["BP_OCI_SOURCE"] == "https://github.com/miciav/nanofaas"
    assert "NATIVE_IMAGE_BUILD_ARGS" in cell.build_command.env


def test_jvm_control_plane_cell_builds_jar_then_dockerfile() -> None:
    cell = _single_cell("control-plane", "jvm")
    assert cell.build_command.command == [
        "bash",
        "-lc",
        "./gradlew :control-plane:bootJar -PcontrolPlaneModules=all && "
        "docker build --platform linux/amd64 "
        "--label org.opencontainers.image.source=https://github.com/miciav/nanofaas "
        "-t ghcr.io/miciav/nanofaas/control-plane:v1.2.3-amd64-jvm "
        "-f platform/control-plane/Dockerfile platform/control-plane",
    ]


def test_java_jvm_function_cell_builds_function_jar_then_dockerfile() -> None:
    cell = _single_cell("java-word-stats", "jvm")
    assert cell.build_command.command == [
        "bash",
        "-lc",
        "./gradlew :functions:java:word-stats:bootJar && "
        "docker build --platform linux/amd64 "
        "--label org.opencontainers.image.source=https://github.com/miciav/nanofaas "
        "-t ghcr.io/miciav/nanofaas/java-word-stats:v1.2.3-amd64-jvm "
        "-f functions/java/word-stats/Dockerfile functions/java/word-stats",
    ]


def test_java_lite_native_uses_dockerfile() -> None:
    cell = _single_cell("java-lite-word-stats", "native")
    assert cell.build_command.command == [
        "docker",
        "build",
        "--platform",
        "linux/amd64",
        "--label",
        "org.opencontainers.image.source=https://github.com/miciav/nanofaas",
        "-t",
        "ghcr.io/miciav/nanofaas/java-lite-word-stats:v1.2.3-amd64-native",
        "-f",
        "functions/java/word-stats-lite/Dockerfile",
        ".",
    ]


def test_default_target_uses_dockerfile_without_flavor_suffix() -> None:
    cell = _single_cell("watchdog", "default")
    assert cell.build_command.command == [
        "docker",
        "build",
        "--platform",
        "linux/amd64",
        "--label",
        "org.opencontainers.image.source=https://github.com/miciav/nanofaas",
        "-t",
        "ghcr.io/miciav/nanofaas/watchdog:v1.2.3-amd64",
        "-f",
        "watchdog/Dockerfile",
        ".",
    ]
```

- [ ] **Step 2: Run tests to verify command planning fails**

Run:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests/test_image_plan.py::test_native_gradle_cell_uses_boot_build_image tools/controlplane/tests/test_image_plan.py::test_jvm_control_plane_cell_builds_jar_then_dockerfile tools/controlplane/tests/test_image_plan.py::test_java_jvm_function_cell_builds_function_jar_then_dockerfile tools/controlplane/tests/test_image_plan.py::test_java_lite_native_uses_dockerfile tools/controlplane/tests/test_image_plan.py::test_default_target_uses_dockerfile_without_flavor_suffix -q
```

Expected: FAIL because build commands are still the no-op `true` commands from Task 3.

- [ ] **Step 3: Replace no-op planning with real command planning**

In `tools/controlplane/src/controlplane_tool/building/image_plan.py`, replace `_plan_noop_build` and update `plan_image_matrix` to call `_plan_build`:

```python
def _label_arg() -> str:
    return f"org.opencontainers.image.source={OCI_SOURCE}"


def _shell_join(parts: Sequence[str]) -> str:
    import shlex

    return " ".join(shlex.quote(part) for part in parts)


def _plan_native_gradle_build(repo_root: Path, target: ImageTargetSpec, image: str, arch: ImageArch) -> PlannedCommand:
    if target.gradle_task is None or target.image_param is None:
        raise ValueError(f"Target {target.name} does not define a Gradle image task")
    command = [
        "./gradlew",
        target.gradle_task,
        f"-P{target.image_param}={image}",
        f"-PimagePlatform={_platform(arch)}",
    ]
    if target.profile_aware:
        command.append("-PcontrolPlaneModules=all")
    if arch == "arm64":
        command.extend([
            "-PimageBuilder=dashaun/builder:tiny",
            "-PimageRunImage=paketobuildpacks/run-jammy-tiny:latest",
        ])
    return PlannedCommand(
        command=command,
        cwd=Path(repo_root),
        env={"NATIVE_IMAGE_BUILD_ARGS": resolve_native_image_build_args(), "BP_OCI_SOURCE": OCI_SOURCE},
    )


def _plan_jvm_docker_build(repo_root: Path, target: ImageTargetSpec, image: str, arch: ImageArch) -> PlannedCommand:
    if target.dockerfile is None:
        raise ValueError(f"Target {target.name} does not define a JVM Dockerfile")
    jar_command = ["./gradlew", *target.jvm_artifact_tasks]
    if target.profile_aware:
        jar_command.append("-PcontrolPlaneModules=all")
    docker_command = [
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
    return PlannedCommand(
        command=["bash", "-lc", f"{_shell_join(jar_command)} && {_shell_join(docker_command)}"],
        cwd=Path(repo_root),
        env={"BP_OCI_SOURCE": OCI_SOURCE},
    )


def _plan_docker_build(repo_root: Path, target: ImageTargetSpec, image: str, arch: ImageArch) -> PlannedCommand:
    if target.dockerfile is None:
        raise ValueError(f"Target {target.name} does not define a Dockerfile")
    return PlannedCommand(
        command=[
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
        ],
        cwd=Path(repo_root),
        env={},
    )


def _plan_build(repo_root: Path, target: ImageTargetSpec, image: str, arch: ImageArch, flavor: ImageFlavor) -> PlannedCommand:
    if flavor == "jvm":
        return _plan_jvm_docker_build(repo_root, target, image, arch)
    if flavor == "native" and target.kind == "gradle":
        return _plan_native_gradle_build(repo_root, target, image, arch)
    return _plan_docker_build(repo_root, target, image, arch)
```

In `plan_image_matrix`, replace:

```python
build_command=_plan_noop_build(repo_root, image),
```

with:

```python
build_command=_plan_build(repo_root, target, image, arch, flavor),
```

- [ ] **Step 4: Run command planning tests**

Run:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests/test_image_plan.py -q
```

Expected: PASS.

- [ ] **Step 5: Commit command planning**

Run:

```bash
git add tools/controlplane/src/controlplane_tool/building/image_plan.py tools/controlplane/tests/test_image_plan.py
git commit -m "Plan JVM and native image builds"
```

Expected: commit succeeds.

### Task 5: Image Matrix Executor with Workflow Events

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/building/image_workflow.py`
- Create: `tools/controlplane/tests/test_image_workflow.py`

- [ ] **Step 1: Write failing tests for executor sequencing**

Create `tools/controlplane/tests/test_image_workflow.py`:

```python
from __future__ import annotations

from pathlib import Path

import pytest
from shellcraft.runners import CommandRunner
from workflow_tasks.shell import RecordingShell

from controlplane_tool.building.image_plan import plan_image_matrix
from controlplane_tool.building.image_workflow import ImageMatrixRunError, run_image_matrix_plan


def test_run_image_matrix_plan_executes_build_then_push_in_order() -> None:
    plan = plan_image_matrix(
        repo_root=Path("/repo"),
        targets=["watchdog"],
        tag="v1",
        arches=("amd64",),
        flavors=("jvm", "native"),
        push=True,
        runtime="docker",
    )
    shell = RecordingShell()
    runner = CommandRunner(shell=shell, repo_root=Path("/repo"))

    results = run_image_matrix_plan(runner=runner, plan=plan, dry_run=True, fail_fast=True)

    assert [result.image for result in results] == ["ghcr.io/miciav/nanofaas/watchdog:v1-amd64"]
    assert shell.commands == [
        [
            "docker",
            "build",
            "--platform",
            "linux/amd64",
            "--label",
            "org.opencontainers.image.source=https://github.com/miciav/nanofaas",
            "-t",
            "ghcr.io/miciav/nanofaas/watchdog:v1-amd64",
            "-f",
            "watchdog/Dockerfile",
            ".",
        ],
        ["docker", "push", "ghcr.io/miciav/nanofaas/watchdog:v1-amd64"],
    ]


def test_run_image_matrix_plan_raises_on_build_failure_fail_fast() -> None:
    plan = plan_image_matrix(
        repo_root=Path("/repo"),
        targets=["watchdog"],
        tag="v1",
        arches=("amd64",),
        flavors=("jvm", "native"),
        push=True,
        runtime="docker",
    )
    shell = RecordingShell(return_code_map={("docker", "build"): 9})
    runner = CommandRunner(shell=shell, repo_root=Path("/repo"))

    with pytest.raises(ImageMatrixRunError, match="build failed"):
        run_image_matrix_plan(runner=runner, plan=plan, dry_run=False, fail_fast=True)


def test_run_image_matrix_plan_collects_failure_when_keep_going() -> None:
    plan = plan_image_matrix(
        repo_root=Path("/repo"),
        targets=["watchdog", "python-word-stats"],
        tag="v1",
        arches=("amd64",),
        flavors=("jvm", "native"),
        push=False,
        runtime="docker",
    )
    shell = RecordingShell(return_code_map={("docker", "build"): 9})
    runner = CommandRunner(shell=shell, repo_root=Path("/repo"))

    results = run_image_matrix_plan(runner=runner, plan=plan, dry_run=False, fail_fast=False)

    assert [result.ok for result in results] == [False, False]
    assert [result.phase for result in results] == ["build", "build"]
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests/test_image_workflow.py -q
```

Expected: FAIL with `ModuleNotFoundError` for `image_workflow`.

- [ ] **Step 3: Implement executor**

Create `tools/controlplane/src/controlplane_tool/building/image_workflow.py`:

```python
from __future__ import annotations

from dataclasses import dataclass

from shellcraft.runners import CommandRunner
from workflow_tasks import step, success, fail

from controlplane_tool.building.image_plan import ImageMatrixCell, ImageMatrixPlan


class ImageMatrixRunError(RuntimeError):
    pass


@dataclass(frozen=True)
class ImageCellResult:
    target: str
    arch: str
    flavor: str
    image: str
    phase: str
    ok: bool
    return_code: int
    detail: str = ""


def _event_label(cell: ImageMatrixCell, phase: str) -> str:
    if cell.flavor == "default":
        return f"{cell.target} {cell.arch} {phase}"
    return f"{cell.target} {cell.arch}-{cell.flavor} {phase}"


def _failure_detail(result) -> str:
    stderr = getattr(result, "stderr", "") or ""
    stdout = getattr(result, "stdout", "") or ""
    return str(stderr).strip() or str(stdout).strip() or f"exit code {result.return_code}"


def run_image_matrix_plan(
    *,
    runner: CommandRunner,
    plan: ImageMatrixPlan,
    dry_run: bool,
    fail_fast: bool,
) -> list[ImageCellResult]:
    results: list[ImageCellResult] = []
    for cell in plan.cells:
        step(_event_label(cell, "build"), detail=cell.image)
        build_result = cell.build_command.run(runner, dry_run=dry_run)
        if build_result.return_code != 0:
            detail = _failure_detail(build_result)
            fail(_event_label(cell, "build failed"), detail=detail)
            cell_result = ImageCellResult(cell.target, cell.arch, cell.flavor, cell.image, "build", False, build_result.return_code, detail)
            results.append(cell_result)
            if fail_fast:
                raise ImageMatrixRunError(f"build failed for {cell.image}: {detail}")
            continue
        success(_event_label(cell, "build done"), detail=cell.image)

        if cell.push_command is None:
            results.append(ImageCellResult(cell.target, cell.arch, cell.flavor, cell.image, "build", True, 0))
            continue

        step(_event_label(cell, "push"), detail=cell.image)
        push_result = cell.push_command.run(runner, dry_run=dry_run)
        if push_result.return_code != 0:
            detail = _failure_detail(push_result)
            fail(_event_label(cell, "push failed"), detail=detail)
            cell_result = ImageCellResult(cell.target, cell.arch, cell.flavor, cell.image, "push", False, push_result.return_code, detail)
            results.append(cell_result)
            if fail_fast:
                raise ImageMatrixRunError(f"push failed for {cell.image}: {detail}")
            continue
        success(_event_label(cell, "push done"), detail=cell.image)
        results.append(ImageCellResult(cell.target, cell.arch, cell.flavor, cell.image, "push", True, 0))
    return results
```

- [ ] **Step 4: Run executor tests**

Run:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests/test_image_workflow.py -q
```

Expected: PASS.

- [ ] **Step 5: Commit executor**

Run:

```bash
git add tools/controlplane/src/controlplane_tool/building/image_workflow.py tools/controlplane/tests/test_image_workflow.py
git commit -m "Execute image matrix plans"
```

Expected: commit succeeds.

### Task 6: Replace CLI `images` Contract

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/cli/commands.py:206-226`
- Modify: `tools/controlplane/tests/test_images_command.py`
- Modify: `tools/controlplane/tests/test_image_matrix.py` or delete old tests after equivalent coverage exists

- [ ] **Step 1: Run GitNexus impact check for CLI command**

Run:

```text
gitnexus_impact({target: "images_command", direction: "upstream", repo: "mcFaas"})
```

Expected: LOW or MEDIUM risk. If HIGH or CRITICAL, report before proceeding.

- [ ] **Step 2: Replace command tests with new CLI contract tests**

Replace `tools/controlplane/tests/test_images_command.py` with:

```python
from __future__ import annotations

from typer.testing import CliRunner

from controlplane_tool.app.main import app

runner = CliRunner()


def test_images_help_lists_new_matrix_options() -> None:
    result = runner.invoke(app, ["images", "--help"])
    assert result.exit_code == 0
    assert "--arch" in result.stdout
    assert "--flavor" in result.stdout
    assert "--fail-fast" in result.stdout
    assert "--keep-going" in result.stdout
    assert "--arch-suffix" not in result.stdout


def test_images_dry_run_plans_explicit_tags(monkeypatch, tmp_path) -> None:
    monkeypatch.chdir(tmp_path)
    (tmp_path / "build.gradle").write_text("version = '7.7.7'\n", encoding="utf-8")
    result = runner.invoke(
        app,
        [
            "images",
            "--dry-run",
            "--no-push",
            "--tag",
            "vtest",
            "--only",
            "control-plane",
            "--arch",
            "amd64",
            "--flavor",
            "all",
        ],
    )
    assert result.exit_code == 0
    assert "ghcr.io/miciav/nanofaas/control-plane:vtest-amd64-jvm" in result.stdout
    assert "ghcr.io/miciav/nanofaas/control-plane:vtest-amd64-native" in result.stdout


def test_images_rejects_multi_arch_compatibility_mode(monkeypatch, tmp_path) -> None:
    monkeypatch.chdir(tmp_path)
    (tmp_path / "build.gradle").write_text("version = '7.7.7'\n", encoding="utf-8")
    result = runner.invoke(app, ["images", "--dry-run", "--arch", "multi"])
    assert result.exit_code != 0
    assert "Invalid value" in result.stdout or "invalid choice" in result.stdout.lower()


def test_images_unknown_target_errors(monkeypatch, tmp_path) -> None:
    monkeypatch.chdir(tmp_path)
    (tmp_path / "build.gradle").write_text("version = '1.0.0'\n", encoding="utf-8")
    result = runner.invoke(app, ["images", "--dry-run", "--only", "does-not-exist", "--no-push"])
    assert result.exit_code != 0
```

- [ ] **Step 3: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests/test_images_command.py -q
```

Expected: FAIL because old CLI still exposes `--arch-suffix`, lacks `--flavor`, and does not print planned explicit tags.

- [ ] **Step 4: Implement new CLI command**

In `tools/controlplane/src/controlplane_tool/cli/commands.py`, update imports near the existing image import:

```python
from typing import Literal

from controlplane_tool.building.image_plan import (
    DEFAULT_ARCHES,
    DEFAULT_FLAVORS,
    plan_image_matrix,
    resolve_current_version,
    select_image_targets,
)
from controlplane_tool.building.image_workflow import run_image_matrix_plan
```

Replace the `images_command` function at `tools/controlplane/src/controlplane_tool/cli/commands.py:206-226` with:

```python
    @app.command("images", context_settings=CLI_CONTEXT_SETTINGS)
    def images_command(
        tag: str | None = typer.Option(None, "--tag", help="Image tag (default: version from build.gradle)."),
        only: str = typer.Option("all", "--only", help="Comma-separated target names or 'all'."),
        arch: Literal["amd64", "arm64", "all"] = typer.Option("all", "--arch", help="amd64 | arm64 | all."),
        flavor: Literal["jvm", "native", "all"] = typer.Option("all", "--flavor", help="jvm | native | all."),
        push: bool = typer.Option(True, "--push/--no-push", help="Push images after building."),
        runtime: str = typer.Option("docker", "--runtime", help="Container runtime CLI."),
        dry_run: bool = typer.Option(False, "--dry-run", help="Print planned build/push commands only."),
        fail_fast: bool = typer.Option(True, "--fail-fast/--keep-going", help="Stop at first failed cell."),
    ) -> None:
        repo_root = _Path.cwd()
        resolved_tag = tag or resolve_current_version(repo_root)
        try:
            targets = select_image_targets(only)
        except ValueError as exc:
            raise typer.BadParameter(str(exc)) from exc
        arches = DEFAULT_ARCHES if arch == "all" else (arch,)
        flavors = DEFAULT_FLAVORS if flavor == "all" else (flavor,)
        plan = plan_image_matrix(
            repo_root=repo_root,
            targets=targets,
            tag=resolved_tag,
            arches=arches,
            flavors=flavors,
            push=push,
            runtime=runtime,
        )
        if dry_run:
            for cell in plan.cells:
                typer.echo(" ".join(cell.build_command.command))
                if cell.push_command is not None:
                    typer.echo(" ".join(cell.push_command.command))
            return
        runner = CommandRunner(shell=SubprocessShell(), repo_root=repo_root)
        results = run_image_matrix_plan(runner=runner, plan=plan, dry_run=False, fail_fast=fail_fast)
        failed = [result for result in results if not result.ok]
        if failed:
            raise typer.Exit(code=1)
```

- [ ] **Step 5: Run CLI tests**

Run:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests/test_images_command.py tools/controlplane/tests/test_image_plan.py tools/controlplane/tests/test_image_workflow.py -q
```

Expected: PASS.

- [ ] **Step 6: Remove or rewrite obsolete image_matrix tests**

If `tools/controlplane/tests/test_image_matrix.py` still asserts old behavior, replace it with a small migration guard:

```python
from __future__ import annotations

from controlplane_tool.building.image_plan import IMAGE_TARGETS


def test_image_plan_catalog_keeps_current_target_names() -> None:
    assert "control-plane" in IMAGE_TARGETS
    assert "java-lite-word-stats" in IMAGE_TARGETS
    assert "watchdog" in IMAGE_TARGETS
```

Run:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests/test_image_matrix.py -q
```

Expected: PASS.

- [ ] **Step 7: Commit CLI replacement**

Run:

```bash
git add tools/controlplane/src/controlplane_tool/cli/commands.py tools/controlplane/tests/test_images_command.py tools/controlplane/tests/test_image_matrix.py
git commit -m "Replace images CLI with matrix publisher"
```

Expected: commit succeeds.

### Task 7: TUI Build Menu Entry and Workflow

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/tui/app.py`
- Modify: `tools/controlplane/tests/test_tui_choices.py`
- Modify: `tools/controlplane/tests/test_tui_product_contract.py`

- [ ] **Step 1: Run GitNexus impact check for TUI build menu**

Run:

```text
gitnexus_impact({target: "NanofaasTUI._build_menu", direction: "upstream", repo: "mcFaas"})
```

Expected: MEDIUM risk because TUI tests cover menu behavior. If HIGH or CRITICAL, report before proceeding.

- [ ] **Step 2: Write failing tests for required TUI entry**

Append to `tools/controlplane/tests/test_tui_choices.py`:

```python

def test_build_menu_contains_publish_images_matrix_entry() -> None:
    from controlplane_tool.tui import app as tui_app

    values = [choice.value for choice in tui_app._BUILD_ACTION_CHOICES]
    titles = [choice.title for choice in tui_app._BUILD_ACTION_CHOICES]
    descriptions = [choice.description for choice in tui_app._BUILD_ACTION_CHOICES]

    assert "publish-images" in values
    assert "publish-images — build & publish image matrix" in titles
    assert "Build and push all selected images across architectures and JVM/native flavors." in descriptions
```

Add a routing test to `tools/controlplane/tests/test_tui_choices.py`:

```python

def test_build_menu_routes_publish_images_to_image_matrix_workflow(monkeypatch) -> None:
    from controlplane_tool.tui.app import NanofaasTUI

    selected = iter(["publish-images"])
    called = {"workflow": False}

    monkeypatch.setattr("controlplane_tool.tui.app._select_build_action", lambda: next(selected))
    monkeypatch.setattr(NanofaasTUI, "_run_publish_images_workflow", lambda self: called.update({"workflow": True}))

    NanofaasTUI()._build_menu()

    assert called["workflow"] is True
```

- [ ] **Step 3: Run TUI tests to verify failure**

Run:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests/test_tui_choices.py::test_build_menu_contains_publish_images_matrix_entry tools/controlplane/tests/test_tui_choices.py::test_build_menu_routes_publish_images_to_image_matrix_workflow -q
```

Expected: FAIL because the menu entry and `_run_publish_images_workflow` do not exist.

- [ ] **Step 4: Add the TUI menu entry**

In `tools/controlplane/src/controlplane_tool/tui/app.py`, add this item to `_BUILD_ACTION_CHOICES` after the existing `image` entry:

```python
    _choice(
        "publish-images — build & publish image matrix",
        "publish-images",
        "Build and push all selected images across architectures and JVM/native flavors.",
    ),
```

Update `_select_build_action` so it accepts `"publish-images"` before calling `is_build_action`:

```python
    if value == "publish-images":
        return value
```

Because `BuildAction` does not include `"publish-images"`, update the return annotation:

```python
def _select_build_action() -> BuildAction | str | None:
```

- [ ] **Step 5: Route the Build menu entry**

In `NanofaasTUI._build_menu`, immediately after:

```python
            if action is None:
                return
```

add:

```python
            if action == "publish-images":
                self._run_publish_images_workflow()
                return
```

- [ ] **Step 6: Implement TUI workflow method**

Add this method inside `NanofaasTUI` before `_environment_menu`:

```python
    def _run_publish_images_workflow(self) -> None:
        from pathlib import Path

        from shellcraft.runners import CommandRunner
        from workflow_tasks.shell import SubprocessShell

        from controlplane_tool.building.image_plan import (
            DEFAULT_ARCHES,
            DEFAULT_FLAVORS,
            plan_image_matrix,
            resolve_current_version,
            select_image_targets,
        )
        from controlplane_tool.building.image_workflow import run_image_matrix_plan

        repo_root = Path.cwd()
        default_tag = resolve_current_version(repo_root)
        tag = _ask(lambda: questionary.text("Image tag:", default=default_tag, style=_STYLE).ask())
        if not tag:
            return
        arch = _ask(
            lambda: questionary.select(
                "Architecture:",
                choices=["all", "amd64", "arm64"],
                default="all",
                style=_STYLE,
            ).ask()
        )
        if not arch:
            return
        flavor = _ask(
            lambda: questionary.select(
                "Flavor:",
                choices=["all", "jvm", "native"],
                default="all",
                style=_STYLE,
            ).ask()
        )
        if not flavor:
            return
        only = _ask(lambda: questionary.text("Targets:", default="all", style=_STYLE).ask())
        if not only:
            return
        push = _ask(lambda: questionary.confirm("Push images?", default=True, style=_STYLE).ask())
        dry_run = _ask(lambda: questionary.confirm("Dry-run? (show commands only)", default=False, style=_STYLE).ask())
        fail_fast = _ask(lambda: questionary.confirm("Fail fast?", default=True, style=_STYLE).ask())

        targets = select_image_targets(only)
        arches = DEFAULT_ARCHES if arch == "all" else (arch,)
        flavors = DEFAULT_FLAVORS if flavor == "all" else (flavor,)
        plan = plan_image_matrix(
            repo_root=repo_root,
            targets=targets,
            tag=tag,
            arches=arches,
            flavors=flavors,
            push=push,
            runtime="docker",
        )

        planned_steps = [cell.image for cell in plan.cells]

        def _run_publish_workflow(dashboard: WorkflowDashboard, sink: TuiWorkflowSink):
            dashboard.append_log(f"Planned {len(plan.cells)} image cells")
            runner = CommandRunner(shell=SubprocessShell(), repo_root=repo_root)
            if dry_run:
                for cell in plan.cells:
                    dashboard.append_log(" ".join(cell.build_command.command))
                    if cell.push_command is not None:
                        dashboard.append_log(" ".join(cell.push_command.command))
                return []
            return run_image_matrix_plan(
                runner=runner,
                plan=plan,
                dry_run=False,
                fail_fast=fail_fast,
            )

        self._controller.run_live_workflow(
            title="Publish Image Matrix",
            summary_lines=[
                f"Tag: {tag}",
                f"Architecture: {arch}",
                f"Flavor: {flavor}",
                f"Targets: {only}",
                f"Push: {push}",
                f"Dry-run: {dry_run}",
            ],
            planned_steps=planned_steps,
            action=_run_publish_workflow,
        )
```

- [ ] **Step 7: Run TUI tests**

Run:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests/test_tui_choices.py::test_build_menu_contains_publish_images_matrix_entry tools/controlplane/tests/test_tui_choices.py::test_build_menu_routes_publish_images_to_image_matrix_workflow -q
```

Expected: PASS.

- [ ] **Step 8: Add product contract test**

Append to `tools/controlplane/tests/test_tui_product_contract.py`:

```python

def test_tui_has_required_publish_image_matrix_entry() -> None:
    from controlplane_tool.tui import app as tui_app

    entries = {choice.value: choice for choice in tui_app._BUILD_ACTION_CHOICES}
    entry = entries["publish-images"]
    assert entry.title == "publish-images — build & publish image matrix"
    assert entry.description == "Build and push all selected images across architectures and JVM/native flavors."
```

- [ ] **Step 9: Run TUI contract tests**

Run:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests/test_tui_product_contract.py tools/controlplane/tests/test_tui_choices.py -q
```

Expected: PASS.

- [ ] **Step 10: Commit TUI entry**

Run:

```bash
git add tools/controlplane/src/controlplane_tool/tui/app.py tools/controlplane/tests/test_tui_choices.py tools/controlplane/tests/test_tui_product_contract.py
git commit -m "Add TUI image matrix publisher"
```

Expected: commit succeeds.

### Task 8: Make JVM Dockerfiles Official Release Paths

**Files:**
- Modify: `platform/control-plane/Dockerfile`
- Modify: `platform/function-runtime/Dockerfile`
- Modify: `functions/java/word-stats/Dockerfile`
- Modify: `functions/java/json-transform/Dockerfile`
- Test: `tools/controlplane/tests/test_image_plan.py`

- [ ] **Step 1: Add tests guarding JVM Dockerfile paths**

Append to `tools/controlplane/tests/test_image_plan.py`:

```python

def test_official_jvm_dockerfiles_are_used_for_jvm_flavor() -> None:
    plan = plan_image_matrix(
        repo_root=Path("/repo"),
        targets=["control-plane", "function-runtime", "java-word-stats", "java-json-transform"],
        tag="v1",
        arches=("amd64",),
        flavors=("jvm",),
        push=False,
        runtime="docker",
    )
    commands = [" ".join(cell.build_command.command) for cell in plan.cells]
    assert any("-f platform/control-plane/Dockerfile platform/control-plane" in command for command in commands)
    assert any("-f platform/function-runtime/Dockerfile platform/function-runtime" in command for command in commands)
    assert any("-f functions/java/word-stats/Dockerfile functions/java/word-stats" in command for command in commands)
    assert any("-f functions/java/json-transform/Dockerfile functions/java/json-transform" in command for command in commands)
```

- [ ] **Step 2: Run tests**

Run:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests/test_image_plan.py::test_official_jvm_dockerfiles_are_used_for_jvm_flavor -q
```

Expected: PASS if Task 4 was implemented correctly.

- [ ] **Step 3: Update Dockerfile comments**

Replace the first two comment lines in `platform/control-plane/Dockerfile`:

```dockerfile
# Official JVM control-plane image path.
# Native release images are built separately with Cloud Native Buildpacks.
```

Add these comments at the top of `platform/function-runtime/Dockerfile`:

```dockerfile
# Official JVM function-runtime image path.
# Native release images are built separately with Cloud Native Buildpacks.
```

Replace the first two comment lines in `functions/java/word-stats/Dockerfile`:

```dockerfile
# Official JVM image path for the word-stats Java function.
# Native release images are built separately with Cloud Native Buildpacks.
```

Replace the first two comment lines in `functions/java/json-transform/Dockerfile`:

```dockerfile
# Official JVM image path for the json-transform Java function.
# Native release images are built separately with Cloud Native Buildpacks.
```

- [ ] **Step 4: Run Dockerfile path test again**

Run:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests/test_image_plan.py::test_official_jvm_dockerfiles_are_used_for_jvm_flavor -q
```

Expected: PASS.

- [ ] **Step 5: Commit JVM Dockerfile official status**

Run:

```bash
git add platform/control-plane/Dockerfile platform/function-runtime/Dockerfile functions/java/word-stats/Dockerfile functions/java/json-transform/Dockerfile tools/controlplane/tests/test_image_plan.py
git commit -m "Mark JVM image Dockerfiles as release paths"
```

Expected: commit succeeds.

### Task 9: CI/GitOps Uses New Publisher

**Files:**
- Modify: `.github/workflows/gitops.yml`
- Modify: `scripts/tests/test_gitops_workflow_control_plane_build.py`

- [ ] **Step 1: Inspect current GitOps tests**

Run:

```bash
sed -n '1,220p' scripts/tests/test_gitops_workflow_control_plane_build.py
```

Expected: identify assertions that currently pin manual `bootBuildImage` or Docker build behavior.

- [ ] **Step 2: Replace or add workflow test for new command**

Update `scripts/tests/test_gitops_workflow_control_plane_build.py` so it asserts:

```python
from __future__ import annotations

from pathlib import Path


def test_gitops_uses_image_matrix_publisher() -> None:
    workflow = Path(".github/workflows/gitops.yml").read_text(encoding="utf-8")
    assert "./scripts/controlplane.sh images" in workflow
    assert "--arch all" in workflow
    assert "--flavor all" in workflow
    assert "--tag ${{ github.ref_name }}" in workflow
    assert "--arch-suffix" not in workflow
    assert ":control-plane:bootBuildImage" not in workflow
```

- [ ] **Step 3: Run workflow test to verify failure**

Run:

```bash
uv run pytest scripts/tests/test_gitops_workflow_control_plane_build.py -q
```

Expected: FAIL because the workflow still uses manual build steps.

- [ ] **Step 4: Replace manual image build steps in GitOps**

In `.github/workflows/gitops.yml`, keep checkout, setup, registry login, Java/uv setup as required by the workflow. Replace the manual image build/push block with one step:

```yaml
      - name: Build and push full image matrix
        run: ./scripts/controlplane.sh images --tag ${{ github.ref_name }} --arch all --flavor all --push --fail-fast
```

If the workflow still needs `latest` tags, add a follow-up step in the same workflow that is explicit about retagging or manifest creation. Do not overload the new per-cell tag contract with ambiguous `latest`.

- [ ] **Step 5: Run workflow test**

Run:

```bash
uv run pytest scripts/tests/test_gitops_workflow_control_plane_build.py -q
```

Expected: PASS.

- [ ] **Step 6: Commit GitOps migration**

Run:

```bash
git add .github/workflows/gitops.yml scripts/tests/test_gitops_workflow_control_plane_build.py
git commit -m "Use image matrix publisher in GitOps"
```

Expected: commit succeeds.

### Task 10: E2E and Scenario Consumer Audit/Migration

**Files:**
- Modify: `tools/workflow-tasks/src/workflow_tasks/components/images.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e/container_local_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenario_tasks.py`
- Modify tests under `tools/workflow-tasks/tests/components/test_images.py`
- Modify tests under `tools/controlplane/tests/` that pin old image command assumptions

- [ ] **Step 1: Audit current image consumer references**

Run:

```bash
rg -n "controlplane.sh images|--arch-suffix|--arch multi|bootBuildImage|docker build|images.build_core|images.build_selected_functions" tools scripts .github docs deploy
```

Expected: list all remaining live consumers. Ignore historical `docs/superpowers/specs/*` and old plan archives unless they are tested as live docs.

- [ ] **Step 2: Decide local e2e tag policy and document it in tests**

Use this policy:

- Release publishing uses explicit tags from `image_plan.py`.
- VM/e2e local workflows may keep local `:e2e` tags because they are not registry release artifacts.
- If an e2e workflow shells out to `controlplane.sh images`, migrate it to the new CLI.
- If an e2e workflow builds images directly for a local registry, keep direct build operations but ensure names do not conflict with release tags.

Add this test to `tools/workflow-tasks/tests/components/test_images.py`:

```python

def test_e2e_image_components_keep_local_e2e_tags() -> None:
    from workflow_tasks.components.images import control_image, runtime_image

    assert control_image("localhost:5000") == "localhost:5000/nanofaas/control-plane:e2e"
    assert runtime_image("localhost:5000") == "localhost:5000/nanofaas/function-runtime:e2e"
```

- [ ] **Step 3: Run e2e image component tests**

Run:

```bash
uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/components/test_images.py -q
```

Expected: PASS if local `:e2e` tags remain intentional.

- [ ] **Step 4: Update any direct `controlplane.sh images` consumer**

For every live consumer found in Step 1 that calls old CLI syntax, replace:

```bash
./scripts/controlplane.sh images --arch arm64 --arch-suffix --tag TAG
```

with:

```bash
./scripts/controlplane.sh images --arch arm64 --flavor all --tag TAG --push
```

If the consumer wants the full publish set, use:

```bash
./scripts/controlplane.sh images --arch all --flavor all --tag TAG --push
```

- [ ] **Step 5: Run targeted consumer tests**

Run:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests/test_e2e_commands.py tools/controlplane/tests/test_scenario_tasks.py tools/controlplane/tests/test_scenario_component_library.py -q
uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/components/test_images.py -q
```

Expected: PASS.

- [ ] **Step 6: Commit e2e consumer migration**

Run:

```bash
git add tools/workflow-tasks/src/workflow_tasks/components/images.py tools/controlplane/src/controlplane_tool/e2e/container_local_runner.py tools/controlplane/src/controlplane_tool/scenario/scenario_tasks.py tools/workflow-tasks/tests/components/test_images.py tools/controlplane/tests
git commit -m "Align image consumers with matrix publishing"
```

Expected: commit succeeds. If some listed files were not changed, omit them from `git add`.

### Task 11: Remove Release Manager

**Files:**
- Delete: `scripts/release-manager/release.py`
- Delete: `scripts/release-manager/README.md`
- Delete: `scripts/tests/test_release_manager_javascript_sdk.py`
- Modify: docs/tests that reference the release manager

- [ ] **Step 1: Find release-manager references**

Run:

```bash
rg -n "release-manager|release.py|build_and_push_arm64|Build and push images from this machine" scripts docs tools .github
```

Expected: identify live references to delete or rewrite. Historical plans/specs can remain if not part of live docs tests.

- [ ] **Step 2: Remove release manager files**

Use `apply_patch` delete hunks or an approved destructive command if execution policy allows it. The desired final state is:

```text
scripts/release-manager/release.py removed
scripts/release-manager/README.md removed
scripts/tests/test_release_manager_javascript_sdk.py removed
```

- [ ] **Step 3: Update live docs references**

For live docs that tell users to use `scripts/release-manager/release.py`, replace with:

```text
Use `./scripts/controlplane.sh images --tag TAG --arch all --flavor all --push` to publish the complete image matrix.
```

For docs that describe release process responsibilities beyond image publishing, keep only the parts that still exist.

- [ ] **Step 4: Run reference scan**

Run:

```bash
rg -n "release-manager|release.py|build_and_push_arm64|Build and push images from this machine" scripts docs tools .github
```

Expected: no live references outside historical `docs/superpowers/specs/*` or old plan files.

- [ ] **Step 5: Run scripts tests**

Run:

```bash
uv run pytest scripts/tests -q
```

Expected: PASS.

- [ ] **Step 6: Commit release manager removal**

Run:

```bash
git add scripts docs .github tools
git commit -m "Remove release manager"
```

Expected: commit succeeds.

### Task 12: Documentation for New Image Publishing Workflow

**Files:**
- Modify: `docs/quickstart.md`
- Modify: `docs/control-plane.md`
- Modify: any live docs found by `rg -n "controlplane.sh images|--arch-suffix|release-manager|bootBuildImage" docs`

- [ ] **Step 1: Find live docs to update**

Run:

```bash
rg -n "controlplane.sh images|--arch-suffix|release-manager|release.py|bootBuildImage|image matrix" docs README.md AGENTS.md
```

Expected: list live documentation references.

- [ ] **Step 2: Add or update image publishing docs**

Add this section to the most appropriate live doc, preferably `docs/quickstart.md` if it already contains operational commands:

````markdown
## Publishing the Image Matrix

Publish the complete image matrix with explicit per-cell tags:

```bash
./scripts/controlplane.sh images --tag v1.2.3 --arch all --flavor all --push
```

The publisher creates explicit tags instead of ambiguous shared tags.

Core and Java Spring targets publish both JVM and GraalVM-native images:

```text
v1.2.3-amd64-jvm
v1.2.3-arm64-jvm
v1.2.3-amd64-native
v1.2.3-arm64-native
```

Java-lite targets are native-only:

```text
v1.2.3-amd64-native
v1.2.3-arm64-native
```

Go, Python, JavaScript, Bash, and watchdog targets publish architecture-only tags:

```text
v1.2.3-amd64
v1.2.3-arm64
```

Use the TUI path `Build -> publish-images — build & publish image matrix` for an interactive, observable run.
````

- [ ] **Step 3: Run docs tests**

Run:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests/test_docs_links.py tools/controlplane/tests/test_architecture_docs.py -q
```

Expected: PASS.

- [ ] **Step 4: Commit documentation**

Run:

```bash
git add docs README.md AGENTS.md
git commit -m "Document image matrix publishing"
```

Expected: commit succeeds. If some files were not changed, omit them from `git add`.

### Task 13: Full Verification and GitNexus Change Detection

**Files:**
- No planned file edits.

- [ ] **Step 1: Run focused Python tests**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_image_plan.py \
  tools/controlplane/tests/test_image_workflow.py \
  tools/controlplane/tests/test_images_command.py \
  tools/controlplane/tests/test_tui_choices.py \
  tools/controlplane/tests/test_tui_product_contract.py \
  -q
```

Expected: PASS.

- [ ] **Step 2: Run workflow-task image component tests**

Run:

```bash
uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/components/test_images.py -q
```

Expected: PASS.

- [ ] **Step 3: Run scripts tests**

Run:

```bash
uv run pytest scripts/tests -q
```

Expected: PASS.

- [ ] **Step 4: Run CLI dry-run smoke**

Run:

```bash
./scripts/controlplane.sh images --tag vplan-smoke --arch all --flavor all --dry-run --no-push
```

Expected:

- Exit code 0.
- Output includes `control-plane:vplan-smoke-amd64-jvm`.
- Output includes `control-plane:vplan-smoke-arm64-native`.
- Output includes `java-lite-word-stats:vplan-smoke-amd64-native`.
- Output includes `watchdog:vplan-smoke-arm64`.
- Output does not include `watchdog:vplan-smoke-arm64-native`.

- [ ] **Step 5: Run TUI import smoke**

Run:

```bash
uv run --project tools/controlplane python -c "from controlplane_tool.tui import app; assert any(c.value == 'publish-images' for c in app._BUILD_ACTION_CHOICES)"
```

Expected: exit code 0.

- [ ] **Step 6: Run GitNexus detect changes**

Run:

```text
gitnexus_detect_changes({scope: "all", repo: "mcFaas"})
```

Expected: changed symbols and affected flows match image publishing, TUI build menu, CI/docs, and release-manager removal only. If unrelated changes appear, inspect before finishing.

- [ ] **Step 7: Run final status**

Run:

```bash
git status --short
```

Expected: only intended changes are present. There may be unrelated pre-existing untracked files; do not stage them.

## Self-Review

Spec coverage:

- Complete image set across current components: covered by Tasks 1, 3, and 4.
- Explicit tags including `TAG-amd64-native`: covered by Task 2.
- JVM images published officially: covered by Tasks 4, 8, 9, and 12.
- Java-lite native-only: covered by Tasks 1, 2, and 3.
- No compatibility requirement: covered by Task 6 and cleanup in Tasks 10-11.
- TUI entry in the adequate menu: covered by Task 7.
- Observable process state: covered by Task 5 and Task 7 live workflow.
- Consumer migration: covered by Tasks 9 and 10.
- Release manager removal: covered by Task 11.
- Final verification and GitNexus: covered by Task 13.

Placeholder scan:

- This plan contains no `TBD`, `TODO`, "similar to", or unexpanded "write tests" steps.
- Every code-changing task includes concrete code or exact replacement text.

Type consistency:

- `ImageArch` is `Literal["amd64", "arm64"]`.
- `ImageFlavor` is `Literal["jvm", "native", "default"]`.
- CLI flavor accepts only `jvm`, `native`, or `all`; `default` is internal.
- `ImageMatrixCell` fields are used consistently by executor and tests.
- TUI entry value is consistently `publish-images`.
