# One-shot clock robustness — protocol design draft

Status: draft for architecture review, not implemented, not qualified, not a
replacement for the approved campaign. UTC thresholds and frozen v11 artifacts
have not been changed. No new physical campaign has started.

Review correction: the user specified that accepted local queue entries execute
against the new allocation, preserving their chosen local destination. An old
arrival epoch must not invalidate them. Receiver-issued capabilities authorize
pool management; they are not per-request queue-lifetime tokens. The user also
selected fixed-count auction rounds rather than distributed global termination.
These decisions supersede conflicting queued-permit and convergence passages
below; implementation is tracked in the review-remediation plan.

## Intent and scope

The user requires robustness to unsynchronized clocks in realistic edge nodes.
Increasing a clock-health threshold does not meet this requirement. Preserve
one-shot's aggregate bids, frozen auction inputs, receiver capacity constraints,
strict function memory, one-hop peer execution and terminal cloud fallback.
Keep the original scientific campaign, topology, modes, functions, raw data and
report requirements. Any runtime change needs a new source/image/protocol
fingerprint and fresh calibration and qualification before scientific use.

## Verified current behavior

Inspected NF clean revision 1f8bf57 and NL revision 580e9a8 plus the uncommitted
probe diagnostics. GitNexus query returned coordinator/actuation/routing symbols;
its index is two commits behind and several context entries are malformed.
This evidence is not sufficient for editing runtime symbols: refresh the index
and run per-symbol upstream impact before implementation.

EpochCoordinator uses nanoTime for solver/auction deadlines but also rejects
preparation, messages and active auction checks against local UTC startsAt.
ReplicaPlanActuator uses local UTC for plan activation, protection of reserved
capacity, preparation checks and drain/release eligibility. Clock health can
invalidate an otherwise physically owned plan. ActiveRoutingPlan.validAt is a
UTC predicate. PlanRouter currently accepts forwarded traffic only for the
single active plan and checks epoch, assignment, origin incarnation and quota;
its token buckets already use a local monotonic time source. Those checks are
useful fencing foundations, not a complete epoch-transition protocol.

The failed v11 clock sample has estimated offset -64.34 ms and uncertainty
67.37 ms; its conservative bound is 131.71 ms. It does not prove a real offset
of that magnitude. The monitor stopped at the first above-limit selected
sample. The existing parser-contention reproduction did not reproduce the
failure. There is no confirmed root cause for that HTTP delay.

## Alternatives

1. Require external clock discipline and choose a justified skew bound. This
   can qualify the present synchronous implementation but does not satisfy the
   user's requested algorithm robustness.
2. Convert all existing UTC predicates to local monotonic deadlines, preserving
   independently derived UTC epoch starts. This tolerates UTC jumps after a
   snapshot, but arbitrary initial offsets can still produce different epoch
   numbers and nonoverlapping auctions. Insufficient as the final design.
3. Use explicit logical epoch identity, monotonic local duration budgets and
   receiver-owned fenced commitments. Recommended direction. It requires changes
   across scheduling, negotiation, actuation, admission and the experiment
   driver, not merely ClockHealth or a monitor retry.

## Recommended protocol invariants

- UTC is metadata for observability and external reporting, never permission to
  spend, release or transfer capacity. Clock health is diagnostic.
- A logical epoch is identified by run/session identity, monotonically ordered
  epoch number, immutable configuration revision, node incarnation and function
  generation. Messages cannot recreate a retired epoch or previous incarnation.
- Each node starts local duration budgets from an accepted explicit epoch trigger
  or bounded peer initiation, using a monotonic clock. A common absolute UTC
  instant is not required. The trigger must not imply globally simultaneous
  activation; delivery skew and readiness lag must be measured.
- A seller owns admission permission and its reserved physical capacity. An
  outbound buyer route is installed only after a seller-ready response for that
  exact identity. A lost acknowledgement must not authorize extra capacity.
- New and old commitments may not share the same capacity twice. Before changing
  replica allocation, the seller atomically closes old admission, fences stale
  requests, and positively drains execution on resources actually removed or reassigned. Unchanged replicas may continue already admitted work under their original physical leases; new-epoch work can acquire only free physical slots. When drain is not
  proven, capacity stays reserved and the conflicting preparation cannot proceed.
- Sender readiness and seller readiness need not change simultaneously. Late
  old-epoch traffic is rejected at the seller; early new-epoch traffic cannot be
  accepted without an installed grant. These outcomes are counted, not hidden.
- Per-assignment rate admission and per-replica c1 remain enforced by the receiver.
  An epoch/token-bucket reset must not enable a transition burst that violates
  the promised capacity. Define and test the burst policy across epochs.
- A local monotonic expiry may close future admission, but never proves physical
  execution has ended. No wall-clock jump or timeout can bypass drain.
