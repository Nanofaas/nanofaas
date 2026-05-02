# Remove kubectl Namespace Operations with a Helm Namespace Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove kubectl-based namespace creation, namespace deletion, and rollout-status waits from the controlplane scenario deployment and cleanup flows by introducing a dedicated Helm release that owns the Kubernetes namespace.

**Architecture:** Use a small namespace-only Helm chart installed as a separate release in a stable management namespace (`default`). Application releases (`control-plane`, `function-runtime`, and CLI-installed control-plane releases) are installed into the target application namespace, but they do not own that namespace. Cleanup removes application releases first, then uninstalls the namespace release last, which deletes the namespace without relying on `kubectl delete namespace`.

**Tech Stack:** Python 3.11, Helm 3, Kubernetes namespaces, uv/pytest, existing scenario component system.

---

## What changed after analysis

The first version of this plan proposed using both `helm upgrade --install --create-namespace` and `--set namespace.create=true` on the control-plane release. That is not the right design.

Important findings:

- Helm stores release state in the release namespace. For Helm 3 this is usually a Secret in the namespace passed with `-n` / `--namespace`.
- `--create-namespace` creates the release namespace if it is missing. It creates a simple namespace before release resources are applied. It does not make that namespace a chart-owned resource.
- `helm uninstall` removes resources associated with the last release manifest. If the namespace object is not part of that manifest, Helm will not delete it.
- If a release owns a namespace while its own release Secret lives inside that same namespace, deleting the namespace also deletes the release state. That can work accidentally in simple cases, but it is fragile, especially when multiple releases live in the same namespace.
- The repository already has `helm/nanofaas/templates/namespace.yaml`, controlled by `namespace.create`, and `helm/nanofaas/values.yaml` defaults `namespace.create: true`.
- The scenario component path correctly overrides `namespace.create=false` today in `control_plane_helm_values()`.
- `K3sCurlRunner._control_plane_helm_values()` also overrides `namespace.create=false`.
- `CliVmRunner._deploy_platform()` currently does not set `namespace.create=false`; it should be fixed while touching this area so the app chart does not try to create/use the chart default namespace.
- The old plan contradicted itself: Task 4 removed `kubectl_delete_namespace_vm_script`, while Task 5 said to keep it as the one legitimate kubectl operation. This revised plan removes it.
- The old plan missed `scenario_components/recipes.py`, `scenario_components/executor.py`, `test_scenario_recipes.py`, `test_cli_test_runner.py`, and TUI tests that mention wait or namespace-delete components.
- There are still read-only kubectl usages in verification code, for example resolving Service IPs or checking managed function readiness. This plan removes deployment/cleanup kubectl operations (`create namespace`, `delete namespace`, `rollout status`). Replacing every read-only kubectl probe is out of scope unless the user explicitly expands the task.

Relevant Helm references:

- Helm install docs: `--create-namespace` creates the release namespace if not present.
  `https://helm.sh/docs/v3/helm/helm_install/`
- Helm uninstall docs: uninstall removes resources associated with the release.
  `https://helm.sh/docs/v3/helm/helm_uninstall/`
- Helm 3 install source: `CreateNamespace` creates a standalone namespace object before release resources are created.
  `https://raw.githubusercontent.com/helm/helm/v3.20.0/pkg/action/install.go`

---

## Target lifecycle

Use three Helm releases for the VM-backed Helm stack:

```text
default namespace
  release: <target-namespace>-namespace
    owns: Namespace/<target-namespace>

<target-namespace>
  release: control-plane
    owns: control-plane Deployment, Service, RBAC, etc.

<target-namespace>
  release: function-runtime
    owns: function-runtime Deployment, Service, etc.
```

Install order:

```bash
helm upgrade --install nanofaas-e2e-namespace helm/nanofaas-namespace \
  -n default \
  --wait \
  --timeout 2m \
  --set namespace.name=nanofaas-e2e

helm upgrade --install control-plane helm/nanofaas \
  -n nanofaas-e2e \
  --wait \
  --timeout 5m \
  --set namespace.create=false

helm upgrade --install function-runtime helm/nanofaas-runtime \
  -n nanofaas-e2e \
  --wait \
  --timeout 3m
```

Cleanup order:

```bash
helm uninstall function-runtime -n nanofaas-e2e --wait --timeout 5m --ignore-not-found
helm uninstall control-plane -n nanofaas-e2e --wait --timeout 5m --ignore-not-found
helm uninstall nanofaas-e2e-namespace -n default --wait --timeout 5m --ignore-not-found
```

Why this is safe:

- The namespace release state lives in `default`, not inside the namespace it deletes.
- The runtime release is removed before the namespace is deleted, so its release Secret is not lost during namespace deletion.
- The control-plane release is removed before the namespace release, so its resources are gone before namespace deletion starts.
- `--wait` on the namespace release uninstall gives Kubernetes time to finish namespace deletion before later steps continue.

---

## Naming rules

Use a deterministic namespace release name:

```python
def namespace_release_name(namespace: str) -> str:
    return f"{namespace}-namespace"
```

Use a fixed management namespace:

```python
NAMESPACE_RELEASE_NAMESPACE = "default"
```

For the current scenario defaults this produces:

| Scenario | Target namespace | Namespace release | Release storage namespace |
|---|---|---|---|
| `k3s-junit-curl` | `nanofaas-e2e` | `nanofaas-e2e-namespace` | `default` |
| `helm-stack` | `nanofaas-e2e` | `nanofaas-e2e-namespace` | `default` |
| `cli-stack` | `nanofaas-cli-stack-e2e` | `nanofaas-cli-stack-e2e-namespace` | `default` |

