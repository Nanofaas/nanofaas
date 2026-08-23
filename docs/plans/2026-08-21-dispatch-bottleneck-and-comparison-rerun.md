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

**Regola di condotta.** Prima di proporre una correzione, vai a leggere il metodo
che credi lento e verifica che faccia davvero quello che pensi. Due volte su tre
non lo faceva.

**Seconda regola, pagata cara.** Ogni rapporto ha un denominatore, e va detto ad
alta voce. Questa indagine ha speso dieci run su Azure a strumentare il percorso
di dispatch perché «0,91 core» era stato letto contro gli otto della VM invece che
contro l'unico concesso dal chart (§20). Il dato che lo smentiva era in ogni
snapshot dal primo giorno. Quando un numero ti dice che qualcosa *non* è il collo
di bottiglia, chiediti rispetto a cosa, e verificalo.

**Quarta.** Un risultato di un confronto è vero *nella configurazione in cui è
stato misurato*, e la configurazione va scritta accanto al risultato. «G1 batte la
JVM» è rimasto in questo documento come proprietà delle build; era una proprietà
del core singolo, e si inverte al secondo (§22). Se non sai dichiarare in quale
regime vale una classifica, non hai ancora un risultato.

**Terza.** Una serie che nessuno raccoglie torna vuota, e vuota è
indistinguibile da «non è successo». Vale al livello del catalogo NanoLab **e** al
livello del `keep` di Prometheus nel chart: la seconda è costata una cella intera
prima che me ne accorgessi.

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

**Vale a un core, e solo lì.** Fra le build native la lettura regge: stesso `-O3`,
p95 695,6 ms con seriale contro 92,2 con G1, e il meccanismo è misurato — le build
seriali fanno *meno* raccolte ma ognuna costa 45 ms invece di 4,8.

Ma «G1 batte anche la JVM» è **falso da due core in su** (§22): a 2 CPU la JVM fa
p95 3,7 ms contro i 275,2 di G1, con zero scarti contro il 10,4%. L'ordinamento si
inverte esattamente fra 1 e 2 core, e questa matrice ha girato tutta a uno.

E il confronto non era nemmeno paritario: il baseline chiamato «JVM» girava con
`-XX:+UseSerialGC` e `-XX:TieredStopAtLevel=1` — collettore seriale e nessun
compilatore ottimizzante — mentre `native-o3-g1` aveva G1. La frase «non è il
nativo, è il collettore seriale» confrontava due build native mentre la terza era
anch'essa seriale.

Questa conclusione **non dipende** da nulla di ciò che segue: vale perché la
capacità era tenuta fissa in tutte le celle (§3.4).

**Ma i numeri assoluti sì.** Tutte e dodici le celle hanno girato contro un
control plane limitato a 1 CPU dal chart, strozzato per l'85% dei periodi al
picco (§20). Il confronto *fra* build resta valido — il vincolo era identico
ovunque — ma i ~435 rps non sono un tetto di nanoFaaS: sono il tetto di un core.
Una matrice che voglia numeri assoluti va rifatta con `controlPlaneCpu`
dichiarato.

### Cosa è già stato smentito — non rifarlo

