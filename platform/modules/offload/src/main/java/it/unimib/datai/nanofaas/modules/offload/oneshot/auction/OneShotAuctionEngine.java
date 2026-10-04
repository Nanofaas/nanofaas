package it.unimib.datai.nanofaas.modules.offload.oneshot.auction;

import it.unimib.datai.nanofaas.modules.offload.oneshot.solver.*;
import java.math.BigInteger;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;

/** Pure application transitions: no network, registry mutation or replica start occurs here. */
public final class OneShotAuctionEngine {
    public record Options(double epsilon, double eta, double zeta, double latencyWeight, double fairnessWeight, boolean unitBids) {
        public Options {
            for (double value : new double[]{epsilon, eta, zeta, latencyWeight, fairnessWeight})
                if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("finite nonnegative auction parameters required");
            if (epsilon == 0 || zeta > 1) throw new IllegalArgumentException("positive epsilon and zeta <= 1 required");
        }
        public static Options base() { return new Options(0.01, 0, 0, 0, 0, false); }
    }
    public record BidProposal(String target, AuctionMessage.Bid bid) {}
    private static final int MAX_MESSAGES = 10_000;
    private final Options options;
    private final LocalReplicaSolver solver;
    private final Supplier<SolveLimits> limits;
    public OneShotAuctionEngine(Options options) { this(options, new LocalReplicaSolver(), () -> SolveLimits.forDuration(Duration.ofSeconds(2))); }
    public OneShotAuctionEngine(Options options, LocalReplicaSolver solver, Supplier<SolveLimits> limits) {
        this.options = Objects.requireNonNull(options); this.solver = Objects.requireNonNull(solver); this.limits = Objects.requireNonNull(limits);
    }

    public List<BidProposal> defineBids(String function, String version, long generation, long wanted, double gamma, List<AuctionMessage.Offer> offers) {
        if (wanted < 0 || generation < 1 || !Double.isFinite(gamma) || gamma < 0 || offers.size() > MAX_MESSAGES)
            throw new IllegalArgumentException("invalid buyer input or offer bound");
        var compatible = offers.stream().filter(o -> valid(o) && o.function().equals(function) && o.version().equals(version)).toList();
        var eligible = compatible.stream().filter(o -> o.quantity() >= 1 && utility(o) > -gamma)
                .sorted(Comparator.comparingDouble(this::utility).reversed().thenComparing(AuctionMessage.Offer::sellerId, Comparator.reverseOrder())).toList();
        List<BidProposal> bids = new ArrayList<>();
        long assigned = 0;
        for (int i = 0; i < eligible.size() && assigned < wanted; i++) {
            var offer = eligible.get(i);
            double gap = i + 1 < eligible.size() ? utility(offer) - utility(eligible.get(i + 1)) : 0;
            double price = offer.price() + options.epsilon() + gap;
            if (!Double.isFinite(price)) throw new IllegalArgumentException("bid price overflow");
            long quantity = Math.min(offer.quantity(), wanted - assigned);
            if (options.unitBids()) {
                if (quantity > MAX_MESSAGES - bids.size()) throw new IllegalArgumentException("bid count limit");
                for (long unit = 0; unit < quantity; unit++) bids.add(new BidProposal(offer.sellerId(), new AuctionMessage.Bid(function, version, generation, 1, price, false)));
            } else bids.add(new BidProposal(offer.sellerId(), new AuctionMessage.Bid(function, version, generation, quantity, price, false)));
            assigned += quantity;
        }
        if (assigned < wanted) {
            Set<String> eligibleIds = new HashSet<>(); eligible.forEach(o -> eligibleIds.add(o.sellerId()));
            for (var offer : compatible.stream().sorted(Comparator.comparing(AuctionMessage.Offer::sellerId)).toList())
                if (offer.residualMemoryMiB() > 0 && (offer.quantity() < 1 || eligibleIds.contains(offer.sellerId())))
                    bids.add(new BidProposal(offer.sellerId(), new AuctionMessage.Bid(function, version, generation, 0, 0, true)));
        }
        if (bids.size() > MAX_MESSAGES) throw new IllegalArgumentException("bid count limit");
        return List.copyOf(bids);
    }
    private double utility(AuctionMessage.Offer offer) {
        return offer.beta() - offer.price() - options.latencyWeight() * offer.latencySeconds() - options.fairnessWeight() * offer.fairness();
    }