- Failed negotiation or preparation uses the existing documented fallback rules.
  No automatic duplicate invocation after an ambiguous forwarded response; retain
  unresolved originals and physical proof accounting. Cloud capacity is finite:
  fallback may return a documented error or rejection.
- Partitions and crashed peers cannot guarantee successful offload or simultaneous
  progress. Preserve safety, bounded local work and explicit degraded outcomes.
  Eventual recovery requires communication and available capacity. No claim of
  progress under unlimited delay or a broken monotonic clock.

## Proposed concrete transition protocol for review

Keep decentralized neighbour auctions. Do not add a central runtime scheduler or
require a global all-node barrier: an unavailable unrelated edge must not prevent
healthy neighbours from retaining local service and attempting a new agreement.
This proposal permits staggered activation and explicit stale-route rejection;
it does not promise atomic activation across the network.

### Logical session and epoch initiation

Introduce protocol v2 with an explicit bounded session ID installed with the
configuration. Nominal startsAt/endsAt remain canonical labels derived from
anchor + epoch * period; no node compares them with its local wall clock for
protocol authorization. Session ID and period are frozen until an explicit drain
and session replacement. Revision/function/profile eligibility remains local
and is checked before participation.

Manual prepare identifies the logical epoch and carries a relative activation
delay, bounded by configured leadTime, rather than deriving a deadline from UTC.
The experiment driver prepares all five edges concurrently and sends explicit
activation for that epoch at its common load boundary; delivery/readiness lag is
recorded. One operator timing reference defines the trace, not a synchronized
clock prerequisite on each runtime node.

Automatic mode arms epoch zero through an explicit session start. A local
monotonic period timer proposes the next logical epoch to current neighbours.
A valid neighbour proposal can trigger that same epoch locally and is forwarded
once per epoch, with bounded deduplication. Each node holds at most one active
or retiring epoch and one preparation. Competing proposals for the same identity
are idempotent; different session/period/canonical-label identities are rejected.
There is no wall-clock-derived skip. A peer behind by more than one epoch asks
for current session/epoch status, closes and drains its old state, records skipped
epochs and joins the reported current epoch only after validating its local
configuration and incarnation. It never reconstructs old grants from status.
Session replacement/restart requires fresh admission grants for the new node
incarnation; tombstoned messages cannot resume execution.

### Receiver ownership and physical transition

The local state machine is IDLE -> PREPARING -> SELLER_READY -> ACTIVE ->
RETIRING -> DRAINED. Failures enter a closed/degraded state with ownership
retained until positive drain. CLOSED epochs cannot reopen through duplicate
READY messages. Preparing a successor first closes the previous epoch's inbound
and origin-local admission; original traffic during the gap follows the declared
cloud fallback. Already admitted execution stays tracked until physical release.

Reuse the physical replica lease and the existing container proxy's physical
slot/release-proof machinery. Add a lease/generation-fenced quiesce/resume
operation that closes admission and waits for occupied slots without destroying
unchanged warm containers. The existing beginDrain is irreversible; updateBackends
does not clear it, so simply calling it and updating the pool is not a valid
resume implementation. A new resume capability must match the exact quiesce
operation, generation and backend incarnation. Strict resume requires every old slot drained when reassigning physical capacity. A distinct retained-allocation operation rotates admission without freeing occupied leases; old work remains counted against the same c1 slot, and no resource is reallocated by that operation.
Downscale obsolete replicas through the existing positive-drain path before
upscale; retained replicas resume only with the new admission epoch.

Close and acquire of an epoch admission permit must share an atomic boundary.
A route computed before retirement but dispatched afterwards must either own a
tracked old-epoch permit (which blocks drain) or be rejected at physical dispatch.
Checking epoch only in PlanRouter, then reopening the same proxy, leaves a race.
A dispatch permit is released only by undispatched cancellation or matching
physical RELEASED evidence, never by an HTTP timeout alone. The control-plane
execution/dispatch bridge and the provider must carry this fence; SDK physical
proof identity is reused rather than replacing it with a new completion signal.

### READY and activation

A seller installs the inbound admission grant before advertising READY. The
READY_ACK receipt verifies the exact session, epoch, assignment, generation and
both incarnations. A buyer may route only after that receipt and its own local
activation. Lost messages retain seller reservations until retirement; retries
are limited to idempotent control messages, never original invocations.

Seller inbound readiness is independent of completion of its own outbound
negotiation. Keep per-epoch readiness flags and persistent quota objects rather
than replacing the plan and recreating full token buckets on every READY update.
New assignment buckets start empty. Preserve/clamp the per-function aggregate
bucket across rate changes so transition cannot create an extra burst; all
inbound/local decisions also obey physically ready c1 dispatch capacity.

