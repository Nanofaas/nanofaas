# Remove kubectl — Replace with Helm Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate all kubectl usage from the controlplane tool by leveraging Helm's `--create-namespace`, `--wait`, and namespace-ownership features. `KubectlOps` is intentionally kept in shellcraft for potential future use.

**Architecture:** Three kubectl operations are in play — namespace creation (replaced by `--create-namespace`), readiness wait (replaced by `--wait`, already present), and namespace deletion (replaced by making the namespace a Helm-managed resource via `namespace.create=true`). Legacy runners (`cli_vm_runner`, `k3s_curl_runner`) and the new component-based path (scenario_components) are updated independently.

**Tech Stack:** Python 3.11, Helm 3, uv/pytest for testing

**Impact analysis results (GitNexus):** All symbols: LOW risk. KubectlOps: MEDIUM (only test files and module-level imports, not production callers). No HIGH/CRITICAL warnings.

---

## File map

### Modified in `tools/controlplane/`

| File | Change |
|---|---|
| `src/controlplane_tool/helm_ops.py` | Add `create_namespace: bool = False` to `upgrade_install` |
| `src/controlplane_tool/scenario_tasks.py` | Remove `kubectl_create_namespace_vm_script`, `kubectl_delete_namespace_vm_script`, `kubectl_rollout_status_vm_script` |
| `src/controlplane_tool/scenario_components/helm.py` | Remove `plan_wait_control_plane_ready`, `plan_wait_function_runtime_ready`, their constants |
| `src/controlplane_tool/scenario_components/cleanup.py` | Remove `plan_delete_namespace`, `DELETE_NAMESPACE`; add `--wait` to control-plane uninstall |
| `src/controlplane_tool/scenario_components/composer.py` | Remove `K8S_WAIT_CONTROL_PLANE_READY`, `K8S_WAIT_FUNCTION_RUNTIME_READY`, `DELETE_NAMESPACE`; fix uninstall order |
| `src/controlplane_tool/vm_cluster_workflows.py` | Remove `plan_wait_control_plane_ready`, `plan_wait_function_runtime_ready` calls |
| `src/controlplane_tool/cli_vm_runner.py` | Replace `kubectl_create_namespace_vm_script` + `kubectl_rollout_status_vm_script` with `create_namespace=True` in helm call |
| `src/controlplane_tool/k3s_curl_runner.py` | Same as cli_vm_runner + replace `kubectl_delete_namespace_vm_script` with `helm_uninstall_vm_script` |
| `src/controlplane_tool/scenario_planner.py` | Replace `kubectl_delete_namespace_vm_script` with `helm_uninstall_vm_script` |
| `tests/test_helm_ops.py` | Add `create_namespace` test |
| `tests/test_scenario_tasks.py` | Remove kubectl create namespace test, update kubeconfig test |
| `tests/test_scenario_component_library.py` | Update `test_helm_component_planners_use_namespace_and_helm_values` |
| `tests/test_e2e_runner.py` | Replace `"kubectl delete namespace"` token with `"helm uninstall"` |

### `~/shellcraft/` — unchanged

`KubectlOps` is retained in shellcraft for future use.

---

