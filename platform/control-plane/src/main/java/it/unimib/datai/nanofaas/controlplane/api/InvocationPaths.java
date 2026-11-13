package it.unimib.datai.nanofaas.controlplane.api;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** The two invocation routes that the ingress filters (rate limit, body limit) guard. */
final class InvocationPaths {
    private static final PathPattern INVOKE = PathPatternParser.defaultInstance.parse("/v1/functions/{name}:invoke");
    private static final PathPattern ENQUEUE = PathPatternParser.defaultInstance.parse("/v1/functions/{name}:enqueue");

    private InvocationPaths() {
    }

    static boolean matches(ServerWebExchange exchange) {
        if (!HttpMethod.POST.equals(exchange.getRequest().getMethod())) {
            return false;
        }
        PathContainer path = exchange.getRequest().getPath().pathWithinApplication();
        return INVOKE.matches(path) || ENQUEUE.matches(path);
    }
}
