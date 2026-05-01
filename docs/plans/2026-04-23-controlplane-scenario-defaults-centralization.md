# Controlplane Scenario Defaults Centralization Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Centralize scenario-specific default namespace and release policy so `cli-stack`, `cli-host`, and the recipe-based k3s flows all resolve deployment identity from one shared source of truth instead of scattered literals.

**Architecture:** Introduce a small pure helper module that owns scenario deployment defaults and explicit-over-default resolution. Normalize namespace and release at the context/entrypoint boundaries, then remove duplicated `if scenario == "cli-stack"` and hard-coded namespace/release literals from flow builders and runners. Keep the change narrow: no new settings system, no new model hierarchy, no behavior changes beyond making all paths agree on the same defaults.

**Tech Stack:** Python 3.13, Pydantic models, Typer CLI plumbing, pytest, existing `tools/controlplane` planner/runner architecture.

---

### Task 1: Add a Shared Scenario Deployment Defaults Helper

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/scenario_defaults.py`
- Test: `tools/controlplane/tests/test_scenario_defaults.py`

**Step 1: Write the failing test**

```python
from controlplane_tool.scenario_defaults import (
    resolve_scenario_namespace,
    resolve_scenario_release,
    scenario_deployment_defaults,
)


def test_cli_stack_defaults_are_isolated() -> None:
    defaults = scenario_deployment_defaults("cli-stack")

    assert defaults.namespace == "nanofaas-cli-stack-e2e"
    assert defaults.release == "nanofaas-cli-stack-e2e"


def test_cli_host_defaults_are_host_scoped() -> None:
    defaults = scenario_deployment_defaults("cli-host")

    assert defaults.namespace == "nanofaas-host-cli-e2e"
    assert defaults.release == "nanofaas-host-cli-e2e"


def test_resolve_namespace_prefers_explicit_then_resolved_then_default() -> None:
    assert resolve_scenario_namespace(
        "cli-stack",
        explicit_namespace="custom",
        resolved_scenario_namespace="ignored",
    ) == "custom"
    assert resolve_scenario_namespace(
        "cli-stack",
        explicit_namespace=None,
        resolved_scenario_namespace="from-scenario",
    ) == "from-scenario"
    assert resolve_scenario_namespace(
        "cli-stack",
        explicit_namespace=None,
        resolved_scenario_namespace=None,
    ) == "nanofaas-cli-stack-e2e"


def test_resolve_release_prefers_explicit_then_default() -> None:
    assert resolve_scenario_release("cli-stack", explicit_release="custom") == "custom"
    assert resolve_scenario_release("helm-stack", explicit_release=None) == "control-plane"
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_scenario_defaults.py -q`

Expected: FAIL with `ModuleNotFoundError` for `controlplane_tool.scenario_defaults`.

**Step 3: Write minimal implementation**

```python
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class ScenarioDeploymentDefaults:
    namespace: str | None = None
    release: str | None = None


_DEFAULTS: dict[str, ScenarioDeploymentDefaults] = {
    "cli": ScenarioDeploymentDefaults(namespace="nanofaas-e2e", release="control-plane"),
    "cli-stack": ScenarioDeploymentDefaults(
        namespace="nanofaas-cli-stack-e2e",
        release="nanofaas-cli-stack-e2e",
    ),
    "cli-host": ScenarioDeploymentDefaults(
        namespace="nanofaas-host-cli-e2e",
        release="nanofaas-host-cli-e2e",
    ),
    "helm-stack": ScenarioDeploymentDefaults(namespace="nanofaas-e2e", release="control-plane"),
    "k3s-junit-curl": ScenarioDeploymentDefaults(namespace="nanofaas-e2e", release="control-plane"),
}


def scenario_deployment_defaults(scenario: str) -> ScenarioDeploymentDefaults:
    return _DEFAULTS.get(scenario, ScenarioDeploymentDefaults())


def resolve_scenario_namespace(
    scenario: str,
    *,
    explicit_namespace: str | None,
    resolved_scenario_namespace: str | None,
) -> str | None:
    if explicit_namespace:
        return explicit_namespace
    if resolved_scenario_namespace:
        return resolved_scenario_namespace
    return scenario_deployment_defaults(scenario).namespace


def resolve_scenario_release(
    scenario: str,
    *,
    explicit_release: str | None,
) -> str | None:
    if explicit_release:
        return explicit_release
    return scenario_deployment_defaults(scenario).release
```

**Step 4: Run test to verify it passes**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_scenario_defaults.py -q`

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario_defaults.py tools/controlplane/tests/test_scenario_defaults.py
git commit -m "refactor: centralize scenario deployment defaults"
```

### Task 2: Normalize Recipe Context Resolution Around the Shared Helper

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario_components/environment.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e_runner.py`
- Test: `tools/controlplane/tests/test_scenario_environment.py`
- Test: `tools/controlplane/tests/test_e2e_runner.py`

