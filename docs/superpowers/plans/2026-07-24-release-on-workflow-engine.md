# Release-on-Workflow-Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move `controlplane-tool release` onto the Workflow-of-Tasks engine: the engine gains journal/resume (A), phase-scoped resources with finalizers (B), phase-scoped secrets (C), and a hardened remote-exec seam (D); the release is rewritten as a `PhasedWorkflow` scenario, the bespoke runner `release/run.py` is deleted in the same PR, and the existing TUI renders the release (E).

**Architecture:** Engine capabilities land first as engine-only tasks in `tools/workflow-tasks` (each independently tested, no release behavior change — Tasks 1–8). The release cut follows in `tools/controlplane` (Tasks 9–12): phase actions are extracted to `release/phases.py`, the scenario is assembled in `release/scenario.py` on the new `PhasedWorkflow`, plan-building moves to `release/plan.py`, and `release/run.py` is deleted. Most of the "new" engine code is a **promotion** of code that already exists in bespoke form in `run.py`/`state.py`/`secrets.py` — move it, don't rebuild it.

**Tech Stack:** Python 3.12, uv workspaces (`tools/workflow-tasks`, `tools/controlplane`), pytest, typer CLI, rich TUI (`tui_toolkit` + `controlplane_tool.tui`), paramiko-backed VM providers from `azure-vm-sdk`/`multipass-sdk`/`proxmox-sdk`.

## Global Constraints

- **One cut, no shims** (spec non-goal): all tasks land on ONE feature branch (`feature/release-on-workflow-engine`) merged as one PR. Intermediate commits may keep `run.py` delegating to already-moved code (real final locations, not compatibility shims), but the merged PR contains no `run.py` and no adapter layer. "Fatto a pezzi con shim rimane sempre sporcizia."
- **No change to the release contract** (spec non-goal): same 15 phases in the same order (`source-tests, amd64-build, local-registry-push, benchmark-1..3, aggregate, regression-gate, arm64-build, arm64-smoke, publish-architectures, publish-manifests, publish-aliases, attest, finalize`), same gates, same publication order, same performance-record semantics, same journal JSON schema (`schemaVersion: 1`, entry key `"release"` with `sourceCommit`/`preparedVersion`/`releaseConfigDigest`/`environmentDigest`). A journal written by the old runner MUST resume under the new scenario.
- **Import boundary:** `workflow_tasks` must never import `controlplane_tool` (import-linter contract + `tools/workflow-tasks/tests/test_package_boundaries.py`). Moved engine modules must shed their `controlplane_tool` imports.
- **Layering (spec D1):** keepalive stays in `azure-vm-sdk` (already landed, `d77a877`); retry lives in the engine exec seam, gated on declared idempotency (A3). The SDK must not retry. `return_code == -1` is paramiko's "channel closed without exit status" sentinel and is the only exec-result retry trigger; real non-zero exits are never retried.
- **Test commands** (run from repo root; ALWAYS run BOTH full suites before each commit, even for engine-only changes — controlplane tests import workflow_tasks):
  - `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests -q`
  - `uv run --project tools/controlplane pytest tools/controlplane/tests -q`
- **GitNexus rules (project CLAUDE.md):** run `gitnexus_impact({target: "<symbol>", direction: "upstream"})` before modifying any existing symbol; run `gitnexus_detect_changes()` before every commit; use `gitnexus_rename` for symbol renames (`ReleaseJournal→Journal`, `ReleaseIdentity→RunIdentity`, `_ensure_vm→ensure_role_vm`). After the final commit run `npx gitnexus analyze`.
- **Commit messages: NO `Co-Authored-By` trailer** (user requirement; overrides the harness default).
- Package root realities: Python packages are `workflow_tasks` and `controlplane_tool`; 4-space indent; dataclasses with explicit fields; tests are plain pytest functions, no fixtures frameworks.

## File Structure

Engine — `tools/workflow-tasks/src/workflow_tasks/`:

| File | Responsibility | Change |
|---|---|---|
| `tasks/models.py` | task specs | add `idempotent` flag (A3) |
| `journal.py` | **new** — digest-evidence journal + `JournaledStep` (A1/A2), moved from `controlplane_tool/release/state.py` | create |
| `core/phase.py` | **new** — `Phase` + `PhasedWorkflow`: phase-scoped resources, finalizers (B2/B3) | create |
| `core/resource_task.py` | `probe` reconciliation contract (B4), value-carrying acquire + `context_resource` (C1) | modify |
| `infra/remote_exec.py` | **new** — `RemoteExec` (D1/D2/D4), `retry_transient` (D5), `run_detached` (D3), promoted from `run.py` | create |
| `infra/secrets.py` | **new** — credential staging, moved from `controlplane_tool/release/secrets.py` (C1) | create |
| `__init__.py` | export new names | modify |

Release — `tools/controlplane/src/controlplane_tool/`:

| File | Responsibility | Change |
|---|---|---|
| `release/phases.py` | **new** — all phase-action functions, moved from `run.py`, exec via `RemoteExec` | create |
| `release/scenario.py` | **new** — `build_release_workflow` + `run_release` on `PhasedWorkflow` | create |
| `release/plan.py` | **new** — plan/settings/git/lock, moved from `run.py` | create |
| `release/run.py` | bespoke runner | **delete** |
| `release/state.py`, `release/secrets.py` | promoted to engine | **delete** |
| `release/publish.py`, `release/attest.py` | `_exec` rerouted through `RemoteExec` (D4 fix at the cited failure sites) | modify |
| `cli/provisioning.py` | per-role ensure/destroy/bootstrap made public for reuse | modify |
| `cli/release.py` | `run` wired to `run_release`, `--tui`, progress sink (E1) | modify |

Tests move with their code: `tests/release/test_state.py` → `tools/workflow-tasks/tests/test_journal.py`; `tests/release/test_secrets.py` → `tools/workflow-tasks/tests/infra/test_secrets.py`; `tests/release/test_run_amd64.py` is adapted in place (its fakes and scenarios are the regression net for the cut — keep them alive, do not rewrite them from scratch).

---

### Task 1: A3 — idempotency as a declared task property

**Files:**
- Modify: `tools/workflow-tasks/src/workflow_tasks/tasks/models.py:15-33`
- Test: `tools/workflow-tasks/tests/tasks/test_models.py`

**Interfaces:**
- Produces: `CommandTaskSpec.idempotent: bool = False` — read by callers that route specs through `RemoteExec` (Task 5) and by the release phase commands (Task 9), which declare `idempotent=True` for every release remote command.

- [ ] **Step 1: Write the failing test** — append to `tools/workflow-tasks/tests/tasks/test_models.py`:

```python
def test_command_task_spec_declares_idempotency_defaulting_to_false() -> None:
    spec = CommandTaskSpec(task_id="t", summary="s", argv=("true",))
    assert spec.idempotent is False
    declared = CommandTaskSpec(task_id="t", summary="s", argv=("true",), idempotent=True)
    assert declared.idempotent is True
```

- [ ] **Step 2: Run it — must fail**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/tasks/test_models.py -q`
Expected: FAIL — `TypeError: __init__() got an unexpected keyword argument 'idempotent'`

- [ ] **Step 3: Implement** — in `tasks/models.py`, add one field to `CommandTaskSpec` after `timeout_seconds`:

```python
    timeout_seconds: int | None = None
    # A3: the engine may only retry/skip a task whose idempotency is declared.
    idempotent: bool = False
```

- [ ] **Step 4: Run both full suites — must pass**
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat(engine): declare task idempotency on CommandTaskSpec (A3)"`

---

### Task 2: A1/A2 — journal promoted to the engine, plus JournaledStep

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/journal.py` (moved from `tools/controlplane/src/controlplane_tool/release/state.py`)
- Create: `tools/workflow-tasks/tests/test_journal.py` (moved from `tools/controlplane/tests/release/test_state.py`)
- Delete: `tools/controlplane/src/controlplane_tool/release/state.py`, `tools/controlplane/tests/release/test_state.py`
- Modify: `tools/workflow-tasks/src/workflow_tasks/__init__.py`, every controlplane importer of `release.state` (find with `grep -rn "release.state\|release import state" tools/controlplane/src tools/controlplane/tests`; known importers: `release/run.py:54-59`, `release/publish.py:17`, `release/attest.py`, `release/metrics.py` — verify with the grep)

**Interfaces:**
- Produces (engine-public, exported from `workflow_tasks`):
  - `Journal(runs_directory: Path, identity: RunIdentity, *, phases: Sequence[str], artifact_digest: ArtifactDigest | None = None)` — was `ReleaseJournal`; `phases` becomes **required** (no engine-side default). Methods unchanged: `entries()`, `record(phase, *, artifacts=(), outcome="passed")`, `resume() -> ResumePlan`, property `state_directory`.
  - `RunIdentity(source_commit, prepared_version, release_config_digest, environment_digest)` — was `ReleaseIdentity`; same fields, same `as_entry()` JSON keys (`sourceCommit`, …). JSON entry key stays `"release"` — schema compatibility is non-negotiable.
  - `ArtifactEvidence(location, reference, digest)`, `ResumePlan`, `JournalCorruptionError`, `ResumeValidationError`, `digest_path(path) -> str` — unchanged.
  - `JournaledStep` (new, below).

- [ ] **Step 1: Move the module.** `git mv tools/controlplane/src/controlplane_tool/release/state.py tools/workflow-tasks/src/workflow_tasks/journal.py`, then edit `journal.py`:
  1. Delete `from controlplane_tool.release.versioning import normalize_version` and `DEFAULT_RELEASE_PHASES` (lines 18, 22-38 of the old file). Replace the version check in `__post_init__` (old lines 74-79):

```python
        if not _SEMVER.fullmatch(self.prepared_version):
            raise ResumeValidationError("prepared version must be a plain semantic version")
```

  with module constant `_SEMVER = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\Z")` (this is exactly the set `normalize_version` accepted after its "no container-tag prefix" check — behavior-identical).
  2. Rename with `gitnexus_rename` (dry-run first): `ReleaseJournal → Journal`, `ReleaseIdentity → RunIdentity`. Constructor signature: make `phases` a required keyword (delete the `= DEFAULT_RELEASE_PHASES` default).
  3. Update the module docstring to `"""Append-only, digest-verified journal for resumable workflow runs (A1/A2)."""`.
  4. Append `JournaledStep` at the end of `journal.py`:

```python
from workflow_tasks.workflow.reporting import skip as _report_skip  # place with the other imports


@dataclass
class JournaledStep:
    """One journal phase as a workflow task (A1).

    Skips (with a visible event) when verified resume evidence marks the
    phase reusable; otherwise records passed/failed evidence. A bare
    "passed" flag is never trusted — reuse decisions come exclusively from
    Journal.resume()'s digest verification, surfaced here via `reusable`.
    failure_injector is the fault-injection hook the release tests use
    (called with the phase name before the action and with
    "<phase>:after-action" after it).
    """

    task_id: str
    title: str
    journal: Journal
    phase: str
    action: Callable[[], Iterable[ArtifactEvidence]]
    reusable: Callable[[], frozenset[str]]
    failure_injector: Callable[[str], None] | None = None

    def run(self) -> None:
        if self.phase in self.reusable():
            _report_skip(f"{self.title} (verified evidence reused)")
            return
        try:
            if self.failure_injector is not None:
                self.failure_injector(self.phase)
            artifacts = tuple(self.action())
            if self.failure_injector is not None:
                self.failure_injector(f"{self.phase}:after-action")
        except BaseException:
            self.journal.record(self.phase, outcome="failed")
            raise
        self.journal.record(self.phase, artifacts=artifacts)
```

  (`Callable`, `Iterable`, `dataclass` are already imported at the top of the moved module — extend those imports if missing.)

- [ ] **Step 2: Move the tests.** `git mv tools/controlplane/tests/release/test_state.py tools/workflow-tasks/tests/test_journal.py`. Update imports to `from workflow_tasks.journal import ...`, rename the two classes throughout, and where the old tests used `DEFAULT_RELEASE_PHASES` define a module-level copy in the test file:

```python
PHASES = (
    "source-tests", "amd64-build", "local-registry-push",
    "benchmark-1", "benchmark-2", "benchmark-3",
    "aggregate", "regression-gate", "arm64-build", "arm64-smoke",
    "publish-architectures", "publish-manifests", "publish-aliases",
    "attest", "finalize",
)
```

  If any assertion checks the old error message `"prepared version must be a semantic version"` or `"must not use a container-tag prefix"`, update it to the new single message `"prepared version must be a plain semantic version"`.

- [ ] **Step 3: Add JournaledStep tests** — append to `tools/workflow-tasks/tests/test_journal.py`:

```python
def _identity() -> RunIdentity:
    return RunIdentity(
        source_commit="a" * 40,
        prepared_version="1.2.3",
        release_config_digest="sha256:" + "b" * 64,
        environment_digest="sha256:" + "c" * 64,
    )


def test_journaled_step_records_evidence_on_success(tmp_path: Path) -> None:
    journal = Journal(tmp_path, _identity(), phases=("only",))
    artifact_file = tmp_path / "artifact.json"
    artifact_file.write_text("{}\n", encoding="utf-8")
    evidence = ArtifactEvidence("local", str(artifact_file), digest_path(artifact_file))
    step = JournaledStep(
        task_id="t", title="only phase", journal=journal, phase="only",
        action=lambda: (evidence,), reusable=frozenset,
    )
    step.run()
    assert journal.entries()[-1]["outcome"] == "passed"


def test_journaled_step_records_failure_and_reraises(tmp_path: Path) -> None:
    journal = Journal(tmp_path, _identity(), phases=("only",))

    def explode() -> tuple[ArtifactEvidence, ...]:
        raise RuntimeError("boom")

    step = JournaledStep(
        task_id="t", title="only phase", journal=journal, phase="only",
        action=explode, reusable=frozenset,
    )
    with pytest.raises(RuntimeError, match="boom"):
        step.run()
    assert journal.entries()[-1]["outcome"] == "failed"


def test_journaled_step_skips_reusable_phase_without_touching_the_journal(tmp_path: Path) -> None:
    journal = Journal(tmp_path, _identity(), phases=("only",))
    step = JournaledStep(
        task_id="t", title="only phase", journal=journal, phase="only",
        action=lambda: (_ for _ in ()).throw(AssertionError("must not run")),
        reusable=lambda: frozenset({"only"}),
    )
    step.run()
    assert journal.entries() == ()
```

- [ ] **Step 4: Update controlplane importers.** For every file found by the grep in the header, replace `from controlplane_tool.release.state import X` with `from workflow_tasks.journal import X` and apply the two renames (`ReleaseJournal→Journal`, `ReleaseIdentity→RunIdentity`). In `run.py`, `ReleaseJournal(plan.journal_root, plan.identity, phases=RELEASE_PHASES, ...)` becomes `Journal(...)` — `phases` is already passed explicitly there, so the required-kwarg change is free.
- [ ] **Step 5: Export from the engine.** Add to `tools/workflow-tasks/src/workflow_tasks/__init__.py`:

```python
from workflow_tasks.journal import (
    ArtifactEvidence,
    Journal,
    JournalCorruptionError,
    JournaledStep,
    ResumePlan,
    ResumeValidationError,
    RunIdentity,
    digest_path,
)
```

  and the same names to `__all__`. Add to `tools/workflow-tasks/tests/test_public_api.py`:

```python
def test_public_api_exports_journal() -> None:
    assert hasattr(workflow_tasks, "Journal")
    assert hasattr(workflow_tasks, "JournaledStep")
    assert hasattr(workflow_tasks, "RunIdentity")
    assert hasattr(workflow_tasks, "ArtifactEvidence")
    assert hasattr(workflow_tasks, "digest_path")
```

- [ ] **Step 6: Run both full suites — must pass** (this proves the move broke no runner behavior; `test_run_amd64.py` still exercises the journal through `run.py`).
- [ ] **Step 7: Commit** — `git commit -m "feat(engine): promote the release journal to workflow_tasks.journal with JournaledStep (A1/A2)"`

---

### Task 3: B2/B3 — Phase and PhasedWorkflow

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/core/phase.py`
- Modify: `tools/workflow-tasks/src/workflow_tasks/core/__init__.py`, `tools/workflow-tasks/src/workflow_tasks/__init__.py`
- Test: `tools/workflow-tasks/tests/core/test_phase.py`

**Interfaces:**
- Consumes: `Task` protocol (`core/task.py`), `ResourceTask` (`core/resource_task.py`), `workflow_step` (`workflow/reporting.py`).
- Produces:
  - `Phase(phase_id: str, title: str, tasks: list[Task], resources: list[ResourceTask] = [])`
  - `PhasedWorkflow(phases: list[Phase], keep: bool = False)` with `run() -> None`. Semantics: a resource is acquired right before the first phase that lists it and released right after the last phase that lists it (LIFO); on failure everything still-acquired is released immediately (LIFO); `keep=True` skips finalizers of resources with `infrastructure=True` only (leases such as staged secrets are ALWAYS released — C1); release errors are collected and reported like `Workflow.run` does.

- [ ] **Step 1: Write the failing tests** — create `tools/workflow-tasks/tests/core/test_phase.py`:

```python
from __future__ import annotations

from dataclasses import dataclass, field

import pytest

from workflow_tasks.core.phase import Phase, PhasedWorkflow
from workflow_tasks.core.resource_task import ResourceTask


@dataclass
class _Recorder:
    events: list[str] = field(default_factory=list)

    def task(self, task_id: str, *, fail: bool = False):
        recorder = self

        @dataclass
        class _Task:
            task_id: str
            title: str

            def run(self) -> None:
                recorder.events.append(f"run:{self.task_id}")
                if fail:
                    raise RuntimeError(f"{self.task_id} exploded")

        return _Task(task_id=task_id, title=task_id)

    def resource(self, task_id: str, *, infrastructure: bool = False, fail_release: bool = False) -> ResourceTask:
        def release() -> None:
            self.events.append(f"release:{task_id}")
            if fail_release:
                raise RuntimeError(f"{task_id} release exploded")

        return ResourceTask(
            task_id=task_id,
            title=task_id,
            acquire=lambda: self.events.append(f"acquire:{task_id}"),
            release=release,
            infrastructure=infrastructure,
        )


def test_resources_are_acquired_lazily_and_released_after_last_use() -> None:
    recorder = _Recorder()
    loadgen = recorder.resource("loadgen")
    workflow = PhasedWorkflow(
        phases=[
            Phase(phase_id="build", title="build", tasks=[recorder.task("build.t")]),
            Phase(phase_id="bench-1", title="bench-1", tasks=[recorder.task("b1.t")], resources=[loadgen]),
            Phase(phase_id="bench-2", title="bench-2", tasks=[recorder.task("b2.t")], resources=[loadgen]),
            Phase(phase_id="publish", title="publish", tasks=[recorder.task("pub.t")]),
        ]
    )
    workflow.run()
    assert recorder.events == [
        "run:build.t",
        "acquire:loadgen", "run:b1.t", "run:b2.t", "release:loadgen",
        "run:pub.t",
    ]


def test_failure_releases_everything_acquired_in_lifo_order() -> None:
    recorder = _Recorder()
    first = recorder.resource("first")
    second = recorder.resource("second")
    workflow = PhasedWorkflow(
        phases=[
            Phase(phase_id="a", title="a", tasks=[recorder.task("a.t")], resources=[first]),
            Phase(phase_id="b", title="b", tasks=[recorder.task("b.t", fail=True)], resources=[first, second]),
        ]
    )
    with pytest.raises(RuntimeError, match="b.t exploded"):
        workflow.run()
    assert recorder.events == [
        "acquire:first", "run:a.t",
        "acquire:second", "run:b.t",
        "release:second", "release:first",
    ]


def test_keep_skips_only_infrastructure_finalizers() -> None:
    recorder = _Recorder()
    vm = recorder.resource("vm", infrastructure=True)
    lease = recorder.resource("lease")
    workflow = PhasedWorkflow(
        phases=[Phase(phase_id="p", title="p", tasks=[recorder.task("p.t")], resources=[vm, lease])],
        keep=True,
    )
    workflow.run()
    assert "release:lease" in recorder.events
    assert "release:vm" not in recorder.events


def test_release_errors_are_collected_and_reported() -> None:
    recorder = _Recorder()
    broken = recorder.resource("broken", fail_release=True)
    workflow = PhasedWorkflow(
        phases=[Phase(phase_id="p", title="p", tasks=[recorder.task("p.t")], resources=[broken])]
    )
    with pytest.raises(RuntimeError, match="Cleanup failed"):
        workflow.run()


def test_release_errors_are_appended_to_the_main_error() -> None:
    recorder = _Recorder()
    broken = recorder.resource("broken", fail_release=True)
    workflow = PhasedWorkflow(
        phases=[Phase(phase_id="p", title="p", tasks=[recorder.task("p.t", fail=True)], resources=[broken])]
    )
    with pytest.raises(RuntimeError, match="Cleanup errors"):
        workflow.run()
```

- [ ] **Step 2: Run — must fail** with `ModuleNotFoundError: No module named 'workflow_tasks.core.phase'`.
- [ ] **Step 3: Implement** — create `tools/workflow-tasks/src/workflow_tasks/core/phase.py`:

```python
from __future__ import annotations

from dataclasses import dataclass, field

from workflow_tasks.core.resource_task import ResourceTask
from workflow_tasks.core.task import Task
from workflow_tasks.workflow.reporting import workflow_step


@dataclass
class Phase:
    """A named group of tasks plus the resources the phase requires (B3).

    Resources are acquired lazily right before the first phase that lists
    them and released right after the last phase that lists them.
    """

    phase_id: str
    title: str
    tasks: list[Task]
    resources: list[ResourceTask] = field(default_factory=list)


@dataclass
class PhasedWorkflow:
    """Sequential phase executor with phase-scoped resource lifecycle (B2/B3).

    Finalizers run on scope exit regardless of outcome. keep=True skips
    finalizers of resources flagged infrastructure=True only; other
    resources (e.g. staged-secret leases) are always released (C1).
    """

    phases: list[Phase]
    keep: bool = False

    def run(self) -> None:
        last_use: dict[int, int] = {}
        for index, phase in enumerate(self.phases):
            for resource in phase.resources:
                last_use[id(resource)] = index

        acquired: list[ResourceTask] = []
        cleanup_errors: list[str] = []
        main_error: BaseException | None = None

        for index, phase in enumerate(self.phases):
            try:
                with workflow_step(task_id=phase.phase_id, title=phase.title):
                    for resource in phase.resources:
                        if any(resource is held for held in acquired):
                            continue
                        with workflow_step(task_id=resource.task_id, title=resource.title):
                            resource.run()
                            self._verify_probe(resource)
                        acquired.append(resource)
                    for task in phase.tasks:
                        with workflow_step(task_id=task.task_id, title=task.title):
                            task.run()
            except BaseException as exc:
                main_error = exc
                break
            expiring = [r for r in acquired if last_use[id(r)] == index]
            cleanup_errors.extend(self._release(expiring, acquired))

        cleanup_errors.extend(self._release(list(acquired), acquired))

        if main_error is not None:
            if cleanup_errors:
                combined = f"{main_error}\n\nCleanup errors:\n" + "\n".join(cleanup_errors)
                raise RuntimeError(combined) from main_error
            raise main_error
        if cleanup_errors:
            raise RuntimeError("Cleanup failed:\n" + "\n".join(cleanup_errors))

    def _verify_probe(self, resource: ResourceTask) -> None:
        # Implemented in Task 4 (B4); a no-op until ResourceTask grows `probe`.
        return None

    def _release(self, targets: list[ResourceTask], acquired: list[ResourceTask]) -> list[str]:
        errors: list[str] = []
        for resource in reversed(targets):
            acquired.remove(resource)
            if self.keep and resource.infrastructure:
                continue
            try:
                with workflow_step(task_id=resource.cleanup_task_id, title=resource.cleanup_title):
                    resource.cleanup()
            except Exception as exc:
                errors.append(f"{resource.cleanup_task_id}: {exc}")
        return errors
```

  Note: `acquired.remove(resource)` and the `any(resource is held ...)` membership test use object identity semantics deliberately — `ResourceTask` instances are compared by identity here because the same object listed in several phases is one resource. (`list.remove` uses `==`; `ResourceTask` is a plain dataclass whose `acquire`/`release` closures differ per instance, so equality collapses to identity in practice. Keep the `is`-based guard for acquisition to make the intent explicit.)

- [ ] **Step 4: Wire exports.** `core/__init__.py`: add `from workflow_tasks.core.phase import Phase, PhasedWorkflow`. Top-level `__init__.py`: add the same import plus `"Phase", "PhasedWorkflow"` to `__all__`. Extend `tests/test_public_api.py`:

```python
def test_public_api_exports_phased_workflow() -> None:
    assert hasattr(workflow_tasks, "Phase")
    assert hasattr(workflow_tasks, "PhasedWorkflow")
```

- [ ] **Step 5: Run both full suites — must pass.**
- [ ] **Step 6: Commit** — `git commit -m "feat(engine): Phase and PhasedWorkflow with phase-scoped resources and finalizers (B2/B3)"`

---

### Task 4: B4 — the reconciling-ensure contract (probe)

**Files:**
- Modify: `tools/workflow-tasks/src/workflow_tasks/core/resource_task.py`, `tools/workflow-tasks/src/workflow_tasks/core/phase.py` (`_verify_probe`)
- Test: `tools/workflow-tasks/tests/core/test_phase.py`

**Interfaces:**
- Produces: `ResourceTask.probe: Callable[[], bool] | None = None`. After `PhasedWorkflow` acquires a resource that declares a probe, the probe MUST report the *actual* state as ready; otherwise the run fails naming the resource. This encodes the engine-level rule "never build on desired == actual" (the `vm_state = var.desired_state` hole); the SDK-side power-management fix is tracked separately.

- [ ] **Step 1: Write the failing tests** — append to `tests/core/test_phase.py`:

```python
def test_probe_failure_after_acquire_fails_the_run_naming_the_resource() -> None:
    recorder = _Recorder()
    resource = recorder.resource("vm")
    resource.probe = lambda: False
    workflow = PhasedWorkflow(
        phases=[Phase(phase_id="p", title="p", tasks=[recorder.task("p.t")], resources=[resource])]
    )
    with pytest.raises(RuntimeError, match="vm.*actual"):
        workflow.run()
    assert "run:p.t" not in recorder.events
    assert "release:vm" in recorder.events  # acquired ⇒ finalized even on probe failure


