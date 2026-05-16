# Workflow Task Composition — Piano 12: Rimozione fallback VM dead in plan() e plan_all()

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rimuovere due rami dead in `e2e_runner.py` introdotti dai piani precedenti — il fallback generico VM in `plan()` e il ramo `else` in `plan_all()` — sostituendoli con `ValueError` espliciti.

**Architecture:** Dopo Piano 11 tutti gli scenari VM noti hanno builder tipizzati. Il ramo `steps = self._planner.vm_backed_steps(request)` in `plan()` (riga 354) non è mai raggiunto; il ramo `else: plans.append(ScenarioPlan(...))` in `plan_all()` (riga 457) è ugualmente irraggiungibile. `ScenarioPlanner.vm_backed_steps()` rimane vivo — chiamato dai builder factory di cli/cli-host e da `plan_all()` per i passi di cli/cli-stack/cli-host — quindi non viene toccato.

**Tech Stack:** Python 3.11+. 2 file toccati: `e2e_runner.py` + `test_e2e_runner.py`.

---

## Background — stato corrente

**`plan()` (righe 347–357):**
```python
        if scenario.requires_vm:
            if request.scenario == "cli":
                from controlplane_tool.scenario.scenarios.cli_vm import build_cli_vm_plan
                return build_cli_vm_plan(self, request)
            if request.scenario == "cli-host":
                from controlplane_tool.scenario.scenarios.cli_host import build_cli_host_plan
                return build_cli_host_plan(self, request)
            steps = self._planner.vm_backed_steps(request)   # ← DEAD per scenari noti
        else:
            steps = self._planner.local_steps(request)
        return ScenarioPlan(scenario=scenario, request=request, steps=steps)
```

**`plan_all()` (righe 446–459):**
```python
                steps = self._planner.vm_backed_steps(request, include_bootstrap=not vm_bootstrap_planned)
                vm_bootstrap_planned = True
                if scenario.name == "cli-stack":
                    ...CliStackPlan...
                elif scenario.name == "cli":
                    ...CliVmPlan...
                elif scenario.name == "cli-host":
                    ...CliHostPlan...
                else:
                    plans.append(ScenarioPlan(scenario=scenario, request=request, steps=steps))  # ← DEAD
                continue
```

**Dopo Piano 12:**
```python
# plan() — VM fallback
        if scenario.requires_vm:
            if request.scenario == "cli":
                from controlplane_tool.scenario.scenarios.cli_vm import build_cli_vm_plan
                return build_cli_vm_plan(self, request)
            if request.scenario == "cli-host":
                from controlplane_tool.scenario.scenarios.cli_host import build_cli_host_plan
                return build_cli_host_plan(self, request)
            raise ValueError(f"Unsupported VM-backed scenario: {request.scenario!r}")
        steps = self._planner.local_steps(request)
        return ScenarioPlan(scenario=scenario, request=request, steps=steps)

# plan_all() — else branch
                elif scenario.name == "cli-host":
                    ...CliHostPlan...
                else:
                    raise ValueError(f"Unsupported VM-backed scenario in plan_all(): {scenario.name!r}")
                continue
```

---

## File Structure

**Modificati:**
- `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py` — `plan()` e `plan_all()`
- `tools/controlplane/tests/test_e2e_runner.py` — nuovo test guard

---

## Task 1: Rimozione dead branches e test guard

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py:347-357` e `:457-458`
- Test: `tools/controlplane/tests/test_e2e_runner.py`

- [ ] **Step 1: Scrivi il test fallente**

Leggi le ultime righe di `test_e2e_runner.py`, poi aggiungi:

```python
def test_plan_raises_for_unknown_vm_scenario(tmp_path: Path) -> None:
    """plan() must raise ValueError for VM scenarios without a builder."""
    import pytest

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    with pytest.raises(ValueError, match="Unsupported VM-backed scenario"):
        runner.plan(E2eRequest(
            scenario="cli",           # Use cli but patch catalog to simulate unknown scenario
            runtime="java",
            vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        ))
