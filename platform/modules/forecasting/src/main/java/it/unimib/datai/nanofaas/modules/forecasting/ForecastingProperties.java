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
        alpha = alpha == null ? 0.5 : alpha;
        maxFunctions = maxFunctions == null ? 1000 : maxFunctions;
        if (enabled && (nodeId == null || nodeId.isBlank() || window == null || maxAge == null))
            throw new IllegalArgumentException("forecasting requires explicit node-id, window and max-age");
    }
}
