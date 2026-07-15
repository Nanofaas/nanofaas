# Watchdog Hardening Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make the Rust watchdog reliably own its child processes, report warm-runtime failure, preserve quoted commands, and fail when one-shot results cannot be delivered.

**Architecture:** Keep the existing single binary and execution modes. Add direct argv parsing, one process-group lifecycle path, and platform-managed restart semantics for warm HTTP; avoid new public APIs or supervisor abstractions.

**Tech Stack:** Rust 2021, Tokio, Axum, nix, shlex, shell integration tests, Python fixtures, Docker, Gradle, GitHub Actions.

---

### Task 1: Parse commands and configuration strictly

**Files:**
- Modify: `watchdog/Cargo.toml`
- Modify: `watchdog/Cargo.lock`
- Modify: `watchdog/src/main.rs:43-167`

**Step 1: Write failing Rust tests**

Add `#[cfg(test)] mod tests` at the bottom of `main.rs` with focused tests:

```rust
#[test]
fn parse_command_preserves_quoted_arguments() {
    assert_eq!(
        parse_command("python3 -c 'print(\"hello world\")'").unwrap(),
        vec!["python3", "-c", "print(\"hello world\")"]
    );
}

#[test]
fn parse_command_rejects_unclosed_quote() {
    assert!(parse_command("python3 -c '").is_err());
}

#[test]
fn parse_u64_env_rejects_invalid_values() {
    assert!(parse_u64("TIMEOUT_MS", "soon").is_err());
}

#[test]
fn execution_mode_rejects_unknown_value() {
    assert!(ExecutionMode::parse("OTHER").is_err());
}
```

**Step 2: Run the tests and verify RED**

Run: `cargo test --manifest-path watchdog/Cargo.toml`

Expected: compilation fails because `parse_command`, `parse_u64`, and `ExecutionMode::parse` do not exist.

**Step 3: Implement the smallest parser change**

- Add the direct dependency `shlex = "1"`.
- Replace `split_whitespace()` with `shlex::split()` and reject an empty argv.
- Change unknown `EXECUTION_MODE` values from silent HTTP fallback to a configuration error.
- Parse `TIMEOUT_MS`, `READY_TIMEOUT_MS`, and `WARM_PORT` through strict helpers that name the invalid variable.
- Keep existing defaults when a variable is absent.

Do not add a second command environment variable or execute `/bin/sh -c`.

**Step 4: Run tests and formatting**

Run: `cargo fmt --manifest-path watchdog/Cargo.toml -- --check`

Run: `cargo test --manifest-path watchdog/Cargo.toml`

Expected: all new unit tests pass.

**Step 5: Commit**

```bash
git add watchdog/Cargo.toml watchdog/Cargo.lock watchdog/src/main.rs
git commit -m "Harden watchdog configuration parsing"
```

### Task 2: Own and reap STDIO process groups

**Files:**
- Modify: `watchdog/src/main.rs:308-469`
- Modify: `watchdog/tests/fixtures/stdio_handler.py`
- Modify: `watchdog/tests/integration/test_stdio_mode.sh`

**Step 1: Add a failing timeout-cleanup integration test**

Add a `spawn_child_hang` fixture scenario that starts `sleep 3600`, writes its PID to
`CHILD_PID_FILE`, writes the handler PID to `HANDLER_PID_FILE`, and then blocks. Add a STDIO test
that invokes the watchdog with a short timeout, waits for the callback, and asserts both PIDs no
longer exist with `kill -0`.

**Step 2: Run the focused integration test and verify RED**

Run: `cd watchdog && ./test-local.sh --stdio`

Expected: the new test fails because at least the spawned descendant remains alive.

**Step 3: Implement process-group lifecycle**

- Start every `tokio::process::Command` with `process_group(0)`.
- Replace blocking `kill_process` with an async `terminate_process_group(&mut Child)` helper.
- Send SIGTERM to the group, await `child.wait()` for 100 ms, send SIGKILL on expiry, then reap.
- In STDIO, take stdout/stderr, read both concurrently with `AsyncReadExt::read_to_end`, and put
  the timeout around `child.wait()` rather than consuming the child with `wait_with_output()`.
- Preserve the existing JSON and stderr error messages.

**Step 4: Verify GREEN**

Run: `cargo test --manifest-path watchdog/Cargo.toml`

Run: `cd watchdog && ./test-local.sh --stdio`

Expected: unit tests and all STDIO integration tests pass with no leftover fixture processes.

**Step 5: Commit**

```bash
git add watchdog/src/main.rs watchdog/tests/fixtures/stdio_handler.py watchdog/tests/integration/test_stdio_mode.sh
git commit -m "Terminate watchdog process groups on timeout"
```

### Task 3: Use the lifecycle path in FILE and one-shot HTTP modes

