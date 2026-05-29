# TUI Error Display — Structural Fix

> **For agentic workers:** Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mostrare errore e stack trace nel TUI quando un'azione del workflow fallisce, senza bypassare il sistema di eventi.

**Root cause:** In `run_live_workflow()`, quando `action` lancia un'eccezione, la sequenza di uscita dai context manager è:
1. Esce da `with bind_workflow_sink(sink)` → sink slegato
2. Esce da `with Live(...)` → display chiuso
3. L'eccezione arriva al handler in `app.py` che chiama `fail()` — ma il sink è già None → no-op silenzioso

**Fix strutturale:** Catturare l'eccezione **dentro** `with bind_workflow_sink(sink)`, mentre sink e Live sono ancora attivi. Chiamare `fail()` lì (sink attivo → l'errore va nel log panel), aggiornare il display, poi ri-lanciare.

**Workaround da ripristinare:** Il commit `2bb876d` ha sostituito `fail()` con `console.print(Panel(...))` in `app.py` — questo bypassa il sistema di eventi. Va ripristinato.

---

## Stato attuale (dopo commit 2bb876d)

**`app.py` riga 660–667 — workaround da ripristinare:**
```python
                except Exception as exc:  # noqa: BLE001
                    tb = traceback.format_exc(limit=8).strip()
                    body = f"[bold red]✗  {escape(str(exc))}[/]"
                    if tb:
                        body += f"\n\n[dim]{escape(tb)}[/]"
                    console.print()
                    console.print(Panel(body, border_style="red", padding=(0, 2)))
                    console.print()
```

**`workflow_controller.py` — nessun error handling in `run_live_workflow`:**
```python
        with Live(...) as active_live:
            ...
            try:
                with bind_workflow_sink(sink):
                    result = action(dashboard, sink)   # ← eccezione non catturata
                    _refresh()
                    return result
            finally:
                key_listener.stop()
```

---

## Stato target

**`app.py` — ripristinato:**
```python
                except Exception as exc:  # noqa: BLE001
                    fail(str(exc), detail=traceback.format_exc(limit=8))
```

**`workflow_controller.py` — fix strutturale:**
```python
import traceback as _traceback
from workflow_tasks import bind_workflow_sink, fail as _fail

        with Live(...) as active_live:
            ...
            try:
                with bind_workflow_sink(sink):
                    try:
                        result = action(dashboard, sink)
                    except Exception as exc:
                        _fail(str(exc), detail=_traceback.format_exc(limit=8))
                        _refresh()
                        raise
                    _refresh()
                    return result
            finally:
                key_listener.stop()
```

Quando `action` lancia:
1. `_fail()` chiama `_emit()` con sink attivo → `TuiWorkflowSink.emit()` → `dashboard.apply_event()` → errore nel log panel del Live display
2. `_refresh()` aggiorna il display (l'utente vede l'errore premendo `l`)
3. `raise` ri-lancia → esce da `bind_workflow_sink` → esce da `Live` → arriva a `app.py`
4. `app.py` chiama `fail()` — sink None → no-op (corretto, errore già mostrato)

---

## File toccati

- `tools/controlplane/src/controlplane_tool/tui/app.py` — ripristino
- `tools/controlplane/src/controlplane_tool/tui/workflow_controller.py` — fix strutturale
- `tools/controlplane/tests/test_tui_workflow_controller.py` — nuovo test

---

## Task 1: Ripristina `app.py` e aggiungi test failing

- [ ] **Step 1: Ripristina `app.py`**

Leggi le righe 660–668 di `app.py`. Sostituisci il workaround con:

```python
                except Exception as exc:  # noqa: BLE001
                    fail(str(exc), detail=traceback.format_exc(limit=8))
```

- [ ] **Step 2: Aggiungi test failing in `test_tui_workflow_controller.py`**

Leggi il file. Aggiungi dopo i test esistenti:

```python
def test_run_live_workflow_calls_fail_when_action_raises() -> None:
    """When action raises, fail() must be called with the error while the sink is still active."""
    from unittest.mock import patch, call
    from controlplane_tool.tui.workflow import WorkflowDashboard

    controller = TuiWorkflowController(event_applier=MagicMock())

    def failing_action(dashboard: WorkflowDashboard, sink) -> None:
        raise RuntimeError("step 30 failed: connection refused")

    with patch("rich.live.Live") as mock_live_cls, \
         patch("controlplane_tool.tui.workflow.WorkflowKeyListener"), \
         patch("controlplane_tool.tui.workflow_controller._fail") as mock_fail:
        mock_live = MagicMock()
        mock_live.__enter__ = MagicMock(return_value=mock_live)
        mock_live.__exit__ = MagicMock(return_value=False)
        mock_live_cls.return_value = mock_live

        with pytest.raises(RuntimeError, match="connection refused"):
            controller.run_live_workflow(
                title="Test",
                summary_lines=["Test scenario"],
                planned_steps=["step one", "step two"],
                action=failing_action,
            )

    mock_fail.assert_called_once()
    call_args = mock_fail.call_args
    assert "connection refused" in call_args.args[0]
    assert "detail" in call_args.kwargs
    assert call_args.kwargs["detail"]  # detail is non-empty (traceback)
```

Run per verificare che fallisce (il test usa `_fail` come alias — se non esiste nel module scope, il patch fallirà):

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_tui_workflow_controller.py::test_run_live_workflow_calls_fail_when_action_raises -v 2>&1 | tail -8
```

Expected: FAIL — `_fail` non esiste ancora in `workflow_controller.py`, il patch non trova il target.

- [ ] **Step 3: Verifica suite dopo ripristino app.py**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q --tb=short 2>&1 | tail -6
```

Expected: 1070 passed (il test nuovo conta come failure separata, oppure errore di patch).

- [ ] **Step 4: Commit parziale**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/controlplane/src/controlplane_tool/tui/app.py \
    tools/controlplane/tests/test_tui_workflow_controller.py && \
git commit -m "$(cat <<'EOF'
revert: restore fail() call in app.py exception handler

The console.print workaround bypassed the workflow event system.
Restored to fail() — the structural fix lands in workflow_controller.py.
Added failing test for the structural fix.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Fix strutturale in `workflow_controller.py`

- [ ] **Step 1: Aggiungi import di `fail` e `traceback` in `workflow_controller.py`**

Leggi le righe 1–18. Trova:
```python
from workflow_tasks import bind_workflow_sink
```

Sostituisci con:
```python
import traceback as _traceback

from workflow_tasks import bind_workflow_sink
from workflow_tasks import fail as _fail
```

- [ ] **Step 2: Aggiungi try/except intorno ad `action()` in `run_live_workflow`**

Leggi le righe 55–67. Trova:
```python
            try:
                with bind_workflow_sink(sink):
                    result = action(dashboard, sink)
                    _refresh()
                    return result
            finally:
                key_listener.stop()
```

Sostituisci con:
```python
            try:
                with bind_workflow_sink(sink):
                    try:
                        result = action(dashboard, sink)
                    except Exception as exc:
                        _fail(str(exc), detail=_traceback.format_exc(limit=8))
                        _refresh()
                        raise
                    _refresh()
                    return result
            finally:
                key_listener.stop()
```

- [ ] **Step 3: Verifica che il test passi**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_tui_workflow_controller.py -v 2>&1 | tail -8
```

Expected: 4 passed (3 esistenti + 1 nuovo).

- [ ] **Step 4: Suite completa**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q --tb=short 2>&1 | tail -6
```

Expected: 1071 passed, 0 failed.

- [ ] **Step 5: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/controlplane/src/controlplane_tool/tui/workflow_controller.py && \
git commit -m "$(cat <<'EOF'
fix: show workflow error in TUI log panel when action fails

Catch exceptions inside run_live_workflow while the workflow sink is
still bound. Call fail() with the error and traceback so the message
appears in the log panel before the Live display closes.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Verifica finale

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q 2>&1 | tail -4
# → 1071 passed, 0 failed

grep -n "_fail\|_traceback" \
    /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane/src/controlplane_tool/tui/workflow_controller.py
# → mostra i due import e l'uso nel try/except
```

---

## Note

**Comportamento dopo il fix:** quando il passo 30 fallisce, l'errore appare nel **log panel** del TUI (premendo `l`). Il pannello delle fasi mostra ✗ sullo step. L'errore non è inline nei passi (comportamento intenzionale per stack trace multi-riga — verificato da `test_apply_e2e_step_event_failure_keeps_error_out_of_step_detail`).

**`app.py` handler invariato:** `fail(str(exc), detail=...)` rimane ma è no-op (sink già slegato). Gestisce correttamente le eccezioni da azioni che non usano `run_live_workflow`.
