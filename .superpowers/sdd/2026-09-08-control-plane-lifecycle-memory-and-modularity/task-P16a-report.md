# Task P16a report — common SDK memory inventory and saturation wire contract

## Fix round 1 — review findings

This section supersedes the initial corpus/adapter claims below where they conflict. Starting
revision: `1cd7a295caca889e00960d7813974267d281ecc5`.

The free-form five-case corpus is replaced by version
`nanofaas.runtime-saturation/v2` with eleven structured scenarios. Each contains a finite
runtime configuration reference, typed request metadata and byte relation, deterministic
handler/callback behavior, ordered action/barrier program, complete initial/final retained
counters, exact response body/required headers, per-request handler and callback lifecycle,
identity evidence, observations and a finite deadline. Ingress and output oversize are
separate: ingress is pre-handler `413 RUNTIME_INPUT_TOO_LARGE`; output is post-handler
`500 RUNTIME_OUTPUT_TOO_LARGE` with a bounded error callback.

`validate_saturation_wire_corpus.py` is the authoritative structural and semantic
validator. It rejects absent fields, missing booleans/headers/observations, non-finite or
non-positive bounds, unknown vocabulary, contradictory lifecycle, impossible callback
outcomes, invalid size relations and non-P01 identity. Ten embedded mutations plus a raw
`Infinity` JSON mutation exercise these failures. Every SDK adapter runs that one validator
under a finite 10 s process deadline and parses the same JSON into a native typed projection;
none copies scenario outcome literals.

What P16a executes is schema/semantic validation only. It does not start runtimes, wait on the
scenario barriers, fill real queues, enforce bytes, inspect live counters or prove runtime
deadline behavior. Timed direct-runtime conformance belongs to P16b after P17/P18 provide
their physical ownership primitives.

P01 identity is corrected: execution ID stays stable across dispatch retries, dispatch
attempt increments for each new invocation attempt, callback delivery retries echo the
current invocation attempt, and the runtime redispatch count is always zero.

Inventory additions: Go starts dispatcher workers before bind and does not shut them down
when `ListenAndServe` fails; JavaScript accepts zero, negative, `NaN` and `Infinity`
callback queue sizes; Go and JavaScript have no finite body-read deadline. Java, Java-lite
and Python likewise have no explicit SDK-owned finite ingress body-read deadline. Go and
JavaScript corrections route to P16b; Java-lite lifecycle ownership remains P18, Python
physical work remains P17, and common ingress deadline/conformance remains P16b.

Fix-round RED/GREEN and verification:

- RED: the mutation test failed with `authoritative saturation-wire validator is required`.
  The first implementation then exposed a validator syntax defect; passing harness actions
  through the semantic boundary removed that reproduced failure.
- GREEN: v2 validates, all ten embedded mutations are rejected, and a separate raw
  `Infinity` JSON mutation is rejected.
- Java and Java-lite complete SDK builds passed with 18/18 actionable tasks.
- Python passed 61 tests with 35 pre-existing warnings; `uv build` produced wheel and sdist.
- Go's complete suite passed on Go 1.24.0. `go vet ./...` still exits 1 only for the
  pre-existing `cold_start.go:27` atomic no-copy warning.
- JavaScript passed 39/39 tests and `npm run build`.
- The full repository build passed in 4 min 9 s with 240/240 actions.

## Initial implementation record (superseded where noted above)

### Scope and revisions

- Base: `9ad0f87e2db260193ce09e72399c7b3086ee4cd7`.
- External MIT commit `779e1480` remains unchanged in history.
- Scope: inventory, common policy and executable shared corpus for `sdks/java`,
  `sdks/java-lite`, `sdks/python`, `sdks/go` and `sdks/javascript`.
- P16a does not implement or claim all runtime count/byte limits; that remains P16b.
- Protected dirty overload experiment files, untracked GitNexus skill directories, the
  untracked replica-status configuration test and ignored progress ledger were not modified
  or staged.

### Inventory and decisions

The complete per-runtime table is in `sdks/runtime-contract/README.md`. It records every
retained request/handler task, callback task/queue, input, output and serialized callback
body found in the five runtimes, together with actual bounds, owner, release and stop path.
Worker counts are never treated as queue bounds.

Key findings:

- Java: callback retention defaults to two active workers plus a fixed 128 queue;
  virtual-thread handler submission and all payload bytes are unbounded.
- Java-lite: callback retention defaults to two active plus 128 queued, but the server
  virtual-thread executor, per-invoke virtual thread and callback HTTP client have incomplete
  ownership/timeout shutdown. P18 owns this correction.
- Python: a real 128 callback semaphore exists, but sync handlers and callback HTTP calls use
  default `to_thread` executors whose worker counts do not bound queued work. Timed-out sync
  handlers can retain payloads after request accounting ends. P17 owns this correction.
- Go: callbacks default to 128 buffered plus two active jobs; request/handler goroutines and
  payload bytes are unbounded, and timeout does not stop a non-cooperative handler.
- JavaScript: the default 128 callback Promise set is a real aggregate in-flight cap, not a
  worker queue; request/handler Promises, body chunks and callback bytes remain unbounded.

No runtime currently implements both single-payload and pending-callback-byte limits.
Existing callback count bounds are retained as implementation assets for P16b rather than
rewritten merely to force identical internal APIs.

### Shared policy and executable corpus

`sdks/runtime-contract/saturation-wire-corpus.json` is the only source of expected outcomes.
It contains version/scope, admission point, retry identity, mandatory release events, an
embedded runner contract and five cases:

1. callback saturation;
2. single payload too large;
3. handler attempt timeout;
4. callback delivery exhausted after the invoke response;
5. runtime stopping after listener acceptance.

Each case carries its implementation owner, deterministic finite deadline, stimulus,
HTTP/no-second-response status, stable error code/message/headers, retryability, handler and
callback expectations, and resources that must release. The runner contract imposes a
finite 1,000 ms maximum on every positive case deadline.

One new adapter per runtime parses this exact JSON file. Adapters validate metadata,
required/actual case equality, unique IDs, finite bounded deadlines, status range, stable
error-code shape, booleans and non-empty release sets. They do not copy expected case
IDs/statuses/codes/messages, so policy values cannot drift independently by language.

The README explicitly distinguishes these P16a policy-corpus assertions from runtime
conformance. It assigns callback saturation and payload limits to P16b; Python physical
work/drain to P17; Java-lite client/executor/start-stop ownership to P18; and common wire,
direct-invocation, observation and remaining runtime conformance to P16b.

### P01 compatibility

No unresolved choice remains. The corpus keeps P01's clocks separate:

- per-waiter timeout remains `408` and never changes shared execution state;
- SDK handler timeout is an attempt-level `504 HANDLER_TIMEOUT` and may emit the attempt's
  error callback before the control plane applies configured retry;
- admission rejection starts no handler and fabricates no terminal callback;
- retry preserves the execution ID, increments dispatch attempt for each new invocation,
  and callback delivery retries echo that current attempt; the control plane remains the
  retry/idempotency and redispatch owner.

Current runtime wire/body and ownership differences are explicit nonconformance routed to
P16b/P17/P18, not silently accepted as alternate contracts.

### TDD evidence

RED was observed before the shared corpus existed:

- Java: `SharedSaturationWireCorpusTest` failed with corpus-not-found
  `IllegalStateException` (92 tests reached, one expected failure).
- Java-lite: the same adapter failed with corpus-not-found `IllegalStateException`.
- Python: focused test failed with `FileNotFoundError` for the exact shared path.
- JavaScript: compiled adapter failed because the shared path did not exist.
- Go: after acquiring exact Go 1.24.0, the adapter was run with an explicit absent corpus
  path and failed on that missing file.

After adding the single JSON source, all five focused adapters passed. The tests use file
completion and finite corpus deadlines; no sleep is used to prove ordering.

### Verification results

- Focused Java + Java-lite adapters:
  `./gradlew :sdks:java:test :sdks:java-lite:test --tests
  '*SharedSaturationWireCorpusTest' --rerun-tasks --no-parallel --console=plain` — passed,
  16/16 actionable tasks.
- Complete Java + Java-lite SDKs:
  `./gradlew :sdks:java:build :sdks:java-lite:build --rerun-tasks --no-parallel
  --console=plain` — passed, 18/18 actionable tasks.
