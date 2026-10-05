package it.unimib.datai.nanofaas.modules.offload.oneshot.solver;

import java.util.Arrays;

/** Multi-choice integer knapsack with exact GCD compression and stable reference tie order. */
final class ReplicaCostDp {
    record Result(int[] choices, double objective) {}
    static Result minimize(double[][] costs, long[] memory, long ram, long gcd, LocalReplicaSolver.Work work) {
        int budget = Math.toIntExact(ram / gcd);
        double[] previous = new double[budget + 1]; // Zero initializes every slack budget.
        double[] next = new double[budget + 1];
        int[][] choices = new int[costs.length][budget + 1];
        for (int f = 0; f < costs.length; f++) {
            Arrays.fill(next, Double.POSITIVE_INFINITY);
            Arrays.fill(choices[f], -1);
            long weight = memory[f] / gcd;
            for (int b = 0; b <= budget; b++) {
                work.checkDeadline();
                int max = (int) Math.min(costs[f].length - 1L, b / weight);
                for (int r = 0; r <= max; r++) {
                    work.visited++;
                    double prior = previous[b - (int) (r * weight)];
                    double candidate = prior + costs[f][r];
                    if (Double.isFinite(prior) && Double.isFinite(costs[f][r]) && !Double.isFinite(candidate))
                        throw new IllegalArgumentException("objective sum exceeds finite range");
                    if (candidate < next[b]) { next[b] = candidate; choices[f][b] = r; }
                }
            }
            double[] swap = previous; previous = next; next = swap;
        }
        int best = 0;
        for (int b = 1; b <= budget; b++) if (previous[b] < previous[best]) best = b;
        if (!Double.isFinite(previous[best])) return null;
        double objective = previous[best];
        int[] selected = new int[costs.length];
        for (int f = costs.length - 1; f >= 0; f--) {
            selected[f] = choices[f][best];
            if (selected[f] < 0) return null;
            best -= (int) (selected[f] * (memory[f] / gcd));
        }
        work.checkDeadline();
        return new Result(selected, objective);
    }
}
