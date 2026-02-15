package it.unimib.datai.nanofaas.controlplane.scheduler;

public interface WorkSignaler {
    void signalWork(String functionName);
}
