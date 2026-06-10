package it.unimib.datai.nanofaas.controlplane.execution;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyStoreTest {

    private IdempotencyStore store;

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
    void get_afterTtlExpired_returnsEmpty() {
        AtomicLong nanos = new AtomicLong();
        store = new IdempotencyStore(Duration.ofMinutes(5), nanos::get);
        store.put("myFunction", "key123", "exec-456");

        // Verify it exists
        assertThat(store.getExecutionId("myFunction", "key123")).hasValue("exec-456");

        // Advance the ticker past the TTL
        nanos.addAndGet(Duration.ofMinutes(6).toNanos());

        // Verify it's expired
        assertThat(store.getExecutionId("myFunction", "key123")).isEmpty();
    }

    @Test
    void afterTtlExpired_acquireOrGet_returnsFreshClaim() {
        AtomicLong nanos = new AtomicLong();
        store = new IdempotencyStore(Duration.ofMinutes(5), nanos::get);
        store.put("myFunction", "key123", "exec-456");

        // Advance the ticker past the TTL
        nanos.addAndGet(Duration.ofMinutes(6).toNanos());

        assertThat(store.getExecutionId("myFunction", "key123")).isEmpty();

        IdempotencyStore.AcquireResult result = store.acquireOrGet("myFunction", "key123");
        assertThat(result.state()).isEqualTo(IdempotencyStore.AcquireResult.State.CLAIMED);
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
    void eviction_removesExpiredEntries() {
        AtomicLong nanos = new AtomicLong();
        store = new IdempotencyStore(Duration.ofMinutes(5), nanos::get);

        // Insert entries
        store.put("fn1", "key1", "exec1");
        store.put("fn2", "key2", "exec2");
        assertThat(store.size()).isEqualTo(2);

        // Advance the ticker past the TTL
        nanos.addAndGet(Duration.ofMinutes(6).toNanos());

        assertThat(store.getExecutionId("fn1", "key1")).isEmpty();
        assertThat(store.getExecutionId("fn2", "key2")).isEmpty();
        assertThat(store.size()).isEqualTo(0);
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
