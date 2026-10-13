# One-shot epoch coordination

The optional offload module enables coordination only with
`nanofaas.offload.one-shot.enabled=true`. It requires the P2P and forecast API
providers. Local identity and incarnation come from the active P2P transport;
configure an explicit `nanofaas.p2p.invocation-uri` on every participating node.
Old peers without endpoint announcements are ineligible.

Explicit positive durations are required for `auction-budget`, `peer-timeout`,
`solver-budget`, `clock-threshold` and `clock-max-age`. The last two govern an
externally measured clock-health sample; they are not inferred from network RTT.
Administrative profile/input wiring supplies the immutable epoch catalog.
The period is supplied by the target UTC half-open epoch window, with no one-minute
default. Preparation must start before that window. Auction budget is bounded by
`max-auction-fraction` (default 0.1, maximum 0.2); later timing qualification must
also establish a much smaller observed high-quantile auction duration with margin.

Bounds default to 100 rounds, four concurrent requests, 16 peers and 64 pending
batches. Queue capacity must be at least four times the peer bound and never exceeds
256. Each batch is limited to one MiB and 1000 messages. The codec rejects unknown
fields, numeric coercion, incompatible typed phases and invalid identities.
`nanofaas.oneshot.v1` carries offers, bids, grants and explicit complete round-phase
closures. Each seller closes all neighbor inputs before allocating. One dedicated
control scheduler freezes forecasts and profiles, invokes the pure engine and
waits for bounded barriers. There is no global optimizer, PG search or hierarchy.

### Aggregate bids and round traffic

The base mode uses aggregate bids (`unitBids=false`). For each selected neighbor
and function, a capacity bid carries one price and a quantity, rather than one
record per unit. The price is calculated once while constructing that round's
bids; prices, residual capacity and remaining demand may change between rounds.
An aggregation group retains its buyer and seller identities, epoch, round,
function version, buyer generation and price. Different groups remain separate.
Memory-only requests are separate records with zero quantity; their multiplicity
is not a capacity quantity.

`quantity` counts integer flow units on the shared grid, not individual HTTP
invocations. For example, `(function=f, price=0.01, quantity=2000)` with
`flowQuantum=0.001` requests/s per unit asks to allocate 2 requests/s. The seller
may grant fewer units when capacity is insufficient. Aggregate bids preserve
the price ordering and buyer tie-break used for unit bids; grants from earlier
rounds remain committed.

The coordinator already collects all records for a neighbor into one batch per
round phase: OFFERS, BIDS, GRANTS and CHECK. Empty batches still close their phase.
With P neighbors and R completed rounds, a node sends `4 * P * R` round-phase
request batches without retries. With F functions, the base mode sends
`O(P * F * R)` records. HELLO, response messages, retry attempts and physical
readiness exchanges are counted separately. Larger quantities increase the
number of digits in a record, not the number of records or batches.

The 1000-message bound limits records in a batch, not flow units: one bid can
carry more than 1000 units. Large catalogs or additional memory-only requests can
still exceed the record or byte bound and fail preparation. Batches are not
fragmented: each is the complete phase closure, and conflicting repeated content
is rejected. Retries resend the same serialized batch and do not add commitments.

Every participant executes exactly `negotiation.maxRounds` complete rounds,
including rounds with no local changes. All peers must use the same configured
count. CHECK remains a phase barrier; its change flag is diagnostic and never
allows a node to leave early. After the final closure, the ledger is finalized
and the outcome is `FIXED_ROUNDS_COMPLETED`. This is a bounded auction, not a
claim of global convergence. No distributed convergence detector is required.

DEADLINE and FAILED remain censored outcomes: a missing closure never counts as
a completed round. Legacy CONVERGED and ROUND_LIMIT values remain readable but
are not emitted by this coordinator or eligible for new physical preparation.
A completed fixed-round auction may proceed to physical readiness. Its duration
measures execution of the configured rounds, never time to convergence. Record
solver durations separately from the whole operational auction, including
waiting, serialization and exchange. Grants remain provisional; readiness and
exclusive replica ownership are required before routing.

