package it.unimib.datai.nanofaas.modules.forecasting;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;
@ConfigurationProperties("nanofaas.forecasting")
public record ForecastingProperties(Boolean enabled, String nodeId, Provider provider, Double alpha,
                                    Duration window, Duration maxAge, Integer maxFunctions) {
    public enum Provider { EWMA, ORACLE }
    public ForecastingProperties {
        enabled = Boolean.TRUE.equals(enabled);
        provider = provider == null ? Provider.EWMA : provider;
        if (alpha == null) alpha = 0.5;
        if (maxFunctions == null) maxFunctions = 1000;
        if (enabled && (nodeId == null || nodeId.isBlank() || window == null || maxAge == null))
            throw new IllegalArgumentException("forecasting requires explicit node-id, window and max-age");
    }
}
