# P24 Minimal Lifecycle Metrics Design

## Goal

Preserve the P24 comparison between historical control plane `e35405ee` and the
post-restructuring candidate, while keeping the reusable NanoLab workflow
single-version. Each comparative arm uses the common `advanced` profile. A
separate candidate-only run uses the `soak` profile to expose the minimum
internal metrics needed to identify known NanoFaaS-owned retention.

The soak is not a replacement for lifecycle correctness, idempotency, transport,
or SDK unit tests. It must not instrument every timeout, request, or object merely
because that state could theoretically retain memory.

## Admission rule for soak metrics

A metric is part of the P24 settlement contract only when all of these conditions
hold:

1. It represents a concrete state owner in NanoFaaS.
2. It can be observed from existing accounting or with fixed-cardinality O(1)
   accounting.
3. It has no dynamic labels.
4. Its post-drain expectation is unambiguous: zero or an explicitly captured
   baseline.
5. It distinguishes a retention class not already covered by another owner
   metric.

Metrics that fail any condition are removed from the applicable NanoLab role.
NanoLab must not require synthetic zero gauges for non-applicable populations.

## Comparative qualification and candidate diagnostics

Every single-version run retains the hard process-memory criterion:

```text
post-drain RSS <= baseline RSS
```

There is no positive tolerance. Process RSS, PSS, cgroup usage, and JVM heap are
collected by NanoLab through its existing process and Prometheus probes.

P24 performs two distinct operations:

1. Run `e35405ee` and the candidate with `advanced`, using only metrics available
   in both revisions for the historical comparison.
2. Run the candidate with `soak`, using the new owner metrics as secondary
   settlement gates and diagnostic evidence.

The historical image is not modified or backported. New owner metrics are never
required from it, and a candidate run with `soak` is not compared directly with
an historical run using `advanced`.

## Control-plane settlement contract

### Existing metrics

NanoLab retains these existing metrics:

| Population | Metric |
| --- | --- |
| Live execution records | `execution_in_flight_records` |
| Retained outcomes | `execution_store_size` |
| Idempotency entries | `idempotency_keys_held` |
| Pending connection acquisition | `nanofaas_http_pool_pending_acquisitions` |
| Replica snapshot entries | `replica_snapshot_entries` |

### New metrics

NanoFaaS adds these fixed-cardinality gauges:

| Population | Metric | Source |
| --- | --- | --- |
| Logical execution reservations | `invocation_execution_reservations` | `InvocationCapacity.executionReservedGlobally()` |
| Canonical input accounting | `invocation_canonical_input_bytes` | `InvocationCapacity.inputReservedGlobally()` |
| Physical input-copy accounting | `invocation_physical_input_copy_bytes` | `InvocationCapacity.physicalInputCopyReservedGlobally()` |
| Attached synchronous waiters | `execution_waiters_retained` | `WaiterCapacity.retainedWaiters()` |
| Scheduled execution-expiry work | `execution_expiry_queue_depth` | owned `ScheduledThreadPoolExecutor` queue |
| Retired function generations | `function_capacity_retired_generations` | `FunctionCapacityRegistry` lifecycle state |

The first five gauges read state already owned by the control plane. Retired
generation accounting may add one aggregate counter updated only during function
registration, retirement, and drain; it must not add work to invocation dispatch.

Every new metric is unlabeled. In particular, no function name, execution ID,
generation, endpoint, or callback URL is exposed as a label.

## Java SDK settlement contract

NanoFaaS adds only these gauges:

| Population | Metric | Source |
| --- | --- | --- |
| Handler capacity owners | `runtime_active_handlers` | reserved permits in `HandlerExecutor` |
| Pending callbacks | `runtime_pending_callbacks` | `CallbackDispatcher.pendingCallbackCount()` |
| Pending callback reservation bytes | `runtime_pending_callback_bytes` | `CallbackDispatcher.pendingCallbackBytes()` |

`runtime_active_handlers` observes the handler capacity owner rather than servlet
requests. A timed-out, non-interruptible handler therefore remains visible until
its task actually releases capacity. The value should be derived from existing
semaphore state rather than maintained by a second invocation-path counter.

The callback gauges publish accounting already maintained for admission. They do
not introduce another callback queue or payload copy.

## JavaScript SDK settlement contract

No new JavaScript SDK metrics are required. NanoLab uses the existing:

```text
runtime_active_handlers
runtime_input_bytes
runtime_output_bytes
runtime_pending_callbacks
runtime_pending_callback_bytes
runtime_serialized_callback_bytes
```