Identical installed batches are acknowledged again, including after round close,
without reapplying them. Only bounded fingerprints of the previous closed round
are retained. Different repeated content is rejected. A new P2P incarnation,
expired clock-health sample, stop, deadline or concurrent preparation invalidates
the run. No old callback can install a new active run. Three real local P2P services
and disturbed in-memory transport tests verify the wire boundary and these cases.

Physical preparation owns a generation-fenced replica lease. Every other replica
writer and function PATCH receives an ownership conflict while this lease exists.
Renewal changes the capability token; expiry invalidates mutations and never frees
occupied resources. Only a positive physical drain to zero releases ownership.

The function concurrency setting is a node-wide ceiling sufficient for all ready
replicas. Each physical replica uses SDK `NANOFAAS_MAX_CONCURRENT_HANDLERS=1` and
`STATIC_PER_POD` with `targetInFlightPerPod=1`. The lease holder sets effective
dispatch capacity from fresh ready counts, including zero. A zero-ready plan
provides no execution capacity; requests eligible for bounded local admission
can wait in the queue until capacity opens or their ordinary timeout expires.

The actuator reduces old replicas only after physical drain, and keeps capacity
reserved by still-valid commitments. It accounts existing replicas before adding
new ones, so adjacent windows cannot oversubscribe memory during preparation.
Desired replicas never constitute readiness. READY subsets may only reduce
provisional assignments; the buyer installs a grant after the seller acknowledges
READY_ACK. Lost replies retain seller reservations until window expiry and drain.
In v1, the immutable routing plan activates at the agreed UTC start, provided clock,
incarnation and generations remain valid. Unready capacity becomes cloud residual.

### Automatic schedule grid

The first automatic epoch claim freezes `(anchor, period)` for the lifetime of the process, including failed preparations. Later changes return HTTP 409 (`ONE_SHOT_CONFLICT`) without consuming the configuration revision. Disabling scheduling, draining ownership, or stopping and starting the lifecycle does not reset this fence. Other settings may change when their existing eligibility and timing qualification checks pass.

To change the grid, disable scheduling and drain commitments and physical executions on all peers, then perform a coordinated restart with the same new anchor and period and collect 20 complete timing samples again before enabling automatic scheduling. Before the first automatic claim, grid changes remain possible only when every scheduled configuration selects an epoch above the coordinator’s last prepared manual epoch; this includes scheduled-to-scheduled updates. Stale candidates return 409 without consuming a revision. Enabling scheduling must also wait for any active manual preparation.

Each automatic claim registers its cancellation ownership before input pinning. Stopping the lifecycle cancels that claim even when subscription initialization has not finished; a later start cannot resume the cancelled preparation. Pinned input and the busy flag remain owned until cleanup completes.

Automatic epoch windows retain the full `Duration` precision, including fractional milliseconds and fractional anchors. Both endpoints derive from the same exact period, so adjacent windows share an endpoint without overlap or gaps. A window whose start is already reached is not prepared retroactively. Arithmetic or `Instant` range overflow raises an explicit configuration/calculation error rather than wrapping the epoch index.

### Preparation for clock-independent epoch transitions

The container runtime now provides a reusable admission pause separately from
permanent replica removal. `beginQuiesce()` closes physical slot acquisition and
returns a pool-owned capability. `awaitQuiesced()` waits for exact SDK release
proofs, including handlers whose HTTP response has already timed out. `resume()`
requires that same unconsumed capability and its receiver-issued opaque admission
identity, unchanged backend membership/incarnations, and no occupied or permanently
draining slots. An unchanged warm backend is retained across this pause.

After resume, the proxy acquires physical slots from the current pool; queued
requests carry no replica-epoch identity. An already admitted dispatch racing
quiesce waits up to the proxy timeout rather than failing solely for the pause.
A dispatch that acquired its slot before closure remains tracked and prevents
reuse until physical release. Pool membership changes close admission again;
a fresh quiesce capability is required. Permanent `beginDrain()` cannot be
undone through this mechanism.