```

**Nota:** Il test sopra non funzionerà direttamente perché `cli` è ora un scenario noto. Usa invece questo pattern che bypassa il dispatch dei builder conosciuti:

```python
def test_plan_raises_for_unknown_vm_scenario(tmp_path: Path) -> None:
    """plan() must raise ValueError for VM scenarios without a builder."""
    import pytest
    from unittest.mock import patch
    from controlplane_tool.scenario.catalog import ScenarioDefinition

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    unknown_scenario = ScenarioDefinition(
        name="unknown-vm-scenario",
        requires_vm=True,
        supported_runtimes=["java"],
    )
    request = E2eRequest(
        scenario="unknown-vm-scenario",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )
    with patch(
        "controlplane_tool.e2e.e2e_runner.resolve_scenario",
        return_value=unknown_scenario,
    ):
        with pytest.raises(ValueError, match="Unsupported VM-backed scenario"):
            runner.plan(request)
```

- [ ] **Step 2: Verifica che il test fallisce (plan() ritorna ancora ScenarioPlan invece di alzare)**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py::test_plan_raises_for_unknown_vm_scenario -v 2>&1 | tail -6
```
Expected: FAIL — `plan()` non alza ancora `ValueError`.

- [ ] **Step 3: Aggiorna `plan()` — rimuovi dead fallback VM**

Leggi prima le righe 344–360 di `e2e_runner.py`.

Trova il blocco:
```python
        if scenario.requires_vm:
            if request.scenario == "cli":
                from controlplane_tool.scenario.scenarios.cli_vm import build_cli_vm_plan
                return build_cli_vm_plan(self, request)
            if request.scenario == "cli-host":
                from controlplane_tool.scenario.scenarios.cli_host import build_cli_host_plan
                return build_cli_host_plan(self, request)
            steps = self._planner.vm_backed_steps(request)
        else:
            steps = self._planner.local_steps(request)
        return ScenarioPlan(scenario=scenario, request=request, steps=steps)
```

Sostituisci con:
```python
        if scenario.requires_vm:
            if request.scenario == "cli":
                from controlplane_tool.scenario.scenarios.cli_vm import build_cli_vm_plan
                return build_cli_vm_plan(self, request)
            if request.scenario == "cli-host":
                from controlplane_tool.scenario.scenarios.cli_host import build_cli_host_plan
                return build_cli_host_plan(self, request)
            raise ValueError(f"Unsupported VM-backed scenario: {request.scenario!r}")
        steps = self._planner.local_steps(request)
        return ScenarioPlan(scenario=scenario, request=request, steps=steps)
```

- [ ] **Step 4: Aggiorna `plan_all()` — sostituisci else dead con ValueError**

Leggi prima le righe 446–462 di `e2e_runner.py`.

Trova il blocco `else` finale:
```python
                else:
                    plans.append(ScenarioPlan(scenario=scenario, request=request, steps=steps))
                continue
```

Sostituisci con:
```python
                else:
                    raise ValueError(f"Unsupported VM-backed scenario in plan_all(): {scenario.name!r}")
                continue
```

- [ ] **Step 5: Verifica che il test guard passi**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py::test_plan_raises_for_unknown_vm_scenario -v 2>&1 | tail -6
```
Expected: PASS.

- [ ] **Step 6: Suite completa**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q --tb=short 2>&1 | tail -8
```
Expected: 1065 passed, solo `test_default_two_vm_k6_script_reads_payload_in_init_context` fallisce.

- [ ] **Step 7: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py \
    tools/controlplane/tests/test_e2e_runner.py && \
git commit -m "$(cat <<'EOF'
refactor: replace dead VM fallbacks in plan() and plan_all() with ValueError

All known VM scenarios now have typed builders. The generic
vm_backed_steps() fallback in plan() and the ScenarioPlan else-branch
in plan_all() were unreachable — replaced with explicit ValueError guards.

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
- `plan()` alza `ValueError` per scenari VM sconosciuti invece di ritornare silenziosamente un piano vuoto
- `plan_all()` alza `ValueError` per scenari VM sconosciuti invece di creare un `ScenarioPlan` generico
- `ScenarioPlanner.vm_backed_steps()` rimane vivo (usato da builder factories e `plan_all()` per cli/cli-stack/cli-host)
- Nessuna variabile `steps` non inizializzata nel path VM di `plan()`

---

## Note su Piano 13

Piano 13 (futuro): se si vuole unificare il dispatch in `run()` e `run_all()` usando il Protocol `ScenarioPlan` invece della tuple `isinstance`, si può introdurre una ScenarioPlan Protocol base e usare `isinstance(plan, ScenarioPlanProtocol)` ovunque. Oppure si può spostare `execute()` e `_execute_steps()` come metodi del Protocol, eliminando il double-dispatch.
