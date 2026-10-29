package it.unimib.datai.nanofaas.modules.offload.oneshot.routing;
import it.unimib.datai.nanofaas.modules.offload.oneshot.actuation.ActiveRoutingPlan;
import it.unimib.datai.nanofaas.modules.offload.oneshot.actuation.AllocationTransition;
import it.unimib.datai.nanofaas.modules.offload.oneshot.api.*;
import it.unimib.datai.nanofaas.modules.offload.oneshot.auction.Assignment;
import it.unimib.datai.nanofaas.controlplane.offload.*;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.p2papi.PeerTransport;
import java.util.*;
import java.util.function.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.MapperFeature;
/** One immutable plan; original requests draw a destination, neighbors use local admission. */
public final class PlanRouter {
    public static final class WeightedPicker {
        private final LinkedHashMap<String, Double> weights = new LinkedHashMap<>();
        public WeightedPicker(Map<String, Double> input) {
            double total = input.values().stream().mapToDouble(Double::doubleValue).sum();
            if (!Double.isFinite(total) || total <= 0) throw new IllegalArgumentException("invalid total weight");
            input.forEach((id, weight) -> {
                if (!Double.isFinite(weight) || weight < 0) throw new IllegalArgumentException("invalid weight");
                if (weight > 0) weights.put(id, weight / total);
            });
        }
        public String next(double draw) {
            if (!Double.isFinite(draw) || draw < 0 || draw >= 1) throw new IllegalArgumentException("draw outside [0,1)");
            double cumulative = 0;
            for (var entry : weights.entrySet()) {
                cumulative += entry.getValue();
                if (draw < cumulative) return entry.getKey();
            }
            // Floating point sums can end just below one.
            return weights.lastEntry().getKey();
        }
    }
    private static final class State {
        final ActiveRoutingPlan plan;
        final Map<String, WeightedPicker> picks = new HashMap<>();
        State(ActiveRoutingPlan plan) {
            this.plan = plan;
            long count = plan.functions().values().stream().mapToLong(f -> f.inbound().size() + f.outbound().size()).sum();
            if (count > 4096) throw new IllegalArgumentException("assignment bound exceeded");
            plan.functions().forEach((name, f) -> {
                var weights = new LinkedHashMap<String, Double>();
                weights.put("local", f.localRate());
                for (var a : f.outbound().stream().sorted(Comparator.comparing(Assignment::id)).toList()) {
                    weights.put(a.id(), a.quantity() * plan.flowQuantum());
                }
                weights.put("cloud", f.cloudRate());
                if (f.forecastRate() > 0) picks.put(name, new WeightedPicker(weights));
            });
        }
    }
    private final Supplier<Optional<ActiveRoutingPlan>> plans;private final Supplier<Optional<OneShotSettings>> current;private final LongFunction<Optional<OneShotSettings>> configurations;
    private final PeerTransport peers;private final DoubleSupplier random;
    private final Function<String,Optional<AllocationTransition>> transitionQueueNode;
    private final JsonMapper mapper=JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build();
    private State state;
    public PlanRouter(Supplier<Optional<ActiveRoutingPlan>> plans,Supplier<Optional<OneShotSettings>> current,LongFunction<Optional<OneShotSettings>> configurations,PeerTransport peers,DoubleSupplier random) {
        this(plans,current,configurations,peers,random,name->Optional.empty());
    }
    public PlanRouter(Supplier<Optional<ActiveRoutingPlan>> plans,Supplier<Optional<OneShotSettings>> current,LongFunction<Optional<OneShotSettings>> configurations,PeerTransport peers,DoubleSupplier random,Function<String,Optional<AllocationTransition>> transitionQueueNode) {
        this.plans=plans;this.current=current;this.configurations=configurations;this.peers=peers;this.random=Objects.requireNonNull(random);
        this.transitionQueueNode=transitionQueueNode;
    }
    public boolean manages(String name) { return current.get().map(s->s.functions().containsKey(name)).orElse(false) || plans.get().map(p->p.functions().containsKey(name)).orElse(false); }
    public synchronized PlannedInvocationRoute route(InvocationTask task,OffloadContext context) {
        if(context.invalidMetadata()) return PlannedInvocationRoute.reject("invalid native metadata");
        var active=plans.get();var settings=active.flatMap(p->configurations.apply(p.revision())).or(()->current.get());
        if(settings.isEmpty() || !settings.get().functions().containsKey(task.functionName())) {
            if(context.metadata()!=null) return PlannedInvocationRoute.reject("no native assignment for function");
            return PlannedInvocationRoute.legacy();
        }
        var configured=settings.get().functions().get(task.functionName());
        if(!ServiceProfileStore.imageMatches(task.functionSpec().image(),configured.imageDigest())) return PlannedInvocationRoute.reject("workload image differs from calibration");
        if(!ServiceProfileStore.hash(mapper.writeValueAsBytes(task.request().input())).equals(configured.inputHash())) return PlannedInvocationRoute.reject("workload input differs from calibration");
        var metadata=context.metadata();
        if(context.offloadedHop() && (metadata==null || peers.activeNeighbors().stream().noneMatch(p->p.peerId().equals(metadata.origin()) && p.incarnation().equals(metadata.originIncarnation()))))
            return PlannedInvocationRoute.reject("native ready assignment required");
        var transition=transitionQueueNode.apply(task.functionName());
        if(transition.isPresent()) {
            var local=transition.get();
            if(!context.offloadedHop() || metadata.epoch()<local.epoch())
                return PlannedInvocationRoute.local(local.nodeId());
        }
        // Readiness may have been published after the initial read but before the transition check.
        if(active.isEmpty()) active=plans.get();
        if(active.isEmpty()) return context.offloadedHop()?PlannedInvocationRoute.reject("seller has no active ready plan"):cloud(settings.get(),0);
        var plan=active.get();var f=plan.functions().get(task.functionName());
        if(f==null || f.generation()!=configured.generation()) return PlannedInvocationRoute.reject("function generation differs from plan");
        // Delayed neighbor work uses current local admission, not an expired assignment quota.
        if(context.offloadedHop() && metadata.epoch()<plan.epoch()) return PlannedInvocationRoute.local(plan.nodeId());
        if(context.offloadedHop()) {
            if(metadata.epoch()!=plan.epoch()) return PlannedInvocationRoute.reject("native ready assignment required");
            var inbound=f.inbound().stream().filter(a->a.id().equals(metadata.assignment()) && a.buyerId().equals(metadata.origin()) && a.buyerIncarnation().equals(metadata.originIncarnation()) && a.readyConfirmed()).findFirst();
            if(inbound.isEmpty()) return PlannedInvocationRoute.reject("inbound assignment absent");
            return PlannedInvocationRoute.local(plan.nodeId());
        }
        if(state==null || !state.plan.equals(plan)) state=new State(plan);
        var picker=state.picks.get(task.functionName());if(picker==null) return cloud(settings.get(),plan.epoch());
        var selected=picker.next(random.getAsDouble());
        if(selected.equals("local")) return PlannedInvocationRoute.local(plan.nodeId());
        if(selected.equals("cloud")) return cloud(settings.get(),plan.epoch());
        var assignment=f.outbound().stream().filter(a->a.id().equals(selected)).findFirst().orElseThrow();
        var peer=peers.activeNeighbors().stream().filter(p->p.peerId().equals(assignment.sellerId()) && p.incarnation().equals(assignment.sellerIncarnation())).findFirst();
        if(peer.isEmpty()) return cloud(settings.get(),plan.epoch());
        return remote(peer.get().invocationUri().toString(),peer.get().peerId(),plan.epoch(),assignment.id(),plan.nodeId(),plan.incarnation());
    }
    private PlannedInvocationRoute cloud(OneShotSettings settings,long epoch) {
        var local=peers.localEndpoint().orElse(null);if(local==null) return PlannedInvocationRoute.reject("origin endpoint unavailable");
        return remote(settings.cloudUri().toString(),"cloud",epoch,"cloud",local.peerId(),local.incarnation());
    }
    private static PlannedInvocationRoute remote(String target,String node,long epoch,String assignment,String origin,String incarnation) {
        return new PlannedInvocationRoute(PlannedInvocationRoute.Kind.REMOTE,target.replaceAll("/+$", ""),node,epoch,assignment,Map.of("X-NanoFaaS-Offload-Version","1","X-NanoFaaS-Offload-Origin",origin+"@"+incarnation,"X-NanoFaaS-Offload-Epoch",Long.toString(epoch),"X-NanoFaaS-Offload-Assignment",assignment),null);
    }
}
