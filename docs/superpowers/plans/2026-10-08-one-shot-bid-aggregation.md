# One-shot bid aggregation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Questo documento pianifica il lavoro; non autorizza l'esecuzione.

**Goal:** Garantire e documentare che le bid one-shot trasportino prezzo e quantità aggregata e che il numero di batch per round non cresca con le unità di carico.

**Architecture:** Riutilizzare `AuctionMessage.Bid(quantity, price, ...)` e `AuctionCodec.Batch`. Il motore aggrega già le bid nella modalità base e il coordinatore invia già un batch per vicino e fase. Il lavoro previsto aggiunge prove di equivalenza, regressioni sul traffico e documentazione; modifiche runtime solo se emerge un difetto concreto.

**Tech Stack:** Java 25, JUnit 5, AssertJ, Reactor, Gradle; nessuna nuova dipendenza.

**Spec:** Requisito concordato nella conversazione: raggruppare le bid equivalenti come `(valore_bid, quantità)` per vicino e round, preservando l'allocazione. Riferimento esistente: [one-shot offload design](../specs/2026-10-04-one-shot-offload-design.md), sezioni sull'asta base e sulle unità di flusso. Le decisioni circoscritte di questo piano sono riportate sotto.

## Evidenze e decisioni

- Baseline ispezionata: `dce58fe2c2f6ca7675e60119887f6f20f009892a`.
- `OneShotAuctionEngine.Options.base()` imposta `unitBids=false`. `defineBids(...)` calcola un prezzo per offerta selezionata e produce una bid con quantità totale; il ramo unitario ripete lo stesso prezzo.
- `EpochCoordinator.execute(...)` raccoglie le bid per destinatario; `exchange(...)` serializza un solo batch per destinatario e fase. La chiusura del round aggiorna assegnazioni, prezzi e repliche prima del round successivo.
- L'esportatore Python `scripts/one-shot/export_reference.py` usa `unit_bids=False`. Le fixture fissate al commit Python `71899f720a4ffffd070ebf7ddc5b74afddfde9c5` includono già una bid `b=0.01, d=2`. Il checkout Python originale non è disponibile localmente: questo piano non presume verificata ogni modalità di quel progetto.
- L'opzione raccomandata è consolidare il comportamento esistente con test e documentazione. Aggiungere un secondo aggregatore nel trasporto sarebbe ridondante; rimuovere `unitBids` eliminerebbe un confronto utile senza migliorare il percorso base.
- Non si promette una riduzione rispetto all'attuale baseline NanoFaaS: l'ottimizzazione è già presente. La proprietà da dimostrare è l'indipendenza dal numero di unità, a parità di vicini, funzioni e round.

## Global Constraints

- Java 25; 4 spazi; package root `it.unimib.datai.nanofaas`; test JUnit 5 `*Test.java`.
- `quantity` è un intero in unità di flusso: `rate = quantity * flowQuantum`, con rate in richieste/s. Non identifica richieste HTTP già arrivate.
- Aggregazione entro la stessa epoca, round, compratore, destinatario, funzione/versione/generazione e prezzo. Le richieste `memoryOnly` restano distinte dalle bid di capacità.
- Una bid aggregata può essere accettata parzialmente. Conservare ordinamento per prezzo e tie-break esistente, deduplicazione e divieto di sostituire assegnazioni già concesse.
- Un batch per vicino e fase costituisce anche la barriera di chiusura; inviare anche batch vuoti. Non unire fasi o round diversi.
- Conservare topic `nanofaas.oneshot.v1`, schema 1, limite 1000 record e 1 MiB per batch, limiti di coda e retry esistenti.
- La quantità massima sul wire resta `9007199254740991`. Non espandere quantità grandi in oggetti unitari nel percorso base.
- Nessuna nuova configurazione, metrica pubblica, dipendenza o modifica a solver, readiness, routing, OpenAPI e Helm prevista da questo intervento.
- Prima di modificare funzioni/metodi/classi, eseguire GitNexus impact sul simbolo preciso; prima di ogni commit eseguire `detect-changes --scope all --repo .` con risultato completo. Conservare i file non tracciati preesistenti.

## Analisi d'impatto per l'esecuzione

L'indice dichiara HEAD `dce58fe2...`, indicizzato il 2026-10-08. Il runner locale disponibile è `/home/michele/.npm/_npx/e46929201c1128dd/node_modules/gitnexus/dist/cli/index.js`; usarlo direttamente se `.gitnexus/run.cjs` tenta un download non disponibile.

