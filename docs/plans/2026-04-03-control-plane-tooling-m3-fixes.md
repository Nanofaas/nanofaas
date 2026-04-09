# Control Plane Tooling Milestone 3 Review Fixes Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix the four review findings from Milestone 3 by making VM-backed orchestration correct, restoring real scenario semantics, and making `e2e all` manage shared VM lifecycle coherently.

**Architecture:** Keep `scripts/controlplane.sh` and the Python tool as the canonical user-facing entrypoints, but stop pretending partially migrated scenarios are complete. For the fix, make `VmOrchestrator` and `AnsibleAdapter` correct for Multipass, introduce explicit compatibility backends for scenarios that are not yet fully ported, and teach `E2eRunner` to distinguish shared-VM bootstrap from per-scenario execution. Preserve top-level wrapper UX, but route it to real workflows instead of placeholder `echo` steps.

**Tech Stack:** Python, Typer, Pydantic, pytest, Bash wrappers, Ansible, Multipass, SSH, Gradle, Docker-compatible runtime.

---

## Scope Guard

**In scope**

- fix Multipass provisioning so playbooks target the VM instead of localhost
- make VM startup idempotent and usable across `e2e all`
- implement real scenario backends for `container-local`, `deploy-host`, `k3s-curl`, `cli`, and `helm-stack`
- make `keep_vm` meaningful and add teardown behavior
- update tests and docs for the repaired M3 contract

**Out of scope**

- redesign of Helm/loadtest internals beyond restoring existing scenario behavior
- full rewrite of every legacy shell flow into native Python steps
- milestone 4 scenario parameterization work

## Fix Strategy

1. Lock the regressions with failing tests first.
2. Repair VM targeting and idempotent lifecycle before touching scenario semantics.
3. Restore scenario behavior with explicit compatibility backend entrypoints instead of placeholder commands.
4. Re-run wrapper-level and tool-level regression tests before touching docs.

### Task 1: Lock the VM targeting and lifecycle regressions with tests

**Files:**
- Modify: `tools/controlplane/tests/test_ansible_adapter.py`
- Modify: `tools/controlplane/tests/test_vm_adapter.py`
- Modify: `tools/controlplane/tests/test_e2e_runner.py`
- Modify: `tools/controlplane/tests/test_e2e_commands.py`
- Create: `scripts/tests/test_controlplane_e2e_wrapper_runtime.py`

**Step 1: Write the failing tests**

Add focused regressions for the review findings:

```python
def test_provision_base_for_multipass_targets_vm_ip() -> None:
    shell = ScriptedShell(
        stdout_map={
            ("multipass", "info", "nanofaas-e2e", "--format", "json"): MULTIPASS_INFO_JSON,
        }
    )
    adapter = AnsibleAdapter(repo_root=Path("/repo"), shell=shell)
    request = VmRequest(lifecycle="multipass", name="nanofaas-e2e", user="ubuntu")

    adapter.provision_base(request, dry_run=False)

    rendered = " ".join(shell.commands[-1])
    assert "-i" in shell.commands[-1]
    assert "192.168.64.10," in rendered
    assert "localhost," not in rendered


def test_ensure_running_is_idempotent_for_existing_multipass_vm() -> None:
    shell = ScriptedShell(
        stdout_map={
            ("multipass", "info", "nanofaas-e2e", "--format", "json"): MULTIPASS_RUNNING_JSON,
        }
    )
    orchestrator = VmOrchestrator(repo_root=Path("/repo"), shell=shell)

    orchestrator.ensure_running(VmRequest(lifecycle="multipass", name="nanofaas-e2e"))

    assert ["multipass", "launch", "--name", "nanofaas-e2e"] not in shell.commands


def test_e2e_all_vm_plan_bootstraps_shared_vm_once() -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())

    plan = runner.plan_all(only=["k3s-curl", "k8s-vm"])

    ensure_steps = [
        step for scenario in plan for step in scenario.steps if "Ensure VM is running" == step.summary
    ]
    assert len(ensure_steps) == 1


def test_container_local_dry_run_no_longer_uses_placeholder_echo() -> None:
    result = CliRunner().invoke(app, ["e2e", "run", "container-local", "--dry-run"])
    assert result.exit_code == 0
    assert "echo container-local verification workflow" not in result.stdout
```

