package it.unimib.datai.nanofaas.modules.asyncqueue;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class QueueBackedEnqueuerTest {

    @Test
    void decrementInFlight_isNoOp() {
        QueueManager queueManager = mock(QueueManager.class);
        QueueBackedEnqueuer enqueuer = new QueueBackedEnqueuer(queueManager);

        enqueuer.decrementInFlight("fn");

        verifyNoInteractions(queueManager);
    }

    @Test
    void releaseSlot_delegatesToQueueManager() {
        QueueManager queueManager = mock(QueueManager.class);
        QueueBackedEnqueuer enqueuer = new QueueBackedEnqueuer(queueManager);

        enqueuer.releaseSlot("fn");

        verify(queueManager).releaseSlot("fn");
    }
}
