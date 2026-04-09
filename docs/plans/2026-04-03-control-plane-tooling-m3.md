# Control Plane Tooling Milestone 3 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Absorb VM lifecycle handling, SSH/Ansible provisioning, and E2E suite orchestration into the unified `tools/controlplane/` product, so the shell scripts become wrappers over one typed orchestration engine instead of carrying scenario logic themselves.

**Architecture:** Introduce a generic control-plane CLI wrapper, a typed VM/scenario model, and a Python orchestration layer for VM and E2E workflows. Keep `scripts/lib/e2e-k3s-common.sh` only as a low-level compatibility backend where needed during the transition, but move top-level scenario control flow into the tool. Promote `ops/ansible/` to the canonical home of Ansible assets and make `scripts/` entrypoints thin wrappers.

**Tech Stack:** Python, Typer, Pydantic, uv, pytest, Bash wrappers, Ansible, SSH, Multipass, Gradle, Docker-compatible runtime.

---

## Scope Guard

**In scope**

- generic non-interactive control-plane wrapper for build, vm, and e2e commands
- typed VM lifecycle and remote target configuration
- canonical `ops/ansible/` asset root
- VM lifecycle orchestration for `multipass` and `external`
- scenario runner for the current E2E suites
- conversion of top-level `scripts/e2e*.sh` runners into wrappers
- `scripts/e2e-all.sh` orchestration through the tool

**Out of scope**

- redesign of loadtest/autoscaling internals
- function/scenario parameterization beyond the current suite catalog
- `nanofaas-cli` feature redesign
- removal of every shell helper under `scripts/lib/` in this milestone

## Milestone 3 Contract

At the end of this milestone, the repository should expose one canonical orchestration wrapper:

```text
scripts/controlplane.sh build ...
scripts/controlplane.sh vm up ...
scripts/controlplane.sh vm sync ...
scripts/controlplane.sh vm provision-base ...
scripts/controlplane.sh vm provision-k3s ...
scripts/controlplane.sh vm registry ...
scripts/controlplane.sh vm down ...
scripts/controlplane.sh e2e list
scripts/controlplane.sh e2e run <scenario> ...
scripts/controlplane.sh e2e all [--only ...] [--skip ...]
scripts/controlplane.sh tui ...
```

Compatibility rules:

- `scripts/control-plane-build.sh` becomes a thin wrapper over `scripts/controlplane.sh`
- `scripts/controlplane-tool.sh` becomes a thin wrapper for the TUI path only
- top-level `scripts/e2e*.sh` become wrappers that forward into `scripts/controlplane.sh e2e ...`
- `scripts/lib/e2e-k3s-common.sh` may remain as a low-level backend during the milestone, but it must stop owning top-level scenario control flow

### Task 1: Establish the generic orchestration wrapper and CLI groups

**Files:**
- Create: `scripts/controlplane.sh`
- Modify: `scripts/control-plane-build.sh`
- Modify: `scripts/controlplane-tool.sh`
- Modify: `tools/controlplane/src/controlplane_tool/main.py`
- Create: `tools/controlplane/src/controlplane_tool/vm_commands.py`
- Create: `tools/controlplane/src/controlplane_tool/e2e_commands.py`
- Test: `tools/controlplane/tests/test_cli_smoke.py`
- Create: `tools/controlplane/tests/test_vm_commands.py`
- Create: `tools/controlplane/tests/test_e2e_commands.py`

**Step 1: Write the failing test**

Add CLI smoke coverage for the new top-level groups and wrapper shape:

```python
from pathlib import Path
from typer.testing import CliRunner

from controlplane_tool.main import app


def test_vm_group_help_exits_zero() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["vm", "--help"])
    assert result.exit_code == 0
    assert "vm" in result.stdout.lower()


def test_e2e_group_help_exits_zero() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["e2e", "--help"])
    assert result.exit_code == 0
    assert "e2e" in result.stdout.lower()


def test_generic_controlplane_wrapper_uses_locked_tool() -> None:
    script = Path("scripts/controlplane.sh").read_text(encoding="utf-8")
    assert "uv run --project tools/controlplane --locked controlplane-tool" in script
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_cli_smoke.py tools/controlplane/tests/test_vm_commands.py tools/controlplane/tests/test_e2e_commands.py -v`

