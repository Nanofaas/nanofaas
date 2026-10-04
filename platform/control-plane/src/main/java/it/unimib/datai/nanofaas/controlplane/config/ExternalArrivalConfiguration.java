package it.unimib.datai.nanofaas.controlplane.config;
import it.unimib.datai.nanofaas.forecastingapi.ExternalArrivalObserver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration(proxyBeanMethods = false)
public class ExternalArrivalConfiguration {
    @Bean @ConditionalOnMissingBean(ExternalArrivalObserver.class)
    ExternalArrivalObserver externalArrivalObserver() { return ExternalArrivalObserver.noOp(); }
}
