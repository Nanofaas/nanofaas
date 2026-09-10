# Task P16a report — common SDK memory inventory and saturation wire contract

## Fix round 5 — causal program and ordered retry

This section supersedes fix-round-4 program-connectivity claims. Starting revision: `bb081205`.

The next fresh review reduced the remaining gap to harness causality. Five RED tests reproduced
accepted contradictions: success followed by a failed redispatch, redispatch replaced by a second
send, stop without `begin-stop`, callback saturation without capacity fill, and swapped
`success-drain`/`restart` kinds. These changes preserved local references and canonical outcome
chains but invalidated the scenario program.

GREEN adds exact per-kind action, actor and ordered outcome sequences to the JSON policy. Python
adds one generic `mapped-sequence-equals` operator; repeated sequence elements are valid data and
no action, actor, outcome or kind value is copied into the validator. The full validator suite is
17/17 GREEN and all embedded mutations remain GREEN. The GitNexus index was refreshed before the
edit; exact upstream impacts for `validate_rule` and `apply_rules` were LOW and limited to the
Runtime-contract validator flow. All five focused adapters passed again (Java/Java-lite 16/16
Gradle actions, Python, Go and JavaScript). Complete non-partial/non-truncated detection reported
eight worktree files/16 symbols including the protected overload files, and six staged P16a
files/15 symbols; both scopes had zero affected processes and LOW risk.

## Fix round 4 — anchor semantics to stimuli and scenario kind

This section supersedes broader fix-round-3 connectivity claims. Starting revision:
`088c3497`.

The next independent review found that internally canonical lifecycle/outcome/envelope chains
could still be disconnected from the facts that cause them. Five deterministic RED tests proved
that the validator accepted below-limit output rewritten as output rejection, above-limit output
rewritten as success, a complete `success-drain` rewrite into handler error, `probe-health`
targeting an invoke request, and `send-request` targeting a health request.

GREEN remains data-driven. Corpus rules now anchor scenario kind to allowed wire outcomes, input
and output size relations to allowed handler lifecycles, and request-bearing actions to allowed
request roles. The Python change only adds the generic `request-action` scope and exposes scenario
and referenced-request context to the existing generic mapping operator; no scenario kind,
outcome, lifecycle, role or action policy value is duplicated in Python. The full validator suite
is now 12/12 GREEN and all embedded mutations remain GREEN. All five focused adapters passed
again: Java/Java-lite 16/16 Gradle actions, Python and Go focused tests, and JavaScript test
compilation plus its Node test. Complete, non-partial/non-truncated GitNexus detection reported
eight worktree files/10 symbols including the two protected overload files and six staged P16a
files/nine symbols; both scopes had zero affected processes and LOW risk.

## Fix round 3 — coordinated semantic consistency

This section supersedes the fix-round-2 connectivity claims where they conflict. Starting
revision: `5e6a0eeb372b308f4111a9c466c7d1df14c6cfe5`.

The round-two validator rejected all seven individual review mutations but still accepted
coordinated changes whose individual references remained canonical. Four focused RED tests
reproduced the gap: a failed handler paired with a success response/callback, an
output-too-large handler paired with the canonical handler-error callback, a `send-request`
action without a request ID, and three callback delivery attempts with an empty
`dispatchAttempts` sequence. All four failed because `ContractError` was not raised.

GREEN keeps policy values in `saturation-wire-corpus.json` and adds only generic operators to
the Python validator. Declarative rules now connect handler lifecycle to allowed wire outcomes,
wire outcomes to allowed callback envelopes, request-bearing action names to request-ID
presence, and callback attempt counts to dispatch-attempt sequence length. Existing
per-element dispatch identity remains independently enforced. The four coordinated tests,
the prior seven review probes, all 23 embedded mutations and the non-finite JSON probe pass.