Expected: FAIL because the generic wrapper and command groups do not exist yet.

**Step 3: Write minimal implementation**

- Add `scripts/controlplane.sh` as the canonical thin wrapper:

```bash
exec uv run --project tools/controlplane --locked controlplane-tool "$@"
```

- Change `scripts/control-plane-build.sh` to forward:

```bash
exec "$(dirname "$0")/controlplane.sh" "$@"
```

- Change `scripts/controlplane-tool.sh` to forward only the TUI path:

```bash
exec "$(dirname "$0")/controlplane.sh" tui "$@"
```

- Register `vm` and `e2e` Typer groups in `main.py`.
- For now, `vm` and `e2e` can expose only help + stub commands that fail with clean `Exit(code=2)` until later tasks implement real behavior.

**Step 4: Run test to verify it passes**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_cli_smoke.py tools/controlplane/tests/test_vm_commands.py tools/controlplane/tests/test_e2e_commands.py -v`

Expected: PASS.

**Step 5: Commit**

```bash
git add scripts/controlplane.sh scripts/control-plane-build.sh scripts/controlplane-tool.sh tools/controlplane/src/controlplane_tool/main.py tools/controlplane/src/controlplane_tool/vm_commands.py tools/controlplane/src/controlplane_tool/e2e_commands.py tools/controlplane/tests/test_cli_smoke.py tools/controlplane/tests/test_vm_commands.py tools/controlplane/tests/test_e2e_commands.py
git commit -m "feat: add unified controlplane vm and e2e command groups"
```

### Task 2: Introduce typed VM and scenario models

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/vm_models.py`
- Create: `tools/controlplane/src/controlplane_tool/e2e_models.py`
- Modify: `tools/controlplane/src/controlplane_tool/models.py`
- Test: `tools/controlplane/tests/test_vm_models.py`
- Create: `tools/controlplane/tests/test_e2e_models.py`

**Step 1: Write the failing test**

Add domain-model tests:

```python
from controlplane_tool.vm_models import VmRequest
from controlplane_tool.e2e_models import E2eRequest


def test_external_vm_request_requires_host() -> None:
    request = VmRequest(lifecycle="external", host="vm.example.test")
    assert request.lifecycle == "external"
    assert request.host == "vm.example.test"


def test_e2e_request_tracks_scenario_runtime_and_vm_config() -> None:
    request = E2eRequest(
        scenario="k8s-vm",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )
    assert request.scenario == "k8s-vm"
    assert request.vm.name == "nanofaas-e2e"
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_vm_models.py tools/controlplane/tests/test_e2e_models.py -v`

Expected: FAIL because the model files do not exist yet.

**Step 3: Write minimal implementation**

Model the two missing domains explicitly:

```python
VmLifecycle = Literal["multipass", "external"]
RuntimeKind = Literal["java", "rust"]
ScenarioName = Literal[
    "docker",
    "buildpack",
    "container-local",
    "k3s-curl",
    "k8s-vm",
    "cli",
    "cli-host",
    "deploy-host",
    "helm-stack",
]
```

Suggested shape:

```python
class VmRequest(BaseModel):
    lifecycle: VmLifecycle
    name: str | None = None
    host: str | None = None
    user: str = "ubuntu"
    home: str | None = None
    cpus: int = 4
    memory: str = "8G"
    disk: str = "30G"


class E2eRequest(BaseModel):
    scenario: ScenarioName
    runtime: RuntimeKind = "java"
    vm: VmRequest | None = None
    keep_vm: bool = False
    namespace: str | None = None
    local_registry: str = "localhost:5000"
```

- Add validation such as:
  - `external` requires `host`
  - VM-backed scenarios require `vm`
  - local scenarios must not require VM config

