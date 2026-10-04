package it.unimib.datai.nanofaas.modules.offload.oneshot.solver;
import java.math.BigDecimal;
/** Decimal grid conversion; only dimensional rates/demand change, utility coefficients do not. */
public final class FlowUnits {
    public record Quantity(long units, double residualRate, double solverDemandSeconds) {}
    private final BigDecimal quantum;
    public FlowUnits(double quantum) {
        if (!Double.isFinite(quantum) || quantum <= 0) throw new IllegalArgumentException("positive finite q required");
        this.quantum = BigDecimal.valueOf(quantum);
    }
    public Quantity convert(double rate, double demandSeconds, boolean exactOracle) {
        if (!Double.isFinite(rate) || rate < 0 || !Double.isFinite(demandSeconds) || demandSeconds <= 0)
            throw new IllegalArgumentException("valid load and demand required");
        try {
            BigDecimal load = BigDecimal.valueOf(rate);
            long units = load.divideToIntegralValue(quantum).longValueExact();
            if (units > 9007199254740991L) throw new IllegalArgumentException("flow units exceed exact numeric range");
            BigDecimal residual = load.subtract(quantum.multiply(BigDecimal.valueOf(units)));
            if (exactOracle && residual.signum() != 0) throw new IllegalArgumentException("oracle load is not on q grid");
            double scaledDemand = BigDecimal.valueOf(demandSeconds).multiply(quantum).doubleValue();
            if (!Double.isFinite(scaledDemand) || scaledDemand <= 0) throw new IllegalArgumentException("scaled demand out of range");
            return new Quantity(units, residual.doubleValue(), scaledDemand);
        } catch (ArithmeticException e) { throw new IllegalArgumentException("flow units out of range", e); }
    }
}
