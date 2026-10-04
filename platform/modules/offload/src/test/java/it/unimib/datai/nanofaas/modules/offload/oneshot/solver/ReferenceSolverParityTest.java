package it.unimib.datai.nanofaas.modules.offload.oneshot.solver;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.*;
class ReferenceSolverParityTest {
    @Test void frozenPythonFixturesMatchObjectiveAndEveryDecisionIncludingTies() throws Exception {
        var stream = getClass().getResourceAsStream("/one-shot/reference/local-problems.json");
        assertThat(stream).isNotNull();
        var fixtures = new JsonMapper().readTree(stream);
        var solver = new LocalReplicaSolver();
        int checked = 0;
        for (var fixture : fixtures) {
            var input = fixture.get("input");
            var rows = new ArrayList<LocalProblem.Function>();
            for (var f : input.get("functions")) rows.add(new LocalProblem.Function(f.get("id").asText(),
                    f.get("load").asDouble(), f.get("demandSeconds").asDouble(), f.get("utilization").asDouble(),
                    f.get("memoryMiB").asLong(), f.get("alpha").asDouble(), f.get("delta").asDouble(),
                    f.get("gamma").asDouble(), f.get("price").asDouble(), f.get("fixedLocal").asDouble(),
                    f.get("fixedOffload").asDouble(), f.get("inbound").asDouble()));
            var problem = new LocalProblem(LocalProblem.Model.valueOf(input.get("model").asText()), input.get("memoryCapacityMiB").asLong(), rows);
            var result = solver.solve(problem, SolveLimits.forDuration(Duration.ofSeconds(2)));
            var expected = fixture.get("expected");
            assertThat(result.status().name()).as(input.get("id").asText()).isEqualTo(expected.get("status").asText());
            if (result.status() == LocalSolution.Status.OPTIMAL) {
                assertThat(result.objective()).isCloseTo(expected.get("objective").asDouble(), org.assertj.core.data.Offset.offset(1e-9));
                for (int i = 0; i < rows.size(); i++) {
                    assertThat(result.local()[i]).isEqualTo(expected.get("local").get(i).asDouble());
                    assertThat(result.replicas()[i]).isEqualTo(expected.get("replicas").get(i).asInt());
                    assertThat(result.rejected()[i]).isEqualTo(expected.get("rejected").get(i).asDouble());
                    if (expected.has("offload")) assertThat(result.offload()[i]).isEqualTo(expected.get("offload").get(i).asDouble());
                    else assertThat(result.offload()[i]).isEqualTo(rows.get(i).fixedOffload());
                }
            }
            checked++;
        }
        assertThat(checked).isEqualTo(80);
    }
}
