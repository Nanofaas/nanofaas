# Control Plane Legacy Retirement Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Eliminate the remaining legacy shell-oriented orchestration code from the control-plane tooling stack so `scripts/controlplane.sh` and `tools/controlplane/` own the full runtime behavior, not just the planning surface.

**Architecture:** Keep `scripts/controlplane.sh` as the only public shell entrypoint, but move all remaining workflow logic from `scripts/lib/*.sh`, `scripts/e2e*.sh`, and `experiments/e2e-loadtest*.sh` into typed Python runners and adapters under `tools/controlplane/src/controlplane_tool/`. Replace compatibility backends one family at a time, lock behavior with contract tests first, and only delete shell components after a Python path is already passing the same tests.

**Tech Stack:** Python, Typer, pytest, subprocess-backed adapters, SSH/Ansible, Multipass, Docker-compatible runtimes, `kubectl`, Gradle, existing `tools/controlplane` domain models and test suites.

---

## Scope and End State

At the end of this roadmap:

- `scripts/controlplane.sh` is the only user-facing shell entrypoint kept on purpose
- all orchestration logic for `e2e`, `loadtest`, and `cli-test` lives under `tools/controlplane/src/controlplane_tool/`
- `scripts/lib/e2e-*.sh`, `scripts/lib/e2e-k3s-common.sh`, and `scripts/lib/scenario-manifest.sh` are deleted
- `scripts/e2e*.sh`, `scripts/control-plane-build.sh`, and `scripts/controlplane-tool.sh` are either deleted or reduced to short, temporary wrappers that are no longer documented as primary UX
- `experiments/e2e-loadtest*.sh` no longer own the canonical Helm/Grafana/parity loadtest workflow

## Explicit Non-Goals

- rewriting Gradle-based project builds in Python
- removing `bash` from the repository entirely
- redesigning function semantics, control-plane APIs, or `nanofaas-cli`
- deleting repo-ops helpers that are not part of the end-user control-plane orchestration surface

## Milestone Map

- `M8` Freeze the legacy inventory and extract Python runtime primitives
- `M9` Replace local shell backends (`container-local`, `deploy-host`) and remove `scenario-manifest.sh`
- `M10` Replace CLI shell backends (`vm`, `host-platform`) and keep `cli-test` fully Python-owned
- `M11` Replace VM/K8s shell backends (`k3s-curl`, `helm-stack`) and absorb `e2e-k3s-common.sh`
- `M12` Replace the legacy Helm/Grafana/parity loadtest shell flow and retire `experiments/e2e-loadtest*.sh`
- `M13` Delete remaining compatibility wrappers and finish repo-wide cleanup

---

### Task 1 / M8: Freeze the legacy surface and create Python runtime primitives

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/runtime_primitives.py`
- Create: `tools/controlplane/src/controlplane_tool/control_plane_api.py`
- Create: `tools/controlplane/tests/test_runtime_primitives.py`
- Create: `tools/controlplane/tests/test_control_plane_api.py`
- Modify: `tools/controlplane/tests/test_canonical_entrypoints.py`
- Modify: `scripts/tests/test_legacy_wrappers_contract.py`
- Modify: `scripts/tests/test_e2e_runtime_contract.py`

**Step 1: Write the failing tests**

Add tests that lock the intended end state:

```python
def test_runtime_primitives_wrap_process_execution_without_shell_scripts() -> None:
    runner = CommandRunner(shell=RecordingShell(), repo_root=Path("/repo"))
    result = runner.run(["echo", "hi"], dry_run=True)
    assert result.command == ["echo", "hi"]


def test_control_plane_api_builds_register_and_invoke_requests() -> None:
    api = ControlPlaneApi(base_url="http://localhost:8080")
    assert api.register_url == "http://localhost:8080/v1/functions"
```

Expand the drift tests so they explicitly classify:

- public wrappers still allowed temporarily
- internal shell backends that must disappear by the end of the roadmap
- files that are already forbidden in strict canonical docs

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_runtime_primitives.py \
  tools/controlplane/tests/test_control_plane_api.py -v

python3 -m pytest \
  scripts/tests/test_legacy_wrappers_contract.py \
  scripts/tests/test_e2e_runtime_contract.py -q
```

