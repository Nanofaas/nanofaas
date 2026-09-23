package it.unimib.datai.nanofaas.modules.syncqueue;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingIndex;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingStrategy;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;
import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Ports the selection logic of {@code SyncQueueService} and its retired {@code SyncScheduler}
 * loop (deleted in Task 13b, issue #208) onto the
 * {@link SchedulingIndex} contract: a single FIFO of tickets shared by every function,
 * scanned up to {@value #SCAN_LIMIT} entries per selection so a blocked head does not hide
 * ready work further back, with a bounded rotation of the same window applied in
 * {@code defer} or {@code advanceScanWindow} rather than during the scan itself.
 *
 * <p>This mirrors {@code SyncQueueService#peekReady}/{@code findReadyMatching}, which do not
 * mutate the queue, and {@code rotateReadyScanWindow}/{@code rotateReadyItem}, which apply
 * the rotation only on the failure paths in that loop's own tick. Neither
 * the scan limit nor the rotation width is tunable here.
 */
public class SharedQueueSchedulingStrategy implements SchedulingStrategy {

    /** Mirrors {@code SyncQueueService.POLL_READY_MATCHING_SCAN_LIMIT}; do not retune. */
    static final int SCAN_LIMIT = 64;

    @Override
    public String id() {
        return "shared-queue";
    }

    @Override
    public SchedulingIndex newIndex() {
        return new SharedQueueIndex();
    }

    private static final class SharedQueueIndex implements SchedulingIndex {
        private final Deque<SchedulingTicket> queue = new ArrayDeque<>();
        private final Set<TicketId> present = new HashSet<>();

        @Override
        public void add(SchedulingTicket ticket) {
            if (present.contains(ticket.id())) {
                throw new IllegalArgumentException("duplicate ticket id: " + ticket.id());
            }
            queue.addLast(ticket);
            present.add(ticket.id());
        }

        @Override
        public void remove(TicketId id) {
            if (!present.remove(id)) {
                return;
            }
            // Ids are unique (see add), so stop at the match instead of removeIf's full walk:
            // this runs under the engine's gate on every dispatch, expiry and removal.
            for (Iterator<SchedulingTicket> it = queue.iterator(); it.hasNext(); ) {
                if (it.next().id().equals(id)) {
                    it.remove();
                    return;
                }
            }
        }

        @Override
        public SchedulingTicket select(Instant now, Predicate<FunctionGeneration> runnable) {
            int visited = 0;
            for (SchedulingTicket ticket : queue) {
                if (++visited > SCAN_LIMIT) {
                    break;
                }
                if (!ticket.notBefore().isAfter(now) && runnable.test(ticket.generation())) {
                    return ticket;
                }
            }
            return null;
        }

        @Override
        public void defer(TicketId id) {
            advanceScanWindow();
        }

        @Override
        public void advanceScanWindow() {
            // Bounded scan-window rotation: move at most SCAN_LIMIT nodes from the front to
            // the back, one at a time, exactly as SyncQueueService#rotateReadyScanWindow does.
            // This is a policy-level rotation, not a lookup for `id`: the failed selection that
            // triggers it (the retired loop's nothing-in-window-can-dispatch path) rotates the
            // whole window rather than repositioning one ticket.
            int rotations = Math.min(SCAN_LIMIT, queue.size());
            for (int i = 0; i < rotations; i++) {
                queue.addLast(queue.removeFirst());
            }
        }

        @Override
        public int size() {
            return present.size();
        }

        @Override
        public void clear() {
            queue.clear();
            present.clear();
        }
    }
}
