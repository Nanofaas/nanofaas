# Modulo `p2p-discovery` — design

Data: 2026-10-01. Stato: bozza da rivedere.

## Obiettivo

Permettere a un'istanza nanofaas di trovare altre istanze in rete, misurarne la
latenza, scegliere quali considerare "vicini attivi" e scambiare messaggi con
loro. La v1 fornisce solo **primitive** (scoperta, vicinato, messaggi): nessun
consumatore (routing delle invocazioni, offload dinamico) è incluso.

Ispirato a dfaas, con due scelte deliberate:

- **Interamente Java**, quindi niente libp2p. jvm-libp2p non ha un Kademlia
  utilizzabile e la sua compatibilità native non è verificata.
- **scalecube-cluster 2.7.1** (SWIM: membership, failure detection, gossip) come
  motore. Decentralizzato: i seed servono solo al primo aggancio.

## Verifiche già fatte (spike, 2026-10-01)

- Java 25 OK; funziona con Reactor Netty 1.3.6 / Netty 4.2.15 / reactor-core
  3.8.6 del BOM Spring Boot 4.1.0 (scalecube dichiara 1.0.32 / 4.1.92).
- 3 nodi con un seed convergono in ~300 ms; i metadata per nodo arrivano.
- `Transport.requestResponse` funziona; RTT 0,6 ms in JVM, 0,13 ms in native.
- Rimozione di un nodo: `LEAVING` ~0,4 s, `REMOVED` ~10 s con i default.
- Build GraalVM native riuscito (27 s, 25 MB) e eseguibile funzionante, con
  configurazione generata dall'agent.
- `Cluster` 2.7.1 non ha `send`: il `Transport` si cattura via
  `TransportConfig.transportFactory`. Il campo `sender` va valorizzato a mano.
- Il codec di default è la serializzazione JDK (14 voci di serializzazione in
  native) e non filtra nulla; è sostituibile con `TransportConfig.messageCodec`.

## Limiti accettati

- Membership completa (ogni nodo conosce tutti): adatto a decine/centinaia di
  nodi, non migliaia. Nessuna DHT.
- Nessun NAT traversal: i nodi devono raggiungersi direttamente (LAN, VPN).
- Nessuna autenticazione né cifratura, coerente con il resto del progetto;
  da documentare nel README del modulo.
- Il file di stato è scritto da un solo nodo; non c'è locking tra processi che condividono lo stesso percorso.
- Riscrivere il file perde i commenti della sezione `config` (i valori restano); un file non leggibile viene copiato in `<file>.corrupt` prima di essere sovrascritto.
- `PeerMessaging` non è raggiungibile da altri moduli: serve un'interfaccia in `:control-plane-spi` (i moduli non possono dipendere l'uno dall'altro).
- Ultima release scalecube: febbraio 2025.

## Struttura

Cartella `platform/modules/p2p-discovery`, selezionata al build tramite
`module.properties` e caricata da Spring Boot tramite
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
Non dipende dall’implementazione del control plane. Esclusa dal build di default e
inattiva a runtime salvo `nanofaas.p2p.enabled=true`. Il frammento OpenAPI del modulo
viene composto con la specifica del core durante il build.

| Componente | Responsabilità |
|---|---|
| `PeerCluster` | Unica classe che conosce scalecube: membri, eventi, `send`/`request`. Rende il motore sostituibile. |
| `LatencyMonitor` | Ping applicativi periodici per peer, RTT mediano su finestra, coordinate Vivaldi locali. |
| `NeighborSelector` | Calcola l'insieme dei vicini attivi (regole sotto). |
| `PeerMessaging` | API di messaggi verso i vicini attivi. |
| `P2pAdminController` | Diagnostica e controllo a runtime. |

`ArchitectureTest` come negli altri moduli: scalecube visibile solo in
`PeerCluster`.

## Selezione dei vicini

Parametri: `maxNeighbors` (opzionale), `maxLatencyMs` (opzionale), `seeds`,
`port`, intervallo di ping.

Per ogni peer, in ordine:

1. Modalità `EXCLUDED` → non attivo.
2. Modalità `FORCE_ACTIVE` → attivo, ignorando soglia e massimo.
3. `maxLatencyMs` impostata e RTT noto sopra soglia → non attivo. Un peer **non
   ancora misurato** con soglia impostata è non attivo finché non ha una misura.
   Con `maxLatencyMs` **non impostata** il filtro non si applica.
4. Se `maxNeighbors` è impostato, tra i candidati restano quelli con RTT più
   basso (i non misurati per ultimi); i `FORCE_ACTIVE` contano nel numero.

Il ricalcolo avviene a ogni cambio di membership, a ogni nuova misura e a ogni
modifica admin. Isteresi piccola sulla soglia per evitare oscillazioni.

