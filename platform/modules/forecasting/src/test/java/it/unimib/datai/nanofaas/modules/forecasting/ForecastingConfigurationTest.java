package it.unimib.datai.nanofaas.modules.forecasting;
import it.unimib.datai.nanofaas.forecastingapi.*;
import it.unimib.datai.nanofaas.controlplane.config.RuntimeConfigExtension;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
class ForecastingConfigurationTest {
    final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ForecastingConfiguration.class));
    @Test void moduleIsInertUnlessExplicitlyEnabled() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(ForecastSource.class);
            assertThat(context).doesNotHaveBean(ForecastController.class);
        });
    }
    @Test void enabledProviderAndObserverResolveWithCoreNoOpPresent() {
        runner.withPropertyValues("nanofaas.forecasting.enabled=true", "nanofaas.forecasting.node-id=edge",
                "nanofaas.forecasting.window=10s", "nanofaas.forecasting.max-age=1m", "nanofaas.forecasting.provider=oracle")
                .withBean("coreNoOp", ExternalArrivalObserver.class, ExternalArrivalObserver::noOp)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ForecastSource.class)).isNotNull();
                    assertThat(context.getBean(ExternalArrivalObserver.class)).isNotSameAs(context.getBean("coreNoOp"));
                });
    }
    @Test void runtimeConfigNamespaceSwitchesTheProviderOfTheNextQuery() {
        runner.withPropertyValues("nanofaas.forecasting.enabled=true", "nanofaas.forecasting.node-id=edge",
                "nanofaas.forecasting.window=10s", "nanofaas.forecasting.max-age=1m", "nanofaas.forecasting.provider=holt",
                "nanofaas.forecasting.holt.trend=0.2", "nanofaas.forecasting.ari.order=3")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var namespace = context.getBean(RuntimeConfigExtension.class);
                    assertThat(namespace.snapshot()).containsEntry("provider", "holt").containsEntry("holtTrend", 0.2)
                            .containsEntry("ariOrder", 3);
                    var source = context.getBean(ForecastSource.class);
                    var now = Instant.now();
                    var query = new ForecastQuery("edge", "f", 1, now, now.plusSeconds(60));
                    assertThat(source.forecast(query).provider()).isEqualTo("holt");
                    namespace.apply(Map.of("provider", "ari"));
                    assertThat(source.forecast(query).provider()).isEqualTo("ari");
                    namespace.apply(Map.of("provider", "ewma"));
                    assertThat(source.forecast(query).provider()).isEqualTo("ewma");
                });
    }
    @Test void invalidModelParametersFailConfiguration() {
        var enabled = runner.withPropertyValues("nanofaas.forecasting.enabled=true", "nanofaas.forecasting.node-id=edge",
                "nanofaas.forecasting.window=10s", "nanofaas.forecasting.max-age=1m");
        enabled.withPropertyValues("nanofaas.forecasting.holt.damping=0").run(context -> assertThat(context).hasFailed());
        enabled.withPropertyValues("nanofaas.forecasting.history=10").run(context -> assertThat(context).hasFailed());
    }
    @Test void enablingWithoutExplicitWindowFailsConfiguration() {
        runner.withPropertyValues("nanofaas.forecasting.enabled=true", "nanofaas.forecasting.node-id=edge")
                .run(context -> assertThat(context).hasFailed());
    }
}
