package it.unimib.datai.nanofaas.modules.forecasting;

import it.unimib.datai.nanofaas.forecastingapi.*;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionCatalogView;
import it.unimib.datai.nanofaas.controlplane.registry.ManagedReplicaControl;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import java.time.Clock;

@AutoConfiguration
@EnableConfigurationProperties(ForecastingProperties.class)
@ConditionalOnProperty(prefix = "nanofaas.forecasting", name = "enabled", havingValue = "true")
public class ForecastingConfiguration {
    @Bean EwmaForecastSource ewmaForecastSource(ForecastingProperties props) {
        return new EwmaForecastSource(props.nodeId(), props.alpha(), props.window(), props.maxAge(), props.maxFunctions(), Clock.systemUTC());
    }
    @Bean OracleForecastStore oracleForecastStore(ForecastingProperties props) {
        return new OracleForecastStore(Clock.systemUTC(), props.maxAge());
    }
    @Bean @Primary ForecastSource selectedForecastSource(ForecastingProperties props, EwmaForecastSource ewma, OracleForecastStore oracle) {
        if(!props.enabled()) return q -> new ForecastSnapshot(q,ForecastSnapshot.Status.MISSING,null,0,"disabled",null);
        return props.provider() == ForecastingProperties.Provider.ORACLE ? oracle::forecast : ewma::forecast;
    }
    @Bean @Primary ExternalArrivalObserver forecastExternalArrivalObserver(ForecastingProperties props,EwmaForecastSource ewma) { return props.enabled()?ewma::record:ExternalArrivalObserver.noOp(); }
    @Bean ForecastController forecastController(ForecastingProperties props,OracleForecastStore oracle) { return new ForecastController(oracle,props.enabled()); }
    @Bean ForecastObservationLifecycle forecastObservationLifecycle(ForecastingProperties props,EwmaForecastSource ewma,
            ObjectProvider<FunctionCatalogView> catalog, ObjectProvider<ManagedReplicaControl> replicas) {
        return new ForecastObservationLifecycle(ewma, catalog.getIfAvailable(), replicas.getIfAvailable(), Clock.systemUTC(),props.enabled());
    }
}
