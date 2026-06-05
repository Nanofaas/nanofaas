# Connectivity Strategy (Phase B1) — Design

**Date:** 2026-06-05
**Status:** Approved (design). Part of the loadtest-scenario-unification roadmap
(`2026-06-05-loadtest-scenario-unification-design.md`, Phase B1).

## 1. Goal

Replace the hard-coded multipass connectivity in the scenario execution machinery
(`build_command_tasks`) with a pluggable, per-lifecycle **`ConnectivityStrategy`**, and
route the **proxmox** loadtest prelude through that same unified machinery — retiring
proxmox's bespoke `_build_prelude_tasks` / `_rewrite_ansible`. **Azure is deferred** (its
provisioning is new behavior that cannot be validated on real VMs right now).

## 2. Background: what differs at execution time

Scenario recipes are turned into honest `CommandTask`s by `build_command_tasks`
(`scenario/scenarios/_workflow_assembly.py`). Operations route by `execution_target`:

- **vm-ops** (`helm`, `docker`, `gradlew`, the CLI binary) run via
  `VmCommandTaskExecutor(OrchestratorVmRunner(orch, request))`. The orchestrator already
  abstracts SSH for all three lifecycles, so **vm-ops are already lifecycle-agnostic** —
  the only variable is *which* orchestrator builds the runner.
- **host-ops** (`ansible-playbook`, `rsync` for `repo.sync_to_vm`) run on the host shell
  and must target the VM's SSH endpoint. This is the part that differs:
  - **multipass:** `<multipass-ip:NAME>` placeholders resolved to the VM IP by
    `CommandResolver` (current behavior in `resolve_host_operation`).
  - **proxmox:** ansible inventory rewritten to the NAT host + `-e ansible_port=<port>` +
    `--private-key`; rsync rebuilt over `host:port` with the key (`_rewrite_ansible` /
    `_repo_sync_command` in `proxmox_vm_loadtest.py`).
  - **azure:** public host + key (no provisioning exists today — out of scope here).

proxmox additionally substitutes `cli.fn_apply_selected` → a REST `functions.register`
step for loadtest scenarios. That is a *recipe/component* concern, not connectivity;
`build_command_tasks` already supports it via its existing `special_handler` parameter.

## 3. Design

### 3.1 `ConnectivityStrategy` (the seam)
A small interface (in `controlplane_tool/scenario`, next to `build_command_tasks`):

```python
class ConnectivityStrategy(Protocol):
    def resolve_ansible_operation(self, op: RemoteCommandOperation) -> RemoteCommandOperation: ...
    def repo_sync_command(self, *, repo_root: Path, destination: str) -> list[str]: ...
    def vm_runner(self, request) -> VmCommandRunner: ...
```

- `resolve_ansible_operation` — rewrite the host ansible op's inventory/port/key for this
  lifecycle.
- `repo_sync_command` — the rsync argv for `repo.sync_to_vm` for this lifecycle.
- `vm_runner` — `OrchestratorVmRunner` wrapping this lifecycle's orchestrator, for vm-ops.

`build_command_tasks` takes a `ConnectivityStrategy` and routes:
host ansible-op → `resolve_ansible_operation`; `repo.sync_to_vm` → `repo_sync_command`;
vm-op → `vm_runner`. Its existing `special_handler` / `context_selector` parameters are
retained for scenario-specific operations (e.g. proxmox's `functions.register`).

### 3.2 Implementations
- **`MultipassConnectivity`** — extracts the current behavior verbatim: `resolve_host_operation`
  via `CommandResolver` (`<multipass-ip:NAME>` → IP); the current multipass rsync builder;
  `vm_runner` over the multipass `VmOrchestrator`. **Behavior-preserving.**
- **`ProxmoxConnectivity`** — extracts proxmox's `_rewrite_ansible` (NAT host + `ansible_port`
  + `--private-key`) and `_repo_sync_command`; `vm_runner` over the proxmox orchestrator.
  Endpoint (`host`, `port`, `key`) comes from the proxmox orchestrator's public
  `ssh_endpoint()` / `ssh_private_key_path()`. **Argv-preserving** (same commands as today).
- **Azure** — deferred. Azure scenario `run()` is untouched in B1.

### 3.3 Migration
- **B1a — Strategy + Multipass:** introduce `ConnectivityStrategy`; refactor
  `build_command_tasks` to take it; provide `MultipassConnectivity` as the default used by
  the recipe-driven scenarios (k3s-junit-curl, helm-stack, cli-stack) and two-vm's stack
  prelude. The produced argv for every existing scenario stays identical.
- **B1b — Proxmox:** add `ProxmoxConnectivity`; route the proxmox loadtest prelude through
  `build_command_tasks` with that strategy + a `special_handler` for `functions.register`;
  retire `_build_prelude_tasks` / `_rewrite_ansible` / `_repo_sync_command`. Proxmox's
  produced argv stays identical to today.

## 4. Testing Strategy

- **Argv golden / characterization tests (CI):** the safety net for a behavior-preserving
  refactor with no real-VM coverage. Pin the exact argv (and env) each scenario's prelude
  produces *before* the refactor; assert the unified machinery produces identical argv
  *after*. This is how proxmox is guarded without a real Proxmox.
  - multipass scenarios: characterize k3s-junit-curl / helm-stack / cli-stack / two-vm
    stack-prelude command argv.
  - proxmox: characterize the proxmox prelude command argv (inventory/port/key/rsync).
- **Unit tests:** each `ConnectivityStrategy` impl (ansible rewrite, repo_sync_command,
  vm_runner wiring) tested in isolation with recording shells / fakes.
- **Real-VM validation (user-run, NOT CI):** multipass — run `two-vm-loadtest` end to end
  on real multipass VMs to confirm B1a is genuinely behavior-preserving. Proxmox/azure are
  NOT validated; the PR must state this explicitly.

## 5. Risks
- **No CI e2e coverage** — argv golden tests prove the commands are unchanged, not that the
  flow works. Mitigation: real multipass run for the validatable path; argv-identity for
  proxmox.
- **proxmox entanglement** — its prelude builder mixes connectivity, repo-sync, and the
  `functions.register` substitution. B1b must move each concern to the right seam
  (strategy vs special_handler) without changing produced argv. Golden test guards this.
- **Scope discipline** — B1 is connectivity only. The canonical-recipe function-registration
  change for two-vm/azure (so all three register the same way) is **not** B1 — it lands in
  the later loadgen-collapse phase. B1 keeps each scenario's current behavior.

## 6. Out of Scope
- Azure provisioning / azure routing through the unified machinery — deferred until real-VM
  validation is possible.
- The shared loadgen sequence (Phase B2) and final scenario collapse (B3).
- Canonicalizing function registration across the three loadtest scenarios.
