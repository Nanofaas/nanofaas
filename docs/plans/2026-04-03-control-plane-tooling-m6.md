# Control Plane Tooling Milestone 6 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make `nanofaas-cli` validation a first-class part of `controlplane-tool`, so CLI smoke, deploy, host-platform, and VM-backed flows can be executed through one typed command surface that reuses the same scenario selection and VM/environment model as the E2E toolchain.

**Architecture:** Build a dedicated `cli-test` domain on top of the Milestone 3 VM/E2E adapters and the existing shell backends for `nanofaas-cli`. Model CLI test scenarios explicitly, wire them into the main Typer app and TUI, and keep the old `scripts/e2e-cli*.sh` files as thin compatibility wrappers during the milestone. Separate unit-test style validation of the `nanofaas-cli` Gradle module from end-to-end deployment flows, but expose both from one product surface.

**Tech Stack:** Python, Typer, pytest, Gradle, existing `scripts/lib/e2e-cli-*.sh` backends, existing VM adapters, `nanofaas-cli` Gradle tasks and JUnit tests.

---

## Scope Guard

**In scope**

- `cli-test` command group with discoverable scenarios
- integration of `nanofaas-cli` unit/integration Gradle tests
- integration of CLI E2E flows with VM and host backends
- reuse of function/scenario selection from Milestone 4
- compatibility wrappers for the existing CLI E2E scripts
- TUI/profile support for saved CLI validation defaults

**Out of scope**

- redesign of `nanofaas-cli` command semantics
- rewrite of CLI shell backends to native Python
- final removal of legacy wrappers

## Milestone 6 Contract

At the end of this milestone, the repository should support this UX:

```text
scripts/controlplane.sh cli-test list
scripts/controlplane.sh cli-test run unit
scripts/controlplane.sh cli-test run deploy-host --function-preset demo-java --dry-run
scripts/controlplane.sh cli-test run vm --saved-profile demo-java
scripts/controlplane.sh cli-test run host-platform --scenario-file tools/controlplane/scenarios/k8s-demo-java.toml --dry-run
scripts/e2e-cli.sh --dry-run
scripts/e2e-cli-host-platform.sh --dry-run
scripts/e2e-cli-deploy-host.sh --dry-run
```

Rules:

- `cli-test` scenarios are explicit and discoverable
- Gradle module tests and E2E CLI workflows are separate scenario types, but surfaced under one group
- function selection and scenario manifests are reused where deployment flows need them
- VM-backed CLI tests reuse the same lifecycle/session model as `e2e`

### Task 1: Add typed CLI test models and scenario catalog

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/cli_test_models.py`
- Create: `tools/controlplane/src/controlplane_tool/cli_test_catalog.py`
- Modify: `tools/controlplane/src/controlplane_tool/models.py`
- Create: `tools/controlplane/tests/test_cli_test_models.py`
- Create: `tools/controlplane/tests/test_cli_test_catalog.py`

**Step 1: Write the failing tests**

Add tests like:

```python
from controlplane_tool.cli_test_catalog import list_cli_test_scenarios, resolve_cli_test_scenario


def test_cli_test_catalog_exposes_unit_vm_host_platform_and_deploy_host() -> None:
    names = [scenario.name for scenario in list_cli_test_scenarios()]
    assert names == ["unit", "vm", "host-platform", "deploy-host"]


def test_vm_cli_scenario_requires_vm() -> None:
    scenario = resolve_cli_test_scenario("vm")
    assert scenario.requires_vm is True
```

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_cli_test_models.py \
  tools/controlplane/tests/test_cli_test_catalog.py -v
```

Expected: FAIL because the CLI-test domain does not exist yet.

**Step 3: Write minimal implementation**

Create a small typed catalog with at least:

- `unit`
- `vm`
- `host-platform`
- `deploy-host`

Each scenario should declare:

- whether it needs a VM
- whether it needs function selection
- whether it requires `:nanofaas-cli:test` or `:nanofaas-cli:installDist`

**Step 4: Run tests to verify they pass**