## Task 1: Add `create_namespace` to `HelmOps.upgrade_install`

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/helm_ops.py`
- Test: `tools/controlplane/tests/test_helm_ops.py`

- [ ] **Step 1: Write the failing test**

Add to `tests/test_helm_ops.py`:

```python
def test_helm_ops_build_upgrade_install_command_with_create_namespace() -> None:
    command = HelmOps(Path("/repo")).upgrade_install(
        release="control-plane",
        chart=Path("helm/nanofaas"),
        namespace="nanofaas-e2e",
        values={},
        create_namespace=True,
    )

    assert "--create-namespace" in command.command
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd tools/controlplane && uv run pytest tests/test_helm_ops.py::test_helm_ops_build_upgrade_install_command_with_create_namespace -v
```

Expected: FAIL — `upgrade_install() got an unexpected keyword argument 'create_namespace'`

- [ ] **Step 3: Add `create_namespace` parameter to `HelmOps.upgrade_install`**

In `src/controlplane_tool/helm_ops.py`, change the method signature and body:

```python
def upgrade_install(
    self,
    *,
    release: str,
    chart: Path,
    namespace: str,
    values: dict[str, str] | None = None,
    wait: bool = True,
    timeout: str = "3m",
    create_namespace: bool = False,
    dry_run: bool = False,
) -> PlannedCommand:
    command = [
        self.binary,
        "upgrade",
        "--install",
        release,
        str(chart),
        "-n",
        namespace,
    ]
    if create_namespace:
        command.append("--create-namespace")
    for key, value in sorted((values or {}).items()):
        command.extend(["--set", f"{key}={value}"])
    if wait:
        command.append("--wait")
    if timeout:
        command.extend(["--timeout", timeout])
    if dry_run:
        command.append("--dry-run")
    return PlannedCommand(command=command, cwd=Path(self.repo_root))
```

- [ ] **Step 4: Run all helm_ops tests**

```bash
cd tools/controlplane && uv run pytest tests/test_helm_ops.py -v
```

Expected: all PASS

- [ ] **Step 5: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/.worktrees/ansible-vm-provisioning
git add tools/controlplane/src/controlplane_tool/helm_ops.py tools/controlplane/tests/test_helm_ops.py
git commit -m "feat(helm): add create_namespace parameter to HelmOps.upgrade_install"
```

---

## Task 2: Replace kubectl in legacy runners

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/cli_vm_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/k3s_curl_runner.py`

Both runners currently: (1) create namespace with kubectl, (2) deploy with helm, (3) wait readiness with kubectl rollout status. The fix: pass `create_namespace=True` to the helm script (which already uses `--wait`), drop both kubectl calls.

- [ ] **Step 1: Update `CliVmRunner._deploy_platform` in `cli_vm_runner.py`**

Find `_deploy_platform` and make these changes:

Remove the import of `kubectl_create_namespace_vm_script` and `kubectl_rollout_status_vm_script` from the top of the file.

Remove the `kubectl_create_namespace_vm_script` call block:
```python
# DELETE THIS BLOCK:
self._vm_exec(
    kubectl_create_namespace_vm_script(
        remote_dir=self._remote_dir,
        namespace=self.namespace,
        kubeconfig_path=self._kubeconfig_path,
    )
)
```

Add `create_namespace=True` to the existing `helm_upgrade_install_vm_script` call for the control-plane deploy:
```python
self._vm_exec(
    helm_upgrade_install_vm_script(
        remote_dir=self._remote_dir,
        release="control-plane",
        chart="helm/nanofaas",
        namespace=self.namespace,
        values={
            "controlPlane.image.repository": self._control_image.split(":")[0],
            "controlPlane.image.tag": self._control_image.split(":")[-1],
        },
        create_namespace=True,
        kubeconfig_path=self._kubeconfig_path,
    )
)
```

Remove the `kubectl_rollout_status_vm_script` call block:
```python
# DELETE THIS BLOCK (helm --wait already covers this):
self._vm_exec(
    kubectl_rollout_status_vm_script(
        remote_dir=self._remote_dir,
        namespace=self.namespace,
        deployment="function-runtime",
        kubeconfig_path=self._kubeconfig_path,
        timeout=120,
    )
)
```

- [ ] **Step 2: Update `helm_upgrade_install_vm_script` in `scenario_tasks.py` to pass through `create_namespace`**

In `src/controlplane_tool/scenario_tasks.py`, update `helm_upgrade_install_vm_script` signature and body:

```python
def helm_upgrade_install_vm_script(
    *,
    remote_dir: str,
    release: str,
    chart: str,
    namespace: str,
    values: dict[str, str],
    kubeconfig_path: str | None = None,
    timeout: str = "3m",
    create_namespace: bool = False,
) -> str:
    command = HelmOps(Path(remote_dir)).upgrade_install(
        release=release,
        chart=Path(chart),
        namespace=namespace,
        values=values,
        wait=True,
        timeout=timeout,
        create_namespace=create_namespace,
    ).command
    return _render_remote_script(
        remote_dir=remote_dir,
        commands=[_with_kubeconfig(command, kubeconfig_path=kubeconfig_path)],
    )
