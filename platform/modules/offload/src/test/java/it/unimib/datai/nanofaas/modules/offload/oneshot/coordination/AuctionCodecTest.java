package it.unimib.datai.nanofaas.modules.offload.oneshot.coordination;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
class AuctionCodecTest {
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
