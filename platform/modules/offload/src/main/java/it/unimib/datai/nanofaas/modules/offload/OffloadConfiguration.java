package it.unimib.datai.nanofaas.modules.offload;

import io.micrometer.core.instrument.MeterRegistry;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableConfigurationProperties(OffloadProperties.class)
public class OffloadConfiguration {
    private static final Logger log = LoggerFactory.getLogger(OffloadConfiguration.class);

    @Bean
    OffloadGateway moduleOffloadGateway(OffloadProperties properties,
                                        ObjectProvider<WebClient> webClient,
                                        ObjectProvider<MeterRegistry> meterRegistry) {
        if (properties.enabled() && !properties.hasTarget()) {
            log.warn("Offload module loaded without nanofaas.offload.target-url; "
                    + "only functions declaring their own offload.targetUrl can offload");
        }
        return new DefaultOffloadGateway(properties, webClient::getObject, meterRegistry::getObject);
    }
}
