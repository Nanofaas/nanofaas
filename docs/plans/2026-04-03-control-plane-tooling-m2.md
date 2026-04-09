# Control Plane Tooling Milestone 2 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Migrate the build-related control-plane consumers from raw Gradle task assembly to one unified wrapper and CLI contract, without yet redesigning the full VM/E2E orchestration model.

**Architecture:** Keep `scripts/control-plane-build.sh` as the shell-facing compatibility facade and make every build-related caller delegate to the `tools/controlplane/` execution engine. Extend the engine only where milestone 2 needs a stable automation contract (`jar` alias, module-combination matrix), then migrate call sites by family: CI/image build, local scripts, remote/VM scripts, Java helper tests, and docs.

**Tech Stack:** Python, Typer, pytest, shell scripts, GitHub Actions YAML, Java/JUnit 5, Gradle, existing control-plane wrapper and planner.

---

## Scope Guard

**In scope**

- `.github/workflows/gitops.yml`
- `scripts/build-push-images.sh`
- `scripts/release-manager/release.py`
- `scripts/native-build.sh`
- `scripts/test-control-plane-module-combinations.sh`
- `scripts/e2e.sh`
- `scripts/e2e-buildpack.sh`
- `scripts/e2e-container-local.sh`
- `scripts/e2e-k8s-vm.sh`
- `scripts/e2e-k3s-helm.sh`
- `scripts/lib/e2e-k3s-common.sh`
- `scripts/image-builder/image_builder.py`
- `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/e2e/BuildpackE2eTest.java`
- build-related docs that still present raw `./gradlew :control-plane:...` as the primary UX

**Out of scope**

- redesign of VM lifecycle and Ansible orchestration
- scenario/function selection model
- load-testing orchestration redesign
- `nanofaas-cli` integrated flows
- `experiments/**` cleanup unless a primary consumer depends on it

## Milestone 2 Contract

At the end of this milestone, build-related control-plane consumers should converge on:

```text
scripts/control-plane-build.sh jar --profile <profile> [--modules <csv>] [-- <extra gradle args>]
scripts/control-plane-build.sh run --profile <profile> [--modules <csv>] [-- <extra gradle args>]
scripts/control-plane-build.sh image --profile <profile> [--modules <csv>] [-- <extra gradle args>]
scripts/control-plane-build.sh native --profile <profile> [--modules <csv>] [-- <extra gradle args>]
scripts/control-plane-build.sh test --profile <profile> [--modules <csv>] [-- <extra gradle args>]
scripts/control-plane-build.sh inspect --profile <profile> [--modules <csv>] [-- <extra gradle args>]
scripts/control-plane-build.sh matrix --task <gradle-task> [--modules <csv>] [--max-combinations <n>] [--dry-run]
```

Rules:

- `--profile` is the normal user-facing selector.
- `--modules` remains the escape hatch.
- raw `-PcontrolPlaneModules=...` assembly should disappear from the milestone 2 consumers.
- raw `./gradlew :control-plane:*` invocations may remain only in low-level docs/examples explicitly labeled as advanced.

### Task 1: Harden the unified CLI contract for automation consumers

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/build_requests.py`
- Modify: `tools/controlplane/src/controlplane_tool/gradle_planner.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_commands.py`
- Modify: `tools/controlplane/src/controlplane_tool/main.py`
- Test: `tools/controlplane/tests/test_cli_commands.py`
- Test: `tools/controlplane/tests/test_gradle_planner.py`

**Step 1: Write the failing test**

Add coverage for the missing automation-facing commands:

```python
from typer.testing import CliRunner

from controlplane_tool.main import app


def test_jar_command_maps_to_bootjar() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["jar", "--profile", "core", "--dry-run"])
    assert result.exit_code == 0
    assert ":control-plane:bootJar" in result.stdout


def test_matrix_command_accepts_task_override() -> None:
    runner = CliRunner()
    result = runner.invoke(
        app,
        ["matrix", "--task", ":control-plane:test", "--max-combinations", "1", "--dry-run"],
    )
    assert result.exit_code == 0
    assert ":control-plane:test" in result.stdout
    assert ":control-plane:printSelectedControlPlaneModules" in result.stdout
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_cli_commands.py tools/controlplane/tests/test_gradle_planner.py -v`

Expected: FAIL because `jar` and `matrix` are not fully implemented yet.

**Step 3: Write minimal implementation**

- Add `jar` as an explicit alias for the existing `bootJar` mapping.
- Add a matrix command path that computes selectors from `control-plane-modules/` or explicit `--modules`.
- Keep matrix planning inside the Python engine, not in shell.
- Reuse the same selector normalization and Gradle planning code already introduced in milestone 1.

Suggested shape:

```python
@app.command("jar", context_settings=CLI_CONTEXT_SETTINGS)
def jar_command(...):
    _run_gradle_action(action="jar", ...)


