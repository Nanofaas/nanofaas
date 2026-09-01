package it.unimib.datai.nanofaas.cli.http;

/**
 * Managed replica status of a function, as served by
 * {@code GET /v1/functions/{name}/replicas}.
 */
public record ReplicaStatus(String name, int desiredReplicas, int readyReplicas) {}
