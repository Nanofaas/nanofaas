# Il collo di bottiglia del dispatch, e come rifare il confronto fra build

Destinatario: un agente che riprende questo lavoro **da zero di contesto** e deve
essere in grado di rifare l'indagine, non solo di eseguire una lista.

Data: 2026-08-21 · Repo: `mcFaas` (questo) e `nanolab` (checkout separato,
`../nanolab`, privato).

---

## 0. Come usare questo documento

Le sezioni §1–§3 sono lo **stato dell'indagine**: cosa è dimostrato, cosa è stato
smentito, e qual è l'ipotesi viva. Leggile prima di toccare qualsiasi cosa: tre
ipotesi plausibili sono già morte, e rifarle costa ore.

La §2 è la più importante e la più insolita: è il **metodo**. Contiene i comandi
esatti con cui i dati già su disco sono stati interrogati. Quasi tutte le
conclusioni di questo documento vengono da lì e **non** da run nuove su Azure.
Prima di provisionare qualcosa, chiediti se la domanda si risponde con una query
sulle snapshot che hai già.

Le sezioni §4–§9 sono operative: cosa fare, come rieseguire, come sorvegliare,
come riparare.

**Regola di condotta.** In questa indagine ho formulato tre ipotesi meccanicistiche
e ne ho uccise due leggendo il codice. Fai lo stesso: prima di proporre una
correzione, vai a leggere il metodo che credi lento e verifica che faccia davvero
quello che pensi. Due volte su tre non lo faceva.

---

## 1. Stato: cosa è stabilito e cosa è falso

### Il risultato solido

Matrice 4 build × 3 ripetizioni su Azure (k3s, due VM), carico aperto variabile
di 450 s su due funzioni (`word-stats-java`, `word-stats-javascript`):

| build | rps | p95 (ms) | p99 (ms) | scarti | picco RSS |
|---|---|---|---|---|---|
| JVM (Java 25, JIT) | 435,1 ± 0,0 | 106,7 ± 0,4 | 175,9 ± 4,3 | 24,86 % | 737,8 MiB |
| Native `-Os`, serial GC | 425,8 ± 8,6 | 514,0 ± 407,4 | 850,5 ± 644,5 | 45,37 % | 432,8 MiB |
| Native `-O3`, serial GC | 425,7 ± 1,5 | 695,6 ± 32,5 | 1067,6 ± 22,8 | 24,17 % | 500,0 MiB |
| **Native `-O3`, G1** | **435,1 ± 0,0** | **92,2 ± 1,5** | **147,7 ± 7,4** | **17,52 %** | 553,6 MiB |

**Non è la compilazione nativa a costare latenza, è il collettore seriale.**
Stesso `-O3`: p95 695,6 ms con seriale, 92,2 ms con G1. Il meccanismo è misurato —
le build seriali fanno *meno* raccolte ma ognuna costa 45 ms invece di 4,8 (49,8 s
di GC su 450 s di run contro 6,5 s della JVM). G1 batte anche la JVM su latenza,
scarti e memoria, a parità di throughput.

Questa conclusione **non dipende** da nulla di ciò che segue: vale perché la
capacità era tenuta fissa in tutte le celle (§3.4).

### Cosa è già stato smentito — non rifarlo

