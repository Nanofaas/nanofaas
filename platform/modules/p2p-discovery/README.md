# p2p-discovery

Optional control-plane module: lets a nanofaas node **find other nanofaas nodes**
on the network, **measure latency** to them, choose which ones count as **active
neighbors**, and **exchange messages** with those neighbors. It provides
primitives only; nothing in the platform routes invocations through it yet.

Design spec: `docs/superpowers/specs/2026-10-01-p2p-discovery-design.md`.

Discovery and membership run on [scalecube-cluster](https://github.com/scalecube/scalecube-cluster)
(SWIM). Every node knows every other node, so it fits tens to a few hundreds of
nodes; there is no DHT. Only `PeerCluster` touches scalecube (enforced by an
ArchUnit rule), so the engine is replaceable.

## Configuration

Include the module with `-PcontrolPlaneModules=p2p-discovery` (or add it to the
selected module list). It is excluded from the default artifact. Its `module.properties`
descriptor drives build selection; Spring Boot loads `P2pConfiguration` through auto-configuration.
The build composes its `openapi.yaml` fragment into the artifact’s `/openapi.yaml`.

```yaml
nanofaas:
  p2p:
    enabled: false              # master switch; the module is inert when false
    node-id: edge-1             # generated when absent; persisted only with a state-file
    port: 7946                  # cluster transport port (0 = ephemeral)
    external-host: 10.0.0.5     # address advertised to peers (containers, NAT)
    seeds: ["10.0.0.9:7946"]    # nodes to join through; one reachable seed is enough
    max-neighbors: 4            # unset = unlimited
    max-latency-ms: 80          # unset = the latency filter is not applied
    ping-interval: 1s
    ping-timeout: 2s
    state-file: /var/lib/nanofaas/p2p.yaml   # unset = nothing is persisted
    admin:
      enabled: false            # exposes /v1/admin/p2p/**
```

Node ids must be **unique and stable**: peer modes and saved peers are keyed by id.

## Which peers are active

For each peer, in order:

1. mode `EXCLUDED` → not active
2. mode `FORCE_ACTIVE` → active, ignoring threshold and maximum
3. `max-latency-ms` set and RTT above it → not active (a peer not measured yet is not active either). With no threshold the filter does not apply. A peer already active stays active up to 10% above the threshold, to avoid flapping.
4. if `max-neighbors` is set, the lowest-RTT candidates are kept (forced peers count towards the number; unmeasured peers come last; an active neighbor keeps its slot unless a challenger is better by more than 10% or 1 ms, so jitter does not swap near-equal peers)

RTT is measured with an application ping (one per peer per `ping-interval`), median over the last 9 samples. Vivaldi coordinates (3 dimensions) are updated from the same pings.

## State file

One YAML file, two sections with different owners:

When a `state-file` is configured, a newly generated node ID is saved during startup, before startup returns.

```yaml
config:            # yours: the node reads it and never changes its meaning
  seeds: ["10.0.0.5:7946"]
  maxNeighbors: 4
  maxLatencyMs: 80
  peers:
    - {id: edge-3, mode: EXCLUDED}
state:             # the node's: rewritten as things change
  nodeId: edge-1
  overrides: {maxNeighbors: 6}        # set through PATCH /config
  peerModes: {edge-7: FORCE_ACTIVE}   # set through PUT /peers/{id}
  peers:                              # peers seen so far, for a fast restart
    - {id: edge-2, address: "10.0.0.7:7946", rttMs: 12.4, coord: [0.3, 1.1, 0.02]}
```

Precedence: `state` overrides → `config` → `application.yml`.

On restart the saved peers' addresses are added to the seeds, so the node rejoins
without waiting. A saved peer enters the table only when the cluster confirms it
(a node that died meanwhile never shows as active), and its saved RTT is used only to
order candidates, never to satisfy the threshold.

The node rewrites the file atomically (data synced to disk before the rename), only when something structural changed (peers joining or leaving, admin changes), plus a refresh of RTTs and coordinates every 5 minutes and one on shutdown. A saved peer the cluster never confirms is kept for 10 minutes of uptime and then dropped from the file. A key it does not know in the file (a typo) makes the file unreadable like a syntax error. Rewriting loses **comments** in `config`
(the values are kept). A file that cannot be parsed is not an error at boot; if the node
has to persist over it, the original text is first copied to `<file>.corrupt` (`.corrupt.1`, `.corrupt.2`, ... if one already exists, so a second corruption never overwrites the first copy). Temporary files left by a killed process are removed at startup.

## Admin API

Needs `nanofaas.p2p.enabled=true` and `nanofaas.p2p.admin.enabled=true`; otherwise
every `/v1/admin/p2p/**` request is a `404`. Details in this module’s `openapi.yaml` fragment.

```bash
curl localhost:8080/v1/admin/p2p/peers
curl -X PUT   localhost:8080/v1/admin/p2p/peers/edge-3 -H 'content-type: application/json' -d '{"mode":"EXCLUDED"}'
curl -X PATCH localhost:8080/v1/admin/p2p/config       -H 'content-type: application/json' -d '{"maxNeighbors":2,"maxLatencyMs":null}'
curl -X DELETE localhost:8080/v1/admin/p2p/overrides
```

`"maxLatencyMs": null` removes the threshold. `AUTO` removes a runtime mode.

## Runtime participation

For standalone deployments, keep `nanofaas.p2p.enabled=false`: no cluster socket or
background tasks are started, and the API cannot enable the module.

With the module and admin API enabled, `GET /v1/admin/p2p/state` reports participation.
`PUT` accepts exactly one `state` field:

```bash
curl -X PUT localhost:8080/v1/admin/p2p/state -H 'content-type: application/json' -d '{"state":"ISOLATED"}'
```

- `ACTIVE`: participate normally; resume or rejoin after either condition below.
- `ISOLATED`: drop incoming and outgoing P2P traffic, including heartbeats and gossip,
  without announcing departure. Other nodes detect the failure after the SWIM timeout.
- `LEFT`: announce voluntary departure and close the cluster transport.

The control-plane HTTP API remains available in every state. Transitions are
idempotent, and preserve the node ID, configuration, peer modes and message handlers,
even without a state file. No neighbors are active while isolated or left; messaging
is unavailable. Transitions run on worker threads and respond after completion.
Runtime participation is not persisted: a process restart follows deployment configuration.

## Messaging primitives

`P2pService.messaging()` returns a `PeerMessaging`: `send`, `request`, `broadcast`
(to active neighbors) and `subscribe(topic, receiver)`. Payloads are opaque `byte[]`;
topics starting with `p2p.` are reserved. Sending to a peer that is not an active
neighbor fails with `PeerNotActiveException`; messages received from a peer that is not
active are dropped. Other modules cannot call this yet: exposing it needs an interface
in `:control-plane-spi`, because modules must not depend on each other.

## Metrics

`p2p_peers`, `p2p_peers_active`, `p2p_peer_rtt_ms{peer=...}`.

## Limits

- Full membership, no DHT. No NAT traversal: nodes must reach each other directly (LAN, VPN).
- **No authentication and no encryption** on the cluster port, like the rest of the project. Consequences worth knowing:
  - the sender of a message is whatever its `sender` header says, so "active neighbors only" on the receive side is a
    filter against honest mistakes, not a security boundary, and a forged `sender` makes a node send its reply to that address;
  - anyone who can reach the port can join the cluster and be measured and selected like any peer.
  Keep the cluster port on a private network or VPN.
- Cluster messages are Java-serialized by scalecube. The module replaces its codec with one that keeps the wire format
  but caps headers at 16 and membership/gossip collections at 10,000 entries before allocation, and allows only
  scalecube's own types plus basic JDK types, with size and depth limits, so a
  crafted packet cannot trigger arbitrary deserialization or a huge allocation.
- Node ids are not checked for duplicates: scalecube keys membership by id, so two nodes started with the same id are
  not detected and one of them is invisible to the others. Give every node its own `node-id`.
- A peer that stops answering pings is counted at the ping timeout, so after a few rounds it falls out of a latency
  threshold; with no threshold it stays active until the cluster itself declares it gone.
- With scalecube's defaults a node that stops without leaving is declared dead after about 10 s.
- State lives in memory plus the state file; a single process must own the file. Writes happen off the request threads; a write that keeps failing (read-only directory) is warned once, then logged at debug until it succeeds.
- `max-neighbors` and `max-latency-ms` are validated at startup: a bad `application.yml` value fails the boot, a bad value in the state file's `config` is ignored with a warning.
- Native image: the reachability metadata under `META-INF/native-image` was derived from a traced 3-node run;
  a scalecube code path that run did not exercise could fail only in native.
