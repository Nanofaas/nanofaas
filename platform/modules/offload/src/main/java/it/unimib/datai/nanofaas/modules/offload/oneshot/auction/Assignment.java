package it.unimib.datai.nanofaas.modules.offload.oneshot.auction;
public record Assignment(String id, String buyerId, String buyerIncarnation, String sellerId, String sellerIncarnation,
                         String function, String version, long sellerGeneration, long buyerGeneration, long epoch,
                         long quantity, boolean readyConfirmed) {
    public Assignment {
        if (id == null || id.isBlank() || quantity < 0 || sellerGeneration < 1 || buyerGeneration < 1)
            throw new IllegalArgumentException("invalid assignment");
    }
    public Assignment confirm(long readyQuantity) {
        if (readyQuantity < 0 || readyQuantity > quantity) throw new IllegalArgumentException("confirmation may only reduce provisional capacity");
        return new Assignment(id, buyerId, buyerIncarnation, sellerId, sellerIncarnation, function, version,
                sellerGeneration, buyerGeneration, epoch, readyQuantity, true);
    }
}
