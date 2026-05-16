# Workflow Task Composition — Piano 6: plan_all() Typed Builders

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrare `plan_all()` a restituire `TwoVmLoadtestPlan`/`AzureVmLoadtestPlan` tipizzati per i due scenari loadtest, eliminando l'ultimo uso di `plan_recipe_steps` direttamente in `e2e_runner.py` per quei due scenari.

**Architecture:** La modifica è chirurgica: nel loop di `plan_all()`, i due rami `two-vm-loadtest` e `azure-vm-loadtest` passano da `ScenarioPlan(scenario, request, steps)` ai rispettivi builder. `run_all()` non cambia: chiama già `_execute_steps(plan)` che funziona via duck typing sia con `ScenarioPlan` che con i builder (entrambi hanno `.steps` e `.request`). I test esistenti continuano a passare perché accedono `plans[0].steps`, `plans[0].request` — disponibili su tutti i builder.

**Tech Stack:** Python 3.11+, dataclasses, builder pattern (da Piano 3). Solo `e2e_runner.py` e `test_e2e_runner.py` sono toccati.

---

## Background — stato attuale di `plan_all()`

In `e2e_runner.py` (righe 418–430), il loop di `plan_all()` per gli scenari VM:

```python
if scenario.requires_vm:
    if scenario.name in {"two-vm-loadtest", "azure-vm-loadtest"}:
        steps = plan_recipe_steps(         # <-- chiamata diretta, non usa il builder
            self.paths.workspace_root,
            request,
            scenario.name,
            shell=self.shell,
            manifest_root=self.manifest_root,
            host_resolver=self._host_resolver,
        )
        vm_bootstrap_planned = True
        plans.append(ScenarioPlan(scenario=scenario, request=request, steps=steps))  # <-- non tipizzato
        continue
    steps = self._planner.vm_backed_steps(request, include_bootstrap=not vm_bootstrap_planned)
    vm_bootstrap_planned = True
    plans.append(ScenarioPlan(scenario=scenario, request=request, steps=steps))
    continue
```

Dopo la migrazione:

```python
if scenario.requires_vm:
    if scenario.name == "two-vm-loadtest":
        from controlplane_tool.scenario.scenarios.two_vm_loadtest import build_two_vm_loadtest_plan
        plans.append(build_two_vm_loadtest_plan(self, request))
        vm_bootstrap_planned = True
        continue
    if scenario.name == "azure-vm-loadtest":
        from controlplane_tool.scenario.scenarios.azure_vm_loadtest import build_azure_vm_loadtest_plan
        plans.append(build_azure_vm_loadtest_plan(self, request))
        vm_bootstrap_planned = True
        continue
    steps = self._planner.vm_backed_steps(request, include_bootstrap=not vm_bootstrap_planned)
    vm_bootstrap_planned = True
    plans.append(ScenarioPlan(scenario=scenario, request=request, steps=steps))
    continue
```

**Perché `run_all()` non cambia:** chiama `self._execute_steps(plan)` che accede solo `plan.steps` e `plan.request`. Entrambi sono disponibili su `TwoVmLoadtestPlan` e `AzureVmLoadtestPlan`. Duck typing funziona.

**Perché NON migriamo k3s-junit-curl / helm-stack / cli-stack in `plan_all()`:** quei scenari usano `_planner.vm_backed_steps(request, include_bootstrap=not vm_bootstrap_planned)`. Il parametro `include_bootstrap=False` evita di duplicare i passi VM bootstrap quando si eseguono più scenari in sequenza sullo stesso VM. I builder non supportano questo parametro — aggiungerlo è fuori scope e richiederebbe refactoring non banale.

---

## File Structure

**Modificati:**
- `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py:418–430` — ramo `two-vm-loadtest` e `azure-vm-loadtest` in `plan_all()`
- `tools/controlplane/tests/test_e2e_runner.py` — 2 nuovi test che verificano il tipo restituito da `plan_all()`

---

## Task 1: Migra `plan_all()` ai builder per i due scenari loadtest

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py:418–430`
- Test: `tools/controlplane/tests/test_e2e_runner.py`

- [ ] **Step 1: Leggi il contesto rilevante**

```bash
sed -n '415,437p' /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py
```

Verifica di vedere i rami `plan_recipe_steps` per `two-vm-loadtest` e `azure-vm-loadtest`.

- [ ] **Step 2: Scrivi i test che documentano il comportamento ATTESO**

Leggi `tools/controlplane/tests/test_e2e_runner.py` (i primi 20 import e le prime funzioni) per capire gli import usati, poi aggiungi in fondo al file:

```python
def test_plan_all_returns_typed_builder_for_two_vm_loadtest(tmp_path: Path) -> None:
    """plan_all() deve restituire TwoVmLoadtestPlan per two-vm-loadtest, non ScenarioPlan generico."""
    from controlplane_tool.scenario.scenarios.two_vm_loadtest import TwoVmLoadtestPlan

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plans = runner.plan_all(only=["two-vm-loadtest"])

    assert len(plans) == 1
    assert isinstance(plans[0], TwoVmLoadtestPlan), (
        f"Expected TwoVmLoadtestPlan, got {type(plans[0])}"
    )
    assert "functions.register" in plans[0].task_ids
    assert "loadgen.run_k6" in plans[0].task_ids


