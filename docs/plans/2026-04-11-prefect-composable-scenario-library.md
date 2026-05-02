# Prefect-Composable Scenario Library Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Follow `@superpowers:test-driven-development` for every code change and keep commits small.

**Goal:** Build a library of independent, testable, Prefect-compatible scenario components that compose self-sufficient `k3s-junit-curl`, `helm-stack`, and `cli-stack` flows without requiring a pre-existing VM or scenario-specific software on the host, and without using shell code as the component API.

**Architecture:** Introduce a new component layer between `VmOrchestrator` and the concrete scenario runners. Each component exposes a stable `task_id`, typed inputs, and a deterministic Python planner that returns typed execution operations instead of shell snippets; recipes compose those components into the three target scenarios. Add an environment resolver that can materialize a managed VM automatically when the request does not provide one, so scenarios become self-contained and stop depending on `vm_request_from_env()` at composition time.

**Tech Stack:** Python 3.12, `uv`, `pytest`, Pydantic, Typer, Prefect-local `LocalFlowDefinition`, `VmOrchestrator`, Ansible, Multipass SDK.

---

## Scope and assumptions

This plan deliberately separates two concerns that are mixed today:

1. environment acquisition and bootstrap
2. scenario-specific test logic

Additional hard requirement:

- the new scenario-component library must not use shell code as its public representation
- component planners must return typed Python operation objects, not shell strings
- any unavoidable shell invocation must stay hidden inside a low-level execution adapter and must not leak into recipes, component contracts, or tests

The new component library must cover these reusable building blocks:

- managed VM acquisition
- base dependency provisioning inside the VM
- repo sync into the VM
- k3s installation
- registry installation and k3s registry wiring
- core image build
- selected function image build
- Helm deploy and readiness checks
- CLI platform lifecycle
- CLI function lifecycle
- scenario-specific verification and cleanup

The three first-class composed scenarios must be:

- `k3s-junit-curl`
- `helm-stack`
- `cli-stack`

Interpretation of “must not depend on software installed on the host” for this plan:

- no scenario may require host-installed Helm, kubectl, k3s, Docker registry tooling, or `nanofaas-cli`
- if a VM is needed and not explicitly provided, the scenario must create and configure one
- the controlplane tool itself and the selected VM provider remain host prerequisites

Non-goals:

- no removal of the existing `host-platform` compatibility path in this batch
- no rewrite of the Java or native CLI implementation
- no packaging change for the controlplane tool runtime itself

## Target file set

New component layer:

- Create: `tools/controlplane/src/controlplane_tool/scenario_components/__init__.py`
- Create: `tools/controlplane/src/controlplane_tool/scenario_components/models.py`
- Create: `tools/controlplane/src/controlplane_tool/scenario_components/operations.py`
- Create: `tools/controlplane/src/controlplane_tool/scenario_components/environment.py`
- Create: `tools/controlplane/src/controlplane_tool/scenario_components/bootstrap.py`
- Create: `tools/controlplane/src/controlplane_tool/scenario_components/images.py`
- Create: `tools/controlplane/src/controlplane_tool/scenario_components/helm.py`
- Create: `tools/controlplane/src/controlplane_tool/scenario_components/cli.py`
- Create: `tools/controlplane/src/controlplane_tool/scenario_components/verification.py`
- Create: `tools/controlplane/src/controlplane_tool/scenario_components/cleanup.py`
- Create: `tools/controlplane/src/controlplane_tool/scenario_components/recipes.py`
- Create: `tools/controlplane/src/controlplane_tool/scenario_components/composer.py`
- Create: `tools/controlplane/src/controlplane_tool/scenario_components/executor.py`

Existing orchestration and model files to refactor:

- Modify: `tools/controlplane/src/controlplane_tool/e2e_models.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_test_models.py`
- Modify: `tools/controlplane/src/controlplane_tool/vm_cluster_workflows.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_stack_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_test_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario_flows.py`
- Modify: `tools/controlplane/src/controlplane_tool/flow_catalog.py`
- Modify: `tools/controlplane/src/controlplane_tool/tui_app.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_host_runner.py`

Tests:

- Create: `tools/controlplane/tests/test_scenario_component_models.py`
- Create: `tools/controlplane/tests/test_scenario_environment.py`
- Create: `tools/controlplane/tests/test_scenario_component_library.py`
- Create: `tools/controlplane/tests/test_scenario_recipes.py`
- Modify: `tools/controlplane/tests/test_scenario_flows.py`
- Modify: `tools/controlplane/tests/test_flow_catalog.py`
- Modify: `tools/controlplane/tests/test_e2e_runner.py`
- Modify: `tools/controlplane/tests/test_cli_test_runner.py`
- Modify: `tools/controlplane/tests/test_cli_runtime.py`
- Modify: `tools/controlplane/tests/test_tui_choices.py`
- Modify: `tools/controlplane/tests/test_docs_links.py`
- Modify: `tools/controlplane/tests/test_canonical_entrypoints.py`

Docs:

- Modify: `docs/testing.md`
- Modify: `tools/controlplane/README.md`
- Modify: `README.md`

## Task 1: Lock the component architecture with failing tests

**Files:**
- Create: `tools/controlplane/tests/test_scenario_component_models.py`
- Create: `tools/controlplane/tests/test_scenario_environment.py`
- Create: `tools/controlplane/tests/test_scenario_recipes.py`
- Modify: `tools/controlplane/tests/test_scenario_flows.py`
- Modify: `tools/controlplane/tests/test_flow_catalog.py`

**Step 1: Write the failing tests**

Add contract tests that describe the target design before implementation:

```python
def test_component_model_has_stable_task_id_and_summary() -> None:
    component = ScenarioComponentDefinition(
        component_id="vm.ensure_running",
        summary="Ensure VM is running",
    )
    assert component.component_id == "vm.ensure_running"
    assert component.summary == "Ensure VM is running"


def test_component_planner_returns_typed_operations_not_shell_strings() -> None:
    component = resolve_component("vm.ensure_running")
    operations = component.planner(make_context())
    assert operations
    assert all(isinstance(op, ScenarioOperation) for op in operations)
    assert not any(isinstance(op, str) for op in operations)


def test_environment_resolver_creates_managed_vm_when_request_has_none(tmp_path: Path) -> None:
    request = E2eRequest(scenario="helm-stack", runtime="java", vm=None)
    context = resolve_scenario_environment(repo_root=tmp_path, request=request)
    assert context.vm_request is not None
    assert context.vm_request.lifecycle == "multipass"


def test_cli_stack_recipe_is_independent_and_self_bootstrapping() -> None:
    recipe = build_scenario_recipe("cli-stack")
    assert recipe.requires_managed_vm is True
    assert recipe.component_ids[:6] == [
        "vm.ensure_running",
        "vm.provision_base",
        "repo.sync_to_vm",
        "registry.ensure_container",
        "k3s.install",
        "k3s.configure_registry",
    ]


def test_helm_stack_recipe_and_cli_stack_recipe_share_components_without_sharing_tail() -> None:
    helm_recipe = build_scenario_recipe("helm-stack")
    cli_recipe = build_scenario_recipe("cli-stack")
    assert helm_recipe.component_ids[:8] == cli_recipe.component_ids[:8]
    assert helm_recipe.component_ids != cli_recipe.component_ids
```

Add flow-level assertions that:

- `resolve_flow_task_ids("e2e.k3s-junit-curl")` comes from recipe composition rather than a hard-coded map
- `resolve_flow_task_ids("e2e.helm-stack")` and `resolve_flow_task_ids("e2e.cli-stack")` are recipe-derived
- `build_scenario_flow("cli-stack", ...)` no longer assumes an externally supplied VM request

**Step 2: Run the tests to verify they fail**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_scenario_component_models.py \
  tools/controlplane/tests/test_scenario_environment.py \
  tools/controlplane/tests/test_scenario_recipes.py \
  tools/controlplane/tests/test_scenario_flows.py \
  tools/controlplane/tests/test_flow_catalog.py -q
```

Expected: FAIL because the component layer, environment resolver, and recipe-driven task IDs do not exist yet.

**Step 3: Commit the red tests**

```bash
git add tools/controlplane/tests/test_scenario_component_models.py tools/controlplane/tests/test_scenario_environment.py tools/controlplane/tests/test_scenario_recipes.py tools/controlplane/tests/test_scenario_flows.py tools/controlplane/tests/test_flow_catalog.py
git commit -m "Add scenario component architecture tests"
```

## Task 2: Create the component model and recipe composer

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/scenario_components/__init__.py`
- Create: `tools/controlplane/src/controlplane_tool/scenario_components/models.py`
- Create: `tools/controlplane/src/controlplane_tool/scenario_components/composer.py`
- Create: `tools/controlplane/src/controlplane_tool/scenario_components/recipes.py`
- Create: `tools/controlplane/tests/test_scenario_component_library.py`