```

- [ ] **Step 3: Update `K3sCurlRunner._deploy_platform` in `k3s_curl_runner.py`**

Same pattern as Step 1 for the k3s runner:
- Remove `kubectl_create_namespace_vm_script` call from `_deploy_platform`
- Add `create_namespace=True` to the control-plane `helm_upgrade_install_vm_script` call
- Remove `kubectl_rollout_status_vm_script` calls from `_wait_for_deployment` method (or remove the method if it's now empty and inline the helm `--wait` note)
- Remove the imports of `kubectl_create_namespace_vm_script` and `kubectl_rollout_status_vm_script` from the top of the file

- [ ] **Step 4: Run the test suite to catch regressions**

```bash
cd tools/controlplane && uv run pytest tests/test_cli_runtime.py tests/test_k3s_runtime.py tests/test_scenario_tasks.py -v
```

Expected: all PASS (the cli and k3s runner tests don't assert kubectl calls)

- [ ] **Step 5: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/.worktrees/ansible-vm-provisioning
git add tools/controlplane/src/controlplane_tool/cli_vm_runner.py \
    tools/controlplane/src/controlplane_tool/k3s_curl_runner.py \
    tools/controlplane/src/controlplane_tool/scenario_tasks.py
git commit -m "refactor(runners): replace kubectl namespace/wait with helm --create-namespace --wait"
```

---

## Task 3: Remove kubectl wait components from scenario_components path

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario_components/helm.py`
- Modify: `tools/controlplane/src/controlplane_tool/vm_cluster_workflows.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario_components/composer.py`
- Test: `tools/controlplane/tests/test_scenario_component_library.py`
- Test: `tools/controlplane/tests/test_e2e_runner.py`

`plan_deploy_control_plane` and `plan_deploy_function_runtime` already use `--wait`, so the separate `plan_wait_*` components are redundant.

- [ ] **Step 1: Update the test first**

In `tests/test_scenario_component_library.py`, find `test_helm_component_planners_use_namespace_and_helm_values` and remove the assertions on `wait_control_plane_operations` and `wait_runtime_operations`:

```python
def test_helm_component_planners_use_namespace_and_helm_values() -> None:
    context = _managed_context(namespace="nanofaas-stack")

    control_plane_operations = helm.plan_deploy_control_plane(context)
    runtime_operations = helm.plan_deploy_function_runtime(context)

    assert control_plane_operations and runtime_operations
    assert all(
        isinstance(operation, RemoteCommandOperation) for operation in control_plane_operations
    )
    assert all(isinstance(operation, RemoteCommandOperation) for operation in runtime_operations)
    assert "--create-namespace" in control_plane_operations[0].argv
    assert "--wait" in control_plane_operations[0].argv
    assert "--wait" in runtime_operations[0].argv
    assert "helm" in control_plane_operations[0].argv[0]
    assert "nanofaas-stack" in " ".join(control_plane_operations[0].argv)
    assert "nanofaas-stack" in " ".join(runtime_operations[0].argv)
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd tools/controlplane && uv run pytest tests/test_scenario_component_library.py::test_helm_component_planners_use_namespace_and_helm_values -v
```

Expected: PASS (this test doesn't depend on the wait functions, just on deploy functions — the test will already pass before we remove the functions, but confirms the new assertions are valid)

- [ ] **Step 3: Remove `plan_wait_control_plane_ready` and `plan_wait_function_runtime_ready` from `scenario_components/helm.py`**

Delete these two functions (lines ~150-195) and their constants (lines ~210-220):
- `plan_wait_control_plane_ready`
- `plan_wait_function_runtime_ready`
- `K8S_WAIT_CONTROL_PLANE_READY`
- `K8S_WAIT_FUNCTION_RUNTIME_READY`

- [ ] **Step 4: Remove the wait calls from `vm_cluster_workflows.py`**

In `src/controlplane_tool/vm_cluster_workflows.py`, find and remove:
```python
*helm_components.plan_wait_control_plane_ready(scenario_context),
*helm_components.plan_wait_function_runtime_ready(scenario_context),
```

Also remove the now-unused import of these functions if they are imported explicitly.

- [ ] **Step 5: Remove registrations from `composer.py`**

In `src/controlplane_tool/scenario_components/composer.py`:

Remove from imports:
```python
K8S_WAIT_CONTROL_PLANE_READY, K8S_WAIT_FUNCTION_RUNTIME_READY,
```

Remove from the registration list:
```python
K8S_WAIT_CONTROL_PLANE_READY, K8S_WAIT_FUNCTION_RUNTIME_READY,
```

- [ ] **Step 6: Run the full test suite**

```bash
cd tools/controlplane && uv run pytest tests/test_scenario_component_library.py tests/test_e2e_runner.py -v
```

Expected: all PASS

- [ ] **Step 7: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/.worktrees/ansible-vm-provisioning
git add tools/controlplane/src/controlplane_tool/scenario_components/helm.py \
    tools/controlplane/src/controlplane_tool/vm_cluster_workflows.py \
    tools/controlplane/src/controlplane_tool/scenario_components/composer.py \
    tools/controlplane/tests/test_scenario_component_library.py
git commit -m "refactor(scenario): remove kubectl wait components, helm --wait is sufficient"
```

