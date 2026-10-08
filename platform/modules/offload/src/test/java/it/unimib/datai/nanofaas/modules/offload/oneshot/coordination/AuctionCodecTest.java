package it.unimib.datai.nanofaas.modules.offload.oneshot.coordination;
import org.junit.jupiter.api.Test;
import it.unimib.datai.nanofaas.modules.offload.oneshot.auction.AuctionMessage;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
class AuctionCodecTest {
    private AuctionCodec.Batch aggregateBatch(long quantity, double quantum) {
        var from = Instant.parse("2026-10-04T10:00:00Z");
        var envelope = new AuctionMessage.Envelope(1, "a", "run", 1, 0, "bid", 1, from, from.plusSeconds(300));
        var message = AuctionMessage.bid(envelope, "b", new AuctionMessage.Bid("f", "v1", 3, quantity, 0.01, false));
        return new AuctionCodec.Batch(1, "a", "run", 1, 0, AuctionCodec.Phase.BIDS, from, from.plusSeconds(300),
                false, List.of(message), quantum);
    }

    @Test void aggregateBatchPayloadGrowsOnlyWithQuantityDigits() {
        var codec = new AuctionCodec();
        var small = codec.encode(aggregateBatch(1, 1));
        var large = codec.encode(aggregateBatch(1000000, 1));
        assertThat(large.length - small.length).isEqualTo(6);
        for (long quantity : new long[]{1, 1000000, 9007199254740991L}) {
            for (double quantum : new double[]{1, 0.001}) {
                var batch = aggregateBatch(quantity, quantum);
                var decoded = codec.decode(codec.encode(batch));
                assertThat(decoded).isEqualTo(batch);
                assertThat(decoded.messages()).singleElement().satisfies(m -> {
                    assertThat(m.bid().quantity()).isEqualTo(quantity);
                    assertThat(m.bid().price()).isEqualTo(0.01);
                });
                assertThat(decoded.flowQuantum()).isEqualTo(quantum);
            }
        }
    }

    @Test void boundedStrictRoundTrip() {
        var codec=new AuctionCodec(); var from=Instant.parse("2026-10-04T10:00:00Z");
        var batch=new AuctionCodec.Batch(1,"a","run",1,0,AuctionCodec.Phase.OFFERS,from,from.plusSeconds(300),false,List.of());
        assertThat(codec.decode(codec.encode(batch))).isEqualTo(batch);
        assertThatThrownBy(()->codec.decode(new byte[1024*1024+1])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->codec.decode("{\"schemaVersion\":2}".getBytes())).isInstanceOf(IllegalArgumentException.class);
        var bytes=new String(codec.encode(batch));
        assertThatThrownBy(()->codec.decode(bytes.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"extra\":0").getBytes())).isInstanceOf(IllegalArgumentException.class);
    }
}