**Step 4: Run test to verify it passes**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_vm_models.py tools/controlplane/tests/test_e2e_models.py -v`

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/vm_models.py tools/controlplane/src/controlplane_tool/e2e_models.py tools/controlplane/src/controlplane_tool/models.py tools/controlplane/tests/test_vm_models.py tools/controlplane/tests/test_e2e_models.py
git commit -m "refactor: add typed vm and e2e request models"
```

### Task 3: Promote `ops/ansible/` to the canonical asset root

**Files:**
- Create/Move: `ops/ansible/ansible.cfg`
- Create/Move: `ops/ansible/requirements.txt`
- Create/Move: `ops/ansible/playbooks/provision-base.yml`
- Create/Move: `ops/ansible/playbooks/provision-k3s.yml`
- Create/Move: `ops/ansible/playbooks/configure-registry.yml`
- Modify: `tools/controlplane/src/controlplane_tool/paths.py`
- Modify: `scripts/lib/e2e-k3s-common.sh`
- Modify: `scripts/tests/test_e2e_ansible_provisioning.py`
- Modify: `tools/controlplane/tests/test_paths.py`

**Step 1: Write the failing test**

Shift the canonical-path tests:

```python
from controlplane_tool.paths import ToolPaths


def test_default_paths_include_ops_ansible_root() -> None:
    paths = ToolPaths.repo_root(Path("/repo"))
    assert paths.ops_root == Path("/repo/ops")
    assert paths.ansible_root == Path("/repo/ops/ansible")
```

And update the provisioning tests:

```python
assert (REPO_ROOT / "ops" / "ansible" / "ansible.cfg").exists()
assert "ops/ansible" in script
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_paths.py -v`

Run: `python3 -m pytest scripts/tests/test_e2e_ansible_provisioning.py -q`

Expected: FAIL because `ops/ansible/` is not canonical yet.

**Step 3: Write minimal implementation**

- Extend `ToolPaths`:

```python
@dataclass(frozen=True)
class ToolPaths:
    workspace_root: Path
    tool_root: Path
    profiles_dir: Path
    runs_dir: Path
    ops_root: Path
    ansible_root: Path
```

- Move Ansible assets to `ops/ansible/`.
- Update code and shell helpers to resolve the canonical root from `ops/ansible/`.
- Keep `scripts/ansible/` only as compatibility if needed in the same patch, but stop treating it as canonical in code/tests/docs.

**Step 4: Run test to verify it passes**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_paths.py -v`

Run: `python3 -m pytest scripts/tests/test_e2e_ansible_provisioning.py -q`

Run: `ansible-playbook --syntax-check ops/ansible/playbooks/provision-base.yml`

Expected: PASS.

**Step 5: Commit**

```bash
git add ops/ansible tools/controlplane/src/controlplane_tool/paths.py scripts/lib/e2e-k3s-common.sh scripts/tests/test_e2e_ansible_provisioning.py tools/controlplane/tests/test_paths.py
git commit -m "refactor: move ansible assets under ops root"
```

### Task 4: Build the VM orchestration adapter layer

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/vm_adapter.py`
- Create: `tools/controlplane/src/controlplane_tool/ansible_adapter.py`
- Create: `tools/controlplane/src/controlplane_tool/shell_backend.py`
- Modify: `tools/controlplane/src/controlplane_tool/vm_commands.py`
- Test: `tools/controlplane/tests/test_vm_commands.py`
- Create: `tools/controlplane/tests/test_vm_adapter.py`
- Create: `tools/controlplane/tests/test_ansible_adapter.py`

**Step 1: Write the failing test**

Add planner-style tests that do not require a real VM:

