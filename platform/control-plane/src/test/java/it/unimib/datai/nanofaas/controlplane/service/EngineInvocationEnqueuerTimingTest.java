package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.capacity.*;
import it.unimib.datai.nanofaas.controlplane.execution.*;
import it.unimib.datai.nanofaas.controlplane.scheduler.*;
import it.unimib.datai.nanofaas.execution.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import java.time.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class EngineInvocationEnqueuerTimingTest {
    private final Instant now = Instant.parse("2026-09-24T12:00:00Z");
    private final SchedulerEngine engine = mock(SchedulerEngine.class);
    private final FunctionCapacityRegistry capacity = new FunctionCapacityRegistry();
    private final ExecutionStore executions = new ExecutionStore();
    private final FunctionSpec spec = new FunctionSpec("fn", "image", null, null, null,
            30000, 1, 10, 3, null, ExecutionMode.LOCAL, null, null, null);
    private final InvocationTask task = new InvocationTask("e1", "fn", spec,
            new InvocationRequest("in", null), null, null, now, 2, InvocationKind.ASYNC);

    private EngineInvocationEnqueuer enqueuer() {
        return enqueuer(EngineInvocationEnqueuer.AdmissionProfile.FUNCTION_QUEUE);
    }

    @SuppressWarnings("unchecked")
    private EngineInvocationEnqueuer enqueuer(EngineInvocationEnqueuer.AdmissionProfile profile) {
        ObjectProvider<SchedulerEngine> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(engine);
        capacity.register("fn", 1);
        executions.put(new ExecutionRecord("e1", task));
        return new EngineInvocationEnqueuer(provider, capacity, () -> 1L,
                profile, true,
                mock(ObjectProvider.class), Clock.fixed(now, ZoneOffset.UTC), executions);
    }

    @Test
    void retryPreservesDueTimeAndAdmissionClockAndCap() {
        var enqueuer = enqueuer();
        when(engine.enqueue(any(), anyInt())).thenReturn(true);
        assertThat(enqueuer.enqueue(task, now.plusSeconds(1), () -> {})).isTrue();
        ArgumentCaptor<PendingEntry> entry = ArgumentCaptor.forClass(PendingEntry.class);
        verify(engine).enqueue(entry.capture(), eq(10));
        assertThat(entry.getValue().ticket().notBefore()).isEqualTo(now.plusSeconds(1));
        assertThat(entry.getValue().ticket().enqueuedAt()).isEqualTo(now);
    }

    @Test
    void terminalDuringInsertionRemovesPublishedTicket() {
        var enqueuer = enqueuer();
        when(engine.enqueue(any(), anyInt())).thenAnswer(call -> {
            executions.getOrNull("e1").markSuccess("done");
            return true;
        });
        assertThat(enqueuer.enqueue(task, now.plusSeconds(1), () -> {})).isFalse();
        verify(engine).remove(new TicketId("e1", 2));
    }

    @Test
    void terminalBeforeInsertionRefusesWithoutPublishing() {
        var enqueuer = enqueuer();
        executions.getOrNull("e1").markSuccess("done");
        assertThat(enqueuer.enqueue(task, now.plusSeconds(1), () -> {})).isFalse();
        verify(engine, never()).enqueue(any(), anyInt());
    }
    @Test
    void initialAdmissionUsesTheInjectedClock() {
        var enqueuer = enqueuer();
        when(engine.enqueue(any(), anyInt())).thenReturn(true);
        assertThat(enqueuer.enqueue(task)).isTrue();
        var entry = ArgumentCaptor.forClass(PendingEntry.class);
        verify(engine).enqueue(entry.capture(), eq(10));
        assertThat(entry.getValue().ticket().notBefore()).isEqualTo(now);
        assertThat(entry.getValue().ticket().enqueuedAt()).isEqualTo(now);
    }

    @Test
    void syncWithoutGatewayNeverFallsBackIntoFunctionQueue() {
        var enqueuer = enqueuer(EngineInvocationEnqueuer.AdmissionProfile.SYNC_QUEUE);
        assertThat(enqueuer.enqueue(task, now.plusSeconds(1), () -> {})).isFalse();
        verifyNoInteractions(engine);
    }

    @Test
    void wrongAttemptIsRefusedBeforePublication() {
        var enqueuer = enqueuer();
        var stale = new InvocationTask("e1", "fn", spec, task.request(), null, null,
                now, 1, InvocationKind.ASYNC);
        assertThat(enqueuer.enqueue(stale, now.plusSeconds(1), () -> {})).isFalse();
        verifyNoInteractions(engine);
    }

}
