# One VM Helm Loadtest Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a new `one-vm-helm-loadtest` scenario that leaves `helm-stack` unchanged while reusing the `two-vm-loadtest` loadgen flow, `RegisterFunctions`, and `RunK6` primitives on a single stack VM.

**Architecture:** The new scenario keeps the existing Helm stack prelude and then runs the loadtest driver with an adapter that treats the stack VM as the load generator. Autoscaling is implemented as a post-loadgen tail: register an INTERNAL autoscaling function with the existing `RegisterFunctions` task, run an autoscaling k6 script with the existing `RunK6` task, then verify replica scale-up and scale-down with a focused Kubernetes polling task. `helm-stack` remains a compatibility scenario and is not modified except for shared code that must stay behavior-preserving.

**Tech Stack:** Python 3.11+, Typer/Pydantic control-plane tool, `workflow_tasks.Workflow`, existing VM adapters, existing k6 task primitives, pytest, uv.

---

## Scope And Constraints

- Keep `helm-stack` behavior and task ordering unchanged.
- Add `one-vm-helm-loadtest` as a new scenario name, not an alias for `helm-stack`.
- Reuse `RegisterFunctions` for normal and autoscaling function registration.
- Reuse `RunK6` and the shared loadgen body where possible.
- Run the load generator on the same VM as the Helm stack. Do not create or destroy a separate loadgen VM.
- Keep generated run artifacts under `tools/controlplane/runs/`.
- Move only the autoscaling k6 asset into `tools/controlplane/assets/k6/autoscaling.js`; do not move the whole `experiments/` tree in this slice.
- Before editing any existing symbol, follow the repository GitNexus rule: run impact analysis for that symbol and stop for user review if GitNexus reports HIGH or CRITICAL risk.

## File Structure

- Modify `tools/workflow-tasks/src/workflow_tasks/components/function_tasks.py`
  - Add optional `scaling_config` support to `FunctionSpec`.
- Modify `tools/workflow-tasks/tests/components/test_function_tasks.py`
  - Cover `scalingConfig` serialization and default omission.
- Create `tools/controlplane/src/controlplane_tool/autoscaling/__init__.py`
  - Public package marker for autoscaling support.
- Create `tools/controlplane/src/controlplane_tool/autoscaling/tasks.py`
  - Focused autoscaling verifier task and summary dataclass.
- Create `tools/controlplane/tests/test_autoscaling_tasks.py`
  - Unit tests for Kubernetes polling and failure messages.
- Create `tools/controlplane/assets/k6/autoscaling.js`
  - k6 workload copied from `experiments/k6/autoscaling.js`, normalized to accept `NANOFAAS_FUNCTION`.
- Modify `tools/controlplane/src/controlplane_tool/scenario/loadtest_flow.py`
  - Add adapter hooks for one-VM mode and post-loadgen tasks.
- Modify `tools/controlplane/src/controlplane_tool/scenario/loadtest_adapter.py`
  - Add default hook methods to existing adapters so two-VM behavior stays unchanged.
- Create `tools/controlplane/src/controlplane_tool/scenario/one_vm_loadtest_adapter.py`
  - Adapter that reuses the stack VM as loadgen and appends autoscaling tasks.
- Create `tools/controlplane/src/controlplane_tool/scenario/scenarios/one_vm_helm_loadtest.py`
  - Scenario plan that mirrors `two_vm_loadtest.py` but uses the one-VM adapter.
- Modify `tools/controlplane/src/controlplane_tool/core/models.py`
  - Add `one-vm-helm-loadtest` to scenario literals and VM-backed scenario set.
- Modify `tools/controlplane/src/controlplane_tool/scenario/catalog.py`
  - Expose the new scenario in listings.
- Modify `tools/controlplane/src/controlplane_tool/scenario/components/recipes.py`
  - Add a recipe entry for the new scenario based on `STACK_PRELUDE`.
- Modify `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py`
  - Route planning and `plan_all` to the new scenario plan.
- Modify `tools/workflow-tasks/src/workflow_tasks/loadtest/two_vm.py`
  - Add the new scenario to `LOADTEST_SCENARIOS` so existing loadtest remapping remains consistent.
- Modify existing tests and add focused tests under `tools/controlplane/tests/`.

---

### Task 0: Baseline, Impact Analysis, And Safety Gates

**Files:**
- No file changes.

- [ ] **Step 1: Confirm the worktree state**

Run:

```bash
git status --short
```

Expected: note any unrelated existing changes. Do not revert user changes. If files listed are outside this plan, ignore them.

- [ ] **Step 2: Run GitNexus impact checks before editing existing symbols**

Run these GitNexus MCP calls or the equivalent available GitNexus tool in the session:

```text
gitnexus_impact({target: "FunctionSpec", direction: "upstream"})
gitnexus_impact({target: "RegisterFunctions", direction: "upstream"})
gitnexus_impact({target: "run_loadtest_flow", direction: "upstream"})
gitnexus_impact({target: "loadtest_flow_task_ids", direction: "upstream"})
gitnexus_impact({target: "loadtest_flow_phase_titles", direction: "upstream"})
gitnexus_impact({target: "E2eRunner", direction: "upstream"})
gitnexus_impact({target: "build_scenario_recipe", direction: "upstream"})
```

Expected: LOW or MEDIUM risk. If any result is HIGH or CRITICAL, stop and report the direct callers, affected processes, and risk level before editing.

- [ ] **Step 3: Run the current sentinel tests**

Run:

```bash
uv run --project tools/controlplane --locked pytest -q \
  tools/workflow-tasks/tests/components/test_function_tasks.py \
  tools/controlplane/tests/test_helm_stack_workflow.py \
  tools/controlplane/tests/test_scenario_recipes.py \
  tools/controlplane/tests/test_e2e_runner.py
```

Expected: all selected tests pass before changes.

---

### Task 1: Add Scaling Config Support To Existing RegisterFunctions

**Files:**
- Modify: `tools/workflow-tasks/src/workflow_tasks/components/function_tasks.py`
- Modify: `tools/workflow-tasks/tests/components/test_function_tasks.py`

- [ ] **Step 1: Write the failing serialization test**

Append this test to `tools/workflow-tasks/tests/components/test_function_tasks.py`:

```python
def test_function_spec_to_body_includes_scaling_config_when_present() -> None:
    body = FunctionSpec(
        name="word-stats-java",
        image="localhost:5000/nanofaas/java-word-stats:e2e",
        timeout_ms=30000,
        concurrency=4,
        queue_size=100,
        scaling_config={
            "strategy": "INTERNAL",
            "minReplicas": 0,
            "maxReplicas": 5,
            "metrics": [{"type": "in_flight", "target": "2"}],
        },
    ).to_body()

    assert body["scalingConfig"] == {
        "strategy": "INTERNAL",
        "minReplicas": 0,
        "maxReplicas": 5,
        "metrics": [{"type": "in_flight", "target": "2"}],
    }
```

- [ ] **Step 2: Write the omission test**

Append this test to the same file:

```python
def test_function_spec_to_body_omits_scaling_config_by_default() -> None:
    body = FunctionSpec(name="echo", image="reg/echo:e2e").to_body()

    assert "scalingConfig" not in body
```

- [ ] **Step 3: Run the tests and verify the first test fails**

