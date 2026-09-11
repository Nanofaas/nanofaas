# Task P16b report — bounded SDK runtimes and cross-language conformance

## Status and revision

- Status: complete; final independent re-reviews CLEAN in every runtime slice.
- Base revision: `7f8f4c2eff03dc317fc0459f371c514631fd93c9`.
- Implementation revision: `1432cc5d6d9e7a5a83b7e3abb1388f82f3509545`.
- Branch: `control-plane-lifecycle-memory`.
- Scope: Java, Java-lite, Python, Go and JavaScript runtimes; shared saturation corpus;
  runtime-backed adapters; warm-echo callback integration test; this report and campaign ledger.
- Preserved: both dirty overload-path files, all untracked `.claude/skills/gitnexus-*`
  directories, and the untracked control-plane replica-status test.

## Selected common policy

| Resource | Default bound |
|---|---:|
| Physically active handlers | 32 |
| Input payload | 1 MiB per invocation |
| Output payload | 1 MiB per invocation |
| One serialized callback | 2 MiB |
| Pending callbacks | 128 |
| Pending callback bytes | 16 MiB |
| Handler timeout | 30 s |
| Body read / callback attempt / shutdown | 5 s each |
| Callback attempts | 3 |

Callback count and worst-case bytes are reserved before handler execution. Handler saturation has
precedence when handler capacity is already exhausted, without weakening the earlier callback
reservation. All limits are finite and validated; JavaScript additionally rejects configured
output/callback limits below the largest canonical wire envelopes (102/132 bytes).

Canonical outcomes are `413 RUNTIME_INPUT_TOO_LARGE`, `429 RUNTIME_CALLBACK_SATURATED`,
`429 RUNTIME_HANDLER_SATURATED`, `500 RUNTIME_OUTPUT_TOO_LARGE`, `503 RUNTIME_STOPPING`, and
`504 HANDLER_TIMEOUT`. Retryable 429/503 outcomes include `Retry-After: 1`.

## Implementation and ownership

- Java and Java-lite perform hard-capped streaming Jackson serialization before admission or
  handoff, including custom serializers. Body reads and callback attempts have deadlines;
  callback handoff is explicit and cannot return a false HTTP 200. Cancellation retains physical
  handler ownership and emits the canonical terminal callback. Java-lite preserves interruption,
  stops callback retries, drains reservations during bounded stop, and exposes exhaustion metrics.
- Python keeps handler and callback executors separate and bounded. Reservations survive request
  timeout/cancellation until the physical worker finishes. Terminal callback task registration is
  atomic with runtime ownership. Raw bytes and JSON callback payloads are bounded and accounted by
  serialized size. The Python 3.14 future bridge passed 10,000 completion interleavings.
- Go uses bounded JSON preflight, an explicit safe standard-type allowlist and finite configuration
  maxima. Callback submit/shutdown serialize channel send/close, eliminating the reproduced race;
  10,000 gated shutdown interleavings and the race detector are green.
- JavaScript owns streaming uploads, handlers, response flushes, callback response bodies, sockets
  and restart state through physical completion. Stop uses one deadline and closes old sockets
  before restart. Canonical fallbacks are bounded, and admitted cancellation is counted once.
- All runtimes expose finite count/byte state and return it to zero after drain/stop. No runtime
  silently accepts a callback that it cannot own or deliver/retry observably.

## Runtime-backed common corpus

Each SDK executes all 12 shared scenarios directly against the runtime, bypassing the control
plane: success/drain, input/output limit, callback and handler saturation, timeout, cancellation,
health under saturation, stop with full callback capacity, restart, delivery exhaustion and
dispatch-retry identity. Adapters consume harness actions/barriers and compare actual HTTP
responses, callback projections/metadata, lifecycle events, identities, observations and initial/
final counters with the corpus.

Reviews rejected earlier structural or circular adapters. Final mutation suites prove that
self-consistent changes to statuses, outcomes, callback terminal state/attempts, observations and
initial/final byte counters fail against runtime evidence. Java and Java-lite each pass six
adversarial mutations; Python also rejects saturated input/serialized/output byte mutations; the
shared validator accepts the canonical document and rejects all 23 embedded mutations.

## TDD RED to GREEN highlights

- Reproduced unbounded post-materialization JSON, callback double release/loss, missing deadlines,
  cancellation ownership and Java-lite interruption/retry defects; bounded serializers and
  explicit lifecycle owners made these green.
- Reproduced Python pre-start cancellation leaks, raw-byte bypasses and circular adapter counters;
  exact-once reservations and runtime-derived ASGI/callback evidence made these green. A reported
  future stall was isolated to sandbox `EPERM` on asyncio's wakeup socket; outside the sandbox the
  example and 10,000-completion stress are green, so production bridge code was not changed for an
  environmental artifact.
- Reproduced Go callback submit/shutdown send-close races and incomplete initial state;
  synchronized channel ownership and actual counter verification made race/corpus runs green.
