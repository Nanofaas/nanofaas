package it.unimib.datai.nanofaas.controlplane.capacity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** The shared generation identity (ADR 0001 §8.2): a name alone is not an identity. */
class FunctionGenerationTest {

    @Test
    void twoIncarnationsOfTheSameNameAreDifferentIdentities() {
        FunctionGeneration first = new FunctionGeneration("echo", 1);
        FunctionGeneration second = new FunctionGeneration("echo", 2);

        assertThat(first).isNotEqualTo(second)
                .isEqualTo(new FunctionGeneration("echo", 1));
        assertThat(second.supersedes(first)).isTrue();
        assertThat(first.supersedes(second)).isFalse();
        assertThat(first.supersedes(first)).isFalse();
    }

    @Test
    void generationsOfDifferentFunctionsNeverSupersedeEachOther() {
        FunctionGeneration echo = new FunctionGeneration("echo", 1);
        FunctionGeneration other = new FunctionGeneration("other", 9);

        assertThat(other.supersedes(echo)).isFalse();
        assertThat(echo.supersedes(null)).isFalse();
        assertThat(echo).isNotEqualTo(new FunctionGeneration("other", 1));
    }

    @Test
    void anIdentityWithoutANameOrAnIncarnationIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new FunctionGeneration(" ", 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new FunctionGeneration(null, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new FunctionGeneration("echo", 0));
    }

    @Test
    void theTextualFormIsDiagnosticOnly() {
        assertThat(new FunctionGeneration("echo", 7)).hasToString("echo#7");
    }
}
