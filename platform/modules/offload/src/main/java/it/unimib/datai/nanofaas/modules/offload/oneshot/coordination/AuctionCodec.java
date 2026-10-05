package it.unimib.datai.nanofaas.modules.offload.oneshot.coordination;
import it.unimib.datai.nanofaas.modules.offload.oneshot.auction.*;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
/** One bounded batch is also the explicit closure barrier for its round phase. */
public final class AuctionCodec {
    public enum Phase { HELLO,OFFERS,BIDS,GRANTS,CHECK,READY,READY_ACK }
    public record Batch(int schemaVersion,String senderId,String incarnation,long epoch,int round,Phase phase,Instant startsAt,Instant endsAt,boolean changed,List<AuctionMessage> messages,double flowQuantum) {
        public Batch(int schemaVersion,String senderId,String incarnation,long epoch,int round,Phase phase,Instant startsAt,Instant endsAt,boolean changed,List<AuctionMessage> messages) {
            this(schemaVersion,senderId,incarnation,epoch,round,phase,startsAt,endsAt,changed,messages,1);
        }
        public Batch {
            bounded(senderId); bounded(incarnation);
            if(!Double.isFinite(flowQuantum) || flowQuantum<=0 || schemaVersion!=1 || epoch<0 || round<0 || phase==null || startsAt==null || endsAt==null || !startsAt.isBefore(endsAt) || messages==null || messages.size()>1000) throw new IllegalArgumentException("invalid batch");
            messages=List.copyOf(messages);
            for(var message:messages) {
                if((phase==Phase.OFFERS && message.kind()!=AuctionMessage.Kind.OFFER) || (phase==Phase.BIDS && message.kind()!=AuctionMessage.Kind.BID)
                    || (phase==Phase.GRANTS && message.kind()!=AuctionMessage.Kind.GRANT) || ((phase==Phase.READY || phase==Phase.READY_ACK) && message.kind()!=AuctionMessage.Kind.READY_CONFIRM) || phase==Phase.HELLO || phase==Phase.CHECK) throw new IllegalArgumentException("message does not match phase");
                validate(message,senderId,incarnation,epoch,round,startsAt,endsAt);
            }
        }
    }
    private static final int MAX_BYTES=1024*1024;
    private final JsonMapper mapper=JsonMapper.builder(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12).maxStringLength(1024).maxNumberLength(64).build()).build())
        .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .enable(tools.jackson.databind.cfg.EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).build();
    public byte[] encode(Batch batch) { byte[] bytes=mapper.writeValueAsBytes(batch); if(bytes.length>MAX_BYTES) throw new IllegalArgumentException("auction batch too large"); return bytes; }
    public Batch decode(byte[] bytes) {
        if(bytes==null || bytes.length==0 || bytes.length>MAX_BYTES) throw new IllegalArgumentException("auction batch too large or empty");
        try { return mapper.readValue(bytes,Batch.class); } catch(RuntimeException failure) { throw new IllegalArgumentException("invalid auction batch",failure); }
    }
    private static void bounded(String value) { if(value==null || value.isBlank() || value.length()>256) throw new IllegalArgumentException("invalid bounded identity"); }
    private static void finite(double value) { if(!Double.isFinite(value) || value<0) throw new IllegalArgumentException("invalid auction number"); }
    private static void validate(AuctionMessage m,String sender,String incarnation,long epoch,int round,Instant from,Instant until) {
        var e=m.envelope(); bounded(e.messageId()); bounded(m.target());
        if(!e.senderId().equals(sender) || !e.incarnation().equals(incarnation) || e.epoch()!=epoch || e.round()!=round || !e.validFrom().equals(from) || !e.validUntil().equals(until)) throw new IllegalArgumentException("batch identity mismatch");
        if(m.offer()!=null) { var o=m.offer(); bounded(o.sellerId()); bounded(o.incarnation()); bounded(o.function()); bounded(o.version());
            if(!sender.equals(o.sellerId()) || !incarnation.equals(o.incarnation()) || o.generation()<1 || o.quantity()<0 || o.quantity()>9007199254740991L || o.residualMemoryMiB()<0 || o.memoryMiB()<1) throw new IllegalArgumentException("invalid offer");
            for(double number:new double[]{o.demandSeconds(),o.utilization(),o.price(),o.beta(),o.latencySeconds(),o.fairness()}) finite(number);
            if(o.demandSeconds()==0 || o.utilization()==0 || o.utilization()>1) throw new IllegalArgumentException("invalid service capacity");
        }
        if(m.bid()!=null) { var b=m.bid(); bounded(b.function()); bounded(b.version()); finite(b.price()); if(b.buyerGeneration()<1 || b.quantity()<0 || b.quantity()>9007199254740991L || b.memoryOnly()!=(b.quantity()==0)) throw new IllegalArgumentException("invalid bid"); }
        if(m.assignment()!=null) { var a=m.assignment(); bounded(a.id()); bounded(a.buyerId()); bounded(a.buyerIncarnation()); bounded(a.sellerId()); bounded(a.sellerIncarnation()); bounded(a.function()); bounded(a.version()); if(a.quantity()>9007199254740991L || a.epoch()!=epoch) throw new IllegalArgumentException("invalid assignment"); }
    }
}