Expected:

- new Python primitive tests fail because the modules do not exist yet
- shell drift tests continue to describe the current legacy inventory

**Step 3: Write minimal implementation**

Create Python primitives that are explicitly reusable by future milestones:

- `CommandRunner`
- `ContainerRuntimeOps`
- `KubectlOps`
- `HttpOps` / `ControlPlaneApi`
- small JSON/file helpers currently buried in shell backends

Do not migrate any workflow yet. Just establish a stable Python substrate.

**Step 4: Run tests to verify they pass**

Run the same commands from Step 2.

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/runtime_primitives.py \
  tools/controlplane/src/controlplane_tool/control_plane_api.py \
  tools/controlplane/tests/test_runtime_primitives.py \
  tools/controlplane/tests/test_control_plane_api.py \
  tools/controlplane/tests/test_canonical_entrypoints.py \
  scripts/tests/test_legacy_wrappers_contract.py \
  scripts/tests/test_e2e_runtime_contract.py
git commit -m "refactor: add python runtime primitives for legacy retirement"
```

---

### Task 2 / M9: Replace local shell backends and delete `scenario-manifest.sh`

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/scenario_runtime.py`
- Create: `tools/controlplane/src/controlplane_tool/local_e2e_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e_catalog.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e_commands.py`
- Modify: `tools/controlplane/tests/test_e2e_runner.py`
- Modify: `tools/controlplane/tests/test_e2e_commands.py`
- Modify: `scripts/tests/test_e2e_runtime_runners.py`
- Delete: `scripts/lib/e2e-container-local-backend.sh`
- Delete: `scripts/lib/e2e-deploy-host-backend.sh`
- Delete: `scripts/lib/scenario-manifest.sh`

**Step 1: Write the failing tests**

Lock the desired post-shell behavior:

```python
def test_container_local_plan_no_longer_routes_to_shell_backend() -> None:
    plan = E2eRunner(Path("/repo"), shell=RecordingShell()).plan(
        E2eRequest(scenario="container-local", runtime="java", functions=["word-stats-java"])
    )
    assert not any("e2e-container-local-backend.sh" in " ".join(step.command) for step in plan.steps)


def test_deploy_host_plan_no_longer_routes_to_shell_backend() -> None:
    ...
```

Add shell/runtime tests that fail if deleted files are still referenced.

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_e2e_runner.py \
  tools/controlplane/tests/test_e2e_commands.py -v

python3 -m pytest \
  scripts/tests/test_e2e_runtime_runners.py -q
```

Expected: FAIL because `container-local` and `deploy-host` still call shell backends.

**Step 3: Write minimal implementation**

Move these workflows into Python:

- resolve selected functions directly from `ResolvedScenario`
- build/register/invoke/scale `container-local` using Python runtime primitives
- build/push/register `deploy-host` without shell backend indirection
- replace shell manifest helpers with direct use of `ResolvedScenario`

Keep `container-local` explicitly single-function at the planner boundary.

**Step 4: Run tests to verify they pass**

Run the same commands from Step 2.

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/scenario_runtime.py \
  tools/controlplane/src/controlplane_tool/local_e2e_runner.py \
  tools/controlplane/src/controlplane_tool/e2e_runner.py \
  tools/controlplane/src/controlplane_tool/e2e_catalog.py \
  tools/controlplane/src/controlplane_tool/e2e_commands.py \
  tools/controlplane/tests/test_e2e_runner.py \
  tools/controlplane/tests/test_e2e_commands.py \
  scripts/tests/test_e2e_runtime_runners.py
git rm \
  scripts/lib/e2e-container-local-backend.sh \
  scripts/lib/e2e-deploy-host-backend.sh \
  scripts/lib/scenario-manifest.sh
git commit -m "refactor: replace local legacy e2e shell backends"
```

---

