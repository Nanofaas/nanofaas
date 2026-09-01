# NanoLab Persistent Recovery Scenario Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add opt-in NanoLab container and Kubernetes validation scenarios proving that a control-plane-only restart restores a managed function and its replica target without recreating its running backend resources.

**Architecture:** Keep `workflow: validate` and add a `persistentRecovery` flag. Reuse the existing platform, Compose, Helm, function-resource, and HTTP paths; extend the validate workflow only when the flag is enabled. A normal resource release still performs final cleanup.

**Tech Stack:** Python 3.12, Pydantic, Sonata Engine/Tasks, Docker Compose, kubectl, Helm, pytest.

---

## Invariants

- Work in `/Users/micheleciavotta/Downloads/nanolab`; NanoFaaS owns documentation only.
- Add only `persistent-recovery-container.yaml` and `persistent-recovery-k8s.yaml`.
- Use `workflow: validate`, `functions: [word-stats-java]`, and `persistentRecovery: true` in both scenarios.
- Reuse `PlatformRequest`, `function_resource`, `build_validate_workflow`, and the existing Compose/Helm/image setup.
- Set the desired replica target to `2`, then poll `GET /v1/functions/{name}/replicas` until both `desiredReplicas` and `readyReplicas` equal `2`.
- Use `GET /v1/functions/{name}` only to verify that the restored registration still reports the expected managed backend.
- Container recovery compares the two labelled function-container IDs before and after restart. Kubernetes recovery compares Deployment and Service UIDs.
- Do not add a workflow name, CLI dispatch branch, generic restart framework, HPA coverage, or a post-delete assertion outside `function_resource` release ordering.

### Task 1: Add the opt-in validate flag

**Files:**

- Modify: `packages/nanolab/src/nanolab/config/scenario.py`
- Modify: `packages/nanolab/tests/config/test_scenario.py`

**Step 1: Write failing schema tests**

Add tests proving that `persistentRecovery: true` parses for `validate` with `container` and `k8s`, defaults to false, and is rejected for every non-`validate` workflow. Keep the existing backend-required validation unchanged.

**Step 2: Run RED**

```bash
uv run --locked --all-packages --all-groups pytest -c packages/nanolab/pyproject.toml packages/nanolab/tests/config/test_scenario.py
```

Expected: the new field is rejected as extra input.

**Step 3: Implement the minimum schema change**

Add `persistent_recovery: bool = Field(default=False, alias="persistentRecovery")` to `ScenarioConfig` and one validator branch requiring `workflow == "validate"` when enabled. Do not modify `WorkflowName` or `nanolab.cli.product`.

**Step 4: Run GREEN and commit**

```bash
uv run --locked --all-packages --all-groups pytest -c packages/nanolab/pyproject.toml packages/nanolab/tests/config/test_scenario.py
git add packages/nanolab/src/nanolab/config/scenario.py packages/nanolab/tests/config/test_scenario.py
git commit -m "Add persistent recovery validation flag"
```

### Task 2: Add recovery HTTP operations to the existing function tasks

**Files:**

- Modify: `packages/sonata-tasks/src/sonata_tasks/http_function.py`
- Modify: `packages/sonata-tasks/tests/test_function_tasks.py`

**Step 1: Write failing task tests**

Using the recording executor, specify three tasks:

- `HttpFunctionSetReplicasTask` sends `PUT /v1/functions/{name}/replicas` with `{"replicas":2}`.
- `HttpFunctionReplicaStatusTask` polls `GET /v1/functions/{name}/replicas` until `desiredReplicas == 2` and `readyReplicas == 2`, and fails with the last response after its bounded timeout.
- `HttpFunctionBackendTask` sends `GET /v1/functions/{name}` and verifies only the expected `deploymentBackend`.

Also test malformed JSON and a timeout where desired reaches two but ready does not. Reuse the module's existing `monotonic`/`sleep` injection pattern rather than adding a dependency.

**Step 2: Run RED**

```bash
uv run --locked --all-packages --all-groups pytest -c packages/nanolab/pyproject.toml packages/sonata-tasks/tests/test_function_tasks.py
```

Expected: the three task classes do not exist.

**Step 3: Implement the three focused tasks**

