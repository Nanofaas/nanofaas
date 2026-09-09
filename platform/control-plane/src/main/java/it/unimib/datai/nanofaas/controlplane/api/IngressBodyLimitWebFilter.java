package it.unimib.datai.nanofaas.controlplane.api;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.Order;
import org.springframework.core.codec.AbstractDataBufferDecoder;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.AbstractJacksonDecoder;
import org.springframework.http.codec.DecoderHttpMessageReader;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Applies a byte boundary to invocation bodies before WebFlux aggregates or parses them.
 * Declared oversized bodies are refused without subscribing to the body. Bodies without a
 * trustworthy length are counted as buffers arrive and their upstream publisher is cancelled on
 * the first buffer that crosses the boundary.
 *
 * <p>The limit is read from the actual JSON decoder configured for {@link InvocationRequest}; the
 * filter therefore shares Spring's existing finite aggregation boundary instead of publishing an
 * independent NanoFaaS default. P07e owns the final public property and calibrated default.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class IngressBodyLimitWebFilter implements WebFilter {

    private static final PathPattern INVOKE_PATTERN =
            PathPatternParser.defaultInstance.parse("/v1/functions/{name}:invoke");
    private static final PathPattern ENQUEUE_PATTERN =
            PathPatternParser.defaultInstance.parse("/v1/functions/{name}:enqueue");

    private final long maxBodyBytes;

    @Autowired
    public IngressBodyLimitWebFilter(ServerCodecConfigurer codecs) {
        this(configuredInvocationJsonLimit(codecs));
    }

    IngressBodyLimitWebFilter(long maxBodyBytes) {
        if (maxBodyBytes <= 0) {
            throw new IllegalArgumentException("maxBodyBytes must be positive");
        }
        this.maxBodyBytes = maxBodyBytes;
    }

    private static long configuredInvocationJsonLimit(ServerCodecConfigurer codecs) {
        ResolvableType requestType = ResolvableType.forClass(InvocationRequest.class);
        for (var reader : codecs.getReaders()) {
            if (!reader.canRead(requestType, MediaType.APPLICATION_JSON)
                    || !(reader instanceof DecoderHttpMessageReader<?> decoderReader)) {
                continue;
            }
            Object decoder = decoderReader.getDecoder();
            int configuredLimit;
            if (decoder instanceof AbstractJacksonDecoder<?> jacksonDecoder) {
                configuredLimit = jacksonDecoder.getMaxInMemorySize();
            } else if (decoder instanceof AbstractDataBufferDecoder<?> dataBufferDecoder) {
                configuredLimit = dataBufferDecoder.getMaxInMemorySize();
            } else {
                continue;
            }
            if (configuredLimit > 0) {
                return configuredLimit;
            }
        }
        throw new IllegalStateException(
                "Invocation JSON decoder must expose a finite max-in-memory size");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!isInvocationPath(exchange)) {
            return chain.filter(exchange);
        }

        long declaredLength = exchange.getRequest().getHeaders().getContentLength();
        if (declaredLength > maxBodyBytes) {
            return reject(exchange);
        }

        ServerHttpRequest boundedRequest = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public Flux<DataBuffer> getBody() {
                return Flux.defer(() -> {
                    long[] received = {0};
                    return super.getBody().<DataBuffer>handle((buffer, sink) -> {
                        int readableBytes = buffer.readableByteCount();
                        if (readableBytes > maxBodyBytes - received[0]) {
                            DataBufferUtils.release(buffer);
                            sink.error(PayloadTooLargeException.INSTANCE);
                            return;
                        }
                        received[0] += readableBytes;
                        sink.next(buffer);
                    });
                });
            }
        };

        return chain.filter(exchange.mutate().request(boundedRequest).build())
                .onErrorResume(PayloadTooLargeException.class, ignored -> reject(exchange));
    }

    private static Mono<Void> reject(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.CONTENT_TOO_LARGE);
        return exchange.getResponse().setComplete();
    }

    private static boolean isInvocationPath(ServerWebExchange exchange) {
        if (exchange.getRequest().getMethod() != HttpMethod.POST) {
            return false;
        }
        PathContainer path = exchange.getRequest().getPath().pathWithinApplication();
        return INVOKE_PATTERN.matches(path) || ENQUEUE_PATTERN.matches(path);
    }

    private static final class PayloadTooLargeException extends ResponseStatusException {
        private static final PayloadTooLargeException INSTANCE = new PayloadTooLargeException();

        private PayloadTooLargeException() {
            super(HttpStatus.CONTENT_TOO_LARGE,
                    "Invocation request body exceeds its byte limit");
        }
    }
}
