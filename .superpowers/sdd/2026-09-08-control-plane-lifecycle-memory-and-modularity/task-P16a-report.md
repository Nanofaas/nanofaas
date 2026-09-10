# Task P16a report — common SDK memory inventory and saturation wire contract

## Scope and revisions

- Base: `9ad0f87e2db260193ce09e72399c7b3086ee4cd7`.
- External MIT commit `779e1480` remains unchanged in history.
- Scope: inventory, common policy and executable shared corpus for `sdks/java`,
  `sdks/java-lite`, `sdks/python`, `sdks/go` and `sdks/javascript`.
- P16a does not implement or claim all runtime count/byte limits; that remains P16b.
- Protected dirty overload experiment files, untracked GitNexus skill directories, the
  untracked replica-status configuration test and ignored progress ledger were not modified
  or staged.

## Inventory and decisions

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

## Shared policy and executable corpus

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

## P01 compatibility

No unresolved choice remains. The corpus keeps P01's clocks separate:

- per-waiter timeout remains `408` and never changes shared execution state;
- SDK handler timeout is an attempt-level `504 HANDLER_TIMEOUT` and may emit the attempt's
  error callback before the control plane applies configured retry;
- admission rejection starts no handler and fabricates no terminal callback;
- retry preserves the supplied execution ID and dispatch attempt, and the control plane
  remains the retry/idempotency owner.

Current runtime wire/body and ownership differences are explicit nonconformance routed to
P16b/P17/P18, not silently accepted as alternate contracts.

## TDD evidence

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

## Verification results

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

## GitNexus gates

GitNexus 1.6.11 was refreshed against the exact base checkout: 19,590 nodes, 56,459 edges,
870 clusters and 767 flows. A graph-first query for SDK callback saturation, timeout, stop,
ownership and payload retention identified the Java callback submit/serialization flow and
the Python and JavaScript runtime owners used in the inventory.

No existing code symbol was edited. Every adapter and corpus file is new; the campaign
record is append-only. Consequently no existing-symbol impact gate, UNKNOWN resolution, or
HIGH/CRITICAL pre-edit warning was applicable. Both final change-detection runs were complete
and contained no `partial` or `truncated` marker. The `all` scope reported 11 files, three
documentation symbols, zero affected processes, and LOW risk; it also included the two
preserved dirty overload-experiment files. The `staged` scope reported exactly the nine P16a
files, two documentation symbols, zero affected processes, and LOW risk. New adapter and
corpus files have no symbols in the base index, so their absence from the symbol count was not
treated as evidence about callers.

## Changed files

- `docs/experiments/lifecycle-memory-2026-09/STATO.md`
- `sdks/runtime-contract/README.md`
- `sdks/runtime-contract/saturation-wire-corpus.json`
- `sdks/java/src/test/java/it/unimib/datai/nanofaas/sdk/runtime/SharedSaturationWireCorpusTest.java`
- `sdks/java-lite/src/test/java/it/unimib/datai/nanofaas/sdk/lite/SharedSaturationWireCorpusTest.java`
- `sdks/python/tests/test_saturation_wire_corpus.py`
- `sdks/go/nanofaas/saturation_wire_corpus_test.go`
- `sdks/javascript/test/saturation-wire-corpus.test.ts`
- `.superpowers/sdd/2026-09-08-control-plane-lifecycle-memory-and-modularity/task-P16a-report.md`

The report is force-added because `.superpowers/` is ignored.

## Remaining concerns and next step

- Policy-corpus adapters are not runtime quota conformance tests. P16b must execute the same
  cases through every real runtime, including direct invocation, and prove count/bytes drain
  to zero.
- P17 must make Python accounting follow physical sync-thread/async-task completion and own
  finite executor shutdown truth.
- P18 must retain and close Java-lite server/callback/HTTP resources and verify the analogous
  Spring Java ownership pattern.
- The unrelated Go vet warning remains visible for its owning task.

Next: P17/P18 ownership primitives, then P16b full runtime conformance.