    public List<AuctionMessage.Offer> localOffers(AuctionSnapshot state) {
        long memory = remainingMemory(state, state.desiredReplicas());
        var result = new ArrayList<AuctionMessage.Offer>();
        for (var f : state.baseProblem().functions()) {
            var identity = state.identities().get(f.id());
            double inbound = inbound(state, f.id());
            boolean sending = f.fixedOffload() > 0 || outbound(state, f.id()) > 0;
            double spare = state.desiredReplicas().get(f.id()) * (f.utilization() / f.demandSeconds()) - f.fixedLocal() - inbound;
            long capacity = sending ? 0 : (long) Math.min(9007199254740991L, Math.max(0, Math.floor(spare)));
            result.add(new AuctionMessage.Offer(state.nodeId(), state.incarnation(), f.id(), identity.version(), identity.generation(),
                    capacity, memory, f.memoryMiB(), f.demandSeconds(), f.utilization(), state.prices().get(f.id()), 1, 0, 0));
        }
        return List.copyOf(result);
    }

    public AuctionTransition advance(AuctionSnapshot state, AuctionMessage message) {
        var e = message.envelope();
        String expectedIncarnation = e.senderId().equals(state.nodeId()) ? state.incarnation() : state.peerIncarnations().get(e.senderId());
        if (!Objects.equals(expectedIncarnation, e.incarnation()) || !message.target().equals(state.nodeId())
                || e.epoch() != state.epoch() || e.round() != state.round()
                || !e.validFrom().equals(state.validFrom()) || !e.validUntil().equals(state.validUntil())) return unchanged(state);
        String key = e.senderId() + ":" + e.incarnation() + ":" + e.messageId();
        if (state.seen().contains(key)) return unchanged(state);
        if (state.seen().size() >= MAX_MESSAGES * 4 + 1) return unchanged(state);
        Set<String> seen = new HashSet<>(state.seen()); seen.add(key);
        switch (message.kind()) {
            case OFFER -> {
                var offer = message.offer(); var identity = state.identities().get(offer.function());
                if (!valid(offer) || identity == null || !identity.version().equals(offer.version())
                        || !offer.sellerId().equals(e.senderId()) || !offer.incarnation().equals(e.incarnation()) || state.roundClosed()) return unchanged(state);
                var offers = new LinkedHashMap<>(state.offers()); offers.put(offer.sellerId() + "/" + offer.function(), offer);
                return transition(state, state.desiredReplicas(), state.prices(), offers, state.pendingBids(), state.assignments(), seen, false, state.status(), List.of());
            }
            case BID -> {
                var bid = message.bid(); var identity = state.identities().get(bid.function());
                if (state.roundClosed() || identity == null || !identity.version().equals(bid.version()) || bid.buyerGeneration() < 1
                        || bid.quantity() < 0 || bid.quantity() > 9007199254740991L || !Double.isFinite(bid.price())
                        || (!bid.memoryOnly() && bid.quantity() == 0) || (bid.memoryOnly() && bid.quantity() != 0)
                        || e.senderId().equals(state.nodeId()) || state.pendingBids().size() >= MAX_MESSAGES
                        || initialOutbound(state, bid.function()) > 0 || outbound(state, bid.function()) > 0) return unchanged(state);
                var pending = new ArrayList<>(state.pendingBids()); pending.add(message);
                return transition(state, state.desiredReplicas(), state.prices(), state.offers(), pending, state.assignments(), seen, false, state.status(), List.of());
            }
            case ROUND_CLOSE -> {
                if (!e.senderId().equals(state.nodeId()) || state.roundClosed()) return unchanged(state);
                return close(state, seen);
            }
            case GRANT, READY_CONFIRM -> {
                Assignment a = message.assignment(); var identity = state.identities().get(a.function());
                if (identity == null || !identity.version().equals(a.version()) || a.epoch() != state.epoch()
                        || !a.sellerId().equals(e.senderId()) || !a.sellerIncarnation().equals(e.incarnation())
                        || !Objects.equals(state.peerIncarnations().get(a.buyerId()), a.buyerIncarnation()) && !a.buyerId().equals(state.nodeId())) return unchanged(state);
                var assignments = new LinkedHashMap<>(state.assignments());
                Assignment prior = assignments.get(a.id());
                if (message.kind() == AuctionMessage.Kind.GRANT) {
                    if (!a.buyerId().equals(state.nodeId()) || !a.buyerIncarnation().equals(state.incarnation())
                            || a.buyerGeneration() != identity.generation() || a.readyConfirmed() || prior != null
                            || inbound(state, a.function()) > 0 || a.quantity() > initialOutbound(state, a.function()) - outbound(state, a.function())) return unchanged(state);
                } else {
                    if (prior == null || a.quantity() > prior.quantity() || !a.equals(prior.confirm(a.quantity())) || !a.readyConfirmed()) return unchanged(state);
                }
                assignments.put(a.id(), a);
                List<AuctionMessage> emit = message.kind() == AuctionMessage.Kind.READY_CONFIRM && a.sellerId().equals(state.nodeId())
                        ? List.of(new AuctionMessage(envelope(state, "ready:" + a.id()), AuctionMessage.Kind.READY_CONFIRM, a.buyerId(), null, null, a)) : List.of();
                return transition(state, state.desiredReplicas(), state.prices(), state.offers(), state.pendingBids(), assignments, seen, state.roundClosed(), state.status(), emit);
            }
        }
        return unchanged(state);
    }