def test_probe_success_lets_the_phase_run() -> None:
    recorder = _Recorder()
    resource = recorder.resource("vm")
    resource.probe = lambda: True
    workflow = PhasedWorkflow(
        phases=[Phase(phase_id="p", title="p", tasks=[recorder.task("p.t")], resources=[resource])]
    )
    workflow.run()
    assert "run:p.t" in recorder.events
```

- [ ] **Step 2: Run — must fail** (`probe` is not a `ResourceTask` field; slots forbid the assignment): `AttributeError`.
- [ ] **Step 3: Implement.**
  1. `core/resource_task.py` — add the field after `infrastructure` (keep `slots=True`; note the test assigns `resource.probe = ...` post-construction, which slots allow because `probe` is a declared field):

```python
    infrastructure: bool = False
    # B4: reports the resource's ACTUAL state. When set, the engine verifies
    # it right after acquire — an ensure that trusts desired state fails here.
    probe: Callable[[], bool] | None = None
```

  2. `core/phase.py` — but the probe failure must count the resource as acquired (finalizer must run). Replace the acquisition block and `_verify_probe`:

```python
                    for resource in phase.resources:
                        if any(resource is held for held in acquired):
                            continue
                        with workflow_step(task_id=resource.task_id, title=resource.title):
                            resource.run()
                            acquired.append(resource)
                            self._verify_probe(resource)
```

```python
    def _verify_probe(self, resource: ResourceTask) -> None:
        if resource.probe is not None and not resource.probe():
            raise RuntimeError(
                f"resource {resource.task_id} did not reach its actual desired state after ensure (B4)"
            )
```

  Check `test_failure_releases_everything_acquired_in_lifo_order` still passes (append inside the step now happens before task failures, same observable order).

- [ ] **Step 4: Run both full suites — must pass.**
- [ ] **Step 5: Commit** — `git commit -m "feat(engine): post-ensure probe verification on resources (B4)"`

---

### Task 5: D1/D2/D4 — the hardened RemoteExec seam (promoted from run.py)

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/infra/remote_exec.py`
- Modify: `tools/workflow-tasks/src/workflow_tasks/__init__.py`
- Test: `tools/workflow-tasks/tests/infra/test_remote_exec.py`

