# Control Plane Tooling Milestone 1 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Establish one canonical control-plane orchestration product for `build`, `run`, `image`, `test`, and `inspect`, shared by both non-interactive CLI and interactive TUI, while keeping shell wrappers as compatibility shims.

**Architecture:** Move the current `tooling/controlplane_tui` app under a canonical root `tools/controlplane/`, introduce a centralized command-planning layer for Gradle invocations, and make both CLI and TUI call the same execution engine. Keep `scripts/controlplane-tool.sh` and add `scripts/control-plane-build.sh` as thin wrappers during migration.

**Tech Stack:** Python, Typer, Rich/questionary, Pydantic, pytest, Gradle, Docker-compatible runtime.

---

### Task 1: Create the canonical product root

**Files:**
- Create: `tools/controlplane/pyproject.toml`
- Create: `tools/controlplane/src/controlplane_tool/__init__.py`
- Create: `tools/controlplane/tests/test_cli_smoke.py`
- Modify: `scripts/controlplane-tool.sh`

**Step 1: Write the failing test**

```python
from typer.testing import CliRunner

from controlplane_tool.main import app


def test_cli_help_exits_zero() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["--help"])
    assert result.exit_code == 0
    assert "control-plane orchestration" in result.stdout.lower()
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_cli_smoke.py -v`

Expected: FAIL because `tools/controlplane/` package does not exist yet.

**Step 3: Write minimal implementation**

- Copy the current packaging metadata from `tooling/controlplane_tui/pyproject.toml`.
- Keep the same package name `controlplane_tool`.
- Update `scripts/controlplane-tool.sh` to point to `tools/controlplane`.

**Step 4: Run test to verify it passes**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_cli_smoke.py -v`

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane/pyproject.toml tools/controlplane/src/controlplane_tool/__init__.py tools/controlplane/tests/test_cli_smoke.py scripts/controlplane-tool.sh
git commit -m "refactor: create canonical controlplane tooling root"
```

### Task 2: Move the current app code and keep imports working

**Files:**
- Move: `tooling/controlplane_tui/src/controlplane_tool/*` → `tools/controlplane/src/controlplane_tool/`
- Move: `tooling/controlplane_tui/tests/*` → `tools/controlplane/tests/`
- Move: `tooling/controlplane_tui/assets/` → `tools/controlplane/assets/`
- Modify: `tools/controlplane/pyproject.toml`
- Modify: `tooling/controlplane_tui/README.md`

**Step 1: Write the failing test**

Use the moved smoke test plus one existing test:

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_profiles.py tools/controlplane/tests/test_cli_smoke.py -v`

Expected: FAIL because the modules/tests have not been moved yet.

**Step 2: Move the files with no behavioral change**

- Preserve package name `controlplane_tool`.
- Preserve test file contents verbatim at first.
- Preserve entry point `controlplane-tool = "controlplane_tool.main:main"`.

**Step 3: Fix minimal path/package fallout**

- Update any hard-coded project-relative paths that still assume `tooling/controlplane_tui`.
- Keep behavior identical to current code.

**Step 4: Run tests to verify they pass**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_profiles.py tools/controlplane/tests/test_cli_smoke.py -v`

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane tooling/controlplane_tui/README.md
git commit -m "refactor: move controlplane tooling into canonical root"
```

### Task 3: Centralize filesystem layout and runtime paths

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/paths.py`
- Modify: `tools/controlplane/src/controlplane_tool/profiles.py`
- Modify: `tools/controlplane/src/controlplane_tool/pipeline.py`
- Modify: `tools/controlplane/src/controlplane_tool/main.py`
- Test: `tools/controlplane/tests/test_profiles.py`
- Create: `tools/controlplane/tests/test_paths.py`

**Step 1: Write the failing test**

```python
from pathlib import Path

from controlplane_tool.paths import ToolPaths


def test_default_paths_are_rooted_under_tools_controlplane() -> None:
    paths = ToolPaths.repo_root(Path("/repo"))
    assert paths.tool_root == Path("/repo/tools/controlplane")
    assert paths.profiles_dir == Path("/repo/tools/controlplane/profiles")
    assert paths.runs_dir == Path("/repo/tools/controlplane/runs")
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_paths.py -v`

Expected: FAIL because `paths.py` does not exist.

**Step 3: Write minimal implementation**

Create a small path model, for example:

```python
@dataclass(frozen=True)
class ToolPaths:
    repo_root: Path
    tool_root: Path
    profiles_dir: Path
    runs_dir: Path
```

- Replace hard-coded `tooling/profiles` and `tooling/runs` defaults.
- Keep optional overrides for tests.

**Step 4: Run targeted tests**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_paths.py tools/controlplane/tests/test_profiles.py -v`

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/paths.py tools/controlplane/src/controlplane_tool/profiles.py tools/controlplane/src/controlplane_tool/pipeline.py tools/controlplane/src/controlplane_tool/main.py tools/controlplane/tests/test_paths.py tools/controlplane/tests/test_profiles.py
git commit -m "refactor: centralize controlplane tooling paths"
```

