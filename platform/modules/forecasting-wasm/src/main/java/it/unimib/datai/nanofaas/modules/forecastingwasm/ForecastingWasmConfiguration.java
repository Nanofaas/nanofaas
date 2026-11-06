package it.unimib.datai.nanofaas.modules.forecastingwasm;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;

@ImportRuntimeHints(ForecastingWasmRuntimeHints.class)
@AutoConfiguration(beforeName = "it.unimib.datai.nanofaas.modules.forecasting.ForecastingConfiguration")
@ConditionalOnProperty(prefix = "nanofaas.forecasting", name = "enabled", havingValue = "true")
public class ForecastingWasmConfiguration {
    @Bean(destroyMethod = "close")
    WasmHoltForecastAlgorithm wasmHoltForecastAlgorithm() { return new WasmHoltForecastAlgorithm(); }
}