**Files:**
- Modify: `watchdog/src/main.rs:325-535`
- Modify: `watchdog/src/main.rs:918-1006`
- Modify: `watchdog/tests/fixtures/file_handler.sh`
- Modify: `watchdog/tests/integration/test_file_mode.sh`
- Modify: `watchdog/tests/integration/test_http_mode.sh`

**Step 1: Extend existing timeout tests**

- Make the FILE timeout fixture write its PID and assert it disappears.
- Change HTTP integration setup so the watchdog launches the fixture itself instead of launching a
  separate server and using `WATCHDOG_CMD="sleep 0"`.
- Assert startup timeout and normal completion leave no runtime PID alive.

**Step 2: Run focused suites and verify RED**

Run: `cd watchdog && ./test-local.sh --file`

Run: `cd watchdog && ./test-local.sh --http`

Expected: new PID-lifecycle assertions fail against the old cleanup paths.

**Step 3: Route both modes through the shared cleanup helper**

- Apply process-group creation to FILE and HTTP children.
- Replace all direct TERM/KILL calls with the async lifecycle helper.
- Set both `PORT` and `SERVER_PORT` to `8081` for warm HTTP and `8080` for one-shot HTTP.
- Keep readiness polling and invoke protocol unchanged.

**Step 4: Verify GREEN**

Run: `cargo test --manifest-path watchdog/Cargo.toml`

Run: `cd watchdog && ./test-local.sh --file`

Run: `cd watchdog && ./test-local.sh --http`

Expected: all focused suites pass and the watchdog owns the runtime fixture it tests.

**Step 5: Commit**

```bash
git add watchdog/src/main.rs watchdog/tests/fixtures/file_handler.sh watchdog/tests/integration/test_file_mode.sh watchdog/tests/integration/test_http_mode.sh
git commit -m "Unify watchdog child cleanup"
```

### Task 4: Fail warm containers when the HTTP runtime dies

**Files:**
- Modify: `watchdog/src/main.rs:591-834`
- Modify: `watchdog/tests/fixtures/http_server.py`
- Modify: `watchdog/tests/integration/test_warm_mode.sh`
- Modify: `watchdog/tests/integration/run_all.sh`

**Step 1: Add failing warm HTTP supervision tests**

Add fixture PID-file support and tests that:

1. start warm HTTP with the watchdog launching `http_server.py` on internal port 8081;
2. successfully invoke through the public watchdog port;
3. kill the internal runtime and assert the watchdog exits non-zero;
4. send SIGTERM to a healthy watchdog and assert the watchdog and child both exit.

**Step 2: Run warm tests and verify RED**

Run: `cd watchdog && ./test-local.sh`

Expected: warm HTTP supervision fails because the current server remains healthy after child exit.

**Step 3: Implement platform-managed restart semantics**

- Add one `shutdown_signal()` future that accepts SIGINT and SIGTERM.
- Monitor the Axum server and persistent HTTP child together with `tokio::select!`.
- Return non-zero when the child exits independently.
- On requested shutdown, stop accepting requests, terminate/reap the child, and return success.
- Keep STDIO/FILE warm invocation serialization unchanged.

Do not add internal restart loops, counters, or backoff.

**Step 4: Verify GREEN**

Run: `cargo test --manifest-path watchdog/Cargo.toml`

Run: `cd watchdog && ./test-local.sh`

Expected: all warm and existing integration tests pass.

**Step 5: Commit**

```bash
git add watchdog/src/main.rs watchdog/tests/fixtures/http_server.py watchdog/tests/integration/test_warm_mode.sh watchdog/tests/integration/run_all.sh
git commit -m "Supervise warm HTTP runtimes"
```

### Task 5: Make callback delivery part of one-shot success

**Files:**
- Modify: `watchdog/src/main.rs:537-589`
- Modify: `watchdog/src/main.rs:836-916`
- Modify: `watchdog/tests/integration/test_callback.sh`

**Step 1: Add a failing callback-exhaustion test**

Use the existing callback fixture with `CALLBACK_SCENARIO=error_503`, capture the watchdog exit
status, and assert three callback attempts followed by a non-zero exit.

**Step 2: Run callback tests and verify RED**

Run: `cd watchdog && ./test-local.sh --callback`

Expected: the new test fails because the watchdog currently exits zero.

**Step 3: Return failure after exhausted delivery**

Return `ExitCode::from(1)` from one-shot `main` when `send_callback` returns an error. Keep the
three existing attempts and backoff values. Avoid a fourth retry layer.

**Step 4: Verify GREEN**

Run: `cargo test --manifest-path watchdog/Cargo.toml`

Run: `cd watchdog && ./test-local.sh --callback`

Expected: success, transient retry, timeout-result callback, and permanent failure tests pass.

**Step 5: Commit**

```bash
git add watchdog/src/main.rs watchdog/tests/integration/test_callback.sh
git commit -m "Fail undelivered watchdog callbacks"
```

### Task 6: Repair the combined image and document the contract

