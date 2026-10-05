package it.unimib.datai.nanofaas.modules.offload.oneshot.routing;
import it.unimib.datai.nanofaas.modules.offload.oneshot.actuation.ActiveRoutingPlan;
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
/** One active immutable plan, deterministic shares and independent seller-authoritative quotas. */
public final class PlanRouter {
    public static final class WeightedPicker {
        private final LinkedHashMap<String,Double> weights=new LinkedHashMap<>(),scores=new LinkedHashMap<>();
        public WeightedPicker(Map<String,Double> input) {
            double total=input.values().stream().mapToDouble(Double::doubleValue).sum();
            if(!Double.isFinite(total) || total<=0) throw new IllegalArgumentException("invalid total weight");
            input.forEach((id,w)->{if(!Double.isFinite(w) || w<0) throw new IllegalArgumentException("invalid weight");if(w>0) {weights.put(id,w/total);scores.put(id,0.0);}});
        }
        public synchronized String next() {
            String best=null;double maximum=Double.NEGATIVE_INFINITY;
            for(var e:weights.entrySet()) {double value=scores.merge(e.getKey(),e.getValue(),Double::sum);if(value>maximum) {maximum=value;best=e.getKey();}}
            scores.compute(best,(k,v)->v-1);return best;
        }
    }
    private static final class State {
        final ActiveRoutingPlan plan;final Map<String,WeightedPicker> picks=new HashMap<>();final Map<String,AssignmentAdmission> quotas=new HashMap<>();
        State(ActiveRoutingPlan plan,int burst,LongSupplier time) {
            this.plan=plan;long count=plan.functions().values().stream().mapToLong(f->f.inbound().size()+f.outbound().size()).sum();if(count>4096) throw new IllegalArgumentException("assignment bound exceeded");
            plan.functions().forEach((name,f)-> {
                var weights=new LinkedHashMap<String,Double>();weights.put("local",f.localRate());
                quotas.put("local:"+name,new AssignmentAdmission(f.localRate(),burst,time));
                for(var a:f.outbound().stream().sorted(Comparator.comparing(Assignment::id)).toList()) {weights.put(a.id(),a.quantity()*plan.flowQuantum());quotas.put("out:"+a.id(),new AssignmentAdmission(a.quantity()*plan.flowQuantum(),burst,time));}
                for(var a:f.inbound()) quotas.put("in:"+a.id(),new AssignmentAdmission(a.quantity()*plan.flowQuantum(),burst,time));
                weights.put("cloud",f.cloudRate());if(f.forecastRate()>0) picks.put(name,new WeightedPicker(weights));
            });
        }
    }
    private final Supplier<Optional<ActiveRoutingPlan>> plans;private final Supplier<Optional<OneShotSettings>> current;private final LongFunction<Optional<OneShotSettings>> configurations;
    private final PeerTransport peers;private final LongSupplier nanoTime;
    private final JsonMapper mapper=JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build();
    private State state;
    public PlanRouter(Supplier<Optional<ActiveRoutingPlan>> plans,Supplier<Optional<OneShotSettings>> current,LongFunction<Optional<OneShotSettings>> configurations,PeerTransport peers,LongSupplier time) {
        this.plans=plans;this.current=current;this.configurations=configurations;this.peers=peers;nanoTime=time;
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
        if(active.isEmpty()) return context.offloadedHop()?PlannedInvocationRoute.reject("seller has no active ready plan"):cloud(settings.get(),task,0);
        var plan=active.get();var f=plan.functions().get(task.functionName());
        if(f==null || f.generation()!=configured.generation()) return PlannedInvocationRoute.reject("function generation differs from plan");
        if(state==null || !state.plan.equals(plan)) state=new State(plan,settings.get().burst(),nanoTime);
        if(context.offloadedHop()) {
            var metadata=context.metadata();if(metadata==null || metadata.epoch()!=plan.epoch() || peers.activeNeighbors().stream().noneMatch(p->p.peerId().equals(metadata.origin()) && p.incarnation().equals(metadata.originIncarnation()))) return PlannedInvocationRoute.reject("native ready assignment required");
            var inbound=f.inbound().stream().filter(a->a.id().equals(metadata.assignment()) && a.buyerId().equals(metadata.origin()) && a.buyerIncarnation().equals(metadata.originIncarnation()) && a.readyConfirmed()).findFirst();
            if(inbound.isEmpty() || !state.quotas.get("in:"+inbound.get().id()).tryAdmit()) return PlannedInvocationRoute.reject("inbound assignment absent or quota exhausted");
            return PlannedInvocationRoute.local(plan.nodeId());
        }
        var picker=state.picks.get(task.functionName());if(picker==null) return cloud(settings.get(),task,plan.epoch());
        var selected=picker.next();
        if(selected.equals("local")) return state.quotas.get("local:"+task.functionName()).tryAdmit()?PlannedInvocationRoute.local(plan.nodeId()):cloud(settings.get(),task,plan.epoch());
        if(selected.equals("cloud")) return cloud(settings.get(),task,plan.epoch());
        var assignment=f.outbound().stream().filter(a->a.id().equals(selected)).findFirst().orElseThrow();
        var peer=peers.activeNeighbors().stream().filter(p->p.peerId().equals(assignment.sellerId()) && p.incarnation().equals(assignment.sellerIncarnation())).findFirst();
        if(peer.isEmpty() || !state.quotas.get("out:"+assignment.id()).tryAdmit()) return cloud(settings.get(),task,plan.epoch());
        return remote(peer.get().invocationUri().toString(),peer.get().peerId(),plan.epoch(),assignment.id(),plan.nodeId(),plan.incarnation());
    }
    private PlannedInvocationRoute cloud(OneShotSettings settings,InvocationTask task,long epoch) {
        var local=peers.localEndpoint().orElse(null);if(local==null) return PlannedInvocationRoute.reject("origin endpoint unavailable");
        return remote(settings.cloudUri().toString(),"cloud",epoch,"cloud",local.peerId(),local.incarnation());
    }
    private static PlannedInvocationRoute remote(String target,String node,long epoch,String assignment,String origin,String incarnation) {
        return new PlannedInvocationRoute(PlannedInvocationRoute.Kind.REMOTE,target.replaceAll("/+$", ""),node,epoch,assignment,Map.of("X-NanoFaaS-Offload-Version","1","X-NanoFaaS-Offload-Origin",origin+"@"+incarnation,"X-NanoFaaS-Offload-Epoch",Long.toString(epoch),"X-NanoFaaS-Offload-Assignment",assignment),null);
    }
}
