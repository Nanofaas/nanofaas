package it.unimib.datai.nanofaas.containerdeployment;

public record ManagedContainer(String name, int replicaIndex, String baseUrl, boolean running) {
}
