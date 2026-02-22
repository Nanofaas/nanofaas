package it.unimib.datai.nanofaas.controlplane.execution;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyStorePutIfAbsentTest {

    private IdempotencyStore store;

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.shutdown();
        }
    }

    @Test
    void putIfAbsent_newKey_returnsNull() {
        store = new IdempotencyStore(Duration.ofMinutes(15));

        String result = store.putIfAbsent("fn", "key1", "exec-1");

        assertThat(result).isNull();
        assertThat(store.getExecutionId("fn", "key1")).hasValue("exec-1");
    }

    @Test
    void putIfAbsent_existingKey_returnsExistingExecutionId() {
        store = new IdempotencyStore(Duration.ofMinutes(15));

        store.putIfAbsent("fn", "key1", "exec-1");
        String result = store.putIfAbsent("fn", "key1", "exec-2");

        assertThat(result).isEqualTo("exec-1");
        // Original value unchanged
        assertThat(store.getExecutionId("fn", "key1")).hasValue("exec-1");
    }

    @Test
    void putIfAbsent_expiredKey_replacesAndReturnsNull() throws InterruptedException {
        store = new IdempotencyStore(Duration.ofMillis(100));

        store.putIfAbsent("fn", "key1", "exec-1");
        assertThat(store.getExecutionId("fn", "key1")).hasValue("exec-1");

        // Wait for TTL to expire
        Thread.sleep(150);

        // Should replace expired entry
        String result = store.putIfAbsent("fn", "key1", "exec-2");
        assertThat(result).isNull();
        assertThat(store.getExecutionId("fn", "key1")).hasValue("exec-2");
    }

    @Test
    void putIfAbsent_differentFunctions_areIndependent() {
        store = new IdempotencyStore(Duration.ofMinutes(15));

        String r1 = store.putIfAbsent("fn1", "key1", "exec-1");
        String r2 = store.putIfAbsent("fn2", "key1", "exec-2");

        assertThat(r1).isNull();
        assertThat(r2).isNull();
        assertThat(store.getExecutionId("fn1", "key1")).hasValue("exec-1");
        assertThat(store.getExecutionId("fn2", "key1")).hasValue("exec-2");
    }
}
