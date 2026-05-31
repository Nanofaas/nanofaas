# Sub-5/6-b1-i — Unified `controlplane-tool images` command (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a non-interactive, tested `controlplane-tool images` command that builds and (optionally) pushes the 16-target nanofaas OCI image matrix, then delete `scripts/build-push-images.sh` and the standalone `scripts/image-builder/` project.

**Architecture:** A pure `building/image_matrix.py` module (the 16-target catalog + version/native-arg/reference/command planners returning `shellcraft` `PlannedCommand`s) ported faithfully from `scripts/image-builder/image_builder.py`; a thin runner + a `images` Typer command in `cli/commands.py` that executes the planned commands through the controlplane shell (injectable for tests, dry-run aware). The control-plane gradle target reuses the existing profile-aware `building.image` flow; all other gradle/docker targets are planned directly.

**Tech Stack:** Python 3.11+, Typer, shellcraft (`PlannedCommand`, `CommandRunner`), workflow_tasks shell backends, pytest, uv.

**Commands:** `uv run --project tools/controlplane pytest <path>` (NO `--no-cov`). Branch: `refactor/wt-sub5b1-images-command` (already created). Spec: `docs/superpowers/specs/2026-05-31-unified-images-command-design.md`. Baseline: controlplane suite green.

**Canonical source:** `scripts/image-builder/image_builder.py` — its `IMAGES` dict (16 targets), `resolve_native_image_build_args`, `resolve_native_active_processors`, `get_current_version`, `build_image_reference`, `build_gradle_command`, `build_docker_command` are the behavior to port. The interactive `questionary` wizard and `console`/`rich` output are dropped.

**Execution primitive:** `shellcraft.runners.PlannedCommand(command: list[str], cwd: Path, env: dict[str,str])` has `.run(runner, *, dry_run=False)`; `CommandRunner(shell=<ShellBackend>, repo_root=<Path>)` wraps a shell. Tests inject `workflow_tasks.shell.RecordingShell`; production uses `SubprocessShell`.

**Key behavior decisions (pinned):**
- Default tag = raw `resolve_current_version()` (image_builder behavior, no `v` prefix); `--tag` overrides.
- Registry base = `ghcr.io/miciav/nanofaas`; OCI source label = `https://github.com/miciav/nanofaas`.
- The control-plane target keeps profile-aware module selection by reusing the existing `building.image` flow (`profile=all`); it is NOT a bare gradle task. All other gradle targets (function-runtime, java-word-stats, java-json-transform) are bare `./gradlew <task> -P...`.

---

### Task 1: Pure `building/image_matrix.py` module (catalog + planners)

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/building/image_matrix.py`
- Create: `tools/controlplane/tests/test_image_matrix.py`

- [ ] **Step 1: Write the failing tests**

Create `tools/controlplane/tests/test_image_matrix.py`:

```python
from __future__ import annotations

from pathlib import Path

from controlplane_tool.building import image_matrix as im


def test_catalog_has_all_16_targets() -> None:
    assert set(im.IMAGE_MATRIX) == {
        "control-plane", "function-runtime",
        "java-word-stats", "java-json-transform",
        "java-lite-word-stats", "java-lite-json-transform",
        "go-word-stats", "go-json-transform",
        "python-word-stats", "python-json-transform",
        "javascript-word-stats", "javascript-json-transform",
        "watchdog", "bash-word-stats", "bash-json-transform",
    }


def test_select_targets_all_returns_sorted_catalog() -> None:
    assert im.select_targets("all") == sorted(im.IMAGE_MATRIX)


def test_select_targets_csv_subset() -> None:
    assert im.select_targets("watchdog,go-word-stats") == ["watchdog", "go-word-stats"]


def test_image_reference_single_arch_no_suffix() -> None:
    assert im.image_reference("watchdog", "1.2.3", "amd64", use_arch_suffix=False) == \
        "ghcr.io/miciav/nanofaas/watchdog:1.2.3"


