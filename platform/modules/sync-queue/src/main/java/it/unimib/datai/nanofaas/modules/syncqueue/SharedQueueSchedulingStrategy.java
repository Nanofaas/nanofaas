package it.unimib.datai.nanofaas.modules.syncqueue;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingIndex;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingStrategy;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;
import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * The shared-queue policy on the {@link SchedulingIndex} contract: one global FIFO shared by
 * every function, kept as one FIFO per generation plus the generations' head tickets in
 * {@code sequence} order. A selection walks the heads oldest first and returns the first one
 * that is due and runnable, so a blocked function never hides ready work however deep the queue
 * is, and the runnable predicate is evaluated at most once per generation per scan.
 *
 * <p>This replaced a single deque scanned 64 entries at a time with a rotating window: under
 * saturation the rotation pushed the oldest tickets behind newer ones, and a runnable function
 * waited one capacity-blocked park per 64 blocked tickets ahead of it. A scan that finds a
 * ticket examines only the heads older than it, never more than the old window did; a scan that
 * finds nothing examines every pending generation's head, as {@code PerFunctionIndex.select}
 * already does. Selection never mutates the index, and neither {@code defer} nor
 * {@code advanceScanWindow} reorders anything.
 */
public class SharedQueueSchedulingStrategy implements SchedulingStrategy {

    @Override
    public String id() {
        return "shared-queue";
    }

    @Override
    public SchedulingIndex newIndex() {
        return new SharedQueueIndex();
    }

    private static final class SharedQueueIndex implements SchedulingIndex {
        /** Oldest sequence first; the unique id makes the order total. */
        private static final Comparator<SchedulingTicket> HEAD_ORDER =
                Comparator.comparingLong(SchedulingTicket::sequence)
                        .thenComparing(ticket -> ticket.id().executionId())
                        .thenComparingInt(ticket -> ticket.id().attempt());

        private final Map<FunctionGeneration, Deque<SchedulingTicket>> byGeneration = new HashMap<>();
        private final Map<TicketId, FunctionGeneration> ownerByTicket = new HashMap<>();
        /** Each pending generation's head ticket, in {@link #HEAD_ORDER}. */
        private final TreeSet<SchedulingTicket> heads = new TreeSet<>(HEAD_ORDER);

        @Override
        public void add(SchedulingTicket ticket) {
            if (ownerByTicket.containsKey(ticket.id())) {
                throw new IllegalArgumentException("duplicate ticket id: " + ticket.id());
            }
            Deque<SchedulingTicket> fifo =
                    byGeneration.computeIfAbsent(ticket.generation(), generation -> new ArrayDeque<>());
            if (fifo.isEmpty()) {
                heads.add(ticket);
            }
            fifo.addLast(ticket);
            ownerByTicket.put(ticket.id(), ticket.generation());
        }

        @Override
        public void remove(TicketId id) {
            FunctionGeneration generation = ownerByTicket.remove(id);
            if (generation == null) {
                return;
            }
            Deque<SchedulingTicket> fifo = byGeneration.get(generation);
            SchedulingTicket head = fifo.peekFirst();
            // A dispatch or an expiry takes the generation's head. Only an out-of-band removal
            // from the middle walks that one generation's FIFO, and leaves its head in place.
            if (head.id().equals(id)) {
                heads.remove(head);
                fifo.pollFirst();
                SchedulingTicket next = fifo.peekFirst();
                if (next == null) {
                    byGeneration.remove(generation);
                } else {
                    heads.add(next);
                }
                return;
            }
            for (Iterator<SchedulingTicket> it = fifo.iterator(); it.hasNext(); ) {
                if (it.next().id().equals(id)) {
                    it.remove();
                    return;
                }
            }
        }

        @Override
        public SchedulingTicket select(Instant now, Predicate<FunctionGeneration> runnable) {
            for (SchedulingTicket head : heads) {
                if (!head.notBefore().isAfter(now) && runnable.test(head.generation())) {
                    return head;
                }
            }
            return null;
        }

        /** Order-preserving no-op: the engine already skips the blocked generation until its next
         * wake. */
        @Override
        public void defer(TicketId id) {
        }

        @Override
        public int size() {
            return ownerByTicket.size();
        }

        @Override
        public void clear() {
            byGeneration.clear();
            ownerByTicket.clear();
            heads.clear();
        }
    }
}