| Ipotesi | Verdetto | Come è stata uccisa |
|---|---|---|
| «Il tetto di *dispatch* è la `sync-queue`» (vecchio titolo issue #197) | **Falsa** | Il tasso di dispatch per `word-stats-java` è identico con e senza: 227/s vs 228/s. La sync-queue costava **latenza e completamenti**: p95 557→91 ms, fallimenti 61,6→17,6 %, throughput utile 167→358 rps. Non strozzava l'ammissione, ritardava il lavoro già ammesso finché scadeva (max 7,58 s contro `timeoutMs` 5 s). Issue #197 corretta il 2026-08-21. |
| «`releaseSlot` non risveglia lo scheduler» | **Falsa** | `QueueManager.releaseSlot(String)` (`QueueManager.java:137`) fa già `notifyWork` se `queued() > 0`. Percorso: `ExecutionCompletionHandler.releaseDispatchSlot` → `QueueBackedEnqueuer` → `QueueManager`. Il fronte di risveglio c'è. |
| «Ogni dispatch fa un round trip alle API di Kubernetes nel wake-up gate» | **Falsa** | `DeploymentWakeUpGate.isEligible` (`:196`) richiede `scalingConfig != null && strategy == INTERNAL && minReplicas == 0`. Le funzioni del confronto **non hanno `scalingConfig`**, quindi `ensureReady` ritorna `completedFuture(null)` immediatamente. Nessuna chiamata, nessun timer. |
| «Il limite è la concorrenza perché gli slot sono saturi» | **Falsa** | N = λ·S = **0,45 su 2**. Gli slot sono vuoti per il 78 % del tempo. |
| «Il limite è la CPU delle *funzioni*» | **Falsa** | I pod funzione **non hanno alcun limite** (§22.1): `buildResources` non ne impone quando lo spec non li dichiara, e il confronto non li dichiara. Usano 0,27 e 0,12 core perché tanto serve loro. |
| «Il limite è la CPU del *control plane*» | **VERA — §20** | Era l'ipotesi che nessuno aveva formulato. `values.yaml:24` impone `limits.cpu: "1"`; al picco l'85,2% dei periodi CFS è strozzato, e a 4 CPU il dispatch passa da 303,7 a 843,4/s. |
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

**Come si legge — e come io l'ho letta male.** Avevo scritto: «un thread saturo
consuma 1,0 core da solo; al picco il processo sta a 0,91, quindi il thread di
dispatch non è CPU-bound, aspetta». **Il ragionamento divide per otto core della
VM. Il divisore era uno**, perché il chart impone `limits.cpu: "1"` al control
plane (§20). Su un core solo, 0,91 non è margine: è il muro.

`container_cpu_cores` da solo non può dirlo, perché misura la CPU **ottenuta**,
non quella **negata**. Le due si distinguono solo insieme:

```bash
# il denominatore: quante CPU crede di avere il runtime
python3 -c "
import json,statistics
from datetime import datetime
d=json.load(open('$S/jvm/run-1/metrics/prometheus-snapshot.json')); q=d['queries']
st=datetime.fromisoformat(d['start'])
w=lambda n:[float(p['value']) for p in q[n]['points']
            if 375<=(datetime.fromisoformat(p['timestamp'])-st).total_seconds()<405]
cores=statistics.mean(w('container_cpu_cores@control-plane'))
frac=statistics.mean(w('process_cpu_usage'))
print(f'core={cores:.2f} frazione={frac:.3f} -> CPU viste={cores/frac:.1f}')
"
# -> core=0.93 frazione=1.000 -> CPU viste=0.9
```

`process_cpu_usage` è una **frazione**: 1,00 significa «sto usando il 100% delle
CPU che ho». Stava a 1,00 in tutte e quattordici le celle archiviate, build JVM
inclusa dove la metrica è affidabile, e nessuno ha letto il denominatore.

La prova diretta è il throttling — `container_cpu_cfs_throttled_periods_total`
diviso `container_cpu_cfs_periods_total` — che fino al 2026-08-22 **non era
raccoglibile**: entrambi i job cAdvisor del chart finiscono con un `keep` che
nomina tre metriche e scarta il resto (`prometheus-configmap.yaml:128` e `:171`).

Questo separa davvero «shardare» (giusto se calcola) da «dare più CPU» (giusto se
è strozzato). Qui era strozzato.

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
- CPU del processo **0,91 core** al picco — che sembrava «nessun thread saturo» e
  invece era **il 91% dell'unico core concesso**, con l'85,2% dei periodi CFS
  strozzati (§20). Questa riga è la ragione per cui le sonde §10–§19 hanno cercato
  nel posto sbagliato.
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
considerato **solo** se la misura mostra il thread davvero saturo di CPU.

§19 ha misurato quel thread occupato al **6,8%**: non è saturo, e shardarlo
moltiplicherebbe i dormienti. Ma la ragione per cui dorme non è il park (§20): è
che il cgroup aveva finito il budget. Con 4 CPU il tetto si sposta di 2,8× senza
toccare una riga di questo percorso, quindi **nulla di §4.2–§4.4 va fatto prima**
di aver deciso il budget di CPU e rimisurato.

### 4.4 Il buco stretto

`Scheduler.java:132`: sostituisci il cleanup `state::releaseSlot` con quello che
passa da `QueueManager.releaseSlot(functionName)`, così anche un dispatch fallito
risveglia il ciclo. Una riga, da fare insieme al resto.

### 4.5 Risorse — per ultimo

**Correzione del 2026-08-22: questa sezione diceva il falso, ed era la prima da
guardare, non l'ultima.**

«Nessun limite CPU era impostato sui pod» vale per le **funzioni**, che lo
scenario registra senza blocco `resources`. Il **control plane** non arriva dallo
scenario ma dal chart, e `deploy/helm/nanofaas/values.yaml:24` gli impone
`limits.cpu: "1"`. Un limite da alzare c'era, ed era il vincolo (§20).

Le funzioni restano con margine: 0,27 e 0,12 core contro 500m di limite.

Lo scenario NanoLab ora dichiara `controlPlaneCpu`. Un confronto che non dichiara
la risorsa più scarsa dell'oggetto che misura lascia decidere il risultato a un
default di packaging — lo stesso difetto del `queueSize: 100` che i documenti
descrivono mentre ogni run registra 20.

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
- **Commit non pushati.** Il ramo `dispatch-instrumentation` (mcFaas e NanoLab) è
  cresciuto oltre questa lista; usa `git log` invece di fidarti di qui. Resta vero
  che `9387c60d` (fix `gcc-c++`, senza cui una build G1 non linka) vive solo sul
  ramo `docs/monografia-latex` e **non è su main**.
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

## 21. Le ottimizzazioni in ingresso non sono misurabili a 1 CPU

Run `azure-cpu1-inbound-opt-c2` (mcFaas `bb5c563a`), limite invariato a 1 CPU.
Lotto: stack trace soppressi sui rifiuti, rifiuto prima di costruire
l'esecuzione, sonda di reacquisizione rimossa, header di risposta letti una volta.

Al `peak900`: 287,9 dispatch/s contro 303,7 del baseline, attesa 48,39 contro
50,27 ms, p95 96,11 contro 96,78. **Nessun guadagno; il dispatch è più basso del
5%.**

Il difetto è nel disegno della misura. Con l'80% dei periodi strozzati la varianza
fra ripetizioni è dell'ordine del 5%, e questi cambiamenti valgono al più qualche
punto: una ripetizione sola non può distinguerli da zero. Un assetto strozzato è
il peggiore per misurare un risparmio di CPU, perché il quantum CFS domina.

**Regola che ne segue:** un cambiamento da pochi punti percentuali non si misura
con una cella da 25 minuti su Azure. Si misura in locale, come in
[`payload-passthrough.md`](../experiments/payload-passthrough.md) — gratis,
ripetibile, e in microsecondi per operazione invece che dentro il rumore. Su Azure
si portano solo le domande che cambiano il sistema di un fattore, non di un
margine.

I quattro cambiamenti restano corretti per conto proprio e vanno tenuti come
pulizia, non rivendicati come guadagno.

## 22. Lo sweep sul budget di CPU: 4 · 3 · 2 · 1 core

Notte del 2026-08-23. Quattro matrici complete — 4 build × 3 ripetizioni ciascuna,
**48 celle** — identiche in tutto tranne `controlPlane.resources.limits.cpu`.
Commit mcFaas `bb5c563a`, NanoLab `9f5ab50`. Provisioning tenuto fra le matrici:
`.dockerignore` esclude `**/build` e `.gradle`, quindi il contesto docker resta
identico e il layer `RUN ./gradlew nativeCompile` è un cache hit — i prepare dopo
il primo sono durati ~13 minuti invece di ~50, e lo sweep si è chiuso in 7h30
invece delle 12 stimate.

### Latenza e throughput

**`rps` non è capacità.** Il profilo è `ramping-arrival-rate`, ad anello aperto:
programma **195.776 arrivi** in 450 s e li emette secondo l'orario, qualunque cosa
faccia la piattaforma. Una build che tiene il passo segna esattamente 435,1 —
quindi la colonna dice «ha retto il ritmo del generatore, sì o no», **non** quanto
throughput potrebbe reggere. Tre righe a 435,0–435,1 non hanno la stessa capacità:
stanno tutte e tre sopra la soglia richiesta, e la colonna non dice di quanto.

Quanto siano lontane dal proprio limite lo dice il numero di VU che k6 deve
tenere occupate, cioè il lavoro in volo, su 1.200 disponibili:

| build (4 core) | iterazioni | rate | VU usate | scartate dal generatore |
|---|---:|---:|---:|---:|
| JVM | 195.776 | 435,1 | **7** | 0 |
| native-o3-g1 | 195.776 | 435,1 | 251 | 0 |
| native-o3 | 188.557 | 419,0 | **1.121** | **7.219** |

La JVM serve tutto con **sette VU su milleduecento**: non è vicina alla
saturazione, è a un ordine di grandezza di distanza, e il suo tetto reale resta
**ignoto** perché questo profilo non lo avvicina mai. G1 nativo regge lo stesso
ritmo tenendo 251 richieste in volo, trentasei volte tante. E `native-o3` scende
a 419 **non perché la piattaforma rifiuti**: k6 esaurisce le VU trattenute da
risposte lente e scarta gli arrivi che non riesce a emettere.

Da due core in su, quindi, questo esperimento non misura più capacità: misura
latenza sotto un carico che per la JVM è banale. Per trovarne il tetto serve un
profilo con picco molto più alto.

Ogni colonna porta la **dispersione sulle tre ripetizioni**, come deviazione
standard campionaria. Tre ripetizioni bastano a dire se due righe differiscono e
non bastano a caratterizzare una distribuzione: il `±` va letto come «quanto sono
finite distanti le tre run», non come intervallo di confidenza. Dove è `0.0` le
tre hanno concordato fino alla cifra stampata.

<!-- tabella:latenza -->
| cpu | build | n | rps | p95 (ms) | p99 (ms) | scarti % | dispatch |
|---:|---|---:|---:|---:|---:|---:|---:|
| **4** | JVM (seriale, C1) | 3 | 435.1 ± 0.0 | 3.2 ± 0.2 | 6.0 ± 0.2 | 0.00 ± 0.00 | 145.0 ± 0.0k |
|  | Native −Os, seriale | 3 | 415.5 ± 3.3 | 871.4 ± 42.8 | 1631.1 ± 384.4 | 15.84 ± 0.47 | 115.8 ± 1.2k |
|  | Native −O3, seriale | 3 | 418.4 ± 0.5 | 870.7 ± 31.3 | 1156.5 ± 9.4 | 19.09 ± 2.23 | 112.1 ± 3.3k |
|  | Native −O3, G1 | 3 | 435.0 ± 0.1 | 171.6 ± 5.2 | 293.9 ± 37.8 | 9.68 ± 0.28 | 130.0 ± 0.4k |
| | | | | | | | |
| **3** | JVM (seriale, C1) | 3 | 435.1 ± 0.0 | 3.3 ± 0.0 | 6.2 ± 0.1 | 0.00 ± 0.00 | 145.0 ± 0.0k |
|  | Native −Os, seriale | 3 | 415.9 ± 1.8 | 913.8 ± 87.9 | 1497.4 ± 301.5 | 17.02 ± 2.26 | 114.2 ± 3.6k |
|  | Native −O3, seriale | 3 | 419.4 ± 2.1 | 822.5 ± 33.6 | 1215.8 ± 89.9 | 16.69 ± 2.31 | 115.6 ± 3.0k |
|  | Native −O3, G1 | 3 | 435.0 ± 0.1 | 215.8 ± 9.9 | 328.5 ± 18.4 | 9.60 ± 0.17 | 130.2 ± 0.2k |
| | | | | | | | |
| **2** | JVM (seriale, C1) | 3 | 435.1 ± 0.0 | 3.7 ± 0.1 | 7.8 ± 1.0 | 0.03 ± 0.01 | 145.0 ± 0.0k |
|  | Native −Os, seriale | 3 | 415.2 ± 1.5 | 913.5 ± 51.5 | 1489.4 ± 351.0 | 17.70 ± 2.78 | 113.1 ± 3.9k |
|  | Native −O3, seriale | 3 | 420.6 ± 1.5 | 805.9 ± 14.1 | 1160.2 ± 69.4 | 15.89 ± 0.22 | 117.2 ± 0.4k |
|  | Native −O3, G1 | 3 | 434.9 ± 0.1 | 275.2 ± 5.1 | 434.9 ± 5.0 | 10.40 ± 0.09 | 128.8 ± 0.1k |
| | | | | | | | |
| **1** | JVM (seriale, C1) | 3 | 435.1 ± 0.0 | 113.7 ± 0.4 | 174.5 ± 10.5 | 26.03 ± 0.23 | 100.8 ± 0.3k |
|  | Native −Os, seriale | 3 | 422.8 ± 0.7 | 812.9 ± 23.9 | 1271.6 ± 30.2 | 28.37 ± 0.81 | 96.7 ± 1.3k |
|  | Native −O3, seriale | 3 | 421.9 ± 1.9 | 834.0 ± 39.6 | 1256.8 ± 61.5 | 25.64 ± 0.03 | 101.4 ± 0.4k |
|  | Native −O3, G1 | 3 | 435.1 ± 0.0 | 93.8 ± 1.9 | 156.8 ± 3.7 | 18.34 ± 0.53 | 114.7 ± 0.8k |
<!-- /tabella:latenza -->

### Risorse

`core medi` è la media sul `peak900`; `core picco` il massimo sull'intera run.
`strozz` è la frazione di periodi CFS strozzati, la metrica che fino al
2026-08-22 il chart scartava allo scrape.

<!-- tabella:risorse -->
| cpu | build | core medi | core picco | strozz | RSS MiB | coda media | attesa (ms) |
|---:|---|---:|---:|---:|---:|---:|---:|
| **4** | JVM (seriale, C1) | 1.11 | 2.31 | 0.0 % | 889 | 0.3 | 0.1 |
|  | Native −Os, seriale | 1.10 | 2.02 | 0.0 % | 550 | 4.8 | 1.6 |
|  | Native −O3, seriale | 0.98 | 2.21 | 0.0 % | 530 | 9.6 | 1.5 |
|  | Native −O3, G1 | 1.26 | 5.26 | 1.1 % | 565 | 0.0 | 2.6 |
| | | | | | | | |
| **3** | JVM (seriale, C1) | 1.18 | 2.76 | 0.0 % | 880 | 0.3 | 0.1 |
|  | Native −Os, seriale | 0.84 | 2.13 | 0.1 % | 545 | 10.2 | 1.9 |
|  | Native −O3, seriale | 0.86 | 2.00 | 0.0 % | 546 | 10.4 | 1.3 |
|  | Native −O3, G1 | 1.32 | 4.04 | 2.8 % | 560 | 1.2 | 1.7 |
| | | | | | | | |
| **2** | JVM (seriale, C1) | 1.24 | 2.52 | 0.9 % | 881 | 0.7 | 0.2 |
|  | Native −Os, seriale | 1.05 | 1.94 | 1.5 % | 538 | 9.4 | 2.0 |
|  | Native −O3, seriale | 0.91 | 1.87 | 1.1 % | 543 | 10.1 | 1.3 |
|  | Native −O3, G1 | 1.05 | 2.98 | 9.8 % | 559 | 5.2 | 2.8 |
| | | | | | | | |
| **1** | JVM (seriale, C1) | 0.81 | 1.61 | 23.0 % | 713 | 20.0 | 14.5 |
|  | Native −Os, seriale | 0.84 | 1.55 | 19.5 % | 469 | 18.9 | 13.4 |
|  | Native −O3, seriale | 0.91 | 1.63 | 18.3 % | 479 | 17.3 | 9.7 |
|  | Native −O3, G1 | 0.92 | 1.50 | 24.6 % | 553 | 18.9 | 7.3 |
<!-- /tabella:risorse -->

### La dispersione è essa stessa un risultato

Il `rps` della JVM ha `± 0,0` a tutti e quattro i budget, e il dispatch `± 0,0k`:
tre run indipendenti producono lo stesso numero. Non è una proprietà del
generatore — le build native, sotto lo stesso profilo, hanno `rps` fino a
`± 3,3` e dispatch fino a `± 3,9k`.

Dove le build seriali sono davvero erratiche è la **coda**. In dispersione
relativa (deviazione / media):

| | p95 | p99 |
|---|---:|---:|
| JVM @4 | 5,0 % | **2,8 %** |
| Native −Os @4 | 4,9 % | **23,6 %** |
| Native −O3, G1 @4 | 3,1 % | 12,9 % |

Sul p95 si assomigliano tutte, intorno al 3–5%. Sul p99 no: `native-os` varia del
**23,6%** fra una ripetizione e l'altra, cioè ±384 ms in valore assoluto. Una
build il cui p99 cambia di centinaia di millisecondi fra run identiche non è solo
lenta: è **imprevedibile**, ed è una proprietà che un confronto sui soli valori
medi non mostra.

C'è anche una lettura da non fare: gli scarti della JVM a 2 core hanno una
dispersione relativa del 22,1%, che sembra enorme finché non si guarda il valore
assoluto — `0,03 % ± 0,01`. Su quantità quasi nulle la dispersione relativa non
significa niente.

### Cosa dicono

**L'ordinamento si inverte fra 1 e 2 core.** A un core `native-o3-g1` batte la
JVM su tutto (p95 93,8 contro 113,7; scarti 18,3% contro 26,0%; 114.746 dispatch
contro 100.786). A due o più la JVM stravince, e non di poco: **p95 3,7 ms contro
275,2**, zero scarti contro il 10,4%, e nessuna coda — profondità media 0,7 su 20.

La conclusione della §1 vale quindi **soltanto a un core**. Non era sbagliata:
era una proprietà della configurazione, letta come proprietà delle build.

**Il ginocchio è a 2 core.** Da 2 a 3 a 4 non cambia praticamente nulla: la JVM
al picco chiede 2,3–2,8 core e non ne usa di più. Un deploy a 2 CPU compra tutto
il guadagno disponibile; il terzo e il quarto non comprano niente.

**Le build native seriali non tengono il passo nemmeno con quattro core liberi.**
415–420 rps, 16–19% di scarti, coda a ~10 su 20 — ma **0% di throttling e ~1,0
core usati su 4**. Coda piena, CPU disponibile, niente strozzatura: è la stessa
firma da cui è partita l'indagine sul dispatch, e stavolta la CPU non c'entra.
Per queste build un collo esiste davvero, ed è altrove. È l'unica domanda aperta
che questo sweep lascia.

**La memoria è il vantaggio che sopravvive al nativo.** 530–565 MiB contro gli
880 della JVM a ogni budget: un terzo in meno, costante. È il solo asse su cui la
compilazione nativa vince in modo non condizionato.

**G1 nativo consuma più CPU della JVM.** A 2 core mostra 9,8% di periodi
strozzati contro lo 0,9% della JVM, e picchi fino a 5,26 core a budget 4. Serve
più macchina per fare meno lavoro.

### Un'avvertenza sulla lettura

Due numeri della stessa riga vengono da finestre diverse: `coda media` e `core
medi` sono il `peak900`, gli scarti e i percentili sono l'intera run. `native-o3-g1`
a 4 core ha coda **0,00** al picco e 9,7% di scarti complessivi — i rifiuti stanno
in altre fasi, non al picco. Prima di raccontare quel dato va guardato per fase.

### Il difetto che questo sweep ha fatto emergere

La build chiamata «JVM» non è la JVM: `platform/control-plane/Dockerfile` la
avviava con `-XX:+UseSerialGC` **e** `-XX:TieredStopAtLevel=1`, cioè collettore
seriale e JIT fermo a C1, senza compilatore ottimizzante. Il commento sopra le
flag ne dava la ragione — *«lower memory overhead in single-core environments»* —
cioè esattamente l'assunzione che questo sweep ha demolito.

Quindi il confronto non è mai stato paritario: `native-o3-g1` aveva G1, il
baseline JVM no. E la §1 concludeva che «il collettore seriale costa latenza»
mettendo a confronto due build native, mentre la terza era anch'essa seriale.

E nonostante l'handicap la JVM vince a ≥2 core con p95 3,2 ms.

Le flag sono ora un build-arg `JVM_TUNING` (default identico a prima, mcFaas
`e8bd580f`), e un fattoriale 2×2 — collettore × tiering, NanoLab `ebd11ba` —
misura i due fattori separatamente a 1 e 2 core.

### 22.1 Chi impone quali limiti, e chi non ne impone

Tre blocchi `resources` diversi vivono in questo sistema e **non è vero che si
somigliano**. Sbagliare quale si sta guardando è costato due conclusioni sbagliate
in mezz'ora, la seconda costruita sopra la prima.

| chi | limite CPU | da dove | dichiarato dallo scenario? |
|---|---|---|---|
| **control plane** | **1 CPU** (finché non lo si dichiara) | `deploy/helm/nanofaas/values.yaml:24` | ora sì, `controlPlaneCpu` |
| **Prometheus** | 500m | `values.yaml:45` | no, ed è irrilevante per la misura |
| **pod funzione** | **nessuno** | — | lo scenario non dichiara `resources` |

I pod funzione non hanno limiti perché `KubernetesDeploymentBuilder.buildResources`
costruisce un `ResourceRequirements` **vuoto** quando `spec.resources()` è nullo, e
il confronto registra le funzioni senza quel blocco: il `comparison-manifest.json`
di una run non contiene né `resources` né `cpu`.

**Il tranello concreto.** Al `peak900` `word-stats-java` misura **0,491 core** di
media. Il `500m` di Prometheus è lì nello stesso file, e la coincidenza invita a
concludere «è appiccicata alla sua quota». Non lo è: non ne ha una. Il segnale che
lo smentiva era nei dati fin dall'inizio — i **picchi a 0,693 e 0,751**, che sopra
una quota rigida non possono esistere. Un valore che supera il limite che gli
attribuisci è una smentita, non un artefatto di misura.

### 22.2 Il peggioramento comune al picco, non spiegato

Fra `hold350` e `peak900`, a 4 core, la latenza media di servizio peggiora per
**tutte** le build:

| build | hold350 | peak900 | variazione |
|---|---:|---:|---:|
| jvm | 0,720 ms | 0,852 ms | +18% |
| native-o3-g1 | 0,611 ms | 0,719 ms | +18% |
| native-o3 | 0,902 ms | 1,348 ms | **+50%** |

Le prime due si muovono **identiche**. Un effetto uguale su build diverse non
viene dalle build: solo il +50% di `native-o3` è suo, ed è il collettore seriale
sotto un tasso di allocazione più alto.

Il +18% comune **non è spiegato**, e non è saturazione delle funzioni (§22.1).
Candidati, in ordine di plausibilità:

1. `function_latency` è misurata **dal control plane**, da quando spedisce a
   quando riceve il completamento. Sotto più carico i suoi event loop hanno più
   lavoro e la callback viene processata più tardi.
2. Contesa sulla VM fra control plane, due funzioni, Prometheus e k3s.
3. Comportamento di cache e predizione dei salti a throughput più alto.

**La misura che deciderebbe**: confrontare la durata vista *dalla funzione* con
quella vista *dal control plane*. Se divergono al picco il tempo è nel control
plane; se salgono insieme è nella funzione o nella rete. L'SDK Java espone già una
durata di esecuzione e **non la raccogliamo** — è una riga nel catalogo, non un
esperimento.

### 22.3 Il riscaldamento non è (soprattutto) il JIT

Latenza media di servizio per fase, 4 core, media di 3 ripetizioni (ms):

<!-- tabella:fasi -->
| build | warm40 | climb200 | hold200 | spike600 | hold350 | peak900 |
|---|---|---|---|---|---|---|
| JVM (seriale, C1) | 3.748 | 1.198 | 0.839 | 0.753 | 0.720 | 0.852 |
| Native −O3, G1 | 2.936 | 1.031 | 0.702 | 0.609 | 0.611 | 0.719 |
| Native −O3, seriale | 2.965 | 1.093 | 0.770 | 0.802 | 0.902 | 1.348 |
<!-- /tabella:fasi -->

La JVM scende di **cinque volte** fra `warm40` e `hold350` e poi è piatta: sembra
il JIT. Ma **le build native fanno la stessa curva**, con lo stesso fattore, e il
JIT non ce l'hanno. Quindi il grosso del riscaldamento è condiviso — pool di
connessioni, arene di buffer Netty, pagine toccate la prima volta, cache del
kernel, k3s che assesta il routing.

Attribuibile al JIT è soltanto lo **scarto** fra JVM e nativo: +0,81 ms a
`warm40`, che scende a +0,14 a regime. Reale, ma molto più piccolo di quanto la
curva grezza della sola riga `jvm` suggerisca.

Guardare una serie senza il suo controllo produce la spiegazione che ci si
aspettava. È lo stesso schema del `process_cpu_usage` letto senza denominatore.

## A. Configurazione di ogni esperimento

Questa sezione esiste perché il `comparison-manifest.json` **non registra il
budget di CPU, la concorrenza, la dimensione della coda né i commit**: registra
varianti, ripetizioni, ordine e immagini. Da una directory di run, quindi, non si
risale al regime in cui è stata misurata. Finché non lo fa il codice, lo fa questa
tabella.

### Invarianti — valgono per tutti gli esperimenti sotto

| | |
|---|---|
| infrastruttura | Azure `westeurope`, stack `Standard_D8s_v5` (8 vCPU, 32 GiB), generatore `Standard_D2s_v5`, k3s `v1.36.3+k3s1` |
| ambiente | `packages/nanolab/environments/azure-comparison.yaml` (gitignorato), `operator_source_cidr: auto` |
| profilo di carico | `runtime-comparison.js`, ad anello aperto, **195.776 arrivi programmati in 450 s**, picco 900 rps |
| funzioni | `word-stats-java` (100% del profilo) e `word-stats-javascript` (35%), un pod ciascuna, **nessun limite di CPU o memoria** (§22.1) |
| moduli control plane | `k8s-deployment-provider,async-queue` — niente sync-queue, niente autoscaler, niente governor |
| per funzione | `concurrency=2`, `queueSize=20`, `timeoutMs=5000`, `maxRetries=3` |
| ordine celle | repetition-major: ogni build una volta, poi tutte di nuovo |
| CPU control plane | **1 core** salvo dove indicato — il default del chart, non una scelta (§20) |

Il `queueSize` è 20 e non 100: `ResolvedFunction` di NanoLab lo scavalca a ogni
registrazione, e il 100 che i documenti della piattaforma descrivono non è mai
stato quello usato.

### Le sonde sul dispatch — una ripetizione, `native-o3-g1`, 1 CPU, 2/20

Tutte con la stessa configurazione; **cambia solo la strumentazione**, e ognuna è
una sonda meccanicistica, non un confronto statistico.

| directory raw | commit mcFaas | commit NanoLab | cosa aggiunge | §
|---|---|---|---|---|
| `azure-conc8-probe` | — | `ea01127` | concorrenza 8 invece di 2 | §3.2 |
| `azure-dispatch-instrumentation-c2` | `8af8c314` | `553b7a5` | durate di `offer`/`poll`, segnale→scheduler | §10 |
| `azure-dispatch-slot-hold-c2` | `df5efda1` | `07bbbf7` | possesso dello slot, CAS→rilascio | §11 |
| `azure-dispatch-reacquisition-c2` | `50e7d87a` | `2b7f01c` | rilascio→riacquisto — **sonda invalidata**, §14 | §12 |
| `azure-dispatch-reacquisition-segments-c2` | `3365c590` | `8e9674e` | segmentazione pre/post visita — **invalidata** | §13 |
| `azure-dispatch-scheduler-direct-probes-c2` | `7ed1e010` | `ce45fae` | submit sincrono, visite bloccate, segnali coalesced | §15 |
| `azure-dispatch-saturation-guard-c2` | `7e532fc9` | `ce45fae` | A/B: guardie `canDispatch()` | §16 |
| `azure-dispatch-wakeup-localization-c2` | `0d924d08` | `c77cfba` | timer sull'`activeFunctions.add` | §17 |
| `azure-dispatch-wakeup-split-c2` | `5247e2ff` | `635908e` | split `poll` / bookkeeping | §18 |
| `azure-scheduler-thread-accounting-c2` | `8b90889e` | `e6fa60b` | bilancio del thread: visita + attesa ≤ finestra | §19 |

I commit citati sono quelli della **strumentazione**, non quelli che archiviano i
raw: chi riproduce ha bisogno del codice, non del JSON.

### Gli esperimenti sul budget di CPU

| directory raw | CPU | build | rip. | commit mcFaas / NanoLab | cosa risponde | § |
|---|---:|---|---:|---|---|---|
| `azure-cpu1-throttling-c2` | 1 | `native-o3-g1` | 1 | `3323982a` / `9f5ab50` | il control plane è strozzato? | §20 |
| `azure-cpu4-c2` | **4** | `native-o3-g1` | 1 | `3323982a` / `9f5ab50` | togliere il muro sposta il tetto? | §20 |
| `azure-cpu1-inbound-opt-c2` | 1 | `native-o3-g1` | 1 | `bb5c563a` / `9f5ab50` | le pulizie in ingresso si vedono? (no, §21) | §21 |
| `azure-matrix-cpu4` | **4** | tutte e 4 | 3 | `bb5c563a` / `9f5ab50` | sweep sul budget | §22 |
| `azure-matrix-cpu3` | **3** | tutte e 4 | 3 | idem | idem | §22 |
| `azure-matrix-cpu2` | **2** | tutte e 4 | 3 | idem | idem | §22 |
| `azure-matrix-cpu1` | 1 | tutte e 4 | 3 | idem | idem | §22 |
| `azure-jvm-2x2-cpu2` | **2** | `jvm`,`jvm-g1`,`jvm-c2`,`jvm-g1-c2` | 3 | `e8bd580f` / `ebd11ba` | collettore × JIT | §23 |
| `azure-jvm-2x2-cpu1` | 1 | idem | 3 | idem | idem | §23 |

Lo sweep sul budget ha tenuto il **provisioning fra una matrice e l'altra**:
`.dockerignore` esclude `**/build` e `.gradle`, quindi il contesto docker resta
identico e il layer `RUN ./gradlew nativeCompile` è un cache hit. I prepare dopo
il primo sono durati ~13 minuti invece di ~50. Chi ripete lo sweep deve quindi
**non toccare i sorgenti mcFaas mentre gira**: una modifica invalida la cache e,
molto peggio, fa misurare alle matrici codice diverso.

### `azure-nosync` — la matrice originale

4 build × 3 ripetizioni, `9387c60d` / `6012bf3`, **1 CPU** (non dichiarata, il
default del chart), 2/20. È la matrice della §1. Non raccoglieva né il throttling
né i gauge del thread scheduler, e il suo baseline `jvm` girava con
`-XX:+UseSerialGC -XX:TieredStopAtLevel=1` cablati nel Dockerfile (§22).

### Cosa serve per riprodurre una qualsiasi di queste

```bash
export NANOFAAS_ROOT=/percorso/di/mcFaas          # il worktree, non il checkout
cd /percorso/di/nanolab
caffeinate -dimsu ./nanolab.sh compare \
  packages/nanolab/scenarios-v2/runtime-comparison-cpu<N>.yaml \
  --environment packages/nanolab/environments/azure-comparison.yaml \
  --run-dir packages/nanolab/runs/<nome> \
  --variants <elenco> --repetitions <n>
```

Più il commit giusto su **entrambi** i repository: gli esperimenti che cambiano
la strumentazione cambiano mcFaas *e* il catalogo di NanoLab, e una metrica
pubblicata dalla piattaforma ma non richiesta dal catalogo torna vuota senza dirlo.

## A-bis. Le tabelle sono generate, non scritte

Ogni tabella numerica di §22, §22.2, §22.3 e appendice B sta fra marcatori
`<!-- tabella:NOME -->` e viene prodotta da
[`../experiments/dispatch-bottleneck/build_tables.py`](../experiments/dispatch-bottleneck/build_tables.py)
leggendo i raw archiviati. Il documento possiede la prosa; fra i marcatori non
possiede nulla.

```bash
cd docs/experiments/dispatch-bottleneck
python3 build_tables.py raw                              # stampa
python3 build_tables.py raw --update=../../plans/2026-08-21-dispatch-bottleneck-and-comparison-rerun.md
```

Se rieseguirlo cambia qualcosa, il documento era andato alla deriva. Gli snapshot
delle quattro matrici sono archiviati **compressi** — 48 MB diventano 2,8 — e lo
script legge `.json` e `.json.gz` indifferentemente; verificato che le tabelle
generate dai due siano identiche.

Nello script vivono anche le tre scelte metodologiche che i numeri non mostrano,
accanto al codice che le applica: quali fasi contano come stazionarie, perché i
punti a 200 rps sono esclusi dal modello di CPU, e perché l'asse dei tassi deve
essere quello ottenuto e non quello offerto.

## B. Osservare le funzioni senza strumentarle

Il control plane vede le funzioni dall'esterno per costruzione, e cAdvisor vede i
loro container. Molto di ciò che serve a uno scheduler è già lì; questa sezione
registra il primo tentativo di estrarlo, **compresi i due modi in cui è andato
storto prima di funzionare**, perché sono ripetibili.

### Il costo di CPU di una funzione: fisso e marginale

`word-stats-java`, budget 4 core, fasi **dopo il riscaldamento**
(`spike600`, `hold350`, `peak900`), sole build che tengono il passo del generatore
(`jvm` e `native-o3-g1`), 18 osservazioni:

```
core = 0,168 + 0,351 CPU-ms per richiesta
```

<!-- tabella:modello-cpu-funzione -->
| | |
|---|---|
| osservazioni | 18 |
| costo fisso | **0.168 core** |
| costo marginale | **0.351 CPU-ms per richiesta** (errore standard 0.052) |
| significatività | marginale a **6.7 errori standard** sopra zero |
| R² | 0.738 |

| tasso | core totali | quota fissa |
|---:|---:|---:|
| 350 rps | 0.291 | 58 % |
| 600 rps | 0.379 | 44 % |
| 900 rps | 0.484 | 35 % |
<!-- /tabella:modello-cpu-funzione -->
A carico moderato **più di metà della CPU della funzione non serve le richieste**:
è GC, JIT, thread di background, scrape. È il numero che decide se conviene
consolidare una funzione su poche repliche cariche o spargerla.

### I due errori da non rifare

**Primo: adattare il modello su tutte le celle insieme.** Le stime per-cella del
marginale vanno da **−0,19 a +0,34** CPU-ms, con R² fra 0,02 e 0,63. Un marginale
negativo è fisicamente impossibile: il modello stava interpolando rumore. Il
controllo che lo rivela è gratuito — **l'immagine della funzione è identica in
tutte e sedici le celle**, quindi la scomposizione deve venire uguale, e se non
viene il modello è sbagliato, non i dati.

**Secondo: mettere sull'asse dei tassi il tasso *offerto* invece di quello
*ottenuto*.** A target 900 il tasso reale va da 890 rps a 4 core a ~300 a 1 core;
mescolando i budget si confrontano regimi diversi sullo stesso asse, e la CPU a
350 risulta più bassa che a 200. Vanno usate solo le celle che tengono il passo.

C'è anche un terzo effetto, più sottile: i punti a 200 rps (`hold200`,
`recover200`) cadono presto nella run e portano ancora il riscaldamento (§22.3).
Includerli sposta l'intercetta da 0,168 a 0,245 core, cioè del 46%.

