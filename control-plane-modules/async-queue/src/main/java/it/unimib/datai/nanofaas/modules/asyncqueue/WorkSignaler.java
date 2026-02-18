package it.unimib.datai.nanofaas.modules.asyncqueue;

public interface WorkSignaler {
    void signalWork(String functionName);
}
