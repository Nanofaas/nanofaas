# Direct scheduler probes

## Why the previous probe is not usable

At `peak900`, concurrency 2 and 9,501 Java dispatches in 30 seconds imply at
most `2 * 30 / 9501 - 4.234 ms = 2.081 ms` of idle slot capacity per dispatch.
The release-to-reacquisition timer reported 4.788 ms, so it cannot represent
the claimed interval population.

The implementation explains the violation: `releaseSlotAndGetHoldNanos()`
makes the slot visible before `releasedWithBacklogAtNanos` receives the release
timestamp. A concurrent scheduler visit can acquire the slot in between; the
late timestamp is then paired with a later acquisition. Consequently the
4.743 ms `pre-active` result and its 99.1% attribution are invalid.

## Hypotheses and direct measurements

The next run keeps the scheduling policy unchanged and records only events on
the scheduler thread:

- `function_scheduler_dispatch_submit_duration`: synchronous time spent by the
  scheduler calling `InvocationService.dispatch`, before its future completes;
- `function_scheduler_slot_blocked`: visits that cannot acquire a slot;
- `function_scheduler_signal_coalesced`: signals suppressed because the
  function already has a pending visit.

NanoLab collects both the Java-function series and all-function sums. Existing
wake, poll, batch, dispatch, hold and queue metrics complete the accounting.

## Decision rule

- High all-function submit utilization supports synchronous WebClient request
  preparation as the scheduler bottleneck.
- Many blocked visits per dispatch support premature self-requeue churn.
- Many coalesced signals, together with low submit utilization and low blocked
  visits, points to the pending-function arbitration/coalescing policy.
- If none is material, add no further scheduler instrumentation until the
  direct counters identify an unaccounted population.

The stale reacquisition timers remain exported only to preserve the already
archived experiment; they must not be used as evidence in this run.

## Result

Run `azure-dispatch-scheduler-direct-probes-c2` used mcFaas `7ed1e010` and
NanoLab `ce45fae`. At `peak900`, all-function synchronous submit used 7.0% of
the scheduler thread, while the scheduler recorded 37,590 slot-blocked visits
for 16,409 dispatches (2.29 per dispatch) and 6,307 coalesced signals. The
submit hypothesis is falsified; non-dispatchable visit churn is the remaining
candidate and requires a guarded A/B intervention to establish causality.

## Prepared intervention

The follow-up branch suppresses enqueue and bounded-batch self-requeue signals
while `FunctionQueueState.canDispatch()` is false. Slot release remains the
authoritative wakeup for saturated queues. Unit tests cover both suppressed
signals and release-driven progress. This intervention has not yet been run or
assigned a performance result.
