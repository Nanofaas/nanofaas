# Docker Compose deployment

This deployment runs the nanoFaaS control plane and Prometheus on the named
`nanofaas` Docker network. Function replicas join the same network and are
reached through Docker DNS; their port `8080` is not published on the host.

Run the published control-plane image:

```bash
docker compose -f deploy/compose/compose.yaml up -d
```

Build the control plane from the current checkout and start it:

```bash
docker compose -f deploy/compose/compose.yaml up -d --build
```

Override the published image when needed:

```bash
NANOFAAS_CONTROL_PLANE_IMAGE=ghcr.io/miciav/nanofaas/control-plane:vX.Y.Z \
  docker compose -f deploy/compose/compose.yaml up -d
```

The API is available on `http://localhost:8080`, management endpoints on
`http://localhost:8081`, and Prometheus on `http://localhost:9090`.

## Docker access

The control plane mounts `/var/run/docker.sock` and runs as `0:0`. Access to
that socket is effectively host-root access because the caller can create
privileged containers and mount host paths. Only use this deployment on a
trusted Docker host, and do not expose its control-plane API to untrusted
clients.

The network is created by Compose. If the control plane is started separately,
create a Docker network named `nanofaas` before configuring
`nanofaas.container-local.network-name=nanofaas`.

## Persistent function catalog

The control plane writes its function catalog to a named volume,
`control-plane-data`, mounted at `/var/lib/nanofaas`, with
`NANOFAAS_REGISTRY_PATH=/var/lib/nanofaas/functions.json`. The catalog survives
`docker compose down` and control-plane restarts; delete it explicitly with
`docker compose down -v` to reset state. The catalog stores function specs
including their environment variables in plaintext, so treat the volume as
sensitive and back it up.
