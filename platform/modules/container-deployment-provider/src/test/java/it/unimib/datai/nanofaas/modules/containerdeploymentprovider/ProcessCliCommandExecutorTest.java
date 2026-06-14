package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessCliCommandExecutorTest {

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void run_timeoutReturnsFailure() {
        ProcessCliCommandExecutor executor = new ProcessCliCommandExecutor(Duration.ofMillis(100));

        ExecutionResult result = executor.run(List.of("/bin/sh", "-c", "sleep 2"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.output()).contains("Timed out");
    }
}
