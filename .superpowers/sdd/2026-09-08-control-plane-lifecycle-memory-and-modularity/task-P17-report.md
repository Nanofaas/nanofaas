# Task P17 report — Python physical timeout and executor ownership

## Scope and revision

- Base: `b3cc08adfb9f629eb94431526cb18b4e7a87f4a7` (`Revert "Make the license
  identical across the projects: MIT"`), an external commit that remained untouched.
- Scope: Python runtime physical handler/callback ownership, bounded admission,
  cancellation accounting, observable wait timeout and bounded shutdown.
- P17 files are the runtime, its tests, this report and the lifecycle-memory ledger.
  The overload experiment and other unrelated dirty/untracked files were preserved.

## Design and ownership

- The runtime owns a handler `ThreadPoolExecutor` with
  `NANOFAAS_MAX_CONCURRENT_HANDLERS` workers and an equally sized pre-submit
  semaphore. A synchronous `Future` or async `Task` remains retained until physical
  completion; the done callback alone releases the handler permit.
- Blocking callback HTTP calls use a separate runtime-owned executor configured by
  `NANOFAAS_CALLBACK_WORKERS`. `NANOFAAS_MAX_PENDING_CALLBACKS` bounds admission
  before submit, so `ThreadPoolExecutor`'s internal queue cannot grow without bound.
- `runtime_handler_wait_timeouts_total` counts expired HTTP waits independently from
  `runtime_active_handlers`, which represents retained physical handler work.
  Pending callbacks and physically executing callback workers have separate gauges.
- Admission check, executor submit/task creation and manager registration are atomic
  against shutdown. This prevents shutdown from reporting drain while submitted work
  is not yet visible.
- Shutdown first stops admission, requests async cancellation and closes both owned
  executors to new work. It then awaits event-driven physical drain without blocking
  the ASGI event loop. The finite report never claims that a non-cooperative Python
  thread was killed; such a handler remains counted and produces `drained=false`.
- Handler and shutdown waits must be finite positive millisecond values. Worker and
  queue settings must be positive integers. Defaults are 32 handler workers, two
  callback workers, 128 pending callbacks, 30 s handler wait and 5 s shutdown.

## RED and GREEN evidence

All ordering tests use `Event`/event-loop signals and finite deadlines; no elapsed
sleep is used as ordering evidence.

- The event-blocked sync test retains four blocked handlers across four 504 responses,
  rejects eight further submissions before executor submit, reports four active handlers,
  and drains only after event release.
- Delayed async cancellation remains active after the 504 and owns the sole permit;
  a second handler cannot be admitted until the cancellation handler cooperates.
- Initial callback isolation work exposed zero active callback workers. GREEN runs two
  simultaneous blocking HTTP calls on `nanofaas-callback*` threads while health remains
  responsive, and a separate test proves callback progress while handler capacity is full.
- Cooperative async lifespan shutdown was RED with `drained=false, active_handlers=1`
  because synchronous `Condition.wait()` blocked cancellation. The async event-driven
  shutdown is GREEN with cancellation observed and all counters drained.
- Six configuration cases (`0`, `NaN`, infinity for handler/shutdown timeouts) were RED;
  all six now fail startup with a finite-positive validation error.
- A deterministic submit/register race was RED with
  `drained=true, active_handlers=0` while the handler thread was already blocked. GREEN
  serializes acceptance, submit and registration under the shutdown monitor and reports
  `drained=false, active_handlers=1`.
- A temporary 1 ms polling bridge made the callback test pass but had no demonstrated
  root cause. An equivalent two-worker probe passed with `asyncio.wrap_future`; the
  polling was removed and the native bridge remains GREEN.

## Verification and review

- Focused P17 tests, including sync/async timeout, cooperative/non-cooperative shutdown,
  callback admission/isolation and the submit/register race: GREEN.
- `uv run pytest -q`: 76 passed, two pre-existing deprecation warnings.
- `uv run pytest tests/test_saturation_wire_corpus.py -q`: 1 passed.
- `uv build`: wheel and source distribution built successfully.
- `git diff --check`: clean.
- First independent review found five Important and two Minor observations. The async
  shutdown, finite validation and missing P17 coverage were fixed; test-owned executor
  cleanup was added. Callback-before-handler admission is explicitly assigned to P16b by
  the common contract. The handler-saturation outcome is also recorded below for P16b.
- Second independent review found one Important submit/register shutdown race. After its
  deterministic RED/GREEN fix, the same reviewer reported CLEAN: 0 Critical,
  0 Important and 0 Minor, with the race closed 1/1.

## GitNexus

Exact upstream impact calls for the new Python ownership symbols returned no graph
result. Per repository policy this was treated as `UNKNOWN`, not low risk; exact text
search confirmed runtime-local callers in `app.py` and `test_runtime.py`. Concept query
identified the existing invoke/callback owners but no indexed Python execution flow.

The pre-stage complete all-scope gate reported four files, 22 symbols, zero affected
processes and LOW risk, without `partial` or `truncated`. Its scope includes protected
user dirt. The final all/staged results and exact staged paths are appended at commit
closure.

## Changed files

- `sdks/python/src/nanofaas/runtime/app.py`
- `sdks/python/tests/test_runtime.py`
- `docs/experiments/lifecycle-memory-2026-09/STATO.md`
- `.superpowers/sdd/2026-09-08-control-plane-lifecycle-memory-and-modularity/task-P17-report.md`

## Remaining contract concern and next step

P16a defines callback saturation but no distinct wire outcome for exhaustion of the new
physical handler bound. P17 currently returns explicit retryable
`429 RUNTIME_HANDLER_SATURATED`; this is not claimed as a new common-contract authority.
P16b must either canonically adopt that outcome or replace it consistently across runtime
conformance before P16 closes. P16b also owns reserving requested callback count/bytes
before handler start, so P17 does not claim the `callback-saturated` runtime scenario yet.

Next: P18 establishes Java-lite executor/client ownership; P16b then applies the common
wire/count/byte policy using the P17/P18 primitives.
