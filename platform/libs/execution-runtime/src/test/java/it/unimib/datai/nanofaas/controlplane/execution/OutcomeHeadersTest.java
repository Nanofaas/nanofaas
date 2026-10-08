package it.unimib.datai.nanofaas.controlplane.execution;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class OutcomeHeadersTest {
    @Test void archivedReplayHeadersAreIsolatedFromMutableHandlerMaps() {
        var headers=new HashMap<String,String>();headers.put("Content-Type","application/json");
        var outcome=new Outcome(ExecutionState.SUCCESS,1,2,null,null,headers,null,200,0,false,true,"edge");
        headers.put("Content-Type","text/plain");
        assertThat(outcome.headers()).containsEntry("Content-Type","application/json");
        assertThatThrownBy(()->outcome.headers().put("extra","injected")).isInstanceOf(UnsupportedOperationException.class);
    }
}
