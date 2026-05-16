# Workflow Task Composition — Design Spec

**Date:** 2026-05-16
**Scope:** Simplify and unify scenario orchestration by replacing the recipe system with composable Task objects and a Workflow class.

---

## Problem Statement

The current recipe system for E2E scenario orchestration has two concrete bugs and one structural problem:

1. **Bug — single function target:** `two_vm_target_function()` always takes `[0]` from the function list. k6 tests only one function even when all are registered.
2. **Bug — CLI coupling:** `cli.fn_apply_selected` is used in loadtest recipes to register functions. The CLI is a user-facing tool, not an infrastructure bootstrapping mechanism. If the CLI changes interface, loadtest scenarios break even if the control-plane API is unchanged.
3. **Structural — recipe system indirection:** 5 layers of abstraction (`ScenarioRecipe → compose_recipe → ScenarioComponentDefinition → planner → RemoteCommandOperation → operation_to_plan_step → ScenarioPlanStep → _execute_steps → bind_workflow_context`) to execute a command. Data flow is hidden in closures and callback injection. Adding or modifying a scenario requires reading 5+ files.

---

## Goals

- Fix both bugs by construction (not by patching)
- Replace the recipe system with composable Task objects and a Workflow class
- Keep `workflow_tasks` as the canonical home for generic orchestration primitives and concrete task implementations
- Reduce `controlplane_tool` to scenario composition only
- Preserve TUI dry-run planning (task IDs derivable before execution)
- Preserve Prefect integration at flow level (unchanged)

---

## Non-Goals

- Runtime TUI task selection (user selects tasks interactively) — future work
- Multi-runtime comparison tables — separate spec
- Parallelism / DAG dependency resolution — sequential execution only for now
- Prefect `@task` integration — Prefect remains at flow level only

---

## Architecture

### Layer boundaries

```
workflow_tasks/          Generic orchestration library (no nanofaas domain knowledge)
  core/                  Task Protocol + Workflow class (zero external deps)
  tasks/                 Existing: executors, models, adapters (unchanged)
  workflow/              Existing: events, context, reporting (unchanged)
  vm/                    Concrete VM tasks (deps: azure-vm-sdk, multipass-client)
  k8s/                   Concrete k8s tasks (no Python deps — shellout to helm/kubectl)
  loadtest/              Concrete loadtest tasks (no Python deps — shellout to k6, urllib for Prometheus)
  functions/             Concrete function registration tasks (no Python deps — urllib for REST)

controlplane_tool/       Nanofaas-specific scenario composition
  scenario/scenarios/    Builder functions: assemble workflow_tasks into named scenarios
  e2e/e2e_runner.py      plan() + execute() only — no step construction logic
```

### Dependency graph

```
controlplane_tool → workflow_tasks[vm] + workflow_tasks submodules
workflow_tasks.vm → azure-vm-sdk, multipass-client
workflow_tasks.k8s, loadtest, functions → stdlib only
workflow_tasks.core → zero deps
```

---

## `workflow_tasks.core` — Task and Workflow

### `Task` Protocol

```python
class Task(Protocol):
    task_id: str    # stable identifier, used for TUI planning and workflow_step context
    title: str      # human-readable label shown in TUI and logs
    def run(self) -> Any: ...
```

All task implementations are dataclasses satisfying this Protocol. No ABC inheritance required.

### `Workflow` class

```python
@dataclass
class Workflow:
    tasks: list[Task]
    cleanup_tasks: list[Task] = field(default_factory=list)

    @property
    def task_ids(self) -> list[str]:
        """All task IDs in execution order. Used by TUI for dry-run planning."""
        return [t.task_id for t in self.tasks + self.cleanup_tasks]

    def run(self) -> None:
        error: BaseException | None = None
        for task in self.tasks:
            with workflow_step(task_id=task.task_id, title=task.title):
                task.run()
        for task in self.cleanup_tasks:   # always executed, even after failure
            with workflow_step(task_id=task.task_id, title=task.title):
                task.run()
```

`workflow_step` (already in `workflow_tasks`) handles `running → completed/failed` events automatically. No manual `bind_workflow_context` needed.