Run:

```bash
uv run --project tools/controlplane --locked pytest -q \
  tools/workflow-tasks/tests/components/test_function_tasks.py
```

Expected: failure with `TypeError: FunctionSpec.__init__() got an unexpected keyword argument 'scaling_config'`.

- [ ] **Step 4: Implement the minimal `FunctionSpec` extension**

Replace the `FunctionSpec` class in `tools/workflow-tasks/src/workflow_tasks/components/function_tasks.py` with:

```python
@dataclass
class FunctionSpec:
    name: str
    image: str
    execution_mode: str = "DEPLOYMENT"
    timeout_ms: int = 5000
    concurrency: int = 2
    queue_size: int = 20
    max_retries: int = 3
    scaling_config: dict[str, object] | None = None

    def to_body(self) -> dict[str, object]:
        body: dict[str, object] = {
            "name": self.name,
            "image": self.image,
            "executionMode": self.execution_mode,
            "timeoutMs": self.timeout_ms,
            "concurrency": self.concurrency,
            "queueSize": self.queue_size,
            "maxRetries": self.max_retries,
        }
        if self.scaling_config is not None:
            body["scalingConfig"] = self.scaling_config
        return body
```

- [ ] **Step 5: Run the focused tests**

Run:

```bash
uv run --project tools/controlplane --locked pytest -q \
  tools/workflow-tasks/tests/components/test_function_tasks.py
```

Expected: all tests in `test_function_tasks.py` pass.

- [ ] **Step 6: Commit Task 1**

Run:

```bash
git add tools/workflow-tasks/src/workflow_tasks/components/function_tasks.py \
  tools/workflow-tasks/tests/components/test_function_tasks.py
git commit -m "feat(workflow-tasks): allow scaling config in function specs"
```

---

### Task 2: Add Autoscaling Verifier Task And k6 Asset

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/autoscaling/__init__.py`
- Create: `tools/controlplane/src/controlplane_tool/autoscaling/tasks.py`
- Create: `tools/controlplane/tests/test_autoscaling_tasks.py`
- Create: `tools/controlplane/assets/k6/autoscaling.js`

- [ ] **Step 1: Write tests for autoscaling verification success**

Create `tools/controlplane/tests/test_autoscaling_tasks.py` with:

```python
from __future__ import annotations

from dataclasses import dataclass

from controlplane_tool.autoscaling.tasks import VerifyAutoscalingReplicas


@dataclass
class _Result:
    return_code: int
    stdout: str
    stderr: str = ""


class _Runner:
    def __init__(self, values: list[str]) -> None:
        self.values = values
        self.commands: list[tuple[str, ...]] = []

    def run_vm_command(self, argv: tuple[str, ...], *, env: dict[str, str], remote_dir: str | None, dry_run: bool):
        self.commands.append(argv)
        if not self.values:
            return _Result(return_code=0, stdout="0")
        return _Result(return_code=0, stdout=self.values.pop(0))


def test_verify_autoscaling_replicas_observes_scale_up_and_down(monkeypatch) -> None:
    monkeypatch.setattr("controlplane_tool.autoscaling.tasks.time.sleep", lambda _: None)
    runner = _Runner(["1", "1", "2", "2", "0"])
    task = VerifyAutoscalingReplicas(
        task_id="autoscaling.verify_replicas",
        title="Verify autoscaling replicas",
        runner=runner,
        namespace="nanofaas",
        deployment_name="fn-word-stats-java",
        remote_dir="/home/ubuntu/mcFaas",
        scale_up_polls=2,
        scale_down_initial_delay_seconds=0,
        scale_down_polls=1,
        poll_interval_seconds=1,
    )

    summary = task.run()

    assert summary.max_replicas_observed == 2
    assert summary.final_desired_replicas == 0
    assert len(runner.commands) == 5
    assert all("kubectl" in " ".join(command) for command in runner.commands)
```

- [ ] **Step 2: Add tests for scale-up and scale-down failures**

Append these tests to `tools/controlplane/tests/test_autoscaling_tasks.py`:

```python
def test_verify_autoscaling_replicas_fails_when_scale_up_never_exceeds_one(monkeypatch) -> None:
    monkeypatch.setattr("controlplane_tool.autoscaling.tasks.time.sleep", lambda _: None)
    runner = _Runner(["1", "1", "1", "1"])
    task = VerifyAutoscalingReplicas(
        task_id="autoscaling.verify_replicas",
        title="Verify autoscaling replicas",
        runner=runner,
        namespace="nanofaas",
        deployment_name="fn-word-stats-java",
        remote_dir="/home/ubuntu/mcFaas",
        scale_up_polls=2,
        scale_down_initial_delay_seconds=0,
        scale_down_polls=1,
        poll_interval_seconds=1,
    )

    try:
        task.run()
    except RuntimeError as exc:
        assert "Scale-up not observed" in str(exc)
        return
    raise AssertionError("expected RuntimeError")


def test_verify_autoscaling_replicas_fails_when_scale_down_never_reaches_zero(monkeypatch) -> None:
    monkeypatch.setattr("controlplane_tool.autoscaling.tasks.time.sleep", lambda _: None)
    runner = _Runner(["2", "2", "2", "2", "1"])
    task = VerifyAutoscalingReplicas(
        task_id="autoscaling.verify_replicas",
        title="Verify autoscaling replicas",
        runner=runner,
        namespace="nanofaas",
        deployment_name="fn-word-stats-java",
        remote_dir="/home/ubuntu/mcFaas",
        scale_up_polls=2,
        scale_down_initial_delay_seconds=0,
        scale_down_polls=1,
        poll_interval_seconds=1,
    )

    try:
        task.run()
    except RuntimeError as exc:
        assert "Scale-down to 0 not observed" in str(exc)
        return
    raise AssertionError("expected RuntimeError")
```

- [ ] **Step 3: Run tests and verify import failure**

Run:

```bash
uv run --project tools/controlplane --locked pytest -q \
  tools/controlplane/tests/test_autoscaling_tasks.py
```

Expected: failure with `ModuleNotFoundError: No module named 'controlplane_tool.autoscaling'`.

- [ ] **Step 4: Create autoscaling package marker**

Create `tools/controlplane/src/controlplane_tool/autoscaling/__init__.py`:

```python
from controlplane_tool.autoscaling.tasks import AutoscalingSummary, VerifyAutoscalingReplicas

__all__ = ["AutoscalingSummary", "VerifyAutoscalingReplicas"]
```

- [ ] **Step 5: Implement autoscaling verifier**

Create `tools/controlplane/src/controlplane_tool/autoscaling/tasks.py`:

```python
from __future__ import annotations

from dataclasses import dataclass
import time

from workflow_tasks.tasks.executors import VmCommandRunner


@dataclass(frozen=True)
class AutoscalingSummary:
    deployment_name: str
    max_replicas_observed: int
    final_desired_replicas: int


