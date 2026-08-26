# ExecutionStore: separare il vivo dall'esito

**Obiettivo:** la memoria dello store smette di essere proporzionale al tasso di
arrivo *moltiplicato per il payload*, e diventa limitata in numero e indipendente
da cosa restituiscono le funzioni.

## Misure che motivano il lavoro (2026-08-26, `A3-sync-3x`)

| | |
|---|---|
| `function_inFlight` max | **2** |
| `execution_store_size` max | **165.786** |
| oggetti trattenuti per record | **35** (5,8 M in totale) |
| byte per record | **1.330** |
| `cleanup()` libera | **56 B su 1.330 (4%)** |
| ritenzione dichiarata / misurata | **30 s / 152 s** |

Forma compatta misurata: **116 B/record** senza payload, e **116 B anche con un
payload da 4 KB** (contro 4.916 B di oggi). E' questa indipendenza dal payload il
motivo per cui il solo tetto in numero non basta.

## Perche' `cleanup()` non funziona oggi

`ExecutionCompletionHandler:83+97` passa **lo stesso** `InvocationResult` a
`markSuccess(result.output(), ...)` e a `completion().complete(result)`.
`cleanup()` azzera `output`/`headers` sul record ma non tocca la future, che
continua a puntare allo stesso result: il payload non se ne va. In piu' con i
default `syncTtl` (30 s) < `cleanupTtl` (2 min) il metodo non fa in tempo a
partire per un record sincrono, che viene sfrattato prima.

## Disegno

Due strutture, due vite. Entrambe Caffeine, come gia' fa `IdempotencyStore`.

- `inFlight: Cache<String, ExecutionRecord>` con `expireAfterWrite(maxLifetime)`.
  Sostituisce il ramo `maxLifetime` del janitor ed e' la rete di sicurezza se una
  `settle()` viene dimenticata: il record scade da solo invece di restare per sempre.
- `outcomes: Cache<String, Outcome>` con `maximumSize(maxOutcomes)` e scadenza per
  voce (`ttl` se leggibile, `syncTtl` altrimenti). E' il tetto in spazio che il
  commento del 2026-08-23 diceva mancare.

`Outcome` e' un record piatto: `long` al posto di `Instant`, niente future, niente
task, niente `HashSet`. Da 35 oggetti a 4.

### La variante aggressiva, dentro `settle()`

- `readableAfterFinishing == true` (ASYNC, o con chiave): esito **completo**.
  Vincolo verificato: `ReactiveInvocationCoordinator:59` chiama
  `terminalResponse(record)` che legge `snapshot.output()` per servire il replay
  idempotente. Toglierlo li' non degrada, rompe.
- `readableAfterFinishing == false` (sync senza chiave): esito **senza**
  `output`/`headers`/`encoding`. Il chiamante li ha gia' ricevuti nel corpo della
  risposta di `:invoke`. `error` resta sempre: costa due stringhe ed e' l'unica cosa
  che ha senso rileggere se la connessione e' caduta.

## Le 9 transizioni terminali

Non esiste un punto di strozzatura unico: 7 delle 9 sono seguite dal completamento
della future, ma `ReactiveInvocationCoordinator:88` (`markTimeout`) e `:96`
(`markError`) no - li' il timeout arriva da Reactor e la future resta pendente.
Quindi `settle()` va chiamata esplicitamente, ed e' idempotente.

| file | riga | transizione |
|---|---|---|
| `ExecutionCompletionHandler` | 83/86 | offload completato |
| `ExecutionCompletionHandler` | 117 | offload fallito |
| `ExecutionCompletionHandler` | 269/272 | completamento normale |
| `ExecutionCompletionHandler` | 317 | retry esaurito |
| `ReactiveInvocationCoordinator` | 88 | timeout sincrono |
| `ReactiveInvocationCoordinator` | 96 | errore sincrono |
| `SyncQueueService` | 300 | funzione rimossa |
| `SyncQueueService` | 319 | timeout in coda |
| `AsyncQueueConfiguration` | 76 | funzione rimossa |

## Il punto delicato: l'idempotenza

`InvocationExecutionFactory:63` fa `executionStore.getOrNull(existingExecutionId)`
e, se non trova nulla, tratta la rivendicazione come stantia e **rifa girare la
funzione**. Dopo `settle()` il record non e' piu' in `inFlight`, quindi
`getOrNull` da solo produrrebbe una doppia esecuzione silenziosa - esattamente il
fallimento che la chiave esiste per prevenire.

