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
        store = ExecutionFixtures.keys(Duration.ofMinutes(15));
        ExecutionFixtures.bind(store, "myFunction", "key123", "exec-456");

        Optional<String> result = ExecutionFixtures.boundExecutionId(store, "myFunction", "key123");

        assertThat(result).hasValue("exec-456");
    }

    @Test
    void get_withUnknownKey_returnsEmpty() {
        store = ExecutionFixtures.keys(Duration.ofMinutes(15));

        Optional<String> result = ExecutionFixtures.boundExecutionId(store, "myFunction", "unknown");

        assertThat(result).isEmpty();
    }

    @Test
    void get_withDifferentFunction_returnsEmpty() {
        store = ExecutionFixtures.keys(Duration.ofMinutes(15));
        ExecutionFixtures.bind(store, "function1", "key123", "exec-456");

        Optional<String> result = ExecutionFixtures.boundExecutionId(store, "function2", "key123");

        assertThat(result).isEmpty();
    }

    @Test
    void get_afterTtlExpired_returnsEmpty() {
        AtomicLong nanos = new AtomicLong();
        store = ExecutionFixtures.keys(Duration.ofMinutes(5), nanos::get);
        ExecutionFixtures.bind(store, "myFunction", "key123", "exec-456");

        // Verify it exists
        assertThat(ExecutionFixtures.boundExecutionId(store, "myFunction", "key123")).hasValue("exec-456");

        // Advance the ticker past the TTL
        nanos.addAndGet(Duration.ofMinutes(6).toNanos());

        // Verify it's expired
        assertThat(ExecutionFixtures.boundExecutionId(store, "myFunction", "key123")).isEmpty();
    }

    @Test
    void afterTtlExpired_acquireOrGet_returnsFreshClaim() {
        AtomicLong nanos = new AtomicLong();
        store = ExecutionFixtures.keys(Duration.ofMinutes(5), nanos::get);
        ExecutionFixtures.bind(store, "myFunction", "key123", "exec-456");

        // Advance the ticker past the TTL
        nanos.addAndGet(Duration.ofMinutes(6).toNanos());

        assertThat(ExecutionFixtures.boundExecutionId(store, "myFunction", "key123")).isEmpty();

        IdempotencyStore.AcquireResult result = store.acquireOrGet("myFunction", "key123");
        assertThat(result.state()).isEqualTo(IdempotencyStore.AcquireResult.State.CLAIMED);
    }

    @Test
    void size_returnsNumberOfEntries() {
        store = ExecutionFixtures.keys(Duration.ofMinutes(15));

        assertThat(ExecutionFixtures.keyCount(store)).isZero();

        ExecutionFixtures.bind(store, "fn1", "key1", "exec1");
        assertThat(ExecutionFixtures.keyCount(store)).isEqualTo(1);

        ExecutionFixtures.bind(store, "fn2", "key2", "exec2");
        assertThat(ExecutionFixtures.keyCount(store)).isEqualTo(2);

        ExecutionFixtures.bind(store, "fn1", "key3", "exec3");
        assertThat(ExecutionFixtures.keyCount(store)).isEqualTo(3);
    }

    @Test
    void eviction_removesExpiredEntries() {
        AtomicLong nanos = new AtomicLong();
        store = ExecutionFixtures.keys(Duration.ofMinutes(5), nanos::get);

        // Insert entries
        ExecutionFixtures.bind(store, "fn1", "key1", "exec1");
        ExecutionFixtures.bind(store, "fn2", "key2", "exec2");
        assertThat(ExecutionFixtures.keyCount(store)).isEqualTo(2);

        // Advance the ticker past the TTL
        nanos.addAndGet(Duration.ofMinutes(6).toNanos());

        assertThat(ExecutionFixtures.boundExecutionId(store, "fn1", "key1")).isEmpty();
        assertThat(ExecutionFixtures.boundExecutionId(store, "fn2", "key2")).isEmpty();
        assertThat(ExecutionFixtures.keyCount(store)).isZero();
    }

    @Test
    void multipleEntries_areIsolated() {
        store = ExecutionFixtures.keys(Duration.ofMinutes(15));

        ExecutionFixtures.bind(store, "fn1", "keyA", "exec1");
        ExecutionFixtures.bind(store, "fn1", "keyB", "exec2");
        ExecutionFixtures.bind(store, "fn2", "keyA", "exec3");

        assertThat(ExecutionFixtures.boundExecutionId(store, "fn1", "keyA")).hasValue("exec1");
        assertThat(ExecutionFixtures.boundExecutionId(store, "fn1", "keyB")).hasValue("exec2");
        assertThat(ExecutionFixtures.boundExecutionId(store, "fn2", "keyA")).hasValue("exec3");
    }
}