Local monotonic expiry closes admission. Positive drain is still required before
physical resource reassignment, removal or ownership release, even after expiry, a failed acknowledgement,
a partition or a configuration change. During a partition one-shot may degrade
to finite cloud capacity and errors; unchanged local monotonic timeouts bound
work. It is not a claim that offload remains successful under unlimited delay.

### Compatibility and experiment alignment

v2 uses distinct P2P topics and strict versioned schemas. v1 data remains readable
but cannot qualify v2. Mixed-version native neighbours do not negotiate grants;
record the incompatibility and use explicit fallback. No silent reinterpretation
of v1 UTC windows. Document the session/relative-delay/activation fields in the
module OpenAPI and native configuration, and update NanoLab bindings.

The nine common 360 s load windows remain defined by the generator/driver.
ORACLE chooses rates by explicit logical epoch, not remote UTC; EWMA continues
using only observed past requests. Report nominal trace epoch and actually active
routing epoch separately for every request/plan/telemetry sample. Transition
traffic stays in the original planned-window denominator; reject or delayed
activation is an observed outcome, not grounds to exclude originals. Manual
campaign activation and automatic P2P initiation both require tests.

## Architecture review boundaries

This is a proposed protocol, not an implemented or qualified design. It changes
scheduling and physical admission together; replacing only ClockHealth, or only
UTC predicates, is insufficient. Review the pause/resume permit race, late-peer
join rules, quota continuity and experiment activation semantics before a native
implementation plan. Preserve the existing scientific gates, replacing only the
clock-dependent interpretation that contradicts this requirement.

## Updated graph and provider evidence

Full index rebuild now resolves EpochCoordinator and ReplicaPlanActuator as
classes. Upstream impact: coordinator MEDIUM, nine symbols including operations
and configuration; actuator LOW, seven symbols including operations and the
coordination area. This is class-level scoping only: method-level impact is still
required before each edit. FTS remains broken after repair and full rebuild;
explicit-symbol graph walks work, concept search is limited until repaired.

ManagedDeploymentCoordinator lease expiry is local monotonic time. Existing
LocalManagedDeploymentProvider downscale calls beginDrain and awaitDrained before
removing one-shot containers. ReplicaSlots does not resume a draining backend
when a pool update preserves its incarnation. These observations support reuse
of physical accounting but require a new explicit fenced resume capability.

## Verification and campaign consequences

Use controllable independent wall and monotonic clocks in Java protocol tests.
Cover positive/negative offsets larger than 100 ms, UTC jumps during auction,
preparation, activation and drain, differing epoch trigger delays, stale requests
crossing transition boundaries, dropped/duplicated READY/ACK, restart and
incarnation changes, partition/recovery and long-running physical handlers.
Assert no premature release, double reservation, stale grant resurrection,
quota reset over-admission, invocation duplication, or incorrect error accounting.

Run native-image multi-node integration with deliberate wall-clock skew using
an injectable test clock rather than changing host/VM clocks used by infrastructure.
Then test realistic delayed control messages and SDK admission/proof boundaries.
Measure protocol completion, readiness lag, rejected stale requests, unresolved
responses and fallback routing as well as CPU/RAM and collection overhead.

The scientific driver must no longer fail an entire protocol run solely because
an HTTP clock measurement is inconclusive. This does not permit silently accepting
uncorrelatable resource data: preserve uncertainty and annotate or bound time
alignment separately. Specify correlation qualification before acquisition.

All old v11 data remains technical failed evidence. Qualification still requires
fresh independent overhead pairs, logging pairs, physical capacity calibration,
native timing qualification, actual-trace gates and pilot before main acquisition.
A revised protocol must not reuse old profiles/timing evidence by relabeling them.

## Independent diagnostic work already verified

Added optional raw per-probe recording in NanoLab, leaving minimum-RTT selection,
three monitor attempts, acceptance threshold and failure behavior unchanged.
Record node, attempt index, before/after UTC, remote timestamp, RTT and exception
class in clock-probes.jsonl. Retain successful attempts preceding a failure.
Tests were observed failing before implementation, then 43 focused tests passed.
Verification completed: 3,870 pure tests passed (three ordinary skips; checkout-only tests were skipped in that invocation), 636 pinned-checkout contracts passed, and 51 tui-toolkit tests passed. All 15 hooks pass. Diagnostics are committed locally in NanoLab; no push or new frozen runtime qualification.

## Task1 transition refinement supported by physical observations

Retained failed-v11 data is technical design evidence, not qualified experimental results. Edge memory handlers reached approximately2.98s p95 and3.10s maximum physical occupancy, beyond the current1s preparation budget. Requiring every unchanged replica to drain at each logical boundary would introduce avoidable downtime. Admission rotation therefore preserves ongoing leases on unchanged allocations; physical downscale or resource reassignment still requires positive release before growth. Strict memory budgets, c1, stale-dispatch fencing and all measurement gates remain unchanged.