`impact OneShotAuctionEngine --direction upstream` restituisce **CRITICAL**, 8 riferimenti diretti e 130 processi interessati. L'output esteso include flussi di attuazione e invocazione: non considerare locale una modifica al motore. `context/impact defineBids` non risolve il metodo (**UNKNOWN**); `impact EpochCoordinator` è ambiguo, con candidati CRITICAL e UNKNOWN. Questi risultati non costituiscono un via libera a modifiche runtime: disambiguare/riparare la risoluzione prima di un'eventuale correzione.

La ricerca testuale di conferma trova il chiamante produttivo `EpochCoordinator.execute` e i test `AuctionReferenceReplayTest` e `ThreeEdgeAuctionSimulationTest` per `defineBids`; `SellerLedger` usa il motore per l'applicazione delle transizioni. Il percorso verificato nel sorgente è `execute → defineBids → exchange(BIDS) → receive/await → SellerLedger.apply → close → exchange(GRANTS)`.

## Review Focus

1. Capacità insufficiente: la bid aggregata deve ottenere la stessa quantità parziale della rappresentazione unitaria (task 1).
2. Prezzi uguali tra compratori: preservare il vincitore stabilito dal tie-break, indipendentemente dall'ordine di ricezione (task 1).
3. Quantità molto grande: nessuna espansione per unità; payload proporzionale ai gruppi e alle cifre della quantità (task 1 e 2).
4. Retry e batch vuoti: le ritrasmissioni non duplicano assegnazioni e l'assenza di bid non elimina la barriera (task 2; riusare anche le regressioni esistenti).
5. Griglia frazionaria e gruppi diversi: conservare `flowQuantum`, identità, prezzi e `memoryOnly`; non fondere gruppi incompatibili (task 1 e 2).

## Task 1: Provare equivalenza e dimensione delle bid aggregate

**Files:**
- Modify: `platform/modules/offload/src/test/java/it/unimib/datai/nanofaas/modules/offload/oneshot/auction/AuctionReferenceReplayTest.java`
- Modify: `platform/modules/offload/src/test/java/it/unimib/datai/nanofaas/modules/offload/oneshot/auction/SellerLedgerTest.java`

**Interfaces:** usare `OneShotAuctionEngine.defineBids(String function, String version, long generation, long wanted, double gamma, List<AuctionMessage.Offer> offers)` e `SellerLedger.apply(AuctionMessage)`. Nessuna nuova interfaccia produttiva.

- [ ] Aggiungere `aggregateAndUnitBidsDescribeTheSameDemand`: eseguire gli stessi input con `Options.base()` e con gli stessi coefficienti ma `unitBids=true`. Normalizzare solo nel test per `(target, function, version, buyerGeneration, price, memoryOnly)` sommando le quantità. Verificare `assertThat(normalizedAggregate).isEqualTo(normalizedUnit)`. Includere prezzi diversi, due vicini, domanda zero, capacità insufficiente e richieste di memoria. Per `memoryOnly`, confrontare anche la molteplicità dei record, perché la somma di quantità zero non la rappresenta.
- [ ] Aggiungere `aggregateBidCountDoesNotGrowWithQuantity`: una sola offerta compatibile senza memoria residua, quantità richiesta/offerta in `{1, 1000, 1000000, 9007199254740991L}`; verificare una sola proposta, quantità esatta e prezzo costante. Eseguire questo caso su `defineBids`, senza solver e senza costruire la variante unitaria per quantità grandi.
- [ ] Aggiungere `aggregateAndUnitBidsProduceTheSamePartialAllocation`: due ledger con capacità 2; a offre quantità 1 a prezzo 0.02, b quantità 3 a prezzo 0.01. Nel secondo ledger espandere solo le bid di capacità con ID distinti. Dopo chiusura, verificare quantità per compratore `{a: 1, b: 1}`, stessi prezzi e repliche. Ignorare ID e numero dei record di assegnazione nel confronto semantico.
- [ ] Aggiungere `equalPriceAggregationPreservesBuyerTieBreak`: capacità 2, a e b chiedono 3 a prezzo 0.01; entrambe le rappresentazioni assegnano 2 ad a. Ripetere invertendo l'ordine d'arrivo e confrontare anche prezzi e repliche.
- [ ] Eseguire `./gradlew :control-plane-modules:offload:test --tests '*AuctionReferenceReplayTest' --tests '*SellerLedgerTest'`. Questi sono test di caratterizzazione: possono passare subito. Se falliscono, verificare prima fixture e aspettativa; correggere il runtime soltanto dopo aver identificato il difetto e completato impact.
- [ ] Dopo verifica e analisi completa delle modifiche, commit proposto: `Test one-shot aggregate bid equivalence`.

## Task 2: Provare il batching sul trasporto e documentarne il contratto

