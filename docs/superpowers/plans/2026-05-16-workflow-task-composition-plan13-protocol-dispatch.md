# Workflow Task Composition — Piano 13: Dispatch Protocol-first in run() e run_all()

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminare la tuple `isinstance` con 7 tipi in `run()` e `run_all()`, sostituendola con `isinstance(plan, ScenarioPlan)` invertito — controllando il tipo legacy invece dei 7 builder, così aggiungere builder futuri non richiede modifiche a `run()` o `run_all()`.

**Architecture:** Il dataclass `ScenarioPlan` (legacy, in `e2e_runner.py`) è l'unico tipo di piano locale. Tutti i builder tipizzati (7 tipi, più eventuali futuri) non sono istanze di `ScenarioPlan`. Invertendo il check — `if isinstance(plan, ScenarioPlan): self.execute(plan, ...)` — i builder vengono dispatchati a `plan.run()` senza richiedere un'enumerazione esplicita. I 7 import builder vengono rimossi da `run()` e `run_all()`.

**Tech Stack:** Python 3.11+, dataclasses. 2 file toccati: `e2e_runner.py` + `test_e2e_runner.py`.

---

## Background — stato corrente

**`run()` (righe 645–656):**
```python
        from controlplane_tool.scenario.scenarios.two_vm_loadtest import TwoVmLoadtestPlan
        from controlplane_tool.scenario.scenarios.azure_vm_loadtest import AzureVmLoadtestPlan
        from controlplane_tool.scenario.scenarios.k3s_junit_curl import K3sJunitCurlPlan
        from controlplane_tool.scenario.scenarios.helm_stack import HelmStackPlan
        from controlplane_tool.scenario.scenarios.cli_stack import CliStackPlan
        from controlplane_tool.scenario.scenarios.cli_vm import CliVmPlan
        from controlplane_tool.scenario.scenarios.cli_host import CliHostPlan
        if isinstance(plan, (TwoVmLoadtestPlan, AzureVmLoadtestPlan, K3sJunitCurlPlan, HelmStackPlan, CliStackPlan, CliVmPlan, CliHostPlan)):
            plan.run(event_listener=event_listener)
        else:
            self.execute(plan, event_listener=event_listener)
```

**`run_all()` (righe 689–701):**
```python
            from controlplane_tool.scenario.scenarios.two_vm_loadtest import TwoVmLoadtestPlan
            from controlplane_tool.scenario.scenarios.azure_vm_loadtest import AzureVmLoadtestPlan
            from controlplane_tool.scenario.scenarios.k3s_junit_curl import K3sJunitCurlPlan
            from controlplane_tool.scenario.scenarios.helm_stack import HelmStackPlan
            from controlplane_tool.scenario.scenarios.cli_stack import CliStackPlan
            from controlplane_tool.scenario.scenarios.cli_vm import CliVmPlan
            from controlplane_tool.scenario.scenarios.cli_host import CliHostPlan
            _BUILDER_TYPES = (TwoVmLoadtestPlan, AzureVmLoadtestPlan, K3sJunitCurlPlan, HelmStackPlan, CliStackPlan, CliVmPlan, CliHostPlan)
            for plan in plans:
                if isinstance(plan, _BUILDER_TYPES):
                    plan.run()
                else:
                    self._execute_steps(plan)
```

**`ScenarioPlan` (legacy dataclass, righe 43–63):**
```python
@dataclass(frozen=True)
class ScenarioPlan:
    scenario: ScenarioDefinition
    request: E2eRequest
    steps: list[ScenarioPlanStep]
    executor: "Callable[[ScenarioPlan], None] | None" = field(...)

    @property
    def task_ids(self) -> list[str]: ...

    def run(self) -> None:
        if self.executor is None:
            raise RuntimeError("ScenarioPlan.run() requires an executor...")
        self.executor(self)
```

**Dopo Piano 13:**
```python
# run():
        if isinstance(plan, ScenarioPlan):
            self.execute(plan, event_listener=event_listener)
        else:
            plan.run(event_listener=event_listener)

# run_all():
            for plan in plans:
                if isinstance(plan, ScenarioPlan):
                    self._execute_steps(plan)
                else:
                    plan.run()
```

**Perché funziona:** `ScenarioPlan` è il tipo legacy, restituito da `plan()` solo per scenari locali (docker, buildpack, ecc.). I 7 builder tipizzati (`TwoVmLoadtestPlan`, ...) sono dataclass indipendenti — `isinstance(builder, ScenarioPlan)` è sempre False. Builder futuri seguiranno lo stesso pattern e non richiederanno modifiche.

**Semantica teardown preservata:**
- Scenari locali via `execute()` → include `_should_teardown()` (no-op per scenari senza VM)
- Builder via `plan.run()` → chiama `_execute_steps()` direttamente (teardown integrato nei passi del piano o gestito da `run_all()`)

---

## File Structure

**Modificati:**
- `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py` — `run()` e `run_all()`
- `tools/controlplane/tests/test_e2e_runner.py` — test esplicito che il dispatch funziona senza enumerazione

---

