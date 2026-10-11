package it.unimib.datai.nanofaas.modules.offload.oneshot.api;

import it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.EpochTiming;
import it.unimib.datai.nanofaas.p2papi.*;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;

import java.time.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Bounded epoch initiation. It never decides whether the auction has converged. */
public final class LogicalEpochInitiator {
    public static final String TOPIC="nanofaas.oneshot.epochs.v2";
    public record Message(int schemaVersion,String kind,String sessionId,String senderId,String incarnation,
                          long epoch,Instant startsAt,Instant endsAt,Duration activationDelay) {}
    public interface Work {
        Mono<Boolean> prepare(long epoch,EpochTiming timing,boolean catchUp);
        Mono<Void> activate(String sessionId,long epoch);
    }
    private enum Phase { PREPARING, READY, ACTIVATING, DONE }
    private static final class Attempt {
        final long epoch,lifecycle;
        final EpochTiming timing;
        final Disposable.Composite subscriptions=Disposables.composite();
        Phase phase=Phase.PREPARING;
        Attempt(long epoch,long lifecycle,EpochTiming timing) {this.epoch=epoch;this.lifecycle=lifecycle;this.timing=timing;}
    }
    private final PeerTransport peers;
    private final LongSupplier nanos;
    private final Work work;
    private final JsonMapper mapper=JsonMapper.builder(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(4).maxStringLength(256).maxNumberLength(64).build()).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private PeerSubscription subscription;
    private OneShotSettings settings;
    private long lifecycle,subscriptionToken,firstEpoch,pendingEpoch=-1;
    private Duration initialDelay;
    private Attempt current;
    private Disposable statusRequest;

