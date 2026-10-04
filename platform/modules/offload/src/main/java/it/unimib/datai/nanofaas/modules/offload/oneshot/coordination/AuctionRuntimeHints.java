package it.unimib.datai.nanofaas.modules.offload.oneshot.coordination;
import it.unimib.datai.nanofaas.modules.offload.oneshot.auction.*;
import org.springframework.aot.hint.*;
public final class AuctionRuntimeHints implements RuntimeHintsRegistrar {
    @Override public void registerHints(RuntimeHints hints,ClassLoader loader) {
        for(var type:new Class<?>[]{AuctionCodec.Batch.class,AuctionMessage.class,AuctionMessage.Envelope.class,AuctionMessage.Offer.class,AuctionMessage.Bid.class,Assignment.class})
            hints.reflection().registerType(type,MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,MemberCategory.INVOKE_PUBLIC_METHODS);
    }
}