### Quattro lacune, tutte risolvibili senza toccare le funzioni

1. **Il catalogo interroga le metriche per-funzione di UNA sola funzione.**
   `queries_for(function, ...)` costruisce il selettore per un nome, quindi di
   `word-stats-javascript` abbiamo la CPU del container ma **non** la latenza di
   servizio. Ogni analisi multi-funzione è cieca a metà. È la lacuna più grave.
2. **Nessun percentile su `function_latency`**: raccogliamo `count` e `sum`,
   quindi solo la media. Per l'ammissione conta la coda, non la media. Si abilita
   nella configurazione Micrometer del **control plane**.
3. **Nessun throttling per i container funzione**: il selettore aggiunto in
   `3323982a` è `container="control-plane"`. Oggi le funzioni non hanno limiti
   (§22.1), ma quando ne avranno servirà.
4. **Byte di rete per richiesta** da cAdvisor: distingue payload grandi da piccoli
   senza sapere nulla del contenuto.

### La sola cosa che richiede la funzione — e il canale esiste già

Il control plane non può dedurre quanto tempo la funzione abbia speso **dentro il
proprio handler**, separato da rete, accodamento interno e serializzazione.

Ma il meccanismo c'è: `ExternalDispatcher` legge già `X-Cold-Start`,
`X-Init-Duration-Ms` e `X-NanoFaaS-Function-Status` dalla risposta. Un
`X-Duration-Ms` è **un'intestazione in più**, non una strumentazione, e ricade
negli SDK che sono nostri.

