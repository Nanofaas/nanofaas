# TUI Provider Discovery Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Keep Azure and Proxmox visible in the Environment picker while ensuring their `.yaml.example` templates are never executed.

**Architecture:** Extend the existing Environment picker with opt-in setup entries only when the conventional concrete provider file is missing. Selecting setup guidance reuses the shared static chrome and returns to a freshly rebuilt picker; workflow execution continues to accept only concrete `*.yaml` paths.

**Tech Stack:** Python 3.13, prompt_toolkit/Rich via `tui-toolkit`, pytest, Ruff, basedpyright.

---

### Task 1: Add safe provider setup discovery

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/tui/app.py:197-224`
- Modify: `tools/controlplane/tests/test_tui_app.py:829-850`

**Step 1: Confirm impact before editing**

Run GitNexus upstream impact for `NanofaasTUI`. The current index reports MEDIUM risk with six direct dependents and no indexed process. Stop and report before editing if the refreshed result becomes HIGH or CRITICAL.

**Step 2: Write failing picker tests**

Add tests that create `azure.yaml.example` and `proxmox.yaml.example` beside a concrete local environment, then assert the offered values contain:

```python
[
    str(environment_path),
    "setup:azure",
    "setup:proxmox",
]
```

Assert their labels are `Azure (setup required)` and `Proxmox (setup required)`, and assert no `.yaml.example` path is offered.

Add a second test with concrete `azure.yaml` and `proxmox.yaml` files. Assert the concrete paths are offered and the matching setup values are absent.

**Step 3: Run the tests and verify RED**

Run:

```bash
env UV_CACHE_DIR=/tmp/mcfaas-provider-uv-cache uv run --locked \
  pytest tests/test_tui_app.py -k "provider_setup or executable_yaml" -q
```

Expected: FAIL because setup choices do not exist.

**Step 4: Implement the minimal discovery data**

In `app.py`, add one small constant describing only the two shipped managed-provider templates:

```python
_PROVIDER_SETUP = {
    "azure": ("Azure", "azure.yaml.example", "azure.yaml"),
    "proxmox": ("Proxmox", "proxmox.yaml.example", "proxmox.yaml"),
}
```

Do not add a provider registry or configuration editor.

**Step 5: Make `_select_environment()` rebuild choices**

Wrap the existing selection in a loop. Continue listing sorted concrete `*.yaml` files. For each setup definition, append a `Choice` only when the template exists and the conventional concrete target does not.

When `setup:<provider>` is selected, render guidance with `_show_static()` and continue the loop. Guidance must include the exact command:

```text
cp tools/controlplane/environments/<provider>.yaml.example tools/controlplane/environments/<provider>.yaml
```

Azure guidance mentions provider values, `ssh_key_path`, and `az login`. Proxmox guidance mentions host/node/template/key values and the password environment variable named by `password_env`. Never copy or parse the template as an executable environment.

**Step 6: Add failing and passing setup-flow tests**

Drive answers `setup:azure`, `setup:proxmox`, then the concrete local environment. Spy on `_show_static()` and assert:

- both setup screens use the Environment breadcrumb;
- both exact copy commands and credential requirements appear;
- selection returns the concrete local path;
- the picker is rebuilt after each acknowledgement.

Add a workflow-boundary test proving `_environment()` is never called with a `.yaml.example` path after a setup selection.

Run the focused app tests and verify GREEN.

**Step 7: Run navigation and chrome regressions**

Run:

```bash
env UV_CACHE_DIR=/tmp/mcfaas-provider-uv-cache uv run --locked pytest -q \
  tests/test_tui_app.py tests/test_tui_navigation.py tests/test_tui_chrome.py
```

Expected: PASS. Explicit Back, Esc, Ctrl+C, and the single-header invariant remain unchanged.

**Step 8: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/tui/app.py \
  tools/controlplane/tests/test_tui_app.py
git commit -m "feat: expose TUI provider setup guidance"
```

### Task 2: Document discovery and verify the product surface

**Files:**
- Modify: `tools/controlplane/README.md:40-52,75-100`
- Modify: `docs/quickstart.md:87-106`
- Modify: `tools/controlplane/tests/test_product_docs.py:27-48`

**Step 1: Write failing documentation assertions**

For both the tool README and quickstart, assert independently that the text states:

- Azure and Proxmox remain visible as `setup required` entries;
- `.yaml.example` files are templates and are never executed;
- the user must copy to `azure.yaml` or `proxmox.yaml` and fill provider credentials.

**Step 2: Run and verify RED**

```bash
env UV_CACHE_DIR=/tmp/mcfaas-provider-uv-cache uv run --locked \
  pytest tests/test_product_docs.py -q
```

Expected: FAIL because the TUI-specific discovery rule is not documented.

**Step 3: Update both documents**

Add the setup-required behavior next to the TUI instructions and clarify that selecting guidance performs no file write and no workflow execution. Keep the existing CLI copy instructions and provider lifecycle details.

**Step 4: Run focused and complete verification**

```bash
env UV_CACHE_DIR=/tmp/mcfaas-provider-uv-cache uv run --locked pytest -q
env UV_CACHE_DIR=/tmp/mcfaas-provider-uv-cache uv run --locked controlplane-quality
git diff --check
```

Run from `tools/controlplane` for the Python commands. Expected: 0 failures and all quality/import contracts kept.

Run GitNexus change detection before committing. Expected scope: `NanofaasTUI`, TUI tests, product documentation; no workflow engine, provider implementation, scenario builder, or Java changes.

**Step 5: Commit**

```bash
git add tools/controlplane/README.md docs/quickstart.md \
  tools/controlplane/tests/test_product_docs.py
git commit -m "docs: explain TUI provider setup entries"
```