If a future namespace can exceed Helm release-name limits, add a slug/hash helper before implementation. Current defaults are short enough.

---

## File map

### New files

| File | Change |
|---|---|
| `helm/nanofaas-namespace/Chart.yaml` | New namespace-only chart |
| `helm/nanofaas-namespace/values.yaml` | Requires `namespace.name` |
| `helm/nanofaas-namespace/templates/namespace.yaml` | Renders `kind: Namespace` |
| `tools/controlplane/src/controlplane_tool/scenario_components/namespace.py` | Namespace install/uninstall component planners |

### Modified files in `tools/controlplane/`

| File | Change |
|---|---|
| `src/controlplane_tool/scenario_components/composer.py` | Register namespace components; remove kubectl wait/delete components |
| `src/controlplane_tool/scenario_components/recipes.py` | Add namespace install/uninstall; remove wait/delete components |
| `src/controlplane_tool/scenario_components/executor.py` | Add summary overrides for namespace install/uninstall; remove deleted ids |
| `src/controlplane_tool/scenario_components/helm.py` | Remove rollout-status wait planners; remove app-release `--create-namespace` |
| `src/controlplane_tool/scenario_components/cleanup.py` | Remove `plan_delete_namespace`; add wait flags to app release uninstalls |
| `src/controlplane_tool/vm_cluster_workflows.py` | Add namespace install prelude script; remove wait scripts |
| `src/controlplane_tool/scenario_planner.py` | Replace namespace delete step with namespace release uninstall; remove wait steps |
| `src/controlplane_tool/scenario_tasks.py` | Add namespace install/uninstall VM scripts; remove kubectl namespace/wait scripts |
| `src/controlplane_tool/cli_vm_runner.py` | Install namespace release before app chart; remove kubectl create/wait calls; set `namespace.create=false` |
| `src/controlplane_tool/k3s_curl_runner.py` | Install namespace release before app charts; remove kubectl create/delete/wait calls |

### Modified tests

| File | Change |
|---|---|
| `tests/test_scenario_component_library.py` | Add namespace component assertions; update Helm deploy assertions |
| `tests/test_scenario_tasks.py` | Add namespace script tests; remove kubectl namespace/wait script tests |
| `tests/test_e2e_runner.py` | Update step ids and cleanup expectations |
| `tests/test_cli_test_runner.py` | Update cleanup command filter/expectations |
| `tests/test_scenario_recipes.py` | Update recipe component ids and ordering |
| `tests/test_tui_choices.py` | Update expected summaries/status rows |
| `tests/test_tui_workflow.py` | Update expected namespace cleanup step id/summary |

### Unchanged

`KubectlOps` in `~/shellcraft/` remains untouched. Read-only kubectl verification calls remain in scope only if a later task explicitly replaces them.

---

## Required GitNexus checks before code edits

Before editing each symbol, run upstream impact analysis as required by `AGENTS.md`.

At minimum:

```text
gitnexus_impact({target: "plan_deploy_control_plane", direction: "upstream"})
gitnexus_impact({target: "plan_deploy_function_runtime", direction: "upstream"})
gitnexus_impact({target: "plan_uninstall_control_plane", direction: "upstream"})
gitnexus_impact({target: "plan_uninstall_function_runtime", direction: "upstream"})
gitnexus_impact({target: "helm_upgrade_install_vm_script", direction: "upstream"})
gitnexus_impact({target: "helm_uninstall_vm_script", direction: "upstream"})
gitnexus_impact({target: "CliVmRunner._deploy_platform", direction: "upstream"})
gitnexus_impact({target: "K3sCurlRunner._deploy_platform", direction: "upstream"})
gitnexus_impact({target: "K3sCurlRunner.cleanup_platform", direction: "upstream"})
gitnexus_impact({target: "ScenarioPlanner.k3s_junit_curl_tail_steps", direction: "upstream"})
gitnexus_impact({target: "build_vm_cluster_prelude_plan", direction: "upstream"})
```

If any result is HIGH or CRITICAL, stop and report it before editing.

---

## Task 1: Add a namespace-only Helm chart

**Files:**

- Create: `helm/nanofaas-namespace/Chart.yaml`
- Create: `helm/nanofaas-namespace/values.yaml`
- Create: `helm/nanofaas-namespace/templates/namespace.yaml`

- [ ] **Step 1: Create chart metadata**

Create `helm/nanofaas-namespace/Chart.yaml`:

```yaml
apiVersion: v2
name: nanofaas-namespace
description: Namespace lifecycle chart for Nanofaas scenario environments
type: application
version: 0.1.0
appVersion: "0.1.0"
```

- [ ] **Step 2: Create chart values**

Create `helm/nanofaas-namespace/values.yaml`:

```yaml
namespace:
  name: ""
```

- [ ] **Step 3: Create the Namespace template**

Create `helm/nanofaas-namespace/templates/namespace.yaml`:

```yaml
{{- $namespace := required "namespace.name is required" .Values.namespace.name -}}
apiVersion: v1
kind: Namespace
metadata:
  name: {{ $namespace | quote }}
  labels:
    app.kubernetes.io/name: nanofaas
    app.kubernetes.io/component: namespace
    app.kubernetes.io/managed-by: {{ .Release.Service | quote }}
    app.kubernetes.io/instance: {{ .Release.Name | quote }}
```

- [ ] **Step 4: Verify template output**

Run:

```bash
helm template nanofaas-e2e-namespace helm/nanofaas-namespace \
  -n default \
  --set namespace.name=nanofaas-e2e
```

