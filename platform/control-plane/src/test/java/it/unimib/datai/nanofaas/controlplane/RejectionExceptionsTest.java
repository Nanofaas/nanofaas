package it.unimib.datai.nanofaas.controlplane;

import it.unimib.datai.nanofaas.controlplane.queue.QueueFullException;
import it.unimib.datai.nanofaas.controlplane.service.RateLimitException;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectReason;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectedException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rejection is a control-flow signal, not a fault: each of these becomes a 429 and
 * nobody reads the stack. Under overload they are thrown more often than requests
 * are served - 590/s against 299 dispatches at the peak of the comparison profile -
 * and every fill-in walks a Reactor stack tens of frames deep.
 */
class RejectionExceptionsTest {

    @Test
    void rejectionsCarryNoStackTrace() {
        assertThat(new QueueFullException().getStackTrace()).isEmpty();
        assertThat(new RateLimitException().getStackTrace()).isEmpty();
        assertThat(new SyncQueueRejectedException(SyncQueueRejectReason.DEPTH, 1).getStackTrace()).isEmpty();
    }

    @Test
    void theSyncRejectionStillCarriesWhatTheResponseNeeds() {
        SyncQueueRejectedException rejected = new SyncQueueRejectedException(SyncQueueRejectReason.EST_WAIT, 7);

        assertThat(rejected.reason()).isEqualTo(SyncQueueRejectReason.EST_WAIT);
        assertThat(rejected.retryAfterSeconds()).isEqualTo(7);
    }
}