Run the same command from Step 2.

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/cli_test_models.py \
  tools/controlplane/src/controlplane_tool/cli_test_catalog.py \
  tools/controlplane/src/controlplane_tool/models.py \
  tools/controlplane/tests/test_cli_test_models.py \
  tools/controlplane/tests/test_cli_test_catalog.py
git commit -m "feat: add cli-test domain catalog"
```

### Task 2: Build the `cli-test` runner on top of the existing adapters and backends

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/cli_test_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/vm_adapter.py`
- Modify: `scripts/lib/e2e-cli-backend.sh`
- Modify: `scripts/lib/e2e-cli-host-backend.sh`
- Modify: `scripts/lib/e2e-deploy-host-backend.sh`
- Create: `tools/controlplane/tests/test_cli_test_runner.py`
- Modify: `scripts/tests/test_controlplane_e2e_wrapper_runtime.py`
- Modify: `scripts/tests/test_e2e_runtime_runners.py`

**Step 1: Write the failing tests**

Add tests that prove the runner chooses the right execution path:

```python
def test_cli_test_runner_unit_scenario_calls_gradle_cli_tests() -> None:
    ...


def test_cli_test_runner_vm_scenario_routes_to_cli_backend_with_manifest() -> None:
    ...


def test_cli_test_runner_host_platform_routes_to_host_backend() -> None:
    ...
```

Add wrapper/runtime tests that assert the shell backends remain concrete workflows, not placeholders.

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_cli_test_runner.py -v

python3 -m pytest \
  scripts/tests/test_controlplane_e2e_wrapper_runtime.py \
  scripts/tests/test_e2e_runtime_runners.py -q
```

Expected: FAIL because the dedicated runner and routing do not exist yet.

**Step 3: Write minimal implementation**

Create `CliTestRunner` that:

- runs `:nanofaas-cli:test` for `unit`
- runs `:nanofaas-cli:installDist` plus the correct backend for `vm`, `host-platform`, and `deploy-host`
- passes the resolved scenario manifest when the chosen scenario needs deployment fixtures
- reuses `VmRequest` and the shell backend abstraction instead of reimplementing remote logic

Keep the shell backends as low-level executors for now.

**Step 4: Run tests to verify they pass**

Run the same commands from Step 2.

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/cli_test_runner.py \
  tools/controlplane/src/controlplane_tool/e2e_runner.py \
  tools/controlplane/src/controlplane_tool/vm_adapter.py \
  scripts/lib/e2e-cli-backend.sh \
  scripts/lib/e2e-cli-host-backend.sh \
  scripts/lib/e2e-deploy-host-backend.sh \
  tools/controlplane/tests/test_cli_test_runner.py \
  scripts/tests/test_controlplane_e2e_wrapper_runtime.py \
  scripts/tests/test_e2e_runtime_runners.py
git commit -m "feat: add cli-test execution runner"
```

### Task 3: Expose `cli-test` commands and compatibility wrappers

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/cli_test_commands.py`
- Modify: `tools/controlplane/src/controlplane_tool/main.py`
- Modify: `scripts/controlplane.sh`
- Modify: `scripts/e2e-cli.sh`
- Modify: `scripts/e2e-cli-host-platform.sh`
- Modify: `scripts/e2e-cli-deploy-host.sh`
- Create: `tools/controlplane/tests/test_cli_test_commands.py`
- Modify: `tools/controlplane/tests/test_cli_smoke.py`
- Create: `scripts/tests/test_cli_test_wrapper_runtime.py`

**Step 1: Write the failing tests**

Add tests like:

```python
def test_cli_test_group_lists_known_scenarios() -> None:
    result = CliRunner().invoke(app, ["cli-test", "list"])
    assert result.exit_code == 0
    assert "vm" in result.stdout
    assert "deploy-host" in result.stdout


def test_cli_test_run_vm_dry_run_renders_backend_steps() -> None:
    result = CliRunner().invoke(app, ["cli-test", "run", "vm", "--dry-run"])
    assert result.exit_code == 0
    assert "e2e-cli-backend.sh" in result.stdout