**Step 1: Write the failing tests**

```python
def test_cli_stack_environment_resolver_sets_isolated_namespace_and_release(tmp_path: Path) -> None:
    request = E2eRequest(scenario="cli-stack", runtime="java", vm=None, namespace=None)

    context = resolve_scenario_environment(repo_root=tmp_path, request=request)

    assert context.namespace == "nanofaas-cli-stack-e2e"
    assert context.release == "nanofaas-cli-stack-e2e"


def test_cli_stack_plan_defaults_to_isolated_namespace_for_all_recipe_steps() -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())
    plan = runner.plan(
        E2eRequest(
            scenario="cli-stack",
            runtime="java",
            vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
            namespace=None,
        )
    )

    rendered = [
        " ".join(step.command)
        for step in plan.steps
        if any(token in " ".join(step.command) for token in (
            "platform install",
            "platform status",
            "helm uninstall",
            "kubectl delete namespace",
        ))
    ]

    assert rendered
    assert all("nanofaas-cli-stack-e2e" in command for command in rendered)
```

**Step 2: Run the targeted tests to verify they fail for the right reason**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_scenario_environment.py tools/controlplane/tests/test_e2e_runner.py -k "cli_stack and (environment_resolver or isolated_namespace)" -q`

Expected: FAIL because `context.namespace` is still `None` or the planned commands still contain `nanofaas-e2e`.

**Step 3: Write minimal implementation**

```python
from controlplane_tool.scenario_defaults import (
    resolve_scenario_namespace,
    resolve_scenario_release,
)


effective_namespace = resolve_scenario_namespace(
    request.scenario,
    explicit_namespace=request.namespace,
    resolved_scenario_namespace=(
        request.resolved_scenario.namespace if request.resolved_scenario is not None else None
    ),
)
effective_release = resolve_scenario_release(
    request.scenario,
    explicit_release=release,
)

return ScenarioExecutionContext(
    ...,
    namespace=effective_namespace,
    ...,
    release=effective_release,
)
```

Then simplify `plan_recipe_steps()` so it trusts `context.namespace` and `context.release` instead of re-deriving `cli-stack` defaults locally. Delete the planner-side `if scenario_name == "cli-stack"` fallback.

**Step 4: Run the focused regression tests**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_scenario_environment.py tools/controlplane/tests/test_e2e_runner.py -k "cli_stack or scenario_environment" -q`

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario_components/environment.py tools/controlplane/src/controlplane_tool/e2e_runner.py tools/controlplane/tests/test_scenario_environment.py tools/controlplane/tests/test_e2e_runner.py
git commit -m "refactor: resolve recipe defaults from shared scenario policy"
```

### Task 3: Remove Literal Defaults from Flows and Dedicated CLI Runners

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario_flows.py`
- Modify: `tools/controlplane/src/controlplane_tool/flow_catalog.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_test_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_stack_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_host_runner.py`
- Test: `tools/controlplane/tests/test_scenario_flows.py`
- Test: `tools/controlplane/tests/test_cli_test_runner.py`
- Test: `tools/controlplane/tests/test_flow_catalog.py`

**Step 1: Write the failing tests**

```python
def test_cli_host_flow_routes_with_host_cli_defaults(monkeypatch) -> None:
    called: dict[str, object] = {}

    monkeypatch.setattr(
        scenario_flows_mod.CliHostPlatformRunner,
        lambda repo_root, **kwargs: SimpleNamespace(
            run=lambda scenario_file=None: called.update(kwargs) or "ok"
        ),
        raising=False,
    )

    flow = build_scenario_flow("cli-host", repo_root=Path("/repo"))

    assert flow.run() == "ok"
    assert called["namespace"] == "nanofaas-host-cli-e2e"
    assert called["release"] == "nanofaas-host-cli-e2e"


def test_flow_catalog_passes_none_for_namespace_and_release_defaults(monkeypatch) -> None:
    captured: dict[str, object] = {}

    monkeypatch.setattr(
        flow_catalog_mod,
        "build_scenario_flow",
        lambda scenario, **kwargs: captured.update({"scenario": scenario, **kwargs}) or SimpleNamespace(
            flow_id="stub",
            task_ids=[],
            run=lambda: None,
        ),
    )

    resolve_flow_definition("e2e.cli-stack", repo_root=Path("/repo"), request=E2eRequest(scenario="cli-stack"))

    assert captured["namespace"] is None
    assert captured["release"] is None
```

