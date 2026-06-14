package it.unimib.datai.nanofaas.sdk.autoconfigure;

import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import it.unimib.datai.nanofaas.sdk.runtime.RuntimeSettings;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class NanofaasAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(NanofaasAutoConfiguration.class)
                    .withBean("testHandler", FunctionHandler.class, () -> request -> "ok");

    @Test
    void runtimeSettings_bindsAndNormalizesEnvironmentValues() {
        contextRunner
                .withPropertyValues(
                        "EXECUTION_ID=exec-123",
                        "TRACE_ID=trace-456",
                        "CALLBACK_URL=http://callback",
                        "FUNCTION_HANDLER=testHandler")
                .run(context -> {
                    RuntimeSettings runtimeSettings = context.getBean(RuntimeSettings.class);
                    assertThat(runtimeSettings.executionId()).isEqualTo("exec-123");
                    assertThat(runtimeSettings.traceId()).isEqualTo("trace-456");
                    assertThat(runtimeSettings.callbackUrl()).isEqualTo("http://callback");
                    assertThat(runtimeSettings.functionHandler()).isEqualTo("testHandler");
                });
    }

    @Test
    void runtimeSettings_convertsBlankValuesToNull() {
        contextRunner
                .withPropertyValues(
                        "EXECUTION_ID= ",
                        "TRACE_ID=",
                        "CALLBACK_URL=\t",
                        "FUNCTION_HANDLER=")
                .run(context -> {
                    RuntimeSettings runtimeSettings = context.getBean(RuntimeSettings.class);
                    assertThat(runtimeSettings.executionId()).isNull();
                    assertThat(runtimeSettings.traceId()).isNull();
                    assertThat(runtimeSettings.callbackUrl()).isNull();
                    assertThat(runtimeSettings.functionHandler()).isNull();
                });
    }
}
