# Release-on-Workflow-Engine Design

**Date:** 2026-07-24
**Status:** Draft
**Author:** derived from the v0.17.0 release hardening effort (2026-07-22 → 24)

## Goal

Bring the `controlplane-tool release` pipeline onto the Workflow-of-Tasks
engine so it becomes a normal scenario instead of a bespoke runner. The
release is today the single architectural outlier versus the single-engine
convergence: loadtest, e2e, and provisioning already run on the engine; the
release does not. Folding it in gives the existing TUI (`workflow.py`
`WorkflowStepState` tree + `_nested_detail_panel`) over the release for free
and lets loadtest/e2e inherit every robustness feature below.

## Non-goals

- No staged migration with adapters or interim compatibility shims. The
  release moves onto the engine in **one cut**: the engine gains the
  first-class concepts below, the release is rewritten as a scenario, and the
  bespoke runner (`tools/controlplane/src/controlplane_tool/release/run.py`)
  is deleted in the same PR — the same style as the single-engine
  convergence. "Fatto a pezzi con shim rimane sempre sporcizia" (user, 2026-07-22).
- No change to what the release *does* (phases, gates, publication order,
  performance-record semantics). This is a re-platforming, not a redesign of
  the release contract.
- Not this document's job: writing the implementation plan. This captures the
  engine capabilities the release requires and why each is needed. The plan
  and the SDK-side fixes (see Related work) are separate.

## Background

Every capability below already exists in the release runner in bespoke form.
The engine has phases/tasks but lacks the concepts that make the release
runner safe to resume, safe against flaky cloud infrastructure, and legible.
Each requirement is motivated by a concrete failure observed while getting
v0.17.0 to publish; those are cited inline so the rationale is not abstract.

## Requirements

### A. Durable execution (the resume / journal core)

**A1. Journaled operations with digest evidence.** Every step records the
artifacts it produced with their `sha256`, in an append-only audit. Resume
re-verifies recorded evidence against reality before skipping a step; a bare
"passed" flag is never trusted. This is the foundation — without it, none of
the resource/secret/retry features can be resumed safely.

**A2. Content-addressed inputs with automatic downstream invalidation.** A
step's inputs (guarded commit, release config, environment) are hashed; if any
input changes, that step and everything downstream invalidate automatically.
The release runner does this by hand today — every code fix during the v0.17.0
effort correctly re-triggered the run from the first affected phase.