```python
from pathlib import Path

from controlplane_tool.vm_adapter import VmOrchestrator
from controlplane_tool.vm_models import VmRequest


def test_vm_up_multipass_plans_expected_backend_calls(tmp_path: Path) -> None:
    orchestrator = VmOrchestrator(repo_root=Path("/repo"), shell=RecordingShell())
    request = VmRequest(lifecycle="multipass", name="nanofaas-e2e", cpus=4, memory="8G", disk="30G")
    orchestrator.ensure_running(request, dry_run=True)
    assert "multipass" in orchestrator.shell.commands[0]


def test_vm_sync_external_plans_rsync_or_scp_via_backend() -> None:
    orchestrator = VmOrchestrator(repo_root=Path("/repo"), shell=RecordingShell())
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev", home="/srv/dev")
    orchestrator.sync_project(request, dry_run=True)
    assert "vm.example.test" in " ".join(orchestrator.shell.commands[0])
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_vm_commands.py tools/controlplane/tests/test_vm_adapter.py tools/controlplane/tests/test_ansible_adapter.py -v`

Expected: FAIL because the adapters and real `vm` commands do not exist yet.

**Step 3: Write minimal implementation**

- Introduce a shell backend abstraction with recordable command execution.
- Implement typed VM operations:
  - `ensure_running`
  - `sync_project`
  - `install_dependencies`
  - `install_k3s`
  - `setup_registry`
  - `teardown`
  - `export_kubeconfig`
- For this milestone, it is acceptable for adapters to call into `scripts/lib/e2e-k3s-common.sh` or equivalent shell commands, as long as the orchestration order is owned by Python.
- Expose CLI commands:

```text
controlplane-tool vm up
controlplane-tool vm sync
controlplane-tool vm provision-base
controlplane-tool vm provision-k3s
controlplane-tool vm registry
controlplane-tool vm down
controlplane-tool vm inspect
```

**Step 4: Run test to verify it passes**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_vm_commands.py tools/controlplane/tests/test_vm_adapter.py tools/controlplane/tests/test_ansible_adapter.py -v`

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/vm_adapter.py tools/controlplane/src/controlplane_tool/ansible_adapter.py tools/controlplane/src/controlplane_tool/shell_backend.py tools/controlplane/src/controlplane_tool/vm_commands.py tools/controlplane/tests/test_vm_commands.py tools/controlplane/tests/test_vm_adapter.py tools/controlplane/tests/test_ansible_adapter.py
git commit -m "feat: add vm orchestration adapters to controlplane tool"
```

### Task 5: Add the E2E scenario catalog and runner

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/e2e_catalog.py`
- Create: `tools/controlplane/src/controlplane_tool/e2e_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e_commands.py`
- Test: `tools/controlplane/tests/test_e2e_commands.py`
- Create: `tools/controlplane/tests/test_e2e_catalog.py`
- Create: `tools/controlplane/tests/test_e2e_runner.py`

**Step 1: Write the failing test**

Define the scenario surface in tests:

```python
from controlplane_tool.e2e_catalog import list_scenarios, resolve_scenario
from controlplane_tool.e2e_models import E2eRequest


def test_catalog_lists_expected_suite_names() -> None:
    names = [scenario.name for scenario in list_scenarios()]
    assert names == [
        "docker",
        "buildpack",
        "container-local",
        "k3s-curl",
        "k8s-vm",
        "cli",
        "cli-host",
        "deploy-host",
        "helm-stack",
    ]


def test_k8s_vm_scenario_is_vm_backed() -> None:
    scenario = resolve_scenario("k8s-vm")
    assert scenario.requires_vm is True
```

Add CLI tests:

```python
def test_e2e_list_prints_known_scenarios() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["e2e", "list"])
    assert result.exit_code == 0
    assert "k8s-vm" in result.stdout


