package it.unimib.datai.nanofaas.p2papi;

@FunctionalInterface
public interface PeerSubscription extends AutoCloseable { @Override void close(); }