## Requirements removed from NanoLab

NanoLab removes the global union of populations and validates a role-specific
contract instead.

The following requirements are removed from P24 qualification:

| Requirement | Decision |
| --- | --- |
| Generic `timers` on every role | Remove. Keep only the concrete control-plane expiry queue. |
| Generic `pending_http` on every role | Remove. Existing acquisition and callback owners cover actionable retained state. |
| `physical_executions` / handler-start counter | Remove. Idempotency execution count is a correctness-test concern, not a memory-soak owner. |
| Absolute `metric_series` settlement | Remove. An exposition has a non-zero baseline and an absolute-zero policy is invalid. |
| Java input/output/serialized payload gauges | Remove. Exact retained Java object size is not available without intrusive or duplicate serialization. |
| Control-plane physical handlers | Remove. Physical handlers belong to SDK runtimes. |
| SDK idempotency entries | Remove. Idempotency is owned by the control plane. |
| SDK retired function owners | Remove. Function generations are owned by the control plane. |
| Control-plane callback queue | Remove. Callback delivery queues are owned by SDK runtimes. |

Prometheus series count may remain report-only evidence. It is not a settlement
gate until NanoLab defines and tests a relative baseline contract.

## Role-specific populations

| Population | Control plane | Java SDK | JavaScript SDK |
| --- | --- | --- | --- |
| Execution records | Required | N/A | N/A |
| Outcomes | Required | N/A | N/A |
| Idempotency entries | Required | N/A | N/A |
| Logical execution reservations | Required | N/A | N/A |
| Canonical input bytes | Required | N/A | Existing runtime input accounting only |
| Physical input-copy bytes | Required | N/A | N/A |
| Waiters | Required | N/A | N/A |
| Expiry queue depth | Required | N/A | N/A |
| Retired generations | Required for function churn | N/A | N/A |
| Handler owners | N/A | Required | Required, existing |
| Pending callbacks | N/A | Required for callback coverage | Required for callback coverage, existing |
| Pending callback bytes | N/A | Required for callback coverage | Required for callback coverage, existing |
| Output and serialized callback bytes | N/A | N/A | Existing diagnostic evidence |

`N/A` means absent from frozen settlement input. It never means a synthetic metric
whose value is always zero.

## Metrics profiles

No `debug` profile is introduced. The hierarchy is:

```text
basic    = production-essential metrics
advanced = basic + histograms and detailed operational metrics
soak     = basic + advanced + P24 ownership metrics
```

The new metrics are aggregate, unlabeled, fixed-cardinality owner gauges, but
their purpose is specialized lifecycle qualification. They are registered only
when `nanofaas.metrics.profile=soak`. Selecting `soak` also enables everything
enabled by `advanced`, including its histogram configuration.

When `soak` is not selected, registration of the new gauges is skipped rather
than merely hidden after registration. Existing owner accounting remains
unchanged; the instrumentation must not add invocation-path accounting solely
for metrics. Heap dumps, JFR recordings, stack traces, and per-execution evidence
remain bounded diagnostic artifacts, not Prometheus metrics.

## NanoLab behavior

NanoLab must:

1. Build settlement policies per role instead of applying one population union to
   every image.
2. Freeze explicit metric bindings for each applicable population.
3. Fail preflight when an applicable metric is absent or non-finite.
4. Ignore no missing applicable value and never coerce one to zero.
5. Omit non-applicable populations completely.
6. Preserve behavioral prerequisite coverage while removing memory-irrelevant
   proof counters from the soak gate.
7. Keep metric-series counting, if collected, informational only.
8. Configure one metrics profile per single-version run and record the effective
   profile in its immutable manifest.
9. Require ownership metrics only when the effective profile is `soak`.
10. Use the common `advanced` profile for both historical comparison arms.

## Testing strategy

NanoLab tests first establish that role-specific settlement accepts only the
applicable matrix and rejects a missing applicable binding. Existing tests that
expect all populations on all roles are replaced. Tests also prove that handler
start counters and absolute metric-series counts are no longer required.

NanoFaaS tests then verify each new gauge against the real owner state and verify
return to zero after release, timeout, cancellation, callback completion, expiry
cancellation, and function-generation drain as applicable. No test relies on
sleeping when the owner exposes deterministic release or cleanup.

The final integration proof has two outputs: the `advanced` historical comparison
and the candidate-only `soak` qualification. In every arm, post-drain RSS must
return to its own baseline without tolerance. The new owner gates apply only to
the candidate `soak` run.