The legacy v1 coordinator still requires measured clock health and UTC epoch
windows. V2 manual and automatic logical initiation are implemented below with
monotonic deadlines. Runtime tests do not qualify the scientific campaign;
NanoLab integration and scientific qualification remain pending.
The scientific v11 failure is not reclassified by these tests.

For unchanged allocations, the container proxy also exposes an explicit retained
admission rotation. It consumes the same receiver-issued one-use capability and
rejects changed membership/incarnations or permanently draining backends. It
preserves occupied physical leases: new-epoch invocations can acquire only free
slots. Accepted local queue entries use the allocation open at dispatch time,
including entries admitted in a previous epoch. This operation does not authorize
container removal or resource reassignment; those still require positive physical
release and the strict quiesce/resume path. The managed container provider and replica controller now expose the same
operations through SPI capabilities bound to the exact writer lease and deployment.
Renewal/replacement makes old wrappers stale; expiry during provider calls leaves
admission closed and physical ownership retained. Unsupported providers cannot
issue a capability. These capabilities authorize pool management only; they are
not attached to LOCAL routes or queued invocation tasks. Routing happens before
local admission and is preserved across the allocation change. Queue entries
are neither rerouted nor rejected merely because their arrival epoch ended.
Requests already executing retain their physical leases until matching release
proof. Remote arrivals still validate metadata, the current neighbor incarnation,
and calibrated image/input. A request carrying an earlier epoch's assignment is
eligible for ordinary LOCAL admission under the current allocation, even if its
old grant is absent from the new plan. It does not consume or recreate an expired
assignment quota. The old assignment records the sender's routing decision; it
does not reserve capacity in the new epoch. Current-epoch assignments retain
their ready-grant/quota checks; future-epoch assignments are rejected.
During a logical physical-allocation transition, new original requests and
validated previous-epoch neighbor requests for the affected functions select
LOCAL even while no ready plan is published. They use
the normal bounded queue admission; a full queue retains its existing rejection
behavior. Dispatch waits for available capacity in the new allocation. This
temporary local route ends when local readiness is published or the transition
fails/stops; merely having no plan at startup does not enable it. Input/image
validation still applies. Queue admission does not promise immediate execution:
physical dispatch acquires available capacity from the current allocation, and
ordinary queue/execution timeouts remain applicable. A received neighbor request
cannot be forwarded again to another peer or the cloud.
Scientific-driver and native-image integration remain pending.

### Logical epoch primitives (integration in progress)

`EpochCoordinator.prepareLogical(sessionId, epoch, startsAt, endsAt, activationDelay)`
uses the labels only as message/forecast identities. Auction and solver budgets
use the same injected monotonic clock, captured before input freezing. Logical
messages use the strict `nanofaas.oneshot.v2` envelope and include the session and
configured round count; mixed sessions, protocols or counts cannot negotiate.
The session is fixed for the coordinator lifetime. Duplicate/older preparations
are rejected before refreezing inputs. Stop/restart during input freezing cannot
install the old preparation into the new lifecycle. Native reflection includes
the new envelope.

The logical coordinator passes its timing context to the actuator. Configuration
`schemaVersion: 2` requires a bounded, nonblank `sessionId`; v1 keeps its existing
JSON contract and cannot use logical endpoints. Once logical preparation starts,
the session, anchor and period are frozen for the process. None of these software
capabilities qualifies the campaign.

Manual v2 operation uses `POST /v1/admin/offload/one-shot/sessions/{sessionId}/epochs/{epoch}/prepare`
with `startsAt`, `endsAt`, and `activationDelay` (ISO-8601 duration). Labels must
match `anchor + epoch * period`; their relation to the node wall clock is irrelevant.
Timing is captured before profile/forecast pinning. The response `NEGOTIATED`
requires `POST .../sessions/{sessionId}/epochs/{epoch}/activate`. Activation is
cached for the current staged epoch. The complete operational timing sample adds
negotiation/input-pinning and physical activation time, excluding the intentional
wait between these calls. Negotiation alone cannot qualify scheduling. Status
reports the configured protocol version and session. Native binding includes the
new request shape; native-image execution still requires qualification.
Tests exercise UTC offsets/jumps, monotonic deadline exhaustion/wrap, duplicate
requests and lifecycle fencing.