---

## Task 4: Eliminate kubectl delete namespace — Helm-managed namespace with correct bootstrap order

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario_components/helm.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario_components/cleanup.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario_components/composer.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario_planner.py`
- Modify: `tools/controlplane/src/controlplane_tool/k3s_curl_runner.py`
- Test: `tools/controlplane/tests/test_e2e_runner.py`

> **Design rationale:** Helm stores its release state (secrets) in the target namespace. Two flags work together:
> - `--create-namespace` on the CLI: Helm creates the namespace BEFORE it tries to store the release secret — this solves the bootstrap problem.
> - `namespace.create=true` in chart values: the namespace becomes a Helm-managed resource — `helm uninstall` deletes it.
>
> When `helm uninstall control-plane` runs, Kubernetes cascade-deletes the Helm release secret along with the namespace. Helm may log a warning but the outcome is correct: all resources gone, no orphaned namespace. This is standard Helm behavior and is documented.
>
> Cleanup order is critical: `helm uninstall function-runtime` FIRST (removes its resources, namespace stays), then `helm uninstall control-plane` (removes control-plane resources AND the namespace). If reversed, the function-runtime release state would be lost in the namespace cascade.

- [ ] **Step 1: Update the test first**

In `tests/test_e2e_runner.py`, find the test that asserts `"kubectl delete namespace"` (around line 241) and remove that token — after this change only `"helm uninstall"` steps will appear in cleanup:

```python
rendered = [
    " ".join(step.command)
    for step in plan.steps
    if any(
        token in " ".join(step.command)
        for token in (
            "platform install",
            "platform status",
            "helm uninstall",
        )
    )
]