    private AuctionTransition close(AuctionSnapshot state, Set<String> seen) {
        var assignments = new LinkedHashMap<>(state.assignments());
        var prices = new LinkedHashMap<>(state.prices());
        var replicas = new LinkedHashMap<>(state.desiredReplicas());
        var emits = new ArrayList<AuctionMessage>();
        Map<String, Long> capacity = new HashMap<>(); localOffers(state).forEach(o -> capacity.put(o.function(), o.quantity()));
        List<AuctionMessage> bids = state.pendingBids().stream().filter(m -> !m.bid().memoryOnly())
                .sorted(Comparator.comparingDouble((AuctionMessage m) -> m.bid().price()).reversed()
                        .thenComparing(m -> m.envelope().senderId()).thenComparing(m -> m.bid().function()).thenComparing(m -> m.envelope().messageId())).toList();
        if ((long) assignments.size() + bids.size() > MAX_MESSAGES)
            return transition(state, state.desiredReplicas(), state.prices(), state.offers(), state.pendingBids(), state.assignments(), seen,
                    true, "ASSIGNMENT_LIMIT", List.of());
        var minBid = new HashMap<String,Double>();
        for (var m : bids) minBid.merge(m.bid().function(), m.bid().price(), Math::max);
        for (var message : bids) {
            var bid = message.bid(); long quantity = Math.min(capacity.getOrDefault(bid.function(), 0L), bid.quantity());
            if (quantity == 0) continue;
            var identity = state.identities().get(bid.function()); var e = message.envelope();
            String id = state.incarnation() + ":" + state.epoch() + ":" + state.round() + ":" + e.senderId() + ":" + e.messageId();
            var assignment = new Assignment(id, e.senderId(), e.incarnation(), state.nodeId(), state.incarnation(), bid.function(),
                    bid.version(), identity.generation(), bid.buyerGeneration(), state.epoch(), quantity, false);
            assignments.put(id, assignment);
            capacity.put(bid.function(), capacity.get(bid.function()) - quantity);
            minBid.merge(bid.function(), bid.price(), Math::min);
            emits.add(new AuctionMessage(envelope(state, "grant:" + e.senderId() + ":" + e.messageId()), AuctionMessage.Kind.GRANT,
                    e.senderId(), null, null, assignment));
        }
        for (var offer : localOffers(state)) {
            if (minBid.containsKey(offer.function())) {
                var f = function(state, offer.function());
                double incoming = assignments.values().stream().filter(a -> a.sellerId().equals(state.nodeId()) && a.function().equals(f.id())).mapToDouble(Assignment::quantity).sum();
                double totalCapacity = state.desiredReplicas().get(f.id()) * (f.utilization() / f.demandSeconds());
                if (totalCapacity > 0) prices.put(f.id(), minBid.get(f.id()) + options.eta() * ((f.fixedLocal() + f.inbound() + incoming) / totalCapacity - 0.9));
            } else if (offer.quantity() > 0) prices.put(offer.function(), offer.price() * (1 - options.zeta()));
        }
        String status = "ROUND_CLOSED";
        if (!bids.isEmpty()) {
            var rows = new ArrayList<LocalProblem.Function>();
            for (var f : state.baseProblem().functions()) {
                double in = f.inbound(), out = 0;
                for (var a : assignments.values()) if (a.function().equals(f.id())) {
                    if (a.sellerId().equals(state.nodeId())) in += a.quantity();
                    if (a.buyerId().equals(state.nodeId())) out += a.quantity();
                }
                rows.add(new LocalProblem.Function(f.id(), f.load(), f.demandSeconds(), f.utilization(), f.memoryMiB(),
                        f.alpha(), f.delta(), f.gamma(), 0, f.fixedLocal(), out, in));
            }
            var fixed = solver.solve(new LocalProblem(LocalProblem.Model.LSPr_x, state.baseProblem().memoryCapacityMiB(), rows), limits.get());
            if (fixed.status() != LocalSolution.Status.OPTIMAL) {
                // A failed recomputation cannot create observable new seller promises.
                return transition(state, state.desiredReplicas(), state.prices(), state.offers(), state.pendingBids(), state.assignments(), seen, true,
                        "SOLVER_" + fixed.status(), List.of());
            }
            int[] values = fixed.replicas(); for (int i = 0; i < rows.size(); i++) replicas.put(rows.get(i).id(), values[i]);
        } else memoryReplicas(state, replicas);
        return transition(state, replicas, prices, state.offers(), state.pendingBids(), assignments, seen, true, status, emits);
    }

