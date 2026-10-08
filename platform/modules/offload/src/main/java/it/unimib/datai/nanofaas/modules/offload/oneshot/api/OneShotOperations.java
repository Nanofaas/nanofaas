package it.unimib.datai.nanofaas.modules.offload.oneshot.api;
import it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.*;
import it.unimib.datai.nanofaas.modules.offload.oneshot.actuation.*;
import it.unimib.datai.nanofaas.p2papi.PeerTransport;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.SmartLifecycle;
import reactor.core.publisher.Mono;
import reactor.core.Disposable;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
/** Bounded manual/scheduled orchestration; the scheduled gate needs measured complete durations. */
public final class OneShotOperations implements SmartLifecycle,AutoCloseable {
    public record Window(Instant startsAt,Instant endsAt) {}
    private final OneShotConfigurationStore configs;private final ServiceProfileStore profiles;private final ProfileEpochInputFactory inputs;
    private final EpochCoordinator coordinator;private final ReplicaPlanActuator actuator;private final EpochEventStore events;private final PeerTransport peers;private final MeterRegistry meters;
    private final boolean enabled;
    private final Clock clock;
    private record Grid(Instant anchor,Duration period) {}
    private Grid claimedGrid;
    private final AtomicBoolean busy=new AtomicBoolean();private final AtomicLong lastScheduled=new AtomicLong(-1);
    private final Map<Long,OneShotSettings> preparedSettings=new ConcurrentHashMap<>();
    private final ArrayDeque<Long> qualified=new ArrayDeque<>();private String qualificationKey="";
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"one-shot-epochs");t.setDaemon(true);return t;});
    private volatile ScheduledFuture<?> tickTask;
    private volatile boolean running;private volatile String lastState="UNCONFIGURED";private volatile Disposable subscription;
    public OneShotOperations(OneShotConfigurationStore configs,ServiceProfileStore profiles,ProfileEpochInputFactory inputs,EpochCoordinator coordinator,ReplicaPlanActuator actuator,EpochEventStore events,PeerTransport peers,MeterRegistry meters) {
        this(configs,profiles,inputs,coordinator,actuator,events,peers,meters,true);
    }
    public OneShotOperations(OneShotConfigurationStore configs,ServiceProfileStore profiles,ProfileEpochInputFactory inputs,EpochCoordinator coordinator,ReplicaPlanActuator actuator,EpochEventStore events,PeerTransport peers,MeterRegistry meters,boolean enabled) {
        this(configs,profiles,inputs,coordinator,actuator,events,peers,meters,enabled,Clock.systemUTC());
    }
    OneShotOperations(OneShotConfigurationStore configs,ServiceProfileStore profiles,ProfileEpochInputFactory inputs,EpochCoordinator coordinator,ReplicaPlanActuator actuator,EpochEventStore events,PeerTransport peers,MeterRegistry meters,boolean enabled,Clock clock) {
        this.clock=Objects.requireNonNull(clock);
        this.enabled=enabled;
        this.configs=configs;this.profiles=profiles;this.inputs=inputs;this.coordinator=coordinator;this.actuator=actuator;this.events=events;this.peers=peers;this.meters=meters;
    }
    public synchronized OneShotConfigurationStore.Snapshot configure(long expected,OneShotSettings settings) {
        var current=configs.snapshot();
        if(expected!=current.map(OneShotConfigurationStore.Snapshot::revision).orElse(0L)) throw new OneShotConfigurationStore.RevisionConflict();
        if(claimedGrid!=null && !claimedGrid.equals(new Grid(settings.anchor(),settings.period())))
            throw new IllegalStateException("automatic epoch grid is frozen; drain and restart all peers to change anchor or period");
        if(!actuator.ownedFunctions().isEmpty() && !actuator.ownedFunctions().equals(settings.functions().keySet())) throw new IllegalStateException("drain owned function scope before changing selection");
        inputs.validate(settings);
        long budget=settings.negotiation().auctionBudget().plus(settings.preparationBudget()).toNanos();
        if(budget>settings.period().toNanos()*settings.maxOperationalFraction() || budget>settings.leadTime().toNanos()) throw new IllegalArgumentException("full operational budget must fit lead time and a small period fraction");
        if(settings.scheduled() && !qualified(settings)) throw new IllegalArgumentException("scheduled mode requires at least 20 complete timing samples with p99 and margin");
        if(settings.scheduled() && current.map(s->!s.settings().scheduled()).orElse(true)) {
            if(busy.get()) throw new IllegalStateException("manual preparation must finish before enabling scheduling");
            long delta=Duration.between(settings.anchor(),clock.instant().plus(settings.leadTime())).toMillis();
            long candidate=Math.max(0,delta/settings.period().toMillis());
            if(candidate<=coordinator.lastPreparedEpoch()) throw new IllegalStateException("scheduled epoch must advance beyond the last manual epoch");
        }
        return configs.replace(expected,settings);
    }
    public Mono<Boolean> drainAndRelease() { if(busy.get()) return Mono.error(new IllegalStateException("preparation active"));return actuator.drainAndRelease(); }
    public Optional<OneShotSettings> settingsFor(long revision) { return Optional.ofNullable(preparedSettings.get(revision)); }
    private String key(OneShotSettings s) { return key(s,profiles.compatible(s).contentHash()); }
    private String key(OneShotSettings s,String hash) {
        return hash+":"+s.environmentFingerprint()+":"+s.functions()+":"+s.memoryCapacityMiB()+":"+s.flowQuantum()+":"+s.negotiation()+":"+s.maxSolverStates()+":"+s.maxSolverBytes()+":"+s.preparationBudget()+":"+peers.activeNeighbors().stream().map(p->p.peerId()+":"+p.incarnation()).sorted().toList();
    }
    private synchronized boolean qualified(OneShotSettings s) {
        if(!key(s).equals(qualificationKey) || qualified.size()<20) return false;
        var samples=qualified.stream().mapToLong(Long::longValue).sorted().toArray();long p99=samples[(int)Math.ceil(samples.length*.99)-1];
        return p99*2.0<s.period().toNanos()*s.maxOperationalFraction() && p99*2.0<s.leadTime().toNanos();
    }
    public Mono<PlanActivation> prepare(long epoch,Window window,boolean automatic) {
        return Mono.defer(()-> {
            OneShotConfigurationStore.Snapshot frozen;
            synchronized(this) {
                frozen=configs.snapshot().orElseThrow(()->new IllegalStateException("configuration missing"));
                if(frozen.settings().scheduled()!=automatic || !running || !busy.compareAndSet(false,true)) return Mono.error(new IllegalStateException("trigger mode conflict or active preparation"));
                if(automatic) claimedGrid=new Grid(frozen.settings().anchor(),frozen.settings().period());
            }
            return prepareFrozen(epoch,window,frozen,automatic);
        });
    }
    private Mono<PlanActivation> prepareFrozen(long epoch,Window window,OneShotConfigurationStore.Snapshot frozen,boolean automatic) {
            var settings=frozen.settings();
            long start=System.nanoTime();String identity;var recorded=new AtomicBoolean();
            try {
                if(automatic && !qualified(settings)) throw new IllegalStateException("timing qualification expired");
                var input=inputs.pin(epoch,window.startsAt(),window.endsAt(),frozen);identity=key(settings,input.profileHash());
            } catch(RuntimeException error) { busy.set(false);return Mono.error(error); }
            return coordinator.prepare(epoch,window.startsAt(),window.endsAt()).flatMap(outcome-> {
                meters.timer("nanofaas_oneshot_solver_seconds").record(outcome.solverNanos(),TimeUnit.NANOSECONDS);
                meters.timer("nanofaas_oneshot_auction_seconds","status",outcome.status().name()).record(outcome.auctionNanos(),TimeUnit.NANOSECONDS);
                if(outcome.status()!=EpochOutcome.Status.CONVERGED) return Mono.just(new PlanActivation(PlanActivation.Status.FAILED,null,Map.of(),Map.of(),outcome.reason(),Instant.now()));
                return actuator.prepare(outcome,settings.preparationBudget());
            }).doOnNext(result-> {
                recorded.set(true);long elapsed=System.nanoTime()-start;boolean censored=result.status()!=PlanActivation.Status.PREPARED;
                lastState=result.status().name();var local=peers.localEndpoint().orElseThrow();events.record(local.peerId(),local.incarnation(),epoch,0,lastState,censored,elapsed,Instant.now());
                meters.counter("nanofaas_oneshot_epochs_total","status",lastState).increment();
                if(!censored) {
                    synchronized(this) { if(!identity.equals(qualificationKey)) { qualified.clear();qualificationKey=identity; }qualified.addLast(elapsed);while(qualified.size()>128) qualified.removeFirst(); }
                    meters.timer("nanofaas_oneshot_operational_seconds").record(elapsed,TimeUnit.NANOSECONDS);
                }
                if(result.plan()!=null) { preparedSettings.put(result.plan().revision(),settings);while(preparedSettings.size()>4) preparedSettings.remove(Collections.min(preparedSettings.keySet())); }
            }).doOnError(error->{recorded.set(true);lastState="FAILED";events.record(epoch,lastState,true,System.nanoTime()-start,Instant.now());})
              .doFinally(signal->{if(recorded.compareAndSet(false,true)) events.record(epoch,"CANCELLED",true,System.nanoTime()-start,Instant.now());inputs.unpin(epoch);busy.set(false);});
    }
    public Map<String,Object> status() { return Map.of("schemaVersion",1,"state",lastState,"busy",busy.get(),"clockHealthy",coordinator.clockHealthy(),"catalogGenerations",inputs.catalogGenerations(),"peerEndpoints",peers.activeNeighbors(),"localEndpoint",peers.localEndpoint().map(p->(Object)p).orElse(Map.of()),"revision",configs.snapshot().map(OneShotConfigurationStore.Snapshot::revision).orElse(0L),"activePlan",actuator.activePlan().map(p->(Object)p).orElse(Map.of())); }
    @Override public boolean isAutoStartup() { return enabled; }
    @Override public synchronized void start() { if(running || !enabled) return;running=true;tickTask=timer.scheduleWithFixedDelay(this::tick,100,100,TimeUnit.MILLISECONDS); }
    void tick() {
        try {
            OneShotConfigurationStore.Snapshot frozen; long epoch; Window window;
            synchronized(this) {
                if(!running || busy.get()) return;
                var current=configs.snapshot();if(current.isEmpty() || !current.get().settings().scheduled()) return;
                frozen=current.get();var s=frozen.settings();var now=clock.instant();
                long delta=Duration.between(s.anchor(),now.plus(s.leadTime())).toMillis();long step=s.period().toMillis();if(delta<0 || step<1) return;
                epoch=delta/step;var from=s.anchor().plusMillis(Math.multiplyExact(epoch,step));
                if(!now.isBefore(from) || epoch<=lastScheduled.get() || epoch<=coordinator.lastPreparedEpoch() || !busy.compareAndSet(false,true)) return;
                lastScheduled.set(epoch);claimedGrid=new Grid(s.anchor(),s.period());window=new Window(from,from.plus(s.period()));
            }
            subscription=prepareFrozen(epoch,window,frozen,true).subscribe(result->{},error->lastState="FAILED");
        } catch(RuntimeException error) { lastState="FAILED"; }
    }
    @Override public synchronized void stop() { running=false;if(tickTask!=null) tickTask.cancel(false);if(subscription!=null) subscription.dispose(); }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE-2044; }
    @Override public void close() { stop();timer.shutdownNow(); }
}