assert rendered
assert all("nanofaas-cli-stack-e2e" in command for command in rendered)
```

- [ ] **Step 2: Run test to see current state**

```bash
cd tools/controlplane && uv run pytest tests/test_e2e_runner.py -k "cli_stack" -v
```

Note the current result (may pass or fail); this is the baseline before code changes.

- [ ] **Step 3: Set `namespace.create=true` in `control_plane_helm_values`**

In `src/controlplane_tool/scenario_components/helm.py`, in `control_plane_helm_values()`:

```python
# Change:
"namespace.create": "false",
# To:
"namespace.create": "true",
```

The `--create-namespace` flag stays on the deploy command — it ensures the namespace exists before Helm stores its release secret on the very first install.

- [ ] **Step 4: Remove `plan_delete_namespace` and `DELETE_NAMESPACE` from `cleanup.py`**

In `src/controlplane_tool/scenario_components/cleanup.py`:
- Delete the `plan_delete_namespace` function entirely
- Delete the `DELETE_NAMESPACE` constant

Add `--wait` to `plan_uninstall_control_plane` to ensure Kubernetes finishes deleting the namespace before subsequent steps run:

```python
def plan_uninstall_control_plane(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    namespace = _namespace(context)
    return (
        RemoteCommandOperation(
            operation_id="cleanup.uninstall_control_plane",
            summary="Uninstall control plane with Helm (also removes namespace)",
            argv=("helm", "uninstall", _control_plane_release(context), "-n", namespace, "--wait"),
            env=_frozen_env({"KUBECONFIG": _kubeconfig_path(context)}),
            execution_target="vm",
        ),
    )
```

- [ ] **Step 5: Update `composer.py` — remove `DELETE_NAMESPACE`, fix uninstall order**

In `src/controlplane_tool/scenario_components/composer.py`:

Remove `DELETE_NAMESPACE` from imports:
```python
# Change:
from controlplane_tool.scenario_components.cleanup import (
    DELETE_NAMESPACE, UNINSTALL_CONTROL_PLANE, UNINSTALL_FUNCTION_RUNTIME,
    VERIFY_CLI_PLATFORM_STATUS_FAILS, VM_DOWN,
)
# To:
from controlplane_tool.scenario_components.cleanup import (
    UNINSTALL_CONTROL_PLANE, UNINSTALL_FUNCTION_RUNTIME,
    VERIFY_CLI_PLATFORM_STATUS_FAILS, VM_DOWN,
)
```

In the registration list, remove `DELETE_NAMESPACE` and ensure `UNINSTALL_FUNCTION_RUNTIME` is registered before `UNINSTALL_CONTROL_PLANE`:
```python
UNINSTALL_FUNCTION_RUNTIME, UNINSTALL_CONTROL_PLANE,
VERIFY_CLI_PLATFORM_STATUS_FAILS, VM_DOWN,
```

- [ ] **Step 6: Replace `kubectl_delete_namespace_vm_script` in `scenario_planner.py`**

In `src/controlplane_tool/scenario_planner.py`, find `k3s_junit_curl_tail_steps`. Replace the `kubectl_delete_namespace_vm_script` call with `helm_uninstall_vm_script` for the control-plane release:

```python
# Remove:
kubectl_delete_namespace_vm_script(
    remote_dir=remote_dir,
    namespace=namespace,
    kubeconfig_path=kubeconfig_path,
)

# Add (after the existing helm uninstall control-plane step, or replace if there's no separate uninstall):
helm_uninstall_vm_script(
    remote_dir=remote_dir,
    release="control-plane",
    namespace=namespace,
    kubeconfig_path=kubeconfig_path,
)
```

Update the import at the top of `scenario_planner.py`: remove `kubectl_delete_namespace_vm_script`.

- [ ] **Step 7: Replace `kubectl_delete_namespace_vm_script` in `k3s_curl_runner.py`**

In `src/controlplane_tool/k3s_curl_runner.py`, in `cleanup_platform`:

```python
# Remove:
self._vm_exec(
    kubectl_delete_namespace_vm_script(
        remote_dir=self._remote_dir,
        namespace=self.namespace,
        kubeconfig_path=self._kubeconfig_path,
    )
)

# Add (ensure this comes AFTER the function-runtime uninstall):
# Note: k3s_curl_runner already calls helm_uninstall for control-plane;
# if namespace deletion was the only purpose of this call, remove it entirely
# since helm uninstall control-plane --wait now handles namespace deletion.
```

Remove the import of `kubectl_delete_namespace_vm_script` from `k3s_curl_runner.py`.

- [ ] **Step 8: Verify no kubectl calls remain in source**

```bash
grep -rn '"kubectl"' tools/controlplane/src/
```

Expected: no output

- [ ] **Step 9: Run the full test suite**

```bash
cd tools/controlplane && uv run pytest tests/ -q --tb=short
```

Expected: all tests PASS

- [ ] **Step 10: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/.worktrees/ansible-vm-provisioning
git add tools/controlplane/src/controlplane_tool/scenario_components/helm.py \
    tools/controlplane/src/controlplane_tool/scenario_components/cleanup.py \
    tools/controlplane/src/controlplane_tool/scenario_components/composer.py \
    tools/controlplane/src/controlplane_tool/scenario_planner.py \
    tools/controlplane/src/controlplane_tool/k3s_curl_runner.py \
    tools/controlplane/tests/test_e2e_runner.py
git commit -m "refactor(scenario): eliminate kubectl delete namespace — Helm now owns namespace lifecycle"
```

---

## Task 5: Remove dead kubectl functions from `scenario_tasks.py`

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario_tasks.py`
- Test: `tools/controlplane/tests/test_scenario_tasks.py`

After Tasks 2–4, `kubectl_create_namespace_vm_script` and `kubectl_rollout_status_vm_script` have no callers. `kubectl_delete_namespace_vm_script` still has callers (`scenario_planner.py`, `k3s_curl_runner.py`) and must be kept — namespace deletion remains the one legitimate kubectl operation.

- [ ] **Step 1: Verify no remaining callers for the two removable functions**

```bash
grep -rn "kubectl_create_namespace_vm_script\|kubectl_rollout_status_vm_script" tools/controlplane/src/
```

Expected: no output

- [ ] **Step 2: Remove the two dead functions from `scenario_tasks.py`**

Delete from `src/controlplane_tool/scenario_tasks.py`:
- `kubectl_create_namespace_vm_script` (lines ~251-270)
- `kubectl_rollout_status_vm_script` (lines ~293-320)

Keep `kubectl_delete_namespace_vm_script` — it is still used for namespace cleanup.

- [ ] **Step 3: Update `test_scenario_tasks.py`**

Remove the import of `kubectl_create_namespace_vm_script` from the top of the test file.

Remove the test `test_cluster_scripts_bind_explicit_kubeconfig` entirely (it tested the kubectl create namespace call).

Keep the remaining tests (`test_build_core_images_vm_script_*`, `test_helm_upgrade_install_vm_script_*`). Update `test_helm_upgrade_install_vm_script_uses_helm_ops_planner` to also assert `--create-namespace` is NOT present by default (to document the default behavior):

```python
def test_helm_upgrade_install_vm_script_uses_helm_ops_planner() -> None:
    script = helm_upgrade_install_vm_script(
        remote_dir="/srv/nanofaas",
        release="control-plane",
        chart="helm/nanofaas",
        namespace="nanofaas-e2e",
        values={"controlPlane.image.tag": "e2e"},
    )

    assert "cd /srv/nanofaas" in script
    assert "helm upgrade --install control-plane helm/nanofaas -n nanofaas-e2e" in script
    assert "--set controlPlane.image.tag=e2e" in script
    assert "--create-namespace" not in script


def test_helm_upgrade_install_vm_script_passes_create_namespace_flag() -> None:
    script = helm_upgrade_install_vm_script(
        remote_dir="/srv/nanofaas",
        release="control-plane",
        chart="helm/nanofaas",
        namespace="nanofaas-e2e",
        values={},
        create_namespace=True,
    )

    assert "--create-namespace" in script
```

- [ ] **Step 4: Run test suite**

```bash
cd tools/controlplane && uv run pytest tests/test_scenario_tasks.py -v
```

Expected: all PASS

- [ ] **Step 5: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/.worktrees/ansible-vm-provisioning
git add tools/controlplane/src/controlplane_tool/scenario_tasks.py \
    tools/controlplane/tests/test_scenario_tasks.py
git commit -m "chore: remove dead kubectl script functions from scenario_tasks"
```

