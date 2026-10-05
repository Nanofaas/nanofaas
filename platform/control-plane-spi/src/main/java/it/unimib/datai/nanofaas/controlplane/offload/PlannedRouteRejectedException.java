package it.unimib.datai.nanofaas.controlplane.offload;
public final class PlannedRouteRejectedException extends RuntimeException {
    public PlannedRouteRejectedException(String reason) { super(reason); }
}