Quella sola intestazione chiuderebbe la domanda aperta della §22.2: se la durata
vista dalla funzione resta piatta mentre quella vista dal control plane sale del
18% al picco, il tempo è nel control plane; se salgono insieme, è nella funzione
o nella rete.

### Cosa serve a uno scheduler, in ordine di valore

1. **Fisso contro marginale** — decide consolidamento e numero di repliche. Già
   derivabile, e la procedura è qui sopra.
2. **Frazione di CPU sul tempo di servizio** — decide la concorrenza utile: una
   funzione che aspetta la tollera alta, una che calcola no.
3. **Coda della distribuzione del servizio** — decide ammissione e timeout.
   Richiede la lacuna 2.
4. **Degrado contro richieste in volo** — è l'ingresso naturale del governor di
   concorrenza, e si costruisce correlando `function_inFlight` con
   `function_latency`, entrambi già pubblicati.

Le prime due non richiedono nulla di nuovo. La terza è configurazione. Solo il
tempo interno all'handler richiede la funzione, ed è un'intestazione.

## 23. Il fattoriale JVM: collettore × JIT

Mattina del 2026-08-23. Due matrici da 4 varianti × 3 ripetizioni a **2 e 1 core**,
`azure-jvm-2x2-cpu2` e `azure-jvm-2x2-cpu1`. Commit mcFaas `e8bd580f`, NanoLab
`ebd11ba`. Le quattro varianti sono i quadranti di collettore × tiering del JIT;
tutto il resto è invariato.