**Step 1: Write the failing test**

Add a small library-level test that proves one recipe can be expanded into ordered component definitions:

```python
def test_compose_recipe_returns_ordered_component_definitions() -> None:
    recipe = build_scenario_recipe("k3s-junit-curl")
    components = compose_recipe(recipe)
    assert components[0].component_id == "vm.ensure_running"
    assert components[-1].component_id == "vm.down"
```

**Step 2: Run the test to verify it fails**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_scenario_component_library.py::test_compose_recipe_returns_ordered_component_definitions -q
```

Expected: FAIL with missing imports or undefined recipe/composer symbols.

**Step 3: Write the minimal implementation**

Create the component primitives as pure dataclasses and helpers:

```python
@dataclass(frozen=True)
class ScenarioComponentDefinition:
    component_id: str
    summary: str
    planner: Callable[[ScenarioExecutionContext], list[ScenarioPlanStep]]


@dataclass(frozen=True)
class ScenarioRecipe:
    name: str
    component_ids: list[str]
    requires_managed_vm: bool = False
```

Create a typed operation model:

```python
@dataclass(frozen=True)
class ScenarioOperation:
    operation_id: str
    summary: str


@dataclass(frozen=True)
class RemoteCommandOperation(ScenarioOperation):
    argv: tuple[str, ...]
    env: Mapping[str, str] = field(default_factory=dict)
```

In `composer.py`, add:

- `compose_recipe(recipe: ScenarioRecipe) -> list[ScenarioComponentDefinition]`
- `recipe_task_ids(recipe: ScenarioRecipe) -> list[str]`

In `operations.py`, define only typed Python operation classes. Do not represent planned work as raw shell strings.

In `recipes.py`, define only the static recipe lists for:

- `k3s-junit-curl`
- `helm-stack`
- `cli-stack`

Do not implement execution yet; only the recipe model and deterministic ordering.

**Step 4: Run the tests to verify they pass**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_scenario_component_models.py \
  tools/controlplane/tests/test_scenario_component_library.py \
  tools/controlplane/tests/test_scenario_recipes.py -q
```

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario_components/__init__.py tools/controlplane/src/controlplane_tool/scenario_components/models.py tools/controlplane/src/controlplane_tool/scenario_components/composer.py tools/controlplane/src/controlplane_tool/scenario_components/recipes.py tools/controlplane/tests/test_scenario_component_library.py
git commit -m "Add scenario component models and recipes"
```

## Task 3: Add a self-sufficient environment resolver

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/scenario_components/environment.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e_models.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_test_models.py`
- Modify: `tools/controlplane/tests/test_scenario_environment.py`
- Modify: `tools/controlplane/tests/test_cli_test_models.py`

**Step 1: Write the failing tests**

Add tests that prove the VM can be omitted from the request for VM-backed composed scenarios:

```python
def test_e2e_request_allows_missing_vm_for_managed_vm_scenarios() -> None:
    request = E2eRequest(scenario="k3s-junit-curl", runtime="java", vm=None)
    assert request.vm is None


def test_cli_test_request_cli_stack_can_be_resolved_without_vm() -> None:
    request = CliTestRequest(scenario="cli-stack", runtime="java", vm=None)
    context = resolve_scenario_environment(repo_root=Path("/repo"), request=request)
    assert context.vm_request is not None
```

**Step 2: Run the tests to verify they fail**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_scenario_environment.py \
  tools/controlplane/tests/test_cli_test_models.py -q
```

Expected: FAIL because `E2eRequest` currently requires `vm` for VM-backed scenarios and no environment resolver exists.

**Step 3: Write the minimal implementation**

Add a typed execution context:

```python
@dataclass(frozen=True)
class ScenarioExecutionContext:
    repo_root: Path
    scenario_name: str
    runtime: str
    namespace: str
    local_registry: str
    resolved_scenario: ResolvedScenario | None
    vm_request: VmRequest