    /** Restrict the final replica plan to fixed local and negotiated flows, stripping unused proposals. */
    public AuctionTransition finalizeAuction(AuctionSnapshot state) {
        var rows = new ArrayList<LocalProblem.Function>();
        for (var f : state.baseProblem().functions()) rows.add(new LocalProblem.Function(f.id(), f.load(), f.demandSeconds(), f.utilization(),
                f.memoryMiB(), f.alpha(), f.delta(), f.gamma(), 0, f.fixedLocal(), outbound(state, f.id()), inbound(state, f.id())));
        var result = solver.solve(new LocalProblem(LocalProblem.Model.LSPr_x, state.baseProblem().memoryCapacityMiB(), rows), limits.get());
        if (result.status() != LocalSolution.Status.OPTIMAL)
            return transition(state, state.desiredReplicas(), state.prices(), state.offers(), state.pendingBids(), state.assignments(), state.seen(),
                    true, "SOLVER_" + result.status(), List.of());
        var replicas = new LinkedHashMap<String,Integer>();
        int[] values = result.replicas(); for (int i = 0; i < rows.size(); i++) replicas.put(rows.get(i).id(), values[i]);
        return transition(state, replicas, state.prices(), state.offers(), state.pendingBids(), state.assignments(), state.seen(), true, "FINALIZED", List.of());
    }

