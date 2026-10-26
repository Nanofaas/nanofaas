package it.unimib.datai.nanofaas.modules.offload.oneshot.api;
import it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.*;
import it.unimib.datai.nanofaas.modules.offload.oneshot.actuation.*;
import it.unimib.datai.nanofaas.p2papi.PeerTransport;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.SmartLifecycle;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;
/** Bounded manual/scheduled orchestration; the scheduled gate needs measured complete durations. */
public final class OneShotOperations implements SmartLifecycle,AutoCloseable {
    public record Window(Instant startsAt,Instant endsAt) {}
    public record LogicalWindow(Instant startsAt,Instant endsAt,Duration activationDelay) {}
    public record SessionStart(long firstEpoch,Duration activationDelay) {}
    record ScheduledWindow(long epoch,Window window) {}

    static Optional<ScheduledWindow> nextWindow(OneShotSettings settings,Instant now) {
        try {
            var delta=Duration.between(settings.anchor(),now.plus(settings.leadTime()));
            if(delta.isNegative()) return Optional.empty();
            long epoch=delta.dividedBy(settings.period());
            var candidate=windowAt(settings,epoch);
            return now.isBefore(candidate.window().startsAt())?Optional.of(candidate):Optional.empty();
        } catch(ArithmeticException | DateTimeException error) {
            throw new IllegalArgumentException("schedule window outside supported time range",error);
        }
    }
    private static ScheduledWindow firstFutureWindow(OneShotSettings settings,Instant now) {
        try {
            long epoch=now.isBefore(settings.anchor())?0:
                    Math.addExact(Duration.between(settings.anchor(),now).dividedBy(settings.period()),1);
            return windowAt(settings,epoch);
        } catch(ArithmeticException | DateTimeException error) {
            throw new IllegalArgumentException("schedule window outside supported time range",error);
        }
    }
    private static ScheduledWindow windowAt(OneShotSettings settings,long epoch) {
        var from=settings.anchor().plus(settings.period().multipliedBy(epoch));
        return new ScheduledWindow(epoch,new Window(from,from.plus(settings.period())));
    }
    private final OneShotConfigurationStore configs;private final ServiceProfileStore profiles;private final ProfileEpochInputFactory inputs;
    private final EpochCoordinator coordinator;private final ReplicaPlanActuator actuator;private final EpochEventStore events;private final PeerTransport peers;private final MeterRegistry meters;
    private final boolean enabled;
    private final Clock clock;
    private final LongSupplier nanos;
    private final LogicalEpochInitiator initiator;
    private long lifecycle;
    private String claimedSession;
    private LogicalPreparation logicalPreparation;
    private final class LogicalPreparation {
        final long epoch,token;
        final EpochTiming timing;
        final Mono<PlanActivation> activation;
        boolean activationStarted;
        LogicalPreparation(long epoch,EpochTiming timing,String identity,long negotiationNanos,long token) {
            this.epoch=epoch;this.timing=timing;this.token=token;
            var startedActivation=Mono.defer(()-> {
                long start=nanos.getAsLong();
                return Mono.using(()->Boolean.TRUE,ignored->actuator.activate(timing.sessionId(),epoch)
                        .doOnNext(result->recordLogical(epoch,result,identity,negotiationNanos+nanos.getAsLong()-start,true))
                        .doOnError(error->recordLogicalFailure(epoch,negotiationNanos+nanos.getAsLong()-start)),
                        ignored->busy.set(false),true);
            }).cache();
            activation=Mono.defer(()-> {
                synchronized(OneShotOperations.this) {
                    if(activationStarted) return startedActivation;
                    if(!running || token!=lifecycle || logicalPreparation!=this || timing.expired(timing.expiryDeadline()))
                        return Mono.error(new IllegalStateException("logical activation retired or expired"));
                    if(!busy.compareAndSet(false,true)) return Mono.error(new IllegalStateException("preparation active"));
                    activationStarted=true;
                    return startedActivation;
                }
            });
        }
    }
    private record Grid(Instant anchor,Duration period) {}
    private Grid claimedGrid;
    private static final class ScheduledRun {
        final Sinks.Empty<Void> cancelled=Sinks.empty();
        volatile boolean stopped;
        synchronized void cancel() { stopped=true;cancelled.tryEmitEmpty(); }
    }
    private ScheduledRun scheduledRun;
    private final AtomicBoolean busy=new AtomicBoolean();private final AtomicLong lastScheduled=new AtomicLong(-1);
    private final Map<Long,OneShotSettings> preparedSettings=new ConcurrentHashMap<>();
    private final ArrayDeque<Long> qualified=new ArrayDeque<>();private String qualificationKey="";
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"one-shot-epochs");t.setDaemon(true);return t;});
    private volatile ScheduledFuture<?> tickTask;
    private volatile boolean running;private volatile String lastState="UNCONFIGURED";
    public OneShotOperations(OneShotConfigurationStore configs,ServiceProfileStore profiles,ProfileEpochInputFactory inputs,EpochCoordinator coordinator,ReplicaPlanActuator actuator,EpochEventStore events,PeerTransport peers,MeterRegistry meters) {
        this(configs,profiles,inputs,coordinator,actuator,events,peers,meters,true);
    }
    public OneShotOperations(OneShotConfigurationStore configs,ServiceProfileStore profiles,ProfileEpochInputFactory inputs,EpochCoordinator coordinator,ReplicaPlanActuator actuator,EpochEventStore events,PeerTransport peers,MeterRegistry meters,boolean enabled) {
        this(configs,profiles,inputs,coordinator,actuator,events,peers,meters,enabled,Clock.systemUTC());
    }
    OneShotOperations(OneShotConfigurationStore configs,ServiceProfileStore profiles,ProfileEpochInputFactory inputs,EpochCoordinator coordinator,ReplicaPlanActuator actuator,EpochEventStore events,PeerTransport peers,MeterRegistry meters,boolean enabled,Clock clock) {
        this(configs,profiles,inputs,coordinator,actuator,events,peers,meters,enabled,clock,System::nanoTime);
    }
    OneShotOperations(OneShotConfigurationStore configs,ServiceProfileStore profiles,ProfileEpochInputFactory inputs,EpochCoordinator coordinator,ReplicaPlanActuator actuator,EpochEventStore events,PeerTransport peers,MeterRegistry meters,boolean enabled,Clock clock,LongSupplier nanos) {
        this.clock=Objects.requireNonNull(clock);
        this.nanos=Objects.requireNonNull(nanos);
        this.enabled=enabled;
        this.configs=configs;this.profiles=profiles;this.inputs=inputs;this.coordinator=coordinator;this.actuator=actuator;this.events=events;this.peers=peers;this.meters=meters;
        initiator=new LogicalEpochInitiator(peers,nanos,new LogicalEpochInitiator.Work() {
            @Override public Mono<Boolean> prepare(long epoch,EpochTiming timing,boolean catchUp) {
                var window=new LogicalWindow(timing.startsAt(),timing.endsAt(),Duration.ofNanos(timing.activationDeadline()-timing.startedNanos()));
                return prepareLogical(timing.sessionId(),epoch,window,true,timing,catchUp)
                        .map(result->result.status()==PlanActivation.Status.NEGOTIATED)
                        .doOnError(error->recordLogicalFailure(epoch,nanos.getAsLong()-timing.startedNanos()));
            }
            @Override public Mono<Void> activate(String session,long epoch) {return OneShotOperations.this.activate(session,epoch).then();}
        });
    }
    public synchronized OneShotConfigurationStore.Snapshot configure(long expected,OneShotSettings settings) {
        var current=configs.snapshot();
        if(expected!=current.map(OneShotConfigurationStore.Snapshot::revision).orElse(0L)) throw new OneShotConfigurationStore.RevisionConflict();
        if(claimedSession!=null && (settings.schemaVersion()!=2 || !claimedSession.equals(settings.sessionId())))
            throw new IllegalStateException("logical session is frozen for this process");
        if(initiator.isArmed() && settings.scheduled() && current.map(c->!c.settings().equals(settings)).orElse(true))
            throw new IllegalStateException("disable automatic initiation before changing its configuration");
        if(claimedGrid!=null && !claimedGrid.equals(new Grid(settings.anchor(),settings.period())))
            throw new IllegalStateException("automatic epoch grid is frozen; drain and restart all peers to change anchor or period");
        if(!actuator.ownedFunctions().isEmpty() && !actuator.ownedFunctions().equals(settings.functions().keySet())) throw new IllegalStateException("drain owned function scope before changing selection");
        inputs.validate(settings);
        long budget=settings.negotiation().auctionBudget().plus(settings.preparationBudget()).toNanos();
        if(budget>settings.period().toNanos()*settings.maxOperationalFraction() || budget>settings.leadTime().toNanos()) throw new IllegalArgumentException("full operational budget must fit lead time and a small period fraction");
        if(settings.scheduled() && !qualified(settings)) throw new IllegalArgumentException("scheduled mode requires at least 20 complete timing samples with p99 and margin");
        if(settings.scheduled()) {
            if(current.map(s->!s.settings().scheduled()).orElse(true) && busy.get()) throw new IllegalStateException("manual preparation must finish before enabling scheduling");
            if(settings.schemaVersion()==1) {
                var now=clock.instant();
                var candidate=nextWindow(settings,now).orElseGet(()->firstFutureWindow(settings,now));
                long fence=coordinator.lastPreparedEpoch();
                if(fence>lastScheduled.get() && candidate.epoch()<=fence) throw new IllegalStateException("scheduled epoch must advance beyond the last manual epoch");
            }
        }
        var result=configs.replace(expected,settings);
        if(!settings.scheduled()) initiator.disarm();
        return result;
    }
    public Mono<Boolean> drainAndRelease() { if(busy.get()) return Mono.error(new IllegalStateException("preparation active"));return actuator.drainAndRelease(); }
    public Optional<OneShotSettings> settingsFor(long revision) { return Optional.ofNullable(preparedSettings.get(revision)); }
    private String key(OneShotSettings s) { return key(s,profiles.compatible(s).contentHash()); }
    private String key(OneShotSettings s,String hash) {
        return s.schemaVersion()+":"+hash+":"+s.environmentFingerprint()+":"+s.functions()+":"+s.memoryCapacityMiB()+":"+s.flowQuantum()+":"+s.negotiation()+":"+s.maxSolverStates()+":"+s.maxSolverBytes()+":"+s.preparationBudget()+":"+peers.activeNeighbors().stream().map(p->p.peerId()+":"+p.incarnation()).sorted().toList();
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
                if(frozen.settings().schemaVersion()!=1) return Mono.error(new IllegalStateException("logical session endpoint required"));
                if(frozen.settings().scheduled()!=automatic || !running || !busy.compareAndSet(false,true)) return Mono.error(new IllegalStateException("trigger mode conflict or active preparation"));
                if(automatic) {
                    claimedGrid=new Grid(frozen.settings().anchor(),frozen.settings().period());
                    lastScheduled.accumulateAndGet(epoch,Math::max);
                }
            }
            return prepareFrozen(epoch,window,frozen,automatic,null);
        });
    }
    public Mono<PlanActivation> prepareLogical(String sessionId,long epoch,LogicalWindow window) {
        return prepareLogical(sessionId,epoch,window,false,null,false);
    }
    private Mono<PlanActivation> prepareLogical(String sessionId,long epoch,LogicalWindow window,boolean automatic,EpochTiming acceptedTiming,boolean catchUp) {
        return Mono.defer(()-> {
            OneShotConfigurationStore.Snapshot frozen;EpochTiming timing;long token;
            synchronized(this) {
                frozen=configs.snapshot().orElseThrow(()->new IllegalStateException("configuration missing"));
                var settings=frozen.settings();
                if(!running || settings.schemaVersion()!=2 || !Objects.equals(sessionId,settings.sessionId()) || settings.scheduled()!=automatic)
                    return Mono.error(new IllegalStateException("logical session or trigger mode conflict"));
                if(automatic && !qualified(settings)) return Mono.error(new IllegalStateException("timing qualification expired"));
                if(epoch<0 || window==null) throw new IllegalArgumentException("invalid logical epoch");
                var canonical=windowAt(settings,epoch).window();
                if(!canonical.startsAt().equals(window.startsAt()) || !canonical.endsAt().equals(window.endsAt()))
                    throw new IllegalArgumentException("canonical epoch labels differ from configuration");
                timing=acceptedTiming==null?EpochTiming.capture(sessionId,window.startsAt(),window.endsAt(),window.activationDelay(),nanos):acceptedTiming;
                if(window.activationDelay().compareTo(settings.negotiation().auctionBudget().plus(settings.preparationBudget()))<0)
                    throw new IllegalArgumentException("activation delay is smaller than operational budget");
                if(logicalPreparation!=null && epoch<=logicalPreparation.epoch || !busy.compareAndSet(false,true))
                    return Mono.error(new IllegalStateException("epoch is not monotonic or preparation active"));
                claimedSession=sessionId;claimedGrid=new Grid(settings.anchor(),settings.period());token=lifecycle;
            }
            return Mono.using(()->epoch,ignored->(catchUp?actuator.drainAndRelease():Mono.just(true)).flatMap(drained-> {
                if(!drained) return Mono.error(new IllegalStateException("catch-up awaits physical drain"));
                if(catchUp) events.record(epoch,"CATCH_UP_SKIPPED_"+Math.max(0,epoch-coordinator.lastPreparedEpoch()-1),true,0,clock.instant());
                var input=inputs.pin(epoch,window.startsAt(),window.endsAt(),frozen);
                String identity=key(frozen.settings(),input.profileHash());
                synchronized(this) { if(!running || token!=lifecycle) return Mono.error(new IllegalStateException("preparation retired")); }
                return coordinator.prepareLogical(epoch,timing).flatMap(outcome-> {
                    meters.timer("nanofaas_oneshot_solver_seconds").record(outcome.solverNanos(),TimeUnit.NANOSECONDS);
                    meters.timer("nanofaas_oneshot_auction_seconds","status",outcome.status().name()).record(outcome.auctionNanos(),TimeUnit.NANOSECONDS);
                    if(outcome.status()!=EpochOutcome.Status.FIXED_ROUNDS_COMPLETED)
                        return Mono.just(new PlanActivation(PlanActivation.Status.FAILED,null,Map.of(),Map.of(),outcome.reason(),clock.instant()));
                    return actuator.prepare(outcome,frozen.settings().preparationBudget(),frozen.settings().negotiation().peerTimeout());
                }).doOnNext(result-> {
                    long elapsed=nanos.getAsLong()-timing.startedNanos();
                    synchronized(this) {
                        if(!running || token!=lifecycle) throw new IllegalStateException("preparation retired");
                        if(result.status()==PlanActivation.Status.NEGOTIATED) {
                            preparedSettings.put(frozen.revision(),frozen.settings());
                            while(preparedSettings.size()>4) preparedSettings.remove(Collections.min(preparedSettings.keySet()));
                            logicalPreparation=new LogicalPreparation(epoch,timing,identity,elapsed,token);
                        }
                    }
                    recordLogical(epoch,result,identity,elapsed,false);
                });
            }),ignored->{inputs.unpin(epoch);busy.set(false);},true)
                    .doOnError(error->{if(!automatic) recordLogicalFailure(epoch,nanos.getAsLong()-timing.startedNanos());});
        });
    }
    public synchronized Map<String,Object> startSession(String sessionId,SessionStart start) {
        var selected=configs.snapshot().orElseThrow(()->new IllegalStateException("configuration missing")).settings();
        if(!running || selected.schemaVersion()!=2 || !selected.scheduled() || !Objects.equals(sessionId,selected.sessionId()))
            throw new IllegalStateException("logical automatic session unavailable");
        if(start==null || start.firstEpoch()<0) throw new IllegalArgumentException("invalid session start");
        if(!initiator.isArmed()) {
            long fence=Math.max(coordinator.lastPreparedEpoch(),logicalPreparation==null?-1:logicalPreparation.epoch);
            if(busy.get() || start.firstEpoch()<=fence) throw new IllegalStateException("session start must advance beyond the last preparation");
        }
        if(!qualified(selected)) throw new IllegalStateException("timing qualification expired");
        initiator.arm(selected,start.firstEpoch(),start.activationDelay());
        claimedSession=sessionId;claimedGrid=new Grid(selected.anchor(),selected.period());
        return Map.of("schemaVersion",2,"sessionId",sessionId,"firstEpoch",start.firstEpoch(),"armed",true);
    }
    public Mono<PlanActivation> activate(String sessionId,long epoch) {
        return Mono.defer(()-> {
            synchronized(this) {
                var prepared=logicalPreparation;
                if(!running || prepared==null || prepared.token!=lifecycle || prepared.epoch!=epoch
                        || !prepared.timing.sessionId().equals(sessionId))
                    return Mono.error(new IllegalStateException("logical epoch is not staged"));
                return prepared.activation;
            }
        });
    }
    private void recordLogicalFailure(long epoch,long elapsed) {
        lastState="FAILED";events.record(epoch,lastState,true,elapsed,clock.instant());
    }
    private void recordLogical(long epoch,PlanActivation result,String identity,long elapsed,boolean activated) {
        lastState=result.status().name();boolean censored=!activated || result.status()!=PlanActivation.Status.PREPARED;
        var local=peers.localEndpoint().orElseThrow();events.record(local.peerId(),local.incarnation(),epoch,0,lastState,censored,elapsed,clock.instant());
        meters.counter("nanofaas_oneshot_epochs_total","status",lastState).increment();
        if(!censored) {
            synchronized(this) { if(!identity.equals(qualificationKey)) { qualified.clear();qualificationKey=identity; }qualified.addLast(elapsed);while(qualified.size()>128) qualified.removeFirst(); }
            meters.timer("nanofaas_oneshot_operational_seconds").record(elapsed,TimeUnit.NANOSECONDS);
        }
    }
    private Mono<PlanActivation> prepareFrozen(long epoch,Window window,OneShotConfigurationStore.Snapshot frozen,boolean automatic,ScheduledRun run) {
            var settings=frozen.settings();
            long start=System.nanoTime();String identity;var recorded=new AtomicBoolean();
            try {
                if(automatic && !qualified(settings)) throw new IllegalStateException("timing qualification expired");
                var input=inputs.pin(epoch,window.startsAt(),window.endsAt(),frozen);identity=key(settings,input.profileHash());
            } catch(RuntimeException error) { busy.set(false);return Mono.error(error); }
            var preparation=Mono.defer(()->run!=null && run.stopped?Mono.empty():coordinator.prepare(epoch,window.startsAt(),window.endsAt())).flatMap(outcome-> {
                meters.timer("nanofaas_oneshot_solver_seconds").record(outcome.solverNanos(),TimeUnit.NANOSECONDS);
                meters.timer("nanofaas_oneshot_auction_seconds","status",outcome.status().name()).record(outcome.auctionNanos(),TimeUnit.NANOSECONDS);
                if(outcome.status()!=EpochOutcome.Status.FIXED_ROUNDS_COMPLETED) return Mono.just(new PlanActivation(PlanActivation.Status.FAILED,null,Map.of(),Map.of(),outcome.reason(),Instant.now()));
                return actuator.prepare(outcome,settings.preparationBudget(),settings.negotiation().peerTimeout());
            }).doOnNext(result-> {
                recorded.set(true);long elapsed=System.nanoTime()-start;boolean censored=result.status()!=PlanActivation.Status.PREPARED;
                lastState=result.status().name();var local=peers.localEndpoint().orElseThrow();events.record(local.peerId(),local.incarnation(),epoch,0,lastState,censored,elapsed,Instant.now());
                meters.counter("nanofaas_oneshot_epochs_total","status",lastState).increment();
                if(!censored) {
                    synchronized(this) { if(!identity.equals(qualificationKey)) { qualified.clear();qualificationKey=identity; }qualified.addLast(elapsed);while(qualified.size()>128) qualified.removeFirst(); }
                    meters.timer("nanofaas_oneshot_operational_seconds").record(elapsed,TimeUnit.NANOSECONDS);
                }
                if(result.plan()!=null) { preparedSettings.put(result.plan().revision(),settings);while(preparedSettings.size()>4) preparedSettings.remove(Collections.min(preparedSettings.keySet())); }
            }).doOnError(error->{recorded.set(true);lastState="FAILED";events.record(epoch,lastState,true,System.nanoTime()-start,Instant.now());});
            // The stop signal is registered with the claim, before input pinning can block.
            // Subscribe to it before starting coordinator work, even after a stop/start cycle.
            if(run!=null) preparation=preparation.takeUntilOther(run.cancelled.asMono());
            return preparation.doFinally(signal->{if(recorded.compareAndSet(false,true)) events.record(epoch,"CANCELLED",true,System.nanoTime()-start,Instant.now());inputs.unpin(epoch);busy.set(false);});
    }
    public Map<String,Object> status() {
        var config=configs.snapshot();
        var result=new LinkedHashMap<String,Object>();
        result.put("schemaVersion",config.map(c->c.settings().schemaVersion()).orElse(1));
        config.map(c->c.settings().sessionId()).ifPresent(session->result.put("sessionId",session));
        result.put("state",lastState);result.put("busy",busy.get());result.put("clockHealthy",coordinator.clockHealthy());
        result.put("catalogGenerations",inputs.catalogGenerations());result.put("peerEndpoints",peers.activeNeighbors());
        result.put("localEndpoint",peers.localEndpoint().map(p->(Object)p).orElse(Map.of()));
        result.put("revision",config.map(OneShotConfigurationStore.Snapshot::revision).orElse(0L));
        result.put("activePlan",actuator.activePlan().map(p->(Object)p).orElse(Map.of()));
        result.put("automaticArmed",initiator.isArmed());
        return Map.copyOf(result);
    }
    @Override public boolean isAutoStartup() { return enabled; }
    @Override public synchronized void start() { if(running || !enabled) return;running=true;initiator.start();tickTask=timer.scheduleWithFixedDelay(this::tick,100,100,TimeUnit.MILLISECONDS); }
    void tick() {
        try {
            if(configs.snapshot().map(c->c.settings().schemaVersion()==2).orElse(false)) {
                if(running) initiator.tick();
                return;
            }
            OneShotConfigurationStore.Snapshot frozen; long epoch; Window window; ScheduledRun run;
            synchronized(this) {
                if(!running || busy.get()) return;
                var current=configs.snapshot();if(current.isEmpty() || !current.get().settings().scheduled()) return;
                frozen=current.get();var s=frozen.settings();var now=clock.instant();
                var candidate=nextWindow(s,now);if(candidate.isEmpty()) return;
                epoch=candidate.get().epoch();
                if(epoch<=lastScheduled.get() || epoch<=coordinator.lastPreparedEpoch() || !busy.compareAndSet(false,true)) return;
                lastScheduled.set(epoch);claimedGrid=new Grid(s.anchor(),s.period());window=candidate.get().window();
                scheduledRun=run=new ScheduledRun();
            }
            var preparation=prepareFrozen(epoch,window,frozen,true,run);
            synchronized(run) { preparation.subscribe(result->{},error->lastState="FAILED"); }
        } catch(RuntimeException error) { lastState="FAILED"; }
    }
    @Override public void stop() {
        ScheduledRun run;
        synchronized(this) { running=false;lifecycle++;logicalPreparation=null;initiator.stop();if(tickTask!=null) tickTask.cancel(false);run=scheduledRun; }
        if(run!=null) run.cancel();
    }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE-2044; }
    @Override public void close() { stop();timer.shutdownNow(); }
}
