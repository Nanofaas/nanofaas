package it.unimib.datai.nanofaas.modules.offload.oneshot.solver;

import java.util.*;
import java.util.function.LongSupplier;
import static it.unimib.datai.nanofaas.modules.offload.oneshot.solver.LocalSolution.Status.*;

/** Exact base LSP/LSPr_x port of the pinned optimizer. No MILP fallback or local search. */
public final class LocalReplicaSolver {
    private static final double EPS = 1e-9;
    private final LongSupplier nanos;
    public LocalReplicaSolver() { this(System::nanoTime); }
    public LocalReplicaSolver(LongSupplier nanos) { this.nanos = Objects.requireNonNull(nanos); }
    static final class Deadline extends RuntimeException {}
    static final class Work {
        final LongSupplier nanos;
        final long deadline;
        long visited;
        Work(LongSupplier nanos, long deadline) { this.nanos = nanos; this.deadline = deadline; }
        void checkDeadline() { if (nanos.getAsLong() - deadline >= 0) throw new Deadline(); }
    }
    private record Curve(double[] cost, double[] local, double[] outbound, double[] rejected) {}
    public LocalSolution solve(LocalProblem problem, SolveLimits limits) {
        long started = nanos.getAsLong();
        Work work = new Work(nanos, limits.deadlineNanos());
        try {
            work.checkDeadline();
            if (problem != null && problem.functions() != null && problem.functions().size() > limits.maxBytes() / 320)
                return failure(SIZE_LIMIT, started, work);
            if (!valid(problem)) return failure(UNSUPPORTED, started, work);
            int nf = problem.functions().size();
            if (nf > limits.maxBytes() / 64) return failure(SIZE_LIMIT, started, work);
            if (problem.model() == LocalProblem.Model.LSPr_x) return fixed(problem, limits, started, work);
            long gcd = 0;
            for (var f : problem.functions()) gcd = gcd(gcd, f.memoryMiB());
            long budget = problem.memoryCapacityMiB() / gcd;
            if (budget >= Integer.MAX_VALUE) return failure(SIZE_LIMIT, started, work);
            int[] levels = new int[nf];
            long sumLevels = 0;
            for (int i = 0; i < nf; i++) {
                work.checkDeadline();
                var f = problem.functions().get(i);
                double hi = Math.min(problem.memoryCapacityMiB() / f.memoryMiB(), Math.ceil(f.load() / capacity(f)) + 1);
                if (hi >= Integer.MAX_VALUE) return failure(SIZE_LIMIT, started, work);
                levels[i] = (int) hi + 1;
                sumLevels = Math.addExact(sumLevels, levels[i]);
            }
            long product = Math.multiplyExact(budget + 1, sumLevels);
            long bytes = Math.addExact(Math.multiplyExact(budget + 1, Math.addExact(16, Math.multiplyExact(4L, nf))),
                    Math.addExact(Math.multiplyExact(48, sumLevels), Math.addExact(512, Math.multiplyExact(320L, nf))));
            if (product > limits.maxStateLevelProduct() || bytes > limits.maxBytes()) return failure(SIZE_LIMIT, started, work);
            Curve[] curves = new Curve[nf];
            double[][] costs = new double[nf][];
            long[] memory = new long[nf];
            for (int i = 0; i < nf; i++) {
                curves[i] = curve(problem.functions().get(i), levels[i], work);
                costs[i] = curves[i].cost(); memory[i] = problem.functions().get(i).memoryMiB();
            }
            var optimum = ReplicaCostDp.minimize(costs, memory, problem.memoryCapacityMiB(), gcd, work);
            if (optimum == null) return failure(INFEASIBLE, started, work);
            double[] x = new double[nf], omega = new double[nf], z = new double[nf];
            for (int i = 0; i < nf; i++) {
                int r = optimum.choices()[i]; x[i] = curves[i].local()[r]; omega[i] = curves[i].outbound()[r]; z[i] = curves[i].rejected()[r];
            }
            work.checkDeadline();
            var result = new LocalSolution(OPTIMAL, x, omega, z, optimum.choices(), optimum.objective(), nanos.getAsLong() - started, work.visited);
            work.checkDeadline();
            return result;
        } catch (Deadline e) { return failure(DEADLINE, started, work); }
        catch (ArithmeticException e) { return failure(SIZE_LIMIT, started, work); }
        catch (IllegalArgumentException e) { return failure(UNSUPPORTED, started, work); }
    }
    private Curve curve(LocalProblem.Function f, int count, Work work) {
        double[] cost = new double[count], x = new double[count], omega = new double[count], z = new double[count];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        double capacity = capacity(f);
        for (int r = 0; r < count; r++) {
            work.checkDeadline();
            double lo = Math.max(0, Math.ceil((r - 1) * capacity - EPS));
            double hi = Math.min(Math.floor(f.load() + EPS), Math.floor(r * capacity + EPS));
            // The uncapped base LSP has only its two linear extrema and the x=0 breakpoint.
            for (double local : new double[]{hi, lo}) {
                if (local < lo || local > hi) continue;
                double out = f.delta() - f.price() + f.gamma() > 0 ? f.load() - local : 0;
                double rejected = f.load() - local - out;
                double candidate = objective(f, local, out, rejected, f.price());
                if (candidate < cost[r]) { cost[r] = candidate; x[r] = local; omega[r] = out; z[r] = rejected; }
            }
        }
        return new Curve(cost, x, omega, z);
    }
    private LocalSolution fixed(LocalProblem p, SolveLimits limits, long started, Work work) {
        int nf = p.functions().size();
        if (nf > limits.maxStateLevelProduct() || 128L * nf + 512 > limits.maxBytes()) return failure(SIZE_LIMIT, started, work);
        double[] x = new double[nf], omega = new double[nf], z = new double[nf];
        int[] replicas = new int[nf];
        long remaining = p.memoryCapacityMiB();
        double objective = 0;
        for (int i = 0; i < nf; i++) {
            work.checkDeadline(); work.visited++;
            var f = p.functions().get(i);
            if (!integer(f.fixedLocal()) || f.fixedLocal() < 0 || !integer(f.fixedOffload()) || !integer(f.inbound()) || f.fixedOffload() < 0 || f.fixedOffload() > f.load())
                return failure(UNSUPPORTED, started, work);
            x[i] = f.fixedLocal(); omega[i] = f.fixedOffload(); z[i] = f.load() - x[i] - omega[i];
            if (z[i] < -EPS) return failure(INFEASIBLE, started, work);
            double r = Math.max(0, Math.ceil((x[i] + f.inbound()) / capacity(f) - EPS));
            if (r > Integer.MAX_VALUE) return failure(SIZE_LIMIT, started, work);
            replicas[i] = (int) r;
            if (replicas[i] > remaining / f.memoryMiB()) return failure(INFEASIBLE, started, work);
            remaining -= replicas[i] * f.memoryMiB();
            objective += objective(f, x[i], omega[i], z[i], 0);
        }
        if (!Double.isFinite(objective)) throw new IllegalArgumentException("objective exceeds finite range");
        work.checkDeadline();
        var result = new LocalSolution(OPTIMAL, x, omega, z, replicas, objective, nanos.getAsLong() - started, work.visited);
        work.checkDeadline();
        return result;
    }
    private static double objective(LocalProblem.Function f, double local, double outbound, double rejected, double price) {
        double cost = -(f.alpha() * local + (f.delta() - price) * outbound - f.gamma() * rejected) / (f.load() == 0 ? 1 : f.load());
        if (!Double.isFinite(cost)) throw new IllegalArgumentException("objective exceeds finite range");
        return cost;
    }
    private static boolean valid(LocalProblem p) {
        if (p == null || p.model() == null || p.functions() == null || p.functions().isEmpty() || p.memoryCapacityMiB() < 0) return false;
        Set<String> names = new HashSet<>();
        for (var f : p.functions()) {
            if (f.id() == null || f.id().isBlank() || !names.add(f.id()) || f.memoryMiB() < 1 || f.load() < 0
                    || !integer(f.load()) || !Double.isFinite(f.demandSeconds()) || f.demandSeconds() <= 0
                    || !Double.isFinite(f.utilization()) || f.utilization() <= 0 || f.utilization() > 1 || !Double.isFinite(capacity(f)) || capacity(f) <= 0 || capacity(f) >= 1 / EPS) return false;
            for (double value : new double[]{f.alpha(), f.delta(), f.gamma(), f.price(), f.fixedLocal(), f.fixedOffload(), f.inbound()})
                if (!Double.isFinite(value) || value < 0) return false;
        }
        return true;
    }
    private static boolean integer(double x) { return Double.isFinite(x) && x <= 9007199254740991L && x == Math.rint(x); }
    private static double capacity(LocalProblem.Function f) { return f.utilization() / f.demandSeconds(); }
    private static long gcd(long a, long b) { while (b != 0) { long rest = a % b; a = b; b = rest; } return a; }
    private LocalSolution failure(LocalSolution.Status status, long started, Work work) {
        return new LocalSolution(status, new double[0], new double[0], new double[0], new int[0], Double.NaN, Math.max(0, nanos.getAsLong() - started), work.visited);
    }
}