## Messaggi

- `send(peerId, topic, payload)`; `request(peerId, topic, payload) → Mono<reply>`;
  `broadcast(topic, payload)` verso i vicini attivi (non gossip sull'intero
  cluster); `subscribe(topic, handler)`.
- Verso un peer non attivo l'invio fallisce con errore esplicito.
- Il payload applicativo è `byte[]` opaco e il topic viaggia in un header del messaggio scalecube. I messaggi SWIM interni restano serializzati con il formato JDK di scalecube, ma il codec è sostituito (`PeerClusterCodec`): stesso formato, tetto sul numero di header e allow-list di classi con limiti di dimensione. `sender` valorizzato dal modulo.

## API admin

Sotto `/v1/admin/p2p`, abilitata da `nanofaas.p2p.admin.enabled` (come
runtime-config, ma il blocco è fatto per richiesta da un `WebFilter` e non con
`@ConditionalOnProperty`, che Spring AOT risolve a build time e nel binario native
farebbe sparire gli endpoint):

- `GET /peers` — peer con RTT, coordinate, stato attivo/non attivo e motivo.
- `PUT /peers/{id}` — `{"mode": "AUTO" | "FORCE_ACTIVE" | "EXCLUDED"}`.
- `PATCH /config` — `maxNeighbors`, `maxLatencyMs` (valore nullo = non applicato).

Metriche Micrometer: peer totali, peer attivi, RTT per peer.

## File di configurazione e stato

Un solo file YAML (`nanofaas.p2p.state-file`; vuoto = nessun file), sul modello
del manifest delle funzioni (`docs/function-definition.md`) ma con due sezioni
a proprietari distinti:

```yaml
config:            # dell'operatore: il nodo la legge e NON la riscrive
  seeds: ["10.0.0.5:7946"]
  maxNeighbors: 4
  maxLatencyMs: 80          # assente = filtro non applicato
  peers:                    # modalità decise dall'operatore
    - {id: edge-3, mode: EXCLUDED}
state:             # del nodo: riscritta dal nodo
  overrides: {maxNeighbors: 6}      # cambiati via PATCH /config
  peerModes: {edge-7: FORCE_ACTIVE} # cambiati via PUT /peers/{id}
  peers:                            # peer noti, per ripartire veloci
    - {id: edge-2, address: "10.0.0.7:7946", rttMs: 12.4, coord: [0.3, 1.1, 0.02]}
```

- **Precedenza** (la più forte vince): `state.overrides` / `state.peerModes` →
  `config` del file → `application.yml`. `DELETE /v1/admin/p2p/overrides`
  azzera la sezione `state.overrides` e `state.peerModes`.
- **Scrittura**: solo la sezione `state`, con file temporaneo e rinomina atomica.
  La sezione `config` è preservata nei valori (il nodo la rilegge dal file prima di
  scrivere); i commenti YAML non sopravvivono. Le modifiche admin si scrivono subito; RTT e coordinate
  sono raggruppati (al massimo una scrittura ogni pochi secondi).
- **Caricamento**: all'avvio. File assente, vuoto o corrotto → warning, il boot
  non fallisce, si parte dalla sola configurazione.
- **Ripartenza veloce**: gli indirizzi in `state.peers` si aggiungono ai
  seed; `rttMs` serve solo come ordinamento iniziale e **mai** per applicare la
  soglia (un peer non rimisurato resta non attivo se la soglia è impostata);
  le coordinate Vivaldi salvate evitano di ripartire da zero.
- **Dipendenza**: `jackson-dataformat-yaml` nel modulo (la CLI ha già
  `YamlIO`, ma non è riusabile dal control plane). Da verificare nel build native.

## Misura della latenza

L'RTT si misura con un ping applicativo sopra `requestResponse`, perché il
failure detector di scalecube non espone le misure. Costo: un ping al secondo
per peer (configurabile).

## Test

- Unitari: `NeighborSelector` (tutte le regole, soglia assente, isteresi);
  Vivaldi con coordinate note.
- Integrazione: 3 nodi in JVM — scoperta, caduta di un nodo, messaggio con
  risposta, filtro per latenza con RTT simulato, cambio modalità via API.
- Persistenza: round-trip del file; `config` preservata dopo una scrittura di
  `state`; file corrotto ignorato; ripartenza con peer salvati e senza seed.
- Native: configurazione dell'agent rigenerata e verificata sul modulo vero
  (inclusa la lettura/scrittura YAML).

## Fuori scope (v1)

Routing delle invocazioni; NAT traversal; comando CLI `p2p apply`;
autenticazione e cifratura; Kademlia/DHT.
