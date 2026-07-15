# Relocate Watchdog Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Move the shared watchdog runtime from the repository root to `runtimes/watchdog/` without changing its build, image, test, or runtime behavior.

**Architecture:** Use a Git-aware directory move and update every repository-owned path reference. Do not leave a compatibility directory or duplicate sources; the new path is authoritative.

**Tech Stack:** Git, Rust/Cargo, Docker, Gradle, Bash, GitHub Actions, Markdown.

---

### Task 1: Inventory and move the runtime

**Files:**
- Move: `watchdog/` → `runtimes/watchdog/`

**Step 1: Inventory references**

Run a repository-wide fixed-string search for `watchdog/`, excluding generated directories.

**Step 2: Move with Git**

```bash
mkdir -p runtimes
git mv watchdog runtimes/watchdog
```

**Step 3: Confirm structure**

Verify the old root path no longer exists and the new Cargo manifest exists.

### Task 2: Update owned references and documentation

**Files:**
- Modify: `.github/workflows/gitops.yml`
- Modify: `docs/testing.md`
- Modify: `docs/function-pod-architecture.md`
- Modify: `runtimes/watchdog/Dockerfile.combined`
- Modify: `runtimes/watchdog/README.md`
- Modify: all remaining repository-owned references discovered in Task 1

**Step 1: Replace paths deliberately**

Update only references that point to the moved runtime. Preserve container image names, binary names, and command semantics.

**Step 2: Ensure Docker build contexts remain correct**

Change Dockerfile `COPY watchdog/...` instructions to `COPY runtimes/watchdog/...` while keeping the repository root as build context.

### Task 3: Verify the relocated component

**Step 1: Run component tests**

```bash
cargo test --manifest-path runtimes/watchdog/Cargo.toml
bash runtimes/watchdog/test-local.sh
```

**Step 2: Verify the combined image**

```bash
docker build -f runtimes/watchdog/Dockerfile.combined -t nanofaas/watchdog-combined:test .
```

Start the image, verify `/health` and a warm invocation, then stop and remove it.

**Step 3: Audit scope**

Run `git diff --check`, GitNexus change detection, and ensure only expected relocation files changed.

**Step 4: Commit**

```bash
git add runtimes .github docs
git commit -m "Move watchdog runtime under runtimes"
```