### Task 4: Introduce a central build request model

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/build_requests.py`
- Modify: `tools/controlplane/src/controlplane_tool/models.py`
- Test: `tools/controlplane/tests/test_build_requests.py`

**Step 1: Write the failing test**

```python
from controlplane_tool.build_requests import BuildRequest, resolve_modules_selector


def test_profile_name_container_local_maps_to_expected_modules() -> None:
    request = BuildRequest(action="jar", profile="container-local")
    assert resolve_modules_selector(request) == "container-deployment-provider"


def test_profile_name_core_maps_to_none_selector() -> None:
    request = BuildRequest(action="test", profile="core")
    assert resolve_modules_selector(request) == "none"
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_build_requests.py -v`

Expected: FAIL because `build_requests.py` does not exist.

**Step 3: Write minimal implementation**

Model:

```python
Action = Literal["build", "run", "image", "native", "test", "inspect"]
ProfileName = Literal["core", "k8s", "container-local", "all"]
```

Rules:

- `core` → `none`
- `k8s` → `k8s-deployment-provider`
- `container-local` → `container-deployment-provider`
- `all` → `all`
- explicit `modules` overrides profile-derived modules

**Step 4: Run test to verify it passes**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_build_requests.py -v`

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/build_requests.py tools/controlplane/src/controlplane_tool/models.py tools/controlplane/tests/test_build_requests.py
git commit -m "refactor: add centralized build request model"
```

### Task 5: Replace ad-hoc Gradle command assembly with a command planner

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/gradle_planner.py`
- Modify: `tools/controlplane/src/controlplane_tool/adapters.py`
- Test: `tools/controlplane/tests/test_gradle_planner.py`
- Test: `tools/controlplane/tests/test_adapters_k6_url.py`
- Test: `tools/controlplane/tests/test_adapters_metrics_prometheus_bootstrap.py`

**Step 1: Write the failing test**

```python
from pathlib import Path

from controlplane_tool.build_requests import BuildRequest
from controlplane_tool.gradle_planner import build_gradle_command


def test_image_request_uses_boot_build_image_and_profile_modules() -> None:
    command = build_gradle_command(
        repo_root=Path("/repo"),
        request=BuildRequest(action="image", profile="k8s"),
        extra_gradle_args=["-PcontrolPlaneImage=nanofaas/control-plane:test"],
    )
    assert command[:2] == ["/repo/gradlew", ":control-plane:bootBuildImage"]
    assert "-PcontrolPlaneModules=k8s-deployment-provider" in command
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_gradle_planner.py -v`

Expected: FAIL because `gradle_planner.py` does not exist.

**Step 3: Write minimal implementation**

Centralize all mappings:

- `build` → `:control-plane:bootJar`
- `run` → `:control-plane:bootRun`
- `image` → `:control-plane:bootBuildImage`
- `native` → `:control-plane:nativeCompile`
- `test` → `:control-plane:test`
- `inspect` → `:control-plane:printSelectedControlPlaneModules`

- Update `ShellCommandAdapter` to consume the planner instead of `_modules_arg()`.
- Remove duplicate Gradle assembly from compile/build-image/test paths where possible.

**Step 4: Run targeted tests**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_gradle_planner.py tools/controlplane/tests/test_adapters_k6_url.py tools/controlplane/tests/test_adapters_metrics_prometheus_bootstrap.py -v`

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/gradle_planner.py tools/controlplane/src/controlplane_tool/adapters.py tools/controlplane/tests/test_gradle_planner.py tools/controlplane/tests/test_adapters_k6_url.py tools/controlplane/tests/test_adapters_metrics_prometheus_bootstrap.py
git commit -m "refactor: centralize control plane gradle command planning"
```

### Task 6: Add the non-interactive CLI surface for milestone 1

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/main.py`
- Create: `tools/controlplane/src/controlplane_tool/cli_commands.py`
- Test: `tools/controlplane/tests/test_cli_commands.py`
- Test: `tools/controlplane/tests/test_cli_run_behavior.py`

**Step 1: Write the failing test**

```python
from typer.testing import CliRunner

from controlplane_tool.main import app


def test_build_command_accepts_profile_and_non_interactive_args() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["build", "--profile", "core", "--dry-run"])
    assert result.exit_code == 0
    assert "bootJar" in result.stdout
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_cli_commands.py -v`

Expected: FAIL because `build` command does not exist.

**Step 3: Write minimal implementation**

Add commands:

- `build`
- `run`
- `image`
- `native`
- `test`
- `inspect`

Required options:

- `--profile`
- `--modules`
- `--dry-run`
- `--extra-gradle-arg` or `--` passthrough

Keep the current interactive profile wizard flow available, but move it under an explicit command such as `tui` or `pipeline-run`.

**Step 4: Run command tests**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_cli_commands.py tools/controlplane/tests/test_cli_run_behavior.py -v`

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/main.py tools/controlplane/src/controlplane_tool/cli_commands.py tools/controlplane/tests/test_cli_commands.py tools/controlplane/tests/test_cli_run_behavior.py
git commit -m "feat: add non-interactive controlplane build commands"
```

### Task 7: Make the TUI call the same engine as the CLI

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/main.py`
- Modify: `tools/controlplane/src/controlplane_tool/pipeline.py`
- Modify: `tools/controlplane/src/controlplane_tool/tui.py`
- Modify: `tools/controlplane/src/controlplane_tool/control_plane_runtime.py`
- Test: `tools/controlplane/tests/test_run_integration.py`
- Test: `tools/controlplane/tests/test_cli_run_behavior.py`