```

Add wrapper tests that assert the legacy scripts forward to `controlplane.sh cli-test run ...`.

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_cli_test_commands.py \
  tools/controlplane/tests/test_cli_smoke.py -v

python3 -m pytest scripts/tests/test_cli_test_wrapper_runtime.py -q
```

Expected: FAIL because the command group and wrapper forwarding do not exist yet.

**Step 3: Write minimal implementation**

Expose:

- `cli-test list`
- `cli-test run <scenario>`
- `cli-test inspect <scenario>`

Keep the legacy top-level CLI E2E scripts as thin wrappers only.

**Step 4: Run tests to verify they pass**

Run the same commands from Step 2.

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/cli_test_commands.py \
  tools/controlplane/src/controlplane_tool/main.py \
  scripts/controlplane.sh \
  scripts/e2e-cli.sh \
  scripts/e2e-cli-host-platform.sh \
  scripts/e2e-cli-deploy-host.sh \
  tools/controlplane/tests/test_cli_test_commands.py \
  tools/controlplane/tests/test_cli_smoke.py \
  scripts/tests/test_cli_test_wrapper_runtime.py
git commit -m "feat: add cli-test commands and wrappers"
```

### Task 4: Integrate saved profiles, TUI, and docs for CLI validation

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/profiles.py`
- Modify: `tools/controlplane/src/controlplane_tool/tui.py`
- Modify: `tools/controlplane/README.md`
- Modify: `README.md`
- Modify: `docs/nanofaas-cli.md`
- Modify: `docs/testing.md`
- Modify: `tools/controlplane/tests/test_profiles.py`
- Modify: `tools/controlplane/tests/test_tui_choices.py`
- Modify: `tools/controlplane/tests/test_wrapper_docs.py`
- Modify: `tools/controlplane/tests/test_docs_links.py`

**Step 1: Write the failing tests**

Add tests that lock:

- saved profiles can store a default `cli-test` scenario
- the TUI can select CLI validation without spawning a separate execution system
- docs point users to `scripts/controlplane.sh cli-test ...` as the canonical path

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_profiles.py \
  tools/controlplane/tests/test_tui_choices.py \
  tools/controlplane/tests/test_wrapper_docs.py \
  tools/controlplane/tests/test_docs_links.py -v
```

Expected: FAIL until the saved-profile schema, TUI copy, and docs are updated.

**Step 3: Write minimal implementation**

Add CLI-test defaults to saved profiles, expose the option in the TUI, and update the docs so old direct script invocation is described only as compatibility behavior.

**Step 4: Run tests to verify they pass**

Run the same command from Step 2.

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/profiles.py \
  tools/controlplane/src/controlplane_tool/tui.py \
  tools/controlplane/README.md \
  README.md \
  docs/nanofaas-cli.md \
  docs/testing.md \
  tools/controlplane/tests/test_profiles.py \
  tools/controlplane/tests/test_tui_choices.py \
  tools/controlplane/tests/test_wrapper_docs.py \
  tools/controlplane/tests/test_docs_links.py
git commit -m "docs: align tooling with integrated cli validation"
```

### Task 5: Final verification for Milestone 6

**Files:**
- No new files; verification only

**Step 1: Run focused tests**

Run:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests -v
python3 -m pytest \
  scripts/tests/test_cli_test_wrapper_runtime.py \
  scripts/tests/test_controlplane_e2e_wrapper_runtime.py \
  scripts/tests/test_e2e_runtime_runners.py -q
./gradlew :nanofaas-cli:test
```

Expected: PASS.

**Step 2: Run CLI dry-runs**

Run:

```bash
scripts/controlplane.sh cli-test list
scripts/controlplane.sh cli-test run vm --dry-run
scripts/controlplane.sh cli-test run deploy-host --function-preset demo-java --dry-run
scripts/e2e-cli.sh --dry-run
```

Expected: commands succeed and show one canonical execution path.

**Step 3: Commit**

```bash
git add -A
git commit -m "chore: verify milestone 6 cli-test workflow"
```