GitNexus upstream impact before the GREEN edit was exact and LOW for `validate_rule`,
`validate_expected` and `apply_rules`; only the validator's `main` flow and Runtime-contract
module are affected. Python's seven validator tests and all embedded mutations passed. All five
focused adapters passed: Java/Java-lite completed 16/16 Gradle actions, Python and Go each passed
their focused test, and JavaScript compiled its test tree and passed the Node test. The first
Python command was blocked only by sandbox access to the existing uv cache; the approved rerun
passed. Complete GitNexus detection was non-partial/non-truncated: all-worktree scope reported
eight files/17 symbols including the two protected overload files, zero affected processes and
LOW risk; staged scope reported exactly the six P16a files/16 symbols, zero affected processes
and LOW risk.

## Fix round 2 — single policy authority and connected semantics

This section supersedes the fix-round-1 validator and corpus claims where they conflict.
Starting revision: `68e347bb17af9cf61a2c45e52b718eb8abc6ea41`.

The corpus is now `nanofaas.runtime-saturation/v3`. Its referenced `contractDefinitions`
set is the sole policy authority for vocabularies, actor/action compatibility, size
relations, backend behavior-to-lifecycle compatibility, exact wire outcomes, callback
transport/envelopes, identity/cross-field rules, observation sets and final counter state.
The Python validator contains generic schema, reference, projection and relation operators;
it contains no scenario IDs, behavior values, status codes, content types, error codes or
messages. Materialized scenario response/callback projections are executable fixtures and
must equal their canonical references.

Callback expectations now specify the exact `POST` URL, content type, trace and dispatch
attempt headers, and success/error payload. One canonical transport template is combined
with referenced payload envelopes. A scenario stores one exact request projection and its
per-delivery dispatch-attempt sequence, avoiding repeated whole envelopes for retries.
The corpus was restored to compact scenario formatting (660 lines); its remaining size is
the eleven exact structured scenario programs and canonical definitions, not repeated
adapter policy.

Honest RED preceded the semantic fix. A focused test applied all seven accepted review
contradictions independently; all seven failed because no `ContractError` was raised, and
the embedded runner separately reported `handler-failure-with-success-outcome` as accepted.
GREEN rejects all seven: handler failure with success, callback success with exhaustion,
handler actor request send, callback requirement without URL, arbitrary success body,
arbitrary error message and noncanonical content type. Six additional mutations cover
missing wire/lifecycle/envelope references, callback payload/projection drift and identity
projection drift. Together with prior probes there are 23 embedded mutations plus the raw
non-finite JSON-number test.

All five adapters remain source-driven and have a finite 10-second validator deadline.
Java/Java-lite deserialize the complete model, Go disallows unknown fields and preserves
boolean presence, and Python/JavaScript consume required definition and scenario
projections. This proves shared semantic validation and native parsability only. Runtime
queue admission, real byte transfer, live callback delivery, observed deadlines and live
counters remain conformance work assigned row-by-row in the README to P16b/P17/P18. No
runtime production file changed.

Round-two verification: focused adapters passed in Java, Java-lite, Python, Go and
JavaScript. Complete Java/Java-lite builds passed 18/18 actions; Python passed 61 tests with
35 existing warnings and built wheel/sdist; Go tests passed and vet still reports only the
existing `cold_start.go:27` atomic no-copy issue; JavaScript passed 39/39 and its package
build. The first full repository run reached 239/240 actions but one existing tight-timeout
container-provider test observed an HTTP EOF. Its exact test and complete 92-test module
both passed on immediate isolated rerun; the complete repository rerun then passed 240/240
actions in 4 min 17 s. No workaround or out-of-scope edit was made.

GitNexus 1.6.11 was refreshed at the round-two base. Exact pre-edit impacts covered every
existing validator and adapter symbol: the shared `fail` funnel was HIGH because it reaches
all validator branches and the `main` flow; that exception contract was preserved. Other
resolved impacts were MEDIUM/LOW, while UNKNOWN test-entry/type results were text-resolved
to these adapter files and framework invocation. Complete, non-partial/non-truncated change
detection used `--limit 10000`: all-worktree scope reported 13 files/210 symbols (including
the two protected overload files), one flow and medium risk; staged-only scope reported
exactly 11 P16a files/209 symbols, the same validator flow and medium risk.

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
