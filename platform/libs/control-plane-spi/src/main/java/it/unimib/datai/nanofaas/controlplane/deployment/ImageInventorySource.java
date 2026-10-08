package it.unimib.datai.nanofaas.controlplane.deployment;

import java.time.Duration;

/** Read-only inventory of images actually present in a deployment backend. */
public interface ImageInventorySource {
    ImageInventory snapshot(Duration timeout, int maxEntries);
}
