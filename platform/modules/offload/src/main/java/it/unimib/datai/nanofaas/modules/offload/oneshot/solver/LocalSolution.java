package it.unimib.datai.nanofaas.modules.offload.oneshot.solver;
/** A failure contains no allocation, so a deadline can never activate a partial optimum. */
public record LocalSolution(Status status, double[] local, double[] offload, double[] rejected,
                            int[] replicas, double objective, long durationNanos, long visitedStates) {
    public enum Status { OPTIMAL, INFEASIBLE, UNSUPPORTED, SIZE_LIMIT, DEADLINE }
    public LocalSolution {
        local = local.clone(); offload = offload.clone(); rejected = rejected.clone(); replicas = replicas.clone();
    }
    @Override public double[] local() { return local.clone(); }
    @Override public double[] offload() { return offload.clone(); }
    @Override public double[] rejected() { return rejected.clone(); }
    @Override public int[] replicas() { return replicas.clone(); }
}
