# Watchdog Hardening Design

## Context

The Rust watchdog is an active, optional execution adapter for function images. It is used
directly by the Bash examples and can wrap any HTTP, STDIO, or FILE runtime. Native HTTP
runtimes built with a nanoFaaS SDK do not need it in deployment mode.

The current implementation compiles, but `cargo test` runs no tests. The shell integration
suite covers happy paths but misses child-process ownership, warm HTTP supervision, callback
delivery failure, and quoted commands. Several lifecycle bugs follow: STDIO timeouts can leave
children alive, only the direct PID is signalled, warm `/health` remains successful after the
HTTP runtime dies, SIGTERM is not handled explicitly, and a permanently failed callback still
produces a successful process exit. The combined Java image is also inconsistent: it does not
enable warm mode, points the runtime URL at the watchdog port, and Spring Boot ignores the
`PORT` variable currently set by the watchdog.

## Decision

Harden the existing single binary instead of introducing a process-supervisor abstraction or
splitting the three execution modes into separate programs. The watchdog remains deliberately
small: one process owns one function runtime, a warm container accepts one invocation at a time,
and Kubernetes or the container runtime—not the watchdog—restarts a failed warm container.

`WATCHDOG_CMD` remains a string for compatibility with OpenAPI, Java DTOs, providers, YAML, and
existing images. Parse it with the small `shlex` crate and execute the resulting argv directly;
never invoke a shell. Invalid quoting and invalid numeric configuration fail startup explicitly.

## Process lifecycle

Every child starts in its own Unix process group. A single asynchronous cleanup routine sends
SIGTERM to the group, waits for a short fixed grace period, sends SIGKILL if necessary, and then
reaps the direct child. STDIO reads stdout and stderr concurrently while waiting, so a full pipe
cannot deadlock the handler. HTTP, STDIO, FILE, timeout, startup failure, and shutdown paths all
use the same cleanup behavior.

Warm HTTP starts the internal runtime once and waits for readiness before binding the public
watchdog API. The watchdog server and child are then monitored together. If the child exits, the
watchdog stops serving and exits non-zero; the platform restarts the container. Graceful shutdown
handles both SIGINT and SIGTERM. For HTTP children, the watchdog exports both `PORT` and
`SERVER_PORT`, using 8081 in warm mode and 8080 in one-shot mode.

## Callback semantics

One-shot execution is successful only after the completion callback receives a successful HTTP
response. Exhausting the existing three callback attempts returns a non-zero watchdog exit code.
Retries are safe because the control-plane completion endpoint returns 204 and ignores missing or
already-terminal completions.

## Verification

Rust unit tests cover command parsing, invalid configuration, and small pure helpers. Existing
shell integration tests remain the end-to-end contract and gain cases for process-group cleanup,
warm HTTP child death, SIGTERM, callback exhaustion, and a real child launched by the watchdog.
The GitHub workflow runs both `cargo test` and the local integration suite. Provider tests and a
container build remain final regression gates because the environment-variable contract is shared
by Kubernetes and the local container provider.

## Out of scope

- Internal child restart or restart backoff.
- Concurrent invocations inside one warm container.
- A new command-array field in OpenAPI.
- Authentication, sandboxing, or privilege dropping inside function images.
- Refactoring `main.rs` into modules before its behavior is covered by tests.
