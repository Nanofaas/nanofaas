package it.unimib.datai.nanofaas.modules.offload.oneshot.coordination;

import it.unimib.datai.nanofaas.p2papi.*;
import it.unimib.datai.nanofaas.modules.offload.oneshot.auction.*;
import it.unimib.datai.nanofaas.modules.offload.oneshot.solver.*;
import it.unimib.datai.nanofaas.forecastingapi.ForecastSnapshot;
import reactor.core.publisher.*;
import reactor.core.scheduler.*;
import org.springframework.context.SmartLifecycle;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

/** Quasi-synchronous, phase-barrier protocol. Missing closures never imply convergence. */
public final class EpochCoordinator implements SmartLifecycle, AutoCloseable {
    public static final String TOPIC="nanofaas.oneshot.v1";
    private record Key(String peer,int round,AuctionCodec.Phase phase) {}
    private static final class Run {
        final PeerEndpoint local; final Map<String,PeerEndpoint> peers; final long epoch,deadline;
        final Instant from,until; final long started;
        final ArrayBlockingQueue<AuctionCodec.Batch> inbox;
        final Semaphore pending;
        final Map<Key,String> accepted=new ConcurrentHashMap<>();
        final Map<Key,AuctionCodec.Batch> waiting=new HashMap<>();
        final AtomicReference<CompletableFuture<?>> exchange=new AtomicReference<>();
        final AtomicLong solverNanos=new AtomicLong();
        volatile boolean cancelled, closed; volatile int round;
        SellerLedger ledger;
        Run(PeerEndpoint local,Map<String,PeerEndpoint> peers,long epoch,Instant from,Instant until,long started,long deadline,int bound) {
            this.local=local; this.peers=Map.copyOf(peers); this.epoch=epoch; this.from=from; this.until=until;
            this.started=started; this.deadline=deadline; inbox=new ArrayBlockingQueue<>(bound); pending=new Semaphore(bound);
        }
    }
    private final PeerTransport transport; private final EpochInput.Factory inputs; private final EpochSettings settings;
    private final ClockHealth clockHealth; private final Supplier<Instant> now; private final AuctionCodec codec=new AuctionCodec();
    private final OneShotAuctionEngine.Options options;
    private final AtomicReference<Run> active=new AtomicReference<>();
    private final AtomicReference<Run> lastClosed=new AtomicReference<>();
    private final AtomicLong lastEpoch=new AtomicLong(-1);
    private final Scheduler scheduler;
    private volatile long lifecycle;
    private volatile PeerSubscription subscription; private volatile boolean running;
    public EpochCoordinator(PeerTransport transport,EpochInput.Factory inputs,EpochSettings settings,ClockHealth health) {
        this(transport,inputs,settings,health,Instant::now,OneShotAuctionEngine.Options.base());
    }
    public EpochCoordinator(PeerTransport transport,EpochInput.Factory inputs,EpochSettings settings,ClockHealth health,Supplier<Instant> now,OneShotAuctionEngine.Options options) {
        this.transport=transport; this.inputs=inputs; this.settings=settings; clockHealth=health; this.now=now; this.options=options;
        scheduler=Schedulers.newBoundedElastic(1,1,"one-shot-control");
    }
    @Override public synchronized void start() {
        if(running) return;
        long token=++lifecycle;
        subscription=transport.subscribe(TOPIC,(sender,payload)->Mono.fromCallable(()->token==lifecycle && running?receive(sender,payload):new byte[]{0}));
        running=true;
    }
    @Override public synchronized void stop() {
        running=false; lifecycle++; lastClosed.set(null); var run=active.get();
        if(run!=null) { run.cancelled=true; var exchange=run.exchange.get(); if(exchange!=null) exchange.cancel(true); }
        if(subscription!=null) { subscription.close(); subscription=null; }
    }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE-2046; }
    @Override public void close() { stop(); scheduler.dispose(); }
    public boolean clockHealthy() { return clockHealth.healthy(); }
    public Mono<EpochOutcome> prepare(long epoch,Instant startsAt,Instant endsAt) {
        return Mono.defer(()-> {
            long started=System.nanoTime();
            if(!running || epoch<0 || startsAt==null || endsAt==null || !startsAt.isBefore(endsAt) || !clockHealth.healthy())
                return Mono.just(failure(epoch,started,"inactive coordinator, window or clock invalid"));
            var local=transport.localEndpoint().orElse(null);
            if(local==null) return Mono.just(failure(epoch,started,"local P2P endpoint unavailable"));
            var period=Duration.between(startsAt,endsAt);
            if(period.compareTo(Duration.ofDays(1))>0 || startsAt.isAfter(now.get().plus(Duration.ofDays(1))) || settings.auctionBudget().toNanos()>period.toNanos()*settings.maxAuctionFraction())
                return Mono.just(failure(epoch,started,"auction budget too large for period"));
            long untilStart=Duration.between(now.get(),startsAt).toNanos();
            if(untilStart<=0) return Mono.just(failure(epoch,started,"preparation must precede epoch start"));
            var peers=new LinkedHashMap<String,PeerEndpoint>();
            for(var peer:transport.activeNeighbors()) if(!peer.peerId().equals(local.peerId())) {
                if(peers.put(peer.peerId(),peer)!=null) return Mono.just(failure(epoch,started,"duplicate peer identity"));
            }
            if(peers.size()>settings.maxPeers()) return Mono.just(failure(epoch,started,"peer bound exceeded"));
            var run=new Run(local,peers,epoch,startsAt,endsAt,started,started+Math.min(untilStart,settings.auctionBudget().toNanos()),settings.queueCapacity());
            if(!active.compareAndSet(null,run)) return Mono.just(failure(epoch,started,"negotiation already active"));
            if(epoch<=lastEpoch.get()) { active.compareAndSet(run,null); return Mono.just(failure(epoch,started,"epoch is not monotonic")); }
            lastEpoch.set(epoch);
            return Mono.fromCallable(()->execute(run)).subscribeOn(scheduler).doFinally(signal -> {
                run.cancelled=true; var exchange=run.exchange.get(); if(exchange!=null) exchange.cancel(true); active.compareAndSet(run,null);
            });
        });
    }
    private EpochOutcome failure(long epoch,long started,String reason) { return new EpochOutcome(epoch,EpochOutcome.Status.FAILED,null,System.nanoTime()-started,0,0,reason); }
    private byte[] receive(String sender,byte[] payload) {
        if(!running) return new byte[]{0};
        AuctionCodec.Batch batch;
        try { batch=codec.decode(payload); } catch(IllegalArgumentException invalid) { return new byte[]{0}; }
        var run=active.get();
        if(run==null || run.epoch!=batch.epoch()) run=lastClosed.get();
        if(run==null) return new byte[]{0};
        var peer=run.peers.get(sender);
        if(peer==null || !sender.equals(batch.senderId()) || !peer.incarnation().equals(batch.incarnation()) || batch.epoch()!=run.epoch
            || !batch.startsAt().equals(run.from) || !batch.endsAt().equals(run.until)
            || !sameIncarnation(run) || !clockHealth.healthy() || !now.get().isBefore(run.from)) return new byte[]{0};
        synchronized(run) {
        var key=new Key(sender,batch.round(),batch.phase());
        String fingerprint=java.util.Base64.getEncoder().encodeToString(digest(payload));
        var previous=run.accepted.get(key);
        // A closed round may acknowledge an identical installed batch without applying it again.
        if(previous!=null) return new byte[]{(byte)(previous.equals(fingerprint)?1:0)};
        if(run!=active.get() || run.cancelled || run.closed || System.nanoTime()>=run.deadline || batch.round()<run.round || batch.round()>run.round+1) return new byte[]{0};
        if(batch.phase()==AuctionCodec.Phase.HELLO) return new byte[]{1};
        previous=run.accepted.putIfAbsent(key,fingerprint);
        if(previous!=null) return new byte[]{(byte)(previous.equals(fingerprint)?1:0)};
        if(!run.pending.tryAcquire()) { run.accepted.remove(key,fingerprint); return new byte[]{0}; }
        if(!run.inbox.offer(batch)) { run.pending.release(); run.accepted.remove(key,fingerprint); return new byte[]{0}; }
        return new byte[]{1};
        }
    }
    private static byte[] digest(byte[] bytes) {
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch(java.security.NoSuchAlgorithmException absent) { throw new IllegalStateException(absent); }
    }
    private boolean sameIncarnation(Run run) {
        if(!transport.localEndpoint().filter(run.local::equals).isPresent()) return false;
        var current=new HashMap<String,String>(); transport.activeNeighbors().forEach(p->current.put(p.peerId(),p.incarnation()));
        return run.peers.values().stream().allMatch(p->p.incarnation().equals(current.get(p.peerId())));
    }
    private void check(Run run) {
        if(run.cancelled || !running || Thread.currentThread().isInterrupted()) throw new IllegalStateException("epoch stopped");
        if(System.nanoTime()>=run.deadline || !now.get().isBefore(run.from)) throw new Deadline();
        if(!clockHealth.healthy() || !sameIncarnation(run)) throw new IllegalStateException("clock or peer incarnation changed");
    }
    private static final class Deadline extends RuntimeException {}
    private SolveLimits limits(Run run) {
        check(run); return new SolveLimits(2_000_000,64L*1024*1024,Math.min(run.deadline,System.nanoTime()+settings.solverBudget().toNanos()));
    }
    private EpochOutcome execute(Run run) {
        int rounds=0;
        try {
            check(run); var input=inputs.freeze(run.epoch,run.from,run.until); validateInput(run,input);
            var solver=new LocalReplicaSolver(); var initial=solver.solve(input.problem(),limits(run)); run.solverNanos.addAndGet(initial.durationNanos());
            if(initial.status()!=LocalSolution.Status.OPTIMAL) throw new IllegalStateException("initial solver: "+initial.status());
            var peers=new LinkedHashMap<String,String>(); run.peers.values().forEach(p->peers.put(p.peerId(),p.incarnation()));
            var engine=new OneShotAuctionEngine(options,solver,()->limits(run),result->run.solverNanos.addAndGet(result.durationNanos()));
            run.ledger=new SellerLedger(AuctionSnapshot.open(run.local.peerId(),run.local.incarnation(),run.epoch,0,input.revision(),run.from,run.until,input.problem(),initial,input.identities(),peers),engine);
            hello(run);
            for(int round=0;round<settings.maxRounds();round++) {
                run.round=round; check(run); var before=run.ledger.snapshot();
                var offers=engine.localOffers(before);
                exchange(run,AuctionCodec.Phase.OFFERS,peer -> offers.stream().map(o->new AuctionMessage(envelope(run),AuctionMessage.Kind.OFFER,peer,o,null,null)).toList(),false);
                await(run,AuctionCodec.Phase.OFFERS).forEach(b->b.messages().forEach(run.ledger::apply));
                var state=run.ledger.snapshot(); var bids=new HashMap<String,List<AuctionMessage>>();
                for(var f:state.baseProblem().functions()) {
                    long committed=state.assignments().values().stream().filter(a->a.buyerId().equals(run.local.peerId()) && a.function().equals(f.id())).mapToLong(Assignment::quantity).sum();
                    long wanted=Math.max(0,(long)f.fixedOffload()-committed);
                    var id=state.identities().get(f.id());
                    for(var bid:engine.defineBids(f.id(),id.version(),id.generation(),wanted,f.gamma(),new ArrayList<>(state.offers().values())))
                        bids.computeIfAbsent(bid.target(),ignored->new ArrayList<>()).add(AuctionMessage.bid(envelope(run),bid.target(),bid.bid()));
                }
                exchange(run,AuctionCodec.Phase.BIDS,peer->bids.getOrDefault(peer,List.of()),false);
                await(run,AuctionCodec.Phase.BIDS).forEach(b->b.messages().forEach(run.ledger::apply));
                var closed=run.ledger.apply(AuctionMessage.close(envelope(run),run.local.peerId()));
                if(closed.state().status().startsWith("SOLVER_")) throw new IllegalStateException(closed.state().status());
                exchange(run,AuctionCodec.Phase.GRANTS,peer->closed.emit().stream().filter(m->m.target().equals(peer)).toList(),false);
                await(run,AuctionCodec.Phase.GRANTS).forEach(b->b.messages().forEach(run.ledger::apply));
                state=run.ledger.snapshot();
                boolean changed=!state.assignments().equals(before.assignments()) || !state.desiredReplicas().equals(before.desiredReplicas()) || !state.prices().equals(before.prices());
                exchange(run,AuctionCodec.Phase.CHECK,peer->List.of(),changed);
                boolean peerChanged=await(run,AuctionCodec.Phase.CHECK).stream().anyMatch(AuctionCodec.Batch::changed); rounds++;
                if(!changed && !peerChanged) {
                    var finalized=run.ledger.finalizeAuction(); check(run);
                    if(!"FINALIZED".equals(finalized.state().status())) throw new IllegalStateException(finalized.state().status());
                    return outcome(run,EpochOutcome.Status.CONVERGED,rounds,"all peer round closures received; no changes");
                }
                run.accepted.keySet().removeIf(key->key.round()<run.round);
                if(round+1<settings.maxRounds()) run.ledger.nextRound();
            }
            return outcome(run,EpochOutcome.Status.ROUND_LIMIT,rounds,"round limit is censored, not convergence");
        } catch(Deadline expired) { return outcome(run,EpochOutcome.Status.DEADLINE,rounds,"auction deadline");
        } catch(Exception failure) { return outcome(run,System.nanoTime()>=run.deadline?EpochOutcome.Status.DEADLINE:EpochOutcome.Status.FAILED,rounds,failure.getMessage()); }
    }
    private void validateInput(Run run,EpochInput input) {
        if(input.problem().functions().size()>1000 || input.problem().model()!=LocalProblem.Model.LSP) throw new IllegalArgumentException("unsupported frozen catalog");
        for(var f:input.problem().functions()) {
            var identity=input.identities().get(f.id()); var forecast=input.forecasts().get(f.id());
            if(identity==null || forecast==null || forecast.status()!=ForecastSnapshot.Status.AVAILABLE || !forecast.query().nodeId().equals(run.local.peerId())
                || forecast.query().generation()!=identity.generation() || !forecast.query().function().equals(f.id()) || !forecast.query().start().equals(run.from) || !forecast.query().end().equals(run.until)) throw new IllegalArgumentException("forecast missing or incompatible");
        }
    }
    private EpochOutcome outcome(Run run,EpochOutcome.Status status,int rounds,String reason) { run.closed=true; if(running && active.get()==run && !run.cancelled) lastClosed.set(run); run.inbox.clear(); run.waiting.clear(); return new EpochOutcome(run.epoch,status,run.ledger==null?null:run.ledger.snapshot(),System.nanoTime()-run.started,run.solverNanos.get(),rounds,reason); }
    private AuctionMessage.Envelope envelope(Run run) { return new AuctionMessage.Envelope(1,run.local.peerId(),run.local.incarnation(),run.epoch,run.round,UUID.randomUUID().toString(),run.ledger.snapshot().revision(),run.from,run.until); }
    private void hello(Run run) throws Exception {
        var missing=new HashSet<>(run.peers.keySet());
        while(!missing.isEmpty()) {
            check(run);
            for(var peer:List.copyOf(missing)) {
                var batch=new AuctionCodec.Batch(1,run.local.peerId(),run.local.incarnation(),run.epoch,0,AuctionCodec.Phase.HELLO,run.from,run.until,false,List.of());
                var request=transport.request(peer,TOPIC,codec.encode(batch),peerTimeout(run)).toFuture();
                run.exchange.set(request);
                try {
                    byte[] reply=request.get(peerTimeout(run).toNanos(),TimeUnit.NANOSECONDS);
                    if(reply!=null && reply.length==1 && reply[0]==1) missing.remove(peer);
                } catch(java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | java.util.concurrent.CancellationException unavailable) { check(run); }
                finally { run.exchange.compareAndSet(request,null); if(!request.isDone()) request.cancel(true); }
            }
            if(!missing.isEmpty()) Thread.sleep(10);
        }
    }
    private Duration peerTimeout(Run run) { return Duration.ofNanos(Math.max(1,Math.min(settings.peerTimeout().toNanos(),run.deadline-System.nanoTime()))); }
    private void exchange(Run run,AuctionCodec.Phase phase,java.util.function.Function<String,List<AuctionMessage>> messages,boolean changed) throws Exception {
        check(run);
        var request=Flux.fromIterable(run.peers.keySet()).flatMap(peer->{
            var batch=new AuctionCodec.Batch(1,run.local.peerId(),run.local.incarnation(),run.epoch,run.round,phase,run.from,run.until,changed,messages.apply(peer));
            byte[] bytes=codec.encode(batch);
            return Mono.defer(()->transport.request(peer,TOPIC,bytes,peerTimeout(run))).flatMap(reply->reply.length==1 && reply[0]==1?Mono.just(true):Mono.error(new IllegalStateException("peer did not accept closed phase"))).retry(1);
        },settings.parallelism()).collectList().toFuture();
        run.exchange.set(request);
        try { request.get(Math.max(1,run.deadline-System.nanoTime()),TimeUnit.NANOSECONDS); }
        finally { run.exchange.compareAndSet(request,null); if(!request.isDone()) request.cancel(true); }
        check(run);
    }
    private List<AuctionCodec.Batch> await(Run run,AuctionCodec.Phase phase) throws Exception {
        var received=new LinkedHashMap<String,AuctionCodec.Batch>();
        for(var peer:run.peers.keySet()) {
            var prior=run.waiting.remove(new Key(peer,run.round,phase)); if(prior!=null) { received.put(peer,prior); run.pending.release(); }
        }
        while(received.size()<run.peers.size()) {
            check(run); var batch=run.inbox.poll(10,TimeUnit.MILLISECONDS); if(batch==null) continue;
            if(batch.round()==run.round && batch.phase()==phase) { received.put(batch.senderId(),batch); run.pending.release(); }
            else if(batch.round()<run.round) run.pending.release();
            else run.waiting.put(new Key(batch.senderId(),batch.round(),batch.phase()),batch);
        }
        return run.peers.keySet().stream().sorted().map(received::get).toList();
    }
}