```

In `environment.py`, implement:

- `default_managed_vm_request() -> VmRequest`
- `resolve_scenario_environment(repo_root: Path, request: E2eRequest | CliTestRequest) -> ScenarioExecutionContext`

Refactor the model validators so they no longer reject `vm=None` for managed VM-backed scenarios. Validation should still reject impossible combinations, but environment materialization becomes the resolver’s job.

Do not add shell-generation helpers here. This layer is only responsible for typed context resolution.

**Step 4: Run the tests to verify they pass**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_scenario_environment.py \
  tools/controlplane/tests/test_cli_test_models.py -q
```

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario_components/environment.py tools/controlplane/src/controlplane_tool/e2e_models.py tools/controlplane/src/controlplane_tool/cli_test_models.py tools/controlplane/tests/test_scenario_environment.py tools/controlplane/tests/test_cli_test_models.py
git commit -m "Add managed scenario environment resolver"
```

## Task 4: Extract the shared bootstrap and infrastructure components

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/scenario_components/bootstrap.py`
- Create: `tools/controlplane/src/controlplane_tool/scenario_components/images.py`
- Create: `tools/controlplane/src/controlplane_tool/scenario_components/helm.py`
- Modify: `tools/controlplane/src/controlplane_tool/vm_cluster_workflows.py`
- Modify: `tools/controlplane/tests/test_scenario_component_library.py`
- Modify: `tools/controlplane/tests/test_e2e_runner.py`

**Step 1: Write the failing tests**

Add tests for concrete planners:

```python
def test_bootstrap_component_plans_vm_and_cluster_setup(tmp_path: Path) -> None:
    context = make_context(tmp_path, scenario_name="helm-stack")
    operations = plan_component("k3s.install", context)
    assert operations[0].summary == "Install k3s in VM"


def test_registry_component_is_vm_local_not_host_local(tmp_path: Path) -> None:
    context = make_context(tmp_path, scenario_name="cli-stack")
    operations = plan_component("registry.ensure_container", context)
    assert operations[0].operation_id == "registry.ensure_container"
    assert isinstance(operations[0], RemoteCommandOperation)
```

**Step 2: Run the tests to verify they fail**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_scenario_component_library.py \
  tools/controlplane/tests/test_e2e_runner.py -q
```

Expected: FAIL because the component planners do not exist.

**Step 3: Write the minimal implementation**

Move the current reusable VM/bootstrap logic behind named components:

- `vm.ensure_running`
- `vm.provision_base`
- `repo.sync_to_vm`
- `registry.ensure_container`
- `k3s.install`
- `k3s.configure_registry`
- `images.build_core`
- `images.build_selected_functions`
- `k8s.ensure_namespace`
- `helm.deploy_control_plane`
- `helm.deploy_function_runtime`
- `k8s.wait_control_plane_ready`
- `k8s.wait_function_runtime_ready`

Keep `vm_cluster_workflows.py` only as a thin helper module for shared image/value builders that the component planners consume. Do not keep it as the primary orchestration surface.
Create Python planners that emit typed operations; if an operation is ultimately executed through shell, the shell details belong in the executor adapter, not in the component planner.

**Step 4: Run the tests to verify they pass**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_scenario_component_library.py \
  tools/controlplane/tests/test_e2e_runner.py -q
```

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario_components/bootstrap.py tools/controlplane/src/controlplane_tool/scenario_components/images.py tools/controlplane/src/controlplane_tool/scenario_components/helm.py tools/controlplane/src/controlplane_tool/vm_cluster_workflows.py tools/controlplane/tests/test_scenario_component_library.py tools/controlplane/tests/test_e2e_runner.py
git commit -m "Extract VM bootstrap scenario components"
```

## Task 5: Extract CLI, verification, and cleanup components

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/scenario_components/cli.py`
- Create: `tools/controlplane/src/controlplane_tool/scenario_components/verification.py`
- Create: `tools/controlplane/src/controlplane_tool/scenario_components/cleanup.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_host_runner.py`
- Modify: `tools/controlplane/tests/test_cli_runtime.py`
- Modify: `tools/controlplane/tests/test_scenario_recipes.py`

**Step 1: Write the failing tests**

Add tests that capture the CLI-specific and cleanup-specific planners:

```python
def test_cli_stack_recipe_contains_cli_platform_and_function_lifecycle_components() -> None:
    recipe = build_scenario_recipe("cli-stack")
    assert "cli.build_install_dist" in recipe.component_ids
    assert "cli.platform_install" in recipe.component_ids
    assert "cli.fn_apply_selected" in recipe.component_ids
    assert "cleanup.verify_cli_platform_status_fails" in recipe.component_ids


def test_host_platform_runner_uses_shared_cli_platform_components(tmp_path: Path) -> None:
    runner = CliHostPlatformRunner(tmp_path)
    assert runner._platform_install_command()[0:2] == ["platform", "install"]
```

**Step 2: Run the tests to verify they fail**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_cli_runtime.py \
  tools/controlplane/tests/test_scenario_recipes.py -q