Add or update one more runner-level test so `CliTestRunner.plan(CliTestRequest(scenario="cli-stack"))` still produces a managed VM plan without hard-coding `"nanofaas-cli-stack-e2e"` inside the runner.

**Step 2: Run the targeted tests to verify they fail**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_scenario_flows.py tools/controlplane/tests/test_cli_test_runner.py tools/controlplane/tests/test_flow_catalog.py -k "cli_stack or cli_host or flow_catalog" -q`

Expected: FAIL because callers still inject literal namespace/release defaults.

**Step 3: Write minimal implementation**

```python
from controlplane_tool.scenario_defaults import (
    resolve_scenario_namespace,
    resolve_scenario_release,
)


effective_namespace = resolve_scenario_namespace(
    scenario,
    explicit_namespace=namespace,
    resolved_scenario_namespace=None,
)
effective_release = resolve_scenario_release(
    scenario,
    explicit_release=release,
)
```

Apply that pattern in:
- `build_scenario_flow()` by changing the function signature defaults from string literals to `None`.
- `resolve_flow_definition()` by passing `None` for namespace/release unless the caller explicitly supplies them.
- `CliStackRunner.__init__()` by changing `namespace` and `release` defaults to `None` and resolving via the helper.
- `CliHostPlatformRunner.__init__()` by doing the same, using the helper so the direct runner and the flow entrypoint agree.
- `CliTestRunner` by delegating raw `request.namespace` and letting `CliStackRunner` own the default resolution.

**Step 4: Run the flow and runner regression tests**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_scenario_flows.py tools/controlplane/tests/test_cli_test_runner.py tools/controlplane/tests/test_flow_catalog.py -q`

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario_flows.py tools/controlplane/src/controlplane_tool/flow_catalog.py tools/controlplane/src/controlplane_tool/cli_test_runner.py tools/controlplane/src/controlplane_tool/cli_stack_runner.py tools/controlplane/src/controlplane_tool/cli_host_runner.py tools/controlplane/tests/test_scenario_flows.py tools/controlplane/tests/test_cli_test_runner.py tools/controlplane/tests/test_flow_catalog.py
git commit -m "refactor: reuse shared scenario defaults in flows and runners"
```

### Task 4: Prove the Refactor Removed Duplication and Preserved Behavior

**Files:**
- Verify: `tools/controlplane/src/controlplane_tool/scenario_defaults.py`
- Verify: `tools/controlplane/src/controlplane_tool/scenario_flows.py`
- Verify: `tools/controlplane/src/controlplane_tool/e2e_runner.py`
- Verify: `tools/controlplane/src/controlplane_tool/cli_test_runner.py`
- Verify: `tools/controlplane/src/controlplane_tool/cli_stack_runner.py`
- Verify: `tools/controlplane/src/controlplane_tool/cli_host_runner.py`
- Verify: `tools/controlplane/src/controlplane_tool/scenario_components/environment.py`

**Step 1: Grep for stale duplicated literals**

Run: `rg -n "nanofaas-cli-stack-e2e|nanofaas-host-cli-e2e|if scenario == \\\"cli-stack\\\"" tools/controlplane/src/controlplane_tool`

Expected: only the helper module and behavior-oriented tests should contain the scenario-specific strings. The source tree should no longer have policy duplicated across flow/planner/runner files.

**Step 2: Run the full targeted regression matrix**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_scenario_defaults.py tools/controlplane/tests/test_scenario_environment.py tools/controlplane/tests/test_e2e_runner.py tools/controlplane/tests/test_scenario_flows.py tools/controlplane/tests/test_cli_test_runner.py tools/controlplane/tests/test_flow_catalog.py -q`

Expected: PASS.

**Step 3: Run one broader controlplane suite slice**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_tui_choices.py -k "cli_stack or cli_host" -q`

Expected: PASS.

**Step 4: Final review with @superpowers:verification-before-completion**

Check that:
- explicit `namespace` still wins over defaults
- `resolved_scenario.namespace` still wins over defaults
- `cli-stack` always lands on `nanofaas-cli-stack-e2e` when no namespace is supplied
- `cli-host` always lands on `nanofaas-host-cli-e2e` when no namespace/release is supplied
- recipe planners no longer contain policy literals

**Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool tools/controlplane/tests
git commit -m "test: lock scenario default resolution regressions"
```

### Notes for Execution

- Use `@superpowers:test-driven-development` for each task. Do not write production code before the failing test is observed.
- Do not broaden scope into profile UX, scenario-file semantics, or non-k8s scenarios unless a test fails and proves they are coupled.
- Prefer deleting duplicated fallback logic over layering new wrappers around it.
- If a refactor forces more than these files, stop and re-evaluate before proceeding.