<!-- tabella:jvm-2x2 -->
| cpu | collettore + JIT | n | p95 (ms) | scarti % | dispatch | core | RSS MiB | strozz % |
|---:|---|---:|---:|---:|---:|---:|---:|---:|
| **2** | seriale + C1 *(baseline)* | 3 | 21.1 ± 4.4 | 1.01 ± 0.40 | 143.1 ± 0.8k | 0.91 ± 0.01 | 880 ± 3 | 7.0 ± 0.5 |
|  | **G1** + C1 | 3 | 46.6 ± 2.4 | 5.95 ± 0.29 | 133.8 ± 0.5k | 1.34 ± 0.08 | 1130 ± 6 | 10.7 ± 0.1 |
|  | seriale + **C2** | 3 | 2.5 ± 0.1 | 0.00 ± 0.00 | 145.0 ± 0.0k | 0.53 ± 0.09 | 969 ± 4 | 0.9 ± 0.1 |
|  | **G1 + C2** | 3 | 3.0 ± 0.0 | 0.10 ± 0.01 | 144.9 ± 0.0k | 0.71 ± 0.06 | 1287 ± 8 | 2.8 ± 0.2 |
| | | | | | | | | |
| **1** | seriale + C1 *(baseline)* | 3 | 118.1 ± 16.5 | 26.05 ± 2.71 | 101.1 ± 4.1k | 0.74 ± 0.07 | 715 ± 21 | 22.8 ± 0.7 |
|  | **G1** + C1 | 3 | 152.6 ± 7.1 | 33.08 ± 2.94 | 91.3 ± 4.5k | 0.94 ± 0.15 | 855 ± 29 | 34.6 ± 1.7 |
|  | seriale + **C2** | 3 | 18.2 ± 9.3 | 1.61 ± 0.90 | 142.1 ± 1.6k | 0.58 ± 0.03 | 957 ± 5 | 11.6 ± 1.2 |
|  | **G1 + C2** | 3 | 58.1 ± 2.4 | 7.74 ± 1.59 | 131.9 ± 2.3k | 0.69 ± 0.03 | 1209 ± 24 | 19.7 ± 3.9 |
<!-- /tabella:jvm-2x2 -->