**Files:**
- Modify: `platform/modules/offload/src/test/java/it/unimib/datai/nanofaas/modules/offload/oneshot/coordination/EpochProtocolTest.java`
- Modify: `platform/modules/offload/src/test/java/it/unimib/datai/nanofaas/modules/offload/oneshot/coordination/AuctionCodecTest.java`
- Modify: `docs/one-shot-coordination.md`
- Modify: `docs/one-shot.md`

**Interfaces:** riusare `EpochProtocolTest.Fake.request(String peer, String topic, byte[] payload, Duration timeout)`, `AuctionCodec.encode(Batch)` e `decode(byte[])`. La cattura dei payload appartiene esclusivamente al trasporto di test.

- [ ] Aggiungere al Fake una raccolta thread-safe di chiamate, destinatari e payload; contare separatamente tentativi di trasporto e batch logici identificati da `(sender, incarnation, epoch, round, phase, target)`. Il contenuto di una ritrasmissione deve essere identico. Non deduplicare il contatore dei tentativi.
- [ ] Aggiungere `oneBatchPerPeerAndPhaseDoesNotDependOnFlowUnits`: riusare lo scenario a tre nodi, con duplicazioni disabilitate, alle griglie `q=1` e `q=0.001`. Convertire load e demand come nel test frazionario esistente, mantenendo invariati carico fisico, capacità e costo del solver. Per ciascun round completato verificare esattamente un invio per coppia diretta e fase `OFFERS/BIDS/GRANTS/CHECK`, inclusi batch vuoti. Escludere HELLO dal conteggio e non assumere un numero totale fisso di round.
- [ ] Nello stesso test verificare una sola bid di capacità per funzione/destinatario nel batch BIDS; il numero di record non aumenta passando a q più piccolo. Confrontare le allocazioni fisiche normalizzate `quantity * q`. Con 2 richieste/s disponibili, una bid di quantità 2000 deve attraversare il codec come un solo record anche se il limite del batch è 1000 record.
- [ ] Aggiungere `aggregateBatchPayloadGrowsOnlyWithQuantityDigits`: usare envelope e identità fissi, una singola bid, quantità 1 e 1000000; verificare round-trip esatto, un solo record e differenza di lunghezza JSON pari a 6 byte. Verificare separatamente round-trip di `flowQuantum=0.001` e della quantità massima supportata. Mantenere il test che rifiuta payload oltre 1 MiB.
- [ ] Riutilizzare il test esistente `threeNodesConvergeWithDuplicateBatchesAndFrozenForecasts`, aggiungendo l'asserzione che i duplicati conservano il payload e non aumentano la quantità assegnata. Conservare i test su perdita dei grant, limite round e griglie incompatibili.
- [ ] Eseguire `./gradlew :control-plane-modules:offload:test --tests '*EpochProtocolTest' --tests '*AuctionCodecTest'`. Anche qui la baseline può passare subito; nessuna modifica produttiva artificiale per ottenere un ciclo RED/GREEN.
- [ ] Aggiornare la documentazione con esempio `(function=f, price=0.01, quantity=2000, q=0.001)`, cioè 2 richieste/s. Spiegare aggregazione per gruppo e batching per vicino/fase, accettazione parziale, prezzo stabile durante la costruzione delle bid del round e aggiornamenti tra round.
- [ ] Documentare la complessità: con P vicini, F funzioni e R round, i batch delle quattro fasi sono `4 * P * R` per nodo senza retry; i record sono `O(P * F * R)` nel percorso base. HELLO, risposte, retry e readiness si contano separatamente. La quantità contribuisce alla lunghezza numerica del payload, non al numero di record.
- [ ] Precisare che 1000 è il limite dei record, non delle unità di carico. Cataloghi grandi e richieste di memoria possono ancora superare questo limite: non introdurre frammentazione in questo intervento, perché richiederebbe modificare il protocollo di chiusura e deduplicazione.
- [ ] Eseguire la suite one-shot: `./gradlew :control-plane-modules:offload:test --tests 'it.unimib.datai.nanofaas.modules.offload.oneshot.*'`. Verificare che siano inclusi i test di peer integration; il gate processi `oneShotE2e` resta separato e richiede Docker. Un'eventuale modifica produttiva a motore/coordinamento richiede anche quel gate JVM con la ricetta esistente.
- [ ] Dopo verifica e analisi completa delle modifiche, commit proposto: `Verify and document one-shot batch bounds`.

## Criterio di completamento

I test dimostrano equivalenza delle quantità assegnate, prezzi e repliche; batch e record non crescono con le unità a parità di gruppi e round; duplicazioni e limiti restano coperti. La documentazione descrive ciò che NanoFaaS già esegue e non attribuisce miglioramenti prestazionali non misurati. Il risultato può consistere interamente in test e documentazione.