Add wrapper-level coverage that `scripts/e2e-container-local.sh`, `scripts/e2e-cli.sh`, `scripts/e2e-k3s-curl.sh`, and `scripts/e2e-k3s-helm.sh` still lead to concrete workflows in dry-run output.

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_ansible_adapter.py \
  tools/controlplane/tests/test_vm_adapter.py \
  tools/controlplane/tests/test_e2e_runner.py \
  tools/controlplane/tests/test_e2e_commands.py -v

python3 -m pytest scripts/tests/test_controlplane_e2e_wrapper_runtime.py -q
```

Expected: FAIL because the current code still targets `localhost,`, repeats `multipass launch`, and exposes placeholder scenario commands.

**Step 3: Add minimal test support code**

If the current `RecordingShell` is too weak, extend `tools/controlplane/src/controlplane_tool/shell_backend.py` with a tiny scripted test double:

```python
@dataclass
class ScriptedShell(ShellBackend):
    stdout_map: dict[tuple[str, ...], str] = field(default_factory=dict)
```

It must:
- record commands like `RecordingShell`
- return configured stdout/stderr for commands that need parsing
- stay test-only simple; no production behavior hidden in the fake

**Step 4: Re-run tests and keep them failing only for the intended reasons**

Run the same commands from Step 2.

Expected: FAIL only on the review regression assertions, not due to missing scaffolding.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/tests/test_ansible_adapter.py \
  tools/controlplane/tests/test_vm_adapter.py \
  tools/controlplane/tests/test_e2e_runner.py \
  tools/controlplane/tests/test_e2e_commands.py \
  scripts/tests/test_controlplane_e2e_wrapper_runtime.py \
  tools/controlplane/src/controlplane_tool/shell_backend.py
git commit -m "test: lock m3 orchestration regressions"
```

### Task 2: Make Multipass provisioning target the VM and make startup idempotent

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/vm_adapter.py`
- Modify: `tools/controlplane/src/controlplane_tool/ansible_adapter.py`
- Test: `tools/controlplane/tests/test_ansible_adapter.py`
- Test: `tools/controlplane/tests/test_vm_adapter.py`

**Step 1: Implement VM connection resolution**

Add explicit resolution helpers to `VmOrchestrator`:

```python
def resolve_multipass_ipv4(self, request: VmRequest) -> str: ...
def connection_host(self, request: VmRequest) -> str: ...
```

Behavior:
- `external`: return `request.host`
- `multipass`: call `multipass info <name> --format json`, parse the primary IPv4
- raise a clean error if the VM cannot be resolved

Keep the parsing logic in `vm_adapter.py`, not in `ansible_adapter.py`.

**Step 2: Make `ensure_running()` idempotent**

For Multipass:
- try `multipass info <name> --format json`
- if missing, `multipass launch ...`
- if present but stopped, `multipass start <name>`
- if running, do nothing

Do not rely on `multipass launch` to be idempotent; it is not.

**Step 3: Make `AnsibleAdapter` use the resolved VM host**

Replace the current `_inventory_target()` logic with one that asks the VM layer for the real target:

```python
inventory = f"{resolved_host},"
```

Keep `localhost,` only for truly local playbooks, not for VM-backed scenarios. If needed, inject a resolver callback or pass `VmOrchestrator` into the adapter so the dependency direction remains explicit.

**Step 4: Run focused tests**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_ansible_adapter.py \
  tools/controlplane/tests/test_vm_adapter.py -v
```

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/vm_adapter.py \
  tools/controlplane/src/controlplane_tool/ansible_adapter.py \
  tools/controlplane/tests/test_ansible_adapter.py \
  tools/controlplane/tests/test_vm_adapter.py
git commit -m "fix: target multipass vms correctly during provisioning"
```

### Task 3: Restore real scenario semantics with explicit compatibility backends

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/e2e_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e_catalog.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e_commands.py`
- Create: `scripts/lib/e2e-container-local-backend.sh`
- Create: `scripts/lib/e2e-deploy-host-backend.sh`
- Create: `scripts/lib/e2e-k3s-curl-backend.sh`
- Create: `scripts/lib/e2e-cli-backend.sh`
- Create: `scripts/lib/e2e-helm-stack-backend.sh`
- Modify: `scripts/tests/test_e2e_runtime_runners.py`
- Modify: `tools/controlplane/tests/test_e2e_runner.py`
- Modify: `tools/controlplane/tests/test_e2e_commands.py`

**Step 1: Write the backend contract tests**

Lock the expected command shape per scenario:

```python
def test_container_local_plan_calls_backend_script() -> None:
    plan = E2eRunner(Path("/repo"), shell=RecordingShell()).plan(
        E2eRequest(scenario="container-local", runtime="java")
    )
    assert any("scripts/lib/e2e-container-local-backend.sh" in " ".join(step.command) for step in plan.steps)


def test_k3s_curl_plan_no_longer_routes_to_k8s_e2e_test() -> None:
    plan = E2eRunner(Path("/repo"), shell=RecordingShell()).plan(
        E2eRequest(
            scenario="k3s-curl",
            runtime="java",
            vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        )
    )
    rendered = [" ".join(step.command) for step in plan.steps]
    assert not any("K8sE2eTest" in command for command in rendered)
    assert any("e2e-k3s-curl-backend.sh" in command for command in rendered)
```

**Step 2: Rebuild each placeholder or misleading scenario as a backend entrypoint**

For each scenario:
- `container-local`: call the real local DEPLOYMENT flow previously covered by `scripts/e2e-container-local.sh`
- `deploy-host`: call the host-side fake-control-plane deploy flow
- `k3s-curl`: call the curl-driven VM workflow, not `K8sE2eTest`
- `cli`: call the real CLI lifecycle flow, not only `installDist`
- `helm-stack`: call the real Helm/load/autoscaling workflow

The backend scripts can initially delegate to existing low-level shell helpers, but they must be real executable workflows, not `echo` placeholders.

**Step 3: Adjust catalog text only if needed**

If a scenario name/description changed during reconstruction, update `e2e_catalog.py`. Otherwise keep names stable and make the implementation honor them.

**Step 4: Run focused tests**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_e2e_runner.py \
  tools/controlplane/tests/test_e2e_commands.py -v

python3 -m pytest scripts/tests/test_e2e_runtime_runners.py -q
```

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/e2e_runner.py \
  tools/controlplane/src/controlplane_tool/e2e_catalog.py \
  tools/controlplane/src/controlplane_tool/e2e_commands.py \
  scripts/lib/e2e-container-local-backend.sh \
  scripts/lib/e2e-deploy-host-backend.sh \
  scripts/lib/e2e-k3s-curl-backend.sh \
  scripts/lib/e2e-cli-backend.sh \
  scripts/lib/e2e-helm-stack-backend.sh \
  tools/controlplane/tests/test_e2e_runner.py \
  tools/controlplane/tests/test_e2e_commands.py \
  scripts/tests/test_e2e_runtime_runners.py
git commit -m "fix: restore real e2e scenario workflows"
```

### Task 4: Give `e2e all` a shared VM session and real `keep_vm` semantics

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/e2e_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e_models.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e_commands.py`
- Modify: `tools/controlplane/tests/test_e2e_runner.py`
- Modify: `tools/controlplane/tests/test_e2e_commands.py`

**Step 1: Write the failing lifecycle tests**

Add coverage for shared bootstrap and teardown:

```python
def test_run_all_bootstraps_vm_once_and_reuses_it() -> None:
    shell = RecordingShell()
    runner = E2eRunner(repo_root=Path("/repo"), shell=shell)

    runner.run_all(only=["k3s-curl", "k8s-vm"], runtime="java")

    launches = [command for command in shell.commands if command[:2] == ["multipass", "launch"]]
    assert len(launches) <= 1


def test_run_all_tears_down_vm_when_keep_vm_false() -> None:
    shell = RecordingShell()
    runner = E2eRunner(repo_root=Path("/repo"), shell=shell)

    runner.run_all(only=["k8s-vm"], runtime="java")

    assert any(command[:2] == ["multipass", "delete"] for command in shell.commands)
```

**Step 2: Introduce a distinct `run_all()` path**

Do not keep implementing `e2e all` as a loop over `run(plan.request)`. Instead:
- compute all plans once
- identify the shared VM bootstrap block
- execute shared VM setup once
- execute per-scenario steps without duplicating VM creation
- teardown once at the end when `keep_vm=False`

If useful, add an internal `VmSessionPlan` or `SharedVmExecution` data structure rather than encoding everything in booleans.

**Step 3: Make `keep_vm` meaningful**

Rules:
- `keep_vm=False`: teardown the Multipass VM at the end of `run_all()` and `run()` for VM-backed scenarios
- `keep_vm=True`: skip teardown
- `external` lifecycle: never attempt teardown

**Step 4: Run focused tests**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_e2e_runner.py \
  tools/controlplane/tests/test_e2e_commands.py -v
```

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/e2e_runner.py \
  tools/controlplane/src/controlplane_tool/e2e_models.py \
  tools/controlplane/src/controlplane_tool/e2e_commands.py \
  tools/controlplane/tests/test_e2e_runner.py \
  tools/controlplane/tests/test_e2e_commands.py