Expected: output contains:

```yaml
kind: Namespace
metadata:
  name: "nanofaas-e2e"
```

- [ ] **Step 5: Lint the chart**

Run:

```bash
helm lint helm/nanofaas-namespace --set namespace.name=nanofaas-e2e
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add helm/nanofaas-namespace
git commit -m "feat(helm): add namespace lifecycle chart"
```

---

## Task 2: Add namespace scenario components

**Files:**

- Create: `tools/controlplane/src/controlplane_tool/scenario_components/namespace.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario_components/composer.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario_components/recipes.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario_components/executor.py`
- Test: `tools/controlplane/tests/test_scenario_component_library.py`
- Test: `tools/controlplane/tests/test_scenario_recipes.py`

- [ ] **Step 1: Write tests for namespace component planners**

Add tests to `tests/test_scenario_component_library.py`:

```python
from controlplane_tool.scenario_components import namespace as namespace_components


def test_namespace_component_installs_namespace_release_in_default_namespace() -> None:
    context = _managed_context(namespace="nanofaas-stack")

    operations = namespace_components.plan_install_namespace(context)

    assert len(operations) == 1
    assert operations[0].operation_id == "namespace.install"
    assert operations[0].argv == (
        "helm",
        "upgrade",
        "--install",
        "nanofaas-stack-namespace",
        "helm/nanofaas-namespace",
        "-n",
        "default",
        "--wait",
        "--timeout",
        "2m",
        "--set",
        "namespace.name=nanofaas-stack",
    )


def test_namespace_component_uninstalls_namespace_release_last() -> None:
    context = _managed_context(namespace="nanofaas-stack")

    operations = namespace_components.plan_uninstall_namespace(context)

    assert len(operations) == 1
    assert operations[0].operation_id == "namespace.uninstall"
    assert operations[0].argv == (
        "helm",
        "uninstall",
        "nanofaas-stack-namespace",
        "-n",
        "default",
        "--wait",
        "--timeout",
        "5m",
        "--ignore-not-found",
    )
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
cd tools/controlplane && uv run pytest tests/test_scenario_component_library.py -k "namespace_component" -v
```

Expected: FAIL because `scenario_components.namespace` does not exist yet.

- [ ] **Step 3: Add `namespace.py` component module**

Create `src/controlplane_tool/scenario_components/namespace.py`:

```python
from __future__ import annotations

from collections.abc import Mapping
from types import MappingProxyType

from controlplane_tool.scenario_components.environment import ScenarioExecutionContext
from controlplane_tool.scenario_components.models import ScenarioComponentDefinition
from controlplane_tool.scenario_components.operations import RemoteCommandOperation, ScenarioOperation

NAMESPACE_RELEASE_NAMESPACE = "default"


def _frozen_env(env: Mapping[str, str] | None = None) -> Mapping[str, str]:
    return MappingProxyType(dict(env or {}))


def _namespace(context: ScenarioExecutionContext) -> str:
    if context.namespace:
        return context.namespace
    if context.resolved_scenario is not None and context.resolved_scenario.namespace:
        return context.resolved_scenario.namespace
    return "nanofaas-e2e"


def _kubeconfig_path(context: ScenarioExecutionContext) -> str:
    home = context.vm_request.home
    if home:
        return f"{home}/.kube/config"
    if context.vm_request.user == "root":
        return "/root/.kube/config"
    return f"/home/{context.vm_request.user}/.kube/config"


def namespace_release_name(namespace: str) -> str:
    return f"{namespace}-namespace"


def plan_install_namespace(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    namespace = _namespace(context)
    return (
        RemoteCommandOperation(
            operation_id="namespace.install",
            summary="Install namespace Helm release",
            argv=(
                "helm",
                "upgrade",
                "--install",
                namespace_release_name(namespace),
                "helm/nanofaas-namespace",
                "-n",
                NAMESPACE_RELEASE_NAMESPACE,
                "--wait",
                "--timeout",
                "2m",
                "--set",
                f"namespace.name={namespace}",
            ),
            env=_frozen_env({"KUBECONFIG": _kubeconfig_path(context)}),
            execution_target="vm",
        ),
    )


def plan_uninstall_namespace(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    namespace = _namespace(context)
    return (
        RemoteCommandOperation(
            operation_id="namespace.uninstall",
            summary="Uninstall namespace Helm release",
            argv=(
                "helm",
                "uninstall",
                namespace_release_name(namespace),
                "-n",
                NAMESPACE_RELEASE_NAMESPACE,
                "--wait",
                "--timeout",
                "5m",
                "--ignore-not-found",
            ),
            env=_frozen_env({"KUBECONFIG": _kubeconfig_path(context)}),
            execution_target="vm",
        ),
    )


NAMESPACE_INSTALL = ScenarioComponentDefinition(
    component_id="namespace.install",
    summary="Install namespace Helm release",
    planner=plan_install_namespace,
)

NAMESPACE_UNINSTALL = ScenarioComponentDefinition(
    component_id="namespace.uninstall",
    summary="Uninstall namespace Helm release",
    planner=plan_uninstall_namespace,
)
```

- [ ] **Step 4: Register namespace components in `composer.py`**

Import the two constants:

```python
from controlplane_tool.scenario_components.namespace import (
    NAMESPACE_INSTALL,
    NAMESPACE_UNINSTALL,
)
```

Register them before Helm deploys and before VM teardown:

```python
for comp in [
    VM_ENSURE_RUNNING, VM_PROVISION_BASE, REPO_SYNC_TO_VM,
    REGISTRY_ENSURE_CONTAINER, K3S_INSTALL, K3S_CONFIGURE_REGISTRY,
    LOADTEST_INSTALL_K6,
    NAMESPACE_INSTALL,
    HELM_DEPLOY_CONTROL_PLANE, HELM_DEPLOY_FUNCTION_RUNTIME,
    CLI_BUILD_INSTALL_DIST, CLI_PLATFORM_INSTALL, CLI_PLATFORM_STATUS,
    CLI_FN_APPLY_SELECTED, CLI_FN_LIST_SELECTED, CLI_FN_INVOKE_SELECTED,
    CLI_FN_ENQUEUE_SELECTED, CLI_FN_DELETE_SELECTED,
    BUILD_CORE, BUILD_SELECTED_FUNCTIONS,
    UNINSTALL_FUNCTION_RUNTIME, UNINSTALL_CONTROL_PLANE,
    NAMESPACE_UNINSTALL, VERIFY_CLI_PLATFORM_STATUS_FAILS, VM_DOWN,
]:
    _registry.register(comp)
```

Remove imports and registration entries for:

- `K8S_WAIT_CONTROL_PLANE_READY`
- `K8S_WAIT_FUNCTION_RUNTIME_READY`
- `DELETE_NAMESPACE`

- [ ] **Step 5: Update scenario recipes**

In `scenario_components/recipes.py`, change `k3s-junit-curl`:

```python
"k3s.configure_registry",
"namespace.install",
"helm.deploy_control_plane",
"helm.deploy_function_runtime",
"tests.run_k3s_curl_checks",
"tests.run_k8s_junit",
"cleanup.uninstall_function_runtime",
"cleanup.uninstall_control_plane",
"namespace.uninstall",
"vm.down",
```

Change `helm-stack`:

```python
"k3s.configure_registry",
"namespace.install",
"helm.deploy_control_plane",
"helm.deploy_function_runtime",
"loadtest.install_k6",
"loadtest.run",
"experiments.autoscaling",
```

Change `cli-stack`:

```python
"k3s.configure_registry",
"namespace.install",
"cli.build_install_dist",
"cli.platform_install",
...
"cleanup.uninstall_control_plane",
"namespace.uninstall",
"cleanup.verify_cli_platform_status_fails",
"vm.down",
```

Remove from all recipes:

- `k8s.wait_control_plane_ready`
- `k8s.wait_function_runtime_ready`
- `cleanup.delete_namespace`

- [ ] **Step 6: Update executor summary overrides**

In `scenario_components/executor.py`, replace deleted summary ids:

```python
"namespace.install": "Install namespace Helm release",
"namespace.uninstall": "Uninstall namespace Helm release",
```

Remove:

```python
"k8s.wait_control_plane_ready": "Wait for control-plane deployment",
"k8s.wait_function_runtime_ready": "Wait for function-runtime deployment",
"cleanup.delete_namespace": "Delete E2E namespace",
```

- [ ] **Step 7: Update recipe tests**

In `tests/test_scenario_recipes.py`:

- Replace expectations for `cleanup.delete_namespace` with `namespace.uninstall`.
- Remove expectations/order assertions for `k8s.wait_*`.
- Assert `namespace.install` appears before app install/deploy components.
- Assert `namespace.uninstall` appears after app uninstall components.

Example:

```python
assert "namespace.install" in recipe.component_ids
assert "namespace.uninstall" in recipe.component_ids
assert "cleanup.delete_namespace" not in recipe.component_ids
assert "k8s.wait_control_plane_ready" not in recipe.component_ids
assert "k8s.wait_function_runtime_ready" not in recipe.component_ids
```

- [ ] **Step 8: Run component and recipe tests**

Run:

```bash
cd tools/controlplane && uv run pytest \
  tests/test_scenario_component_library.py \
  tests/test_scenario_recipes.py \
  -v
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/scenario_components/namespace.py \
  tools/controlplane/src/controlplane_tool/scenario_components/composer.py \
  tools/controlplane/src/controlplane_tool/scenario_components/recipes.py \
  tools/controlplane/src/controlplane_tool/scenario_components/executor.py \
  tools/controlplane/tests/test_scenario_component_library.py \
  tools/controlplane/tests/test_scenario_recipes.py
git commit -m "feat(scenario): manage namespaces with a dedicated Helm release"
```

---

## Task 3: Remove kubectl rollout waits from Helm component path

**Files:**

- Modify: `tools/controlplane/src/controlplane_tool/scenario_components/helm.py`
- Modify: `tools/controlplane/src/controlplane_tool/vm_cluster_workflows.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario_planner.py`
- Test: `tools/controlplane/tests/test_scenario_component_library.py`
- Test: `tools/controlplane/tests/test_e2e_runner.py`

`plan_deploy_control_plane()` and `plan_deploy_function_runtime()` already use Helm `--wait`. The separate `kubectl rollout status` operations are redundant and should be removed.

- [ ] **Step 1: Update Helm component tests**

In `tests/test_scenario_component_library.py`, update `test_helm_component_planners_use_namespace_and_helm_values`:

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
    assert "--wait" in control_plane_operations[0].argv
    assert "--wait" in runtime_operations[0].argv
    assert "--create-namespace" not in control_plane_operations[0].argv
    assert "--create-namespace" not in runtime_operations[0].argv
    assert "--set" in control_plane_operations[0].argv
    assert "namespace.create=false" in control_plane_operations[0].argv
    assert "helm" in control_plane_operations[0].argv[0]
    assert "nanofaas-stack" in " ".join(control_plane_operations[0].argv)
    assert "nanofaas-stack" in " ".join(runtime_operations[0].argv)