### Task 3 / M10: Replace CLI shell backends and keep `cli-test` fully Python-owned

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/cli_runtime.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_test_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_test_commands.py`
- Modify: `tools/controlplane/src/controlplane_tool/vm_adapter.py`
- Modify: `tools/controlplane/tests/test_cli_test_runner.py`
- Modify: `tools/controlplane/tests/test_cli_test_commands.py`
- Modify: `scripts/tests/test_cli_test_wrapper_runtime.py`
- Modify: `scripts/tests/test_controlplane_e2e_wrapper_runtime.py`
- Delete: `scripts/lib/e2e-cli-backend.sh`
- Delete: `scripts/lib/e2e-cli-host-backend.sh`

**Step 1: Write the failing tests**

Add tests that assert the CLI flows do not depend on shell backends anymore:

```python
def test_cli_vm_runner_no_longer_uses_shell_backend_script() -> None:
    ...


def test_cli_host_platform_runner_no_longer_uses_shell_backend_script() -> None:
    ...
```

Update wrapper/runtime tests so they require:

- wrapper still resolves to `scripts/controlplane.sh cli-test ...`
- runtime path no longer references `e2e-cli-backend.sh` or `e2e-cli-host-backend.sh`

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_cli_test_runner.py \
  tools/controlplane/tests/test_cli_test_commands.py -v

python3 -m pytest \
  scripts/tests/test_cli_test_wrapper_runtime.py \
  scripts/tests/test_controlplane_e2e_wrapper_runtime.py -q
```

Expected: FAIL because the current runtime still goes through shell scripts.

**Step 3: Write minimal implementation**

Move these concerns into Python:

- `:nanofaas-cli:installDist` build step
- manifest-aware VM CLI workflow
- manifest-free host-platform workflow
- multi-function deploy-host behavior already preserved from the previous milestone

Keep the low-level subprocess execution in Python adapters rather than shell scripts.

**Step 4: Run tests to verify they pass**

Run the same commands from Step 2.

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/cli_runtime.py \
  tools/controlplane/src/controlplane_tool/cli_test_runner.py \
  tools/controlplane/src/controlplane_tool/cli_test_commands.py \
  tools/controlplane/src/controlplane_tool/vm_adapter.py \
  tools/controlplane/tests/test_cli_test_runner.py \
  tools/controlplane/tests/test_cli_test_commands.py \
  scripts/tests/test_cli_test_wrapper_runtime.py \
  scripts/tests/test_controlplane_e2e_wrapper_runtime.py
git rm \
  scripts/lib/e2e-cli-backend.sh \
  scripts/lib/e2e-cli-host-backend.sh
git commit -m "refactor: replace legacy cli shell backends"
```

---

### Task 4 / M11: Replace VM/K8s shell backends and absorb `e2e-k3s-common.sh`

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/k3s_runtime.py`
- Create: `tools/controlplane/src/controlplane_tool/registry_runtime.py`
- Modify: `tools/controlplane/src/controlplane_tool/vm_adapter.py`
- Modify: `tools/controlplane/src/controlplane_tool/ansible_adapter.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e_catalog.py`
- Modify: `tools/controlplane/tests/test_e2e_runner.py`
- Modify: `tools/controlplane/tests/test_vm_commands.py`
- Modify: `scripts/tests/test_e2e_ansible_provisioning.py`
- Modify: `scripts/tests/test_e2e_k3s_common_external_ssh_mode.py`
- Modify: `scripts/tests/test_e2e_k3s_common_deleted_vm_recovery.py`
- Modify: `scripts/tests/test_e2e_runtime_contract.py`
- Modify: `scripts/tests/test_e2e_runtime_runners.py`
- Delete: `scripts/lib/e2e-k3s-curl-backend.sh`
- Delete: `scripts/lib/e2e-helm-stack-backend.sh`
- Delete: `scripts/lib/e2e-k3s-common.sh`

**Step 1: Write the failing tests**

Lock the intended runtime:

```python
def test_k3s_curl_plan_no_longer_routes_to_shell_backend() -> None:
    ...


def test_helm_stack_plan_no_longer_routes_to_shell_backend() -> None:
    ...
```

Update the common-library tests so they fail when `e2e-k3s-common.sh` still exists or is still referenced from wrappers/docs/runtime.

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_e2e_runner.py \
  tools/controlplane/tests/test_vm_commands.py -v

