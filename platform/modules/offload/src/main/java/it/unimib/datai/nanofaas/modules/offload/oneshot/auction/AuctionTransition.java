package it.unimib.datai.nanofaas.modules.offload.oneshot.auction;
import java.util.List;
public record AuctionTransition(AuctionSnapshot state, List<AuctionMessage> emit) {
    public AuctionTransition { emit = List.copyOf(emit); }
}
