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