python3 -m pytest \
  scripts/tests/test_e2e_ansible_provisioning.py \
  scripts/tests/test_e2e_k3s_common_external_ssh_mode.py \
  scripts/tests/test_e2e_k3s_common_deleted_vm_recovery.py \
  scripts/tests/test_e2e_runtime_contract.py \
  scripts/tests/test_e2e_runtime_runners.py -q
```

Expected: FAIL because the current path still depends on shell backends and `e2e-k3s-common.sh`.

**Step 3: Write minimal implementation**

Move into Python:

- k3s provisioning and registry setup orchestration
- k3s curl-style register/invoke/assert workflow
- Helm install / autoscaling / smoke flow currently in the helm shell backend
- external-vs-multipass branching currently in `e2e-k3s-common.sh`

Keep `vm_adapter.py` and `ansible_adapter.py` as the only places that know about SSH/Multipass/Ansible command formation.

**Step 4: Run tests to verify they pass**

Run the same commands from Step 2.

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/k3s_runtime.py \
  tools/controlplane/src/controlplane_tool/registry_runtime.py \
  tools/controlplane/src/controlplane_tool/vm_adapter.py \
  tools/controlplane/src/controlplane_tool/ansible_adapter.py \
  tools/controlplane/src/controlplane_tool/e2e_runner.py \
  tools/controlplane/src/controlplane_tool/e2e_catalog.py \
  tools/controlplane/tests/test_e2e_runner.py \
  tools/controlplane/tests/test_vm_commands.py \
  scripts/tests/test_e2e_ansible_provisioning.py \
  scripts/tests/test_e2e_k3s_common_external_ssh_mode.py \
  scripts/tests/test_e2e_k3s_common_deleted_vm_recovery.py \
  scripts/tests/test_e2e_runtime_contract.py \
  scripts/tests/test_e2e_runtime_runners.py
git rm \
  scripts/lib/e2e-k3s-curl-backend.sh \
  scripts/lib/e2e-helm-stack-backend.sh \
  scripts/lib/e2e-k3s-common.sh
git commit -m "refactor: replace legacy vm and k3s shell backends"
```

---

### Task 5 / M12: Replace legacy loadtest shell ownership and retire `experiments/e2e-loadtest*.sh`

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/loadtest_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/loadtest_commands.py`
- Modify: `tools/controlplane/src/controlplane_tool/adapters.py`
- Create: `tools/controlplane/src/controlplane_tool/grafana_runtime.py`
- Modify: `tools/controlplane/tests/test_loadtest_runner.py`
- Modify: `tools/controlplane/tests/test_loadtest_commands.py`
- Modify: `scripts/tests/test_loadtest_wrapper_runtime.py`
- Delete: `experiments/e2e-loadtest.sh`
- Delete: `experiments/e2e-loadtest-registry.sh`
- Modify or Delete: `scripts/e2e-loadtest.sh`

**Step 1: Write the failing tests**

Add tests that make the legacy shell ownership impossible to keep:

```python
def test_loadtest_wrapper_no_longer_routes_to_experiments_script() -> None:
    script = Path("scripts/e2e-loadtest.sh").read_text(encoding="utf-8")
    assert "experiments/e2e-loadtest.sh" not in script


def test_loadtest_runner_owns_grafana_and_parity_flow() -> None:
    ...
```

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_loadtest_runner.py \
  tools/controlplane/tests/test_loadtest_commands.py -v

python3 -m pytest scripts/tests/test_loadtest_wrapper_runtime.py -q
```

Expected: FAIL because `scripts/e2e-loadtest.sh` still delegates to the old experiment flow.

**Step 3: Write minimal implementation**

Move these behaviors into Python:

- Helm/Grafana/parity bootstrap
- Grafana local runtime startup and teardown
- loadtest profile mapping for `--profile demo-java`
- registry-summary behavior if it remains part of the supported product surface

Delete the `experiments/e2e-loadtest*.sh` files once tests are green.

**Step 4: Run tests to verify they pass**

Run the same commands from Step 2.

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/loadtest_runner.py \
  tools/controlplane/src/controlplane_tool/loadtest_commands.py \
  tools/controlplane/src/controlplane_tool/adapters.py \
  tools/controlplane/src/controlplane_tool/grafana_runtime.py \
  tools/controlplane/tests/test_loadtest_runner.py \
  tools/controlplane/tests/test_loadtest_commands.py \
  scripts/tests/test_loadtest_wrapper_runtime.py