Keep the classes in `http_function.py`, next to registration/deletion. Use the existing endpoint/resource resolution, executor, role, curl, and JSON verification conventions. Do not infer replica state from `GET /v1/functions/{name}`.

**Step 4: Run GREEN and commit**

```bash
uv run --locked --all-packages --all-groups pytest -c packages/nanolab/pyproject.toml packages/sonata-tasks/tests/test_function_tasks.py
git add packages/sonata-tasks/src/sonata_tasks/http_function.py packages/sonata-tasks/tests/test_function_tasks.py
git commit -m "Add HTTP replica validation tasks"
```

### Task 3: Add the container recovery validation path

**Files:**

- Modify: `packages/sonata-tasks/src/sonata_tasks/validate.py`
- Create: `packages/sonata-tasks/src/sonata_tasks/validate_recovery.py`
- Create: `packages/sonata-tasks/tests/test_validate_recovery.py`
- Modify: `packages/nanolab/src/nanolab/plans/validate.py`
- Modify: `packages/nanolab/tests/plans/test_validate.py`
- Create: `packages/nanolab/scenarios-v2/persistent-recovery-container.yaml`

**Step 1: Write failing cleanup and workflow-order tests**

Use recording executors to require this container sequence:

1. Before acquiring the recovery Compose project, remove any container returned by `docker ps -aq --filter label=io.nanofaas.managed=true --filter label=io.nanofaas.function=word-stats-java`. The existing Compose acquire already runs `down --volumes --remove-orphans`; this extra step clears only standalone function containers left by an interrupted prior run.
2. Acquire the existing registry and Compose resources, using project name `nanofaas-recovery`.
3. Build, push, and register `word-stats-java` through the existing platform path.
4. Set replicas to two and poll the replica endpoint until desired and ready are two.
5. Capture exactly two sorted running IDs using `docker ps -q` with the same two labels.
6. Run `docker compose -f deploy/compose/compose.yaml -p nanofaas-recovery restart control-plane` and the existing Compose readiness probe.
7. Verify `GET /v1/functions/word-stats-java` reports `deploymentBackend: container-local`, poll the replica endpoint again, compare the same two IDs, and invoke the function.
8. Let the ordinary function, Compose, and registry resource releases clean up in reverse order.

Test zero, one, and three captured IDs as failures. Do not use a shell pipeline or add a post-delete container assertion: deletion occurs in resource release, so no later workflow task can depend on it.

**Step 2: Run RED**

```bash
uv run --locked --all-packages --all-groups pytest -c packages/nanolab/pyproject.toml packages/sonata-tasks/tests/test_validate_recovery.py packages/nanolab/tests/plans/test_validate.py
```

Expected: the recovery request/path and scenario do not exist.

**Step 3: Implement the focused extension**

Add only the recovery data needed by `ValidateWorkflowRequest` and delegate the optional recovery steps to `validate_recovery.py`; keep the ordinary path unchanged. Model orphan cleanup as a recovery-only resource whose acquire removes the labelled containers and whose release is a no-op, then make the Compose resource require it so cleanup precedes Compose acquisition. In `build_validate_plan`, select the `nanofaas-recovery` Compose project and attach the cleanup/recovery request only when `config.persistent_recovery` is true.

The scenario is:

```yaml
workflow: validate
backend: container
build: docker
functions:
  - word-stats-java
persistentRecovery: true
```

The cleanup task must pass IDs as argv entries to `docker rm -f`; an empty list succeeds without invoking `docker rm`.

**Step 4: Verify and commit**

```bash
uv run --locked --all-packages --all-groups pytest -c packages/nanolab/pyproject.toml packages/sonata-tasks/tests/test_validate_recovery.py packages/nanolab/tests/plans/test_validate.py
NANOFAAS_ROOT=/Users/micheleciavotta/Downloads/nanofaas ./nanolab.sh plan packages/nanolab/scenarios-v2/persistent-recovery-container.yaml
git add packages/sonata-tasks/src/sonata_tasks/validate.py packages/sonata-tasks/src/sonata_tasks/validate_recovery.py packages/sonata-tasks/tests/test_validate_recovery.py packages/nanolab/src/nanolab/plans/validate.py packages/nanolab/tests/plans/test_validate.py packages/nanolab/scenarios-v2/persistent-recovery-container.yaml
git commit -m "Add container persistent recovery scenario"
```

