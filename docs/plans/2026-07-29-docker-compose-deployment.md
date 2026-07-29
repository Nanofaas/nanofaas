# Docker Compose deployment plan

## Goal

Provide a production-shaped Docker Compose deployment that:

1. lets the containerized control plane create and remove function replicas through Docker;
2. keeps the control plane and every managed replica on one named Docker network, without publishing replica ports on the host.

The Compose file must support both the published control-plane image and a local Dockerfile build. Prometheus is part of the deployment; a registry is not.

## Design

- Mount `/var/run/docker.sock` read/write into the control-plane container.
- Run the control plane as `0:0`. Docker socket access is already host-root-equivalent, so a proxy would add a service without creating a meaningful security boundary for `containers/create`.
- Configure the `docker-java` runtime adapter and a named network.
- When a network is configured, address replicas as `http://<container-name>:8080` and do not allocate or publish a host port.
- Preserve the current host-port behavior when no network is configured.
- Let Compose own the named network; the runtime adapter only joins replicas to it and fails clearly if it is missing.
- Run Prometheus on the same network and scrape the control plane at `control-plane:8081`.

## TDD sequence

1. Add failing provider tests for container-DNS backend URLs and absence of host-port allocation.
2. Add failing docker-java adapter tests for joining the configured network without port bindings.
3. Add the smallest property, provider, and adapter changes that make those tests pass.
4. Add Compose configuration tests before adding the Compose and Prometheus files.
5. Validate `docker compose config`, module tests, and a real Docker lifecycle on the shared network.

## Non-goals

- Creating a registry in Compose.
- Automatically creating arbitrary Docker networks from the control plane.
- Replacing the internal round-robin proxy.
- Changing Kubernetes/Helm behavior.
