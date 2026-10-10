package it.unimib.datai.nanofaas.sdk.runtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;

/** Explicit opt-in only; a host logging configuration always takes precedence. */
public final class LoggingEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getProperty("NANOFAAS_LOG_FORMAT") != null && environment.getProperty("logging.config") == null) {
            environment.getPropertySources().addLast(new MapPropertySource("nanofaasLogging", Map.of(
                    "logging.config", "classpath:nanofaas-logback.xml")));
        }
    }
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
}
