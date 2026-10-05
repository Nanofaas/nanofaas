package it.unimib.datai.nanofaas.modules.offload.oneshot.routing;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
class PlannedRoutingTest {
    @Test void quotaBurstIsFiniteAndTimeCannotMintExtraTokens() {
        var time=new AtomicLong(100);
        var quota=new AssignmentAdmission(2,1,time::get);
        assertThat(quota.tryAdmit()).isTrue();assertThat(quota.tryAdmit()).isFalse();
        time.addAndGet(250000000);assertThat(quota.tryAdmit()).isFalse();
        time.addAndGet(250000000);assertThat(quota.tryAdmit()).isTrue();
        time.set(0);assertThat(quota.tryAdmit()).isFalse();
    }
    @Test void weightedSelectionIsDeterministicAndConservesShares() {
        var weights=new java.util.LinkedHashMap<String,Double>();weights.put("local",1.0);weights.put("b",2.0);weights.put("cloud",1.0);
        var picker=new PlanRouter.WeightedPicker(weights);
        var counts=new java.util.HashMap<String,Integer>();for(int i=0;i<400;i++) counts.merge(picker.next(),1,Integer::sum);
        assertThat(counts).containsEntry("local",100).containsEntry("b",200).containsEntry("cloud",100);
    }
}