Effective dispatch capacity can be zero. The replica owner can pause queued
dispatch with `setReadyConcurrency(lease, 0)` without revoking already acquired
leases. Returning to positive capacity wakes the scheduler. This is a prerequisite
for preserving accepted local queues during a physical allocation transition.
The logical actuator uses it while changing physical allocation.

For a logical outcome, `prepare` returns `NEGOTIATED` without changing the current
physical allocation. `activate(sessionId, epoch)` retires old routing, pauses queued
dispatch and physical admission, reduces obsolete replicas through the provider's
positive-drain path, then grows the new allocation within the same memory budget.
It obtains fresh capabilities for changed pools and reopens them while queued
dispatch remains paused. It then observes actual ready capacity, installs local
and inbound grants, and reopens queued dispatch. No old queued request is rerouted
or evicted by this operation. Retained executing slots are never declared free.
A failed reduction retains occupied memory and prevents conflicting growth.

A local request already dispatched just before queued dispatch is paused may
reach the function proxy during quiescence. The proxy keeps that admitted HTTP
request within its existing in-flight bound and waits for the current allocation
to reopen, including a temporary zero-replica pool. It then acquires a slot from
the new pool and invokes once. The configured per-hop timeout still bounds this
wait; expiry produces a 504 without starting a handler. If the new pool is
smaller, accepted transition waiters keep that original deadline and wait for
positive physical release. Ordinary overload outside a transition still produces
503. Waiting never releases an occupied physical slot.

Logical READY/ACK exchanges use a separate v2 topic and verify session, epoch,
round count, canonical labels, function generation and node incarnation. The
seller installs inbound readiness before acknowledging it; final outbound
readiness updates preserve unchanged quotas within the same epoch. Repeating
activation for the current staged epoch returns the cached result and performs
no additional allocation. Superseded epochs cannot be activated again. UTC in
activation results is diagnostic; logical expiry uses monotonic time.

The ordinary inter-epoch burst policy has not been changed by this step. Broader
burst-policy qualification and NanoLab/native integration remain tracked
in the remediation plan. Provider-boundary tests run the real local provider and
physical slot ledger with a simulated container adapter; they are not VM campaign
measurements or native-image qualification.

### Automatic logical session initiation

After collecting at least 20 complete qualified manual timing samples, set
`scheduled: true` and call `POST .../sessions/{sessionId}/start` with
`firstEpoch` and `activationDelay`. The initial epoch must exceed the existing
preparation fence; epoch zero is valid only with a fresh fence. Identical start
requests are idempotent. Configuration changes require disabling the automatic
session first. A process/component restart requires explicit rearming.

The existing timer uses monotonic deadlines to initiate negotiation and activate
the negotiated allocation. `nanofaas.oneshot.epochs.v2` carries bounded
`PROPOSE`/`STATUS_REQUEST`/`STATUS` messages. A valid proposal starts and forwards
one preparation per epoch, with bounded retries; it does not terminate the
auction. Only current neighbors with matching session, incarnation and canonical
labels can trigger it. A peer behind multiple epochs confirms the sender's status
and must obtain positive physical drain before joining; an unavailable drain is
a failed transition, not evidence of released capacity. Accepted local queues
retain their destination. No UTC-derived skip or global convergence detector is
introduced.

Tests cover propagation on a line and two triangles, duplicate/lost messages,
monotonic wrap, delayed peers, mixed/stale control identities, and explicit HTTP
arming after timing qualification. These in-process tests do not replace native
multi-node qualification or the suspended scientific campaign.

### Observation readiness diagnostics

A logical allocation with incomplete handshake confirmations remains usable if
its safe local plan was installed while ownership and generation were valid.
Confirmation expiry returns `DEGRADED`; it does not authorize any unconfirmed
outbound grant. `diagnostics` exposes the local handshake perspective, including
partial capacity, timeouts, refusals and lost responses. These are observations,
not evidence of mathematical convergence or of remote packet non-delivery.
The eight fixed rounds and the allocation/routing mathematics are unchanged.