@app.command("matrix")
def matrix_command(task: str = ":control-plane:bootJar", ...):
    ...
```

**Step 4: Run test to verify it passes**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_cli_commands.py tools/controlplane/tests/test_gradle_planner.py -v`

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/build_requests.py tools/controlplane/src/controlplane_tool/gradle_planner.py tools/controlplane/src/controlplane_tool/cli_commands.py tools/controlplane/src/controlplane_tool/main.py tools/controlplane/tests/test_cli_commands.py tools/controlplane/tests/test_gradle_planner.py
git commit -m "feat: add automation-friendly controlplane build commands"
```

### Task 2: Migrate CI and image-build producers to the wrapper

**Files:**
- Modify: `.github/workflows/gitops.yml`
- Modify: `scripts/build-push-images.sh`
- Modify: `scripts/release-manager/release.py`
- Modify: `scripts/image-builder/image_builder.py`
- Create: `scripts/tests/test_gitops_workflow_control_plane_build.py`
- Modify: `scripts/tests/test_build_push_images_native_args.py`
- Modify: `scripts/tests/test_release_manager_native_args.py`
- Modify: `scripts/image-builder/tests/test_image_builder.py`

**Step 1: Write the failing test**

Lock the new contract in text-level tests before changing the callers:

```python
from pathlib import Path


def test_gitops_workflow_uses_control_plane_build_wrapper() -> None:
    workflow = Path(".github/workflows/gitops.yml").read_text(encoding="utf-8")
    assert "scripts/control-plane-build.sh image --profile all" in workflow
    assert "./gradlew :control-plane:bootBuildImage" not in workflow
```

Update the existing script tests to require wrapper usage, for example:

```python
assert "scripts/control-plane-build.sh image --profile all --" in script
assert ":control-plane:bootBuildImage" not in script
```

For `scripts/image-builder/image_builder.py`, update the command expectation:

```python
assert cmd.startswith("./scripts/control-plane-build.sh image --profile all -- ")
```

**Step 2: Run test to verify it fails**

Run: `python3 -m pytest scripts/tests/test_gitops_workflow_control_plane_build.py scripts/tests/test_build_push_images_native_args.py scripts/tests/test_release_manager_native_args.py scripts/image-builder/tests/test_image_builder.py -q`

Expected: FAIL because these call sites still invoke raw control-plane Gradle tasks.

**Step 3: Write minimal implementation**

- Replace direct control-plane `bootBuildImage` invocations with `scripts/control-plane-build.sh image --profile all -- ...`.
- Preserve environment prefixes such as `NATIVE_IMAGE_BUILD_ARGS=...` and `BP_OCI_SOURCE=...`.
- Keep function-runtime/example image builds unchanged unless they are explicitly part of the control-plane call site being migrated.
- In Python command builders, emit wrapper commands instead of raw control-plane Gradle tasks.

Example shell shape:

```bash
NATIVE_IMAGE_BUILD_ARGS="$RESOLVED_NATIVE_IMAGE_BUILD_ARGS" \
BP_OCI_SOURCE="$OCI_SOURCE" \
./scripts/control-plane-build.sh image --profile all -- -PcontrolPlaneImage="$IMG"
```

**Step 4: Run test to verify it passes**

Run: `python3 -m pytest scripts/tests/test_gitops_workflow_control_plane_build.py scripts/tests/test_build_push_images_native_args.py scripts/tests/test_release_manager_native_args.py scripts/image-builder/tests/test_image_builder.py -q`

Expected: PASS.

**Step 5: Commit**

```bash
git add .github/workflows/gitops.yml scripts/build-push-images.sh scripts/release-manager/release.py scripts/image-builder/image_builder.py scripts/tests/test_gitops_workflow_control_plane_build.py scripts/tests/test_build_push_images_native_args.py scripts/tests/test_release_manager_native_args.py scripts/image-builder/tests/test_image_builder.py
git commit -m "refactor: route control-plane image builds through wrapper"
```

### Task 3: Delegate native and module-matrix scripts to the same entrypoint

**Files:**
- Modify: `scripts/native-build.sh`
- Modify: `scripts/test-control-plane-module-combinations.sh`
- Create: `scripts/tests/test_native_build_wrapper.py`
- Create: `scripts/tests/test_control_plane_module_matrix_wrapper.py`

**Step 1: Write the failing test**

Create text-level regression tests:

```python
from pathlib import Path