| Ipotesi | Verdetto | Come è stata uccisa |
|---|---|---|
| «Il tetto di *dispatch* è la `sync-queue`» (vecchio titolo issue #197) | **Falsa** | Il tasso di dispatch per `word-stats-java` è identico con e senza: 227/s vs 228/s. La sync-queue costava **latenza e completamenti**: p95 557→91 ms, fallimenti 61,6→17,6 %, throughput utile 167→358 rps. Non strozzava l'ammissione, ritardava il lavoro già ammesso finché scadeva (max 7,58 s contro `timeoutMs` 5 s). Issue #197 corretta il 2026-08-21. |
| «`releaseSlot` non risveglia lo scheduler» | **Falsa** | `QueueManager.releaseSlot(String)` (`QueueManager.java:137`) fa già `notifyWork` se `queued() > 0`. Percorso: `ExecutionCompletionHandler.releaseDispatchSlot` → `QueueBackedEnqueuer` → `QueueManager`. Il fronte di risveglio c'è. |
| «Ogni dispatch fa un round trip alle API di Kubernetes nel wake-up gate» | **Falsa** | `DeploymentWakeUpGate.isEligible` (`:196`) richiede `scalingConfig != null && strategy == INTERNAL && minReplicas == 0`. Le funzioni del confronto **non hanno `scalingConfig`**, quindi `ensureReady` ritorna `completedFuture(null)` immediatamente. Nessuna chiamata, nessun timer. |
| «Il limite è la concorrenza perché gli slot sono saturi» | **Falsa** | N = λ·S = **0,45 su 2**. Gli slot sono vuoti per il 78 % del tempo. |
| «Il limite è la CPU delle funzioni» | **Falsa** | I container funzione usano 0,27 e 0,12 core di media. Nessun limite CPU era impostato. |
| «Il limite è la dimensione della coda» | **Falsa** | Una coda più grande scambia rifiuti con attese: non aggiunge capacità di drenaggio. |
| «La serializzazione del payload pesa sul thread» | **Falsa** | I payload k6 sono ~120 byte (`runtime-comparison.js:87-92`). |

Resta un buco stretto e reale, ma **non** è il tetto: `Scheduler.java:132` passa
`state::releaseSlot` — il metodo di basso livello su `FunctionQueueState`, che non
segnala — come cleanup di fallimento. Un dispatch che lancia un'eccezione libera
lo slot senza risvegliare il ciclo. Percorso di eccezione, una riga di fix (§4.4).

---

## 2. Il metodo: interrogare i dati che hai già

I dati della matrice buona sono in **`../nanolab/packages/nanolab/runs/azure-nosync/`**
(4 directory per build × 3 run, più `comparison-manifest.json`,
`comparison-report.html`, `matrix.log`). Sono 8,5 MB e valgono 3h15 di Azure.
`packages/nanolab/runs/` è gitignorato: durevoli sul disco, non nel repo.

Struttura di una snapshot: `<build>/run-<n>/metrics/prometheus-snapshot.json`,
con chiavi `start`, `end`, `queries`. `queries` è un **dizionario** nome →
`{query, required, points:[{timestamp, value}]}`. I contatori sono cumulativi:
l'ultimo punto è il totale della run.

### Le query che rispondono alle domande vere

```bash
S=../nanolab/packages/nanolab/runs/azure-nosync

# quali serie esistono davvero in una snapshot
python3 -c "
import json; print(list(json.load(open('$S/jvm/run-1/metrics/prometheus-snapshot.json'))['queries']))"
```

**La domanda centrale — gli slot sono pieni o vuoti?** Legge di Little,
`N = λ·S`, con λ dai dispatch e S dalla latenza media di servizio:

```bash
python3 -c "
import json, statistics
S='$S'
for v in ['jvm','native-os','native-o3','native-o3-g1']:
    ns=[];ws=[];ss=[]
    for r in (1,2,3):
        try: d=json.load(open(f'{S}/{v}/run-{r}/metrics/prometheus-snapshot.json'))['queries']
        except FileNotFoundError: continue
        L=lambda n: (d[n]['points'][-1]['value'] if d[n]['points'] else 0)
        c,s,disp=L('function_latency_count'),L('function_latency_sum'),L('function_dispatch_total')
        qc,qs=L('function_queue_wait_count'),L('function_queue_wait_sum')
        ns.append(disp/450*(s/c)); ws.append(qs/qc*1000); ss.append(s/c*1000)
    print(f'{v:14s} N={statistics.mean(ns):.2f}  servizio={statistics.mean(ss):.2f}ms  '
          f'attesa={statistics.mean(ws):.1f}ms  rapporto={statistics.mean(ws)/statistics.mean(ss):.1f}x')
"
```

Restituisce:

```
jvm            N=0.45  servizio=1.98ms  attesa=12.8ms  rapporto=6.4x
native-os      N=0.31  servizio=2.61ms  attesa=9.4ms   rapporto=3.6x
native-o3      N=0.41  servizio=1.76ms  attesa=9.4ms   rapporto=5.3x
native-o3-g1   N=0.38  servizio=1.48ms  attesa=7.4ms   rapporto=5.0x
```

**Come si legge.** Il limite di concorrenza è 2. N = 0,45 significa che
mediamente **meno di mezzo slot su due è occupato**. Eppure la coda si riempie e
le richieste ci passano dentro 6 volte il tempo che passano in esecuzione.
*Coda piena + slot vuoti + attesa ≫ servizio* è la firma di un dispatcher che non
riesce a immettere lavoro, e **non** di un sistema a corto di capacità. È
l'osservazione da cui discende tutto il resto del documento.

**La CPU per fase di carico** — distingue «satura» da «in attesa»:

```bash
python3 -c "
import json, statistics
from datetime import datetime
q=json.load(open('$S/jvm/run-1/metrics/prometheus-snapshot.json'))['queries']['container_cpu_cores@control-plane']['points']
t0=datetime.fromisoformat(q[0]['timestamp'])
for nome,a,b in [('warm 40',0,30),('hold 200',90,150),('spike 600',160,190),('hold 350',320,365),('peak 900',375,405)]:
    v=[p['value'] for p in q if a<=(datetime.fromisoformat(p['timestamp'])-t0).total_seconds()<b]
    if v: print(f'{nome:12s} media={statistics.mean(v):.2f}  max={max(v):.2f} core')
"
```

```
warm 40      media=0.14  max=0.29 core
hold 200     media=0.41  max=0.64 core
spike 600    media=0.59  max=0.78 core
hold 350     media=0.65  max=1.07 core
peak 900     media=0.91  max=1.42 core
```

**Come si legge.** Un singolo thread saturo consuma 1,0 core **da solo**. Al picco
di 900 rps offerti l'intero processo — event loop Netty, scheduler, scrape
Prometheus, tutto — sta a 0,91 di media. Il thread di dispatch **non è
CPU-bound: aspetta.** Questo è ciò che separa «shardare» (giusto se calcola) da
«togliere l'attesa» (giusto se dorme). Qui dorme.

**Verificare un limite di configurazione invece di assumerlo:**

```bash
python3 -c "
import json, glob, collections
mx=collections.Counter()
for p in glob.glob('$S/*/run-*/metrics/prometheus-snapshot.json'):
    v=[x['value'] for x in json.load(open(p))['queries']['function_queue_depth']['points']]
    mx[max(v)]+=1
print('massimi di function_queue_depth:', dict(mx))
"
# -> {20.0: 11, 0.0: 1}
```

Undici celle su dodici toccano **esattamente 20,0** e mai 21. La coda è da 20,
non da 100. (La dodicesima con massimo 0,0 è un buco di scrape, non una cella
senza coda: le altre metriche sono normali.)

### Il tranello da cui viene quel 20

`CLAUDE.md` e `application.yml:46` dicono `queueSize: 100`. È il default **della
piattaforma**, e nanolab lo scavalca a ogni registrazione:
`ResolvedFunction.queue_size = 20` e `concurrency = 2`
(`nanolab/plans/functions.py:31-32`) finiscono nel payload come `"queueSize"` e
`"concurrency"` (`sonata_tasks/manifest.py:36`), e il valore per-funzione vince.

**Non fidarti di CLAUDE.md sui valori di runtime.** Descrive i default del control
plane, non ciò con cui gli esperimenti registrano. Il codice di registrazione è
l'unica fonte.

Nota correlata: l'override a 8 slot / coda 100 **esiste già** in
`loadtest.py:313`, ma è gated su `scaling_config is not None`, quindi si applica
solo alle run del governor. Il confronto ha girato a 2/20.

### Leggere il codice per uccidere un'ipotesi

Il pattern che ha funzionato tre volte: **non fermarti al nome del metodo, leggi
la guardia**.

- `wakeUpGate.ensureReady` *sembra* costoso; la guardia `isEligible` lo rende un
  no-op per le funzioni senza `scalingConfig`.
- `releaseSlot` *sembra* non segnalare; c'è un omonimo di basso livello su
  `FunctionQueueState` e uno su `QueueManager` che segnala. Il percorso reale
  passa dal secondo.

Comando utile per non confondere omonimi:

```bash
grep -rn --include='*.java' "releaseSlot(" platform/control-plane/src/main platform/modules/*/src/main
```

Attenzione con `grep -r` in questo repo: le directory `build/` contengono cache
JSON da centinaia di MB. Restringi sempre a `*/src/main` o usa `--include='*.java'`.

---

## 3. Il collo di bottiglia: evidenza e ipotesi viva

> **Aggiornamento 2026-08-21:** la sonda instrumentata descritta in §10 ha
> falsificato sia l'ipotesi park/unpark sia la contesa di `offer`/`poll` come
> cause dominanti. Questa sezione resta come traccia dell'ipotesi precedente,
> non come conclusione corrente.

### 3.1 Cosa dicono i numeri

- Slot occupati **0,45 su 2** — la concorrenza non è vincolante.
- Coda al massimo (20) con **42 563 rifiuti** su ~145 000 arrivi.
- Attesa in coda **6,4×** il tempo di servizio.
- CPU del processo **0,91 core** al picco: nessun thread saturo.
- Capacità teorica a 2 slot e 1,94 ms di servizio: **~1030 rps**. Dispacciati: **228**.

Il tetto sta fra «task in coda» e «slot acquisito». Non nella funzione, non nella
concorrenza, non nella coda, non nella sync-queue.

### 3.2 L'ipotesi viva: il thread si parcheggia ogni due dispatch

`concurrency = 2` e `MAX_BATCH_PER_FUNCTION = 2` (`Scheduler.java:20`)
**coincidono**. Nel ciclo (`Scheduler.java:115-140`):

```java
while (dispatched < MAX_BATCH_PER_FUNCTION && state.tryAcquireSlot()) { ... }  // 2 dispatch
if (state.queued() > 0 && dispatched > 0) signalWork(functionName);            // si ri-segnala
```

Sequenza con concorrenza 2:

1. dispaccia 2 → entrambi gli slot occupati;
2. `dispatched = 2 > 0` → si ri-segnala → `poll()` ritorna subito, nessun park;
3. `tryAcquireSlot()` **fallisce** → `dispatched = 0` → **niente ri-segnalazione**;
4. il giro dopo `activeFunctions.poll(500ms)` **parcheggia il thread**;
5. si risveglia solo quando un completamento chiama `notifyWork`.

**Ogni coppia di dispatch paga un ciclo park/unpark.**

Con concorrenza 8 la dinamica cambia da sé: dispaccia 2, si ri-segnala, ripolla
senza parcheggiare, altri 2, altri 2, altri 2 — fino a esaurire gli 8 slot.
**Un park ogni 8 dispatch invece di ogni 2.**

L'ipotesi spiega ogni osservazione: CPU bassa (un thread parcheggiato non
consuma), slot mediamente vuoti (sono vuoti *perché* il thread dorme), coda
piena, attesa ≫ servizio.

