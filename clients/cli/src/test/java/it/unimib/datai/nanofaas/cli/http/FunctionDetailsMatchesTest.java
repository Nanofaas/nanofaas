package it.unimib.datai.nanofaas.cli.http;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.OffloadPolicy;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FunctionDetailsMatchesTest {

    private static final OffloadPolicy ALWAYS = new OffloadPolicy(null, null, "always");
    private static final OffloadPolicy PRESSURE = new OffloadPolicy(null, null, "pressure");

    private static FunctionDetails details(OffloadPolicy offload) {
        return new FunctionDetails("echo", "img", List.of(), Map.of(), null,
                5000, 1, 10, 0, null, ExecutionMode.POOL, ExecutionMode.POOL,
                null, null, RuntimeMode.HTTP, null, null, null, offload, null);
    }

    private static FunctionSpec spec(OffloadPolicy offload) {
        return new FunctionSpec("echo", "img", List.of(), Map.of(), null,
                5000, 1, 10, 0, null, ExecutionMode.POOL, RuntimeMode.HTTP, null, null, null, offload);
    }

    @Test
    void offloadPolicyChangeAloneTriggersMismatch() {
        // fn apply with a changed offload block must re-register, not silently no-op
        assertThat(details(ALWAYS).matches(spec(PRESSURE))).isFalse();
        assertThat(details(null).matches(spec(ALWAYS))).isFalse();
    }

    @Test
    void identicalOffloadPolicyMatches() {
        assertThat(details(ALWAYS).matches(spec(ALWAYS))).isTrue();
    }

    @Test
    void manifestWithoutOffloadBlockDoesNotForceReRegister() {
        // unspecified in the manifest = keep whatever is registered
        assertThat(details(ALWAYS).matches(spec(null))).isTrue();
    }
}
