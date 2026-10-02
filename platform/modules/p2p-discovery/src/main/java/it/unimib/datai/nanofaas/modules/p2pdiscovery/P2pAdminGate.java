package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Hides {@code /v1/admin/p2p/**} unless both nanofaas.p2p.enabled and nanofaas.p2p.admin.enabled are true.
 * Done per request, not with @ConditionalOnProperty: that is evaluated when Spring AOT generates the native
 * image, which would bake the build-time value into the binary.
 */
public class P2pAdminGate implements WebFilter {
    private static final String PREFIX = "/v1/admin/p2p";

    private final boolean open;

    public P2pAdminGate(P2pProperties props) {
        this.open = props.enabled() && props.admin().enabled();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!open && exchange.getRequest().getPath().value().startsWith(PREFIX)) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }
}
