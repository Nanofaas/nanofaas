package it.unimib.datai.nanofaas.controlplane.offload;

/** Why an invocation was offloaded; used as a metric label. */
public enum OffloadTrigger {
    EAGER,
    DEPTH,
    EST_WAIT
}