### Task 4: Add Kubernetes recovery to the same path

**Files:**

- Modify: `packages/sonata-tasks/src/sonata_tasks/validate_recovery.py`
- Modify: `packages/sonata-tasks/tests/test_validate_recovery.py`
- Modify: `packages/nanolab/tests/plans/test_validate.py`
- Create: `packages/nanolab/scenarios-v2/persistent-recovery-k8s.yaml`

**Step 1: Write the failing Kubernetes workflow test**

Require this sequence after the existing Helm platform and function registration:

1. Set replicas to two and poll until desired and ready equal two.
2. Capture the UIDs of Deployment `fn-word-stats-java` and its Service, plus the current control-plane pod name and UID.
3. Select the control-plane pod with `app=nanofaas-control-plane`; do not use `app.kubernetes.io/component=control-plane`, which is absent from the pod template.
4. Delete the exact captured pod name with `kubectl delete pod ... --wait=true`.
5. Poll until a Ready pod selected by `app=nanofaas-control-plane` has a UID different from the captured UID.
6. Verify `GET /v1/functions/word-stats-java` reports `deploymentBackend: k8s`, poll the replica endpoint, verify the Deployment and Service UIDs are unchanged, and invoke the function.

Cover missing/multiple initial control-plane pods, replacement with the old UID, non-Ready replacement, and changed function-resource UIDs.

**Step 2: Run RED, implement, and run GREEN**

Reuse the existing Helm values, namespace, endpoint resource, executor role, and persistence default. Keep the Helm release and PVC acquired across the pod restart; normal scenario teardown removes them afterward.

```bash
uv run --locked --all-packages --all-groups pytest -c packages/nanolab/pyproject.toml packages/sonata-tasks/tests/test_validate_recovery.py packages/nanolab/tests/plans/test_validate.py
NANOFAAS_ROOT=/Users/micheleciavotta/Downloads/nanofaas ./nanolab.sh plan packages/nanolab/scenarios-v2/persistent-recovery-k8s.yaml --environment packages/nanolab/environments/multipass.yaml
```

The scenario is:

```yaml
workflow: validate
backend: k8s
build: docker
functions:
  - word-stats-java
persistentRecovery: true
```

**Step 3: Commit**

```bash
git add packages/sonata-tasks/src/sonata_tasks/validate_recovery.py packages/sonata-tasks/tests/test_validate_recovery.py packages/nanolab/tests/plans/test_validate.py packages/nanolab/scenarios-v2/persistent-recovery-k8s.yaml
git commit -m "Add Kubernetes persistent recovery scenario"
```

### Task 5: Document and verify the scenarios

**Files:**

- Modify: NanoFaaS `README.md`
- Modify: NanoFaaS `docs/testing.md`
- Modify: NanoLab scenario documentation only if it maintains an explicit scenario list

**Step 1: Document behavior and commands**

Explain that lifecycle scenarios intentionally remove state, while each `persistent-recovery-*` scenario keeps state only across its internal control-plane restart and performs ordinary teardown afterward.

**Step 2: Run the complete verification**

```bash
cd /Users/micheleciavotta/Downloads/nanolab
uv run --locked --all-packages --all-groups pytest -c packages/nanolab/pyproject.toml packages/nanolab/tests packages/sonata-tasks/tests
NANOFAAS_ROOT=/Users/micheleciavotta/Downloads/nanofaas ./nanolab.sh plan packages/nanolab/scenarios-v2/persistent-recovery-container.yaml
NANOFAAS_ROOT=/Users/micheleciavotta/Downloads/nanofaas ./nanolab.sh plan packages/nanolab/scenarios-v2/persistent-recovery-k8s.yaml --environment packages/nanolab/environments/multipass.yaml
```

Run the container scenario on Docker. Run the Kubernetes scenario against Multipass when available; otherwise record unit-test and plan-compilation evidence and leave the live Kubernetes run explicitly pending.

**Step 3: Commit documentation**

```bash
cd /Users/micheleciavotta/Downloads/nanofaas
git add README.md docs/testing.md
git commit -m "Document persistent recovery validation"
```