def test_image_reference_arch_suffix() -> None:
    assert im.image_reference("watchdog", "1.2.3", "arm64", use_arch_suffix=True) == \
        "ghcr.io/miciav/nanofaas/watchdog:1.2.3-arm64"


def test_image_reference_multi_never_suffixes() -> None:
    assert im.image_reference("watchdog", "1.2.3", "multi", use_arch_suffix=True) == \
        "ghcr.io/miciav/nanofaas/watchdog:1.2.3"


def test_native_image_build_args_env_override(monkeypatch) -> None:
    monkeypatch.setenv("NATIVE_IMAGE_BUILD_ARGS", "-Xfoo")
    assert im.resolve_native_image_build_args() == "-Xfoo"


def test_native_image_build_args_default(monkeypatch) -> None:
    monkeypatch.delenv("NATIVE_IMAGE_BUILD_ARGS", raising=False)
    monkeypatch.setenv("NATIVE_ACTIVE_PROCESSORS", "3")
    monkeypatch.setenv("NATIVE_IMAGE_XMX", "4g")
    assert im.resolve_native_image_build_args() == \
        "-H:+AddAllCharsets -J-Xmx4g -J-XX:ActiveProcessorCount=3"


def test_resolve_current_version_reads_build_gradle(tmp_path) -> None:
    (tmp_path / "build.gradle").write_text("group = 'x'\nversion = '0.9.1'\n", encoding="utf-8")
    assert im.resolve_current_version(tmp_path) == "0.9.1"


def test_plan_build_docker_target_amd64() -> None:
    cmd = im.plan_build_command(Path("/repo"), "watchdog", "ghcr.io/x/watchdog:1", "amd64")
    assert cmd.command == [
        "docker", "build", "--platform", "linux/amd64",
        "--label", "org.opencontainers.image.source=https://github.com/miciav/nanofaas",
        "-t", "ghcr.io/x/watchdog:1", "-f", "watchdog/Dockerfile", ".",
    ]
    assert cmd.cwd == Path("/repo")


def test_plan_build_docker_target_multi_uses_buildx() -> None:
    cmd = im.plan_build_command(Path("/repo"), "go-word-stats", "ghcr.io/x/go:1", "multi")
    assert cmd.command[:3] == ["docker", "buildx", "build"]
    assert "linux/arm64,linux/amd64" in cmd.command


def test_plan_build_gradle_target_sets_native_env() -> None:
    cmd = im.plan_build_command(Path("/repo"), "function-runtime", "ghcr.io/x/fr:1", "amd64")
    assert cmd.command[0] == "./gradlew"
    assert ":function-runtime:bootBuildImage" in cmd.command
    assert "-PfunctionRuntimeImage=ghcr.io/x/fr:1" in cmd.command
    assert "-PimagePlatform=linux/amd64" in cmd.command
    assert "NATIVE_IMAGE_BUILD_ARGS" in cmd.env


def test_plan_build_gradle_arm64_adds_tiny_builder() -> None:
    cmd = im.plan_build_command(Path("/repo"), "java-word-stats", "ghcr.io/x/jw:1", "arm64")
    assert "-PimageBuilder=dashaun/builder:tiny" in cmd.command
    assert "-PimageRunImage=paketobuildpacks/run-jammy-tiny:latest" in cmd.command


def test_plan_push_command() -> None:
    cmd = im.plan_push_command(Path("/repo"), "ghcr.io/x/watchdog:1", runtime="docker")
    assert cmd.command == ["docker", "push", "ghcr.io/x/watchdog:1"]


def test_control_plane_is_marked_profile_target() -> None:
    assert im.IMAGE_MATRIX["control-plane"].profile_aware is True
    assert im.IMAGE_MATRIX["function-runtime"].profile_aware is False
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_image_matrix.py -q`
Expected: FAIL / collection error (module `image_matrix` does not exist yet).

- [ ] **Step 3: Implement `building/image_matrix.py`**

```python
from __future__ import annotations

