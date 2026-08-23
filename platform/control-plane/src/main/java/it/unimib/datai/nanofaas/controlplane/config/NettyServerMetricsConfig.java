package it.unimib.datai.nanofaas.controlplane.config;

import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publish what happens to a request before the application sees it.
 *
 * <p>Everything this platform measures starts once a request has been read,
 * routed and handed to a handler. Under overload most of a caller's wait happens
 * earlier: at the peak of the comparison profile a rejected request took 675 ms
 * to receive its 429, while the control plane measured 5.25 ms of queue wait and
 * 1.30 ms of service - so 668 ms passed somewhere no meter was looking, in the
 * accept queue and in Netty's pending reads.
 *
 * <p>`connections.active` is that backlog. Without it "the queue is 20 deep"
 * reads as the system's whole backlog, when it is a small buffer downstream of a
 * much larger one.
 *
 * <p>The URI tag is collapsed to a constant on purpose: this platform's paths
 * carry a function name, and tagging by URI would add a time series per
 * registered function to every connection meter.
 */
@Configuration
public class NettyServerMetricsConfig {

    @Bean
    public WebServerFactoryCustomizer<NettyReactiveWebServerFactory> nettyServerMetricsCustomizer() {
        return factory -> factory.addServerCustomizers(
                server -> server.metrics(true, uri -> "nanofaas"));
    }
}