@dataclass
class VerifyAutoscalingReplicas:
    task_id: str
    title: str
    runner: VmCommandRunner
    namespace: str
    deployment_name: str
    remote_dir: str
    scale_up_polls: int = 24
    scale_down_initial_delay_seconds: int = 90
    scale_down_polls: int = 24
    poll_interval_seconds: int = 5

    def _replica_count(self, jsonpath: str) -> int:
        result = self.runner.run_vm_command(
            (
                "bash",
                "-lc",
                "kubectl get deployment "
                f"{self.deployment_name} -n {self.namespace} "
                f"-o jsonpath='{jsonpath}' 2>/dev/null || echo 0",
            ),
            env={},
            remote_dir=self.remote_dir,
            dry_run=False,
        )
        if result.return_code != 0:
            raise RuntimeError(result.stderr or result.stdout or "kubectl replica query failed")
        try:
            return int((result.stdout or "0").strip() or "0")
        except ValueError as exc:
            raise RuntimeError(f"invalid replica count: {result.stdout!r}") from exc

    def _ready_replicas(self) -> int:
        return self._replica_count("{.status.readyReplicas}")

    def _desired_replicas(self) -> int:
        return self._replica_count("{.spec.replicas}")

    def run(self) -> AutoscalingSummary:
        max_replicas = 0
        for _ in range(self.scale_up_polls):
            time.sleep(self.poll_interval_seconds)
            ready = self._ready_replicas()
            desired = self._desired_replicas()
            max_replicas = max(max_replicas, ready, desired)
            if max_replicas > 1:
                break

        if max_replicas <= 1:
            raise RuntimeError(f"Scale-up not observed: max replicas stayed at {max_replicas}")

        time.sleep(self.scale_down_initial_delay_seconds)
        final_desired = self._desired_replicas()
        for _ in range(self.scale_down_polls):
            if final_desired == 0:
                return AutoscalingSummary(
                    deployment_name=self.deployment_name,
                    max_replicas_observed=max_replicas,
                    final_desired_replicas=final_desired,
                )
            time.sleep(self.poll_interval_seconds)
            final_desired = self._desired_replicas()

        raise RuntimeError(f"Scale-down to 0 not observed: desired replicas = {final_desired}")
```

- [ ] **Step 6: Add the autoscaling k6 asset**

Create `tools/controlplane/assets/k6/autoscaling.js`:

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.NANOFAAS_URL || 'http://localhost:30080';
const FN = __ENV.NANOFAAS_FUNCTION || __ENV.FUNCTION_NAME || 'word-stats-java';

export const options = {
    stages: [
        { duration: '10s', target: 10 },
        { duration: '20s', target: 20 },
        { duration: '90s', target: 20 },
        { duration: '10s', target: 0 },
    ],
    thresholds: {
        http_req_failed: ['rate<0.30'],
    },
};

const TEXTS = [
    'The quick brown fox jumps over the lazy dog. The dog barked at the fox while the fox ran away quickly.',
    'Lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.',
    'To be or not to be that is the question whether tis nobler in the mind to suffer the slings and arrows of outrageous fortune.',
    'It was the best of times it was the worst of times it was the age of wisdom it was the age of foolishness.',
];

export default function () {
    const text = TEXTS[Math.floor(Math.random() * TEXTS.length)];
    const payload = JSON.stringify({
        input: { text: text, topN: 5 },
    });

    const res = http.post(`${BASE_URL}/v1/functions/${FN}:invoke`, payload, {
        headers: { 'Content-Type': 'application/json' },
        timeout: '30s',
    });

    check(res, {
        'status is 200': (r) => r.status === 200,
    });

    sleep(0.05);
}
```

- [ ] **Step 7: Run focused tests**

Run:

```bash
uv run --project tools/controlplane --locked pytest -q \
  tools/controlplane/tests/test_autoscaling_tasks.py
```

Expected: all tests pass.

- [ ] **Step 8: Commit Task 2**

Run:

```bash
git add tools/controlplane/src/controlplane_tool/autoscaling \
  tools/controlplane/tests/test_autoscaling_tasks.py \
  tools/controlplane/assets/k6/autoscaling.js
git commit -m "feat(controlplane): add autoscaling verifier task"
```

---

### Task 3: Extend Loadtest Flow With One-VM And Post-Loadgen Hooks

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/loadtest_flow.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/loadtest_adapter.py`
- Modify: `tools/controlplane/tests/test_loadtest_flow.py` if present, otherwise create `tools/controlplane/tests/test_loadtest_flow_one_vm_hooks.py`

- [ ] **Step 1: Locate or create the loadtest flow test file**

Run:

```bash
find tools/controlplane/tests -maxdepth 1 -type f -name '*loadtest_flow*' | sort
```

Expected: use the existing file if it exists. If no file is printed, create `tools/controlplane/tests/test_loadtest_flow_one_vm_hooks.py` in the next step.

- [ ] **Step 2: Write tests for static task IDs in one-VM mode**

Add this test to the selected test file:

```python
from __future__ import annotations

from pathlib import Path
from types import SimpleNamespace

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.e2e.e2e_runner import E2eRunner
from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.scenario.loadtest_flow import (
    FlowPhase,
    loadtest_flow_task_ids,
    loadtest_flow_phase_titles,
)
from controlplane_tool.scenario.scenarios._workflow_assembly import build_setup
from workflow_tasks.components.models import ScenarioRecipe


class _Connectivity:
    def vm_runner(self, vm_request):
        return SimpleNamespace()

    def remote_dir(self, vm_request):
        return "/home/ubuntu/mcFaas"

    def resolve_host_operation(self, operation):
        return operation


class _OneVmAdapter:
    title_suffix = " (one VM)"
    connectivity = _Connectivity()

    def uses_dedicated_loadgen_vm(self) -> bool:
        return False

    def extra_step_ids(self, phase: FlowPhase) -> list[str]:
        return []

    def post_loadgen_task_ids(self) -> list[str]:
        return ["autoscaling.register_function", "autoscaling.run_k6", "autoscaling.verify_replicas"]

    def extra_step_titles(self, phase: FlowPhase) -> list[str]:
        return []

    def post_loadgen_task_titles(self) -> list[str]:
        return ["Register autoscaling function", "Run autoscaling k6", "Verify autoscaling replicas"]