- Python adapter: `uv run pytest tests/test_saturation_wire_corpus.py -q` — 1 passed.
- Python suite: `uv run pytest -q` — 61 passed, 35 pre-existing deprecation warnings.
- Python package: `uv build` — wheel and source distribution built.
- Go adapter: `GOTOOLCHAIN=go1.24.0 go test ./nanofaas -run
  TestConsumesSharedRuntimeSaturationWireContract -count=1` — passed.
- Go suite: `GOTOOLCHAIN=go1.24.0 go test ./... -count=1` — passed.
- Go tooling: `GOTOOLCHAIN=go1.24.0 go vet ./...` — exits 1 on the pre-existing
  `nanofaas/cold_start.go:27:24` copy of `sync/atomic.Int64` containing `noCopy`. P16a does
  not edit that unrelated existing symbol.
- JavaScript adapter: `node --test --test-reporter=spec
  build-test/test/saturation-wire-corpus.test.js` — 1 passed after `npm run build:test`.
- JavaScript suite: `npm test` — 39/39 passed with localhost socket permission. The initial
  sandboxed run failed with `listen EPERM`, not an assertion; the escalated rerun passed.
- JavaScript package: `npm run build` — passed.
- Full repository: `./gradlew build --rerun-tasks --no-parallel --continue
  --console=plain --offline` — `BUILD SUCCESSFUL` in 4 min 14 s, 240/240 actionable tasks.

### GitNexus gates

GitNexus 1.6.11 was refreshed against the exact base checkout: 19,590 nodes, 56,459 edges,
870 clusters and 767 flows. A graph-first query for SDK callback saturation, timeout, stop,
ownership and payload retention identified the Java callback submit/serialization flow and
the Python and JavaScript runtime owners used in the inventory.

The initial implementation edited no existing code symbol. Fix round 1 edits all five
adapter tests and the corpus. Exact UID-based upstream impact found LOW risk for private
helpers and UNKNOWN for framework-discovered tests/files. Exact text search resolved every
UNKNOWN to its defining adapter and local helper/test-runner use; there are no production
callers and no HIGH/CRITICAL impact. The index was refreshed at
`1cd7a295caca889e00960d7813974267d281ecc5` to 19,651 nodes, 56,554 edges and
871 clusters. Final fix-round all/staged results are appended after exact staging.

Both fix-round gates were rerun with a 200-symbol limit and returned without a
`partial` or `truncated` marker. `all` reported 13 files/49 symbols, zero affected
processes and LOW risk, including the two protected dirty overload-experiment files.
`staged` reported exactly 11 P16a files/48 symbols, zero affected processes and LOW
risk. The CLI abbreviates its human-readable symbol list after 15 entries even with
the higher limit, but its result reports the full symbol count and no truncation flag.

### Changed files

- `docs/experiments/lifecycle-memory-2026-09/STATO.md`
- `sdks/runtime-contract/README.md`
- `sdks/runtime-contract/saturation-wire-corpus.json`
- `sdks/runtime-contract/validate_saturation_wire_corpus.py`
- `sdks/runtime-contract/test_validate_saturation_wire_corpus.py`
- `sdks/java/src/test/java/it/unimib/datai/nanofaas/sdk/runtime/SharedSaturationWireCorpusTest.java`
- `sdks/java-lite/src/test/java/it/unimib/datai/nanofaas/sdk/lite/SharedSaturationWireCorpusTest.java`
- `sdks/python/tests/test_saturation_wire_corpus.py`
- `sdks/go/nanofaas/saturation_wire_corpus_test.go`
- `sdks/javascript/test/saturation-wire-corpus.test.ts`
- `.superpowers/sdd/2026-09-08-control-plane-lifecycle-memory-and-modularity/task-P16a-report.md`

The report is force-added because `.superpowers/` is ignored.

### Remaining concerns and next step

- Policy-corpus adapters are not runtime quota conformance tests. P16b must execute the same
  cases through every real runtime, including direct invocation, and prove count/bytes drain
  to zero.
- P17 must make Python accounting follow physical sync-thread/async-task completion and own
  finite executor shutdown truth.
- P18 must retain and close Java-lite server/callback/HTTP resources and verify the analogous
  Spring Java ownership pattern.
- The unrelated Go vet warning remains visible for its owning task.

Next: P17/P18 ownership primitives, then P16b full runtime conformance.
