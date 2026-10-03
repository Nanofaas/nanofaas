package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ImageInventoryCommandTest {
    private final ImageInventoryCommand command = new ImageInventoryCommand();
    @Test void drainsPipesAndRejectsOverflowWithoutHanging() {
        assertThatThrownBy(() -> command.run(List.of("/bin/sh", "-c", "yes image"), Duration.ofSeconds(1), 1024))
                .isInstanceOf(IllegalStateException.class);
        assertThat(command.run(List.of("/bin/sh", "-c", "printf '[]'; printf 'diagnostic' >&2"), Duration.ofSeconds(1), 1024))
                .isEqualTo("[]");
    }
    @Test void enforcesDeadlineAndNonzeroExit() {
        long start = System.nanoTime();
        assertThatThrownBy(() -> command.run(List.of("/bin/sh", "-c", "sleep 20"), Duration.ofMillis(100), 1024))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        assertThatThrownBy(() -> command.run(List.of("/bin/sh", "-c", "printf '[]'; exit 2"), Duration.ofSeconds(1), 1024))
                .isInstanceOf(IllegalStateException.class);
    }
}
