package it.unimib.datai.nanofaas.modules.syncqueue;

import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueService;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsBinder;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SyncQueueConfigurationTest {
    @Test
    void syncQueueLifecycleListener_removesFunctionState() {
        SyncQueueService syncQueueService = mock(SyncQueueService.class);
        SyncQueueConfiguration configuration = new SyncQueueConfiguration();
        FunctionRegistrationListener listener = configuration.syncQueueLifecycleListener(
                syncQueueService, mock(WorkloadMetricsBinder.class));

        listener.onRegister(new it.unimib.datai.nanofaas.common.model.FunctionSpec(
                "echo", "image", null, java.util.Map.of(), null, 1000, 1, 1, 3, null,
                it.unimib.datai.nanofaas.common.model.ExecutionMode.LOCAL, null, null, null
        ));
        listener.onRemove("echo");

        verify(syncQueueService).registerFunction("echo", 1);
        verify(syncQueueService).removeFunctionState("echo");
    }
}