def test_e2e_run_dry_run_prints_planned_steps() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["e2e", "run", "k3s-curl", "--dry-run"])
    assert result.exit_code == 0
    assert "scenario" in result.stdout.lower()
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_e2e_commands.py tools/controlplane/tests/test_e2e_catalog.py tools/controlplane/tests/test_e2e_runner.py -v`

Expected: FAIL because the catalog and runner do not exist yet.

**Step 3: Write minimal implementation**

- Add a scenario catalog with metadata:
  - requires VM or local only
  - supported runtimes
  - whether it uses host CLI
  - whether it groups multiple phases (`helm-stack`)
- Implement the runner for the current suite set.
- It is acceptable in this milestone for scenario steps to call the VM adapter and low-level shell backends rather than rewriting every kubectl/curl detail in pure Python immediately.
- Expose CLI commands:

```text
controlplane-tool e2e list
controlplane-tool e2e run <scenario> [--dry-run]
controlplane-tool e2e all [--only <csv>] [--skip <csv>] [--dry-run]
```

**Step 4: Run test to verify it passes**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_e2e_commands.py tools/controlplane/tests/test_e2e_catalog.py tools/controlplane/tests/test_e2e_runner.py -v`

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/e2e_catalog.py tools/controlplane/src/controlplane_tool/e2e_runner.py tools/controlplane/src/controlplane_tool/e2e_commands.py tools/controlplane/tests/test_e2e_commands.py tools/controlplane/tests/test_e2e_catalog.py tools/controlplane/tests/test_e2e_runner.py
git commit -m "feat: add e2e scenario catalog and runner"
```

### Task 6: Convert legacy E2E scripts into wrappers

**Files:**
- Modify: `scripts/e2e-all.sh`
- Modify: `scripts/e2e.sh`
- Modify: `scripts/e2e-buildpack.sh`
- Modify: `scripts/e2e-container-local.sh`
- Modify: `scripts/e2e-k3s-curl.sh`
- Modify: `scripts/e2e-k8s-vm.sh`
- Modify: `scripts/e2e-k3s-helm.sh`
- Modify: `scripts/e2e-cli.sh`
- Modify: `scripts/e2e-cli-host-platform.sh`
- Modify: `scripts/e2e-cli-deploy-host.sh`
- Modify: `scripts/tests/test_e2e_runtime_runners.py`
- Modify: `scripts/tests/test_e2e_runtime_contract.py`
- Modify: `scripts/tests/test_e2e_k3s_helm_control_plane_native.py`

**Step 1: Write the failing test**

Turn the shell runners into explicit wrapper-contract tests:

```python
def test_e2e_k8s_vm_script_is_now_a_wrapper() -> None:
    script = read_script("e2e-k8s-vm.sh")
    assert "scripts/controlplane.sh e2e run k8s-vm" in script
    assert "e2e_install_k3s" not in script


def test_e2e_all_script_delegates_to_tool_all_command() -> None:
    script = read_script("e2e-all.sh")
    assert "scripts/controlplane.sh e2e all" in script
    assert "SUITES=(" not in script
```

**Step 2: Run test to verify it fails**

Run: `python3 -m pytest scripts/tests/test_e2e_runtime_runners.py scripts/tests/test_e2e_runtime_contract.py scripts/tests/test_e2e_k3s_helm_control_plane_native.py -q`

Expected: FAIL because the scripts still own orchestration logic.

**Step 3: Write minimal implementation**

- Replace the top-level shell scripts with thin forwarders:

```bash
exec "$(dirname "$0")/controlplane.sh" e2e run k8s-vm "$@"
```

- For `e2e-all.sh`, forward `--only`, `--skip`, and `--dry-run` to `e2e all`.
- Preserve existing environment knobs; wrappers should pass them through unchanged.
- Leave `scripts/lib/e2e-k3s-common.sh` in place for the tool backend during this milestone.

**Step 4: Run test to verify it passes**

Run: `python3 -m pytest scripts/tests/test_e2e_runtime_runners.py scripts/tests/test_e2e_runtime_contract.py scripts/tests/test_e2e_k3s_helm_control_plane_native.py -q`

Run: `bash -n scripts/e2e-all.sh scripts/e2e.sh scripts/e2e-buildpack.sh scripts/e2e-container-local.sh scripts/e2e-k3s-curl.sh scripts/e2e-k8s-vm.sh scripts/e2e-k3s-helm.sh scripts/e2e-cli.sh scripts/e2e-cli-host-platform.sh scripts/e2e-cli-deploy-host.sh`

Expected: PASS.

**Step 5: Commit**

```bash
git add scripts/e2e-all.sh scripts/e2e.sh scripts/e2e-buildpack.sh scripts/e2e-container-local.sh scripts/e2e-k3s-curl.sh scripts/e2e-k8s-vm.sh scripts/e2e-k3s-helm.sh scripts/e2e-cli.sh scripts/e2e-cli-host-platform.sh scripts/e2e-cli-deploy-host.sh scripts/tests/test_e2e_runtime_runners.py scripts/tests/test_e2e_runtime_contract.py scripts/tests/test_e2e_k3s_helm_control_plane_native.py
git commit -m "refactor: convert legacy e2e scripts into controlplane wrappers"
```

### Task 7: Update docs and run milestone verification

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`
- Modify: `docs/testing.md`
- Modify: `docs/quickstart.md`
- Modify: `docs/control-plane.md`
- Modify: `tools/controlplane/README.md`
- Modify: `tools/controlplane/tests/test_docs_links.py`