Ogni colonna porta la dispersione sulle tre ripetizioni. Serve: a 1 core il p95
del baseline si sposta di **±16,5 ms** fra run identiche, che è più di quanto
valgano alcune delle differenze rivendicate altrove in questo documento.

Letto come effetti rispetto al baseline, che è la domanda per cui il fattoriale
esiste. L'ultima colonna dice se la differenza supera le due dispersioni messe
insieme — un criterio volutamente grezzo: con tre ripetizioni un test t
vestirebbe meglio la stessa informazione, e questa è l'affermazione più debole
che i dati sostengono:

<!-- tabella:jvm-effetti -->
| cpu | effetto | Δ p95 | Δ scarti | Δ dispatch | Δ core | Δ p95 supera la dispersione? |
|---:|---|---:|---:|---:|---:|---|
| **2** | solo collettore (G1) | +25.5 ms | +4.9 pt | -6.5 % | +0.42 | **separato** |
|  | solo JIT (C2) | -18.5 ms | -1.0 pt | +1.4 % | -0.38 | **separato** |
|  | entrambi | -18.1 ms | -0.9 pt | +1.2 % | -0.20 | **separato** |
| | | | | | | |
| **1** | solo collettore (G1) | +34.5 ms | +7.0 pt | -9.7 % | +0.20 | **separato** |
|  | solo JIT (C2) | -99.9 ms | -24.4 pt | +40.5 % | -0.16 | **separato** |
|  | entrambi | -60.0 ms | -18.3 pt | +30.5 % | -0.05 | **separato** |
<!-- /tabella:jvm-effetti -->