git rm \
  experiments/e2e-loadtest.sh \
  experiments/e2e-loadtest-registry.sh
git commit -m "refactor: retire legacy loadtest shell ownership"
```

---

### Task 6 / M13: Delete remaining compatibility wrappers and finish repository cleanup

**Files:**
- Modify or Delete: `scripts/control-plane-build.sh`
- Modify or Delete: `scripts/controlplane-tool.sh`
- Modify or Delete: `scripts/e2e-all.sh`
- Modify or Delete: `scripts/e2e-buildpack.sh`
- Modify or Delete: `scripts/e2e-cli.sh`
- Modify or Delete: `scripts/e2e-cli-host-platform.sh`
- Modify or Delete: `scripts/e2e-cli-deploy-host.sh`
- Modify or Delete: `scripts/e2e-container-local.sh`
- Modify or Delete: `scripts/e2e-k3s-curl.sh`
- Modify or Delete: `scripts/e2e-k3s-helm.sh`
- Modify or Delete: `scripts/e2e-k8s-vm.sh`
- Modify or Delete: `scripts/e2e-loadtest.sh`
- Modify: `README.md`
- Modify: `CLAUDE.md`
- Modify: `docs/control-plane.md`
- Modify: `docs/testing.md`
- Modify: `docs/e2e-tutorial.md`
- Modify: `docs/nanofaas-cli.md`
- Modify: `tools/controlplane/README.md`
- Modify: `tools/controlplane/tests/test_canonical_entrypoints.py`
- Modify: `tools/controlplane/tests/test_docs_links.py`
- Modify: `scripts/tests/test_legacy_wrappers_contract.py`

**Step 1: Decide the final wrapper policy**

By default, prefer deletion over keeping shims.

Allowed end states:

- only `scripts/controlplane.sh` remains documented
- a deleted wrapper is referenced nowhere in strict docs/tests
- if one shim must survive temporarily, tests must name it explicitly and mark it as temporary

**Step 2: Write the failing tests**

Tighten the repo-wide tests:

```python
def test_strict_docs_reference_only_controlplane_wrapper() -> None:
    ...


def test_no_legacy_wrapper_scripts_remain_documented() -> None:
    ...
```

**Step 3: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_canonical_entrypoints.py \
  tools/controlplane/tests/test_docs_links.py -v

python3 -m pytest \
  scripts/tests/test_legacy_wrappers_contract.py -q
```

Expected: FAIL until wrappers and docs are fully cleaned.

**Step 4: Implement the cleanup**

- delete wrappers whose callers are fully migrated
- update all docs to use `scripts/controlplane.sh ...`
- remove compatibility-only explanations that no longer serve a real migration need

**Step 5: Run full confidence gate**

Run:

```bash
./gradlew test
uv run --project tools/controlplane --locked pytest tools/controlplane/tests -q
python3 -m pytest scripts/tests -q
```

Expected: PASS.

**Step 6: Commit**

```bash
git add -A
git commit -m "refactor: remove final legacy controlplane surfaces"
```

---

## Recommended Execution Order

Do not skip around.

1. `M8` first, because it creates the Python primitives the later migrations need.
2. `M9` before `M10`, because `scenario-manifest.sh` and local shell helpers are simpler and force the Python-side manifest ownership.
3. `M10` before `M11`, because CLI runtime migration is smaller than the full VM/K3s migration.
4. `M11` before `M12`, because the Helm/Grafana loadtest flow depends on the VM/K3s runtime substrate.
5. `M13` only when all earlier milestones are green and no canonical path still uses deleted shell pieces.

## Risk Notes

- `M11` and `M12` are the highest-risk milestones because they replace the shell code that currently owns the most real behavior.
- Keep the shell files until the Python path is already green. Delete only after the tests prove the Python path is authoritative.
- Do not widen scenario semantics while migrating. Preserve current contracts unless an explicit test and doc update say otherwise.
- Keep live VM/k3s verification as a final confidence gate after each major VM-backed milestone.