def _request() -> E2eRequest:
    return E2eRequest(
        scenario="one-vm-helm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )


def test_one_vm_loadtest_static_ids_skip_loadgen_vm_lifecycle() -> None:
    runner = E2eRunner(repo_root=Path("/repo"))
    request = _request()
    setup = build_setup(runner, request)
    recipe = ScenarioRecipe(
        name="one-vm-helm-loadtest-stack",
        component_ids=("vm.provision_base",),
        requires_managed_vm=True,
    )

    ids = loadtest_flow_task_ids(
        runner=runner,
        request=request,
        setup=setup,
        recipe=recipe,
        adapter=_OneVmAdapter(),
    )

    assert "vm.stack.ensure_running" in ids
    assert "vm.loadgen.ensure_running" not in ids
    assert "vm.loadgen.destroy" not in ids
    assert "autoscaling.register_function" in ids
    assert "autoscaling.run_k6" in ids
    assert "autoscaling.verify_replicas" in ids
    assert ids[-1] == "vm.stack.destroy"


def test_one_vm_loadtest_static_titles_include_autoscaling_tail() -> None:
    runner = E2eRunner(repo_root=Path("/repo"))
    request = _request()
    setup = build_setup(runner, request)
    recipe = ScenarioRecipe(
        name="one-vm-helm-loadtest-stack",
        component_ids=("vm.provision_base",),
        requires_managed_vm=True,
    )

    titles = loadtest_flow_phase_titles(
        runner=runner,
        request=request,
        setup=setup,
        recipe=recipe,
        adapter=_OneVmAdapter(),
    )

    assert "Ensure loadgen VM running (one VM)" not in titles
    assert "Register autoscaling function" in titles
    assert "Run autoscaling k6" in titles
    assert "Verify autoscaling replicas" in titles
    assert titles[-1] == "Destroy stack VM (one VM)"
```

- [ ] **Step 3: Run the tests and verify hook methods are missing**

Run:

```bash
uv run --project tools/controlplane --locked pytest -q \
  tools/controlplane/tests/test_loadtest_flow_one_vm_hooks.py
```

Expected: failure mentioning missing scenario literal or missing hook behavior. If the test file name already existed, run that exact existing file instead.

- [ ] **Step 4: Add default hook methods to existing loadtest adapters**

In `tools/controlplane/src/controlplane_tool/scenario/loadtest_adapter.py`, add these methods to each existing adapter class (`MultipassLoadtestAdapter`, `ProxmoxLoadtestAdapter`, `AzureLoadtestAdapter`):

```python
    def uses_dedicated_loadgen_vm(self) -> bool:
        return True

    def post_loadgen_tasks(self, ctx: RunContext) -> list:
        return []

    def post_loadgen_task_ids(self) -> list[str]:
        return []

    def post_loadgen_task_titles(self) -> list[str]:
        return []
```

Place the methods near the existing optional-capability methods such as `emits_step_events`, `cleanup_on_failure`, and `extra_step_titles`.

- [ ] **Step 5: Update `loadtest_flow.py` for one-VM lifecycle and post-loadgen tasks**

In `tools/controlplane/src/controlplane_tool/scenario/loadtest_flow.py`, add these helper functions near `_adapter_connectivity`:

```python
def _uses_dedicated_loadgen_vm(adapter) -> bool:
    fn = getattr(adapter, "uses_dedicated_loadgen_vm", None)
    return True if fn is None else bool(fn())


def _post_loadgen_tasks(adapter, ctx: RunContext) -> list:
    fn = getattr(adapter, "post_loadgen_tasks", None)
    return [] if fn is None else list(fn(ctx))


def _post_loadgen_task_ids(adapter) -> list[str]:
    fn = getattr(adapter, "post_loadgen_task_ids", None)
    return [] if fn is None else list(fn())


def _post_loadgen_task_titles(adapter) -> list[str]:
    fn = getattr(adapter, "post_loadgen_task_titles", None)
    return [] if fn is None else list(fn())
```

In `_run_loadtest_flow_native`, replace the loadgen ensure block with:

```python
    # ── 3. Ensure or reuse loadgen VM ──────────────────────────────────────
    if _uses_dedicated_loadgen_vm(adapter):
        ctx.loadgen_info = _ensure_vm(
            "vm.loadgen.ensure_running",
            f"Ensure loadgen VM running{s}",
            adapter.loadgen_lifecycle(),
            _loadgen_vm_config(request),
        )
    else:
        ctx.loadgen_info = ctx.stack_info
```

In `_run_loadtest_flow_native`, replace the body execution block with:

```python
    # ── 6. Loadgen body workflow ────────────────────────────────────────────
    body = _build_loadgen_body(runner, request, adapter, ctx)
    body += _post_loadgen_tasks(adapter, ctx)
    cleanup = _destroy_tasks(adapter, ctx, request)
    _run_workflow(body, cleanup_tasks=cleanup)
```

In `_run_loadtest_flow_emitting`, replace the loadgen ensure block with:

```python
        if _uses_dedicated_loadgen_vm(adapter):
            loadgen_task = _ensure_vm_task(
                "vm.loadgen.ensure_running",
                f"Ensure loadgen VM running{s}",
                adapter.loadgen_lifecycle(),
                _loadgen_vm_config(request),
            )
            ctx.loadgen_info = emitter.run_task(loadgen_task)
        else:
            ctx.loadgen_info = ctx.stack_info
```

In `_run_loadtest_flow_emitting`, replace the body construction block with:

```python
    body = _build_loadgen_body(runner, request, adapter, ctx)
    body += _post_loadgen_tasks(adapter, ctx)
    cleanup = _destroy_tasks(adapter, ctx, request)
    emitter.run_tasks(body, cleanup_tasks=cleanup)
```

Replace `_destroy_tasks` with:

```python
def _destroy_tasks(adapter, ctx: RunContext, request) -> list:
    s = adapter.title_suffix
    if not getattr(request, "cleanup_vm", True):
        return []
    if _uses_dedicated_loadgen_vm(adapter):
        return [
            DestroyVm(
                task_id="vm.loadgen.destroy",
                title=f"Destroy loadgen VM{s}",
                lifecycle=adapter.loadgen_lifecycle(),
                info=ctx.loadgen_info,
            ),
            DestroyVm(
                task_id="vm.stack.destroy",
                title=f"Destroy stack VM{s}",
                lifecycle=adapter.stack_lifecycle(),
                info=ctx.stack_info,
            ),
        ]
    return [
        DestroyVm(
            task_id="vm.stack.destroy",
            title=f"Destroy stack VM{s}",
            lifecycle=adapter.stack_lifecycle(),
            info=ctx.stack_info,
        )
    ]
```

Update `loadtest_flow_task_ids` so the loadgen lifecycle IDs are conditional:

```python
    if _uses_dedicated_loadgen_vm(adapter):
        ids += ["vm.loadgen.ensure_running"]
    ids += list(adapter.extra_step_ids(FlowPhase.BEFORE_LOADGEN))
    ids += list(_LOADGEN_BODY_IDS)
    ids += _post_loadgen_task_ids(adapter)
    if _uses_dedicated_loadgen_vm(adapter):
        ids += ["vm.loadgen.destroy", "vm.stack.destroy"]
    else:
        ids += ["vm.stack.destroy"]
    return ids
```

Update `loadtest_flow_phase_titles` with the same conditional shape:

```python
    if _uses_dedicated_loadgen_vm(adapter):
        titles += [f"Ensure loadgen VM running{s}"]
    titles += list(_adapter_extra_titles(adapter, FlowPhase.BEFORE_LOADGEN))
    titles += [f"{base}{s}" for base in _LOADGEN_BODY_BASE_TITLES]
    titles += _post_loadgen_task_titles(adapter)
    if _uses_dedicated_loadgen_vm(adapter):
        titles += [f"Destroy loadgen VM{s}", f"Destroy stack VM{s}"]
    else:
        titles += [f"Destroy stack VM{s}"]
    return titles
```

- [ ] **Step 6: Run focused loadtest flow tests**

Run:

```bash
uv run --project tools/controlplane --locked pytest -q \
  tools/controlplane/tests/test_loadtest_flow_one_vm_hooks.py \
  tools/controlplane/tests/test_two_vm_loadtest_plan.py \
  tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py \
  tools/controlplane/tests/test_azure_vm_loadtest_runner.py
```

Expected: new one-VM hook tests pass and existing two-VM/proxmox/azure loadtest tests still pass.

- [ ] **Step 7: Commit Task 3**

Run:

```bash
git add tools/controlplane/src/controlplane_tool/scenario/loadtest_flow.py \
  tools/controlplane/src/controlplane_tool/scenario/loadtest_adapter.py \
  tools/controlplane/tests/test_loadtest_flow_one_vm_hooks.py
git commit -m "feat(controlplane): support one-vm loadtest flow hooks"
```

---

### Task 4: Implement One-VM Loadtest Adapter With Autoscaling Tail

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/scenario/one_vm_loadtest_adapter.py`
- Create: `tools/controlplane/tests/test_one_vm_loadtest_adapter.py`

- [ ] **Step 1: Write adapter tests**

Create `tools/controlplane/tests/test_one_vm_loadtest_adapter.py`:

```python
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from types import SimpleNamespace

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.e2e.e2e_runner import E2eRunner
from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.scenario.loadtest_flow import RunContext
from controlplane_tool.scenario.one_vm_loadtest_adapter import OneVmLoadtestAdapter
from workflow_tasks.components.function_tasks import RegisterFunctions
from workflow_tasks.loadtest.tasks import RunK6


@dataclass
class _Info:
    name: str = "nanofaas-e2e"
    host: str = "10.0.0.1"
    user: str = "ubuntu"
    home: str = "/home/ubuntu"


def _request(tmp_path: Path) -> E2eRequest:
    return E2eRequest(
        scenario="one-vm-helm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        cleanup_vm=True,
    )


def _ctx() -> RunContext:
    return RunContext(
        stack_info=_Info(),
        stack_host="10.0.0.1",
        loadgen_info=_Info(),
        control_plane_url="http://10.0.0.1:30080",
        prometheus_url="http://10.0.0.1:30090",
        run_dir=Path("/tmp/run"),
        remote_paths=SimpleNamespace(
            root_dir="/home/ubuntu/two-vm-loadtest",
            scripts_dir="/home/ubuntu/two-vm-loadtest/scripts",
            payloads_dir="/home/ubuntu/two-vm-loadtest/payloads",
            results_dir="/home/ubuntu/two-vm-loadtest/results",
            script_path="/home/ubuntu/two-vm-loadtest/scripts/script.js",
            summary_path="/home/ubuntu/two-vm-loadtest/results/k6-summary.json",
            payload_path=None,
        ),
    )


def test_one_vm_adapter_reuses_stack_vm_for_loadgen(tmp_path: Path) -> None:
    adapter = OneVmLoadtestAdapter(
        runner=E2eRunner(repo_root=tmp_path),
        request=_request(tmp_path),
    )

    assert adapter.uses_dedicated_loadgen_vm() is False
    assert adapter.control_plane_url(_ctx()) == "http://10.0.0.1:30080"
    assert adapter.prometheus_url(_ctx()) == "http://10.0.0.1:30090"


def test_one_vm_adapter_builds_autoscaling_tail_tasks(tmp_path: Path) -> None:
    asset = tmp_path / "tools" / "controlplane" / "assets" / "k6" / "autoscaling.js"
    asset.parent.mkdir(parents=True)
    asset.write_text("export default function () {}\n", encoding="utf-8")
    adapter = OneVmLoadtestAdapter(
        runner=E2eRunner(repo_root=tmp_path),
        request=_request(tmp_path),
    )

    tasks = adapter.post_loadgen_tasks(_ctx())

    assert [task.task_id for task in tasks] == [
        "autoscaling.register_function",
        "autoscaling.run_k6",
        "autoscaling.verify_replicas",
    ]
    assert isinstance(tasks[0], RegisterFunctions)
    assert isinstance(tasks[1], RunK6)
    assert tasks[1].config.script_path == Path("/home/ubuntu/two-vm-loadtest/scripts/autoscaling.js")
    assert tasks[1].config.env["NANOFAAS_FUNCTION"] == "word-stats-java"
```

- [ ] **Step 2: Run tests and verify missing module**

Run:

```bash
uv run --project tools/controlplane --locked pytest -q \
  tools/controlplane/tests/test_one_vm_loadtest_adapter.py
```

Expected: failure with `ModuleNotFoundError: No module named 'controlplane_tool.scenario.one_vm_loadtest_adapter'`.

- [ ] **Step 3: Implement the adapter**

Create `tools/controlplane/src/controlplane_tool/scenario/one_vm_loadtest_adapter.py`:

```python
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from controlplane_tool.autoscaling.tasks import VerifyAutoscalingReplicas
from controlplane_tool.infra.vm_lifecycle_adapters import MultipassVmAdapter
from controlplane_tool.scenario.loadtest_adapter import MultipassConnectivity
from controlplane_tool.scenario.loadtest_flow import FlowPhase, RunContext
from controlplane_tool.scenario.scenarios._workflow_assembly import _Setup, build_setup
from controlplane_tool.scenario.scenario_helpers import function_image, selected_functions
from controlplane_tool.scenario.two_vm_loadtest_config import (
    two_vm_control_plane_url,
    two_vm_prometheus_url,
    two_vm_remote_paths,
    two_vm_target_function,
)
from workflow_tasks.components.function_tasks import FunctionSpec, RegisterFunctions
from workflow_tasks.loadtest.models import K6Config, K6Stage
from workflow_tasks.loadtest.tasks import RunK6


@dataclass
class OneVmLoadtestAdapter:
    runner: object
    request: object
    title_suffix: str = " (one VM)"
    _cached_setup: _Setup | None = field(default=None, init=False, repr=False)

    @property
    def connectivity(self) -> MultipassConnectivity:
        return MultipassConnectivity(runner=self.runner, request=self.request)

    def uses_dedicated_loadgen_vm(self) -> bool:
        return False

    def stack_lifecycle(self):
        return MultipassVmAdapter(self.runner.vm)

    def loadgen_lifecycle(self):
        return self.stack_lifecycle()

    def loadgen_install_endpoint(self, ctx: RunContext):
        from controlplane_tool.scenario.loadtest_adapter import _Endpoint

        return _Endpoint(host=ctx.stack_info.host, user=ctx.stack_info.user, private_key=None, port=None)

    def loadgen_runner(self, ctx: RunContext):
        return self.connectivity.vm_runner(self.request.vm)

    def fetcher(self, ctx: RunContext):
        from controlplane_tool.scenario.loadtest_adapter import _MultipassFetcher

        return _MultipassFetcher(self.runner.vm, self.request.vm)

    def control_plane_url(self, ctx: RunContext) -> str:
        return two_vm_control_plane_url(self.request.vm, host=ctx.stack_info.host)

    def prometheus_url(self, ctx: RunContext) -> str:
        return two_vm_prometheus_url(self.request.vm, host=ctx.stack_info.host)

    def prepare_loadgen(self, ctx: RunContext) -> None:
        from controlplane_tool.e2e.two_vm_loadtest_runner import TwoVmLoadtestRunner

        request = self.request.model_copy(update={"loadgen_vm": self.request.vm})
        TwoVmLoadtestRunner(
            repo_root=self.runner.paths.workspace_root,
            shell=self.runner.shell,
            runs_root=self.runner.paths.runs_dir,
        ).prepare_loadgen(request, ctx.remote_paths)
        autoscaling_asset = (
            self.runner.paths.workspace_root
            / "tools"
            / "controlplane"
            / "assets"
            / "k6"
            / "autoscaling.js"
        )
        self.runner.vm.transfer_to(
            self.request.vm,
            source=autoscaling_asset,
            destination=f"{ctx.remote_paths.scripts_dir}/autoscaling.js",
        )

    def create_run_dir(self) -> Path:
        from controlplane_tool.e2e.two_vm_loadtest_runner import TwoVmLoadtestRunner

        return TwoVmLoadtestRunner(
            repo_root=self.runner.paths.workspace_root,
            shell=self.runner.shell,
            runs_root=self.runner.paths.runs_dir,
        )._create_run_dir()  # noqa: SLF001

    def extra_steps(self, phase: FlowPhase, ctx: RunContext) -> list:
        return []

    def extra_step_ids(self, phase: FlowPhase) -> list[str]:
        return []

    def extra_step_titles(self, phase: FlowPhase) -> list[str]:
        return []

    def emits_step_events(self) -> bool:
        return False

    def cleanup_on_failure(self, error: Exception) -> list[str]:
        return []

    def register_functions(self, ctx: RunContext) -> None:
        setup = self._setup()
        runtime_image_default = f"{setup.context.local_registry}/nanofaas/function-runtime:e2e"
        RegisterFunctions(
            task_id="functions.register",
            title="Register functions",
            control_plane_url=ctx.control_plane_url,
            specs=[
                FunctionSpec(
                    name=fn_key,
                    image=function_image(fn_key, self.request.resolved_scenario, runtime_image_default),
                )
                for fn_key in selected_functions(self.request.resolved_scenario)
            ],
        ).run()

    def post_loadgen_task_ids(self) -> list[str]:
        return [
            "autoscaling.register_function",
            "autoscaling.run_k6",
            "autoscaling.verify_replicas",
        ]

    def post_loadgen_task_titles(self) -> list[str]:
        return [
            "Register autoscaling function",
            "Run autoscaling k6",
            "Verify autoscaling replicas",
        ]

    def post_loadgen_tasks(self, ctx: RunContext) -> list:
        setup = self._setup()
        runtime_image_default = f"{setup.context.local_registry}/nanofaas/function-runtime:e2e"
        function_name = two_vm_target_function(self.request)
        function_image_value = function_image(
            function_name,
            self.request.resolved_scenario,
            runtime_image_default,
        )
        autoscaling_script = Path(f"{ctx.remote_paths.scripts_dir}/autoscaling.js")
        autoscaling_summary = Path(f"{ctx.remote_paths.results_dir}/autoscaling-k6-summary.json")
        return [
            RegisterFunctions(
                task_id="autoscaling.register_function",
                title="Register autoscaling function",
                control_plane_url=ctx.control_plane_url,
                specs=[
                    FunctionSpec(
                        name=function_name,
                        image=function_image_value,
                        timeout_ms=30000,
                        concurrency=4,
                        queue_size=100,
                        max_retries=3,
                        scaling_config={
                            "strategy": "INTERNAL",
                            "minReplicas": 0,
                            "maxReplicas": 5,
                            "metrics": [{"type": "in_flight", "target": "2"}],
                        },
                    )
                ],
            ),
            RunK6(
                task_id="autoscaling.run_k6",
                title="Run autoscaling k6",
                runner=self.loadgen_runner(ctx),
                config=K6Config(
                    script_path=autoscaling_script,
                    target_url=ctx.control_plane_url,
                    summary_output_path=autoscaling_summary,
                    stages=(
                        K6Stage(duration="10s", target=10),
                        K6Stage(duration="20s", target=20),
                        K6Stage(duration="90s", target=20),
                        K6Stage(duration="10s", target=0),
                    ),
                    env={
                        "NANOFAAS_URL": ctx.control_plane_url,
                        "NANOFAAS_FUNCTION": function_name,
                    },
                ),
                remote_dir=ctx.loadgen_info.home,
            ),
            VerifyAutoscalingReplicas(
                task_id="autoscaling.verify_replicas",
                title="Verify autoscaling replicas",
                runner=self.loadgen_runner(ctx),
                namespace=setup.context.namespace,
                deployment_name=f"fn-{function_name}",
                remote_dir=ctx.loadgen_info.home,
            ),
        ]

    def _setup(self) -> _Setup:
        if self._cached_setup is None:
            self._cached_setup = build_setup(self.runner, self.request)
        return self._cached_setup
```

- [ ] **Step 4: Run focused adapter tests**

Run:

```bash
uv run --project tools/controlplane --locked pytest -q \
  tools/controlplane/tests/test_one_vm_loadtest_adapter.py
```

Expected: all tests pass.

- [ ] **Step 5: Commit Task 4**

Run:

```bash
git add tools/controlplane/src/controlplane_tool/scenario/one_vm_loadtest_adapter.py \
  tools/controlplane/tests/test_one_vm_loadtest_adapter.py
git commit -m "feat(controlplane): add one-vm loadtest adapter"
```

---

### Task 5: Register The one-vm-helm-loadtest Scenario

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/scenario/scenarios/one_vm_helm_loadtest.py`
- Modify: `tools/controlplane/src/controlplane_tool/core/models.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/catalog.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/components/recipes.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py`
- Modify: `tools/workflow-tasks/src/workflow_tasks/loadtest/two_vm.py`
- Modify: `tools/controlplane/tests/test_scenario_recipes.py`
- Modify: `tools/controlplane/tests/test_e2e_runner.py`

- [ ] **Step 1: Add scenario tests**

Append this test to `tools/controlplane/tests/test_scenario_recipes.py`:

```python
def test_one_vm_helm_loadtest_recipe_uses_stack_prelude_without_legacy_autoscaling() -> None:
    recipe = build_scenario_recipe("one-vm-helm-loadtest")

    assert recipe.requires_managed_vm is True
    _assert_order(
        list(recipe.component_ids),
        [
            "vm.ensure_running",
            "vm.provision_base",
            "repo.sync_to_vm",
            "registry.ensure_container",
            "images.build_core",
            "images.build_selected_functions",
            "k3s.install",
            "k3s.configure_registry",
            "namespace.install",
            "helm.deploy_control_plane",
            "helm.deploy_function_runtime",
        ],
    )
    assert "experiments.autoscaling" not in recipe.component_ids
    assert "loadtest.run" not in recipe.component_ids
```

Append this test to `tools/controlplane/tests/test_e2e_runner.py`:

```python
def test_one_vm_helm_loadtest_plan_uses_one_vm_adapter_task_shape() -> None:
    plan = E2eRunner(Path("/repo"), shell=RecordingShell()).plan(
        E2eRequest(
            scenario="one-vm-helm-loadtest",
            runtime="java",
            vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        )
    )

    assert plan.scenario.name == "one-vm-helm-loadtest"
    assert "vm.stack.ensure_running" in plan.task_ids
    assert "vm.loadgen.ensure_running" not in plan.task_ids
    assert "autoscaling.register_function" in plan.task_ids
    assert "autoscaling.run_k6" in plan.task_ids
    assert "autoscaling.verify_replicas" in plan.task_ids
```

- [ ] **Step 2: Run scenario tests and verify unknown scenario failures**

Run:

```bash
uv run --project tools/controlplane --locked pytest -q \
  tools/controlplane/tests/test_scenario_recipes.py::test_one_vm_helm_loadtest_recipe_uses_stack_prelude_without_legacy_autoscaling \
  tools/controlplane/tests/test_e2e_runner.py::test_one_vm_helm_loadtest_plan_uses_one_vm_adapter_task_shape
```

Expected: failures mentioning unsupported or unknown `one-vm-helm-loadtest`.

- [ ] **Step 3: Create the scenario plan module**

Create `tools/controlplane/src/controlplane_tool/scenario/scenarios/one_vm_helm_loadtest.py`:

```python
from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from workflow_tasks.components.models import ScenarioRecipe

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.scenario.catalog import ScenarioDefinition
from controlplane_tool.scenario.components.executor import ScenarioPlanStep
from controlplane_tool.scenario.components.recipes import STACK_PRELUDE
from controlplane_tool.scenario.scenarios._workflow_assembly import _Setup, build_command_tasks, build_setup

if TYPE_CHECKING:
    from controlplane_tool.e2e.e2e_runner import E2eRunner


@dataclass
class OneVmHelmLoadtestPlan:
    scenario: ScenarioDefinition
    request: E2eRequest
    steps: list[ScenarioPlanStep]
    runner: "E2eRunner" = field(repr=False, compare=False)

    @property
    def task_ids(self) -> list[str]:
        from controlplane_tool.scenario.loadtest_flow import loadtest_flow_task_ids

        return loadtest_flow_task_ids(
            runner=self.runner,
            request=self.request,
            setup=self._build_setup(),
            recipe=self._recipe(),
            adapter=self._adapter(),
        )

    @property
    def phase_titles(self) -> list[str]:
        from controlplane_tool.scenario.loadtest_flow import loadtest_flow_phase_titles

        return loadtest_flow_phase_titles(
            runner=self.runner,
            request=self.request,
            setup=self._build_setup(),
            recipe=self._recipe(),
            adapter=self._adapter(),
        )

    def _build_setup(self) -> _Setup:
        return build_setup(self.runner, self.request)

    def _recipe(self) -> ScenarioRecipe:
        return ScenarioRecipe(
            name="one-vm-helm-loadtest-stack",
            component_ids=STACK_PRELUDE,
            requires_managed_vm=True,
        )

    def _adapter(self):
        from controlplane_tool.scenario.one_vm_loadtest_adapter import OneVmLoadtestAdapter

        return OneVmLoadtestAdapter(runner=self.runner, request=self.request)

    def _build_stack_prelude_tasks(self, setup: _Setup, *, resolve_host: bool = True) -> list:
        return build_command_tasks(
            self.runner,
            self.request,
            setup,
            self._recipe(),
            resolve_host=resolve_host,
        )

    def run(self, event_listener=None) -> None:
        from controlplane_tool.scenario.loadtest_flow import run_loadtest_flow

        run_loadtest_flow(
            runner=self.runner,
            request=self.request,
            setup=self._build_setup(),
            recipe=self._recipe(),
            adapter=self._adapter(),
            event_listener=event_listener,
        )


def build_one_vm_helm_loadtest_plan(
    runner: "E2eRunner",
    request: E2eRequest,
) -> OneVmHelmLoadtestPlan:
    from controlplane_tool.scenario.catalog import resolve_scenario

    scenario = resolve_scenario("one-vm-helm-loadtest")
    return OneVmHelmLoadtestPlan(scenario=scenario, request=request, steps=[], runner=runner)
```

- [ ] **Step 4: Register scenario in core models**

In `tools/controlplane/src/controlplane_tool/core/models.py`, add `"one-vm-helm-loadtest"` to `ScenarioName` after `"helm-stack"`, and add it to `VM_BACKED_SCENARIOS`:

```python
ScenarioName = Literal[
    "docker",
    "buildpack",
    "container-local",
    "k3s-junit-curl",
    "cli",
    "cli-stack",
    "cli-host",
    "deploy-host",
    "helm-stack",
    "one-vm-helm-loadtest",
    "two-vm-loadtest",
    "azure-vm-loadtest",
    "proxmox-vm-loadtest",
]
```

```python
VM_BACKED_SCENARIOS = frozenset(
    {
        "k3s-junit-curl",
        "cli",
        "cli-stack",
        "cli-host",
        "helm-stack",
        "one-vm-helm-loadtest",
        "two-vm-loadtest",
        "azure-vm-loadtest",
        "proxmox-vm-loadtest",
    }
)
```

- [ ] **Step 5: Register scenario in catalog**

In `tools/controlplane/src/controlplane_tool/scenario/catalog.py`, add this entry immediately after `helm-stack`:

```python
    ScenarioDefinition(
        name="one-vm-helm-loadtest",
        description="One-VM Helm stack load test with autoscaling verification.",
        requires_vm=True,
        supported_runtimes=("java", "rust"),
        grouped_phases=True,
    ),
```

- [ ] **Step 6: Register scenario recipe**

In `tools/controlplane/src/controlplane_tool/scenario/components/recipes.py`, add:

```python
    "one-vm-helm-loadtest": ScenarioRecipe(
        name="one-vm-helm-loadtest",
        component_ids=STACK_PRELUDE,
        requires_managed_vm=True,
    ),
```

Place it after the `helm-stack` recipe.

- [ ] **Step 7: Add loadtest scenario set membership**

In `tools/workflow-tasks/src/workflow_tasks/loadtest/two_vm.py`, include `"one-vm-helm-loadtest"`:

```python
LOADTEST_SCENARIOS: frozenset[str] = frozenset(
    {
        "one-vm-helm-loadtest",
        "two-vm-loadtest",
        "azure-vm-loadtest",
        "proxmox-vm-loadtest",
    }
)
```

- [ ] **Step 8: Route E2eRunner.plan**

In `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py`, add this branch after the `helm-stack` branch in `plan()`:

```python
        if request.scenario == "one-vm-helm-loadtest":
            from controlplane_tool.scenario.scenarios.one_vm_helm_loadtest import (
                build_one_vm_helm_loadtest_plan,
            )
            return build_one_vm_helm_loadtest_plan(self, self._prepare_recipe_request(request))
```

In `plan_all()`, add a matching branch in the VM-backed section:

```python
                if scenario.name == "one-vm-helm-loadtest":
                    from controlplane_tool.scenario.scenarios.one_vm_helm_loadtest import (
                        build_one_vm_helm_loadtest_plan,
                    )
                    plans.append(build_one_vm_helm_loadtest_plan(self, request))
                    vm_bootstrap_planned = True
                    continue
```

- [ ] **Step 9: Run focused scenario tests**

Run:

```bash
uv run --project tools/controlplane --locked pytest -q \
  tools/controlplane/tests/test_scenario_recipes.py::test_one_vm_helm_loadtest_recipe_uses_stack_prelude_without_legacy_autoscaling \
  tools/controlplane/tests/test_e2e_runner.py::test_one_vm_helm_loadtest_plan_uses_one_vm_adapter_task_shape
```

Expected: all three tests pass.

- [ ] **Step 10: Run broader scenario tests**

Run:

```bash
uv run --project tools/controlplane --locked pytest -q \
  tools/controlplane/tests/test_scenario_recipes.py \
  tools/controlplane/tests/test_e2e_runner.py \
  tools/controlplane/tests/test_helm_stack_workflow.py
```

Expected: all selected tests pass, including existing `helm-stack` tests.

- [ ] **Step 11: Commit Task 5**

Run:

```bash
git add tools/controlplane/src/controlplane_tool/scenario/scenarios/one_vm_helm_loadtest.py \
  tools/controlplane/src/controlplane_tool/core/models.py \
  tools/controlplane/src/controlplane_tool/scenario/catalog.py \
  tools/controlplane/src/controlplane_tool/scenario/components/recipes.py \
  tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py \
  tools/workflow-tasks/src/workflow_tasks/loadtest/two_vm.py \
  tools/controlplane/tests/test_scenario_recipes.py \
  tools/controlplane/tests/test_e2e_runner.py
git commit -m "feat(controlplane): add one-vm helm loadtest scenario"
```

---

### Task 6: CLI Visibility, Docs, And Final Verification

**Files:**
- Modify: `tools/controlplane/README.md`
- Modify: `README.md`
- Modify: `docs/testing.md`
- Modify: `tools/controlplane/tests/test_docs_links.py` if needed by existing docs assertions.

- [ ] **Step 1: Add documentation references**

In `tools/controlplane/README.md`, add this paragraph near the loadtest scenario section:

```markdown
`scripts/controlplane.sh e2e run one-vm-helm-loadtest` runs the Helm stack and load generator on the same managed VM. It reuses the modern loadtest workflow, writes artifacts under `tools/controlplane/runs/`, and includes autoscaling verification without invoking the legacy `experiments/autoscaling.py` script.
```

In root `README.md`, add this sentence near the existing `two-vm-loadtest` description:

```markdown
Use `scripts/controlplane.sh e2e run one-vm-helm-loadtest` when the Helm stack, k6 load generation, Prometheus snapshots, report generation, and autoscaling verification should run through the modern workflow on one VM.
```

In `docs/testing.md`, add this command near the load testing commands:

```markdown
./scripts/controlplane.sh e2e run one-vm-helm-loadtest
```

- [ ] **Step 2: Run docs tests**

Run:

```bash
uv run --project tools/controlplane --locked pytest -q \
  tools/controlplane/tests/test_docs_links.py \
  tools/controlplane/tests/test_canonical_entrypoints.py
```

Expected: docs tests pass. If a test expects an explicit scenario list, update that assertion to include `one-vm-helm-loadtest`.

- [ ] **Step 3: Run Python quality checks for touched packages**

Run:

```bash
uv run --project tools/controlplane --locked ruff check \
  tools/controlplane/src/controlplane_tool/autoscaling \
  tools/controlplane/src/controlplane_tool/scenario \
  tools/controlplane/tests/test_autoscaling_tasks.py \
  tools/controlplane/tests/test_one_vm_loadtest_adapter.py
```

Expected: no ruff violations.

- [ ] **Step 4: Run focused regression suite**

Run:

```bash
uv run --project tools/controlplane --locked pytest -q \
  tools/workflow-tasks/tests/components/test_function_tasks.py \
  tools/controlplane/tests/test_autoscaling_tasks.py \
  tools/controlplane/tests/test_one_vm_loadtest_adapter.py \
  tools/controlplane/tests/test_loadtest_flow_one_vm_hooks.py \
  tools/controlplane/tests/test_scenario_recipes.py \
  tools/controlplane/tests/test_e2e_runner.py \
  tools/controlplane/tests/test_helm_stack_workflow.py \
  tools/controlplane/tests/test_two_vm_loadtest_plan.py \
  tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py
```

Expected: all selected tests pass.

- [ ] **Step 5: Verify CLI lists the new scenario**

Run:

```bash
uv run --project tools/controlplane --locked controlplane-tool e2e list
```

Expected: output includes `one-vm-helm-loadtest` and still includes `helm-stack`.

- [ ] **Step 6: Verify dry-run plan shape**

Run:

```bash
uv run --project tools/controlplane --locked controlplane-tool e2e run one-vm-helm-loadtest --dry-run
```

Expected: dry-run output includes the Helm deploy steps, canonical k6/report steps, `autoscaling.register_function`, `autoscaling.run_k6`, and `autoscaling.verify_replicas`; it does not include `experiments.autoscaling`.

- [ ] **Step 7: Run GitNexus change detection before final commit**

Run:

```text
gitnexus_detect_changes({scope: "all"})
```

Expected: changed symbols and flows match this plan. If GitNexus reports unrelated execution flows, inspect them before committing.

- [ ] **Step 8: Commit Task 6**

Run:

```bash
git add README.md docs/testing.md tools/controlplane/README.md tools/controlplane/tests/test_docs_links.py
git commit -m "docs: document one-vm helm loadtest scenario"
```

---

## Final Verification

Run the full focused command set before handing the branch back:

```bash
uv run --project tools/controlplane --locked pytest -q \
  tools/workflow-tasks/tests/components/test_function_tasks.py \
  tools/controlplane/tests/test_autoscaling_tasks.py \
  tools/controlplane/tests/test_one_vm_loadtest_adapter.py \
  tools/controlplane/tests/test_loadtest_flow_one_vm_hooks.py \
  tools/controlplane/tests/test_scenario_recipes.py \
  tools/controlplane/tests/test_e2e_runner.py \
  tools/controlplane/tests/test_helm_stack_workflow.py \
  tools/controlplane/tests/test_two_vm_loadtest_plan.py \
  tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py \
  tools/controlplane/tests/test_docs_links.py \
  tools/controlplane/tests/test_canonical_entrypoints.py
```

Expected: all tests pass.

Run:

```bash
uv run --project tools/controlplane --locked ruff check \
  tools/workflow-tasks/src/workflow_tasks/components/function_tasks.py \
  tools/controlplane/src/controlplane_tool/autoscaling \
  tools/controlplane/src/controlplane_tool/scenario \
  tools/controlplane/tests/test_autoscaling_tasks.py \
  tools/controlplane/tests/test_one_vm_loadtest_adapter.py \
  tools/controlplane/tests/test_loadtest_flow_one_vm_hooks.py
```

Expected: no ruff violations.

Run:

```bash
uv run --project tools/controlplane --locked controlplane-tool e2e list
```

Expected: `one-vm-helm-loadtest` appears in the scenario list.

Run:

```bash
uv run --project tools/controlplane --locked controlplane-tool e2e run one-vm-helm-loadtest --dry-run
```

Expected: dry-run includes one stack VM lifecycle, the Helm deploy prelude, canonical k6/report tasks, and autoscaling tail tasks.

Run:

```text
gitnexus_detect_changes({scope: "all"})
```

Expected: change scope is limited to function spec serialization, autoscaling verification, loadtest flow adapter hooks, one-VM scenario registration, and documentation.

## Self-Review Notes

- Spec coverage: the plan keeps `helm-stack` unchanged, creates `one-vm-helm-loadtest`, reuses `RegisterFunctions`, reuses `RunK6`, keeps one VM, and includes autoscaling verification.
- Placeholder scan: this plan contains concrete file paths, code snippets, commands, and expected outcomes for each implementation task.
- Type consistency: scenario name is consistently `one-vm-helm-loadtest`; autoscaling task IDs are consistently `autoscaling.register_function`, `autoscaling.run_k6`, and `autoscaling.verify_replicas`; the one-VM adapter hook names match the loadtest flow hook names.