**È un'ipotesi, non un fatto.** Ha una previsione quantitativa falsificabile:
alzando la concorrenza a 8 il throughput deve salire in modo visibile, fino a ~4×
se il park domina. Se non si muove, l'ipotesi è morta come le altre tre e serve la
misura diretta (§4.0).

### 3.3 Il corollario sul dimensionamento

Se e quando gli slot torneranno a essere il vincolo, **non** dimensionarli dai
core. Little con l'obiettivo:

```
N = λ_obiettivo × S
```

A 1,5 ms di servizio servono 1,4 slot per 900 rps e ~8 slot per 5300 rps. La
regola «core × 2» vale per lavoro CPU-bound con slot vincolanti: qui non vale
nessuna delle due condizioni. E per `word-stats-javascript` — Node, thread
singolo — una concorrenza alta sposta soltanto la coda dentro al processo, dove
non la vedi e non la puoi rifiutare.

### 3.4 Perché la capacità era fissa, e perché è giusto così

Nessun adattamento durante la run: `effectiveConcurrency` è modificato solo dai
tre controller del modulo `concurrency-control`, che **non era caricato**
(`COMPARISON_MODULES = ("k8s-deployment-provider", "async-queue")`). Nessun
autoscaler, nessun `scalingConfig` → `KubernetesDeploymentBuilder` usa il default
`int replicas = 1`.