```

- [ ] **Step 2: Run test to verify current failure**

Run:

```bash
cd tools/controlplane && uv run pytest tests/test_scenario_component_library.py::test_helm_component_planners_use_namespace_and_helm_values -v
```

Expected: FAIL because the current operations include `--create-namespace` and wait planner references still exist.

- [ ] **Step 3: Update `scenario_components/helm.py`**

Remove these functions and constants:

- `plan_wait_control_plane_ready`
- `plan_wait_function_runtime_ready`
- `K8S_WAIT_CONTROL_PLANE_READY`
- `K8S_WAIT_FUNCTION_RUNTIME_READY`

In `plan_deploy_control_plane()`, remove:

```python
"--create-namespace",
```

In `plan_deploy_function_runtime()`, remove:

```python
"--create-namespace",
```

Keep:

```python
"--wait",
"--timeout",
```

Keep `control_plane_helm_values()` setting:

```python
"namespace.create": "false",
"namespace.name": namespace,
```

- [ ] **Step 4: Update VM cluster prelude dataclass**

In `vm_cluster_workflows.py`, change `VmClusterPreludePlan`.

Remove fields:

```python
wait_control_plane_script: str
wait_function_runtime_script: str
```

Add:

```python
install_namespace_script: str
```

Import namespace components:

```python
from controlplane_tool.scenario_components import namespace as namespace_components
```

Build a namespace plan:

```python
namespace_plan = {
    operation.operation_id: operation
    for operation in namespace_components.plan_install_namespace(scenario_context)
}
```

Remove from `helm_plan`:

```python
*helm_components.plan_wait_control_plane_ready(scenario_context),
*helm_components.plan_wait_function_runtime_ready(scenario_context),
```

Set the new field:

```python
install_namespace_script=_render_operations(
    (namespace_plan["namespace.install"],),
    remote_dir=remote_dir,
),
```

- [ ] **Step 5: Update `ScenarioPlanner.k3s_vm_prelude_steps`**

In `scenario_planner.py`, insert the namespace install step after k3s registry configuration and before Helm deploys:

```python
self._remote_exec_step(
    "Install namespace Helm release",
    vm_request,
    prelude.install_namespace_script,
    step_id="namespace.install",
),
```

Remove wait steps:

```python
self._remote_exec_step(
    "Wait for control-plane deployment",
    ...
    step_id="k8s.wait_control_plane_ready",
),
self._remote_exec_step(
    "Wait for function-runtime deployment",
    ...
    step_id="k8s.wait_function_runtime_ready",
),
```

- [ ] **Step 6: Update E2E step-id tests**

In `tests/test_e2e_runner.py`, update expected prelude/tail ids:

- Add `namespace.install` before `helm.deploy_control_plane`.
- Remove `k8s.wait_control_plane_ready`.
- Remove `k8s.wait_function_runtime_ready`.

- [ ] **Step 7: Run tests**

Run:

```bash
cd tools/controlplane && uv run pytest \
  tests/test_scenario_component_library.py \
  tests/test_e2e_runner.py \
  -v
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/scenario_components/helm.py \
  tools/controlplane/src/controlplane_tool/vm_cluster_workflows.py \
  tools/controlplane/src/controlplane_tool/scenario_planner.py \
  tools/controlplane/tests/test_scenario_component_library.py \
  tools/controlplane/tests/test_e2e_runner.py
git commit -m "refactor(scenario): replace rollout waits with Helm wait"
```

---

## Task 4: Replace kubectl namespace scripts in legacy runners

**Files:**

- Modify: `tools/controlplane/src/controlplane_tool/scenario_tasks.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_vm_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/k3s_curl_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario_planner.py`
- Test: `tools/controlplane/tests/test_scenario_tasks.py`
- Test: `tools/controlplane/tests/test_cli_runtime.py`
- Test: `tools/controlplane/tests/test_k3s_runtime.py`
- Test: `tools/controlplane/tests/test_e2e_runner.py`

- [ ] **Step 1: Write namespace script tests**

In `tests/test_scenario_tasks.py`, add:

```python
def test_helm_namespace_install_vm_script_installs_release_in_default_namespace() -> None:
    script = helm_namespace_install_vm_script(
        remote_dir="/srv/nanofaas",
        namespace="nanofaas-e2e",
        kubeconfig_path="/home/ubuntu/.kube/config",
    )

    assert "cd /srv/nanofaas" in script
    assert "KUBECONFIG=/home/ubuntu/.kube/config" in script
    assert "helm upgrade --install nanofaas-e2e-namespace helm/nanofaas-namespace -n default" in script
    assert "--set namespace.name=nanofaas-e2e" in script
    assert "--wait" in script


def test_helm_namespace_uninstall_vm_script_uninstalls_release_from_default_namespace() -> None:
    script = helm_namespace_uninstall_vm_script(
        remote_dir="/srv/nanofaas",
        namespace="nanofaas-e2e",
        kubeconfig_path="/home/ubuntu/.kube/config",
    )

    assert "cd /srv/nanofaas" in script
    assert "KUBECONFIG=/home/ubuntu/.kube/config" in script
    assert "helm uninstall nanofaas-e2e-namespace -n default" in script
    assert "--ignore-not-found" in script
    assert "--wait" in script
```

Remove tests for:

- `kubectl_create_namespace_vm_script`
- `kubectl_delete_namespace_vm_script`
- `kubectl_rollout_status_vm_script`

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
cd tools/controlplane && uv run pytest tests/test_scenario_tasks.py -v
```