**A4. Release from an immutable remote ref, not the local working tree.** The
release must not depend on the developer's local git state. Today it archives
the local working tree and guards on it being clean, which couples the release
to the workspace: any untracked file (a docs file), any hook-generated edit
(the GitNexus `AGENTS.md`/`CLAUDE.md` rewrites), or any stray change aborts the
run — even mid-flight. During the v0.17.0 effort the run failed at the
regression-gate re-check solely because a spec document had been created in the
working tree while the release was running. The source must instead come from
an immutable, pushed ref: fetch the target ref (a tag `vX.Y.Z` or a pinned
origin SHA) into a clean temporary location (`git archive <sha>` or a
throwaway worktree / shallow clone) and build from **that**. The guard becomes
"the prepared-version commit exists on origin and matches the requested
version", not "the local tree is clean". Payoffs: (a) the local workspace
becomes irrelevant — editing during a release is fine; (b) the released
artifact is exactly what is on origin — reproducible and auditable ("released
from the pushed ref, not my laptop"); (c) the entire clean-tree-guard failure
class disappears. Interaction with `release prepare`: the version-bump commit
must be pushed before the release, and the release targets that exact remote
SHA. This is small enough to land as a standalone improvement to the current
runner ahead of the full engine move.

**A3. Idempotency as a declared task property.** Tasks declare whether they are
safe to re-run. This is the precondition for both retry (D9) and resume (A1):
the engine may only retry/skip a task when its idempotency is known.

### B. Resource lifecycle

**B1. Resources-as-tasks.** Creating a VM (or a Buildx builder, or a registry
tunnel) is an ordinary idempotent "ensure" task, not special orchestration
code.

**B2. Finalizer / compensation semantics.** A teardown runs on scope exit
**regardless of outcome** (à la Airflow `all_done` / Temporal compensation).
This is the one genuinely new engine primitive the release needs. It yields:
- `--keep` becomes "skip finalizers", not bespoke plumbing.
- **Granular per-resource teardown** replaces the monolithic `tofu destroy`.
  The monolith is the direct cause of the "CanNotDelete lock saves the VMs but
  cleanup still destroys the NSGs → Standard-SKU public IPs then block all
  inbound" hole that had to be healed by hand (`tofu apply -var
  desired_state=running`) after nearly every failed run.

**B3. Declarative, phase-scoped resource requirements.** A phase declares the
resources it needs ("benchmark requires loadgen", "arm64-build requires the
arm-builder VM"); the engine creates them lazily right before the phase and
tears them down right after. During the v0.17.0 effort roughly ten failed
pre-benchmark cycles each provisioned, paid for, and destroyed a loadgen VM
that was never used.

**B4. Real state reconciliation in "ensure".** An ensure task must check the
resource's *actual* state, not its *desired* state, and reconcile. A
deallocated Azure VM was not restarted because the SDK's power state is
cosmetic (`output "vm_state" = var.desired_state`) and `ensure_running` trusted
it. The engine must not build on a "desired == actual" assumption. (The
underlying SDK fix is tracked separately; the engine-level requirement is the
reconciling contract.)

### C. Secrets

**C1. Phase-scoped credential staging.** A secret (GHCR token, cosign key) is
materialized onto the resource that needs it **only for the phases that use
it**, and is always deleted afterward — on success or failure. The engine
needs a first-class "this secret is live during phase X" concept; the release
already staged/deleted credentials per phase by hand.

### D. Robust remote execution (transport hardening)

**D1. Connection-death-aware exec with retry.** Keepalive to *detect* a dropped
connection (so it surfaces as an error in ~90s instead of hanging forever in
`recv_exit_status`), plus retry-on-idempotent with backoff to *recover* from a
transient drop. Built during v0.17.0 as keepalive in `azure-vm-sdk`
(`d77a877`) + retry in the release runner (`7c3c08fe`, `3a2dd846`). The
correct layering must be preserved: keepalive in the SDK (detects death),
retry in the engine's exec seam (recovers, knowing task idempotency A3) — the
SDK must not retry, because it cannot know whether a command is idempotent.
`exit -1` is paramiko's unambiguous "channel closed without exit status"
sentinel and is the retry trigger; real non-zero exits are never retried.

**D2. Bounded remote output.** Remote command output must be safe against the
SSH channel window (~2MB): a build emitting megabytes deadlocks — the writer
blocks, the command never exits. The release runner buffers output to a remote
file and returns a bounded tail; the engine's exec must do this structurally.

**D3. Fire-and-poll for long commands (evolution).** Long remote commands
should run detached (write exit-code + log files, orchestrator polls) so they
survive an orchestrator disconnect, instead of holding one SSH session open
for the length of a build. Motivated by the ~1h `docker run --rm` attach-stream
hang that had to be killed on the VM by hand.

**D4. Legible error propagation.** Surface the underlying command's stderr and
*which* operation failed; no silent retry loops; fail-fast on authorization
errors. Time was lost to bare "release publication command failed: skopeo
copy" and "…: sh" messages, and to a silent `az AuthorizationFailed` retry
loop that showed nothing on screen.

**D5. Transient cloud-error retry with backoff.** Cloud control-plane calls
that fail transiently (e.g. Azure's 180s `NicReservedForAnotherVm` reservation
after a delete) must retry with backoff instead of aborting cleanup.

### E. Observability

**E1. Per-phase and per-task structured progress.** A phase progress
bar/timer plus a nested sub-task tree — the `WorkflowStepState` /
`_nested_detail_panel` the TUI already renders. This delivers the TUI over the
release, which was the original motivation for putting the release on the
engine.

## Build order

1. **A (durable execution)** is the foundation; without journal + invalidation
   nothing below is resumable.
2. **B (resources + finalizers)** removes the bulk of the operational pain
   (lock/NSG healing, wasted loadgen provisioning); B2's finalizer primitive is
   the only truly new engine concept.
3. **D1 / D2** are already written in the runner — promote them to the engine's
   exec seam rather than rebuild.
4. **E1** is the final dividend (the TUI).

## Related work (out of this document's scope)

- **azure-vm-sdk power management is cosmetic** (B4 root cause). `start`/`stop`/
  `restart` flip a passthrough `desired_state` variable and do not touch Azure
  power; a deallocated VM cannot be restarted through the SDK. Real fix needs
  actual power management (approach A: `az vm start`/`deallocate` +
  `get-instance-view`; approach B: `azapi` PATCH on `powerState`). Owner
  sign-off required — it introduces a dependency into the tofu-only SDK.
- **Release-profile bump** for v0.18.0 (Standard_D8s_v5 already landed for
  v0.17.0; evaluating native-cell parallelism budget — one GraalVM
  native-image needs 11+ GiB).
- **Native ARM64 builder** (`Standard_D8ps_v5`) already replaced QEMU for the
  arm64 phases (measured ~5h → ~1h); this becomes a phase-scoped resource under
  B3 once the engine lands.