def test_plan_all_returns_typed_builder_for_azure_vm_loadtest(tmp_path: Path) -> None:
    """plan_all() deve restituire AzureVmLoadtestPlan per azure-vm-loadtest."""
    from controlplane_tool.scenario.scenarios.azure_vm_loadtest import AzureVmLoadtestPlan

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plans = runner.plan_all(only=["azure-vm-loadtest"])

    assert len(plans) == 1
    assert isinstance(plans[0], AzureVmLoadtestPlan), (
        f"Expected AzureVmLoadtestPlan, got {type(plans[0])}"
    )
    assert "functions.register" in plans[0].task_ids
```

**Nota sugli import**: il file usa già `Path`, `E2eRunner`, `RecordingShell` — tutti disponibili. `tmp_path` è un fixture pytest già usato nel file.

- [ ] **Step 3: Verifica che i test falliscano**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py::test_plan_all_returns_typed_builder_for_two_vm_loadtest -v 2>&1 | tail -8
```
Expected: `AssertionError: Expected TwoVmLoadtestPlan, got <class 'controlplane_tool.e2e.e2e_runner.ScenarioPlan'>`.

- [ ] **Step 4: Sostituisci il ramo loadtest in `plan_all()`**

In `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py`, trova e sostituisci il blocco (righe 418–430 circa):

```python
            if scenario.requires_vm:
                if scenario.name in {"two-vm-loadtest", "azure-vm-loadtest"}:
                    steps = plan_recipe_steps(
                        self.paths.workspace_root,
                        request,
                        scenario.name,
                        shell=self.shell,
                        manifest_root=self.manifest_root,
                        host_resolver=self._host_resolver,
                    )
                    vm_bootstrap_planned = True
                    plans.append(ScenarioPlan(scenario=scenario, request=request, steps=steps))
                    continue
                steps = self._planner.vm_backed_steps(request, include_bootstrap=not vm_bootstrap_planned)
                vm_bootstrap_planned = True
                plans.append(ScenarioPlan(scenario=scenario, request=request, steps=steps))
                continue
```

Con:

```python
            if scenario.requires_vm:
                if scenario.name == "two-vm-loadtest":
                    from controlplane_tool.scenario.scenarios.two_vm_loadtest import build_two_vm_loadtest_plan
                    plans.append(build_two_vm_loadtest_plan(self, request))
                    vm_bootstrap_planned = True
                    continue
                if scenario.name == "azure-vm-loadtest":
                    from controlplane_tool.scenario.scenarios.azure_vm_loadtest import build_azure_vm_loadtest_plan
                    plans.append(build_azure_vm_loadtest_plan(self, request))
                    vm_bootstrap_planned = True
                    continue
                steps = self._planner.vm_backed_steps(request, include_bootstrap=not vm_bootstrap_planned)
                vm_bootstrap_planned = True
                plans.append(ScenarioPlan(scenario=scenario, request=request, steps=steps))
                continue
```

- [ ] **Step 5: Verifica che i nuovi test passino**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py::test_plan_all_returns_typed_builder_for_two_vm_loadtest tests/test_e2e_runner.py::test_plan_all_returns_typed_builder_for_azure_vm_loadtest -v 2>&1 | tail -8
```
Expected: `2 passed`.

- [ ] **Step 6: Verifica che i test esistenti su `plan_all()` e `run_all()` passino**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py -k "plan_all or run_all or two_vm or e2e_all" -v 2>&1 | tail -20
```
Expected: tutti passano. I test esistenti accedono `plans[0].steps`, `plans[0].request`, `plans[0].scenario.name` — tutti disponibili sui builder.

**Se `test_e2e_all_vm_plan_bootstraps_shared_vm_once` fallisce:**  
Questo test verifica che ci sia UN SOLO passo "Ensure VM is running" in tutti i piani combinati. Il builder `TwoVmLoadtestPlan` include sempre bootstrap. Ma questo test usa `only=["k3s-junit-curl"]`, non loadtest — non è impattato.

**Se un test accede `plans[0].executor` (attributo solo del vecchio `ScenarioPlan` dataclass):**  
Aggiornalo per non accedere a `executor` — non fa parte del Protocol.

- [ ] **Step 7: Suite completa**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q --tb=short 2>&1 | tail -8
```
Expected: 1055+ passano, solo `test_default_two_vm_k6_script_reads_payload_in_init_context` fallisce (pre-esistente).

- [ ] **Step 8: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py \
    tools/controlplane/tests/test_e2e_runner.py && \
git commit -m "$(cat <<'EOF'
feat: plan_all() returns typed builders for two-vm and azure-vm loadtest scenarios

Removes last direct plan_recipe_steps usage in plan_all() for loadtest scenarios.
plan_all() now returns TwoVmLoadtestPlan/AzureVmLoadtestPlan, consistent with plan().
run_all() unchanged: _execute_steps() works with builders via duck typing.

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
- `plan_all()` ritorna `TwoVmLoadtestPlan` / `AzureVmLoadtestPlan` per i due loadtest (tipizzati)
- `plan_all()` ritorna `ScenarioPlan` generico per k3s-junit-curl, helm-stack, cli-stack, docker, buildpack, ecc. (invariato — `include_bootstrap` optimization preservata)
- `plan_recipe_steps` non è più chiamato direttamente in `plan_all()`
- Nessun altro file cambia

---

## Note su Piano 7

Piano 7 (futuro) potrà considerare:
- Aggiornare `run_all()` per usare `plan.run()` anche per i builder (con event_listener forwarding)
- Aggiornare la signature di `plan_all()` da `list[ScenarioPlan]` a un tipo Union/Protocol più preciso
- Valutare la migrazione di k3s-junit-curl/helm-stack/cli-stack in `plan_all()` con supporto `include_bootstrap`
- Rimozione di `plan_recipe_steps` da `e2e_runner.py` una volta che nessun caller diretto rimane
