package it.unimib.datai.nanofaas.modules.forecasting;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;
import java.util.Locale;
@ConfigurationProperties("nanofaas.forecasting")
public record ForecastingProperties(Boolean enabled, String nodeId, Provider provider, Double alpha,
                                    Duration window, Duration maxAge, Integer maxFunctions,
                                    Holt holt, Ari ari, Integer history) {
    public enum Provider {
        EWMA, ORACLE, HOLT, ARI;
        public String id() { return name().toLowerCase(Locale.ROOT); }
    }
    /** Initial Holt weights; the runtime-config namespace can replace them. */
    public record Holt(Double level, Double trend, Double damping) {
        public Holt {
            if (level == null) level = 0.4;
            if (trend == null) trend = 0.4;
            if (damping == null) damping = 0.95;
        }
    }
    /** Initial ARI order; the runtime-config namespace can replace it. */
    public record Ari(Integer order) {
        public Ari { if (order == null) order = 6; }
    }
    public ForecastingProperties {
        enabled = Boolean.TRUE.equals(enabled);
        provider = provider == null ? Provider.EWMA : provider;
        if (alpha == null) alpha = 0.5;
        if (maxFunctions == null) maxFunctions = 1000;
        if (holt == null) holt = new Holt(null, null, null);
        if (ari == null) ari = new Ari(null);
        if (history == null) history = 360;
        if (enabled && (nodeId == null || nodeId.isBlank() || window == null || maxAge == null))
            throw new IllegalArgumentException("forecasting requires explicit node-id, window and max-age");
    }
}
