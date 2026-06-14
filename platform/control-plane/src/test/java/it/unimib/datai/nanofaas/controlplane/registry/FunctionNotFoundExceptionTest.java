package it.unimib.datai.nanofaas.controlplane.registry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FunctionNotFoundExceptionTest {

    @Test
    void defaultConstructor_hasNoMessage() {
        FunctionNotFoundException ex = new FunctionNotFoundException();
        assertThat(ex.getMessage()).isNull();
    }

    @Test
    void functionNameConstructor_includesFunctionNameInMessage() {
        FunctionNotFoundException ex = new FunctionNotFoundException("echo");
        assertThat(ex.getMessage()).isEqualTo("Function not found: echo");
    }
}
