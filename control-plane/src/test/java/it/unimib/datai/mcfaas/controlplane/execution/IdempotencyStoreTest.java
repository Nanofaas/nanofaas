package it.unimib.datai.mcfaas.controlplane.execution;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyStoreTest {

    private IdempotencyStore store;

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.shutdown();
        }
    }

    @Test
    void put_andGet_returnsStoredExecutionId() {
        store = new IdempotencyStore(Duration.ofMinutes(15));
        store.put("myFunction", "key123", "exec-456");

        Optional<String> result = store.getExecutionId("myFunction", "key123");

        assertThat(result).hasValue("exec-456");
    }

    @Test
    void get_withUnknownKey_returnsEmpty() {
        store = new IdempotencyStore(Duration.ofMinutes(15));

        Optional<String> result = store.getExecutionId("myFunction", "unknown");

        assertThat(result).isEmpty();
    }

    @Test
    void get_withDifferentFunction_returnsEmpty() {
        store = new IdempotencyStore(Duration.ofMinutes(15));
        store.put("function1", "key123", "exec-456");

        Optional<String> result = store.getExecutionId("function2", "key123");

        assertThat(result).isEmpty();
    }

    @Test
    void get_afterTtlExpired_returnsEmpty() throws InterruptedException {
        // Use very short TTL for testing
        store = new IdempotencyStore(Duration.ofMillis(100));
        store.put("myFunction", "key123", "exec-456");

        // Verify it exists
        assertThat(store.getExecutionId("myFunction", "key123")).hasValue("exec-456");

        // Wait for TTL to expire
        Thread.sleep(150);

        // Verify it's expired
        assertThat(store.getExecutionId("myFunction", "key123")).isEmpty();
    }

    @Test
    void size_returnsNumberOfEntries() {
        store = new IdempotencyStore(Duration.ofMinutes(15));

        assertThat(store.size()).isEqualTo(0);

        store.put("fn1", "key1", "exec1");
        assertThat(store.size()).isEqualTo(1);

        store.put("fn2", "key2", "exec2");
        assertThat(store.size()).isEqualTo(2);

        store.put("fn1", "key3", "exec3");
        assertThat(store.size()).isEqualTo(3);
    }

    @Test
    void eviction_removesExpiredEntries() throws InterruptedException {
        store = new IdempotencyStore(Duration.ofMillis(50));

        // Insert entries
        store.put("fn1", "key1", "exec1");
        store.put("fn2", "key2", "exec2");
        assertThat(store.size()).isEqualTo(2);

        // Wait for TTL + eviction cycle (janitor runs every minute, so we trigger via get)
        Thread.sleep(100);

        // Trigger eviction via get (which checks TTL)
        store.getExecutionId("fn1", "key1");
        store.getExecutionId("fn2", "key2");

        // Size might still show 2 until janitor runs, but get returns empty
        assertThat(store.getExecutionId("fn1", "key1")).isEmpty();
        assertThat(store.getExecutionId("fn2", "key2")).isEmpty();
    }

    @Test
    void put_overwritesExistingKey() {
        store = new IdempotencyStore(Duration.ofMinutes(15));

        store.put("myFunction", "key123", "exec-1");
        assertThat(store.getExecutionId("myFunction", "key123")).hasValue("exec-1");

        store.put("myFunction", "key123", "exec-2");
        assertThat(store.getExecutionId("myFunction", "key123")).hasValue("exec-2");
    }

    @Test
    void multipleEntries_areIsolated() {
        store = new IdempotencyStore(Duration.ofMinutes(15));

        store.put("fn1", "keyA", "exec1");
        store.put("fn1", "keyB", "exec2");
        store.put("fn2", "keyA", "exec3");

        assertThat(store.getExecutionId("fn1", "keyA")).hasValue("exec1");
        assertThat(store.getExecutionId("fn1", "keyB")).hasValue("exec2");
        assertThat(store.getExecutionId("fn2", "keyA")).hasValue("exec3");
    }
}
