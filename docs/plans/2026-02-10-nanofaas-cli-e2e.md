# E2E Test Suite for Nanofaas CLI Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Create an E2E test suite that verifies the `nanofaas-cli` functionality against a real `nanofaas` distribution running in a k3s environment.

**Architecture:** Reuse the Multipass/k3s setup logic from `scripts/e2e-k3s-curl.sh`. Create a new script `scripts/e2e-cli.sh` that builds the CLI, deploys the control plane, and then executes a series of CLI commands to verify its behavior.

**Tech Stack:** Bash, Multipass, k3s, Java 21, Picocli, GraalVM (optional for native image).

---

### Task 1: Create the E2E CLI Script Skeleton

**Files:**
- Create: `scripts/e2e-cli.sh`

**Step 1: Copy base logic from `e2e-k3s-curl.sh`**

Copy the VM setup, k3s installation, project syncing, and deployment logic.

**Step 2: Add CLI building logic**

Ensure `nanofaas-cli` is built inside the VM.

**Step 3: Define CLI test functions**

Add functions for:
- `test_cli_config`: Verify the CLI can be configured (or use env vars).
- `test_cli_fn_list`: Verify `nanofaas fn list` works.
- `test_cli_fn_apply`: Verify `nanofaas fn apply` registers a function.
- `test_cli_invoke`: Verify `nanofaas invoke` works.
- `test_cli_fn_delete`: Verify `nanofaas fn delete` works.

### Task 2: Implement CLI Build and Setup

**Files:**
- Modify: `scripts/e2e-cli.sh`

**Step 1: Implement `build_cli` function**

Build the CLI as a shadow jar (or native image if preferred, but shadow jar is faster to build for tests).

**Step 2: Implement `setup_cli_env` function**

Set environment variables `NANOFAAS_ENDPOINT` and `NANOFAAS_NAMESPACE` to point to the control plane in k3s.

### Task 3: Implement Functional Tests in `e2e-cli.sh`

**Files:**
- Modify: `scripts/e2e-cli.sh`

**Step 1: Implement `test_cli_fn_list`**

Initially it should be empty.

**Step 2: Implement `test_cli_fn_apply`**

Use a sample function definition.

**Step 3: Implement `test_cli_invoke`**

Verify sync invocation.

**Step 4: Implement `test_cli_fn_delete`**

Verify the function is removed.

### Task 4: Verify the Test Suite

**Step 1: Run the new script**

Run: `./scripts/e2e-cli.sh`
Expected: All tests pass.

**Step 2: Commit**

```bash
git add scripts/e2e-cli.sh
git commit -m "test: add E2E test suite for nanofaas-cli"
```