Lo schema dello scenario **rifiuta** un confronto adattivo
(`scenario.py:149`, messaggio *"the comparison profile compares control-plane
builds"*). La ragione: un governor sposterebbe i limiti *sotto* le build
confrontate, e la differenza risultante verrebbe attribuita alla build.

Conseguenza da tenere presente leggendo i risultati: **i ~435 rps non sono un
tetto di nanoFaaS**, sono il tetto di questa configurazione. Il confronto fra
build resta valido proprio perché la capacità era fissa.

---

## 4. Cosa fare, in ordine di costo

### 4.0 Se il punto 4.1 non muove il throughput: misura diretta

Due cose mancano per chiudere la questione senza ipotesi.

**(a) Le due serie che non vengono raccolte.** I gauge `function_inFlight` e
`function_effective_concurrency` **esistono già** nel control plane
(`QueueManager.getOrCreate`, registrati alla creazione della coda) ma non sono
nelle query. In `nanolab`,
`packages/nanolab/src/nanolab/metrics/catalogue.py`, dentro
`_async_queue_queries` (riga ~163, accanto a `function_queue_depth`) — è lì che
vanno, perché è `QueueManager`, cioè il modulo `async-queue`, a pubblicarli:

```python
PrometheusQuery("function_inFlight", f"function_inFlight{_fn(function_name)}"),
PrometheusQuery("function_effective_concurrency",
                f"function_effective_concurrency{_fn(function_name)}"),
```

`function_inFlight` è **camelCase** nel nome del meter: Micrometer non lo
normalizza. Il file ha un test di copertura che scandisce i sorgenti della
piattaforma e raggruppa per publisher — falla girare dopo la modifica.

**(b) Un timer sul thread dello scheduler**, attorno a
`invocationService.dispatch(task)` in `Scheduler.processFunction`
(`Scheduler.java:122-135`), più un contatore delle iterazioni del ciclo e uno dei
park (incrementalo quando `poll` ritorna `null` o quando `dispatched == 0`).

**Criteri di decisione:**

- molti park e timer basso → ipotesi §3.2 confermata → §4.2 e §4.3;
- timer ≈ 3-4 ms → il tempo se ne va *dentro* `dispatch()` → profila lì (JFR sul
  build JVM, filtrato sul thread `nanofaas-scheduler`);
- `function_inFlight` incollato a `function_effective_concurrency` → è la
  concorrenza a saturare e §3 va rivista da capo.

### 4.1 Concorrenza 8 e coda 100 per il confronto — nessuna ricompilazione Java

L'override esiste già in `_build_functions` (`loadtest.py:313`) ma è gated su
`scaling_config is not None`. Sganciarlo, o passare `concurrency` / `queue_size`
dal profilo comparison, è una modifica di poche righe **solo Python**: il control
plane legge questi valori dal payload di registrazione.

Poi **una cella sola**, per non spendere ore su un'ipotesi:

```bash
caffeinate -dimsu ./nanolab.sh compare \
  packages/nanolab/scenarios-v2/runtime-comparison-jvm.yaml \
  --environment packages/nanolab/environments/azure-comparison.yaml \
  --run-dir /percorso/DUREVOLE/probe-conc8 \
  --variants native-o3-g1 --repetitions 1
```

Confronta con `azure-nosync/native-o3-g1/run-1` usando la query Little della §2.
Numeri da guardare: `function_dispatch_total` (deve salire), il rapporto
attesa/servizio (deve scendere sotto 1×), N (deve avvicinarsi al limite).

Il report **si rifiuta di emettere un verdetto** con una sola ripetizione, ed è
voluto: questa è una sonda, non un risultato.

### 4.2 Sganciare il lotto dalla concorrenza

`MAX_BATCH_PER_FUNCTION = 2` è una costante compile-time. Il ciclo dovrebbe
dispacciare finché ci sono slot, non fino a un tetto fisso — l'equità fra funzioni
è già garantita dal re-inserimento in coda, non dal contatore. Una costante e una
condizione.

### 4.3 Strutturale: togliere il park dal percorso caldo

Il thread che rilascia lo slot sa già che c'è lavoro. Invece di segnalare un
thread parcheggiato e attendere l'unpark, può dispacciare direttamente: il
dispatch è già una catena di `CompletableFuture`, e l'unico lavoro seriale del
ciclo è l'ammissione (`tryAcquireSlot` + `poll`). `FunctionQueueState` è già
thread-safe (CAS su `inFlight`, `offer`/`poll` sincronizzati), quindi non servono
modifiche ai lock.

Lo **sharding** per funzione (`hash(functionName) % N`) è l'alternativa, e va
considerato **solo** se la misura mostra il thread davvero saturo di CPU. Oggi
non lo è (0,91 core per l'intero processo al picco): shardare un thread che dorme
raddoppia i dormienti.

### 4.4 Il buco stretto

`Scheduler.java:132`: sostituisci il cleanup `state::releaseSlot` con quello che
passa da `QueueManager.releaseSlot(functionName)`, così anche un dispatch fallito
risveglia il ciclo. Una riga, da fare insieme al resto.

### 4.5 Risorse — per ultimo

Il control plane è l'unico componente vicino alla saturazione (picco 1,42 core).
Le funzioni usano 0,27 e 0,12 core: non hanno bisogno di niente. Nessun limite
CPU era impostato sui pod (lo scenario non ha blocco `resources`), quindi non
c'è nemmeno un limite da alzare.

---

## 5. Rifare gli esperimenti

### Prerequisiti

```bash
export NANOFAAS_ROOT=/percorso/di/mcFaas     # obbligatorio, validato all'avvio
cd ../nanolab
az account show                              # devi essere loggato
```

L'ambiente Azure è `packages/nanolab/environments/azure-comparison.yaml`,
**gitignorato**. Se manca, copialo da `.example` e compila `resource_group` e
`ssh_key_path`. Lascia `operator_source_cidr: auto`: chiede alla VM da quale
indirizzo arrivi, a ogni provisioning, ed è ciò che evita di riaprire le NodePort
a `0.0.0.0/0` davanti a un control plane senza autenticazione.

### La matrice completa

```bash
caffeinate -dimsu ./nanolab.sh compare \
  packages/nanolab/scenarios-v2/runtime-comparison-jvm.yaml \
  --environment packages/nanolab/environments/azure-comparison.yaml \
  --run-dir /percorso/DUREVOLE/matrice-N \
  --repetitions 3 \
  > /percorso/DUREVOLE/matrice-N.log 2>&1
```

`caffeinate -dimsu` non è opzionale su macOS: la matrice dura ore e un Mac che va
in sleep uccide i tunnel SSH e con essi le celle in corso.

Lo scenario nomina `controlPlaneVariant: jvm`, ma `compare` lo sovrascrive per
ogni cella (`cell_scenario`): il file serve solo come base.

Opzioni: `--variants jvm,native-o3-g1` (sottoinsieme), `--repetitions 1` (sonda),
`--fresh` (rifà anche le celle complete), `--native-build-memory 6g` /
`--native-parallelism N` (solo se il prepare viene OOM-killed; su
`Standard_D8s_v5` da 32 GB non serve).

### Come è fatta

Due fasi. **Prepare** compila una volta sola le immagini delle funzioni e tutte e
quattro le build del control plane nel registry locale della VM. **Matrix**
esegue celle che non compilano niente: ognuna riceve la sua immagine come
`prebuilt_control_plane_image`, e `platform.py` salta del tutto la propria build.
È questo che impedisce a una cella di ricompilare in silenzio un artefatto diverso
sotto lo stesso nome.

L'ordine è **repetition-major**: ogni build una volta, poi tutte di nuovo.
Eseguire le tre ripetizioni di una build di fila farebbe coincidere l'asse «quale
build» con l'asse «quando è girata», e una deriva dell'host nell'arco delle ore
finirebbe interamente sulle build eseguite tardi, riportata come loro proprietà.

Provisioning tenuto per tutta la matrice (`keep=True`): un cluster, un registry.

### Tempi e costo

Prepare ~45–60 min (dominata dalle tre compilazioni native, ~20 min l'una, G1 su
Oracle GraalVM la più lenta). Ogni cella ~8,5 min. Matrice 4×3: **~3h15**.
Due VM (`Standard_D8s_v5` + `Standard_D2s_v5`) ≈ **0,48 $/h**.

---

## 6. Sorvegliare una run

`compare` **non mostra l'output dei comandi remoti**: `ConsoleProgressSink.emit`
scarta gli eventi senza `task_id`, quindi le righe di log non arrivano mai a
schermo per le celle. Il battito è un heartbeat ogni 60 s durante il prepare e le
righe di avanzamento dei task per le celle.

```bash
tail -f /percorso/DUREVOLE/matrice-N.log

# celle complete (una cella conta solo con ENTRAMBI i file)
find /percorso/DUREVOLE/matrice-N -name k6-summary.json | wc -l
find /percorso/DUREVOLE/matrice-N -name prometheus-snapshot.json | wc -l

# la VM è viva e sta facendo qualcosa?
az vm list -d -o table | grep nanofaas-comparison
ssh -i ~/.ssh/<chiave> azureuser@<ip> 'uptime; sudo k3s kubectl get pods -A'
```

Il load average sulla VM è il modo più affidabile per distinguere «sta
compilando» da «è bloccato» durante il prepare, che è muto per venti minuti.

**Sui monitor in background: fermali prima di cancellare i log su cui puntano.**
Questa indagine ha prodotto quattro falsi allarmi esattamente per non averlo fatto.

| Sintomo | Significato |
|---|---|
| cella finita in molto meno di 8 min | k6 morto presto; guarda `dropped_iterations` |
| `Prometheus query timed out` | §8, tunnel |
| `exit -1` | sentinella di paramiko per connessione morta, **non** SIGHUP |
| p95 identici fra due build diverse | il control plane non è stato ridistribuito; verifica il tag dell'immagine nel pod |
| `function_*` assenti dalla snapshot | meter per-funzione rimosso alla deregistrazione prima della raccolta |

---

## 7. Salvare i risultati

I dati stanno in `../nanolab/packages/nanolab/runs/azure-nosync/`, che è il
percorso che `compare` userebbe da solo (`ToolPaths.runs_dir` =
`packages/nanolab/runs`, gitignorato). Sono le uniche misure con G1 esistenti.

Se una run finisce in una directory temporanea, **copiala prima di ogni altra
cosa**. Il report si rigenera dalle directory senza cluster:

```python
from sonata_tasks.loadtest.comparison_report import WriteComparisonReport
WriteComparisonReport(task_id="", title="...", root=Path("...")).run()
```

---

## 8. Riparare al volo: guasti già visti

**Cella morta con `exit -1` o «channel closed without an exit status».**
Paramiko segnala una connessione morta, non un segnale. `compare` ritenta **una
volta** (`CELL_ATTEMPTS = 2`), tarato sul fatto che due k6 su otto sono morti
così. Un secondo fallimento di fila non è un blip: lascia che la matrice si
fermi. Riprendi con lo stesso `--run-dir`, le celle complete non si rifanno.

**Prometheus in timeout.** Su Azure le query passano da un tunnel SSH
(`_TUNNELLED_PROVIDERS` include `azure`) proprio per togliere di mezzo
l'indirizzo dell'operatore. Se va comunque in timeout: l'IP pubblico della VM
cambia a ogni deallocazione — durante questo lavoro è cambiato cinque volte.
Ricontrolla IP e regole NSG. Il client ritenta 3 volte con timeout 20 s sui
guasti di trasporto, ma **non** su un rifiuto: un rifiuto è una risposta.

**Ripresa in ordine sbagliato.** Ripresa e interleaving sono in tensione: una
ripresa esegue le celle mancanti nell'ordine in cui mancano, il che può
concentrare una build in una finestra temporale e reintrodurre la confusione che
l'interleaving evita. Se un'interruzione lunga ha lasciato buchi sparsi, valuta
`--fresh`: costa ore ma salva il confronto. **Annota sempre nel report se un
risultato viene da una ripresa.**

**`./gradlew: No such file or directory` o `Error: repo deploy not found`.**
Stessa causa: la working directory dell'executor è il checkout **solo su
multipass**; su Azure è la home. Ogni percorso passato a comandi remoti va reso
assoluto derivandolo da `remote_project_dir(...)`. Già corretto nel codice
attuale: se ricompare, è codice nuovo che l'ha dimenticato.

**Il build G1 non linka: `cannot find -lstdc++`.** Già risolto in
`deploy/native-java/Dockerfile`: serve `gcc-c++`, **non** `libstdc++-devel`.
Quest'ultimo su Oracle Linux 9 si installa senza errori e contiene solo
`libstdc++fs.a` — il build muore al link dopo undici minuti di compilazione. Un
test presidia la cosa (`test_native_builder_can_link_the_g1_collector`).

**Cache buildkit corrotta.** `docker builder prune -af`, **mai**
`docker system prune -a`: quest'ultimo cancella anche le quattro immagini appena
compilate, cioè un'ora di lavoro.

**Tentazione `kubectl set env` per un A/B veloce.** Riavvia il control plane e
**azzera il registry in-memory delle funzioni** — nanoFaaS non ha stato
persistente. Vanno riregistrate a mano, o la cella misura un sistema senza
funzioni.

**G1 non pubblica metriche JVM.** Non è un guasto: SubstrateVM non registra MXBean
sotto G1, quindi niente `jvm_memory_used_bytes`, niente `jvm_gc_pause_seconds`.
Per questo il profilo passa `jvm_metrics_required=False` e sposta la guardia su
`container_memory_bytes@control-plane`, che cAdvisor riporta per ogni build allo
stesso modo. **Non rialzare quel flag.**

---

## 9. Pulizia in sospeso

- **Infrastruttura Azure: distrutta** il 2026-08-21 (VM, dischi, NIC, IP pubblici,
  NSG, vnet — 12 risorse, zero rimaste). Con essa se n'è andato il registry locale
  con le quattro build: una nuova matrice ripaga l'ora di prepare da capo.
  Se rialzi un ambiente, ricorda che `az network nsg delete` fallisce finché la
  subnet lo referenzia: cancella prima le vnet, poi gli NSG.
- **Commit non pushati.** `mcFaas`: `9387c60d` (fix `gcc-c++`), `51794204`
  (monografia). `nanolab`: 12, dall'ambiente Azure fino a
  `6012bf3 feat: compare builds without the sync queue in front of them`.
- **Issue #197**: corretta il 2026-08-21 — nuovo titolo *"The sync queue costs 6× p95
  and 44 points of failures at an unchanged dispatch rate"*, corpo con la smentita in
  testa e una sezione finale sul secondo tetto (quello di `async-queue`) che rimanda
  a questo documento.
- **CI di nanolab esegue `basedpyright`**, non solo pytest:
  `uv run --locked --all-packages --all-groups basedpyright --project packages/<pkg>`.
  Suite verdi non bastano — un `str` al posto di un `Literal` ha rotto main.

---

## 10. Sonda instrumentata a concurrency 2

Run: `../nanolab/packages/nanolab/runs/azure-dispatch-instrumentation-c2/`,
`native-o3-g1`, una ripetizione, concurrency 2 e coda 20. Commit mcFaas
`8af8c314`; commit NanoLab `553b7a5`. Le 12 risorse Azure sono state distrutte
dopo la raccolta e il resource group non contiene residui del confronto.

L'instrumentazione misura durata totale di `offer`/`poll` (inclusa l'attesa del
monitor), segnale→scheduler, hit del batch e backlog contemporaneamente non vuoto
e dispatchabile. Risultato complessivo: `offer` 365 ns, `poll` 238 ns,
segnale→scheduler 222 µs. La peggiore media su una finestra di scrape da 5 s è
rispettivamente 634 ns, 434 ns e 638 µs. I monitor `synchronized` non spiegano
attese nell'ordine dei millisecondi.

Il dato decisivo è temporale. Durante `peak900` la coda è 19–20 in tutti i sei
scrape e `inFlight` è 2/2 in tutti e sei; durante il drain la coda è piena in
8/9 scrape e gli slot sono pieni negli stessi 8/9. Il backlog dispatchabile è
quasi sempre zero. Servizio e attesa al picco sono 4,319 ms e 35,792 ms, contro
0,719 ms e 0,048 ms a `hold200`.

La precedente applicazione di Little a medie sull'intera run non stazionaria
mescolava lunghi periodi leggeri con due burst saturi: `N=0,38` non significa che
gli slot fossero vuoti mentre la coda era piena. Nei periodi in cui la coda è
effettivamente piena, gli slot sono saturi. Il prossimo esperimento deve misurare
il tempo di possesso dello slot, dal suo acquisto al rilascio, per separare il
tempo remoto già osservato dal ritardo di completamento/callback che mantiene lo
slot occupato.

## 11. Durata esatta di possesso dello slot

Run: `azure-dispatch-slot-hold-c2`, `native-o3-g1`, una ripetizione, concurrency
2 e coda 20. Commit mcFaas `df5efda1`; commit NanoLab `07bbbf7`. Raw, protocollo,
tabella completa per fase e script di analisi sono versionati in
[`../experiments/dispatch-bottleneck/`](../experiments/dispatch-bottleneck/).

Il timer parte all'acquisizione CAS e termina in `QueueManager.releaseSlot`.
Sull'intera run misura 164,044 s / 115.859 dispatch = **1,416 ms**, contro
**1,491 ms** di `function_latency`. Al `peak900`: **347,6 dispatch/s**, slot
**4,047 ms**, latency **4,083 ms**, queue wait **32,125 ms**, coda media
**17,17/20**, utilizzo dei due slot **70,3%**. Restano **1,707 ms** medi per
ciclo-slot fuori dal timer mentre la coda è quasi piena; il wake-up medio nella
stessa fase è 484,6 µs.

Verdetto: nessun ritardo millisecond-level della callback trattiene lo slot oltre
la latenza già osservata. L'ipotesi è falsificata. La prossima sonda deve misurare
direttamente rilascio→successiva acquisizione per localizzare gli 1,707 ms di
capacità inattiva; non serve altra strumentazione nel callback.

## 12. Tempo diretto da rilascio a successiva acquisizione

Run: `azure-dispatch-reacquisition-c2`, `native-o3-g1`, una ripetizione,
concurrency 2 e coda 20. Commit mcFaas `50e7d87a`; commit NanoLab `2b7f01c`.
Raw, tabella per fase e script riproducibile sono in
[`../experiments/dispatch-bottleneck/`](../experiments/dispatch-bottleneck/).
Build nativa 891,5 s, push 5,1 s, k6 451,5 s. Le 12 risorse Azure sono state
distrutte e `caffeinate` è terminato dopo la run.

Il nuovo timer parte in `QueueManager.releaseSlot` solo quando resta backlog e
termina alla successiva acquisizione CAS riuscita. Al `peak900` misura
**3,161 ms** su circa il **97%** dei 9.502 dispatch della fase. Nella stessa
finestra: **316,7 dispatch/s**, slot **4,204 ms**, `function_latency`
**4,257 ms**, queue wait **41,621 ms**, coda media **17,33/20** e wake-up medio
**615,5 µs**. Sull'intera run sono stati misurati 31.332 intervalli, media
**2,038 ms**, su 114.527 dispatch Java.

Verdetto: l'ipotesi è confermata. Il gap millisecond-level esiste davvero fra il
rilascio e il successivo acquisto dello slot; non è tempo nascosto nella callback.
Il wake-up medio è più corto, ma conta 2,37 segnali per dispatch al picco e non è
la stessa popolazione del timer di reacquisizione: non sottrarre direttamente i
due valori. La localizzazione rimasta è interna al percorso dello scheduler fra
segnale, arbitraggio della funzione attiva e nuovo CAS. Prima di cambiare la
politica di scheduling, una sonda successiva dovrebbe separare questi tratti;
non serve ripetere build, GC, concorrenza o callback.

## 13. Segmentazione prima/dopo la visita dello scheduler

Run del 2026-08-22: `azure-dispatch-reacquisition-segments-c2`,
`native-o3-g1`, una ripetizione, concurrency 2 e coda 20. Commit mcFaas
`3365c590`; commit NanoLab `8e9674e`. Piano di implementazione:
[`2026-08-21-dispatch-reacquisition-segmentation.md`](2026-08-21-dispatch-reacquisition-segmentation.md).
Build nativa 872,8 s, push 5,0 s e k6 451,5 s. Le 12 risorse Azure sono state
distrutte; inventario finale vuoto e `caffeinate` assente.

Il segmento `active` inizia all'ingresso della visita `processFunction` o, se
successivo, al rilascio, e termina al CAS riuscito. È registrato insieme al timer
totale e ne condivide esattamente il conteggio; il tratto precedente si ottiene
per sottrazione delle somme. Al `peak900`: **4,788 ms** totali,
**4,743 ms pre-active**, **0,045 ms active→CAS**, copertura **100%** dei 9.501 dispatch,
queue wait **47,557 ms** e coda media **15,00/20**. Il pre-active rappresenta
il **99,1%** della reacquisizione. Sull'intera run: 32.025 conteggi per ciascun
timer, 3,024 ms totali, 2,994 ms pre-active e 0,030 ms active→CAS.

Verdetto originario: il ritardo sembrava precedere la visita utile dello
scheduler. Questo verdetto è invalidato dalla verifica descritta nella sezione
seguente.

## 14. Falsificazione della sonda e prossimo esperimento

Al `peak900`, concurrency 2 e 9.501 dispatch in 30 secondi danno un limite di
inattività pari a `2 * 30 / 9501 - 4,234 = 2,081 ms` per dispatch. Il timer di
reacquisizione riporta 4,788 ms: supera quindi il limite fisico e non può
rappresentare la popolazione dichiarata.

La causa è una race nella sonda. `releaseSlotAndGetHoldNanos()` rende disponibile
lo slot prima che `releasedWithBacklogAtNanos` riceva il timestamp. Lo scheduler
può acquisire lo slot in quella finestra; il timestamp pubblicato in ritardo
rimane poi in FIFO e viene associato a un'acquisizione successiva. Anche la
segmentazione active/pre-active eredita lo stesso pairing stale. I raw restano
validi come registrazione della run, ma i valori di reacquisizione non sono
evidenza causale.

Il prossimo esperimento usa solo misure dirette sul thread scheduler: durata
sincrona di `InvocationService.dispatch`, visite bloccate per assenza di slot e
segnali coalesced. Piano:
[`2026-08-22-dispatch-scheduler-direct-probes.md`](2026-08-22-dispatch-scheduler-direct-probes.md).

## 15. Risultato delle sonde dirette

Run del 2026-08-22: `azure-dispatch-scheduler-direct-probes-c2`, commit mcFaas
`7ed1e010`, commit NanoLab `ce45fae`, una ripetizione, concurrency 2 e coda 20.
Build nativa 878,9 s, push 5,0 s, k6 451,5 s. Raw e script di analisi sono in
[`../experiments/dispatch-bottleneck/`](../experiments/dispatch-bottleneck/).

Al `peak900`, 10.405 dispatch Java in 30 s (**346,8/s**) spendono **117,3 µs**
medi nel submit sincrono. Sulle due funzioni, 16.409 submit totalizzano **2,108 s**,
ossia solo il **7,0%** del tempo del thread scheduler. L'ipotesi che la
preparazione WebClient monopolizzi lo scheduler è falsificata.

Nella stessa finestra si contano **22.282** visite Java senza slot
(**2,14/dispatch**) e **4.611** segnali Java coalesced (**0,44/dispatch**).
Sulle due funzioni: **37.590** visite bloccate, **6.307** segnali coalesced e
16.409 dispatch; il rapporto blocked/dispatch complessivo è **2,29**. La CPU del
control plane è **0,91 core** medi. Il churn di funzioni già sature è quindi il
candidato rimasto, mentre il submit sincrono non lo è.

Verdetto: l'esperimento discrimina le ipotesi ma non dimostra ancora causalità.
Il prossimo test deve essere un'A/B minimale che evita enqueue e self-requeue
quando `FunctionQueueState.canDispatch()` è falso. Se blocked/dispatch crolla e
throughput/queue wait migliorano con lo stesso profilo, la causa è validata; in
caso contrario occorre misurare la durata completa delle visite.

Risultato complessivo: 115.986 dispatch Java, 435,06 richieste/s, p95 90,22 ms,
p99 151,21 ms, 17,33% scarti e 29.063 rifiuti Java. Le 12 risorse Azure sono
state distrutte; inventario finale vuoto e `caffeinate` assente.

## 16. A/B delle guardie di saturazione

Run del 2026-08-22: `azure-dispatch-saturation-guard-c2`, commit mcFaaS
`5b703f7b`, commit NanoLab `ce45fae`, una ripetizione, concurrency 2 e coda 20.
Le guardie evitano il segnale e il self-requeue quando `canDispatch()` è falso.

Al `peak900`: **342,6 dispatch Java/s**, queue wait **32,105 ms**,
**0,82** visite bloccate e **0,13** segnali coalesced per dispatch Java. Rispetto
al controllo diretto (346,8/s e 31,040 ms), non c'è miglioramento misurabile.

Verdetto: l'ipotesi che il churn di visite non dispatchable sia la causa dominante
è invalidata. I contatori calano, ma il limite resta. Raw e risultati sono nel
registro [`dispatch-bottleneck`](../experiments/dispatch-bottleneck/).

## 17. Localizzazione enqueue → scheduler

Run del 2026-08-22: `azure-dispatch-wakeup-localization-c2`, commit mcFaaS
`0d924d08`, commit NanoLab `c77cfba`. È stato aggiunto il timer sincrono
`function_scheduler_signal_enqueue_duration` attorno a `activeFunctions.add`.

Al `peak900`: enqueue **61,5 µs**, segnale→scheduler **995,2 µs**, residuo
**933,7 µs**. L'`add` è solo il 6,2% del timer complessivo: l'ipotesi che la
contesa nell'inserimento della coda attiva sia il collo dominante è invalidata.

## 18. Split del wakeup

Run del 2026-08-22: `azure-dispatch-wakeup-split-c2`, commit mcFaaS `994af3f3`
(strumentazione `5247e2ff`), commit NanoLab `635908e`, una ripetizione,
concurrency 2 e coda 20. Le sonde separano:

```text
signal → ritorno activeFunctions.poll() → fine bookkeeping → processFunction
```

Al `peak900`:

| tratto | media |
|---|---:|
| enqueue | 39,7 µs |
| signal → ritorno di `poll()` | 1.078,2 µs |
| bookkeeping dopo `poll()` | 5,6 µs |
| wakeup complessivo | 1.083,5 µs |

Il ritorno di `poll()` vale il **99,5%** del wakeup; il bookkeeping è escluso
come collo. Il costo è localizzato nel wakeup/scheduling del thread scheduler
prima del ritorno di `poll()`, ma non è ancora attribuito a una specifica politica
del sistema operativo o primitiva di attesa.

Risultato complessivo: 115.209 dispatch Java, 435,06 richieste/s, p95 94,24 ms,
p99 141,11 ms, 17,67% scarti e 29.840 rifiuti Java. `caffeinate` è stato usato
durante il run e rimosso al termine; teardown NanoLab completato e inventario
Azure vuoto. Raw, checksum e script sono in
[`docs/experiments/dispatch-bottleneck/`](../experiments/dispatch-bottleneck/).

## 19. Bilancio del thread scheduler

Run del 2026-08-22: `azure-scheduler-thread-accounting-c2`, `native-o3-g1`, una
ripetizione, 2/20. Commit mcFaas `8b90889e`; commit NanoLab `e6fa60b`. Piano:
[`2026-08-22-dispatch-scheduler-thread-accounting.md`](2026-08-22-dispatch-scheduler-thread-accounting.md).

Il timer di §18 parte dal **segnale**, non dalla chiamata a `poll()`: se il
thread fosse dentro `processFunction` per un'altra funzione quando il segnale
arriva, quel tempo sarebbe coda per il thread singolo, non latenza di risveglio.
Le due letture chiedevano rimedi opposti — shardare contro togliere il park — e i
dati fino a §18 non le distinguevano.

`scheduler_visit_duration` e `scheduler_idle_duration` partizionano il wall clock
del ciclo, senza tag `function`. Al `peak900` il bilancio chiude al **99,7%**:

| | |
|---|---:|
| thread occupato nelle visite | **6,8%** |
| thread fermo dentro `poll()` | **92,9%** |
| durata di una visita | 143,9 µs |
| visite per dispatch | 1,58 |
| timer segnale→`poll()` | 1.324,1 µs |
| coda media / attesa in coda | 16,67 su 20 / 52,5 ms |

**L'ipotesi «coda dietro le proprie visite» è falsificata.** Il thread non è
occupato: dorme per il 93% del tempo mentre la coda è quasi piena. I 1.324 µs
sono attesa reale. Lo **sharding è escluso** — moltiplicherebbe i dormienti,
come §4.3 avvertiva — e §4.2 (sganciare il lotto dalla concorrenza) non tocca il
punto, perché il lotto non è il vincolo con 1,58 visite per dispatch.

Meccanismo residuo da attribuire: con concurrency 2 e slot pieni `canDispatch()`
è falso, quindi `processFunction` non si ri-segnala e il thread si parcheggia; lo
risveglia solo il `notifyWork` di `QueueManager.releaseSlot`. Il prossimo
esperimento deve separare il ritardo di **emissione** di quel segnale dalla
latenza di **unpark** del SO — sono due cause con due rimedi diversi
(anticipare il segnale al rilascio contro cambiare la primitiva di attesa).

Questa è la prima sonda della serie che poteva falsificare sé stessa, e il
controllo ha lavorato: due finestre transitorie (`warm40` 100,6%, `ramp900`
100,1%) leggono `NO`, con uno sforamento ≤0,6% compatibile con scrape a 5 s su
finestre da 30 s. Tutte le finestre stazionarie, inclusa quella decisiva, stanno
sotto 100. La race di §14 sforava del 130%: la differenza di ordine di grandezza
è essa stessa il segnale.

## 20. Il tetto era la quota di CPU

Run del 2026-08-22: `azure-cpu1-throttling-c2` e `azure-cpu4-c2`, `native-o3-g1`,
una ripetizione, stesso profilo e stessa build. Unica variabile:
`controlPlane.resources.limits.cpu`. Commit mcFaas `3323982a`, NanoLab `9f5ab50`.

| al `peak900` | 1 CPU | 4 CPU |
|---|---:|---:|
| dispatch/s | 303,7 | **843,4** |
| periodi CFS strozzati | **85,2%** | **0,0%** |
| secondi strozzati (30 s) | 44,74 | 0,00 |
| attesa in coda | 50,27 ms | 0,34 ms |
| profondità coda | 20,00 / 20 | 3,33 |

**Il collo di bottiglia era la quota cgroup del control plane**, non il percorso
di dispatch. `deploy/helm/nanofaas/values.yaml:24` impone 1 CPU, nessuna delle
quattordici celle precedenti lo dichiarava, e le sonde da §10 a §19 hanno
misurato un thread fermo il 93% del tempo senza poter vedere *perché* fosse
fermo.

### Cosa questo invalida e cosa no

Le misure restano valide; le **inferenze causali** no. In particolare cade la
§2, che è il cuore metodologico di questo documento:

> «Un singolo thread saturo consuma 1,0 core da solo. Al picco l'intero processo
> sta a 0,91 di media. Il thread di dispatch non è CPU-bound: aspetta.»

Il ragionamento divide per otto core della VM. Il divisore era **uno**. E il
dato che lo diceva era già in ogni snapshot dal primo giorno: `process_cpu_usage`
misura 1,00 in tutte e quattordici le celle, build JVM inclusa, dove la metrica
è affidabile. Non è stato raccolto un dato nuovo — è stato letto un denominatore.

Resta valido il confronto fra build (§1): la quota era identica in tutte le
celle, quindi G1 batte il collettore seriale a parità di vincolo. Ma i ~435 rps
non sono un tetto di nanoFaaS: sono il tetto di un core.

Coerente a posteriori anche `azure-conc8-probe`, che alzando la concorrenza a 8
rese +3,69% invece del ×4 atteso, e che era rimasta senza spiegazione.

### La trappola sotto la trappola

Le tre query di throttling, aggiunte al catalogo, sono tornate **vuote**:
entrambi i job cAdvisor del chart terminano con un `keep` che nomina tre
metriche e scarta tutto il resto (`prometheus-configmap.yaml:128` e `:171`).
La domanda non era mai arrivata a Prometheus. Costo: una cella intera.

È lo stesso guasto un livello più in basso — una serie che non chiedi torna
vuota, e vuota è indistinguibile da «non è successo» — ma stavolta non era il
catalogo a non chiederla, era il chart a non lasciarla passare. Ora un test lo
presidia (`scripts/tests/test_e2e_k3s_helm_control_plane_native.py`), e pretende
la metrica in **entrambi** i job.

### Il compromesso da conoscere

Con 4 CPU la latenza *peggiora*: p95 96,78 → 157,65 ms, p99 150,32 → 254,87 ms.
Non è un regresso: con un core i rifiuti sono istantanei e tirano giù i
percentili, con quattro le stesse richieste vengono servite invece che respinte.
Rifiuti 31.157 → 14.503, fallimenti 18,79% → 9,33%. Confrontare percentili fra
le due configurazioni significa confrontare popolazioni diverse.

### Stato corrente

Il prossimo passo non è un'altra sonda sul dispatch. È decidere il budget di CPU
che il confronto vuole misurare — `controlPlaneCpu` ora lo rende dichiarabile
nello scenario — e rifare la matrice 4 build × 3 ripetizioni con quel budget
esplicito. Solo dopo, se un tetto resta, ha senso tornare al percorso di
dispatch: oggi il thread è fermo perché non ha CPU, non perché aspetti male.

Da fare prima di ottimizzare altro:

1. **Profilare.** Il 93% della CPU del control plane non è mai stato attribuito.
   JFR sulla build JVM sotto carico, in locale, senza Azure.
2. **Rimandare la riscrittura degli header** dopo l'ammissione
   (`InvocationController:84`): oggi ogni richiesta, incluse quelle rifiutate,
   alloca una mappa e una String per header prima di sapere se verrà servita.
3. Il minibatch verso le funzioni resta un'idea valida ma prematura: costa un
   protocollo su tre SDK e una revisione dell'accounting di concorrenza, per un
   beneficio misurato del 3,5% di un core.

**Trappola per chi riproduce:** il `pytest_configure` di NanoLab ripunta
`NANOFAAS_ROOT` su un `git archive` di HEAD. La guardia di copertura del catalogo
non vede modifiche non committate, quindi un controllo negativo eseguito prima
del commit mcFaas passa a vuoto.
