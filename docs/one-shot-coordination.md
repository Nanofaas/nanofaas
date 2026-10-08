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

CONVERGED means unchanged decisions after all closures in the local participating
neighborhood. It does not assert convergence of a separate node that did not
participate. ROUND_LIMIT, DEADLINE and FAILED are censored operational outcomes,
never fast completed-auction timing samples. Missing closures cannot produce
CONVERGED. Record exact solver durations separately from the whole wall-operational
auction, including waiting, serialization and exchange. Grants remain provisional;
readiness and exclusive replica ownership are required before routing.

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
admission from fresh ready counts. A zero-ready plan provides no local capacity,
independently of the ordinary admission state's legacy minimum of one.

The actuator reduces old replicas only after physical drain, and keeps capacity
reserved by still-valid commitments. It accounts existing replicas before adding
new ones, so adjacent windows cannot oversubscribe memory during preparation.
Desired replicas never constitute readiness. READY subsets may only reduce
provisional assignments; the buyer installs a grant after the seller acknowledges
READY_ACK. Lost replies retain seller reservations until window expiry and drain.
The immutable routing plan activates at the agreed UTC start, provided clock,
incarnation and generations remain valid. Unready capacity becomes cloud residual.

### Automatic schedule grid

The first automatic epoch claim freezes `(anchor, period)` for the lifetime of the process, including failed preparations. Later changes return HTTP 409 (`ONE_SHOT_CONFLICT`) without consuming the configuration revision. Disabling scheduling, draining ownership, or stopping and starting the lifecycle does not reset this fence. Other settings may change when their existing eligibility and timing qualification checks pass.

To change the grid, disable scheduling and drain commitments and physical executions on all peers, then perform a coordinated restart with the same new anchor and period and collect 20 complete timing samples again before enabling automatic scheduling. Before the first automatic claim, grid changes remain possible only when every scheduled configuration selects an epoch above the coordinator’s last prepared manual epoch; this includes scheduled-to-scheduled updates. Stale candidates return 409 without consuming a revision. Enabling scheduling must also wait for any active manual preparation.

Each automatic claim registers its cancellation ownership before input pinning. Stopping the lifecycle cancels that claim even when subscription initialization has not finished; a later start cannot resume the cancelled preparation. Pinned input and the busy flag remain owned until cleanup completes.

Automatic epoch windows retain the full `Duration` precision, including fractional milliseconds and fractional anchors. Both endpoints derive from the same exact period, so adjacent windows share an endpoint without overlap or gaps. A window whose start is already reached is not prepared retroactively. Arithmetic or `Instant` range overflow raises an explicit configuration/calculation error rather than wrapping the epoch index.
