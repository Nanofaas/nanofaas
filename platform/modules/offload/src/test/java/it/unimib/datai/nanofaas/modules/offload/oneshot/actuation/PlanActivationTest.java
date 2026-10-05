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
}