Expected: FAIL because the namespace Helm scripts do not exist yet.

- [ ] **Step 3: Add namespace script helpers to `scenario_tasks.py`**

Add:

```python
def _namespace_release_name(namespace: str) -> str:
    return f"{namespace}-namespace"


def helm_namespace_install_vm_script(
    *,
    remote_dir: str,
    namespace: str,
    kubeconfig_path: str | None = None,
    timeout: str = "2m",
) -> str:
    command = [
        "helm",
        "upgrade",
        "--install",
        _namespace_release_name(namespace),
        "helm/nanofaas-namespace",
        "-n",
        "default",
        "--wait",
        "--timeout",
        timeout,
        "--set",
        f"namespace.name={namespace}",
    ]
    return _render_remote_script(
        remote_dir=remote_dir,
        commands=[_with_kubeconfig(command, kubeconfig_path=kubeconfig_path)],
    )


def helm_namespace_uninstall_vm_script(
    *,
    remote_dir: str,
    namespace: str,
    kubeconfig_path: str | None = None,
    timeout: str = "5m",
) -> str:
    command = [
        "helm",
        "uninstall",
        _namespace_release_name(namespace),
        "-n",
        "default",
        "--wait",
        "--timeout",
        timeout,
        "--ignore-not-found",
    ]
    return _render_remote_script(
        remote_dir=remote_dir,
        commands=[_with_kubeconfig(command, kubeconfig_path=kubeconfig_path)],
    )
```

Optionally update `helm_uninstall_vm_script()` to support wait flags:

```python
def helm_uninstall_vm_script(
    *,
    remote_dir: str,
    release: str,
    namespace: str,
    kubeconfig_path: str | None = None,
    wait: bool = True,
    timeout: str = "5m",
    ignore_not_found: bool = True,
) -> str:
    command = ["helm", "uninstall", release, "-n", namespace]
    if wait:
        command.append("--wait")
    if timeout:
        command.extend(["--timeout", timeout])
    if ignore_not_found:
        command.append("--ignore-not-found")
    return _render_remote_script(
        remote_dir=remote_dir,
        commands=[_with_kubeconfig(command, kubeconfig_path=kubeconfig_path)],
    )
```

- [ ] **Step 4: Remove kubectl script helpers**

Delete from `scenario_tasks.py`:

- `kubectl_create_namespace_vm_script`
- `kubectl_delete_namespace_vm_script`
- `kubectl_rollout_status_vm_script`

- [ ] **Step 5: Update `CliVmRunner._deploy_platform`**

Remove imports:

```python
kubectl_create_namespace_vm_script,
kubectl_rollout_status_vm_script,
```

Add import:

```python
helm_namespace_install_vm_script,
```

Replace the namespace create call with:

```python
self._vm_exec(
    helm_namespace_install_vm_script(
        remote_dir=self._remote_dir,
        namespace=self.namespace,
        kubeconfig_path=self._kubeconfig_path,
    )
)
```

Update the control-plane Helm values to prevent the application chart from owning the namespace:

```python
values={
    "namespace.create": "false",
    "namespace.name": self.namespace,
    "controlPlane.image.repository": self._control_image.split(":")[0],
    "controlPlane.image.tag": self._control_image.split(":")[-1],
}
```

Remove the `kubectl_rollout_status_vm_script` call. Helm `--wait` in `helm_upgrade_install_vm_script()` covers application readiness.

- [ ] **Step 6: Update `K3sCurlRunner._deploy_platform`**

Remove imports:

```python
kubectl_create_namespace_vm_script,
kubectl_delete_namespace_vm_script,
kubectl_rollout_status_vm_script,
```

Add imports:

```python
helm_namespace_install_vm_script,
helm_namespace_uninstall_vm_script,
```

Replace the namespace create call with:

```python
self._vm_exec(
    helm_namespace_install_vm_script(
        remote_dir=self._remote_dir,
        namespace=self.namespace,
        kubeconfig_path=self._kubeconfig_path,
    )
)
```

Remove `_wait_for_deployment()` and remove these calls from `run()`:

```python
self._wait_for_deployment("nanofaas-control-plane", 180)
self._wait_for_deployment("function-runtime", 120)
```

Keep read-only verification helpers such as `_control_plane_service_ip()` and `_await_managed_function_ready()` for now.

- [ ] **Step 7: Update `K3sCurlRunner.cleanup_platform`**

After uninstalling function-runtime and control-plane, replace kubectl namespace deletion with namespace release uninstall:

```python
try:
    self._vm_exec(
        helm_namespace_uninstall_vm_script(
            remote_dir=self._remote_dir,
            namespace=self.namespace,
            kubeconfig_path=self._kubeconfig_path,
        )
    )
except RuntimeError:
    pass
```

The order must stay:

1. `helm uninstall function-runtime -n <target>`
2. `helm uninstall control-plane -n <target>`
3. `helm uninstall <target>-namespace -n default`

- [ ] **Step 8: Update `ScenarioPlanner.k3s_junit_curl_tail_steps`**

Remove import:

```python
kubectl_delete_namespace_vm_script,
```

Add import:

```python
helm_namespace_uninstall_vm_script,
```

Replace `delete_namespace_step` with `uninstall_namespace_step`:

```python
uninstall_namespace_step = self._remote_exec_step(
    "Uninstall namespace Helm release",
    vm_request,
    helm_namespace_uninstall_vm_script(
        remote_dir=remote_dir,
        namespace=namespace,
        kubeconfig_path=kubeconfig_path,
    ),
    step_id="namespace.uninstall",
)
```

