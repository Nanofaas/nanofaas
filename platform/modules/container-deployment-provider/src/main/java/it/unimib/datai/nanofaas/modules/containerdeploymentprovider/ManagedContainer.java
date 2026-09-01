package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

record ManagedContainer(String name, int replicaIndex, Integer hostPort, boolean running) {
}
