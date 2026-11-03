package it.unimib.datai.nanofaas.modules.offload.oneshot.actuation;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
class PlanActivationTest {
    @Test void immutablePlanHasHalfOpenWindowAndNoInventedCapacity() {
        var from=Instant.parse("2026-10-04T10:00:00Z");
        var functions=new java.util.HashMap<String,ActiveRoutingPlan.FunctionPlan>();
        functions.put("f",new ActiveRoutingPlan.FunctionPlan("v",3,0,0,2,2,1,1,1,java.util.List.of(),java.util.List.of()));
        var plan=new ActiveRoutingPlan("a","run",1,1,from,from.plusSeconds(300),1,functions);
        functions.clear(); assertThat(plan.functions()).hasSize(1);
        assertThat(plan.validAt(from)).isTrue(); assertThat(plan.validAt(from.plusSeconds(300))).isFalse();
        assertThat(plan.functions().get("f").localRate()).isZero();
    }
    @Test void planCoversItsForecastRoundedToTheNearestQuantum() {
        var from=Instant.parse("2026-10-04T10:00:00Z");
        // 0.24 req/s on a 0.05 grid plans 5 units, 0.01 above the forecast.
        var rounded=Map.of("f",new ActiveRoutingPlan.FunctionPlan("v",3,1,0.25,0,0.24,1,1,1,java.util.List.of(),java.util.List.of()));
        assertThat(new ActiveRoutingPlan("a","run",1,1,from,from.plusSeconds(60),0.05,rounded).functions()).hasSize(1);
        var beyond=Map.of("f",new ActiveRoutingPlan.FunctionPlan("v",3,1,0.27,0,0.24,1,1,1,java.util.List.of(),java.util.List.of()));
        assertThatThrownBy(()->new ActiveRoutingPlan("a","run",1,1,from,from.plusSeconds(60),0.05,beyond))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("infeasible ready plan");
    }
}