For `cleanup_vm=False`, use:

```python
uninstall_namespace_step = self._step(
    "Uninstall namespace Helm release",
    ["echo", "Skipping namespace cleanup (--no-cleanup-vm)"],
    step_id="namespace.uninstall",
)
```

Return `uninstall_namespace_step` after `uninstall_control_plane_step`.

- [ ] **Step 9: Update tests for legacy runners and tail steps**

Update `tests/test_e2e_runner.py` expected tail ids:

```python
assert [step.step_id for step in steps] == [
    "tests.run_k3s_curl_checks",
    "tests.run_k8s_junit",
    "cleanup.uninstall_function_runtime",
    "cleanup.uninstall_control_plane",
    "namespace.uninstall",
    "vm.down",
]
```

Update any command filters that look for `"kubectl delete namespace"` to look for namespace Helm uninstall:

```python
for token in (
    "platform install",
    "platform status",
    "helm uninstall",
):
```

Keep the assertion that rendered cleanup commands include the scenario namespace. The namespace uninstall command includes it through the release name, for example `nanofaas-cli-stack-e2e-namespace`.

- [ ] **Step 10: Run targeted tests**

Run:

```bash
cd tools/controlplane && uv run pytest \
  tests/test_scenario_tasks.py \
  tests/test_cli_runtime.py \
  tests/test_k3s_runtime.py \
  tests/test_e2e_runner.py \
  -v
```

Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/scenario_tasks.py \
  tools/controlplane/src/controlplane_tool/cli_vm_runner.py \
  tools/controlplane/src/controlplane_tool/k3s_curl_runner.py \
  tools/controlplane/src/controlplane_tool/scenario_planner.py \
  tools/controlplane/tests/test_scenario_tasks.py \
  tools/controlplane/tests/test_cli_runtime.py \
  tools/controlplane/tests/test_k3s_runtime.py \
  tools/controlplane/tests/test_e2e_runner.py
git commit -m "refactor(runners): replace namespace kubectl calls with Helm release lifecycle"
```

---

## Task 5: Update cleanup components for Helm-only namespace destruction

**Files:**

- Modify: `tools/controlplane/src/controlplane_tool/scenario_components/cleanup.py`
- Test: `tools/controlplane/tests/test_scenario_component_library.py`
- Test: `tools/controlplane/tests/test_e2e_runner.py`

- [ ] **Step 1: Update cleanup tests**

In `tests/test_scenario_component_library.py`, update control-plane uninstall expectations:

```python
def test_cli_stack_cleanup_uses_cli_release_name() -> None:
    context = _managed_context(scenario="cli-stack")

    operations = cleanup.plan_uninstall_control_plane(context)

    assert operations[0].argv[:3] == ("helm", "uninstall", "nanofaas-cli-stack-e2e")
    assert "-n" in operations[0].argv
    assert "nanofaas-cli-stack-e2e" in operations[0].argv
    assert "--wait" in operations[0].argv
    assert "--ignore-not-found" in operations[0].argv
```

Add function-runtime uninstall wait assertions if missing:

```python
def test_cleanup_uninstalls_function_runtime_with_wait() -> None:
    context = _managed_context(namespace="nanofaas-stack")

    operations = cleanup.plan_uninstall_function_runtime(context)

    assert operations[0].argv == (
        "helm",
        "uninstall",
        "function-runtime",
        "-n",
        "nanofaas-stack",
        "--wait",
        "--timeout",
        "5m",
        "--ignore-not-found",
    )
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
cd tools/controlplane && uv run pytest tests/test_scenario_component_library.py -k "cleanup" -v
```

Expected: FAIL because uninstall commands currently lack wait flags and `DELETE_NAMESPACE` still exists.

- [ ] **Step 3: Update cleanup planners**

In `scenario_components/cleanup.py`, remove:

- `plan_delete_namespace`
- `DELETE_NAMESPACE`

Update `plan_uninstall_control_plane()`:

```python
argv=(
    "helm",
    "uninstall",
    _control_plane_release(context),
    "-n",
    namespace,
    "--wait",
    "--timeout",
    "5m",
    "--ignore-not-found",
),
```

Update `plan_uninstall_function_runtime()`:

```python
argv=(
    "helm",
    "uninstall",
    "function-runtime",
    "-n",
    namespace,
    "--wait",
    "--timeout",
    "5m",
    "--ignore-not-found",
),
```

- [ ] **Step 4: Run tests**

Run:

```bash
cd tools/controlplane && uv run pytest \
  tests/test_scenario_component_library.py \
  tests/test_e2e_runner.py \
  -v
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/scenario_components/cleanup.py \
  tools/controlplane/tests/test_scenario_component_library.py \
  tools/controlplane/tests/test_e2e_runner.py
