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
 * Ports the selection logic of {@link Scheduler} onto the {@link SchedulingIndex} contract:
 * one FIFO of tickets per function, visited round-robin, with a bounded number of
 * consecutive dispatches per function turn before moving on to the next active function.
 *
 * <p>This mirrors the existing scheduler exactly: {@code Scheduler} takes function names off
 * a work-signal queue (coalesced while pending) and drains up to
 * {@value #DEFAULT_MAX_BATCH_PER_FUNCTION} tasks from that function's own FIFO
 * ({@link FunctionQueueState}) before moving to the next signalled function. Here the
 * function-name queue is {@code activeOrder} and the per-function FIFO is a
 * {@link SchedulingTicket} deque; the turn advances in {@code remove}, since {@code select}
 * must not mutate anything (see the {@link SchedulingIndex} contract).
 *
 * <p>Batch size is not tunable here: preserving the measured value is this class's whole
 * point (see {@code Scheduler#DEFAULT_MAX_BATCH_PER_FUNCTION}).
 */
public class PerFunctionSchedulingStrategy implements SchedulingStrategy {

    /** Mirrors {@code Scheduler.DEFAULT_MAX_BATCH_PER_FUNCTION}; do not retune. */
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