La factory deve percio' vedere anche gli esiti archiviati.

## Cosa sparisce

Il thread janitor, `evictExpired()`, `StoredExecution`, `cleanupTtl`,
`ExecutionRecord.cleanup()`, il campo `cleaned`, e la scansione periodica che
attraversa 5,8 milioni di oggetti in vecchia generazione.

## Contratto pubblico

`openapi.yaml:767` dichiara `output` non obbligatorio e con `type: 'null'` fra i
tipi ammessi, quindi `output: null` e' **gia' conforme**. Cambia solo la prosa
"JSON output (present on success)". `headers` ed `encoding` non sono documentati
affatto (lo schema elenca 6 campi, il record Java ne restituisce 11): la deriva va
sanata nello stesso passaggio.

## Due bug, trovati chiedendosi chi cerca il record dopo `settle()`

**1. Perdita di slot di dispatch - INTRODOTTO QUI, CHIUSO.** Archiviare il record
alla transizione terminale lo toglie dai vivi. Ma `completeExecution` cerca il
record per id *proprio* per restituire lo slot di concorrenza che il dispatch sta
ancora tenendo (`completeUnderLock` chiama `releaseDispatchSlotOnce` anche quando
trova il record gia' terminale). Con un timeout sincrono che marcava e archiviava
mentre il dispatch era in volo, il completamento successivo non trovava piu' nulla
e **lo slot restava preso per sempre**: la concorrenza effettiva si sarebbe
degradata run dopo run, falsando proprio cio' che la campagna misura.

Invariante nuovo: **non si archivia mentre un dispatch e' in volo.** Il
coordinatore marca e basta; `settle()` sta in fondo a `completeExecution`, dopo la
contabilita' dello slot. Se il completamento non arrivasse mai, ci pensa
`maxLifetime`. Due test lo fissano: uno sul gestore, uno end-to-end sul
coordinatore.

**2. Future condivisa mai completata dopo un timeout sincrono - PREESISTENTE, CHIUSO.**
Verificato con una sonda che fallisce **anche su `main`**: dopo `markTimeout()`, un
dispatch che riesce subito dopo non completa la future, perche' `completeUnderLock`
esce su record terminale. Chi altro e' in attesa su quella future - un secondo
chiamante con la stessa chiave di idempotenza - aspetta fino al proprio timeout
anche se la funzione ha risposto in 60 ms.

Il fix e' minimo e non tocca l'invariante esistente: quando un risultato arriva per
un record gia' terminale *e riguarda il tentativo in corso*, la future condivisa
viene completata con la risposta vera. Lo stato registrato resta TIMEOUT - e' voluto
e gia' coperto da un test. `complete()` su una future gia' completata non fa nulla,
quindi non puo' sovrascrivere niente, e il ramo di retry non entra nella condizione
perche' `resetForRetry` riporta il record a QUEUED. Due test: uno che il chiamante
in attesa riceve la risposta, uno che il retry NON completa la future in anticipo.

Non confonde la campagna: la condizione richiede un timeout, e A3 ha
`function_timeout_total = 0` e `idemShare: 0.0`. Li' resta inerte.

## Risultato misurato (stesso banco di prova, 200.000 record, SerialGC)

| esito trattenuto | prima | dopo | |
|---|---|---|---|
| sincrona senza chiave | 1.330 B | **208 B** | 6,4x |
| sincrona senza chiave, payload 4 KB | 4.916 B | **153 B** | 32x |
| con chiave / asincrona (payload trattenuto per contratto) | 1.330 B | 799 B | 1,7x |

Riproducibile su tre esecuzioni consecutive, valori identici. I 208 B contro i 116
del prototipo su `ConcurrentHashMap` sono il costo dei nodi Caffeine: e' il prezzo
di `maximumSize` e della scadenza per voce, e vale la pena.

A 166k record: da 220 MB a **34 MB**, e da 815 MB a **25 MB** con payload da 4 KB.
E' quest'ultima riga il punto: la memoria dello store ha smesso di dipendere da
cosa restituiscono le funzioni.

`./gradlew build` verde, 397 test.

## Verifica ancora da fare

Rilanciare `A3-sync-3x` e confrontare con i tre run di baseline.
Previsione: pausa young sotto i 5 ms (regime dei run A1/A2 a ~77k record) e zero full GC.
