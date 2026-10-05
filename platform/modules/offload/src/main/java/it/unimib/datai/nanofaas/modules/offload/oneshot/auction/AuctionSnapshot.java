package it.unimib.datai.nanofaas.modules.offload.oneshot.auction;
import it.unimib.datai.nanofaas.modules.offload.oneshot.solver.*;
import java.time.Instant;
import java.util.*;
public record AuctionSnapshot(String nodeId, String incarnation, long epoch, int round, long revision,
        Instant validFrom, Instant validUntil, LocalProblem baseProblem, Map<String, FunctionIdentity> identities,
        Map<String, String> peerIncarnations, Map<String, Integer> desiredReplicas, Map<String, Double> prices,
        Map<String, AuctionMessage.Offer> offers, List<AuctionMessage> pendingBids,
        Map<String, Assignment> assignments, Set<String> seen, boolean roundClosed, String status) {
    public record FunctionIdentity(String version, long generation) {}
    public AuctionSnapshot {
        identities = immutable(identities); peerIncarnations = immutable(peerIncarnations);
        desiredReplicas = immutable(desiredReplicas); prices = immutable(prices); offers = immutable(offers);
        assignments = immutable(assignments); pendingBids = List.copyOf(pendingBids); seen = Set.copyOf(seen);
    }
    private static <K,V> Map<K,V> immutable(Map<K,V> map) { return Collections.unmodifiableMap(new LinkedHashMap<>(map)); }
    public static AuctionSnapshot open(String node, String incarnation, long epoch, int round, long revision,
            Instant from, Instant until, LocalProblem problem, LocalSolution initial,
            Map<String, FunctionIdentity> identities, Map<String, String> peers) {
        if (initial.status() != LocalSolution.Status.OPTIMAL) throw new IllegalArgumentException("optimal initial local plan required");
        var functions = new ArrayList<LocalProblem.Function>();
        var replicas = new LinkedHashMap<String,Integer>(); var prices = new LinkedHashMap<String,Double>();
        double[] x = initial.local(), outbound = initial.offload(); int[] r = initial.replicas();
        if (x.length != problem.functions().size() || r.length != x.length) throw new IllegalArgumentException("invalid initial plan dimensions");
        long remaining = problem.memoryCapacityMiB();
        for (int i = 0; i < x.length; i++) {
            var f = problem.functions().get(i); var identity = identities.get(f.id());
            if (identity == null || identity.generation() < 1 || identity.version() == null || identity.version().isBlank()
                    || r[i] < 0 || r[i] > remaining / f.memoryMiB()) throw new IllegalArgumentException("initial memory or identity invalid");
            remaining -= r[i] * f.memoryMiB();
            functions.add(new LocalProblem.Function(f.id(), f.load(), f.demandSeconds(), f.utilization(), f.memoryMiB(),
                    f.alpha(), f.delta(), f.gamma(), f.price(), x[i], outbound[i], f.inbound()));
            replicas.put(f.id(), r[i]); prices.put(f.id(), 0.0);
        }
        return new AuctionSnapshot(node, incarnation, epoch, round, revision, from, until,
                new LocalProblem(LocalProblem.Model.LSP, problem.memoryCapacityMiB(), functions), identities, peers,
                replicas, prices, Map.of(), List.of(), Map.of(), Set.of(), false, "NEGOTIATING");
    }
    public AuctionSnapshot nextRound() {
        if (!roundClosed) throw new IllegalStateException("close the current round first");
        return new AuctionSnapshot(nodeId, incarnation, epoch, round + 1, revision, validFrom, validUntil, baseProblem,
                identities, peerIncarnations, desiredReplicas, prices, Map.of(), List.of(), assignments, Set.of(), false, "NEGOTIATING");
    }
}
