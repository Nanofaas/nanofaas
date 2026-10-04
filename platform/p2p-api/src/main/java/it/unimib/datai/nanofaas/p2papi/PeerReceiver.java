package it.unimib.datai.nanofaas.p2papi;

import reactor.core.publisher.Mono;
@FunctionalInterface
public interface PeerReceiver { Mono<byte[]> onMessage(String senderId, byte[] payload); }