**Interfaces:**
- Consumes: provider duck-type `exec_argv(request, argv, *, env, cwd, dry_run)` / `transfer_to(request, *, source, destination)` returning objects with `return_code`/`stdout`/`stderr`; `workflow_log` from `workflow/reporting.py` (retries are VISIBLE events — D4's "no silent retry loops").
- Produces (engine-public):
  - `CONNECTION_DEAD = -1`
  - `class RemoteCommandError(RuntimeError)` with attributes `describe`, `return_code`; message `"{describe} failed (exit {rc}): {detail or 'no output'}"` — carries both *which operation* and *the underlying stderr* (fixes the cited "release publication command failed: skopeo copy" / "…: sh" bare messages).
  - `require_success(result, describe) -> result`
  - `RemoteExec(provider, request, attempts=4, sleep=time.sleep)` with:
    - `run(argv, *, cwd=None, env=None, bounded=False, idempotent=False, describe=None) -> result`
    - `transfer_to(*, source: Path, destination: str, describe=None) -> result`
- Source material being promoted (delete from `run.py` in the same step, redirect `run.py` call sites): `_provider_exec` (`run.py:2029-2059`), `_provider_transfer_to` (`run.py:2062-2076`), `_retry_on_connection_death` (`run.py:2079-2105`), `_require_result` (`run.py:2108-2113`).

- [ ] **Step 1: Write the failing tests** — create `tools/workflow-tasks/tests/infra/test_remote_exec.py`:

```python
from __future__ import annotations

from dataclasses import dataclass

import pytest

from workflow_tasks.infra.remote_exec import (
    CONNECTION_DEAD,
    RemoteCommandError,
    RemoteExec,
)


@dataclass
class _Result:
    return_code: int
    stdout: str = ""
    stderr: str = ""


class _Provider:
    def __init__(self, results: list[object]) -> None:
        self.results = list(results)
        self.calls: list[tuple[str, ...]] = []

    def exec_argv(self, request, argv, *, env, cwd, dry_run):
        self.calls.append(tuple(argv))
        outcome = self.results.pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        return outcome

    def transfer_to(self, request, *, source, destination):
        outcome = self.results.pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        return outcome


def _exec(provider: _Provider) -> RemoteExec:
    return RemoteExec(provider=provider, request=object(), sleep=lambda _: None)


def test_idempotent_run_retries_a_dropped_connection() -> None:
    provider = _Provider([_Result(CONNECTION_DEAD), _Result(0, stdout="ok")])
    result = _exec(provider).run(("true",), idempotent=True, describe="probe")
    assert result.stdout == "ok"
    assert len(provider.calls) == 2


def test_non_idempotent_run_never_retries_a_dropped_connection() -> None:
    provider = _Provider([_Result(CONNECTION_DEAD), _Result(0)])
    with pytest.raises(RemoteCommandError, match="exit -1"):
        _exec(provider).run(("start-build",), idempotent=False, describe="start build")
    assert len(provider.calls) == 1


def test_real_nonzero_exit_is_never_retried_and_error_names_operation_and_stderr() -> None:
    provider = _Provider([_Result(3, stderr="denied: token expired")])
    with pytest.raises(RemoteCommandError) as info:
        _exec(provider).run(("skopeo", "copy"), idempotent=True, describe="skopeo copy amd64")
    assert "skopeo copy amd64" in str(info.value)
    assert "denied: token expired" in str(info.value)
    assert len(provider.calls) == 1


def test_run_gives_up_after_exhausting_attempts() -> None:
    provider = _Provider([_Result(CONNECTION_DEAD)] * 4)
    with pytest.raises(RemoteCommandError, match="exit -1"):
        _exec(provider).run(("true",), idempotent=True, describe="probe")
    assert len(provider.calls) == 4


def test_connect_time_exception_is_retried_only_when_idempotent() -> None:
    provider = _Provider([OSError("connection reset"), _Result(0, stdout="ok")])
    result = _exec(provider).run(("true",), idempotent=True, describe="probe")
    assert result.stdout == "ok"

    failing = _Provider([OSError("connection reset")])
    with pytest.raises(OSError):
        _exec(failing).run(("start",), idempotent=False, describe="start")


def test_bounded_run_wraps_the_command_in_a_remote_tail() -> None:
    provider = _Provider([_Result(0, stdout="tail")])
    _exec(provider).run(("./gradlew", "test"), bounded=True, idempotent=True, describe="tests")
    (argv,) = provider.calls
    assert argv[0] == "sh" and argv[1] == "-c"
    assert "./gradlew test" in argv[2]
    assert "tail -c 65536" in argv[2]


def test_transfer_retries_a_dropped_connection(tmp_path) -> None:
    source = tmp_path / "f"
    source.write_text("x", encoding="utf-8")
    provider = _Provider([_Result(CONNECTION_DEAD), _Result(0)])
    _exec(provider).transfer_to(source=source, destination="/tmp/f")
    assert provider.results == []
```

- [ ] **Step 2: Run — must fail** with `ModuleNotFoundError`.
- [ ] **Step 3: Implement** — create `tools/workflow-tasks/src/workflow_tasks/infra/remote_exec.py`:

```python
"""Hardened remote execution seam (D1/D2/D4).

Layering (spec D1): keepalive lives in the SSH SDK and turns a dead
connection into a fast, unambiguous failure; retry lives HERE, gated on the
caller's declared idempotency (A3), because only the orchestrator can know
whether re-running a command is safe. The SDK must not retry.
"""

from __future__ import annotations

from collections.abc import Callable, Sequence
from dataclasses import dataclass, field
from pathlib import Path
import shlex
import time
from typing import Any

from workflow_tasks.workflow.reporting import workflow_log

# paramiko's "channel closed without an exit status" sentinel — never a real
# shell exit code. The only exec-result condition that triggers a retry.
CONNECTION_DEAD = -1
_BOUNDED_LOG = "/tmp/workflow-exec.log"
_TAIL_BYTES = 65536


class RemoteCommandError(RuntimeError):
    """A remote operation failed; the message says which one and why (D4)."""

    def __init__(self, describe: str, return_code: int, detail: str) -> None:
        self.describe = describe
        self.return_code = return_code
        super().__init__(
            f"{describe} failed (exit {return_code}): {detail.strip() or 'no output'}"
        )


def require_success(result: Any, describe: str) -> Any:
    return_code = int(getattr(result, "return_code", 0))
    if return_code != 0:
        detail = str(getattr(result, "stderr", "") or getattr(result, "stdout", ""))
        raise RemoteCommandError(describe, return_code, detail)
    return result


@dataclass
class RemoteExec:
    """All scenario remote traffic for one (provider, request) pair."""

    provider: Any
    request: Any
    attempts: int = 4
    sleep: Callable[[float], None] = field(default=time.sleep, repr=False)

    def run(
        self,
        argv: Sequence[str],
        *,
        cwd: str | None = None,
        env: dict[str, str] | None = None,
        bounded: bool = False,
        idempotent: bool = False,
        describe: str | None = None,
    ) -> Any:
        label = describe or shlex.join(tuple(argv)[:3])
        command = tuple(argv)
        if bounded:
            # D2: the SSH executor waits for the exit status before draining
            # output, so a command whose output exceeds the channel window
            # (~2MB) deadlocks — the remote writer blocks and the command
            # never exits. Buffer remotely and return only a bounded tail;
            # stderr folds into stdout. Commands whose stdout is parsed must
            # NOT be bounded.
            script = shlex.join(command)
            command = (
                "sh",
                "-c",
                "{ " + script + " ; } >" + _BOUNDED_LOG + " 2>&1; "
                f"ec=$?; tail -c {_TAIL_BYTES} {_BOUNDED_LOG}; exit $ec",
            )
        result = self._with_connection_retry(
            lambda: self.provider.exec_argv(
                self.request, command, env=env, cwd=cwd, dry_run=False
            ),
            describe=label,
            idempotent=idempotent,
        )
        return require_success(result, label)

    def transfer_to(
        self, *, source: Path, destination: str, describe: str | None = None
    ) -> Any:
        label = describe or f"transfer {source.name}"
        result = self._with_connection_retry(
            lambda: self.provider.transfer_to(
                self.request, source=source, destination=destination
            ),
            describe=label,
            # a file transfer is a content-addressed overwrite: always safe
            idempotent=True,
        )
        return require_success(result, label)

    def _with_connection_retry(
        self, operation: Callable[[], Any], *, describe: str, idempotent: bool
    ) -> Any:
        for attempt in range(1, self.attempts + 1):
            last = attempt == self.attempts
            try:
                result = operation()
            except Exception as error:  # noqa: BLE001 - transport failure, not command failure
                if last or not idempotent:
                    raise
                workflow_log(
                    f"⟳ {describe}: connection error ({error}); retry {attempt}/{self.attempts - 1}",
                    stream="stderr",
                )
                self.sleep(min(5 * attempt, 30))
                continue
            if (
                int(getattr(result, "return_code", 0)) == CONNECTION_DEAD
                and idempotent
                and not last
            ):
                workflow_log(
                    f"⟳ {describe}: connection dropped; retry {attempt}/{self.attempts - 1}",
                    stream="stderr",
                )
                self.sleep(min(5 * attempt, 30))
                continue
            return result
        raise AssertionError("unreachable")  # pragma: no cover
```

- [ ] **Step 4: Run the new tests — must pass.** Then export `RemoteExec`, `RemoteCommandError`, `require_success`, `CONNECTION_DEAD` from `workflow_tasks/__init__.py` (+ `__all__`) and add to `test_public_api.py`:

```python
def test_public_api_exports_remote_exec() -> None:
    assert hasattr(workflow_tasks, "RemoteExec")
    assert hasattr(workflow_tasks, "RemoteCommandError")
```

- [ ] **Step 5: Redirect run.py (delete the promoted helpers).** In `release/run.py`: delete `_provider_exec`, `_provider_transfer_to`, `_retry_on_connection_death`, `_require_result` (lines 2029-2113) and replace every call:
  - `_provider_exec(provider, request, argv, cwd=..., bounded=...)` → `RemoteExec(provider, request).run(argv, cwd=..., bounded=..., idempotent=True)` — every release remote operation is idempotent (tests, digest-pinned builds/pushes/transfers, `mkdir -p`), which is exactly what the old docstring asserted. Practical shape: at the top of `_run_amd64_release_locked` build `exec_stack = RemoteExec(provider, stack_request)` / `exec_arm = RemoteExec(provider, arm_request)` and thread them to the helpers instead of `(provider, request)` pairs where convenient — or, lazier and fine for this intermediate state, construct `RemoteExec(provider, request)` inline inside the old helper signatures. Keep the diff minimal; Task 9 restructures these signatures properly.
  - `_provider_transfer_to(provider, request, source=..., destination=..., action=...)` → `RemoteExec(provider, request).transfer_to(source=..., destination=..., describe=...)`.
  - `_require_result(x, action)` (still used by teardown and ansible-result checks) → `require_success(x, action)` imported from `workflow_tasks.infra.remote_exec`.
  - `secrets.py` is untouched here (moves in Task 8).
- [ ] **Step 6: Delete the now-redundant retry unit tests from `tests/release/test_run_amd64.py`** (`_FlakyProvider`, `_FlakyTransferProvider` and the four tests at lines 460-535: `test_provider_exec_retries_on_dropped_connection`, `test_provider_exec_does_not_retry_a_real_nonzero_exit`, `test_provider_exec_gives_up_after_exhausting_retries`, `test_provider_transfer_to_retries_on_dropped_connection`) — their behavior is now covered by the engine tests written in Step 1.
- [ ] **Step 7: Run both full suites — must pass.**
- [ ] **Step 8: Commit** — `git commit -m "feat(engine): promote connection-death retry and bounded exec to RemoteExec (D1/D2/D4)"`

---

### Task 6: D5 — transient cloud-error retry with backoff

**Files:**
- Modify: `tools/workflow-tasks/src/workflow_tasks/infra/remote_exec.py`, `tools/workflow-tasks/src/workflow_tasks/__init__.py`
- Test: `tools/workflow-tasks/tests/infra/test_remote_exec.py`

**Interfaces:**
- Produces: `retry_transient(operation, *, describe, is_transient, attempts=6, base_delay=15.0, sleep=time.sleep) -> Any`. Delay schedule `min(base_delay * attempt, 60)` = 15+30+45+60+60 ≈ 210s across 6 attempts — covers Azure's 180s `NicReservedForAnotherVm` reservation. The `is_transient` predicate is the caller's; it MUST NOT match authorization failures (D4 fail-fast — the silent `az AuthorizationFailed` loop is the anti-pattern this replaces).

- [ ] **Step 1: Write the failing tests** — append to `tests/infra/test_remote_exec.py`:

```python
from workflow_tasks.infra.remote_exec import retry_transient


def test_retry_transient_retries_matching_errors_with_backoff() -> None:
    attempts: list[int] = []
    delays: list[float] = []

    def operation() -> str:
        attempts.append(1)
        if len(attempts) < 3:
            raise RuntimeError("NicReservedForAnotherVm: retry later")
        return "done"

    result = retry_transient(
        operation,
        describe="recreate stack VM",
        is_transient=lambda error: "NicReservedForAnotherVm" in str(error),
        sleep=delays.append,
    )
    assert result == "done"
    assert delays == [15.0, 30.0]


def test_retry_transient_fails_fast_on_non_transient_errors() -> None:
    def operation() -> None:
        raise PermissionError("AuthorizationFailed")

    with pytest.raises(PermissionError):
        retry_transient(
            operation,
            describe="recreate stack VM",
            is_transient=lambda error: "NicReservedForAnotherVm" in str(error),
            sleep=lambda _: None,
        )


def test_retry_transient_gives_up_after_attempts() -> None:
    def operation() -> None:
        raise RuntimeError("NicReservedForAnotherVm")

    with pytest.raises(RuntimeError, match="NicReserved"):
        retry_transient(
            operation,
            describe="recreate stack VM",
            is_transient=lambda error: True,
            attempts=3,
            sleep=lambda _: None,
        )
```

- [ ] **Step 2: Run — must fail** (`ImportError: cannot import name 'retry_transient'`).
- [ ] **Step 3: Implement** — append to `remote_exec.py`:

```python
def retry_transient(
    operation: Callable[[], Any],
    *,
    describe: str,
    is_transient: Callable[[Exception], bool],
    attempts: int = 6,
    base_delay: float = 15.0,
    sleep: Callable[[float], None] = time.sleep,
) -> Any:
    """Retry a cloud control-plane call while its failure is transient (D5).

    Every retry is a visible workflow event — never a silent loop (D4). The
    predicate must not match authorization errors: those fail fast.
    """
    for attempt in range(1, attempts + 1):
        try:
            return operation()
        except Exception as error:
            if attempt == attempts or not is_transient(error):
                raise
            workflow_log(
                f"⟳ {describe}: transient error ({error}); retry {attempt}/{attempts - 1}",
                stream="stderr",
            )
            sleep(min(base_delay * attempt, 60.0))
    raise AssertionError("unreachable")  # pragma: no cover
```

- [ ] **Step 4: Export `retry_transient` from `workflow_tasks/__init__.py`; run both full suites — must pass.**
- [ ] **Step 5: Commit** — `git commit -m "feat(engine): retry_transient with backoff for cloud control-plane calls (D5)"`

---

### Task 7: D3 — fire-and-poll for long remote commands

**Files:**
- Modify: `tools/workflow-tasks/src/workflow_tasks/infra/remote_exec.py`
- Test: `tools/workflow-tasks/tests/infra/test_remote_exec.py`

**Interfaces:**
- Produces: `RemoteExec.run_detached(argv, *, cwd=None, describe, idempotent=False, poll_interval=15.0, timeout=7200.0, clock=time.monotonic) -> str` (returns the bounded log tail). The command runs under `nohup`, writing `<base>.log` and `<base>.ec` (exit code); the orchestrator polls for the exit-code file. An orchestrator disconnect mid-build no longer kills or hangs the build (the ~1h `docker run --rm` attach-stream hang). Polls and log collection are idempotent and therefore connection-retry-safe; only the (quick) start command is gated on the caller's `idempotent`.

- [ ] **Step 1: Write the failing tests** — append to `tests/infra/test_remote_exec.py`:

```python
class _DetachedProvider:
    """Simulates: start returns immediately; first poll empty; second poll has the exit code."""

    def __init__(self, exit_code: int) -> None:
        self.exit_code = exit_code
        self.calls: list[str] = []

    def exec_argv(self, request, argv, *, env, cwd, dry_run):
        script = argv[2] if argv[0] == "sh" else " ".join(argv)
        self.calls.append(script)
        if "nohup" in script:
            return _Result(0)
        if ".ec" in script and "cat" in script:
            polls = sum(1 for call in self.calls if "cat" in call)
            return _Result(0, stdout="" if polls == 1 else f"{self.exit_code}\n")
        if "tail" in script:
            return _Result(0, stdout="build output tail")
        return _Result(0)


def test_run_detached_polls_until_exit_code_and_returns_log_tail() -> None:
    provider = _DetachedProvider(exit_code=0)
    output = RemoteExec(provider=provider, request=object(), sleep=lambda _: None).run_detached(
        ("docker", "buildx", "bake"), describe="arm64 bake", poll_interval=0.0
    )
    assert output == "build output tail"
    assert any("nohup" in call for call in provider.calls)


def test_run_detached_raises_legibly_on_remote_failure() -> None:
    provider = _DetachedProvider(exit_code=17)
    with pytest.raises(RemoteCommandError) as info:
        RemoteExec(provider=provider, request=object(), sleep=lambda _: None).run_detached(
            ("docker", "buildx", "bake"), describe="arm64 bake", poll_interval=0.0
        )
    assert "arm64 bake" in str(info.value) and "17" in str(info.value)


def test_run_detached_times_out_with_the_log_location() -> None:
    class _NeverFinishes(_DetachedProvider):
        def exec_argv(self, request, argv, *, env, cwd, dry_run):
            script = argv[2] if argv[0] == "sh" else " ".join(argv)
            self.calls.append(script)
            if "cat" in script:
                return _Result(0, stdout="")
            return _Result(0)

    ticks = iter([0.0, 1.0, 100.0, 200.0])
    with pytest.raises(TimeoutError, match="arm64 bake"):
        RemoteExec(provider=_NeverFinishes(0), request=object(), sleep=lambda _: None).run_detached(
            ("docker", "buildx", "bake"),
            describe="arm64 bake",
            poll_interval=0.0,
            timeout=50.0,
            clock=lambda: next(ticks),
        )
```

- [ ] **Step 2: Run — must fail** (`AttributeError: 'RemoteExec' object has no attribute 'run_detached'`).
- [ ] **Step 3: Implement** — add to `RemoteExec` (plus `from uuid import uuid4` at the top of the module):

```python
    def run_detached(
        self,
        argv: Sequence[str],
        *,
        cwd: str | None = None,
        describe: str,
        idempotent: bool = False,
        poll_interval: float = 15.0,
        timeout: float = 7200.0,
        clock: Callable[[], float] = time.monotonic,
    ) -> str:
        """Fire-and-poll for long commands (D3): the command runs detached
        under nohup writing exit-code and log files, so it survives an
        orchestrator disconnect; we poll for the exit-code file and return a
        bounded log tail."""
        base = f"/tmp/wt-detached-{uuid4().hex}"
        script = shlex.join(tuple(argv))
        if cwd is not None:
            script = f"cd {shlex.quote(cwd)} && {script}"
        wrapped = "{ " + script + " ; } >" + f"{base}.log 2>&1; echo $? >{base}.ec"
        self.run(
            ("sh", "-c", f"nohup sh -c {shlex.quote(wrapped)} >/dev/null 2>&1 &"),
            idempotent=idempotent,
            describe=f"start {describe}",
        )
        deadline = clock() + timeout
        while True:
            probe = self.run(
                ("sh", "-c", f"cat {base}.ec 2>/dev/null || true"),
                idempotent=True,
                describe=f"poll {describe}",
            )
            text = str(getattr(probe, "stdout", "")).strip()
            if text:
                exit_code = int(text)
                break
            if clock() >= deadline:
                raise TimeoutError(
                    f"{describe} did not finish within {int(timeout)}s; remote log: {base}.log"
                )
            self.sleep(poll_interval)
        tail = self.run(
            ("sh", "-c", f"tail -c {_TAIL_BYTES} {base}.log"),
            idempotent=True,
            describe=f"collect {describe} log",
        )
        output = str(getattr(tail, "stdout", ""))
        self.run(
            ("rm", "-f", "--", f"{base}.ec", f"{base}.log"),
            idempotent=True,
            describe=f"clean {describe} files",
        )
        if exit_code != 0:
            raise RemoteCommandError(describe, exit_code, output)
        return output
```

- [ ] **Step 4: Run both full suites — must pass.**
- [ ] **Step 5: Commit** — `git commit -m "feat(engine): fire-and-poll run_detached for long remote commands (D3)"`

Note: adopting `run_detached` for specific release build commands is a deliberate follow-up AFTER the cut (spec calls D3 an "evolution") — the cut itself preserves today's attached `bounded=True` behavior so the re-platforming stays behavior-identical. The primitive lands now so the follow-up is a one-line change per command.

---

### Task 8: C1 — secrets promoted to the engine + context-manager resources

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/infra/secrets.py` (moved from `tools/controlplane/src/controlplane_tool/release/secrets.py` — the module is stdlib-only, no import surgery needed)
- Create: `tools/workflow-tasks/tests/infra/test_secrets.py` (moved from `tools/controlplane/tests/release/test_secrets.py`)
- Delete: `tools/controlplane/src/controlplane_tool/release/secrets.py`, `tools/controlplane/tests/release/test_secrets.py`
- Modify: `tools/workflow-tasks/src/workflow_tasks/core/resource_task.py` (value-carrying acquire + `context_resource`), `tools/workflow-tasks/src/workflow_tasks/__init__.py`, controlplane importers of `release.secrets` (grep: `release/run.py:49-53` and any others found by `grep -rn "release.secrets\|release import secrets" tools/controlplane/src tools/controlplane/tests`)

**Interfaces:**
- Produces:
  - `workflow_tasks.infra.secrets` — `stage_ghcr_credentials(provider, request, *, username, token_file, registry="ghcr.io")` (yields `RemoteDockerCredentials(docker_config)`), `stage_cosign_credentials(provider, request, *, key_file, password_file=None)` (yields `RemoteCosignCredentials(key_file, password_file)`), `validate_secret_file(path)`, `ReleaseCredentialCleanupError` — all unchanged; a staged secret is deleted on success AND failure by construction (the CM), which is C1's "always deleted afterward".
  - `ResourceTask.acquire: Callable[[], Any]`; `ResourceTask.run()` stores the acquire return value; new property `ResourceTask.value`.
  - `context_resource(task_id, title, factory: Callable[[], AbstractContextManager[Any]], *, infrastructure=False, probe=None) -> ResourceTask` — enters the CM on acquire (its yield becomes `.value`), exits it on release. This + `Phase.resources` is the first-class "this secret is live during phase X" concept: a lease listed by exactly the phases that use it is staged right before the first and deleted right after the last, or on failure.

- [ ] **Step 1: Move the module and its tests.** `git mv tools/controlplane/src/controlplane_tool/release/secrets.py tools/workflow-tasks/src/workflow_tasks/infra/secrets.py` and `git mv tools/controlplane/tests/release/test_secrets.py tools/workflow-tasks/tests/infra/test_secrets.py`. Update the test module's imports to `from workflow_tasks.infra.secrets import ...`. Update controlplane importers (`run.py`) to `from workflow_tasks.infra.secrets import stage_cosign_credentials, stage_ghcr_credentials, validate_secret_file`. Run both suites — must pass before continuing.
- [ ] **Step 2: Write the failing tests for value-carrying resources** — append to `tools/workflow-tasks/tests/core/test_phase.py`:

```python
from contextlib import contextmanager

from workflow_tasks.core.resource_task import context_resource


def test_context_resource_stages_on_acquire_and_cleans_on_release() -> None:
    events: list[str] = []

    @contextmanager
    def lease():
        events.append("staged")
        try:
            yield {"docker_config": "/tmp/creds/docker"}
        finally:
            events.append("deleted")

    resource = context_resource("ghcr", "GHCR credentials", lease)
    resource.run()
    assert resource.value == {"docker_config": "/tmp/creds/docker"}
    assert events == ["staged"]
    resource.cleanup()
    assert events == ["staged", "deleted"]


def test_context_resource_lease_is_deleted_even_when_a_phase_fails() -> None:
    events: list[str] = []

    @contextmanager
    def lease():
        events.append("staged")
        try:
            yield "creds"
        finally:
            events.append("deleted")

    recorder = _Recorder()
    workflow = PhasedWorkflow(
        phases=[
            Phase(
                phase_id="publish", title="publish",
                tasks=[recorder.task("publish.t", fail=True)],
                resources=[context_resource("ghcr", "GHCR credentials", lease)],
            )
        ],
        keep=True,  # keep skips infrastructure only — the lease must STILL be deleted
    )
    with pytest.raises(RuntimeError):
        workflow.run()
    assert events == ["staged", "deleted"]
```

- [ ] **Step 3: Run — must fail** (`ImportError: cannot import name 'context_resource'`).
- [ ] **Step 4: Implement** — rewrite `core/resource_task.py` as:

```python
from __future__ import annotations

from collections.abc import Callable
from contextlib import AbstractContextManager, ExitStack
from dataclasses import dataclass, field
from typing import Any


@dataclass(slots=True)
class ResourceTask:
    task_id: str
    title: str
    acquire: Callable[[], Any] = field(repr=False)
    release: Callable[[], None] = field(repr=False)
    infrastructure: bool = False
    # B4: reports the resource's ACTUAL state. When set, the engine verifies
    # it right after acquire — an ensure that trusts desired state fails here.
    probe: Callable[[], bool] | None = None
    _value: Any = field(default=None, init=False, repr=False, compare=False)

    @property
    def cleanup_task_id(self) -> str:
        return f"{self.task_id}.cleanup"

    @property
    def cleanup_title(self) -> str:
        return f"Release {self.title.removeprefix('Acquire ')}"

    @property
    def value(self) -> Any:
        return self._value

    def run(self) -> None:
        self._value = self.acquire()

    def cleanup(self) -> None:
        self.release()


def context_resource(
    task_id: str,
    title: str,
    factory: Callable[[], AbstractContextManager[Any]],
    *,
    infrastructure: bool = False,
    probe: Callable[[], bool] | None = None,
) -> ResourceTask:
    """A resource backed by a context manager: entered on acquire (its yield
    becomes .value), exited on release. Listing it on the phases that use it
    makes the CM's lifetime exactly phase-scoped (C1)."""
    stack = ExitStack()
    return ResourceTask(
        task_id=task_id,
        title=title,
        acquire=lambda: stack.enter_context(factory()),
        release=stack.close,
        infrastructure=infrastructure,
        probe=probe,
    )
```

- [ ] **Step 5: Export.** Add `context_resource` (and re-export `ResourceTask` if not already) to `workflow_tasks/__init__.py`; extend `test_public_api.py` with `assert hasattr(workflow_tasks, "context_resource")` inside a new `test_public_api_exports_resources` function. Check `tests/core/test_resource_cleanup.py` still passes (acquire callables returning `None` are unaffected).
- [ ] **Step 6: Run both full suites — must pass.**
- [ ] **Step 7: Commit** — `git commit -m "feat(engine): promote secret staging and add context_resource leases (C1)"`

---

### Task 9: Release phase actions extracted to phases.py on the engine exec seam

This is a **move task**: no behavior change, `run.py` keeps orchestrating but its phase bodies now live in `release/phases.py` and all remote traffic goes through `RemoteExec`. The full controlplane suite (unchanged except imports) is the regression net.

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/release/phases.py`
- Modify: `tools/controlplane/src/controlplane_tool/release/run.py` (delete moved bodies, import from `phases`), `tools/controlplane/src/controlplane_tool/release/publish.py:324-340` (`_exec`), `tools/controlplane/src/controlplane_tool/release/attest.py:260-270` (`_exec`)
- Test: existing `tools/controlplane/tests/release/` suite (imports updated where they referenced moved names via `controlplane_tool.release.run`)

**Interfaces:**
- Consumes: `RemoteExec`, `require_success`, `ArtifactEvidence`, `Journal`, `digest_path` from `workflow_tasks`.
- Produces (`controlplane_tool.release.phases`, consumed by Task 10's scenario) — the moved functions, public (underscore dropped), with `(provider, request)` pairs replaced by a `RemoteExec`:
  - `source_test_commands(remote_source_dir: Path) -> tuple[CommandTaskSpec, ...]` and `amd64_build_commands(plan, *, remote_bake_file, remote_buildkit_config, remote_source_dir)` — moved verbatim from `run.py:276-410` and `run.py:413-491`, each `CommandTaskSpec(...)` gaining `idempotent=True` (A3 declaration).
  - `create_source_archive(repo_root, guarded_commit, destination) -> ArtifactEvidence` — verbatim from `run.py:494-526`.
  - `stage_source_archive(exec: RemoteExec, *, archive: Path, remote_archive: str, remote_source_dir: str, expected_digest: str | None = None) -> None` — from `run.py:529-559`.
  - `run_source_tests(plan, exec: RemoteExec, *, archive_builder, remote_archive, remote_source_dir) -> tuple[ArtifactEvidence, ...]` — from `_run_source_tests` (`run.py:1318-1348`).
  - `build_amd64_images(plan, exec, *, remote_bake, remote_buildkit, remote_source_dir) -> tuple[ArtifactEvidence, ...]` — from `run.py:1351-1380` (includes `verify_generated_build_inputs`, `reset_named_builder` from `run.py:1608-1642`).
  - `push_local_images(plan, exec, expected_build_evidence) -> tuple[ArtifactEvidence, ...]` — from `run.py:1645-1662`.
  - `build_arm64_images(plan, image_plan, bake_file, exec, *, remote_bake, remote_source_dir, registry_upstream) -> tuple[ArtifactEvidence, ...]` — from `run.py:1383-1435`.
  - `smoke_arm64_images(plan, image_plan, exec, expected_build_evidence, *, registry_upstream) -> tuple[ArtifactEvidence, ...]` — from `run.py:1438-1508` (+ `require_image_architecture`, `smoke_arm64_server`, `pinned_image` from `run.py:1511-1605`).
  - `run_benchmark(plan, index, loadtest_builder, bindings, fetcher, control_plane_url, prometheus_url, exec, expected_registry_digests) -> tuple[ArtifactEvidence, ...]` — from `run.py:1796-1840` (+ `native_image`, `pinned_native_image`, `function_target_name` from `run.py:1843-1869`).
  - `write_aggregate(plan, journal) -> ArtifactEvidence`, `evaluate_gate(plan, journal) -> tuple[RegressionDecision, ArtifactEvidence]`, `performance_profile(plan)`, `regression_policy(plan)`, `release_records(dir)`, `aggregate_from_record(...)`, `aggregate_from_payload(...)`, `decision_from_payload(...)`, `read_json_file(...)`, `write_json(...)` — from `run.py:1872-2015`.
  - Evidence helpers: `journal_phase_artifacts(journal, phase)`, `journal_artifact(journal, phase, *, location, reference)`, `read_verified_local_json(journal, phase, path)`, `evidence_map(artifacts)`, `local_image_evidence(plan, exec)`, `registry_image_evidence(plan, exec)`, `inspect_image_digest(exec, reference)`, `remote_image_digest(exec, location, reference, *, ghcr_authfile=None)`, `inspect_ghcr_digest(exec, reference, *, authfile)`, `inspect_registry_digest(exec, reference)`, `registry_digest_map(plan, artifacts)` — from `run.py:1257-1315` and `run.py:1665-1793`.
  - VM-side helpers: `verify_release_vm_facts(plan, provider, role, request)` (`run.py:1157-1185`), `secure_release_endpoints(plan, provider, stack_request, loadgen_request) -> tuple[str, str]` (`run.py:1188-1208`), `provision_release_builder(provider, request, repo_root)` (`run.py:2018-2026`).

**Adaptation rule (mechanical, applied throughout):** `_provider_exec(provider, request, ARGV, cwd=CWD, bounded=B)` → `exec.run(ARGV, cwd=CWD, bounded=B, idempotent=True, describe=DESC)` where `DESC` is the enclosing `CommandTaskSpec.summary` when iterating command specs (e.g. `for command in ...: exec.run(command.argv, cwd=command.remote_dir, bounded=True, idempotent=command.idempotent, describe=command.summary)`), else a short literal naming the operation (e.g. `"push {cell.image}"`, `"inspect registry digest {reference}"`, `"remove smoke container"`). `_provider_transfer_to(provider, request, source=S, destination=D, action=A)` → `exec.transfer_to(source=S, destination=D, describe=A)`. Raw `provider.exec_argv(...)` calls whose non-zero exit is *expected and inspected* (buildx inspect probe at `run.py:1630-1637`, watchdog expected-exit at `run.py:1470-1487`, best-effort container removal at `run.py:1586-1594`) call `exec.provider.exec_argv(exec.request, ...)` directly — they must NOT go through `require_success`.

- [ ] **Step 1: `gitnexus_impact` on `run_amd64_release` (upstream)** — confirm the only callers are `cli/release.py` and the test suite; report the blast radius.
- [ ] **Step 2: Create `phases.py`** by moving the functions listed above out of `run.py` (cut from `run.py`, paste into `phases.py`, drop leading underscores, apply the adaptation rule). Module docstring: `"""Release phase actions: every remote operation is idempotent and goes through the engine's hardened RemoteExec seam."""`. Also move the toolchain constants `_GO_TOOLCHAIN`/`_NODE_TOOLCHAIN`/`_RUST_TOOLCHAIN` (`run.py:78-87`) — keep their underscore names, they stay module-private.
- [ ] **Step 3: Rewire `run.py`** to `from controlplane_tool.release import phases` and call `phases.<name>(...)` everywhere a moved function was used, constructing `exec_stack = RemoteExec(provider, stack_request)` and `exec_arm = RemoteExec(provider, arm_request)` once in `_run_amd64_release_locked` and threading them through. The journal's `artifact_digest` hook becomes `lambda location, reference: phases.remote_image_digest(exec_stack, location, reference, ghcr_authfile=ghcr_auth.get("authfile"))`.
- [ ] **Step 4: Reroute publish/attest `_exec` through the engine (D4 fix at the cited failure sites).** In `publish.py`, replace the `_exec` body (`publish.py:324-340`):

```python
def _exec(
    provider: object,
    request: object,
    argv: tuple[str, ...],
    *,
    env: dict[str, str] | None = None,
) -> object:
    from workflow_tasks.infra.remote_exec import RemoteExec

    return RemoteExec(provider, request).run(
        argv, env=env, idempotent=True, describe=f"publish: {argv[0]} {argv[1]}"
    )
```

  In `attest.py`, replace its `_exec` (`attest.py:260-270`) the same way with `describe=f"attest: {argv[0]}"`. (Move the import to the top of each module with the other imports.) The old bare messages `"release publication command failed: skopeo copy"` / `"release attestation command failed: sh"` are replaced by `RemoteCommandError`'s operation + stderr message. Update any test in `tests/release/test_publish.py` / `tests/release/test_attest.py` that asserts the old message text to match the new format (search for `"command failed"` in both files).
- [ ] **Step 5: Update test imports.** In `tests/release/test_run_amd64.py`, symbols that moved (e.g. `source_test_commands`, `amd64_build_commands`, `create_source_archive`, `stage_source_archive`) are now imported `from controlplane_tool.release.phases import ...`. The runner-level imports (`build_amd64_release_plan`, `run_amd64_release`, `CredentialFiles`) stay on `controlplane_tool.release.run` for now.
- [ ] **Step 6: Run both full suites — must pass** (behavior unchanged; this is the proof the move is faithful).
- [ ] **Step 7: `gitnexus_detect_changes()`, then commit** — `git commit -m "refactor(release): extract phase actions to release/phases.py on the RemoteExec seam"`

---

### Task 10: The release as a PhasedWorkflow scenario

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/release/scenario.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli/provisioning.py` (publish per-role helpers)
- Test: `tools/controlplane/tests/release/test_scenario.py` (new, minimal — the heavy regression net arrives in Task 11 when `test_run_amd64.py` is pointed at `run_release`)

**Interfaces:**
- Consumes: everything Task 9 produced in `phases`; `Phase`, `PhasedWorkflow`, `context_resource`, `ResourceTask`, `Journal`, `JournaledStep`, `RemoteExec`, `retry_transient` from `workflow_tasks`; `stage_ghcr_credentials`/`stage_cosign_credentials` from `workflow_tasks.infra.secrets`; from `cli/provisioning.py` the newly public helpers (below).
- Produces:
  - `cli/provisioning.py` renames (use `gitnexus_rename`, update `provision_environment`'s internal calls; `provision_environment` itself is unchanged in behavior and keeps serving the e2e/loadtest paths):
    - `_ensure_vm` → `ensure_role_vm(orchestrator, request, *, role) -> VmRequest`
    - `_destroy_task` → `destroy_role_vm_task(orchestrator, request, *, role) -> DestroyVm | None`
    - plus three new wrappers that factor the existing inline op-pipelines out of `provision_environment` (`provisioning.py:275-329`) so the scenario and `provision_environment` share one source of truth:

```python
def bootstrap_stack(scenario, environment, orchestrator, repo_root, resolved, *, dedicated_loadgen):
    context = _context(repo_root, resolved)
    _run_operations(
        orchestrator,
        _retarget_cloud_operations(
            environment, orchestrator, context,
            _stack_operations(scenario, context, dedicated_loadgen=dedicated_loadgen),
        ),
        role="stack",
    )


def bootstrap_loadgen(environment, orchestrator, repo_root, resolved):
    context = _context(repo_root, resolved)
    _run_operations(
        orchestrator,
        _retarget_cloud_operations(
            environment, orchestrator, context,
            _remote_operations(
                (*plan_loadtest_install_k6(context), *plan_repo_sync_to_vm(context))
            ),
        ),
        role="loadgen",
    )


def bootstrap_arm_builder(orchestrator, repo_root, resolved):
    context = _context(repo_root, resolved)
    _run_operations(
        orchestrator,
        _remote_operations(plan_vm_provision_base(context)),
        role="arm-builder",
    )
```

    `provision_environment` is refactored to call these three instead of its inline blocks (behavior-identical; the cloud-role block stays inline, the release has no cloud role).
  - `scenario.run_release(plan, *, resume=False, keep=False, provider_factory=None, ensure_vm=None, destroy_vm=None, builder_provisioner=None, loadtest_builder=None, archive_builder=None, failure_injector=None) -> RegressionDecision` — the single entry point replacing `run_amd64_release`. Injection-seam mapping from the old runner (this is what keeps `test_run_amd64.py` alive in Task 11): `provider_factory` unchanged; the old `provisioner` CM is replaced by `ensure_vm: Callable[[object, VmRequest, str], VmRequest]` (default wraps `ensure_role_vm` + role bootstrap + `verify_and_secure`) and `destroy_vm: Callable[[object, VmRequest, str], None]`; `builder_provisioner`, `loadtest_builder`, `archive_builder`, `failure_injector` unchanged.
  - `scenario.build_release_workflow(...) -> PhasedWorkflow` — pure assembly, unit-testable without any provider.

- [ ] **Step 1: Write the failing assembly tests** — create `tools/controlplane/tests/release/test_scenario.py`:

```python
from __future__ import annotations

from controlplane_tool.release.scenario import phase_resource_names
from controlplane_tool.release.run import RELEASE_PHASES  # moves to release.plan in Task 11


def test_loadgen_is_scoped_to_benchmark_phases_only() -> None:
    scoped = phase_resource_names()
    for phase in RELEASE_PHASES:
        if phase.startswith("benchmark-"):
            assert "loadgen" in scoped[phase]
        else:
            assert "loadgen" not in scoped[phase]


def test_arm_builder_is_scoped_to_arm64_phases_only() -> None:
    scoped = phase_resource_names()
    assert {p for p in RELEASE_PHASES if "arm-builder" in scoped[p]} == {"arm64-build", "arm64-smoke"}


def test_ghcr_lease_spans_publish_and_attest_and_cosign_only_attest() -> None:
    scoped = phase_resource_names()
    assert {p for p in RELEASE_PHASES if "ghcr" in scoped[p]} == {
        "publish-architectures", "publish-manifests", "publish-aliases", "attest",
    }
    assert {p for p in RELEASE_PHASES if "cosign" in scoped[p]} == {"attest"}


def test_stack_is_released_before_finalize() -> None:
    scoped = phase_resource_names()
    assert "stack" in scoped["source-tests"]
    assert "stack" in scoped["attest"]
    for local_only in ("aggregate", "regression-gate", "finalize"):
        assert "stack" not in scoped[local_only]
```

- [ ] **Step 2: Run — must fail** (`ModuleNotFoundError: controlplane_tool.release.scenario`).
- [ ] **Step 3: Implement provisioning renames/wrappers** (with `gitnexus_rename` for the two renames, `gitnexus_impact` first), then create `scenario.py`. Complete skeleton — the phase actions are one-line closures over `phases.*`, shown in full for the assembly-critical parts:

```python
"""The guarded Azure release as a Workflow-of-Tasks scenario.

One cut from the bespoke runner: 15 journaled phases on PhasedWorkflow,
phase-scoped VMs and credential leases, hardened exec. The journal schema
and phase order are IDENTICAL to the old runner — old journals resume here.
"""

from __future__ import annotations

from collections.abc import Callable, Iterable
from pathlib import Path

from workflow_tasks import (
    ArtifactEvidence,
    Journal,
    JournaledStep,
    Phase,
    PhasedWorkflow,
    RemoteExec,
    ResourceTask,
    context_resource,
    digest_path,
    retry_transient,
)
from workflow_tasks.infra.remote_exec import require_success
from workflow_tasks.infra.secrets import stage_cosign_credentials, stage_ghcr_credentials
from workflow_tasks.loadtest.adapters import HttpPrometheusClient
from workflow_tasks.vm.models import VmRequest, vm_remote_home
from workflow_tasks.workflow.reporting import step as report_step

from controlplane_tool.cli.execution import build_role_bindings
from controlplane_tool.cli.provisioning import (
    bootstrap_arm_builder,
    bootstrap_loadgen,
    bootstrap_stack,
    destroy_role_vm_task,
    ensure_role_vm,
)
from controlplane_tool.cli.vm_provider import vm_provider_for_environment, vm_request_for_role
from controlplane_tool.plans.loadtest import build_loadtest_plan
from controlplane_tool.release import arm, attest, phases, publish
# In this task these still import from release.run; Task 11 repoints them to release.plan.
from controlplane_tool.release.run import (
    Amd64ReleasePlan,
    RELEASE_PHASES,
    _assert_guarded_source as assert_guarded_source,
    _release_lock_path as release_lock_path,
    _release_run_lock as release_run_lock,
    create_source_archive,
)
from controlplane_tool.release.metrics import RegressionDecision

_AZURE_TRANSIENT_MARKERS = ("NicReservedForAnotherVm",)


def _is_transient_azure(error: Exception) -> bool:
    return any(marker in str(error) for marker in _AZURE_TRANSIENT_MARKERS)


def phase_resource_names() -> dict[str, tuple[str, ...]]:
    """Which named resources each phase requires (B3). Pure data: unit-tested."""
    benchmark = ("stack", "loadgen")
    mapping: dict[str, tuple[str, ...]] = {
        "source-tests": ("stack",),
        "amd64-build": ("stack",),
        "local-registry-push": ("stack",),
        "benchmark-1": benchmark,
        "benchmark-2": benchmark,
        "benchmark-3": benchmark,
        "aggregate": (),
        "regression-gate": (),
        "arm64-build": ("stack", "arm-builder"),
        "arm64-smoke": ("stack", "arm-builder"),
        "publish-architectures": ("stack", "ghcr"),
        "publish-manifests": ("stack", "ghcr"),
        "publish-aliases": ("stack", "ghcr"),
        "attest": ("stack", "ghcr", "cosign"),
        "finalize": (),
    }
    assert tuple(mapping) == RELEASE_PHASES
    return mapping
```

  Then the workflow builder and entry point (complete):

```python
def build_release_workflow(
    plan: Amd64ReleasePlan,
    journal: Journal,
    reusable: frozenset[str],
    *,
    provider: object,
    requests: dict[str, VmRequest],
    ensure_vm: Callable[[object, VmRequest, str], VmRequest],
    destroy_vm: Callable[[object, VmRequest, str], None],
    builder_provisioner: Callable[[object, object, Path], None],
    loadtest_builder: Callable[..., object],
    archive_builder: Callable[[Path, str, Path], ArtifactEvidence],
    failure_injector: Callable[[str], None] | None,
    keep: bool,
) -> tuple[PhasedWorkflow, Callable[[], RegressionDecision]]:
    stack_request = requests["stack"]
    exec_stack = RemoteExec(provider, stack_request)
    exec_arm = RemoteExec(provider, requests["arm-builder"])

    remote_root = f"{vm_remote_home(stack_request)}/nanofaas-release/{plan.version}"
    source_dir = f"{remote_root}/source"
    source_archive = f"{remote_root}/source.tar"
    remote_bake = f"{remote_root}/{plan.bake_file.name}"
    remote_buildkit = f"{remote_root}/{plan.buildkit_config.name}"
    local_archive = plan.run_dir / "source.tar"

    endpoints_box: dict[str, tuple[str, str]] = {}
    bindings_box: dict[str, object] = {}

    def acquire_stack() -> VmRequest:
        resolved = ensure_vm(provider, stack_request, "stack")
        builder_provisioner(provider, stack_request, plan.repo_root)
        return resolved

    def acquire_loadgen() -> tuple[str, str]:
        # for the loadgen role, ensure_vm returns the SECURED ENDPOINTS
        # (control_plane_url, prometheus_url) — see _default_ensure_vm and
        # the test fake in Task 11 — because verified ingress is the thing
        # the benchmarks actually consume from this resource.
        endpoints = ensure_vm(provider, requests["loadgen"], "loadgen")
        endpoints_box["endpoints"] = endpoints
        return endpoints

    def acquire_arm() -> VmRequest:
        resolved = ensure_vm(provider, requests["arm-builder"], "arm-builder")
        builder_provisioner(provider, requests["arm-builder"], plan.repo_root)
        return resolved

    resources: dict[str, ResourceTask] = {
        "stack": ResourceTask(
            task_id="release.resource.stack", title="Stack VM",
            acquire=acquire_stack,
            release=lambda: destroy_vm(provider, stack_request, "stack"),
            infrastructure=True,
        ),
        "loadgen": ResourceTask(
            task_id="release.resource.loadgen", title="Loadgen VM",
            acquire=acquire_loadgen,
            release=lambda: destroy_vm(provider, requests["loadgen"], "loadgen"),
            infrastructure=True,
        ),
        "arm-builder": ResourceTask(
            task_id="release.resource.arm-builder", title="ARM64 builder VM",
            acquire=acquire_arm,
            release=lambda: destroy_vm(provider, requests["arm-builder"], "arm-builder"),
            infrastructure=True,
        ),
        "ghcr": context_resource(
            "release.resource.ghcr", "GHCR credentials",
            lambda: stage_ghcr_credentials(
                provider, stack_request,
                username=publish.ghcr_username(),
                token_file=plan.credentials.ghcr_token,
            ),
        ),
        "cosign": context_resource(
            "release.resource.cosign", "Cosign credentials",
            lambda: stage_cosign_credentials(
                provider, stack_request,
                key_file=plan.credentials.cosign_key,
                password_file=plan.credentials.cosign_password,
            ),
        ),
    }

    def endpoints() -> tuple[str, str]:
        if "endpoints" not in endpoints_box:
            raise RuntimeError("release loadgen ingress was not verified before benchmarks")
        return endpoints_box["endpoints"]

    def benchmark_bindings() -> tuple[object, object]:
        if "bindings" not in bindings_box:
            bindings, fetcher = build_role_bindings(
                plan.environment, vm_provider=provider, repo_root=plan.repo_root
            )
            bindings_box["bindings"], bindings_box["fetcher"] = bindings, fetcher
        return bindings_box["bindings"], bindings_box["fetcher"]

    arm_plan = arm.build_arm64_image_plan(
        plan.repo_root, plan.version, registry=plan.image_plan.registry
    )
    arm_bake = plan.run_dir / "docker-bake-arm64.json"
    remote_arm_bake = f"{remote_root}/{arm_bake.name}"

    def source_tests_action() -> tuple[ArtifactEvidence, ...]:
        return phases.run_source_tests(
            plan, exec_stack,
            archive_builder=archive_builder,
            remote_archive=source_archive,
            remote_source_dir=source_dir,
        )

    def amd64_build_action() -> tuple[ArtifactEvidence, ...]:
        archive_evidence = phases.journal_artifact(
            journal, "source-tests", location="local", reference=str(local_archive)
        )
        phases.stage_source_archive(
            exec_stack, archive=local_archive, remote_archive=source_archive,
            remote_source_dir=source_dir, expected_digest=archive_evidence.digest,
        )
        return phases.build_amd64_images(
            plan, exec_stack,
            remote_bake=remote_bake, remote_buildkit=remote_buildkit,
            remote_source_dir=source_dir,
        )

    def registry_push_action() -> tuple[ArtifactEvidence, ...]:
        return phases.push_local_images(
            plan, exec_stack, phases.journal_phase_artifacts(journal, "amd64-build")
        )

    def benchmark_action(index: int) -> tuple[ArtifactEvidence, ...]:
        bindings, fetcher = benchmark_bindings()
        control_plane_url, prometheus_url = endpoints()
        expected = phases.registry_digest_map(
            plan, phases.journal_phase_artifacts(journal, "local-registry-push")
        )
        return phases.run_benchmark(
            plan, index, loadtest_builder, bindings, fetcher,
            control_plane_url, prometheus_url, exec_stack, expected,
        )

    def aggregate_action() -> tuple[ArtifactEvidence, ...]:
        return (phases.write_aggregate(plan, journal),)

    def gate_action() -> tuple[ArtifactEvidence, ...]:
        assert_guarded_source(plan)
        decision, artifact = phases.evaluate_gate(plan, journal)
        if not decision.passed:
            raise RuntimeError(
                "release regression gate failed: " + "; ".join(decision.failures)
            )
        return (artifact,)

    def arm64_build_action() -> tuple[ArtifactEvidence, ...]:
        assert_guarded_source(plan)
        archive_evidence = phases.journal_artifact(
            journal, "source-tests", location="local", reference=str(local_archive)
        )
        phases.stage_source_archive(
            exec_arm, archive=local_archive, remote_archive=source_archive,
            remote_source_dir=source_dir, expected_digest=archive_evidence.digest,
        )
        return phases.build_arm64_images(
            plan, arm_plan, arm_bake, exec_arm,
            remote_bake=remote_arm_bake, remote_source_dir=source_dir,
            registry_upstream=provider.connection_host(stack_request),  # type: ignore[attr-defined]
        )

    def arm64_smoke_action() -> tuple[ArtifactEvidence, ...]:
        return phases.smoke_arm64_images(
            plan, arm_plan, exec_arm,
            phases.journal_phase_artifacts(journal, "arm64-build"),
            registry_upstream=provider.connection_host(stack_request),  # type: ignore[attr-defined]
        )

    def publish_context() -> tuple[object, dict[str, str], str]:
        assert_guarded_source(plan)
        phases.journal_phase_artifacts(journal, "regression-gate")
        phases.journal_phase_artifacts(journal, "arm64-smoke")
        publish_plan = publish.build_publish_plan(
            plan.repo_root, plan.version, local_registry=plan.image_plan.registry
        )
        source_digests = publish.require_publication_evidence(
            publish_plan,
            phases.journal_phase_artifacts(journal, "local-registry-push")
            + phases.journal_phase_artifacts(journal, "arm64-build"),
        )
        docker_config = resources["ghcr"].value.docker_config
        return publish_plan, source_digests, docker_config

    def publish_architectures_action() -> tuple[ArtifactEvidence, ...]:
        publish_plan, source_digests, docker_config = publish_context()
        return publish.publish_architecture_images(
            provider, stack_request, publish_plan, source_digests,
            authfile=f"{docker_config}/config.json",
        )

    def publish_manifests_action() -> tuple[ArtifactEvidence, ...]:
        publish_plan, _, docker_config = publish_context()
        architecture_digests = {
            artifact.reference.removeprefix("docker://"): artifact.digest
            for artifact in phases.journal_phase_artifacts(journal, "publish-architectures")
        }
        return publish.publish_manifests(
            provider, stack_request, publish_plan, architecture_digests,
            docker_config=docker_config,
        )

    def publish_aliases_action() -> tuple[ArtifactEvidence, ...]:
        publish_plan, _, docker_config = publish_context()
        manifest_digests = {
            artifact.reference.removeprefix("docker://"): artifact.digest
            for artifact in phases.journal_phase_artifacts(journal, "publish-manifests")
        }
        return publish.publish_aliases(
            provider, stack_request, publish_plan, manifest_digests,
            docker_config=docker_config,
        )

    def release_record():
        published = (
            phases.journal_phase_artifacts(journal, "publish-architectures")
            + phases.journal_phase_artifacts(journal, "publish-manifests")
            + phases.journal_phase_artifacts(journal, "publish-aliases")
        )
        images = {
            artifact.reference.removeprefix("docker://"): artifact.digest
            for artifact in published
        }
        from controlplane_tool.release.metrics import build_release_record

        return images, build_release_record(
            version=plan.version,
            source_commit=plan.identity.source_commit,
            image_digests=images,
            aggregate=phases.aggregate_from_payload(
                phases.read_verified_local_json(journal, "aggregate", plan.run_dir / "aggregate.json")
            ),
            policy=phases.regression_policy(plan),
        )

    def attest_action() -> tuple[ArtifactEvidence, ...]:
        assert_guarded_source(plan)
        images, _ = release_record()
        benchmark_digest = phases.journal_phase_artifacts(journal, "aggregate")[0].digest
        predicate = attest.build_release_predicate(
            version=plan.version,
            source_commit=plan.identity.source_commit,
            azure_profile=plan.settings.profile,
            benchmark_record_digest=benchmark_digest,
            image_digests=images,
        )
        predicate_file = plan.run_dir / "predicate.json"
        predicate_file.write_text(attest.render_predicate(predicate), encoding="utf-8")
        remote_predicate = f"{remote_root}/predicate.json"
        exec_stack.run(("mkdir", "-p", remote_root), idempotent=True, describe="create release root")
        exec_stack.transfer_to(
            source=predicate_file, destination=remote_predicate,
            describe="transfer release predicate",
        )
        attest.attest_release_images(
            provider, stack_request,
            images=images,
            predicate_remote=remote_predicate,
            sbom_dir_remote=f"{remote_root}/sboms",
            cosign=resources["cosign"].value,
            docker_config=resources["ghcr"].value.docker_config,
        )
        return (
            ArtifactEvidence("local", str(predicate_file), digest_path(predicate_file)),
        )

    actions: dict[str, Callable[[], Iterable[ArtifactEvidence]]] = {
        "source-tests": source_tests_action,
        "amd64-build": amd64_build_action,
        "local-registry-push": registry_push_action,
        "benchmark-1": lambda: benchmark_action(1),
        "benchmark-2": lambda: benchmark_action(2),
        "benchmark-3": lambda: benchmark_action(3),
        "aggregate": aggregate_action,
        "regression-gate": gate_action,
        "arm64-build": arm64_build_action,
        "arm64-smoke": arm64_smoke_action,
        "publish-architectures": publish_architectures_action,
        "publish-manifests": publish_manifests_action,
        "publish-aliases": publish_aliases_action,
        "attest": attest_action,
    }

    class _FinalizeTask:
        # Deliberately NOT a JournaledStep: a documentation failure must
        # leave the journal without ANY finalize entry (not a failed one) so
        # --resume retries finalization without rebuilding verified images.
        task_id = "release.finalize"
        title = "finalize"

        def run(self) -> None:
            if "finalize" in reusable:
                report_step("finalize (verified evidence reused)")
                return
            if failure_injector is not None:
                failure_injector("finalize")
            _, record = release_record()
            attest.finalize_release(
                journal, record=record, performance_root=plan.performance_root
            )

    scoped = phase_resource_names()
    phase_list: list[Phase] = []
    for name in RELEASE_PHASES:
        phase_reusable = name in reusable
        if name == "finalize":
            tasks = [_FinalizeTask()]
        else:
            tasks = [
                JournaledStep(
                    task_id=f"release.phase.{name}", title=name,
                    journal=journal, phase=name, action=actions[name],
                    reusable=lambda: reusable,
                    failure_injector=failure_injector,
                )
            ]
        phase_list.append(
            Phase(
                phase_id=f"release.{name}", title=name, tasks=tasks,
                # a fully-reusable phase must not acquire anything (B3: no
                # resource is ever provisioned for work that will be skipped)
                resources=[] if phase_reusable else [resources[r] for r in scoped[name]],
            )
        )

    def decision_reader() -> RegressionDecision:
        return phases.decision_from_payload(
            phases.read_verified_local_json(
                journal, "regression-gate", plan.run_dir / "regression-decision.json"
            )
        )

    return PhasedWorkflow(phases=phase_list, keep=keep), decision_reader
```

  And the entry point with the guard/lock/resume flow preserved from `_run_amd64_release_locked` (`run.py:619-770`):

```python
def run_release(
    plan: Amd64ReleasePlan,
    *,
    resume: bool = False,
    keep: bool = False,
    provider_factory: Callable[..., object] | None = None,
    ensure_vm: Callable[[object, VmRequest, str], VmRequest] | None = None,
    destroy_vm: Callable[[object, VmRequest, str], None] | None = None,
    builder_provisioner: Callable[[object, object, Path], None] | None = None,
    loadtest_builder: Callable[..., object] | None = None,
    archive_builder: Callable[[Path, str, Path], ArtifactEvidence] | None = None,
    failure_injector: Callable[[str], None] | None = None,
) -> RegressionDecision:
    """Run the release through publication as a PhasedWorkflow scenario."""
    with release_run_lock(release_lock_path(plan)):
        return _run_release_locked(
            plan, resume=resume, keep=keep,
            provider_factory=provider_factory, ensure_vm=ensure_vm,
            destroy_vm=destroy_vm, builder_provisioner=builder_provisioner,
            loadtest_builder=loadtest_builder, archive_builder=archive_builder,
            failure_injector=failure_injector,
        )


def _run_release_locked(plan, *, resume, keep, provider_factory, ensure_vm,
                        destroy_vm, builder_provisioner, loadtest_builder,
                        archive_builder, failure_injector) -> RegressionDecision:
    if plan.credentials is None:
        raise ValueError("release run requires explicit credential files")
    plan.credentials.validate(repo_root=plan.repo_root)
    assert_guarded_source(plan)
    existing_entries = tuple(plan.state_directory.glob("*.json"))
    if existing_entries and not resume:
        raise ValueError("release journal already exists; pass --resume to verify and reuse it")
    if resume and not existing_entries:
        raise ValueError("--resume requires an existing release journal")

    provider = (provider_factory or vm_provider_for_environment)(plan.environment, plan.repo_root)
    requests = {
        "stack": vm_request_for_role(plan.environment, "stack", loadtest=True),
        "loadgen": vm_request_for_role(plan.environment, "loadgen", loadtest=True),
        "arm-builder": vm_request_for_role(plan.environment, "arm-builder"),
    }
    if not resume:
        report_step("tearing down any previous release VMs")
        for role, request in requests.items():
            require_success(
                retry_transient(
                    lambda request=request: provider.teardown(request),  # type: ignore[attr-defined]
                    describe=f"recreate dedicated release {role} VM",
                    is_transient=_is_transient_azure,
                ),
                f"recreate dedicated release {role} VM",
            )

    ghcr_auth: dict[str, str] = {}
    exec_stack = RemoteExec(provider, requests["stack"])
    journal = Journal(
        plan.journal_root, plan.identity, phases=RELEASE_PHASES,
        artifact_digest=lambda location, reference: phases.remote_image_digest(
            exec_stack, location, reference, ghcr_authfile=ghcr_auth.get("authfile"),
        ),
    )

    resolved_ensure = ensure_vm or _default_ensure_vm(plan)
    resolved_destroy = destroy_vm or _default_destroy_vm
    resolved_builder = builder_provisioner or phases.provision_release_builder

    if resume:
        report_step("resume: verifying journal evidence against GHCR")
        # GHCR-published evidence can only be verified authenticated; the
        # token is staged for this window alone and dropped again before any
        # build phase runs. The STACK VM must exist to verify remote
        # evidence — ensure it (idempotent) before staging.
        resolved_ensure(provider, requests["stack"], "stack")
        resolved_builder(provider, requests["stack"], plan.repo_root)
        with stage_ghcr_credentials(
            provider, requests["stack"],
            username=publish.ghcr_username(),
            token_file=plan.credentials.ghcr_token,
        ) as resume_auth:
            ghcr_auth["authfile"] = f"{resume_auth.docker_config}/config.json"
            try:
                reusable = frozenset(journal.resume().reusable_phases)
            finally:
                ghcr_auth.pop("authfile", None)
    else:
        reusable = frozenset()

    workflow, decision_reader = build_release_workflow(
        plan, journal, reusable,
        provider=provider, requests=requests,
        ensure_vm=resolved_ensure, destroy_vm=resolved_destroy,
        builder_provisioner=resolved_builder,
        loadtest_builder=loadtest_builder or build_loadtest_plan,
        archive_builder=archive_builder or create_source_archive,
        failure_injector=failure_injector,
        keep=keep,
    )
    workflow.run()
    decision = decision_reader()
    if not decision.passed:
        raise RuntimeError("release regression gate evidence did not pass")
    return decision


def _default_ensure_vm(plan: Amd64ReleasePlan):
    def ensure(provider: object, request: VmRequest, role: str):
        resolved = ensure_role_vm(provider, request, role=role)
        phases.verify_release_vm_facts(plan, provider, role, request)
        if role == "stack":
            phases.secure_release_endpoints(plan, provider, request, None)
            bootstrap_stack(
                plan.scenario, plan.environment, provider, plan.repo_root, resolved,
                dedicated_loadgen=True,
            )
            return resolved
        if role == "arm-builder":
            arm_host = provider.connection_host(request)  # type: ignore[attr-defined]
            provider.restrict_inbound_sources(  # type: ignore[attr-defined]
                plan_stack_request(plan),
                ports=(5000,),
                source_cidrs=(f"{arm_host}/32",),
                # Separate priority band from the 30080/81/90 endpoint rules
                # (1010-1012) so the registry rule never collides.
                priority_base=1020,
            )
            bootstrap_arm_builder(provider, plan.repo_root, resolved)
            return resolved
        # loadgen: securing returns the verified endpoints — they are the
        # resource's value, consumed by the benchmark actions.
        endpoints = phases.secure_release_endpoints(
            plan, provider, plan_stack_request(plan), request
        )
        bootstrap_loadgen(plan.environment, provider, plan.repo_root, resolved)
        return endpoints

    return ensure


def plan_stack_request(plan: Amd64ReleasePlan) -> VmRequest:
    return vm_request_for_role(plan.environment, "stack", loadtest=True)


def _default_destroy_vm(provider: object, request: VmRequest, role: str) -> None:
    task = destroy_role_vm_task(provider, request, role=role)
    if task is not None:
        retry_transient(
            task.run,
            describe=f"destroy release {role} VM",
            is_transient=_is_transient_azure,
        )
```

  Implementation notes to honor while filling this in:
  - `acquire_loadgen` in `build_release_workflow` simplifies to `endpoints_box["endpoints"] = ensure_vm(provider, requests["loadgen"], "loadgen")` — the default ensure returns endpoints for loadgen; keep the box so `benchmark_action` reads it.
  - The stack resource's acquire must run `ensure_vm(...)` BEFORE `builder_provisioner` (parity with the old runner); on resume the stack ensure runs twice (once in the resume block, once at acquire) — `ensure_role_vm` is idempotent, this is the reconciling contract at work.
  - `digest_path` comes from `workflow_tasks` in both `scenario.py` and `phases.py` — never re-export it through release modules.
  - Journal ordering vs. phase skipping: `Journal.record` enforces strict phase order against the *latest outcomes*, and `resume()` already appended the invalidation entry for any untrusted suffix — identical to the old runner, no new ordering logic here.
- [ ] **Step 4: Run the new scenario tests + both full suites — must pass** (the old runner is still the CLI entry; the scenario is exercised by `test_scenario.py` assembly tests only until Task 11).
- [ ] **Step 5: `gitnexus_detect_changes()`, then commit** — `git commit -m "feat(release): the release as a PhasedWorkflow scenario with phase-scoped resources"`

---

### Task 11: One cut — plan.py, delete run.py, rewire CLI, adapt the regression suite

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/release/plan.py`
- Delete: `tools/controlplane/src/controlplane_tool/release/run.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli/release.py`, `tools/controlplane/src/controlplane_tool/release/scenario.py` (imports repointed from `release.run` to `release.plan`), `tools/controlplane/tests/release/test_run_amd64.py` (renamed → `tools/controlplane/tests/release/test_release_flow.py`)

**Interfaces:**
- Produces (`controlplane_tool.release.plan`) — moved verbatim from `run.py`, public names: `GitState`, `git_state` (`run.py:90-94, 187-202`), `CredentialFiles` (`run.py:96-116`), `BuilderConfiguration`, `ReleaseSettings` (`run.py:119-134`), `Amd64ReleasePlan` (`run.py:136-184`), `build_amd64_release_plan` (`run.py:205-273`), `RELEASE_PHASES`/`AMD64_PHASES` (`run.py:67-77`), `release_lock_path`/`release_run_lock` (`run.py:570-608`, underscores dropped), `assert_guarded_source` (`run.py:611-616`), `read_yaml`/`release_settings`/`number`/`finite_nonnegative` (`run.py:2116-2194`, keep module-private with underscores).
- The CLI keeps its exact UX: `release prepare|plan|run` flags unchanged (`--resume`, `--keep`, `--provision` acknowledgement, credential file options).

- [ ] **Step 1: `gitnexus_impact` on `run_amd64_release` and `build_amd64_release_plan` (upstream)** — the full list of importers to repoint. Expected: `cli/release.py`, `release/scenario.py`, `tests/release/test_run_amd64.py`, possibly `tests/` helpers. Anything else found must be repointed too.
- [ ] **Step 2: Create `plan.py`** by moving the listed symbols out of `run.py`. Then delete from `run.py` everything already living in `phases.py`/`scenario.py`/`plan.py` — at this point `run.py` must contain ONLY `run_amd64_release`/`_run_amd64_release_locked` and dead imports. Delete the file: `git rm tools/controlplane/src/controlplane_tool/release/run.py`.
- [ ] **Step 3: Repoint `scenario.py` and `tests/release/test_scenario.py`** imports from `controlplane_tool.release.run` to `controlplane_tool.release.plan` (drop the `_`-alias imports — the names are public in `plan.py`).
- [ ] **Step 4: Rewire the CLI.** In `cli/release.py` replace the import block (`cli/release.py:7-13`) with:

```python
from controlplane_tool.release.plan import (
    Amd64ReleasePlan,
    CredentialFiles,
    build_amd64_release_plan,
    git_state,
)
from controlplane_tool.release.scenario import run_release
```

  and in `run_command` replace `decision = run_amd64_release(plan, resume=resume, keep=keep)` with `decision = run_release(plan, resume=resume, keep=keep)`. Everything else in the command stays.
- [ ] **Step 5: Adapt the regression suite.** `git mv tools/controlplane/tests/release/test_run_amd64.py tools/controlplane/tests/release/test_release_flow.py`, then mechanically:
  1. Imports: `from controlplane_tool.release.plan import ...` for plan/credential/settings symbols; `from controlplane_tool.release.scenario import run_release`; `from controlplane_tool.release.phases import ...` for anything not already repointed in Task 9.
  2. Call sites: `run_amd64_release(plan, resume=R, keep=K, provider_factory=F, provisioner=P, builder_provisioner=B, loadtest_builder=L, archive_builder=A, failure_injector=I)` → `run_release(plan, resume=R, keep=K, provider_factory=F, ensure_vm=_fake_ensure, destroy_vm=_fake_destroy, builder_provisioner=B, loadtest_builder=L, archive_builder=A, failure_injector=I)` with two module-level fakes replacing every no-op `provisioner` CM:

```python
def _fake_ensure(provider: object, request: object, role: str) -> object:
    if role == "loadgen":
        return ("http://stack:30080", "http://stack:30090")
    return request


def _fake_destroy(provider: object, request: object, role: str) -> None:
    return None
```

     Tests that asserted `post_ensure_verifier` behavior (VM-facts mismatch, NSG calls — e.g. `test_post_provision_vm_fact_mismatch_stops_before_source_tests_or_builds`) must NOT use `_fake_ensure` (it would bypass the checks): give them a targeted fake that runs the real verification but no bootstrap:

```python
def _verifying_ensure(plan):
    def ensure(provider: object, request: object, role: str) -> object:
        phases.verify_release_vm_facts(plan, provider, role, request)
        if role == "loadgen":
            return phases.secure_release_endpoints(
                plan, provider, requests_stack_for(plan), request
            )
        return request

    return ensure
```

     with `requests_stack_for(plan)` = `vm_request_for_role(plan.environment, "stack", loadtest=True)` (define once at module level). The `_ReleaseProvider` fakes already implement `release_vm_facts`, `restrict_inbound_sources`, `connection_host`, `ssh_private_key_path`, `teardown`, so this fake exercises exactly the assertion each of those tests was written for while skipping real ansible/multipass bootstrap.
  3. Ordering expectations: the old runner tore down/provisioned ALL VMs up front; the scenario provisions per phase. Any test asserting provisioning order (e.g. loadgen created before source-tests) must be updated to the new (spec-mandated) order: loadgen is created right before `benchmark-1` and destroyed right after `benchmark-3`; the arm-builder right before `arm64-build`. Tests asserting "journal exists ⇒ requires --resume", lock behavior, credential validation, evidence-mutation rejection, failure-injection phase blocking, and publication gating keep their assertions unchanged — those contracts did not move.
- [ ] **Step 6: Run both full suites — must pass.** Also run the layered checks: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/test_package_boundaries.py -q` and grep the tree for stragglers: `grep -rn "release.run\|run_amd64_release\|release\.state\|release\.secrets" tools/ --include="*.py" | grep -v __pycache__` must return nothing.
- [ ] **Step 7: `gitnexus_detect_changes()`, then commit** — `git commit -m "feat(release)!: one cut — release runs on the workflow engine, bespoke runner deleted"`

---

### Task 12: E1 — the TUI and visible progress over the release

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/cli/release.py`
- Test: `tools/controlplane/tests/release/test_release_flow.py` (one CLI-level test)

**Interfaces:**
- Consumes: `TuiWorkflowController.run_live_workflow` (`controlplane_tool/tui/workflow_controller.py:22`), `ConsoleProgressSink` (`controlplane_tool/cli/progress.py:11`), `bind_workflow_sink` from `workflow_tasks`. The scenario already emits `workflow_step` events for every phase, task, resource acquire and resource release — the `WorkflowStepState` tree and `_nested_detail_panel` render them without further work; that is the E1 dividend.
- Produces: `release run --tui` renders the live dashboard; without `--tui`, per-phase/per-task progress lines go to **stderr** (never silent — the v0.17.0 "muto fino alle fasi" complaint), stdout keeps only the final result line.

- [ ] **Step 1: Write the failing test** — append to `test_release_flow.py`:

```python
def test_release_run_emits_progress_events_to_a_bound_sink(tmp_path, monkeypatch) -> None:
    # Reuse the module's existing happy-path fixture setup for a full fake
    # release (same fixtures as test_run_composes_amd64_gate_and_defers_all_
    # credentials_and_publication), then:
    from workflow_tasks import bind_workflow_sink
    from controlplane_tool.cli.progress import ConsoleProgressSink

    lines: list[str] = []
    sink = ConsoleProgressSink(write=lines.append)
    with bind_workflow_sink(sink):
        run_release(plan, resume=False, keep=False, **fakes)
    assert any("release.source-tests" in line for line in lines)
    assert any("release.finalize" in line for line in lines)
```

  (Adapt `plan`/`fakes` from the module's existing happy-path test — copy its arrangement, do not invent a new fixture path.)
- [ ] **Step 2: Run — must fail** only if the events are missing; if it passes immediately, good — the engine already emits them; keep the test as the regression net and continue.
- [ ] **Step 3: Wire the CLI.** In `cli/release.py` `run_command`, add the option `tui: bool = typer.Option(False, "--tui", help="Render the live workflow dashboard")` and replace the plain `run_release` call:

```python
        try:
            plan = _release_plan(...)  # unchanged
            if tui:
                from controlplane_tool.tui.workflow_controller import TuiWorkflowController

                decision = TuiWorkflowController().run_live_workflow(
                    title=f"Release {version}",
                    summary_lines=plan.render().splitlines()[:3],
                    planned_steps=None,
                    action=lambda dashboard, sink: run_release(plan, resume=resume, keep=keep),
                )
            else:
                from workflow_tasks import bind_workflow_sink
                from controlplane_tool.cli.progress import ConsoleProgressSink

                sink = ConsoleProgressSink(write=lambda line: typer.echo(line, err=True))
                with bind_workflow_sink(sink):
                    decision = run_release(plan, resume=resume, keep=keep)
        except (FileNotFoundError, PermissionError, RuntimeError, ValueError) as error:
            raise _bad_parameter(error) from error
```

- [ ] **Step 4: Run both full suites — must pass.**
- [ ] **Step 5: `gitnexus_detect_changes()`, final commit** — `git commit -m "feat(release): live TUI and stderr progress for release run (E1)"`. Then re-index: `npx gitnexus analyze` (check `.gitnexus/meta.json` `stats.embeddings` first; add `--embeddings` if non-zero).

---

## Spec coverage map (self-check)

| Spec requirement | Task |
|---|---|
| A1 journaled operations, digest evidence, verified resume | 2 (promotion), 10 (`JournaledStep` per phase) |
| A2 content-addressed inputs, downstream invalidation | 2 (`RunIdentity` digests + `resume()` invalidation — moved intact) |
| A3 idempotency declared | 1 (`CommandTaskSpec.idempotent`), 5 (retry gated on it), 9 (release declares `idempotent=True`) |
| B1 resources-as-tasks | 3/8 (`ResourceTask` + `context_resource`), 10 (VMs, builders, leases as resources) |
| B2 finalizers regardless of outcome; `--keep` = skip finalizers; granular per-resource teardown | 3 (`PhasedWorkflow`), 10 (per-role destroy replaces the monolithic teardown path; keep skips only `infrastructure=True`) |
| B3 phase-scoped resource requirements (lazy loadgen/arm) | 3 (engine), 10 (`phase_resource_names` + reusable phases acquire nothing) |
| B4 reconciling ensure (actual vs desired) | 4 (`probe` contract; SDK fix explicitly out of scope per spec) |
| C1 phase-scoped credential staging, always deleted | 8 (engine secrets + `context_resource`), 10 (ghcr/cosign leases; keep never skips them) |
| D1 connection-death retry, correct layering, `-1` sentinel | 5 |
| D2 bounded remote output | 5 (`bounded=True` structural in `RemoteExec`) |
| D3 fire-and-poll | 7 (primitive; adoption deliberately post-cut) |
| D4 legible errors, visible retries, fail-fast auth | 5 (`RemoteCommandError`), 6 (predicate must not match auth), 9 (publish/attest `_exec` rerouted) |
| D5 transient cloud-error retry | 6 (engine), 10 (teardown/destroy wrapped) |
| E1 per-phase/per-task structured progress + TUI | 12 (with 3's nested `workflow_step` events) |
| Non-goal: one cut, runner deleted same PR | 11 |
| Non-goal: release contract unchanged | Global constraint; enforced by the adapted regression suite (11) |

Known deliberate simplifications (all named in-code where they live): GHCR login becomes one contiguous publish→attest window instead of two back-to-back logins (same phases covered, one fewer login cycle); `aggregate`/`regression-gate`/`finalize` no longer hold the stack VM (they are local-only — the stack is released after its true last use, `attest`); D3 is landed as a primitive but not yet adopted by build commands (behavior-preserving cut).