```

Expected: FAIL because the CLI component planners and shared cleanup primitives are not extracted yet.

**Step 3: Write the minimal implementation**

Create reusable planners for:

- `cli.build_install_dist`
- `cli.platform_install`
- `cli.platform_status`
- `cli.fn_apply_selected`
- `cli.fn_list_selected`
- `cli.fn_invoke_selected`
- `cli.fn_enqueue_selected`
- `cli.fn_delete_selected`
- `cleanup.uninstall_control_plane`
- `cleanup.uninstall_function_runtime`
- `cleanup.delete_namespace`
- `cleanup.verify_cli_platform_status_fails`
- `vm.down`

Refactor `CliHostPlatformRunner` to be a thin compatibility facade over the shared CLI platform primitives. Keep host execution semantics unchanged; only share command assembly and assertions.

The shared CLI primitives must produce typed operations such as `HostCliOperation` or `RemoteCliOperation`, not shell fragments.

**Step 4: Run the tests to verify they pass**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_cli_runtime.py \
  tools/controlplane/tests/test_scenario_recipes.py -q
```

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario_components/cli.py tools/controlplane/src/controlplane_tool/scenario_components/verification.py tools/controlplane/src/controlplane_tool/scenario_components/cleanup.py tools/controlplane/src/controlplane_tool/cli_host_runner.py tools/controlplane/tests/test_cli_runtime.py tools/controlplane/tests/test_scenario_recipes.py
git commit -m "Extract CLI and cleanup scenario components"
```

## Task 6: Rebuild the three scenarios from recipes

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/e2e_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_stack_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_test_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario_flows.py`
- Modify: `tools/controlplane/src/controlplane_tool/flow_catalog.py`
- Modify: `tools/controlplane/tests/test_scenario_flows.py`
- Modify: `tools/controlplane/tests/test_flow_catalog.py`
- Modify: `tools/controlplane/tests/test_cli_test_runner.py`

**Step 1: Write the failing tests**

Add regression tests that prove the three target scenarios are recipe-composed and independent:

```python
def test_k3s_junit_curl_flow_builds_managed_vm_context_when_request_has_no_vm(tmp_path: Path) -> None:
    flow = build_scenario_flow("k3s-junit-curl", repo_root=tmp_path, request=make_request(vm=None))
    assert flow.task_ids[0] == "vm.ensure_running"


def test_helm_stack_flow_is_recipe_composed_not_hard_coded(tmp_path: Path) -> None:
    flow = build_scenario_flow("helm-stack", repo_root=tmp_path)
    assert "loadtest.run" in flow.task_ids


def test_cli_stack_flow_is_recipe_composed_and_self_bootstrapping(tmp_path: Path) -> None:
    plan = CliTestRunner(tmp_path).plan(CliTestRequest(scenario="cli-stack", runtime="java", vm=None))
    assert "Build nanofaas-cli installDist in VM" in [step.summary for step in plan.steps]
```

**Step 2: Run the tests to verify they fail**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_scenario_flows.py \
  tools/controlplane/tests/test_flow_catalog.py \
  tools/controlplane/tests/test_cli_test_runner.py -q
```

Expected: FAIL because current flow construction still mixes hard-coded task maps, dedicated runner logic, and environment assumptions.

**Step 3: Write the minimal implementation**

Refactor orchestration so the recipe composer becomes the source of truth:

- `E2eRunner.plan()` expands the selected scenario recipe into typed operations using the resolved environment
- add `scenario_components/executor.py` to translate typed operations into `ScenarioPlanStep`s at the edge
- `CliStackRunner.plan_steps()` delegates to the same recipe composer instead of carrying its own step order
- `CliTestRunner.plan()` resolves `cli-stack` through the recipe-based planner
- `scenario_flows.py` and `flow_catalog.py` derive `task_ids` from the recipe composer rather than a duplicated static map

Keep the public flow names unchanged.

**Step 4: Run the tests to verify they pass**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_scenario_flows.py \
  tools/controlplane/tests/test_flow_catalog.py \
  tools/controlplane/tests/test_cli_test_runner.py -q
```

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/e2e_runner.py tools/controlplane/src/controlplane_tool/cli_stack_runner.py tools/controlplane/src/controlplane_tool/cli_test_runner.py tools/controlplane/src/controlplane_tool/scenario_flows.py tools/controlplane/src/controlplane_tool/flow_catalog.py tools/controlplane/tests/test_scenario_flows.py tools/controlplane/tests/test_flow_catalog.py tools/controlplane/tests/test_cli_test_runner.py
git commit -m "Compose scenario flows from reusable recipes"
```

## Task 7: Update docs and TUI to describe the new independence model

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/tui_app.py`
- Modify: `docs/testing.md`
- Modify: `tools/controlplane/README.md`
- Modify: `README.md`
- Modify: `tools/controlplane/tests/test_tui_choices.py`
- Modify: `tools/controlplane/tests/test_docs_links.py`
- Modify: `tools/controlplane/tests/test_canonical_entrypoints.py`