git commit -m "fix: share vm lifecycle across e2e all runs"
```

### Task 5: Re-verify wrappers, docs, and end-to-end contract

**Files:**
- Modify: `README.md`
- Modify: `docs/testing.md`
- Modify: `docs/control-plane.md`
- Modify: `scripts/e2e-all.sh`
- Modify: `scripts/e2e-container-local.sh`
- Modify: `scripts/e2e-cli.sh`
- Modify: `scripts/e2e-cli-deploy-host.sh`
- Modify: `scripts/e2e-k3s-curl.sh`
- Modify: `scripts/e2e-k3s-helm.sh`
- Modify: `scripts/e2e-k8s-vm.sh`
- Test: `scripts/tests/test_e2e_runtime_contract.py`
- Test: `scripts/tests/test_e2e_runtime_runners.py`
- Test: `scripts/tests/test_e2e_ansible_provisioning.py`

**Step 1: Update compatibility docs and wrapper expectations**

Make the docs reflect the repaired contract:
- VM-backed scenarios provision the VM, not localhost
- `e2e all` reuses one VM session
- `keep_vm` has defined semantics
- the scenario list maps to real workflows again

**Step 2: Re-run wrapper and repo-level regression suites**

Run:

```bash
bash -n \
  scripts/controlplane.sh \
  scripts/control-plane-build.sh \
  scripts/controlplane-tool.sh \
  scripts/e2e-all.sh \
  scripts/e2e-container-local.sh \
  scripts/e2e-cli.sh \
  scripts/e2e-cli-deploy-host.sh \
  scripts/e2e-k3s-curl.sh \
  scripts/e2e-k3s-helm.sh \
  scripts/e2e-k8s-vm.sh

python3 -m pytest \
  scripts/tests/test_e2e_runtime_contract.py \
  scripts/tests/test_e2e_runtime_runners.py \
  scripts/tests/test_e2e_ansible_provisioning.py -q

uv run --project tools/controlplane pytest tools/controlplane/tests -v
```

Expected: PASS.

**Step 3: Run representative dry-run smoke checks**

Run:

```bash
./scripts/controlplane.sh e2e run container-local --dry-run
./scripts/controlplane.sh e2e run k3s-curl --dry-run
./scripts/controlplane.sh e2e run cli --dry-run
./scripts/controlplane.sh e2e all --only k3s-curl,k8s-vm --dry-run
```

Expected:
- no placeholder `echo ... workflow`
- no `localhost,` in VM Ansible commands
- only one shared VM bootstrap section in `e2e all`

**Step 4: Commit**

```bash
git add \
  README.md \
  docs/testing.md \
  docs/control-plane.md \
  scripts/e2e-all.sh \
  scripts/e2e-container-local.sh \
  scripts/e2e-cli.sh \
  scripts/e2e-cli-deploy-host.sh \
  scripts/e2e-k3s-curl.sh \
  scripts/e2e-k3s-helm.sh \
  scripts/e2e-k8s-vm.sh \
  scripts/tests/test_e2e_runtime_contract.py \
  scripts/tests/test_e2e_runtime_runners.py \
  scripts/tests/test_e2e_ansible_provisioning.py
git commit -m "docs: align controlplane e2e wrappers with repaired m3 flows"
```

## Final Verification

Run the full verification set after Task 5:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests -v

python3 -m pytest \
  scripts/tests/test_controlplane_e2e_wrapper_runtime.py \
  scripts/tests/test_e2e_runtime_contract.py \
  scripts/tests/test_e2e_runtime_runners.py \
  scripts/tests/test_e2e_ansible_provisioning.py -q

./scripts/controlplane.sh e2e list
./scripts/controlplane.sh e2e run k8s-vm --dry-run
./scripts/controlplane.sh e2e all --only k3s-curl,k8s-vm --dry-run
```

Expected:
- all Python and wrapper regression suites pass
- VM-backed dry-run plans no longer target `localhost,`
- no placeholder workflows remain in the exposed scenarios
- `e2e all` shows a coherent shared-VM execution plan