**Step 1: Write the failing test**

Extend docs tests with `M3` expectations:

```python
assert "scripts/controlplane.sh e2e run k8s-vm" in testing
assert "scripts/controlplane.sh vm up" in root_readme
assert "ops/ansible" in tool_readme
assert "scripts/e2e-k8s-vm.sh" in testing
assert "wrapper" in testing.lower()
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_docs_links.py -v`

Expected: FAIL because docs still describe the old script-first VM/E2E UX.

**Step 3: Write minimal implementation**

- Document `scripts/controlplane.sh` as the canonical orchestration entrypoint.
- Document `vm` and `e2e` subcommands.
- Document `ops/ansible/` as the canonical operational asset root.
- Keep explicit note that old `scripts/e2e*.sh` files are compatibility wrappers.

**Step 4: Run milestone verification**

Run:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests/test_cli_smoke.py tools/controlplane/tests/test_vm_models.py tools/controlplane/tests/test_vm_adapter.py tools/controlplane/tests/test_vm_commands.py tools/controlplane/tests/test_e2e_models.py tools/controlplane/tests/test_e2e_catalog.py tools/controlplane/tests/test_e2e_runner.py tools/controlplane/tests/test_e2e_commands.py tools/controlplane/tests/test_docs_links.py -v
python3 -m pytest scripts/tests/test_e2e_ansible_provisioning.py scripts/tests/test_e2e_runtime_runners.py scripts/tests/test_e2e_runtime_contract.py scripts/tests/test_e2e_k3s_helm_control_plane_native.py -q
bash -n scripts/controlplane.sh scripts/control-plane-build.sh scripts/controlplane-tool.sh scripts/e2e-all.sh scripts/e2e.sh scripts/e2e-buildpack.sh scripts/e2e-container-local.sh scripts/e2e-k3s-curl.sh scripts/e2e-k8s-vm.sh scripts/e2e-k3s-helm.sh scripts/e2e-cli.sh scripts/e2e-cli-host-platform.sh scripts/e2e-cli-deploy-host.sh
ansible-playbook --syntax-check ops/ansible/playbooks/provision-base.yml
ansible-playbook --syntax-check ops/ansible/playbooks/provision-k3s.yml
ansible-playbook --syntax-check ops/ansible/playbooks/configure-registry.yml
scripts/controlplane.sh e2e list
scripts/controlplane.sh vm up --lifecycle multipass --name nanofaas-plan-test --dry-run
scripts/controlplane.sh e2e run k8s-vm --lifecycle multipass --dry-run
scripts/controlplane.sh e2e all --only k3s-curl,k8s-vm --dry-run
```

Expected: PASS.

**Step 5: Commit**

```bash
git add README.md CLAUDE.md docs/testing.md docs/quickstart.md docs/control-plane.md tools/controlplane/README.md tools/controlplane/tests/test_docs_links.py
git commit -m "docs: document vm and e2e orchestration through controlplane tool"
```