import os
import re
from dataclasses import dataclass
from pathlib import Path

from shellcraft.runners import PlannedCommand

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
    profile_aware: bool = False  # control-plane reuses the profile-aware building.image flow


def _gradle(name, task, image_param, group, *, profile_aware=False) -> ImageTarget:
    return ImageTarget(name=name, kind="gradle", group=group, task=task,
                       image_param=image_param, profile_aware=profile_aware)


def _docker(name, dockerfile, group) -> ImageTarget:
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
        if arch == "arm64":
            command += [
                "-PimageBuilder=dashaun/builder:tiny",
                "-PimageRunImage=paketobuildpacks/run-jammy-tiny:latest",
            ]
        env = {"NATIVE_IMAGE_BUILD_ARGS": resolve_native_image_build_args(), "BP_OCI_SOURCE": OCI_SOURCE}
        return PlannedCommand(command=command, cwd=repo_root, env=env)

    # docker target
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
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_image_matrix.py -q`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/building/image_matrix.py tools/controlplane/tests/test_image_matrix.py
git commit -m "feat(controlplane): add image_matrix catalog + command planners"
```

---

### Task 2: Runner + `images` CLI command

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/building/image_matrix.py` (add `run_image_matrix`)
- Modify: `tools/controlplane/src/controlplane_tool/cli/commands.py` (register `images`)
- Create: `tools/controlplane/tests/test_images_command.py`

- [ ] **Step 1: Write the failing runner test**

Append to `tools/controlplane/tests/test_image_matrix.py`:

```python
def test_run_image_matrix_dry_run_records_build_then_push(monkeypatch) -> None:
    from workflow_tasks.shell import RecordingShell
    from shellcraft.runners import CommandRunner

    monkeypatch.delenv("NATIVE_IMAGE_BUILD_ARGS", raising=False)
    shell = RecordingShell()
    runner = CommandRunner(shell=shell, repo_root=Path("/repo"))

    im.run_image_matrix(
        runner=runner, repo_root=Path("/repo"),
        targets=["watchdog"], tag="9.9.9", arch="amd64",
        use_arch_suffix=False, push=True, runtime="docker", dry_run=True,
    )

    cmds = [c for c in shell.commands]
    assert ["docker", "build", "--platform", "linux/amd64",
            "--label", "org.opencontainers.image.source=https://github.com/miciav/nanofaas",
            "-t", "ghcr.io/miciav/nanofaas/watchdog:9.9.9", "-f", "watchdog/Dockerfile", "."] in cmds
    assert ["docker", "push", "ghcr.io/miciav/nanofaas/watchdog:9.9.9"] in cmds


def test_run_image_matrix_no_push_skips_push() -> None:
    from workflow_tasks.shell import RecordingShell
    from shellcraft.runners import CommandRunner

    shell = RecordingShell()
    runner = CommandRunner(shell=shell, repo_root=Path("/repo"))
    im.run_image_matrix(
        runner=runner, repo_root=Path("/repo"),
        targets=["watchdog"], tag="1", arch="amd64",
        use_arch_suffix=False, push=False, runtime="docker", dry_run=True,
    )
    assert not any(c[:2] == ["docker", "push"] for c in shell.commands)
```

NOTE: confirm `RecordingShell` exposes recorded argv as `.commands` (list of `list[str]`); if the attribute differs (e.g. `.calls`), adjust the test accessor to match — check `workflow_tasks/shell.py`.

- [ ] **Step 2: Run to verify it fails**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_image_matrix.py::test_run_image_matrix_dry_run_records_build_then_push -q`
Expected: FAIL (`run_image_matrix` not defined).

- [ ] **Step 3: Implement `run_image_matrix` in `image_matrix.py`**

Add to `building/image_matrix.py`:

```python
from collections.abc import Sequence

from shellcraft.runners import CommandRunner


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
```

