package it.unimib.datai.nanofaas.controlplane.execution;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The conservative, bounded weighing and freezing of {@link OutcomeWeigher}
 * (plan task P02, finding R1).
 */
class OutcomeWeigherTest {

    private static final String ID = "exec-1234567890";

    private static Outcome outcome(Object output) {
        return new Outcome(ExecutionState.SUCCESS, 0L, 1L, output, null, null, null,
                200, 0L, false, true);
    }

    @Test
    void deepPayloadsBeyondTheTraversalLimitAreNotCacheable() {
        Object payload = "x".repeat(1024);
        for (int i = 0; i < 5; i++) {
            payload = List.of(payload);
        }

        assertThat(OutcomeWeigher.freeze(ID, outcome(payload))).isNull();
        assertThat(OutcomeWeigher.weigh(ID, outcome(payload)).cacheable()).isFalse();
    }

    @Test
    void aListOf256NullsFollowedByALargeStringIsNotCacheable() {
        List<Object> wide = new ArrayList<>(Collections.nCopies(256, null));
        wide.add("x".repeat(1024 * 1024));

        assertThat(OutcomeWeigher.freeze(ID, outcome(wide))).isNull();
        assertThat(OutcomeWeigher.weigh(ID, outcome(wide)).cacheable()).isFalse();
    }

    @Test
    void aMapWiderThanTheLimitIsNotCacheable() {
        Map<String, Object> wide = new LinkedHashMap<>();
        for (int i = 0; i < 257; i++) {
            wide.put("k" + i, "v");
        }

        assertThat(OutcomeWeigher.freeze(ID, outcome(wide))).isNull();
    }

    @Test
    void aNonLatin1StringWeighsTwoBytesPerChar() {
        int length = 64;
        long latin1 = OutcomeWeigher.weigh(ID, outcome("a".repeat(length))).weight();
        long utf16 = OutcomeWeigher.weigh(ID, outcome("€".repeat(length))).weight();

        // Same char count, but the UTF-16 string retains twice the character bytes.
        assertThat(utf16 - latin1).isEqualTo(length);
    }

    @Test
    void aCyclicStructureIsNotCacheable() {
        List<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);

        assertThat(OutcomeWeigher.freeze(ID, outcome(cyclic))).isNull();
    }

    @Test
    void aSharedReferenceIsNotCacheable() {
        List<Object> shared = List.of("v");
        List<Object> parent = new ArrayList<>();
        parent.add(shared);
        parent.add(shared);

        assertThat(OutcomeWeigher.freeze(ID, outcome(parent))).isNull();
    }

    @Test
    void aMutableOutputIsFrozenSoLaterMutationDoesNotReachTheRetainedCopy() {
        List<String> mutable = new ArrayList<>(List.of("a", "b"));

        OutcomeWeigher.FreezeResult frozen = OutcomeWeigher.freeze(ID, outcome(mutable));

        assertThat(frozen).isNotNull();
        @SuppressWarnings("unchecked")
        List<String> retained = (List<String>) frozen.outcome().output();

        mutable.add("c");
        assertThat(retained).containsExactly("a", "b");
        assertThatThrownBy(() -> retained.add("z")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void anOpaqueObjectIsNotCacheable() {
        assertThat(OutcomeWeigher.freeze(ID, outcome(new StringBuilder("x")))).isNull();
    }

    @Test
    void aByteArrayOutputIsCacheableAndFrozen() {
        byte[] bytes = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        OutcomeWeigher.FreezeResult frozen = OutcomeWeigher.freeze(ID, outcome(bytes));

        assertThat(frozen).isNotNull();
        assertThat(frozen.outcome().output()).isEqualTo(bytes);
        assertThat(frozen.outcome().output()).isNotSameAs(bytes);
    }

    @Test
    void saturatingAdditionClampsAtLongMaxValue() {
        assertThat(OutcomeWeigher.saturatingAdd(10, 20)).isEqualTo(30);
        assertThat(OutcomeWeigher.saturatingAdd(Long.MAX_VALUE - 5, 10)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void saturatingMultiplicationClampsAtLongMaxValue() {
        assertThat(OutcomeWeigher.saturatingMultiply(6, 7)).isEqualTo(42);
        assertThat(OutcomeWeigher.saturatingMultiply(1L << 40, 1L << 40)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void weightAsIntClampsWeightBeyondTheIntRange() {
        assertThat(OutcomeWeigher.weightAsInt(Long.MAX_VALUE)).isEqualTo(Integer.MAX_VALUE);
        assertThat(OutcomeWeigher.weightAsInt(0)).isEqualTo(1);
        assertThat(OutcomeWeigher.weightAsInt(12_345)).isEqualTo(12_345);
    }
}