- Reproduced JavaScript old-socket restart races, shutdown/body admission races, dropped callbacks,
  unbounded direct errors and incomplete metrics/adapters; physical ownership, bounded fallback and
  action-driven assertions made them green.
- Runtime-backed Java-lite evidence exposed a noncanonical handler-error envelope and optional null
  callback fields; minimal wire fixes aligned both JVM runtimes. Aggregate review then exposed stale
  warm-echo mocks and uncaught callback-thread NPEs hidden by green JUnit. A real loopback callback
  server now proves completed delivery and exact path/body/headers.

## Coordinated verification

- `./gradlew build --no-parallel --console=plain --offline` — `BUILD SUCCESSFUL`, 240 tasks.
- Fresh Java/Java-lite/warm-echo rerun — `BUILD SUCCESSFUL`, 21/21 tasks executed. Final counts:
  Java 143/143, Java-lite 81/81, warm-echo 6/6; JVM adapters 12/12 and mutation suites 6/6 per SDK.
- Python 3.14: `.venv/bin/python -m pytest -q` — 139 passed in 3.22 s, one pre-existing test-only
  Python 3.16 deprecation warning. Adapter/stress selection: 24/24.
- JavaScript: `npm test` — 80/80; build and standalone 12/12 adapter are green.
- Go 1.24 explicit toolchain: `go test ./...`, `go test -race ./...`, and `go vet ./...` — green.
- Shared contract: 26/26 unittests and embedded mutation validation — green.
- `git diff --check` — clean before report generation.

## Function-process memory evidence

These are maximum RSS observations of runtime-backed adapter commands on this host, not production
capacity benchmarks. No control-plane process ran in any measurement; control-plane memory remains
separate and is measured by P19.

| Runtime | Command boundary | Max RSS |
|---|---|---:|
| Go | precompiled Go test binary, 12 runtime scenarios | 16,304 KiB |
| Python | Python/pytest adapter plus P16b stress selection, 24 tests | 76,332 KiB |
| JavaScript | Node test runner, 12 runtime scenarios | 113,328 KiB |
| Java | Gradle single-use daemon/test-worker command, 12 runtime scenarios | 121,468 KiB |
| Java-lite | Gradle single-use daemon/test-worker command, 12 runtime scenarios | 1,078,792 KiB |

The JVM figures are conservative command-tree envelopes and include Gradle/test infrastructure;
they are not comparable to precompiled Go or direct Python/Node. Java-lite's value therefore does
not establish a runtime heap requirement. P19 owns repeatable production baselines.

## GitNexus and review

Exact upstream impacts ran before production-symbol edits. HIGH/CRITICAL paths were audited for
central serializers, dispatchers, handlers, callback clients and lifecycle owners. UNKNOWN results
were limited mainly to dynamic/framework or reflective test boundaries and were resolved through
exact text search plus executable HTTP/ASGI/JUnit tests. Whole-worktree graph analysis completed
with 49 files, 673 symbols, 67 affected flows and CRITICAL aggregate risk. The selectively staged
gate completed with 116 files, 1,771 symbols, the same 67 affected flows and CRITICAL risk. Neither
analysis reported `partial` or `truncated`; the CLI capped only the printed symbol listing while
explicitly retaining complete counts and risk. The risk was never treated as a waiver: all central
paths are covered by runtime-backed corpus, ownership, saturation, cancellation, stop/restart,
race and full-build verification.

Independent reviews repeatedly found and closed real defects in every runtime: unbounded
materialization, physical ownership races, send/close races, incomplete adapters, minimum canonical
envelope sizes, Java-lite wire mismatches and masked warm-echo callback failures. Go, Python and
JavaScript have final CLEAN reviews. JVM runtime/corpus behavior is independently clean. The final
narrow warm-echo re-review is also CLEAN after 20 forced repetitions of its six tests, with real
loopback delivery and no uncaught callback-thread exception.

## Changed files and residual concerns

Implementation changes are confined to `sdks/java/**`, `sdks/java-lite/**`, `sdks/python/**`,
`sdks/go/**`, `sdks/javascript/**`, `sdks/runtime-contract/**`, warm-echo
`InvokeControllerTest`, this report and the lifecycle ledger. The staged manifest is the
authoritative exhaustive list and excludes protected user dirt.

- Non-cooperative user work cannot be killed safely in-process; permits/bytes remain owned until
  physical exit and bounded stop reports incomplete drain.
- Conservative worst-case callback-byte reservation means 16 MiB can limit admission before the
  128 callback-count cap.
- Go rejects arbitrary custom marshalers outside its documented bounded standard-type allowlist.
- JavaScript canonical errors must remain in `CANONICAL_RUNTIME_ERRORS`, which derives minimums.
- Python retains one unrelated `asyncio.iscoroutinefunction` Python 3.16 deprecation warning.