**Data-producing tasks** are called directly by the scenario builder, not wrapped in `Workflow`. Their results are passed explicitly to subsequent tasks via constructor arguments. This keeps data flow visible and statically traceable.

---

## `workflow_tasks` Sub-packages

### `workflow_tasks.vm`

Tasks: `EnsureVmRunning`, `ProvisionBase`, `SyncProject`, `TeardownVm`

Each task receives a VM runner (Protocol) in its constructor. Concrete implementations (`MultipassVmAdapter`, `AzureVmOrchestrator`) are injected by `controlplane_tool`. `workflow_tasks.vm` depends on `azure-vm-sdk` and the multipass Python client.

### `workflow_tasks.k8s`

Tasks: `InstallK3s`, `ConfigureK3sRegistry`, `EnsureRegistry`, `HelmInstall`, `HelmUninstall`, `NamespaceInstall`, `NamespaceUninstall`

All tasks shell out to `helm` / `kubectl` / `k3s` binaries. No additional Python dependencies.

### `workflow_tasks.loadtest`

Tasks: `InstallK6`, `RunK6Matrix`, `CapturePrometheus`, `WriteReport`

**`RunK6Matrix` fixes the single-function bug:**
```python
@dataclass
class RunK6Matrix:
    task_id: str = "loadtest.run_k6_matrix"
    title: str = "Run k6 against all targets"
    targets: list[str]   # ALL function keys — no [0] truncation
    ...

    def run(self) -> K6MatrixResult:
        results = []
        for fn_key in self.targets:   # explicit loop, bug impossible
            with workflow_step(task_id=f"k6.{fn_key}", title=f"k6 — {fn_key}"):
                results.append(self._run_single(fn_key))
        return K6MatrixResult(results)
```

`CapturePrometheus` uses `urllib` to query the Prometheus HTTP API. No Python deps beyond stdlib.

### `workflow_tasks.functions`

Tasks: `RegisterFunctions`

**Fixes the CLI coupling bug:** uses `urllib` to call `POST /v1/functions` directly on the control-plane REST API. No `nanofaas-cli` involved.

```python
@dataclass
class RegisterFunctions:
    task_id: str = "functions.register"
    title: str = "Register functions"
    control_plane_url: str
    specs: list[FunctionSpec]

    def run(self) -> None:
        for spec in self.specs:
            with workflow_step(task_id=f"functions.register.{spec.name}", ...):
                _post_function(self.control_plane_url, spec)
```

---

## `controlplane_tool` — Scenario Builders

### What is removed

| Removed | Replaced by |
|---|---|
| `scenario/components/` (entire directory) | `workflow_tasks` sub-packages |
| `scenario/scenario_planner.py` | scenario builder functions |
| `e2e_runner._execute_steps()` | `Workflow.run()` |
| `plan_recipe_steps()` | builder functions |
| `compose_recipe()` | direct `Workflow([...])` construction |
| `cli.fn_apply_selected` in loadtest recipes | `RegisterFunctions` REST task |

### `ScenarioPlan` Protocol

All scenario builders return an object satisfying this Protocol:

```python
class ScenarioPlan(Protocol):
    @property
    def task_ids(self) -> list[str]: ...
    def run(self) -> None: ...
```

Each builder returns its own concrete dataclass (`TwoVmLoadtestPlan`, `K3sJunitCurlPlan`, etc.) satisfying the Protocol. `e2e_runner` only depends on the Protocol, not the concrete types.

### Scenario builder pattern

Each scenario is a module with a `build_<scenario>(request) -> <Scenario>Plan` function:

```python
# scenario/scenarios/two_vm_loadtest.py
@dataclass
class TwoVmLoadtestPlan:
    setup: Workflow
    run_loadtest: Callable[[], None]

    @property
    def task_ids(self) -> list[str]:
        return self.setup.task_ids + ["loadtest.run_k6_matrix",
                                       "loadtest.prometheus", "loadtest.report"]

def build_two_vm_loadtest(request: E2eRequest) -> TwoVmLoadtestPlan:
    vm = _vm_orchestrator(request)
    function_keys = request.resolved_scenario.function_keys

    setup = Workflow([
        EnsureVmRunning(vm=vm, target=request.vm),
        ProvisionBase(vm=vm, target=request.vm),
        SyncProject(vm=vm, target=request.vm),
        InstallK3s(vm=vm, target=request.vm),
        ConfigureK3sRegistry(...),
        EnsureRegistry(...),
        HelmInstall(...),          # control-plane
        HelmInstall(...),          # function-runtime
        RegisterFunctions(         # REST, no CLI
            url=_cp_url(request),
            specs=_function_specs(request),
        ),
        EnsureVmRunning(vm=vm, target=request.loadgen_vm),
        ProvisionBase(vm=vm, target=request.loadgen_vm),
        InstallK6(vm=vm, target=request.loadgen_vm),
    ], cleanup_tasks=[
        TeardownVm(vm=vm, target=request.loadgen_vm),
        TeardownVm(vm=vm, target=request.vm),
    ])

    def run_loadtest() -> None:
        k6 = RunK6Matrix(
            targets=function_keys,    # ALL functions, no [0]
            ...
        ).run()
        prom = CapturePrometheus(window=k6.window, ...).run()
        WriteReport(k6=k6, prometheus=prom, ...).run()

    return TwoVmLoadtestPlan(setup=setup, run_loadtest=run_loadtest)
```

### `e2e_runner.py` simplified

```python
class E2eRunner:
    def plan(self, request: E2eRequest) -> ScenarioPlan:
        """Build scenario plan without executing. TUI reads plan.task_ids."""
        builder = _scenario_builder(request.scenario)
        return builder(request)   # returns concrete plan satisfying ScenarioPlan Protocol

    def execute(self, plan: ScenarioPlan) -> None:
        plan.run()   # each plan knows how to execute itself
```

### Scenarios covered

All current recipe-based scenarios are migrated:
- `two-vm-loadtest`
- `azure-vm-loadtest`
- `k3s-junit-curl`
- `helm-stack`
- `cli-stack`

Direct-runner scenarios (`helm_stack_runner`, `k3s_curl_runner`, `container_local_runner`, `deploy_host_runner`) are migrated to the Task/Workflow pattern in a subsequent phase, after the recipe system is replaced.

---

## TUI Integration

The TUI currently calls `resolve_flow_task_ids()` to show phases before execution. After this change:

```python
# flow_catalog.py
plan = _build_scenario_plan(scenario, request)
task_ids = plan.task_ids   # derived from Workflow.task_ids, no execution needed
```

No changes needed to the TUI itself — `task_ids` is still a list of strings, derived from `Workflow.task_ids` instead of from `compose_recipe()`.

---

## Error Handling

- `Workflow.tasks`: first failure stops execution, `cleanup_tasks` still run
- `cleanup_tasks`: all run regardless of prior failures; errors are collected and re-raised after all cleanup completes
- Individual task errors propagate as exceptions; `workflow_step` catches them and emits `task.failed` before re-raising

---

## Testing Strategy

- Each concrete task is independently testable: construct with a mock runner, call `.run()`, assert side effects
- `Workflow` tested with stub tasks: verify ordering, cleanup-always behavior, error propagation
- Scenario builder functions tested by constructing the plan and asserting `task_ids` — no execution needed
- Bug regression test: assert `len(RunK6Matrix(..., targets=["a","b","c"]).run().results) == 3`

---

## Migration Path

1. Add `Task` Protocol and `Workflow` class to `workflow_tasks.core`
2. Add sub-packages (`vm`, `k8s`, `loadtest`, `functions`) to `workflow_tasks` — implement concrete tasks
3. Write scenario builder functions in `controlplane_tool/scenario/scenarios/`
4. Migrate `two-vm-loadtest` and `azure-vm-loadtest` first (highest value, known bugs)
5. Migrate remaining recipe scenarios (`k3s-junit-curl`, `helm-stack`, `cli-stack`)
6. Remove recipe system (`components/`, `scenario_planner.py`, `plan_recipe_steps`)
7. Migrate direct-runner scenarios to Task/Workflow pattern (second phase)