**Files:**
- Modify: `watchdog/Dockerfile.combined`
- Modify: `watchdog/src/main.rs`
- Modify: `watchdog/README.md`
- Modify: `docs/function-pod-architecture.md`
- Modify: `docs/testing.md`

**Step 1: Add a structural regression check**

Add a Rust `include_str!("../Dockerfile.combined")` unit test verifying that the combined image
sets `WARM=true`, selects HTTP mode, and points `RUNTIME_URL` to
`127.0.0.1:8081/invoke`.

**Step 2: Run it and verify RED**

Run: `cargo test --manifest-path watchdog/Cargo.toml`

Expected: the combined-image contract test fails.

**Step 3: Correct image defaults and documentation**

- Set `WARM=true`, `EXECUTION_MODE=HTTP`, and internal `RUNTIME_URL` port 8081 in
  `Dockerfile.combined`.
- Document quoted command behavior, strict configuration errors, process-group timeout cleanup,
  callback exit behavior, SIGTERM, and platform-managed restart.
- Add watchdog unit and local integration commands to `docs/testing.md`.
- Remove unsupported performance numbers from the README unless a reproducible benchmark backs
  them.

**Step 4: Verify the image**

Run: `docker build -f watchdog/Dockerfile.combined -t nanofaas/watchdog-combined:test .`

Run the image, wait for `/health`, POST a sample invocation, then stop it and verify exit without
orphaned Java processes.

Expected: image builds, health succeeds, invocation returns JSON, and shutdown succeeds.

**Step 5: Commit**

```bash
git add watchdog/Dockerfile.combined watchdog/README.md docs/function-pod-architecture.md docs/testing.md watchdog/src/main.rs
git commit -m "Document hardened watchdog lifecycle"
```

### Task 7: Make watchdog verification a release gate

**Files:**
- Modify: `.github/workflows/gitops.yml`

**Step 1: Add a CI job**

Add `test-watchdog` on Ubuntu that checks out the repository, installs the stable Rust toolchain,
installs `jq` and `netcat-openbsd`, runs `cargo test --manifest-path watchdog/Cargo.toml`, and runs
`./watchdog/test-local.sh`. Add `test-watchdog` to the `publish.needs` list.

**Step 2: Validate workflow syntax locally**

Run:

```bash
uv run --project tools/controlplane python -c "from pathlib import Path; import yaml; d=yaml.safe_load(Path('.github/workflows/gitops.yml').read_text()); jobs=d['jobs']; assert 'test-watchdog' in jobs; assert set(jobs['publish']['needs']) == {'test-java', 'test-python', 'test-watchdog'}"
```

Expected: `test-java`, `test-python`, and `test-watchdog` are all required by `publish`.

**Step 3: Run the same commands as CI**

Run: `cargo test --manifest-path watchdog/Cargo.toml`

Run: `cd watchdog && ./test-local.sh`

Expected: all unit and integration tests pass.

**Step 4: Commit**

```bash
git add .github/workflows/gitops.yml
git commit -m "Test watchdog in CI"
```

### Task 8: Full platform regression and scope audit

**Files:**
- No production changes expected.

**Step 1: Run format and watchdog suites**

Run: `cargo fmt --manifest-path watchdog/Cargo.toml -- --check`

Run: `cargo test --manifest-path watchdog/Cargo.toml`

Run: `cd watchdog && ./test-local.sh`

Expected: all pass with no leftover fixture processes.

**Step 2: Run provider regressions**

Run: `./gradlew :control-plane-modules:k8s-deployment-provider:test :control-plane-modules:container-deployment-provider:test`

Expected: both provider suites pass, proving the existing `WATCHDOG_CMD` contract is unchanged.

**Step 3: Run control-plane scenario regressions**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests -q`

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests -q`

Expected: all workflow and control-plane tool tests pass.

**Step 4: Run image-level smoke tests**

Run: `docker build -f watchdog/Dockerfile -t nanofaas/watchdog:test watchdog`

Run: `docker build -f functions/bash/word-stats/Dockerfile -t nanofaas/bash-word-stats:test .`

Run:

```bash
docker run --rm -d --name nanofaas-watchdog-smoke -p 18082:8080 nanofaas/bash-word-stats:test
curl -fsS -X POST http://127.0.0.1:18082/invoke \
  -H 'Content-Type: application/json' \
  -H 'X-Execution-Id: watchdog-smoke' \
  -d '{"input":{"text":"hello hello watchdog","topN":2}}'
docker stop nanofaas-watchdog-smoke
```

Expected: both images build; the response contains `"wordCount":3`; the container stops cleanly.

**Step 5: Audit the change graph and worktree**

Run GitNexus change detection for the full branch, review every affected execution flow, run
`git diff --check`, and confirm `git status --short` is clean.

**Step 6: Final checkpoint commit if verification required small fixes**

```bash
git add <only-the-verified-fix-files>
git commit -m "Complete watchdog hardening verification"
```
