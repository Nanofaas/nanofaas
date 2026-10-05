package it.unimib.datai.nanofaas.forecastingapi;

@FunctionalInterface
public interface ExternalArrivalObserver {
    void record(ExternalArrival arrival);
    static ExternalArrivalObserver noOp() { return arrival -> {}; }
}