git commit -m "refactor(cleanup): uninstall namespace through Helm release"
```

---

## Task 6: Update CLI-stack, TUI, and workflow expectations

**Files:**

- Modify: `tools/controlplane/tests/test_cli_test_runner.py`
- Modify: `tools/controlplane/tests/test_tui_choices.py`
- Modify: `tools/controlplane/tests/test_tui_workflow.py`
- Modify: any other tests found by `rg`

- [ ] **Step 1: Find stale test expectations**

Run:

```bash
rg -n "cleanup\\.delete_namespace|Delete E2E namespace|Ensure E2E namespace exists|k8s\\.wait_|Wait for control-plane deployment|Wait for function-runtime deployment|kubectl delete namespace|kubectl rollout status|kubectl_create_namespace_vm_script|kubectl_delete_namespace_vm_script|kubectl_rollout_status_vm_script" tools/controlplane/tests tools/controlplane/src
```

Expected before updates: matches in tests and old source.

- [ ] **Step 2: Update CLI test runner expectations**

In `tests/test_cli_test_runner.py`, remove `"kubectl delete namespace"` from command filters. The rendered commands should still include the scenario namespace because the namespace release name contains it:

```python
for token in (
    "platform install",
    "platform status",
    "helm uninstall",
):
```

- [ ] **Step 3: Update TUI expectations**

In `tests/test_tui_choices.py` and `tests/test_tui_workflow.py`, replace:

```text
Ensure E2E namespace exists
Delete E2E namespace
Wait for control-plane deployment
Wait for function-runtime deployment
cleanup.delete_namespace
```

With:

```text
Install namespace Helm release
Uninstall namespace Helm release
namespace.install
namespace.uninstall
```

Do not add wait-step replacements; Helm deploy steps now include `--wait`.

- [ ] **Step 4: Run updated tests**

Run:

```bash
cd tools/controlplane && uv run pytest \
  tests/test_cli_test_runner.py \
  tests/test_tui_choices.py \
  tests/test_tui_workflow.py \
  -v
```

Expected: PASS.

- [ ] **Step 5: Verify stale expectations are gone**

Run:

```bash
rg -n "cleanup\\.delete_namespace|Delete E2E namespace|Ensure E2E namespace exists|k8s\\.wait_|Wait for control-plane deployment|Wait for function-runtime deployment|kubectl delete namespace|kubectl rollout status|kubectl_create_namespace_vm_script|kubectl_delete_namespace_vm_script|kubectl_rollout_status_vm_script" tools/controlplane/tests tools/controlplane/src
```

Expected: no output, except read-only kubectl helpers if the search expression is expanded too broadly.

- [ ] **Step 6: Commit**

```bash
git add \
  tools/controlplane/tests/test_cli_test_runner.py \
  tools/controlplane/tests/test_tui_choices.py \
  tools/controlplane/tests/test_tui_workflow.py
git commit -m "test: update scenario expectations for Helm namespace lifecycle"
```

---

## Task 7: Verify no deployment/cleanup kubectl operations remain

**Files:**

- No source changes expected unless this task finds stale references.

- [ ] **Step 1: Search for removed kubectl deployment/cleanup operations**

Run:

```bash
rg -n "kubectl create namespace|kubectl delete namespace|kubectl rollout status|kubectl_create_namespace_vm_script|kubectl_delete_namespace_vm_script|kubectl_rollout_status_vm_script|k8s\\.wait_control_plane_ready|k8s\\.wait_function_runtime_ready|cleanup\\.delete_namespace" tools/controlplane/src tools/controlplane/tests
```

Expected: no output.

- [ ] **Step 2: Search remaining kubectl usage and classify it**

Run:

```bash
rg -n '"kubectl"|kubectl ' tools/controlplane/src
```

Expected: remaining matches are read-only verification/status calls, for example `kubectl get svc`, `kubectl get deployment`, or unrelated preflight checks. Do not remove them in this plan unless the user expands the task.

- [ ] **Step 3: Verify Helm chart rendering**

Run:

```bash
helm template nanofaas-e2e-namespace helm/nanofaas-namespace \
  -n default \
  --set namespace.name=nanofaas-e2e
```

Expected: Namespace manifest only.

Run:

```bash
helm template control-plane helm/nanofaas \
  -n nanofaas-e2e \
  --set namespace.create=false \
  --set namespace.name=nanofaas-e2e \
  --set demos.enabled=false \
  --set prometheus.create=false
```

Expected: no `kind: Namespace` in output.

- [ ] **Step 4: Run full Python test suite**

Run:

```bash
cd tools/controlplane && uv run pytest tests/ -q --tb=short
```

Expected: PASS.

- [ ] **Step 5: Run GitNexus detect changes**

Run:

```text
gitnexus_detect_changes({scope: "all"})
```

Expected: affected flows are limited to scenario planning, scenario cleanup, Helm deployment, and tests.

- [ ] **Step 6: Commit if fixes were needed**

Only if this task required changes:

```bash
git add <changed-files>
git commit -m "chore: remove stale kubectl namespace references"
```

---

## Final implementation checklist

Before claiming this implementation is complete:

- [ ] Namespace chart exists and renders only `kind: Namespace`.
- [ ] Namespace release is installed in `default`.
- [ ] Application releases are installed in the target namespace.
- [ ] Control-plane chart is deployed with `namespace.create=false` in scenario paths.
- [ ] Runtime release is uninstalled before control-plane release.
- [ ] Control-plane release is uninstalled before namespace release.
- [ ] Namespace release is uninstalled with `--wait`.
- [ ] No `kubectl create namespace`, `kubectl delete namespace`, or `kubectl rollout status` remains in `tools/controlplane/src`.
- [ ] Remaining kubectl usages, if any, are read-only verification/status operations and are explicitly out of scope.
- [ ] Recipe component ids no longer reference deleted `k8s.wait_*` or `cleanup.delete_namespace` components.
- [ ] TUI and CLI-stack tests use `namespace.install` / `namespace.uninstall`.
- [ ] `helm lint helm/nanofaas-namespace --set namespace.name=nanofaas-e2e` passes.
- [ ] `cd tools/controlplane && uv run pytest tests/ -q --tb=short` passes.
- [ ] `gitnexus_detect_changes({scope: "all"})` reports expected impact only.