def test_native_build_uses_control_plane_wrapper() -> None:
    script = Path("scripts/native-build.sh").read_text(encoding="utf-8")
    assert "./scripts/control-plane-build.sh native --profile all" in script
    assert "./gradlew :control-plane:nativeCompile" not in script


def test_module_combination_script_delegates_to_matrix_command() -> None:
    script = Path("scripts/test-control-plane-module-combinations.sh").read_text(encoding="utf-8")
    assert "scripts/control-plane-build.sh matrix" in script
    assert ":control-plane:printSelectedControlPlaneModules" not in script
```

**Step 2: Run test to verify it fails**

Run: `python3 -m pytest scripts/tests/test_native_build_wrapper.py scripts/tests/test_control_plane_module_matrix_wrapper.py -q`

Expected: FAIL because both scripts still contain raw control-plane Gradle logic.

**Step 3: Write minimal implementation**

- In `scripts/native-build.sh`, switch the control-plane native compile to the wrapper and leave `:function-runtime:nativeCompile` raw.
- Turn `scripts/test-control-plane-module-combinations.sh` into a thin compatibility layer over `scripts/control-plane-build.sh matrix ...`.
- Keep existing flags (`--task`, `--modules`, `--max-combinations`, `--dry-run`) intact.

Expected shell shape:

```bash
./scripts/control-plane-build.sh native --profile all
./scripts/control-plane-build.sh matrix --task "${TASK}" --modules "${MODULES_CSV}" --max-combinations "${MAX_COMBINATIONS}"
```

**Step 4: Run test to verify it passes**

Run: `python3 -m pytest scripts/tests/test_native_build_wrapper.py scripts/tests/test_control_plane_module_matrix_wrapper.py -q`

Expected: PASS.

**Step 5: Commit**

```bash
git add scripts/native-build.sh scripts/test-control-plane-module-combinations.sh scripts/tests/test_native_build_wrapper.py scripts/tests/test_control_plane_module_matrix_wrapper.py
git commit -m "refactor: delegate native and matrix scripts to wrapper"
```

### Task 4: Migrate local and VM-triggered E2E runners to wrapper commands

**Files:**
- Modify: `scripts/e2e.sh`
- Modify: `scripts/e2e-buildpack.sh`
- Modify: `scripts/e2e-container-local.sh`
- Modify: `scripts/e2e-k8s-vm.sh`
- Modify: `scripts/tests/test_e2e_runtime_runners.py`
- Modify: `scripts/tests/test_e2e_runtime_contract.py`

**Step 1: Write the failing test**

Tighten the current runner tests to require wrapper usage:

```python
def test_e2e_container_local_script_uses_wrapper_for_control_plane_jar() -> None:
    script = read_script("e2e-container-local.sh")
    assert "scripts/control-plane-build.sh jar --profile container-local" in script
    assert ":control-plane:bootJar" not in script


def test_e2e_k8s_vm_uses_wrapper_for_control_plane_test() -> None:
    script = read_script("e2e-k8s-vm.sh")
    assert "scripts/control-plane-build.sh test --profile k8s" in script
    assert ":control-plane:test" not in script