**Step 1: Write the failing test**

Extend an existing integration/smoke test to verify the interactive entrypoint and the new CLI path produce the same run artifact shape:

```python
assert (result.run_dir / "summary.json").exists()
assert (result.run_dir / "report.html").exists()
```

Add a new assertion that both pathways use the same `PipelineRunner`.

**Step 2: Run test to verify it fails**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_run_integration.py tools/controlplane/tests/test_cli_run_behavior.py -v`

Expected: FAIL because CLI and TUI entrypoints are still split.

**Step 3: Write minimal implementation**

- Introduce one execution service for milestone 1 actions.
- Ensure `main.py` interactive flow only collects/loads profile, then delegates to the same runner layer used by non-interactive commands.
- Update `control_plane_runtime.py` to use the centralized planner for `bootRun`.

**Step 4: Run tests**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_run_integration.py tools/controlplane/tests/test_cli_run_behavior.py -v`

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/main.py tools/controlplane/src/controlplane_tool/pipeline.py tools/controlplane/src/controlplane_tool/tui.py tools/controlplane/src/controlplane_tool/control_plane_runtime.py tools/controlplane/tests/test_run_integration.py tools/controlplane/tests/test_cli_run_behavior.py
git commit -m "refactor: share execution engine between cli and tui"
```

### Task 8: Add shell compatibility wrappers with no business logic

**Files:**
- Modify: `scripts/controlplane-tool.sh`
- Create: `scripts/control-plane-build.sh`
- Test: `tools/controlplane/tests/test_wrapper_docs.py`

**Step 1: Write the failing test**

```python
from pathlib import Path


def test_control_plane_build_wrapper_uses_tools_controlplane_project() -> None:
    script = Path("scripts/control-plane-build.sh").read_text(encoding="utf-8")
    assert "uv run --project tools/controlplane" in script
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_wrapper_docs.py -v`

Expected: FAIL because wrapper does not exist.

**Step 3: Write minimal implementation**

- `scripts/controlplane-tool.sh` remains a thin launcher only.
- `scripts/control-plane-build.sh` forwards to the new CLI and does not assemble Gradle commands itself.

**Step 4: Run test**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_wrapper_docs.py -v`

Expected: PASS.

**Step 5: Commit**

```bash
git add scripts/controlplane-tool.sh scripts/control-plane-build.sh tools/controlplane/tests/test_wrapper_docs.py
git commit -m "refactor: add thin compatibility wrappers for controlplane tooling"
```

### Task 9: Update milestone 1 docs to expose one UX

**Files:**
- Modify: `README.md`
- Modify: `docs/control-plane.md`
- Modify: `docs/control-plane-modules.md`
- Modify: `docs/quickstart.md`
- Modify: `tools/controlplane/README.md`
- Test: `tools/controlplane/tests/test_docs_links.py`

**Step 1: Write the failing test**

Add/update assertions such as:

```python
assert "scripts/control-plane-build.sh" in quickstart
assert "tools/controlplane" in tool_readme
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_docs_links.py -v`

Expected: FAIL because docs still point at the old layout and old UX.

**Step 3: Update documentation**

- Document the canonical tool root.
- Show the new wrapper-based commands for milestone 1.
- Mark old raw Gradle module-selection recipes as low-level/advanced.

**Step 4: Run docs test**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_docs_links.py -v`

Expected: PASS.

**Step 5: Commit**

```bash
git add README.md docs/control-plane.md docs/control-plane-modules.md docs/quickstart.md tools/controlplane/README.md tools/controlplane/tests/test_docs_links.py
git commit -m "docs: align control plane tooling docs with unified ux"
```

### Task 10: Milestone 1 verification sweep

**Files:**
- No new files required unless fixes emerge

**Step 1: Run the tool test suite**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests -v`

Expected: PASS.

**Step 2: Run CLI dry-run smoke commands**

Run:

```bash
scripts/control-plane-build.sh build --profile core --dry-run
scripts/control-plane-build.sh image --profile all --dry-run
scripts/control-plane-build.sh test --profile k8s --dry-run
scripts/control-plane-build.sh inspect --profile container-local --dry-run
```

Expected: all exit `0`, print the planned Gradle invocation, and never assemble commands in shell.

**Step 3: Run one real build command**

Run: `scripts/control-plane-build.sh build --profile core`

Expected: PASS and produce the control-plane JAR.

**Step 4: Run one real test command**

Run: `scripts/control-plane-build.sh test --profile core -- --tests '*CoreDefaultsTest'`

Expected: PASS.

**Step 5: Commit final stabilization if needed**

```bash
git add -A
git commit -m "test: verify unified controlplane tooling milestone 1"
```