### Il JIT è tutto il guadagno, e il collettore è una perdita

**Restituire C2 è la correzione.** A 2 core il p95 passa da 21,1 a **2,5 ms**, gli
scarti da 1,0% a **zero**, e — il dato che sorprende — la CPU **scende** da 0,91 a
**0,53 core**. Codice ottimizzato fa lo stesso lavoro con meno istruzioni: il
costo di compilazione si ripaga molte volte in una run da 450 s.

A 1 core l'effetto è drammatico: p95 da 118,1 a **18,2 ms**, scarti dal 26,1% al
**1,6%**, dispatch **+40,5%**. La configurazione scelta «per ambienti a core
singolo» è proprio quella che a un core costa di più.

**G1 peggiora tutto, a entrambi i budget.** Da solo aggiunge +25,5 ms di p95 a 2
core e +34,5 a 1, con più scarti, meno dispatch e più CPU. E costa memoria:
da 880 MiB del baseline a 1130 (G1+C1) e 1287 (G1+C2).

**I due effetti non si sommano.** `G1 + C2` è **peggio** di `seriale + C2` su ogni
asse — 3,0 contro 2,5 ms a 2 core, 58,1 contro 18,2 a 1. Il collettore sottrae a
quello che il JIT guadagna.

**Conseguenza operativa:** togliere `-XX:TieredStopAtLevel=1` e **tenere**
`-XX:+UseSerialGC`. Una riga, e a 1 core vale il 40% di dispatch in più.

Tutte e sei le differenze superano la dispersione, quindi nessuna delle
conclusioni sopra poggia su rumore. Un numero però va preso morbido: `seriale+C2`
a 1 core misura **18,2 ± 9,3 ms**, cioè una dispersione pari a metà del valore.
Che sia molto meglio dei 118,1 del baseline è fuori discussione; *quanto* meglio,
con tre ripetizioni, no.

### 23.1 Lo stesso build in due matrici dà numeri diversi di 5,7×

Il baseline `seriale + C1` a 2 core è per costruzione identico alla riga `jvm` di
`azure-matrix-cpu2` nella §22. Non lo è nei risultati:

