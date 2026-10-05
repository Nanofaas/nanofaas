package it.unimib.datai.nanofaas.modules.offload.oneshot.solver;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
class LocalReplicaSolverTest {
    final LocalReplicaSolver solver = new LocalReplicaSolver();
    final SolveLimits limits = SolveLimits.forDuration(Duration.ofSeconds(2));
    LocalProblem.Function row(String id, double load, long memory) {
        return new LocalProblem.Function(id, load, 0.1, 1, memory, 2, 1, 0.1, 0, 0, 0, 0);
    }
    @Test void exactMemoryCompressionAndDeterministicTieKeepFirstFunction() {
        var problem = new LocalProblem(LocalProblem.Model.LSP, 128, List.of(row("a", 10, 128), row("b", 10, 128)));
        var result = solver.solve(problem, limits);
        assertThat(result.status()).isEqualTo(LocalSolution.Status.OPTIMAL);
        assertThat(result.replicas()).containsExactly(1, 0);
        assertThat(result.local()).containsExactly(10, 0);
        var exposed = result.local(); exposed[0] = 99;
        assertThat(result.local()).containsExactly(10, 0);
    }
    @Test void zeroLoadRequiresNoReplicaAndRestrictedInboundCannotExceedRam() {
        assertThat(solver.solve(new LocalProblem(LocalProblem.Model.LSP, 0, List.of(row("f", 0, 1))), limits).replicas()).containsExactly(0);
        var fixed = new LocalProblem.Function("f", 10, 0.1, 1, 128, 2, 1, 0.1, 0, 0, 0, 11);
        assertThat(solver.solve(new LocalProblem(LocalProblem.Model.LSPr_x, 128, List.of(fixed)), limits).status()).isEqualTo(LocalSolution.Status.INFEASIBLE);
    }
    @Test void invalidNumericInputsAndLimitsNeverProduceAnOptimalResult() {
        var valid = new LocalProblem(LocalProblem.Model.LSP, 10, List.of(row("f", 10, 1)));
        assertThat(solver.solve(valid, new SolveLimits(1, 100000, Long.MAX_VALUE)).status()).isEqualTo(LocalSolution.Status.SIZE_LIMIT);
        assertThat(solver.solve(valid, new SolveLimits(2000000, 1, Long.MAX_VALUE)).status()).isEqualTo(LocalSolution.Status.SIZE_LIMIT);
        assertThat(solver.solve(valid, new SolveLimits(2000000, 100000, System.nanoTime() - 1)).status()).isEqualTo(LocalSolution.Status.DEADLINE);
        assertThat(solver.solve(new LocalProblem(LocalProblem.Model.LSP, 10, List.of(row("f", Double.NaN, 1))), limits).status()).isEqualTo(LocalSolution.Status.UNSUPPORTED);
        assertThat(solver.solve(new LocalProblem(LocalProblem.Model.LSP, Long.MAX_VALUE, List.of(row("f", 10, 1))), limits).status()).isEqualTo(LocalSolution.Status.SIZE_LIMIT);
    }
    @Test void flowGridScalesDemandAndSeparatesOnlyEwmaRemainder() {
        var units = new FlowUnits(0.5).convert(3, 0.1, true);
        assertThat(units.units()).isEqualTo(6);
        assertThat(units.solverDemandSeconds()).isEqualTo(0.05);
        assertThat(new FlowUnits(0.1).convert(0.3, 0.1, true).units()).isEqualTo(3);
        assertThat(new FlowUnits(0.1).convert(0.31, 0.1, false).residualRate()).isCloseTo(0.01, org.assertj.core.data.Offset.offset(1e-12));
        assertThatThrownBy(() -> new FlowUnits(0.1).convert(0.31, 0.1, true)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void deadlineReachedDuringWorkNeverReturnsPartialOptimalAllocation() {
        var ticks = new java.util.concurrent.atomic.AtomicLong();
        var timed = new LocalReplicaSolver(ticks::getAndIncrement);
        var problem = new LocalProblem(LocalProblem.Model.LSP, 100, List.of(row("a", 100, 1), row("b", 100, 1)));
        assertThat(timed.solve(problem, new SolveLimits(2000000, 1000000, 20)).status()).isEqualTo(LocalSolution.Status.DEADLINE);
    }
    @Test void smallProblemsMatchIndependentExhaustiveObjective() {
        var random = new java.util.Random(238);
        for (int trial = 0; trial < 100; trial++) {
            int first = random.nextInt(9), second = random.nextInt(9), ram = random.nextInt(7);
            var a = new LocalProblem.Function("a", first, 0.5, 1, 2, random.nextInt(4), random.nextInt(3), 0.1, random.nextInt(3), 0, 0, 0);
            var b = new LocalProblem.Function("b", second, 1, 1, 1, random.nextInt(4), random.nextInt(3), 0.1, random.nextInt(3), 0, 0, 0);
            double best = Double.POSITIVE_INFINITY;
            for (int x = 0; x <= first; x++) for (int y = 0; y <= second; y++) {
                if (Math.ceil(x * 0.5) * 2 + y > ram) continue;
                for (int outA = 0; outA <= first - x; outA++) for (int outB = 0; outB <= second - y; outB++) {
                    double costA = -(a.alpha() * x + (a.delta() - a.price()) * outA - a.gamma() * (first - x - outA)) / (first == 0 ? 1 : first);
                    double costB = -(b.alpha() * y + (b.delta() - b.price()) * outB - b.gamma() * (second - y - outB)) / (second == 0 ? 1 : second);
                    best = Math.min(best, costA + costB);
                }
            }
            var result = solver.solve(new LocalProblem(LocalProblem.Model.LSP, ram, List.of(a, b)), SolveLimits.forDuration(Duration.ofSeconds(1)));
            assertThat(result.status()).isEqualTo(LocalSolution.Status.OPTIMAL);
            assertThat(result.objective()).isCloseTo(best, org.assertj.core.data.Offset.offset(1e-9));
        }
    }
    @Test void arithmeticOverflowIsUnsupportedRatherThanInfeasible() {
        var a = new LocalProblem.Function("a", 1, 1, 1, 1, Double.MAX_VALUE * 0.6, 0, 0, 0, 0, 0, 0);
        var b = new LocalProblem.Function("b", 1, 1, 1, 1, Double.MAX_VALUE * 0.6, 0, 0, 0, 0, 0, 0);
        assertThat(solver.solve(new LocalProblem(LocalProblem.Model.LSP, 2, List.of(a, b)), limits).status()).isEqualTo(LocalSolution.Status.UNSUPPORTED);
    }
    @Test void flowUnitTransformationPreservesNormalizedWelfareAndPhysicalReplicas() {
        var original = solver.solve(new LocalProblem(LocalProblem.Model.LSP, 1, List.of(row("f", 3, 1))), limits);
        var grid = new FlowUnits(0.5).convert(3, 0.1, true);
        var scaled = new LocalProblem.Function("f", grid.units(), grid.solverDemandSeconds(), 1, 1, 2, 1, 0.1, 0, 0, 0, 0);
        var converted = solver.solve(new LocalProblem(LocalProblem.Model.LSP, 1, List.of(scaled)), limits);
        assertThat(converted.objective()).isEqualTo(original.objective());
        assertThat(converted.replicas()).containsExactly(original.replicas());
        assertThat(converted.local()[0] * 0.5).isEqualTo(original.local()[0]);
    }
    @Test void deadlineAtResultPublicationCannotReturnOptimal() {
        var problem = new LocalProblem(LocalProblem.Model.LSP, 2, List.of(row("f", 10, 1)));
        var probe = new java.util.concurrent.atomic.AtomicLong();
        new LocalReplicaSolver(probe::getAndIncrement).solve(problem, new SolveLimits(2000000, 1000000, Long.MAX_VALUE));
        var ticks = new java.util.concurrent.atomic.AtomicLong();
        var result = new LocalReplicaSolver(ticks::getAndIncrement).solve(problem, new SolveLimits(2000000, 1000000, probe.get() - 1));
        assertThat(result.status()).isEqualTo(LocalSolution.Status.DEADLINE);
        assertThat(result.local()).isEmpty();
    }
    @Test void unsafeCapacityAndFractionalFixedFlowsCannotPublishAllocation() {
        for (var f : List.of(
                new LocalProblem.Function("f",1,1e-12,1,1,1,1,0,0,1,0,0),
                new LocalProblem.Function("f",1,1,1,1,1,1,0,0,0,.5,0),
                new LocalProblem.Function("f",1,1,1,1,1,1,0,0,0,0,.5),
                new LocalProblem.Function("f",1,1,2,1,1,1,0,0,0,0,0))) {
            var result=solver.solve(new LocalProblem(LocalProblem.Model.LSPr_x,1,List.of(f)),limits);
            assertThat(result.status()).isEqualTo(LocalSolution.Status.UNSUPPORTED);
            assertThat(result.replicas()).isEmpty();
        }
    }
}