```

Also update the existing `e2e.sh` and `e2e-buildpack.sh` assertions so they require wrapper-based control-plane calls.

**Step 2: Run test to verify it fails**

Run: `python3 -m pytest scripts/tests/test_e2e_runtime_runners.py scripts/tests/test_e2e_runtime_contract.py -q`

Expected: FAIL because these runners still call raw control-plane Gradle tasks.

**Step 3: Write minimal implementation**

- Replace control-plane `bootJar` and `test` calls with wrapper commands.
- For remote execution strings, call `./scripts/control-plane-build.sh ...` from the synced repo root on the VM.
- Keep unrelated function-runtime builds raw for this milestone.
- Preserve existing env variables and `--tests ...` pass-through using `--`.

Examples:

```bash
./scripts/control-plane-build.sh test --profile all -- -PrunE2e --tests it.unimib.datai.nanofaas.controlplane.e2e.E2eFlowTest
./scripts/control-plane-build.sh jar --profile container-local -- --quiet
vm_exec "cd ${REMOTE_DIR} && ./scripts/control-plane-build.sh test --profile k8s -- -PrunE2e --tests it.unimib.datai.nanofaas.controlplane.e2e.K8sE2eTest --no-daemon"
```

**Step 4: Run test to verify it passes**

Run: `python3 -m pytest scripts/tests/test_e2e_runtime_runners.py scripts/tests/test_e2e_runtime_contract.py -q`

Expected: PASS.

**Step 5: Commit**

```bash
git add scripts/e2e.sh scripts/e2e-buildpack.sh scripts/e2e-container-local.sh scripts/e2e-k8s-vm.sh scripts/tests/test_e2e_runtime_runners.py scripts/tests/test_e2e_runtime_contract.py
git commit -m "refactor: switch e2e runners to controlplane wrapper"
```

### Task 5: Migrate k3s helm helpers and Java buildpack helper paths

**Files:**
- Modify: `scripts/e2e-k3s-helm.sh`
- Modify: `scripts/lib/e2e-k3s-common.sh`
- Modify: `scripts/tests/test_e2e_k3s_helm_control_plane_native.py`
- Modify: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/e2e/BuildpackE2eTest.java`
- Create: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/e2e/BuildpackE2eCommandTest.java`

**Step 1: Write the failing test**

First, update the shell test to require wrapper-based control-plane calls:

```python
def test_k3s_helm_script_uses_wrapper_for_control_plane_builds() -> None:
    script = SCRIPT.read_text(encoding="utf-8")
    assert "scripts/control-plane-build.sh image --profile k8s" in script
    assert "scripts/control-plane-build.sh jar --profile k8s" in script
    assert ":control-plane:bootBuildImage" not in script
    assert ":control-plane:bootJar -PcontrolPlaneModules=" not in script
```

Then extract a pure Java command-construction assertion:

```java
@Test
void buildCommandUsesWrapperForControlPlaneImage() {
    List<String> command = BuildpackE2eTest.controlPlaneImageCommand(false);
    assertThat(command).contains("./scripts/control-plane-build.sh", "image", "--profile", "all");
}
```

**Step 2: Run test to verify it fails**

Run: `python3 -m pytest scripts/tests/test_e2e_k3s_helm_control_plane_native.py -q`

Run: `./gradlew :control-plane:test --tests '*BuildpackE2eCommandTest'`

Expected: FAIL because the scripts and Java helper still construct raw control-plane Gradle commands.

**Step 3: Write minimal implementation**

- In `scripts/e2e-k3s-helm.sh`, replace host and remote control-plane image/jar build calls with wrapper commands plus `--` for extra Gradle properties.
- In `scripts/lib/e2e-k3s-common.sh`, separate the control-plane artifact build from the other module artifact builds so the control-plane piece can use the wrapper.
- In `BuildpackE2eTest.java`, extract command construction into helper methods and make the control-plane portion call the wrapper.
- Add a small unit-style Java test that validates the generated command list without requiring Docker.

Representative shell shape:

```bash
NATIVE_IMAGE_BUILD_ARGS='${RESOLVED_CP_NATIVE_IMAGE_BUILD_ARGS}' \
BP_OCI_SOURCE=https://github.com/miciav/nanofaas \
./scripts/control-plane-build.sh image --profile k8s -- -PcontrolPlaneImage=${CONTROL_IMAGE} --no-daemon
```

**Step 4: Run test to verify it passes**

Run: `python3 -m pytest scripts/tests/test_e2e_k3s_helm_control_plane_native.py -q`

Run: `./gradlew :control-plane:test --tests '*BuildpackE2eCommandTest'`

Run: `bash -n scripts/e2e-k3s-helm.sh scripts/lib/e2e-k3s-common.sh`

Expected: PASS.

**Step 5: Commit**

```bash
git add scripts/e2e-k3s-helm.sh scripts/lib/e2e-k3s-common.sh scripts/tests/test_e2e_k3s_helm_control_plane_native.py control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/e2e/BuildpackE2eTest.java control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/e2e/BuildpackE2eCommandTest.java
git commit -m "refactor: route k3s and buildpack helpers through wrapper"
```

### Task 6: Update docs so the wrapper is the primary build UX

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`
- Modify: `docs/control-plane.md`
- Modify: `docs/control-plane-modules.md`
- Modify: `docs/no-k8s-profile.md`
- Modify: `docs/quickstart.md`
- Modify: `docs/tutorial-java-function.md`
- Modify: `docs/testing.md`
- Modify: `tools/controlplane/README.md`
- Modify: `tools/controlplane/tests/test_docs_links.py`
- Modify: `tools/controlplane/tests/test_wrapper_docs.py`