| stesso build, 2 core | sweep (§22) | fattoriale (§23) |
|---|---:|---:|
| p95 | **3,7 ± 0,1 ms** | **21,1 ± 4,4 ms** |
| p99 | 7,8 ms | 32,5 ms |
| scarti | 0,03 % | 1,01 % |
| dispatch | 144.981 | 143.069 |
| RSS | 881 MiB | 880 MiB |
| periodi strozzati | 0,9 % | **7,0 %** |

Le tre ripetizioni sono strette **dentro** ciascuna matrice — 3,6/3,8/3,7 contro
16,4/25,1/21,7 — quindi non è rumore campionario: sono due popolazioni diverse.
Dispatch e memoria coincidono, quindi il control plane è lo stesso; a divergere
sono latenza e throttling, cioè l'ambiente.

Non è spiegato. Il candidato più semplice è l'host Azure: le due matrici hanno
provisionato VM distinte a ore diverse.

**Ne discende una regola operativa, non un'ipotesi:** i confronti valgono
**dentro** una matrice, mai fra matrici. È la ragione per cui il baseline è stato
rieseguito qui invece di riusare quello della §22, e questa tabella quantifica
quanto sarebbe costato riusarlo: un fattore **5,7** sul p95. Vale anche per la
§22 stessa — l'ordinamento fra build regge, i valori assoluti di p95 sono
proprietà di quella nottata.

## 24. Il prossimo esperimento: alzare il carico su `seriale + C2`

Preparato il 2026-08-23, **non ancora eseguito**. Scenari
`runtime-comparison-load{2,3,4}x.yaml`: `jvm-c2` (seriale + C2, la
configurazione che la §23 indica), **2 core**, e la sola variabile è la scala del
profilo — picco a 1.800, 2.700 e 3.600 rps contro i 900 attuali.

### Perché 2×–4× e non +20/30/50 %

A 1× `seriale + C2` usa **0,53 core su 2** e serve l'intero carico offerto con
p95 2,5 ms e zero scarti. Aumenti di qualche decina di percento non lo farebbero
nemmeno sudare. Estrapolando dai dati del fattoriale:

| | dove | quanto sopra il picco attuale |
|---|---:|---:|
| CPU del control plane (`core = 0,225 + 0,344 CPU-ms/req`, R² 0,81) | ~5.157 rps | 5,7× |
| **limite di concorrenza** (2 slot ÷ 0,526 ms di servizio) | **~3.799 rps** | **4,2×** |

Il muro che si incontra **per primo non è la CPU**: è il limite di concorrenza,
intorno a 3.800 rps. Da cui 4× come estremo — la previsione è quantitativa e
falsificabile, e se il tetto non cade lì il modello di Little sul percorso di
dispatch è sbagliato, che sarebbe la scoperta più interessante possibile.

### La metrica di sicurezza, e cosa ha già rivelato

A questi tassi il **generatore** diventa sospetto: k6 gira su una
`Standard_D2s_v5`, due vCPU, e finora ha emesso al massimo 435 rps. Senza
misurarlo, un tetto a 2.500 rps sarebbe indistinguibile fra «la piattaforma non
ce la fa» e «k6 non ce la fa».

`dropped_iterations` e l'uso delle VU vivono in `k6-summary.json` e non in
`summary.json`, quindi non erano mai stati guardati. Ora sono una tabella:

<!-- tabella:generatore -->
| cpu | build | arrivi emessi | non emessi | VU max / disponibili | esaurito? |
|---:|---|---:|---:|---:|---|
| **4** | JVM (seriale, C1) | 195.8 ± 0.0k | 0 ± 0 (0.0 %) | 7 / 1200 | no |
|  | Native −Os, seriale | 187.0 ± 1.5k | 8802 ± 1497 (4.5 %) | 1191 / 1200 | **sì** |
|  | Native −O3, seriale | 188.3 ± 0.2k | 7427 ± 191 (3.8 %) | 1150 / 1200 | **sì** |
|  | Native −O3, G1 | 195.8 ± 0.0k | 0 ± 0 (0.0 %) | 235 / 1200 | no |
| | | | | | |
| **3** | JVM (seriale, C1) | 195.8 ± 0.0k | 0 ± 0 (0.0 %) | 8 / 1200 | no |
|  | Native −Os, seriale | 187.1 ± 0.8k | 8626 ± 813 (4.4 %) | 1200 / 1200 | **sì** |
|  | Native −O3, seriale | 188.7 ± 0.9k | 7029 ± 939 (3.6 %) | 1102 / 1200 | **sì** |
|  | Native −O3, G1 | 195.8 ± 0.0k | 0 ± 0 (0.0 %) | 373 / 1200 | no |
| | | | | | |
| **2** | JVM (seriale, C1) | 195.8 ± 0.0k | 0 ± 0 (0.0 %) | 17 / 1200 | no |
|  | Native −Os, seriale | 187.0 ± 0.6k | 8810 ± 611 (4.5 %) | 1192 / 1200 | **sì** |
|  | Native −O3, seriale | 189.3 ± 0.7k | 6505 ± 677 (3.3 %) | 1022 / 1200 | **sì** |
|  | Native −O3, G1 | 195.8 ± 0.0k | 0 ± 0 (0.0 %) | 434 / 1200 | no |
| | | | | | |
| **1** | JVM (seriale, C1) | 195.8 ± 0.0k | 0 ± 0 (0.0 %) | 111 / 1200 | no |
|  | Native −Os, seriale | 190.3 ± 0.3k | 5498 ± 300 (2.8 %) | 1066 / 1200 | **sì** |
|  | Native −O3, seriale | 189.9 ± 0.9k | 5902 ± 858 (3.0 %) | 1047 / 1200 | **sì** |
|  | Native −O3, G1 | 195.8 ± 0.0k | 0 ± 0 (0.0 %) | 70 / 1200 | no |
<!-- /tabella:generatore -->

**Le build native seriali avevano già esaurito il generatore**, in ogni matrice:
1191, 1200 e 1192 VU su 1200, con il 3–4,5 % degli arrivi mai emessi. I loro
415–420 rps della §22 sono quindi un risultato **congiunto** di piattaforma e
generatore, non una misura di capacità. La JVM, per contrasto, ne usa 7.

Da cui due precauzioni negli scenari nuovi: il pool di VU cresce con la scala
(600 × scala, con tetto a 2.400 perché ogni VU costa memoria su due vCPU), e
questa tabella va letta **prima** di qualunque affermazione su un tetto.

### Comando

```bash
export NANOFAAS_ROOT=/percorso/di/mcFaas
cd /percorso/di/nanolab
for s in 2 3 4; do
  caffeinate -dimsu ./nanolab.sh compare \
    packages/nanolab/scenarios-v2/runtime-comparison-load${s}x.yaml \
    --environment packages/nanolab/environments/azure-comparison.yaml \
    --run-dir packages/nanolab/runs/azure-load${s}x --variants jvm-c2 --repetitions 3
done
```

Nove celle, nessuna compilazione nativa: circa 2h30 in tutto.

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
