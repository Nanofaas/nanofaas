package it.unimib.datai.nanofaas.modules.forecasting;
import it.unimib.datai.nanofaas.forecastingapi.*;
import org.junit.jupiter.api.Test;
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
    @Test void enablingWithoutExplicitWindowFailsConfiguration() {
        runner.withPropertyValues("nanofaas.forecasting.enabled=true", "nanofaas.forecasting.node-id=edge")
                .run(context -> assertThat(context).hasFailed());
    }
}
