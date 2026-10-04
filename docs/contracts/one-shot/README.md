# One-shot contracts, version 1

These JSON Schema 2020-12 documents define the persisted boundary between forecasts,
service calibration and auction events. Reject unknown versions, missing required
fields, unknown properties and non-finite numbers. The exporter validator also checks
cross-field interval and replica bounds. Hashes identify SHA-256 of the original
immutable bytes; a document never contains its own hash.

Forecasts express **external arrivals** in requests/s over UTC half-open intervals.
Function generations belong to the originating node; they are not globally comparable.
A revision replaces an entire forecast atomically. Intervals for the same function
and generation cannot overlap. Forwarded requests and internal retries do not add arrivals.

Service profiles record physical warm handler occupancy in seconds, excluding queue,
transport and cold starts. Preserve the raw measurement bytes identified by
`rawSamplesHash`, the environment fingerprint and source commit. Host/VM/CPU, image,
runtime, backend, input, resource limits and co-location form the measurement context.
The replica validity interval and allowed capacity error limit application of the
model. Synthetic fixtures cannot substitute for measured experiment profiles.
`workflow-validation` profiles belong to local validation; scientific runs require
`scientific-experiment` profiles measured in their actual environment.

Events are JSON Lines: each line validates against `run-events.schema.json`.
Monotonic offsets are meaningful within one node incarnation only. UTC correlates
nodes; censored deadline closures are not completed-auction timing samples.

## Frozen optimizer reference

Fixtures are under `platform/modules/offload/src/test/resources/one-shot/reference`.
The manifest pins DFaaSOptimizer commit
`71899f720a4ffffd070ebf7ddc5b74afddfde9c5` and hashes both source and fixture bytes.
The checkout must be exactly at that commit and the named sources must be clean.
Regenerate using its existing Python environment (NumPy, pandas, Pyomo and optimizer
dependencies); `jsonschema` and `pytest` are needed only for contract tooling tests:

```sh
python scripts/one-shot/export_reference.py --reference /path/to/DFaaSOptimizer \
  --output platform/modules/offload/src/test/resources/one-shot/reference
python -m pytest scripts/tests/test_one_shot_contracts.py -q
```

Ordinary Java tests read checked-in JSON; no Python solver is used at runtime or
in Java CI. The 80 small LSP/LSPr_x cases have independently enumerated objective
checks. INFEASIBLE is meaningful for these bounded, validated cases; a general
unsupported reference input must not be classified this way.

The auction transcript calls the original base helper functions on an explicit
feasible initial allocation, recording capacity, bids, grants and prices for each
round. It does not claim to reproduce initialization, convergence or a full runner.
Tentative replica starts, replacement, hierarchy and PG local search are disabled.
Later engine tests must cover the remaining base transitions independently.

## Variable mapping and ties

`x` is origin traffic executed locally; `omega` is the intended outbound edge load;
`z` is residual/rejected load in the optimizer, mapped by runtime policy to terminal
cloud when admissible. It is not automatically an accepted cloud allocation.
`r` is desired replica count; runtime capacity uses confirmed ready instances.
Inbound assignments consume seller capacity but never become new external arrivals.
Rates are quantized by q requests/s; multiply service demand by q for the model.
Fractional leftovers follow the explicit terminal-cloud policy.

LSP minimizes negative normalized welfare. Its DP uses exact memory-GCD compression,
ascending replica levels and strict cost improvement; per-level equal-cost choices
prefer larger x, then the first minimum-memory budget during backtracking. Independent
enumeration/MILP may return a different allocation at the same objective; objective
parity alone does not establish DP tie-break parity. LSPr_x fixes origin x and outbound
commitments, includes inbound load and chooses the minimum feasible replica count.

## Arrival observation at the HTTP boundary

The core observes valid invocation/enqueue HTTP requests for a currently registered
function once, before subscribing to the execution service. Presence of the native
`X-NanoFaaS-Offload-Hop` header excludes forwarded traffic. Internal execution retries
and resubscription do not produce HTTP arrivals. Each client repeat is a new arrival,
even with the same idempotency key; replay outcome handling and arrival counting are
separate. Experiment clients disable automatic retries or correlate them explicitly.
The observation contains function, local generation, UTC arrival instant and a fresh
request correlation ID, never input or handler headers. Without the optional provider,
the observer is a no-op. A re-registered function gets a new generation and cannot
inherit windows attributed to the previous one.
