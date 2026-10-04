package it.unimib.datai.nanofaas.modules.offload.oneshot.api;
import it.unimib.datai.nanofaas.controlplane.registry.*;
import it.unimib.datai.nanofaas.forecastingapi.*;
import it.unimib.datai.nanofaas.p2papi.*;
import it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.*;
import it.unimib.datai.nanofaas.modules.offload.oneshot.auction.*;
import it.unimib.datai.nanofaas.modules.offload.oneshot.solver.*;
import java.time.*;
import java.util.*;
/** Freezes one profile revision, one configuration and compatible generation-scoped forecasts. */
public final class ProfileEpochInputFactory implements EpochInput.Factory {
    private record Frozen(long epoch,Instant from,Instant until,EpochInput input) {}
    private final java.util.concurrent.atomic.AtomicReference<Frozen> pinned=new java.util.concurrent.atomic.AtomicReference<>();
    public EpochInput pin(long epoch,Instant from,Instant until,OneShotConfigurationStore.Snapshot snapshot) {
        if(!Duration.between(from,until).equals(snapshot.settings().period())) throw new IllegalArgumentException("epoch period mismatch");
        var input=freeze(snapshot.settings(),snapshot.revision(),from,until,true);
        if(!pinned.compareAndSet(null,new Frozen(epoch,from,until,input))) throw new IllegalStateException("input already pinned");
        return input;
    }
    public void unpin(long epoch) { var f=pinned.get();if(f!=null && f.epoch()==epoch) pinned.compareAndSet(f,null); }
    private final OneShotConfigurationStore configs;private final ServiceProfileStore profiles;
    private final FunctionCatalogView catalog;private final ManagedReplicaControl control;
    private final PeerTransport peers;private final ForecastSource forecasts;
    public ProfileEpochInputFactory(OneShotConfigurationStore configs,ServiceProfileStore profiles,FunctionCatalogView catalog,ManagedReplicaControl control,PeerTransport peers,ForecastSource forecasts) {
        this.configs=configs;this.profiles=profiles;this.catalog=catalog;this.control=control;this.peers=peers;this.forecasts=forecasts;
    }
    public void validate(OneShotSettings settings) { freeze(settings,1,Instant.EPOCH,Instant.EPOCH.plus(settings.period()),false); }
    @Override public EpochInput freeze(long epoch,Instant from,Instant until) {
        var pin=pinned.get();
        if(pin!=null) { if(pin.epoch()!=epoch || !pin.from().equals(from) || !pin.until().equals(until)) throw new IllegalStateException("pinned window mismatch");return pin.input(); }
        var frozen=configs.snapshot().orElseThrow(()->new IllegalStateException("one-shot configuration missing"));
        if(!Duration.between(from,until).equals(frozen.settings().period())) throw new IllegalArgumentException("epoch period differs from configuration");
        return freeze(frozen.settings(),frozen.revision(),from,until,true);
    }
    private EpochInput freeze(OneShotSettings settings,long revision,Instant from,Instant until,boolean getForecasts) {
        var evidence=profiles.compatible(settings);var profile=evidence.profile();
        var registered=new HashMap<String,RegisteredFunction>();catalog.listRegistered().forEach(f->registered.put(f.name(),f));
        var functions=new ArrayList<LocalProblem.Function>();var ids=new LinkedHashMap<String,AuctionSnapshot.FunctionIdentity>();var snapshots=new LinkedHashMap<String,ForecastSnapshot>();
        var node=getForecasts?peers.localEndpoint().orElseThrow(()->new IllegalStateException("local P2P endpoint missing")).peerId():"validation";
        Long oracleRevision=null;var units=new FlowUnits(settings.flowQuantum());
        for(var measured:profile.functions().stream().sorted(Comparator.comparing(ServiceProfileStore.Function::function)).toList()) {
            var name=measured.function();var configured=settings.functions().get(name);var f=registered.get(name);
            if(f==null) throw new IllegalArgumentException("function missing: "+name);
            var spec=f.spec();var generation=control.generationOf(f);var target=f.managedDeploymentTarget().orElseThrow(()->new IllegalArgumentException("managed backend required"));
            var limits=spec.resources()==null?null:spec.resources().limits();var cc=spec.scalingConfig()==null?null:spec.scalingConfig().concurrencyControl();
            long max=settings.memoryCapacityMiB()/measured.memoryMiB();
            var colocated=new HashSet<>(settings.functions().keySet());colocated.remove(name);
            if(generation==null || generation.id()!=configured.generation() || !measured.imageDigest().equals(configured.imageDigest()) || !spec.image().endsWith("@"+configured.imageDigest())
                || !measured.inputHash().equals(configured.inputHash()) || !measured.backend().equals(target.backendId()) || spec.runtimeMode()==null || !measured.runtime().equalsIgnoreCase(spec.runtimeMode().name())
                || limits==null || limits.memoryMiB()==null || limits.memoryMiB().longValue()!=measured.memoryMiB() || limits.cpu()==null || Double.compare(limits.cpu().doubleValue(),measured.cpuQuota())!=0
                || !new HashSet<>(measured.coLocation()).equals(colocated) || measured.validity().minReplicas()>1 || measured.validity().maxReplicas()<max || max<1
                || spec.concurrency()==null || spec.concurrency()<max || cc==null || cc.mode()!=it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode.STATIC_PER_POD || !Integer.valueOf(1).equals(cc.targetInFlightPerPod())
                || spec.env()==null || !"1".equals(spec.env().get("NANOFAAS_MAX_CONCURRENT_HANDLERS")) || !"true".equals(spec.env().get("NANOFAAS_ONE_SHOT_PROFILE")) || !control.supportsPhysicalReplicaControl(target))
                throw new IllegalArgumentException("calibration, resources or replica range incompatible: "+name);
            ForecastSnapshot forecast=null;double rate=0;
            if(getForecasts) {
                var query=new ForecastQuery(node,name,generation.id(),from,until);forecast=forecasts.forecast(query);
                if(forecast==null || forecast.status()!=ForecastSnapshot.Status.AVAILABLE || !query.equals(forecast.query()) || forecast.rate()==null) throw new IllegalArgumentException("forecast missing/stale: "+name);
                rate=forecast.rate();
                if("oracle".equals(forecast.provider())) { if(oracleRevision!=null && oracleRevision!=forecast.revision()) throw new IllegalArgumentException("oracle revision changed during freeze");oracleRevision=forecast.revision(); }
                snapshots.put(name,forecast);
            }
            var quantity=units.convert(rate,measured.serviceSeconds(),forecast!=null && "oracle".equals(forecast.provider()));
            functions.add(new LocalProblem.Function(name,quantity.units(),quantity.solverDemandSeconds(),configured.utilization(),measured.memoryMiB(),configured.alpha(),configured.delta(),configured.gamma(),0,0,0,0));
            ids.put(name,new AuctionSnapshot.FunctionIdentity(configured.imageDigest(),generation.id()));
        }
        return new EpochInput(new LocalProblem(LocalProblem.Model.LSP,settings.memoryCapacityMiB(),functions),ids,snapshots,revision,settings.flowQuantum(),settings.negotiation(),settings.maxSolverStates(),settings.maxSolverBytes(),evidence.contentHash());
    }
}