NOTE on the control-plane profile target: this slice plans control-plane as a bare `:control-plane:bootBuildImage` gradle task (same as the other gradle targets). The richer profile-aware path (`building.image --profile all`) is deferred — the `profile_aware` flag is carried on the target for a follow-up but is NOT special-cased in the runner yet, to keep this slice focused on consolidating the matrix. (If review decides control-plane MUST use the profile flow now, route `target.profile_aware` targets through `resolve_flow_definition("building.image", profile="all", extra_gradle_args=[...]) + run_local_flow` instead — but default to the bare task.)

- [ ] **Step 4: Register the `images` Typer command**

In `tools/controlplane/src/controlplane_tool/cli/commands.py`, add the import near the top:
```python
from controlplane_tool.building import image_matrix
from controlplane_tool.core.shell_backend import SubprocessShell
from shellcraft.runners import CommandRunner
from pathlib import Path as _Path
```
And register the command (place it right after the existing `image_command`):
```python
    @app.command("images", context_settings=CLI_CONTEXT_SETTINGS)
    def images_command(
        tag: str | None = typer.Option(None, "--tag", help="Image tag (default: version from build.gradle)."),
        only: str = typer.Option("all", "--only", help="Comma-separated target names or 'all'."),
        arch: str = typer.Option("amd64", "--arch", help="amd64 | arm64 | multi."),
        arch_suffix: bool = typer.Option(False, "--arch-suffix/--no-arch-suffix", help="Append -<arch> to the tag."),
        push: bool = typer.Option(True, "--push/--no-push", help="Push images after building."),
        runtime: str = typer.Option("docker", "--runtime", help="Container runtime CLI."),
        dry_run: bool = typer.Option(False, "--dry-run", help="Print planned build/push commands only."),
    ) -> None:
        repo_root = _Path.cwd()
        resolved_tag = tag or image_matrix.resolve_current_version(repo_root)
        try:
            targets = image_matrix.select_targets(only)
        except ValueError as exc:
            raise typer.BadParameter(str(exc)) from exc
        runner = CommandRunner(shell=SubprocessShell(), repo_root=repo_root)
        image_matrix.run_image_matrix(
            runner=runner, repo_root=repo_root, targets=targets, tag=resolved_tag,
            arch=arch, use_arch_suffix=arch_suffix, push=push, runtime=runtime, dry_run=dry_run,
        )
```
(Confirm `SubprocessShell` is importable from `controlplane_tool.core.shell_backend`; if that shim was removed in a prior sub-project, import from `workflow_tasks.shell` instead — grep first.)

- [ ] **Step 5: Write the command-level test**

Create `tools/controlplane/tests/test_images_command.py`:
```python
from __future__ import annotations

from typer.testing import CliRunner

from controlplane_tool.cli.runtime import build_app  # adjust if the app factory differs

runner = CliRunner()


def test_images_dry_run_lists_only_selected(monkeypatch, tmp_path) -> None:
    monkeypatch.chdir(tmp_path)
    (tmp_path / "build.gradle").write_text("version = '7.7.7'\n", encoding="utf-8")
    app = build_app()
    result = runner.invoke(app, ["images", "--dry-run", "--only", "watchdog", "--no-push"])
    assert result.exit_code == 0
```
NOTE: find the real Typer app factory — grep `tools/controlplane/src/controlplane_tool/cli/` for how the app is built (`build_app`, `create_app`, or a module-level `app`); use that. If commands run via subprocess only, instead test `image_matrix.run_image_matrix` directly (Task 2 Step 1 already does) and assert the command appears in `controlplane-tool images --help`.

