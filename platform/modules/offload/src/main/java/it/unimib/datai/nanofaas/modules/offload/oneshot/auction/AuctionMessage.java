package it.unimib.datai.nanofaas.modules.offload.oneshot.auction;
import java.time.Instant;
public record AuctionMessage(Envelope envelope, Kind kind, String target, Offer offer, Bid bid, Assignment assignment) {
    public enum Kind { OFFER, BID, GRANT, ROUND_CLOSE, READY_CONFIRM }
    public record Envelope(int schemaVersion, String senderId, String incarnation, long epoch, int round,
                           String messageId, long planRevision, Instant validFrom, Instant validUntil) {
        public Envelope {
            if (schemaVersion != 1 || senderId == null || senderId.isBlank() || incarnation == null || incarnation.isBlank()
                    || epoch < 0 || round < 0 || messageId == null || messageId.isBlank() || planRevision < 0
                    || validFrom == null || validUntil == null || !validFrom.isBefore(validUntil))
                throw new IllegalArgumentException("invalid auction envelope");
        }
    }
    public record Offer(String sellerId, String incarnation, String function, String version, long generation,
                        long quantity, long residualMemoryMiB, long memoryMiB, double demandSeconds,
                        double utilization, double price, double beta, double latencySeconds, double fairness) {}
    public record Bid(String function, String version, long buyerGeneration, long quantity, double price, boolean memoryOnly) {}
    public AuctionMessage {
        if (envelope == null || kind == null || target == null || target.isBlank()) throw new IllegalArgumentException("invalid message");
        if ((kind == Kind.OFFER && (offer == null || bid != null || assignment != null))
                || (kind == Kind.BID && (bid == null || offer != null || assignment != null))
                || ((kind == Kind.GRANT || kind == Kind.READY_CONFIRM) && (assignment == null || offer != null || bid != null))
                || (kind == Kind.ROUND_CLOSE && (offer != null || bid != null || assignment != null)))
            throw new IllegalArgumentException("invalid typed auction payload");
    }
    public static AuctionMessage bid(Envelope e, String target, Bid bid) { return new AuctionMessage(e, Kind.BID, target, null, bid, null); }
    public static AuctionMessage close(Envelope e, String target) { return new AuctionMessage(e, Kind.ROUND_CLOSE, target, null, null, null); }
}