**Step 1: Write the failing tests**

Add tests that assert the docs and TUI say the right thing:

```python
def test_docs_describe_cli_stack_as_self_bootstrapping_vm_scenario() -> None:
    text = Path("docs/testing.md").read_text(encoding="utf-8")
    assert "creates and configures its VM when needed" in text
    assert "does not require host-installed Helm" in text


def test_tui_cli_e2e_menu_describes_cli_stack_as_canonical_vm_backed_path(monkeypatch) -> None:
    ...
```

**Step 2: Run the tests to verify they fail**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_tui_choices.py \
  tools/controlplane/tests/test_docs_links.py \
  tools/controlplane/tests/test_canonical_entrypoints.py -q
```

Expected: FAIL because the docs and TUI do not yet describe the new component-based independence guarantees.

**Step 3: Write the minimal implementation**

Update the documentation and menu labels so they explicitly state:

- `k3s-junit-curl`, `helm-stack`, and `cli-stack` are self-bootstrapping VM-backed scenarios
- scenario-specific software is installed inside the VM, not expected on the host
- `cli-stack` remains the canonical VM-backed CLI stack path
- `host-platform` remains a compatibility path and is not one of the self-contained composed scenarios

**Step 4: Run the tests to verify they pass**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_tui_choices.py \
  tools/controlplane/tests/test_docs_links.py \
  tools/controlplane/tests/test_canonical_entrypoints.py -q
```

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/tui_app.py docs/testing.md tools/controlplane/README.md README.md tools/controlplane/tests/test_tui_choices.py tools/controlplane/tests/test_docs_links.py tools/controlplane/tests/test_canonical_entrypoints.py
git commit -m "Document self-bootstrapping scenario components"
```

## Task 8: Final verification and cleanup

**Files:**
- Verify only; no new files unless a small follow-up fix is required

**Step 1: Run the focused scenario test groups**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_scenario_component_models.py \
  tools/controlplane/tests/test_scenario_environment.py \
  tools/controlplane/tests/test_scenario_component_library.py \
  tools/controlplane/tests/test_scenario_recipes.py \
  tools/controlplane/tests/test_scenario_flows.py \
  tools/controlplane/tests/test_flow_catalog.py \
  tools/controlplane/tests/test_e2e_runner.py \
  tools/controlplane/tests/test_cli_test_runner.py \
  tools/controlplane/tests/test_cli_runtime.py -q
```

Expected: PASS.

**Step 2: Run the full controlplane test suite**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane pytest tools/controlplane/tests -q
```

Expected: PASS.

**Step 3: Inspect git diff for accidental sprawl**

Run:

```bash
git status --short
git diff --stat
```

Expected: only the intended scenario-component, flow, test, and docs changes.

**Step 4: Commit the final verification state if a small fix was needed**

```bash
git add -A
git commit -m "Finalize Prefect-composable scenario library"
```

Only do this if Step 1 or Step 2 required a final small fix. Otherwise keep the branch at the prior task commits.

## Notes for the implementer

- Prefer adapting existing code over creating parallel abstractions. The new component layer should become the source of truth for step composition.
- Keep each component planner pure: it should accept a typed context and return typed operations, not shell snippets and not executed commands during composition.
- The only layer allowed to know how to turn an operation into shell is the low-level executor adapter.
- Do not let `scenario_flows.py` keep a second hard-coded task map once recipes exist.
- Do not require callers to pass `vm_request_from_env()` to get a valid `k3s-junit-curl`, `helm-stack`, or `cli-stack` flow.
- Keep `host-platform` outside the “self-contained composed scenarios” set for this batch, but make it reuse the shared CLI platform primitives.
- If a test reveals that “no host software dependency” cannot be met because of an existing shell call like `rsync` or `ssh`, document that explicitly and wrap it behind a typed adapter boundary or split the work into a follow-up packaging plan instead of hiding the dependency in scenario code.
