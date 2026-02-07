package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionStoreEvictionTest {

    private ExecutionStore store = new ExecutionStore();

    @AfterEach
    void tearDown() {
        store.shutdown();
    }

    @Test
    void eviction_doesNotRemoveRunningExecution() throws Exception {
        ExecutionRecord record = createRecord("exec-running");
        record.markRunning();
        store.put(record);

        // Backdate the createdAt to simulate TTL expiry
        backdateEntry("exec-running", Instant.now().minus(Duration.ofMinutes(20)));

        // Trigger eviction
        invokeEvictExpired();

        // RUNNING record should NOT be evicted
        assertThat(store.get("exec-running")).isPresent();
    }

    @Test
    void eviction_doesNotRemoveQueuedExecution() throws Exception {
        ExecutionRecord record = createRecord("exec-queued");
        store.put(record);
        assertThat(record.state()).isEqualTo(ExecutionState.QUEUED);

        backdateEntry("exec-queued", Instant.now().minus(Duration.ofMinutes(20)));

        invokeEvictExpired();

        assertThat(store.get("exec-queued")).isPresent();
    }

    @Test
    void eviction_removesCompletedExecution() throws Exception {
        ExecutionRecord record = createRecord("exec-done");
        record.markRunning();
        record.markSuccess("result");
        store.put(record);

        backdateEntry("exec-done", Instant.now().minus(Duration.ofMinutes(20)));

        invokeEvictExpired();

        assertThat(store.get("exec-done")).isEmpty();
    }

    @Test
    void eviction_removesErrorExecution() throws Exception {
        ExecutionRecord record = createRecord("exec-err");
        record.markRunning();
        record.markError(new it.unimib.datai.nanofaas.common.model.ErrorInfo("ERR", "failed"));
        store.put(record);

        backdateEntry("exec-err", Instant.now().minus(Duration.ofMinutes(20)));

        invokeEvictExpired();

        assertThat(store.get("exec-err")).isEmpty();
    }

    @Test
    void eviction_removesTimedOutExecution() throws Exception {
        ExecutionRecord record = createRecord("exec-timeout");
        record.markRunning();
        record.markTimeout();
        store.put(record);

        backdateEntry("exec-timeout", Instant.now().minus(Duration.ofMinutes(20)));

        invokeEvictExpired();

        assertThat(store.get("exec-timeout")).isEmpty();
    }

    @Test
    void eviction_doesNotRemoveRecentExecution() throws Exception {
        ExecutionRecord record = createRecord("exec-recent");
        record.markRunning();
        record.markSuccess("result");
        store.put(record);

        // Don't backdate - should not be evicted even though completed
        invokeEvictExpired();

        assertThat(store.get("exec-recent")).isPresent();
    }

    @Test
    void remove_deletesExecution() {
        ExecutionRecord record = createRecord("exec-to-remove");
        store.put(record);
        assertThat(store.get("exec-to-remove")).isPresent();

        store.remove("exec-to-remove");
        assertThat(store.get("exec-to-remove")).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private void backdateEntry(String executionId, Instant createdAt) throws Exception {
        Field executionsField = ExecutionStore.class.getDeclaredField("executions");
        executionsField.setAccessible(true);
        Map<String, Object> executions = (Map<String, Object>) executionsField.get(store);

        Object storedExecution = executions.get(executionId);
        // StoredExecution is a record - need to recreate with new createdAt
        Field recordField = storedExecution.getClass().getDeclaredField("record");
        recordField.setAccessible(true);
        ExecutionRecord record = (ExecutionRecord) recordField.get(storedExecution);

        // Create new StoredExecution with backdated createdAt via the record's constructor
        Class<?> storedClass = storedExecution.getClass();
        var ctor = storedClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object newStored = ctor.newInstance(record, createdAt);
        executions.put(executionId, newStored);
    }

    private void invokeEvictExpired() throws Exception {
        Method method = ExecutionStore.class.getDeclaredMethod("evictExpired");
        method.setAccessible(true);
        method.invoke(store);
    }

    private ExecutionRecord createRecord(String executionId) {
        InvocationTask task = new InvocationTask(executionId, "testFunc", null, null, null, null, null, 1);
        return new ExecutionRecord(executionId, task);
    }
}