## Task 1: Inversion del dispatch in run() e run_all()

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py:645-656` e `:689-701`
- Test: `tools/controlplane/tests/test_e2e_runner.py`

- [ ] **Step 1: Scrivi test esplicito che fallisce**

Leggi le ultime righe di `test_e2e_runner.py`, poi aggiungi:

```python
def test_run_dispatches_new_builder_without_explicit_registration(tmp_path: Path) -> None:
    """run() must dispatch any non-ScenarioPlan object via plan.run() — no registration needed."""
    from unittest.mock import MagicMock
    from controlplane_tool.e2e.e2e_runner import ScenarioPlan as LegacyPlan

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)

    # Simulate a new builder plan (NOT an instance of the legacy ScenarioPlan dataclass)
    fake_builder = MagicMock(spec_set=["task_ids", "run", "request", "steps", "scenario"])
    fake_builder.task_ids = ["step.one"]
    fake_request = E2eRequest(scenario="cli", runtime="java", vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"))
    fake_builder.request = fake_request

    assert not isinstance(fake_builder, LegacyPlan), "Sanity check: fake builder is not legacy ScenarioPlan"

    with patch.object(runner, "plan", return_value=fake_builder):
        runner.run(fake_request)

    fake_builder.run.assert_called_once()
```

Run to confirm it fails:
```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py::test_run_dispatches_new_builder_without_explicit_registration -v 2>&1 | tail -8
```
Expected: FAIL — `run()` still uses the 7-tuple isinstance which doesn't match `MagicMock`.

- [ ] **Step 2: Aggiorna `run()` — inverti il dispatch**

Leggi le righe 644–657 di `e2e_runner.py`.

Trova il blocco:
```python
        from controlplane_tool.scenario.scenarios.two_vm_loadtest import TwoVmLoadtestPlan
        from controlplane_tool.scenario.scenarios.azure_vm_loadtest import AzureVmLoadtestPlan
        from controlplane_tool.scenario.scenarios.k3s_junit_curl import K3sJunitCurlPlan
        from controlplane_tool.scenario.scenarios.helm_stack import HelmStackPlan
        from controlplane_tool.scenario.scenarios.cli_stack import CliStackPlan
        from controlplane_tool.scenario.scenarios.cli_vm import CliVmPlan
        from controlplane_tool.scenario.scenarios.cli_host import CliHostPlan
        if isinstance(plan, (TwoVmLoadtestPlan, AzureVmLoadtestPlan, K3sJunitCurlPlan, HelmStackPlan, CliStackPlan, CliVmPlan, CliHostPlan)):
            plan.run(event_listener=event_listener)
        else:
            self.execute(plan, event_listener=event_listener)
```

Sostituisci con:
```python
        if isinstance(plan, ScenarioPlan):
            self.execute(plan, event_listener=event_listener)
        else:
            plan.run(event_listener=event_listener)
```

- [ ] **Step 3: Aggiorna `run_all()` — inverti il dispatch**

Leggi le righe 688–702 di `e2e_runner.py`.

Trova il blocco:
```python
            from controlplane_tool.scenario.scenarios.two_vm_loadtest import TwoVmLoadtestPlan
            from controlplane_tool.scenario.scenarios.azure_vm_loadtest import AzureVmLoadtestPlan
            from controlplane_tool.scenario.scenarios.k3s_junit_curl import K3sJunitCurlPlan
            from controlplane_tool.scenario.scenarios.helm_stack import HelmStackPlan
            from controlplane_tool.scenario.scenarios.cli_stack import CliStackPlan
            from controlplane_tool.scenario.scenarios.cli_vm import CliVmPlan
            from controlplane_tool.scenario.scenarios.cli_host import CliHostPlan
            _BUILDER_TYPES = (TwoVmLoadtestPlan, AzureVmLoadtestPlan, K3sJunitCurlPlan, HelmStackPlan, CliStackPlan, CliVmPlan, CliHostPlan)
            for plan in plans:
                if isinstance(plan, _BUILDER_TYPES):
                    plan.run()
                else:
                    self._execute_steps(plan)
```

Sostituisci con:
```python
            for plan in plans:
                if isinstance(plan, ScenarioPlan):
                    self._execute_steps(plan)
                else:
                    plan.run()
```

- [ ] **Step 4: Verifica che il test nuovo passi**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py::test_run_dispatches_new_builder_without_explicit_registration -v 2>&1 | tail -6
```
Expected: PASS.

- [ ] **Step 5: Suite completa**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q --tb=short 2>&1 | tail -8
```
Expected: 1066 passed, solo `test_default_two_vm_k6_script_reads_payload_in_init_context` fallisce.

- [ ] **Step 6: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py \
    tools/controlplane/tests/test_e2e_runner.py && \
git commit -m "$(cat <<'EOF'
refactor: invert run/run_all dispatch — check legacy ScenarioPlan, not builder tuple

Replace the 7-type isinstance tuple in run() and run_all() with a single
isinstance(plan, ScenarioPlan) check. Typed builders are never instances of
the legacy ScenarioPlan dataclass, so new builders dispatch correctly
without any registration in run() or run_all().

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Verifica Finale

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py -q 2>&1 | tail -5
```

Dopo questo piano:
- `run()` e `run_all()` non importano più nessun builder tipizzato
- Aggiungere un builder futuro richiede solo: creare il file builder + aggiornare `plan()` e `plan_all()` (non `run()` / `run_all()`)
- Il dispatch usa il tipo legacy come sentinel — preciso e stabile

---

## Note su Piano 14

Piano 14 (futuro): `plan_all()` usa ancora `_planner.vm_backed_steps(request, include_bootstrap=...)` per i builder cli/cli-host/cli-stack costruiti inline. Potrebbe unificare usando i factory `build_cli_vm_plan` / `build_cli_host_plan` passando `include_bootstrap` come parametro — ma questo richiederebbe cambiare le firme dei factory e aumenta la complessità. Valutare solo se serve.
