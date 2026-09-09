package it.unimib.datai.nanofaas.controlplane.api;

import io.netty.buffer.PooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.core.io.buffer.PooledDataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class IngressBodyLimitWebFilterTest {

    private static final NettyDataBufferFactory BUFFERS =
            new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT);

    @Test
    void crossingBufferCancelsTheServerBodySubscriptionAndReleasesReceivedBuffers() {
        DataBuffer accepted = BUFFERS.wrap(new byte[]{1, 2, 3});
        DataBuffer crossing = BUFFERS.wrap(new byte[]{4, 5});
        AtomicBoolean cancelled = new AtomicBoolean();
        Flux<DataBuffer> body = Flux.concat(Flux.just(accepted, crossing), Flux.never())
                .doOnCancel(() -> cancelled.set(true));
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/functions/echo:invoke").body(body));
        IngressBodyLimitWebFilter filter = new IngressBodyLimitWebFilter(4);
        WebFilterChain decoder = boundedExchange -> DataBufferUtils
                .join(boundedExchange.getRequest().getBody())
                .then();

        StepVerifier.create(filter.filter(exchange, decoder)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(cancelled).isTrue();
        assertThat(((PooledDataBuffer) accepted).isAllocated()).isFalse();
        assertThat(((PooledDataBuffer) crossing).isAllocated()).isFalse();
    }

    @Test
    void declaredOversizeIsRejectedWithoutSubscribingToTheBody() {
        AtomicBoolean subscribed = new AtomicBoolean();
        Flux<DataBuffer> body = Flux.defer(() -> {
            subscribed.set(true);
            return Flux.just(BUFFERS.wrap(new byte[]{1, 2, 3, 4, 5}));
        });
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/functions/echo:invoke")
                        .header("Content-Length", "5")
                        .body(body));
        IngressBodyLimitWebFilter filter = new IngressBodyLimitWebFilter(4);
        WebFilterChain chain = ignored -> Mono.error(new AssertionError("chain must not run"));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(subscribed).isFalse();
    }

    @Test
    void configuredJsonCodecLimitDrivesThePreParsingDeclaredLengthBoundary() {
        ServerCodecConfigurer codecs = ServerCodecConfigurer.create();
        codecs.defaultCodecs().maxInMemorySize(4);
        IngressBodyLimitWebFilter filter = new IngressBodyLimitWebFilter(codecs);
        AtomicBoolean subscribed = new AtomicBoolean();
        Flux<DataBuffer> body = Flux.defer(() -> {
            subscribed.set(true);
            return Flux.just(BUFFERS.wrap(new byte[]{1, 2, 3, 4, 5}));
        });
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/functions/echo:invoke")
                        .header("Content-Length", "5")
                        .body(body));

        StepVerifier.create(filter.filter(exchange, ignored -> Mono.empty())).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(subscribed).isFalse();
    }
}