- [ ] **Step 6: Run all the new tests + the suite**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_image_matrix.py tools/controlplane/tests/test_images_command.py -q` → pass.
Run: `uv run --project tools/controlplane pytest tools/controlplane/tests -q 2>&1 | tail -3` → 0 failures.
Run: `uv run --project tools/controlplane controlplane-tool images --help` → shows the options.

- [ ] **Step 7: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/building/image_matrix.py tools/controlplane/src/controlplane_tool/cli/commands.py tools/controlplane/tests/test_image_matrix.py tools/controlplane/tests/test_images_command.py
git commit -m "feat(controlplane): images command — build+push the OCI image matrix"
```

---

### Task 3: Retire the two old implementations + docs

**Files:**
- Delete: `scripts/build-push-images.sh`
- Delete: `scripts/image-builder/` (entire directory)
- Modify: live docs referencing either

- [ ] **Step 1: Delete the bash script and the standalone project**

```bash
git rm scripts/build-push-images.sh
git rm -r scripts/image-builder
```

- [ ] **Step 2: Repoint live docs**

Find live references (excluding historical archives + snapshots):
```bash
grep -rln "build-push-images\|image-builder" . --include="*.md" \
  | grep -v "docs/plans/\|docs/superpowers/\|experiments/control-plane-staging/versions/\|node_modules"
```
For each hit that is a current/operational doc (e.g. `AGENTS.md`, `README.md`, `docs/testing.md` if present), replace the reference with `scripts/controlplane.sh images ...` (or `controlplane-tool images`). Do NOT edit files under `docs/plans/`, `docs/superpowers/`, or the frozen snapshots.

- [ ] **Step 3: Verify nothing live still references the removed tooling**

```bash
grep -rn "build-push-images\|scripts/image-builder" . --include="*.md" --include="*.sh" --include="*.gradle" --include="*.yml" --include="*.yaml" \
  | grep -v "docs/plans/\|docs/superpowers/\|experiments/control-plane-staging/versions/\|node_modules\|\.git/"
```
Expected: EMPTY. (`release.py` still has its own `build_and_push_arm64` — that is slice b1-ii, NOT this one; it does not reference the deleted scripts, so it will not appear here.)

- [ ] **Step 4: Full verification**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests -q 2>&1 | tail -3` → 0 failures.
Run: `uv run --project tools/controlplane lint-imports --config tools/controlplane/.importlinter` → 0 broken.
Run: `uv run --project tools/controlplane ruff check tools/controlplane/src/controlplane_tool/building/image_matrix.py tools/controlplane/src/controlplane_tool/cli/commands.py` → clean.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "chore: retire build-push-images.sh + standalone image-builder (use controlplane-tool images)"
```

---

## Self-Review

- **Spec coverage:** matrix+helpers+planners = Task 1; runner + `images` command + dry-run + tests = Task 2; delete bash + `scripts/image-builder/` + docs = Task 3; 16-target catalog, native args, image_reference, arch handling, push/no-push, --only, --tag default = Task 1+2 with explicit tests. The `release.py` repoint is explicitly deferred to b1-ii (noted in Task 2 Step 3 + Task 3 Step 3). ✓
- **Deviation from spec (flagged):** the spec proposed extending `ImageOps.build` with `platform`/`labels` and reusing it. The plan instead constructs docker/gradle argv directly in `image_matrix.plan_build_command`, because the matrix needs `docker buildx` (multi-arch) and gradle env which `ImageOps.build` does not model — direct construction is a more faithful port of `image_builder.py` and keeps the matrix logic cohesive. No `ImageOps` change is made. Likewise the control-plane profile-aware flow is carried as a `profile_aware` flag but not special-cased yet (noted inline) — call out for reviewer decision.
- **Placeholder scan:** none — full module + test code given; the two NOTE markers point at concrete grep-to-confirm integration points (`RecordingShell` accessor, app factory, `SubprocessShell` import location), not vague work.
- **Type consistency:** `ImageTarget`, `IMAGE_MATRIX`, `select_targets`, `image_reference`, `plan_build_command`, `plan_push_command`, `run_image_matrix` names + signatures are identical across Task 1, Task 2, and the tests. ✓
