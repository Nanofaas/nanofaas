package it.unimib.datai.nanofaas.modules.forecasting;

import it.unimib.datai.nanofaas.forecastingapi.*;
import it.unimib.datai.nanofaas.controlplane.config.RuntimeConfigExtension;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionCatalogView;
import it.unimib.datai.nanofaas.controlplane.registry.ManagedReplicaControl;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import java.time.Clock;
import java.util.List;

@AutoConfiguration
@EnableConfigurationProperties(ForecastingProperties.class)
@ConditionalOnProperty(prefix = "nanofaas.forecasting", name = "enabled", havingValue = "true")
public class ForecastingConfiguration {
    @Bean ForecastSelection forecastSelection(ForecastingProperties props) { return ForecastSelection.from(props); }
    @Bean EwmaForecastSource ewmaForecastSource(ForecastingProperties props) {
        return new EwmaForecastSource(props.nodeId(), props.alpha(), props.window(), props.maxAge(), props.maxFunctions(), Clock.systemUTC());
    }
    @Bean TrendForecastSource trendForecastSource(ForecastingProperties props, ForecastSelection selection) {
        return new TrendForecastSource(props.nodeId(), props.window(), props.maxAge(), props.maxFunctions(), props.history(),
                () -> selection.get().trendModel(), Clock.systemUTC());
    }
    @Bean OracleForecastStore oracleForecastStore(ForecastingProperties props) {
        return new OracleForecastStore(Clock.systemUTC(), props.maxAge());
    }
    // Read per query, so a runtime-config switch applies to the next one.
    @Bean @Primary ForecastSource selectedForecastSource(ForecastingProperties props, ForecastSelection selection,
            EwmaForecastSource ewma, TrendForecastSource trend, OracleForecastStore oracle) {
        if(!props.enabled()) return q -> new ForecastSnapshot(q,ForecastSnapshot.Status.MISSING,null,0,"disabled",null);
        return q -> switch (selection.get().provider()) {
            case EWMA -> ewma.forecast(q);
            case ORACLE -> oracle.forecast(q);
            case HOLT, ARI -> trend.forecast(q);
        };
    }
    // Every windowed source observes whatever is selected, so a switch finds it already warm.
    @Bean @Primary ExternalArrivalObserver forecastExternalArrivalObserver(ForecastingProperties props,EwmaForecastSource ewma,TrendForecastSource trend) {
        return props.enabled() ? arrival -> { ewma.record(arrival); trend.record(arrival); } : ExternalArrivalObserver.noOp();
    }
    @Bean ForecastController forecastController(ForecastingProperties props,OracleForecastStore oracle) { return new ForecastController(oracle,props.enabled()); }
    @Bean ForecastObservationLifecycle forecastObservationLifecycle(ForecastingProperties props,EwmaForecastSource ewma,TrendForecastSource trend,
            ObjectProvider<FunctionCatalogView> catalog, ObjectProvider<ManagedReplicaControl> replicas) {
        return new ForecastObservationLifecycle(List.of(ewma, trend), catalog.getIfAvailable(), replicas.getIfAvailable(), Clock.systemUTC(),props.enabled());
    }
    /** Served by the runtime-config module's admin API when that module is built in. */
    @Bean RuntimeConfigExtension forecastingRuntimeConfigExtension(ForecastingProperties props, ForecastSelection selection) {
        return new ForecastingRuntimeConfigExtension(selection, props.history());
    }
}
