package it.unimib.datai.nanofaas.p2papi;

import java.time.Duration;
import java.util.List;
import reactor.core.publisher.Mono;
public interface PeerTransport {
    List<PeerEndpoint> activeNeighbors();
    Mono<byte[]> request(String peerId, String topic, byte[] payload, Duration timeout);
    PeerSubscription subscribe(String topic, PeerReceiver receiver);
}
