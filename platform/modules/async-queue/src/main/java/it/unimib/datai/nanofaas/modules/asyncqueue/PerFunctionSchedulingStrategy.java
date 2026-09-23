package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingIndex;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingStrategy;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;
import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Ports the selection logic of the retired {@code modules.asyncqueue.Scheduler} loop onto the
 * {@link SchedulingIndex} contract (that loop was deleted in Task 13b, issue #208):
 * one FIFO of tickets per function, visited round-robin, with a bounded number of
 * consecutive dispatches per function turn before moving on to the next active function.
 *
 * <p>Each function with pending work takes up to {@value #DEFAULT_MAX_BATCH_PER_FUNCTION}
 * consecutive tickets from its own FIFO before the next active function gets a turn. The
 * function-name queue is {@code activeOrder} and the per-function FIFO is a
 * {@link SchedulingTicket} deque; the turn advances in {@code remove}, since {@code select}
 * must not mutate anything (see the {@link SchedulingIndex} contract).
 *
 * <p>Batch size is not tunable here: preserving the measured value is this class's whole
 * point (that loop's own {@code DEFAULT_MAX_BATCH_PER_FUNCTION}, recorded here).
 */
public class PerFunctionSchedulingStrategy implements SchedulingStrategy {

    /** The retired loop's {@code DEFAULT_MAX_BATCH_PER_FUNCTION}, preserved; do not retune. */
    static final int DEFAULT_MAX_BATCH_PER_FUNCTION = 2;

    @Override
    public String id() {
        return "per-function";
    }

    @Override
    public SchedulingIndex newIndex() {
        return new PerFunctionIndex();
    }

    private static final class PerFunctionIndex implements SchedulingIndex {
        private final Map<String, Deque<SchedulingTicket>> byFunction = new HashMap<>();
        /** Round-robin order of functions that currently hold at least one ticket. */
        private final Deque<String> activeOrder = new ArrayDeque<>();
        private final Map<TicketId, String> ownerByTicket = new HashMap<>();

        /** Function currently mid-turn, and how many of its tickets have been removed so far. */
        private String turnFunction;
        private int turnCount;

        @Override
        public void add(SchedulingTicket ticket) {
            if (ownerByTicket.containsKey(ticket.id())) {
                throw new IllegalArgumentException("duplicate ticket id: " + ticket.id());
            }
            String functionName = ticket.generation().functionName();
            Deque<SchedulingTicket> fifo = byFunction.computeIfAbsent(functionName, name -> new ArrayDeque<>());
            boolean becomesActive = fifo.isEmpty();
            fifo.addLast(ticket);
            ownerByTicket.put(ticket.id(), functionName);
            if (becomesActive) {
                activeOrder.addLast(functionName);
            }
        }

        @Override
        public void remove(TicketId id) {
            String functionName = ownerByTicket.remove(id);
            if (functionName == null) {
                return;
            }
            Deque<SchedulingTicket> fifo = byFunction.get(functionName);
            if (fifo == null || !removeFromFifo(fifo, id)) {
                return;
            }
            if (functionName.equals(turnFunction)) {
                turnCount++;
            } else {
                turnFunction = functionName;
                turnCount = 1;
            }
            boolean drained = fifo.isEmpty();
            if (drained) {
                byFunction.remove(functionName);
            }
            if (drained || turnCount >= DEFAULT_MAX_BATCH_PER_FUNCTION) {
                activeOrder.remove(functionName);
                if (!drained) {
                    activeOrder.addLast(functionName);
                }
                turnFunction = null;
                turnCount = 0;
            }
        }

        /**
         * Scans every active function in one call, skipping any whose head ticket is not
         * runnable right now, rather than surfacing only the front function the way the old
         * {@code Scheduler.processFunction} did (it handles exactly one function per
         * invocation and, on a blocked lease, ends the visit and drops the function from
         * {@code activeFunctions} until an external event re-signals it).
         *
         * <p>That difference is intentional, not a gap: {@link SchedulingIndex} is a passive,
         * thread-free structure by contract (no callbacks, no wake-ups — see the type-level
         * javadoc), so a function that transiently cannot dispatch is simply "not runnable
         * now" from here; the drop-and-wait-for-a-wake-event behaviour belongs to the engine
         * that owns the wake sequence (a later task), not to the index. It does not change
         * the resulting selection order: a function skipped every scan until it unblocks never
         * changes which ticket is returned for another function in between, because this
         * index's own turn/rotation bookkeeping (in {@code remove}) is unaffected by scans
         * that find it not runnable. Verified against the real {@code Scheduler} with a
         * corpus covering publish, a function blocked then unblocked mid-stream, dispatches
         * interleaved across three functions and a standalone removal — see
         * {@code PerFunctionSchedulingStrategyTraceComparisonTest}: the two traces are
         * identical.
         */
        @Override
        public SchedulingTicket select(Instant now, Predicate<FunctionGeneration> runnable) {
            for (String functionName : activeOrder) {
                Deque<SchedulingTicket> fifo = byFunction.get(functionName);
                if (fifo == null || fifo.isEmpty()) {
                    continue;
                }
                SchedulingTicket head = fifo.peekFirst();
                if (!head.notBefore().isAfter(now) && runnable.test(head.generation())) {
                    return head;
                }
            }
            return null;
        }

        @Override
        public void defer(TicketId id) {
            String functionName = ownerByTicket.get(id);
            if (functionName == null) {
                return;
            }
            Deque<SchedulingTicket> fifo = byFunction.get(functionName);
            if (fifo == null) {
                return;
            }
            SchedulingTicket found = removeAndReturnFromFifo(fifo, id);
            if (found != null) {
                fifo.addLast(found);
            }
        }

        @Override
        public int size() {
            return ownerByTicket.size();
        }

        @Override
        public void clear() {
            byFunction.clear();
            activeOrder.clear();
            ownerByTicket.clear();
            turnFunction = null;
            turnCount = 0;
        }

        private static boolean removeFromFifo(Deque<SchedulingTicket> fifo, TicketId id) {
            return removeAndReturnFromFifo(fifo, id) != null;
        }

        private static SchedulingTicket removeAndReturnFromFifo(Deque<SchedulingTicket> fifo, TicketId id) {
            Iterator<SchedulingTicket> iterator = fifo.iterator();
            while (iterator.hasNext()) {
                SchedulingTicket candidate = iterator.next();
                if (candidate.id().equals(id)) {
                    iterator.remove();
                    return candidate;
                }
            }
            return null;
        }
    }
}