**Step 1: Write the failing test**

Extend the existing docs tests with milestone 2 expectations:

```python
assert "scripts/control-plane-build.sh image --profile all" in root_readme
assert "scripts/control-plane-build.sh jar --profile container-local" in control_plane
assert "scripts/control-plane-build.sh matrix" in testing
assert "./gradlew :control-plane:bootJar -PcontrolPlaneModules=" not in modules
```

Also add a wrapper-doc test for the new commands:

```python
script = Path("scripts/control-plane-build.sh").read_text(encoding="utf-8")
assert "controlplane-tool" in script
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_docs_links.py tools/controlplane/tests/test_wrapper_docs.py -v`

Expected: FAIL because docs still present raw Gradle as a normal workflow in several places.

**Step 3: Write minimal implementation**

- Make `scripts/control-plane-build.sh ...` the first-class documentation path for build/run/image/test/inspect/matrix.
- Keep raw Gradle examples only in advanced or low-level notes.
- Update no-k8s instructions to use `--profile container-local`.
- Update testing docs to mention wrapper-driven test and matrix flows.

**Step 4: Run test to verify it passes**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_docs_links.py tools/controlplane/tests/test_wrapper_docs.py -v`

Expected: PASS.

**Step 5: Commit**

```bash
git add README.md CLAUDE.md docs/control-plane.md docs/control-plane-modules.md docs/no-k8s-profile.md docs/quickstart.md docs/tutorial-java-function.md docs/testing.md tools/controlplane/README.md tools/controlplane/tests/test_docs_links.py tools/controlplane/tests/test_wrapper_docs.py
git commit -m "docs: make controlplane wrapper the primary build interface"
```

### Task 7: Run milestone verification from the unified interface

**Files:**
- No code changes required unless verification reveals fallout.

**Step 1: Run the focused Python and shell suites**

Run:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests/test_cli_commands.py tools/controlplane/tests/test_gradle_planner.py tools/controlplane/tests/test_docs_links.py tools/controlplane/tests/test_wrapper_docs.py -v
python3 -m pytest scripts/tests/test_gitops_workflow_control_plane_build.py scripts/tests/test_build_push_images_native_args.py scripts/tests/test_release_manager_native_args.py scripts/tests/test_native_build_wrapper.py scripts/tests/test_control_plane_module_matrix_wrapper.py scripts/tests/test_e2e_runtime_runners.py scripts/tests/test_e2e_runtime_contract.py scripts/tests/test_e2e_k3s_helm_control_plane_native.py scripts/image-builder/tests/test_image_builder.py -q
bash -n scripts/control-plane-build.sh scripts/build-push-images.sh scripts/native-build.sh scripts/e2e.sh scripts/e2e-buildpack.sh scripts/e2e-container-local.sh scripts/e2e-k8s-vm.sh scripts/e2e-k3s-helm.sh scripts/lib/e2e-k3s-common.sh scripts/test-control-plane-module-combinations.sh
```

Expected: PASS.

**Step 2: Run wrapper dry-runs to validate the new user contract**

Run:

```bash
scripts/control-plane-build.sh jar --profile core --dry-run
scripts/control-plane-build.sh image --profile all --dry-run -- -PcontrolPlaneImage=nanofaas/control-plane:test
scripts/control-plane-build.sh test --profile k8s --dry-run -- -PrunE2e --tests it.unimib.datai.nanofaas.controlplane.e2e.K8sE2eTest
scripts/control-plane-build.sh matrix --task :control-plane:bootJar --max-combinations 2 --dry-run
```

Expected: each command prints a wrapper-planned invocation with no raw caller-side module assembly.

**Step 3: Run the Java helper verification**

Run: `./gradlew :control-plane:test --tests '*BuildpackE2eCommandTest'`

Expected: PASS.

**Step 4: Commit any final fallout fixes**

```bash
git add -A
git commit -m "test: verify controlplane build call-site migration"
```

Only create this final commit if verification required follow-up edits.
