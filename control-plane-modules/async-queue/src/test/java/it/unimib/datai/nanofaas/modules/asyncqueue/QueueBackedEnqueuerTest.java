package it.unimib.datai.nanofaas.modules.asyncqueue;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class QueueBackedEnqueuerTest {

    @Test
    void releaseDispatchSlot_delegatesToQueueManager() {
        QueueManager queueManager = mock(QueueManager.class);
        QueueBackedEnqueuer enqueuer = new QueueBackedEnqueuer(queueManager);

        enqueuer.releaseDispatchSlot("fn");

        verify(queueManager).releaseSlot("fn");
    }
}