    public LogicalEpochInitiator(PeerTransport peers,LongSupplier nanos,Work work) {
        this.peers=Objects.requireNonNull(peers);this.nanos=Objects.requireNonNull(nanos);this.work=Objects.requireNonNull(work);
    }
    public synchronized void start() {
        if(subscription!=null) return;
        long token=++subscriptionToken;
        subscription=peers.subscribe(TOPIC,(sender,bytes)->Mono.fromSupplier(()->receive(token,sender,bytes)));
    }
    public synchronized void stop() {
        disarm();subscriptionToken++;if(subscription!=null) subscription.close();subscription=null;
    }
    public synchronized void disarm() {
        lifecycle++;settings=null;pendingEpoch=-1;
        if(current!=null) current.subscriptions.dispose();current=null;
        if(statusRequest!=null) statusRequest.dispose();statusRequest=null;
    }
    public synchronized boolean isArmed() {return settings!=null;}
    public synchronized void arm(OneShotSettings selected,long epoch,Duration activationDelay) {
        if(subscription==null || selected.schemaVersion()!=2 || !selected.scheduled() || epoch<0)
            throw new IllegalStateException("logical scheduled session unavailable");
        validateDelay(selected,activationDelay);
        if(settings!=null) {
            if(!settings.equals(selected) || firstEpoch!=epoch || !initialDelay.equals(activationDelay))
                throw new IllegalStateException("logical session already armed");
            return;
        }
        // Check label arithmetic before accepting the session; UTC is never read.
        labels(selected,epoch);
        settings=selected;firstEpoch=epoch;initialDelay=activationDelay;
    }
    public void tick() {
        Attempt activate=null;long propose=-1,token;Duration delay=null;
        synchronized(this) {
            if(settings==null || pendingEpoch>=0) return;
            token=lifecycle;
            if(current==null) {propose=firstEpoch;delay=initialDelay;}
            else if(current.phase==Phase.READY && current.timing.expired(current.timing.activationDeadline())) {
                current.phase=Phase.ACTIVATING;activate=current;
            } else if(current.phase==Phase.DONE && nanos.getAsLong()-(current.timing.activationDeadline()+settings.period().toNanos()-settings.leadTime().toNanos())>=0) {
                propose=Math.addExact(current.epoch,1);delay=settings.leadTime();
            }
        }
        if(propose>=0) claim(token,propose,delay,false);
        if(activate!=null) {
            var attempt=activate;
            attempt.subscriptions.add(Mono.defer(()->work.activate(attempt.timing.sessionId(),attempt.epoch))
                    .subscribeOn(Schedulers.boundedElastic()).subscribe(ignored->{},error->finish(attempt,false),()->finish(attempt,false)));
        }
    }
    private boolean claim(long token,long epoch,Duration delay,boolean catchUp) {
        Attempt attempt;OneShotSettings selected;List<PeerEndpoint> neighbors;Message proposal;
        synchronized(this) {
            if(token!=lifecycle || settings==null || epoch<firstEpoch) return false;
            if(current!=null && epoch==current.epoch) return true;
            if(current!=null && (epoch<current.epoch || current.phase!=Phase.DONE)) return false;
            selected=settings;validateDelay(selected,delay);
            var window=labels(selected,epoch);
            var timing=EpochTiming.capture(selected.sessionId(),window.startsAt(),window.endsAt(),delay,nanos);
            if(current!=null) current.subscriptions.dispose();
            attempt=new Attempt(epoch,lifecycle,timing);current=attempt;pendingEpoch=-1;
            neighbors=peers.activeNeighbors();
            if(neighbors.size()>selected.negotiation().maxPeers()) {attempt.phase=Phase.DONE;return false;}
            proposal=message("PROPOSE",epoch,delay);
        }
        attempt.subscriptions.add(Mono.defer(()->work.prepare(epoch,attempt.timing,catchUp)).subscribeOn(Schedulers.boundedElastic())
                .subscribe(ready->finish(attempt,ready),error->finish(attempt,false)));
        // Only one forwarding attempt per accepted epoch, with bounded transport retries.
        byte[] bytes=mapper.writeValueAsBytes(proposal);
        attempt.subscriptions.add(Flux.fromIterable(neighbors).flatMap(peer->Mono.defer(()->peers.request(peer.peerId(),TOPIC,bytes,selected.negotiation().peerTimeout()))
                        .timeout(selected.negotiation().peerTimeout())
                        .flatMap(reply->reply.length==1 && reply[0]==1?Mono.just(reply):Mono.error(new IllegalStateException("proposal not accepted")))
                        .retryWhen(Retry.fixedDelay(2,Duration.ofMillis(20))).onErrorResume(error->Mono.empty()),selected.negotiation().parallelism())
                .subscribe());
        return true;
    }
    private synchronized void finish(Attempt attempt,boolean ready) {
        if(current==attempt && lifecycle==attempt.lifecycle && settings!=null) attempt.phase=ready?Phase.READY:Phase.DONE;
    }
    private byte[] receive(long token,String sender,byte[] bytes) {
        Message received;long sessionToken;
        try {
            if(bytes==null || bytes.length>8192) return new byte[]{0};
            received=mapper.readValue(bytes,Message.class);
            synchronized(this) {
                if(subscription==null || token!=subscriptionToken || settings==null || !valid(sender,received)) return new byte[]{0};
                sessionToken=lifecycle;
                if(received.kind().equals("STATUS_REQUEST")) {
                    if(current==null) return new byte[]{0};
                    return mapper.writeValueAsBytes(message("STATUS",current.epoch,settings.leadTime()));
                }
                if(!received.kind().equals("PROPOSE")) return new byte[]{0};
                if(current!=null && received.epoch()==current.epoch) return new byte[]{1};
                long preceding=current==null?firstEpoch-1:current.epoch;
                if(received.epoch()<=preceding || current!=null && current.phase!=Phase.DONE) return new byte[]{0};
                if(received.epoch()-preceding>1) {
                    if(pendingEpoch>=0) return new byte[]{(byte)(pendingEpoch==received.epoch()?1:0)};
                    pendingEpoch=received.epoch();
                    requestStatus(sessionToken,sender,received);
                    return new byte[]{1};
                }
            }
            return new byte[]{(byte)(claim(sessionToken,received.epoch(),received.activationDelay(),false)?1:0)};
        } catch(RuntimeException invalid) {return new byte[]{0};}
    }
    private void requestStatus(long token,String sender,Message proposal) {
        var selected=settings;
        var request=message("STATUS_REQUEST",proposal.epoch(),proposal.activationDelay());
        statusRequest=Mono.defer(()->peers.request(sender,TOPIC,mapper.writeValueAsBytes(request),selected.negotiation().peerTimeout()))
                .timeout(selected.negotiation().peerTimeout()).subscribeOn(Schedulers.boundedElastic()).subscribe(bytes->{
                    try {
                        if(bytes.length>8192) throw new IllegalArgumentException("oversized status");
                        var status=mapper.readValue(bytes,Message.class);
                        synchronized(this) {
                            if(token!=lifecycle || settings==null || !valid(sender,status) || !status.kind().equals("STATUS") || status.epoch()!=proposal.epoch()) return;
                        }
                        claim(token,status.epoch(),selected.leadTime(),true);
                    } finally {clearPending(token,proposal.epoch());}
                },error->clearPending(token,proposal.epoch()));
    }
    private synchronized void clearPending(long token,long epoch) {if(token==lifecycle && pendingEpoch==epoch) pendingEpoch=-1;}
    private boolean valid(String sender,Message message) {
        if(message.schemaVersion()!=2 || !settings.sessionId().equals(message.sessionId()) || !sender.equals(message.senderId()) || message.epoch()<firstEpoch
                || peers.activeNeighbors().stream().noneMatch(peer->peer.peerId().equals(sender) && peer.incarnation().equals(message.incarnation()))) return false;
        var window=labels(settings,message.epoch());validateDelay(settings,message.activationDelay());
        return window.startsAt().equals(message.startsAt()) && window.endsAt().equals(message.endsAt());
    }
    private Message message(String kind,long epoch,Duration delay) {
        var local=peers.localEndpoint().orElseThrow();var window=labels(settings,epoch);
        return new Message(2,kind,settings.sessionId(),local.peerId(),local.incarnation(),epoch,window.startsAt(),window.endsAt(),delay);
    }
    private static OneShotOperations.Window labels(OneShotSettings settings,long epoch) {
        var from=settings.anchor().plus(settings.period().multipliedBy(epoch));
        return new OneShotOperations.Window(from,from.plus(settings.period()));
    }
    private static void validateDelay(OneShotSettings settings,Duration delay) {
        if(delay==null || delay.compareTo(settings.negotiation().auctionBudget().plus(settings.preparationBudget()))<0 || delay.compareTo(Duration.ofDays(1))>0)
            throw new IllegalArgumentException("invalid logical initiation delay");
    }
}