    private void memoryReplicas(AuctionSnapshot state, Map<String,Integer> replicas) {
        var counts = new TreeMap<String,Long>();
        state.pendingBids().stream().filter(m -> m.bid().memoryOnly()).forEach(m -> counts.merge(m.bid().function(), 1L, Long::sum));
        if (counts.isEmpty()) return;
        long available = remainingMemory(state, replicas), remaining = available;
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        Map<String,Long> allocated = new TreeMap<>();
        for (var e : counts.entrySet()) {
            long memory = function(state, e.getKey()).memoryMiB();
            long extra = BigInteger.valueOf(available).multiply(BigInteger.valueOf(e.getValue()))
                    .divide(BigInteger.valueOf(total).multiply(BigInteger.valueOf(memory))).longValueExact();
            if (extra > Integer.MAX_VALUE - replicas.get(e.getKey())) throw new IllegalArgumentException("replica count limit");
            replicas.merge(e.getKey(), (int) extra, Integer::sum);
            allocated.put(e.getKey(), extra * memory); remaining -= extra * memory;
        }
        // At most one residual replica per function: after it, the deficit is nonpositive.
        while (remaining > 0) {
            String selected = null; BigInteger best = BigInteger.ZERO;
            for (var e : counts.entrySet()) {
                long memory = function(state, e.getKey()).memoryMiB();
                BigInteger deficit = BigInteger.valueOf(available).multiply(BigInteger.valueOf(e.getValue()))
                        .subtract(BigInteger.valueOf(allocated.get(e.getKey())).multiply(BigInteger.valueOf(total)));
                if (memory <= remaining && deficit.signum() > 0 && (selected == null || deficit.compareTo(best) > 0
                        || deficit.equals(best) && e.getValue() > counts.get(selected))) { selected = e.getKey(); best = deficit; }
            }
            if (selected == null) break;
            long memory = function(state, selected).memoryMiB();
            replicas.put(selected, Math.addExact(replicas.get(selected), 1)); allocated.merge(selected, memory, Math::addExact); remaining -= memory;
        }
    }
    private static AuctionMessage.Envelope envelope(AuctionSnapshot s, String id) {
        return new AuctionMessage.Envelope(1, s.nodeId(), s.incarnation(), s.epoch(), s.round(), id, s.revision(), s.validFrom(), s.validUntil());
    }
    private static LocalProblem.Function function(AuctionSnapshot s, String id) {
        return s.baseProblem().functions().stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
    }
    private static double initialOutbound(AuctionSnapshot s, String f) { return function(s, f).fixedOffload(); }
    private static long outbound(AuctionSnapshot s, String f) {
        return s.assignments().values().stream().filter(a -> a.buyerId().equals(s.nodeId()) && a.function().equals(f)).mapToLong(Assignment::quantity).sum();
    }
    private static double inbound(AuctionSnapshot s, String f) {
        return function(s, f).inbound() + s.assignments().values().stream().filter(a -> a.sellerId().equals(s.nodeId()) && a.function().equals(f)).mapToLong(Assignment::quantity).sum();
    }
    private static long remainingMemory(AuctionSnapshot s, Map<String,Integer> replicas) {
        long remaining = s.baseProblem().memoryCapacityMiB();
        for (var f : s.baseProblem().functions()) {
            int count = replicas.get(f.id());
            if (count < 0 || count > remaining / f.memoryMiB()) throw new IllegalArgumentException("memory overcommit");
            remaining -= count * f.memoryMiB();
        }
        return remaining;
    }
    private static boolean valid(AuctionMessage.Offer o) {
        return o.sellerId() != null && o.incarnation() != null && o.function() != null && o.version() != null
                && o.generation() > 0 && o.quantity() >= 0 && o.quantity() <= 9007199254740991L && o.memoryMiB() > 0 && o.residualMemoryMiB() >= 0
                && Double.isFinite(o.demandSeconds()) && o.demandSeconds() > 0 && Double.isFinite(o.utilization()) && o.utilization() > 0
                && Double.isFinite(o.price()) && Double.isFinite(o.beta()) && Double.isFinite(o.latencySeconds()) && o.latencySeconds() >= 0
                && Double.isFinite(o.fairness()) && o.fairness() >= 0;
    }
    private static AuctionTransition unchanged(AuctionSnapshot s) { return new AuctionTransition(s, List.of()); }
    private static AuctionTransition transition(AuctionSnapshot s, Map<String,Integer> replicas, Map<String,Double> prices,
            Map<String,AuctionMessage.Offer> offers, List<AuctionMessage> bids, Map<String,Assignment> assignments, Set<String> seen,
            boolean closed, String status, List<AuctionMessage> emit) {
        return new AuctionTransition(new AuctionSnapshot(s.nodeId(), s.incarnation(), s.epoch(), s.round(), s.revision(), s.validFrom(), s.validUntil(),
                s.baseProblem(), s.identities(), s.peerIncarnations(), replicas, prices, offers, bids, assignments, seen, closed, status), emit);
    }
}
