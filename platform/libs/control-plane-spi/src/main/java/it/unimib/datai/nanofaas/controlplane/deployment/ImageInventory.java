package it.unimib.datai.nanofaas.controlplane.deployment;

import java.time.Instant;
import java.util.List;

/** A measured backend inventory, with explicit scope and completeness. */
public record ImageInventory(String backend, String scope, Instant collectedAt, Status status,
                             String reasonCode, List<Entry> entries) {
    public enum Status { AVAILABLE, PARTIAL, UNAVAILABLE }

    public ImageInventory {
        if (backend == null || scope == null || collectedAt == null || status == null) {
            throw new IllegalArgumentException("inventory metadata is required");
        }
        entries = List.copyOf(entries);
        if (status == Status.UNAVAILABLE && !entries.isEmpty()) {
            throw new IllegalArgumentException("unavailable inventory cannot contain images");
        }
    }

    public record Entry(String nodeId, List<String> references, String digest, String imageId) {
        public Entry {
            references = List.copyOf(references);
            if (references.isEmpty() && (digest == null || digest.isBlank()) && (imageId == null || imageId.isBlank())) {
                throw new IllegalArgumentException("image identity is required");
            }
        }
    }
}
