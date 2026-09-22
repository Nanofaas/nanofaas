# Containerd Rootless Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. L'utente ha scelto il coordinamento dei subagent e la selezione di modello/effort da parte dell'agente principale. Questa consegna aggiorna soltanto il piano: non avvia l'implementazione né i subagent.

**Goal:** Aggiungere un provider containerd+crun rootless, mutuamente esclusivo con Docker/Kubernetes, usando le librerie containerd-java e libcni-java e replicando gli scenari Docker.

**Architecture:** Mantenere `ManagedDeploymentProvider` come unico contratto del core. Estrarre lifecycle/proxy locali in una libreria interna e collegarla agli adattatori Docker e containerd, mantenendo Kubernetes indipendente. Eseguire il control plane nel contesto RootlessKit e indirizzare le repliche tramite IP CNI.

**Tech Stack:** Java 25, Spring Boot/Gradle esistenti, JUnit 5, Mockito/AssertJ, ArchUnit, containerd 2.2.1 come baseline delle librerie, crun, RootlessKit, CNI, GraalVM, NanoLab.

**Spec:** [2026-09-19-containerd-rootless-deployment-design.md](../specs/2026-09-19-containerd-rootless-deployment-design.md), proposta allegata da rivedere insieme al piano.

## Global Constraints

- Java 25; usare il package effettivo `it.unimib.datai.nanofaas` del repository.
- Branch `feat/containerd-rootless-deployment`, base `main` locale `05f49dcb`.
- Worktree `/home/michele/Documenti/nanofaas/.claude/worktrees/containerd-rootless`.
- Al massimo un provider di deployment nell'artefatto; `none` resta valido e `all` conserva Kubernetes.
- SPI `ManagedDeploymentProvider` invariata; nessun tipo containerd/Docker/K8s nel core.
- Containerd e crun rootless; rete mediante libcni-java/CniContainerNetwork.
- Libreria condivisa fuori da `platform/modules`, senza registrazione Spring automatica.
- Provision/reconcile/deprovision non entrano nel percorso caldo di ogni invocazione.
- Nessuna gestione VM nel repository NanoFaaS: gli E2E appartengono a NanoLab.
- Nessuna dipendenza da percorsi assoluti personali nelle build/versioni pubblicate.
- Ogni commit richiede `gitnexus detect-changes --scope all` completo, senza partial/truncated.
- Prima di ogni modifica di simbolo: impact sul grafo aggiornato del repository corretto.
- Nessuna pubblicazione remota, merge o modifica a host/VM eseguita da questo piano.

## Review Focus

1. CNI DEL/snapshot delete fallisce dopo stop o restart: mantenere ownership e consentire retry (Task 1, 5, 7).
2. IP CNI valido ma irraggiungibile dal control plane o callback errata: provare entrambi i versi e il confine RootlessKit (Task 4, 7).
3. Namespace/label errati e nomi lunghi: nessuna adozione/rimozione di risorse estranee, identificatori deterministici (Task 4, 5).
4. Due optional conflittuali rompono `all`, o bean di un provider escluso vengono istanziati (Task 3, 6).
5. Native image si compila ma non carica epoll/FFM/Gson/gRPC; cgroup rootless ignora limiti: verifica runtime reale (Task 1, 7, 8).

## Stato delle verifiche preliminari

Ispezionati sorgenti NanoFaaS da `main` e checkout locali delle due librerie. Nessuna
compilazione o suite runtime eseguita durante la pianificazione. Le coordinate
`io.nanofaas:containerd-java:0.3.0` sono dichiarate, non prova di pubblicazione.

GitNexus consultato nel checkout principale: indice `cad3fa7b`, diverso dalla base del
worktree. È evidenza preliminare e va rigenerato nel worktree prima di implementare.
`ContainerLocalDeploymentProvider`: **CRITICAL**, 414 elementi/134 processi segnalati;
caller diretto riportato `provision_startsMinReplicasAndReturnsStableProxyEndpoint`,
con limiti di risoluzione della dispatch via interfaccia e risultati anche da archivi.
`ManagedDeploymentProvider`: LOW, tre implementazioni dirette riportate e limite
esplicito della dispatch dinamica; non prova assenza di impatto sul core.
`ControlPlaneModulesPlugin`: UNKNOWN, non una valutazione di sicurezza; l'applicazione
del plugin è confermata da `settings.gradle` e dai test Gradle.

## Mappa dei file e responsabilità

Nella tabella e nei task, `D` =
`platform/modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider`,
`S` = `platform/container-deployment-runtime/src/main/java/it/unimib/datai/nanofaas/containerdeployment`,
`C` = `platform/modules/containerd-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/containerddeploymentprovider`.
Sono abbreviazioni di percorsi esatti; i test seguono lo stesso package sotto `src/test/java`.

| File/insieme | Responsabilità |
| --- | --- |
| `S/LocalManagedDeploymentProvider.java` | Lifecycle estratto, recovery, scaling e pending removal |
| `S/ContainerRuntimeAdapter.java`, `ContainerInstanceSpec.java`, `ManagedContainer.java` | Contratto interno runtime con endpoint esplicito |
| `S/LocalDeploymentSettings.java` | Callback/readiness comuni, nessuna proprietà Docker |
| `S/ManagedFunctionProxy.java`, `ManagedFunctionProxyFactory.java`, `RoundRobinFunctionProxy.java`, `RoundRobinFunctionProxyFactory.java`, `EndpointProbe.java`, `HttpEndpointProbe.java` | Codice esistente spostato con test |
| `D/ContainerLocalDeploymentProvider.java` | Wrapper Docker, backend ID e compatibilità nomi/metadati |
| `D/DockerJavaContainerRuntimeAdapter.java`, `CliContainerRuntimeAdapter.java` | Port allocation/URL e API specifiche Docker |
| `C/ContainerdDeploymentProvider.java` | Wrapper containerd, ownership/identità specifiche |
| `C/ContainerdRuntimeAdapter.java` | Traduzione richieste nella libreria e discovery |
| `C/ContainerdProperties.java`, `ContainerdDeploymentProviderConfiguration.java` | Configurazione rootless e wiring Spring |
| `C/ContainerdImageValidator.java` | Pull/verifica immagini tramite la libreria |
| `C/ContainerdRuntimeHints.java` | Solo metadata native aggiuntivi dimostrati necessari |
| `platform/modules/containerd-deployment-provider/{build.gradle,module.properties,README.md}` | Artefatto selezionabile e istruzioni |
| `platform/modules/containerd-deployment-provider/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | Registrazione nuova autoconfiguration |
| `platform/container-deployment-runtime/build.gradle`, `settings.gradle` | Libreria condivisa ordinaria |
| `platform/gradle-plugin/src/{main,test}/java/it/unimib/datai/nanofaas/gradle/` | Selettore, conflitti e regressione `all` |
| `deploy/containerd-rootless/`, `docs/deployment-containerd.md` | Launcher/configurazione di esempio e gestione operativa |

## Ordine e dipendenze

```text
Task 1 (libreria pronta) -------------------> Task 4 (adapter)
Task 2 (estrazione + regressione Docker) --> Task 4
Task 3 (selettore) ------------------------> Task 6 (artefatti)
Task 4 -> Task 5 (recovery) -> Task 6 -> Task 7 (NanoLab) -> Task 8 (native/consegna)
```

I task 1 e 7 producono commit anche in repository separati. Creare lì branch dedicati
e preservare eventuali modifiche locali; non copiare sorgenti nel repository NanoFaaS.
Completare ogni gate prima di procedere ai task dipendenti. Ogni task segue test
rosso → implementazione minima → test verde → analisi del grafo → commit.

## Coordinamento dei subagent, modelli ed effort

L'agente principale gestisce assegnazione, dipendenze, scelta del modello e del
reasoning effort, revisione, escalation e integrazione. Non richiede all'utente di
scegliere il modello per ogni task. Questa modalità sostituisce la precedente
raccomandazione di esecuzione inline.

La skill `superpowers:subagent-driven-development` prescrive la scelta esplicita del
modello in base a complessità/rischio/costo, un implementatore con contesto dedicato
per task, una revisione separata e la review finale sul modello più capace disponibile.
Le assegnazioni concrete e la politica dell'effort qui sotto sono decisioni di questo
piano: la skill non prescrive questa tabella dell'effort.

### Assegnazioni iniziali

| Task/ruolo | Modello implementatore | Effort | Modello reviewer | Effort review |
| --- | --- | --- | --- | --- |
| 1 — API libreria, rete persistente, cleanup e limiti rootless | `gpt-6-astra` | `high` | `gpt-6-astra` | `xhigh` |
| 2 — Estrazione lifecycle condiviso e regressione Docker | `gpt-6-astra` | `high` | `gpt-6-astra` | `high` |
| 3 — Selettore Gradle e conflitti | `gpt-5.6-terra` | `medium` | `gpt-5.6-sol` | `medium` |
| 4 — Adattatore containerd e configurazione rootless | `gpt-5.6-sol` | `high` | `gpt-6-astra` | `high` |
| 5 — Recovery, ownership e cleanup idempotente | `gpt-6-astra` | `high` | `gpt-6-astra` | `high` |
| 6 — Wiring e isolamento degli artefatti | `gpt-5.6-terra` | `high` | `gpt-5.6-sol` | `high` |
| 7 — Integrazione NanoLab e parità degli scenari | `gpt-5.6-sol` | `high` | `gpt-6-astra` | `high` |
| 8 — GraalVM e verifica finale degli artefatti | `gpt-5.6-sol` | `high` | `gpt-6-astra` | `high` |
| Sottotask puramente meccanico di configurazione/documentazione | `gpt-5.6-luna` | `low` | `gpt-5.6-terra` | `medium` |
| Review finale di tutti i branch e dei contratti tra repository | — | — | `gpt-6-astra` | `xhigh` |

La riga meccanica si applica solo a un incarico circoscritto con contenuto già
determinato: non affidare a Luna la progettazione del launcher rootless, la correzione
di GraalVM o l'interpretazione dei risultati E2E. Accorpare piccole modifiche della
stessa natura quando il costo di un altro dispatch supera il beneficio. Se restano
nel task principale, usarne implementatore e revisione senza creare agenti aggiuntivi.

### Politica dell'effort e controllo del costo

- `low`: trascrizioni e modifiche meccaniche a configurazione/documentazione.
- `medium`: implementazione già specificata e test ordinari con scope circoscritto.
- `high`: integrazione, debugging, recovery, concorrenza e invarianti del lifecycle.
- `xhigh`: decisioni architetturali delicate, problemi persistenti e review finale.
- `max` e `ultra`: eccezioni motivate da un problema concreto; mai default.

Specificare sempre sia `model` sia `reasoning_effort` al dispatch; non ereditare
implicitamente modello/effort della sessione principale. Usare `fork_turns="none"`
e consegnare un brief autonomo con task, spec, vincoli, file autorizzati, contratti
in ingresso/uscita, verifiche richieste e percorso del report. Non trasferire l'intera
conversazione. Esempio di parametri per il Task 3, da usare solo durante l'esecuzione:

```json
{
  "task_name": "task_3_selector",
  "model": "gpt-5.6-terra",
  "reasoning_effort": "medium",
  "fork_turns": "none"
}
```

Il costo si valuta sul lavoro completato, inclusi turni, contesto e tentativi di
correzione; un prezzo per token inferiore non garantisce un task più economico.
Registrare modello, effort, motivazione, esito e cambi di assegnazione nel ledger
del piano. Non dichiarare risparmi monetari misurati senza dati di utilizzo/prezzo.

### Revisione ed escalation

1. Dopo implementazione, test e self-review, un agente distinto verifica aderenza
   alla spec e qualità del diff. Riceve brief, report delle verifiche e diff completo
   del task, non soltanto l'ultimo commit. Non ripete test già eseguiti sulla stessa
   revisione senza una ragione concreta.
2. Correzioni iniziali: riprendere l'implementatore con i finding e test mirati.
   Aumentare effort/modello se emergono ambiguità architetturali, concorrenza o errori
   ripetuti; non attendere tentativi inutili solo per mantenere un modello economico.
3. Seguire il limite della skill di cinque round di correzione per task. Nei round
   4–5 assegnare un implementatore nuovo con modello superiore, quando disponibile:
   Luna → Terra → Sol → Astra. Se il task era già su Astra, usare contesto fresco ed
   effort `xhigh`; eventuali `max`/`ultra` richiedono una motivazione nel ledger.
4. Le ri-revisioni controllano i finding e le regressioni del diff di correzione.
   Per un fix piccolo e meccanico usare Terra `medium`; per invarianti di recovery,
   cleanup o concorrenza mantenere Astra `high`. Non risparmiare sulla revisione di
   una modifica rischiosa solo perché occupa poche righe.
5. Al limite dei round, il coordinatore valuta e registra ciascun finding secondo
   la skill. Una dipendenza strutturale irrisolta non diventa completata per effetto
   del limite: correggere il piano/contratto e non avviare lavoro che la assume valida.
6. Terminati i task, un reviewer nuovo Astra `xhigh` esamina i branch coinvolti,
   le integrazioni e i finding rinviati. Il coordinatore resta responsabile della
   verifica dei criteri di completamento e della consegna.

### Parallelismo e avanzamento

- Con la capacità attuale: massimo tre subagent attivi oltre al coordinatore;
  è un limite, non un obiettivo di utilizzo.
- Parallelizzare soltanto incarichi indipendenti con file e risorse assegnati in modo
  esclusivo. I subagent condividono il filesystem: evitare scritture sovrapposte,
  operazioni Git concorrenti nello stesso worktree e test che contendono daemon,
  namespace, porte, VM o repository Maven locale. Il coordinatore serializza queste
  operazioni anche quando le letture o implementazioni sono indipendenti.
- Task 1 può procedere insieme all'estrazione del Task 2 su repository distinti,
  purché i contratti del piano siano rispettati. Task 2 e 3 condividono build/file
  di progetto: eseguirli in sequenza nel worktree attuale. Task 4 attende i gate
  1 e 2; poi rispettare l'ordine 4 → 5 → 6 → 7 → 8 e il gate 3 prima di 6.
- Gli agenti non delegano a loro volta e non lanciano reviewer aggiuntivi: dispatch
  e revisione appartengono al coordinatore, evitando duplicazioni di costo.
- Conservare nel ledger task completati, commit, verifiche, review e decisioni;
  alla ripresa leggere ledger e Git prima di assegnare lavoro già svolto.
- Quando l'implementazione sarà avviata, proseguire tra i task senza chiedere ogni
  volta conferma di modello/effort. Restano validi i confini di autorizzazione e le
  richieste necessarie per azioni distruttive, pubblicazioni o blocchi reali.

### Task 1: Rendere containerd-java consumabile e affidabile per recovery/cleanup

**Files, repository `/home/michele/containerd-java`:**
- Modify: `build.gradle.kts`, `src/main/java/io/nanofaas/containerd/spi/Containers.java`.
- Modify: `src/main/java/io/nanofaas/containerd/internal/ContainersServiceImpl.java`.
- Modify: `src/main/java/io/nanofaas/containerd/ContainerSpec.java`, `internal/OciSpecBuilder.java` nello stesso package root.
- Modify se richiesto dalla prova cgroup: `src/main/java/io/nanofaas/containerd/spi/ContainerdClient.java`, `ContainerdClientBuilder.java`, `internal/TasksServiceImpl.java`.
- Test: `src/test/java/io/nanofaas/containerd/internal/ContainerNetworkLifecycleTest.java`, `ContainersServiceImplTest.java`, `OciSpecBuilderTest.java`, `src/test/java/io/nanofaas/containerd/ContainerSpecTest.java`.
- Create: `src/integrationTest/java/io/nanofaas/containerd/RootlessResourceLimitsIT.java`.
- Modify: `README.md`, `CHANGELOG.md`; aggiornare i test native per le nuove API.

**Interfaces:** consuma `ContainerNetwork.attach/detach` e `NetworkAttachment`; produce
`Containers.networkAttachment(String id): NetworkAttachment`, leggibile dopo riavvio
del client, `Containers.pendingRemovals(): List<Container>` e cleanup che fallisce
esplicitamente finché esistono risorse residue. `pendingRemovals` restituisce dal
journal persistente identità, labels e snapshot delle rimozioni incomplete, anche
quando il daemon non contiene più i metadata del container; non ricrea container.
Versione proposta per le API corrette: `0.4.0-SNAPSHOT`, fino al rilascio concordato.
Produrre due coordinate, `io.nanofaas:containerd-java` e `io.nanofaas:containerd-java-cni`;
la seconda dipende transitivamente dalla prima e da `io.libcni:libcni-java:0.1.0`.

- [ ] Aggiungere test per attachment recuperato da un nuovo client, CNI DEL fallita,
  snapshot remove fallita, assenza task, rimozione ripetuta e rollback dopo ADD parziale.
  Specificare il risultato persistente, non solo le chiamate del mock:

  ```java
  // Estendere il fixture gRPC già presente in ContainerNetworkLifecycleTest.
  NetworkAttachment allocated = new NetworkAttachment(
      List.of("10.90.0.2/24"), List.of("10.90.0.1"), List.of(), List.of(), null);
  // Dopo start, ricreare il client sullo stesso daemon e stateDirectory.
  assertThat(restarted.containers().networkAttachment(id)).isEqualTo(allocated);
  // Dopo un DEL fallito, container o cleanup journal devono restare discoverable.
  assertThatThrownBy(() -> restarted.containers().remove(id,
      RemoveOptions.builder().force(true).removeSnapshot(true).build()))
      .isInstanceOf(ContainerdException.class);
  assertThat(restarted.containers().pendingRemovals())
      .extracting(Container::id).contains(id);
  ```

- [ ] Eseguire `./gradlew test --tests '*ContainerNetworkLifecycleTest' --tests '*ContainersServiceImplTest'`.
  Atteso: fallimento sulle garanzie mancanti, non per ambiente container assente.
- [ ] Persistire l'attachment e i dati necessari al cleanup prima di dichiarare start
  riuscito. Riutilizzare la state directory esistente con scrittura atomica; distinguere
  attachment mancante da daemon irraggiungibile. Non ricavare IP con `exec ip addr`.
- [ ] Rendere cleanup riprendibile: tentare tutte le risorse, propagare gli errori,
  conservare identità rete/snapshot finché il loro cleanup non riesce. Coprire anche
  `remove` senza task e container metadata già rimossi. Non perdere l'errore primario
  durante rollback; collegare i secondari come suppressed.
- [ ] Aggiungere a `ContainerSpec.Builder` `cpuSetCpus(String)` e
  `memoryReservationBytes(long)` con le corrispondenti proprietà OCI. Provare CPU
  quota/weight, memory limit/reservation e cpuset nei cgroup delegati rootless.
  Se necessario aggiungere opzioni pubbliche per `systemdCgroup(boolean)` e
  `cgroupsPath(String)`, passando `SystemdCgroup` allo shim e `linux.cgroupsPath` allo
  spec; non assumere che configurare il plugin CRI influenzi il client gRPC nativo.
- [ ] Applicare `maven-publish`, pubblicare core e CNI con POM completi e metadata
  native. Verificare una piccola build consumer che dipende soltanto da CNI:

  ```groovy
  dependencies {
      implementation 'io.nanofaas:containerd-java-cni:0.4.0-SNAPSHOT'
  }
  ```

  Deve compilare importando `ContainerdClient`, `CniContainerNetwork` e i tipi libcni.
  Un JAR aggiunto con `files(...)` non costituisce il risultato richiesto.
- [ ] Pubblicare localmente libcni dal suo checkout (`./gradlew publishToMavenLocal`),
  poi core+CNI (`./gradlew test publishToMavenLocal`) e risolvere la build consumer.
  Pubblicazione remota separata dalla verifica locale; prima della consegna CI usare
  versioni pubblicate o checkout delle librerie a SHA fissati, mai snapshot mutevoli
  come unica sorgente riproducibile.
- [ ] Gate: test unitari verdi, consumer completo e prova reale rootless dei limiti;
  registrare digest/versioni. Analisi graph del repository, commit
  `Support recoverable rootless container networking`.

### Task 2: Estrarre il runtime locale condiviso senza regressioni Docker

**Files:** `S/*` e `D/*` della mappa; `platform/container-deployment-runtime/build.gradle`,
`settings.gradle`, `platform/modules/container-deployment-provider/build.gradle`.
Test: spostare nel nuovo progetto i test del proxy/readiness e aggiungere
`LocalManagedDeploymentProviderTest`; conservare nel modulo Docker
`ContainerLocalDeploymentProviderTest`, `ContainerLocalDeprovisionRecoveryTest`,
`R6DeprovisionFailureOwnershipRegressionTest`, test adapter e test architetturali.

**Interfaces:** la SPI pubblica resta identica. Il contratto interno proposto è:

```java
public interface ContainerRuntimeAdapter {
    boolean isAvailable();
    void pullImage(String image);
    ManagedContainer runContainer(ContainerInstanceSpec spec);
    void removeContainer(String name);
    List<ManagedContainer> listManagedContainers(String functionName);
}
public record ManagedContainer(String name, int replicaIndex,
                               String baseUrl, boolean running) {}
public record ContainerInstanceSpec(String containerName, String image,
    List<String> command, Map<String, String> env, ResourceSpec resources,
    Map<String, String> labels) {}
public record LocalDeploymentSettings(String callbackUrl,
    Duration readinessTimeout, Duration readinessPollInterval) {}
```

`LocalManagedDeploymentProvider` implementa la SPI e `AutoCloseable`, riceve backend
ID, settings, adapter, probe e proxy factory nel costruttore. Espone due punti di
variazione protetti `containerNamePrefix(String): String` e
`containerName(String,int): String` per preservare nomi Docker e validare nomi containerd.
Usare questi stessi metodi in provision, reconcile e validazione metadata persistiti.
`ContainerProxyProperties` resta nel wiring Docker; trasformare la configurazione del
proxy estratto in parametri neutrali, senza prefisso Spring Docker nella libreria.

- [ ] Rigenerare l'indice nel worktree, eseguire impact dei simboli spostati e conservare
  il warning CRITICAL. Usare rename/move di GitNexus dove disponibile; niente sostituzioni
  globali per rinominare simboli.
- [ ] Stabilire baseline con
  `./gradlew :control-plane-modules:container-deployment-provider:test --no-parallel`.
  Registrare separatamente gli integration test che necessitano del daemon.
- [ ] Aggiungere un test che dimostri che il lifecycle usa l'endpoint restituito dall'adapter:

  ```java
  when(adapter.runContainer(any())).thenReturn(
      new ManagedContainer("nanofaas-echo-r1", 1, "http://10.90.0.2:8080", true));
  provider.provision(spec);
  verify(probe).awaitReady(eq("http://10.90.0.2:8080"), any(), any());
  verify(proxy).updateBackends(List.of("http://10.90.0.2:8080"));
  ```

  Usare il fixture `FunctionSpec` del test esistente. Eseguire prima della modifica:
  il vecchio contratto void/non pubblico deve impedire il caso richiesto.
- [ ] Spostare lifecycle, DTO e proxy nella libreria; rendere pubblici solo i tipi
  necessari agli adapter. Docker riceve `PortAllocator` nel proprio adapter e ricostruisce
  gli stessi URL di oggi, incluso discovery delle porte per il recovery.
- [ ] Conservare backend `container-local`, nomi e `CONTAINER_NAME_PREFIX`, variabili
  riservate, timeout PATCH, bound di concorrenza, readiness, zero repliche e close
  senza distruzione. Estrarre codice, non riscriverne gli algoritmi.
- [ ] Testare endpoint IP e nome/porta, vecchi metadata persistiti Docker, fallimento
  di provision, pending removal e doppio deprovision. Verificare con ArchUnit che la
  libreria non dipenda da Docker/Fabric8/containerd né dal core concreto.
- [ ] Eseguire test nuovo runtime e intera suite Docker. Gate: stessi comportamenti
  Docker prima/dopo; analisi graph e commit `Extract shared local deployment runtime`.

### Task 3: Estendere selezione esclusiva e correggere `all`

**Files:** nuovo `platform/modules/containerd-deployment-provider/{build.gradle,module.properties}`,
descrittori Docker/K8s, `ControlPlaneModulesPlugin.java`, `ControlPlaneModulesPluginTest.java`,
`RepositoryModuleDescriptorsTest.java`, `ModuleConstraintResolverTest.java` nei percorsi della mappa.

**Interfaces:** nuovo module ID `containerd-deployment-provider`; default false;
nessuna dipendenza forte verso un altro provider. I conflitti sono simmetrici.

- [ ] Estendere il fixture TestKit con i tre descrittori e verificare la matrice:
  ciascun singolo provider accettato, ciascuna coppia e la tripla rifiutate;
  `all` include solo Kubernetes; `none` include zero provider; default invariato.
- [ ] Eseguire `./gradlew -p platform/gradle-plugin test --tests '*ControlPlaneModulesPluginTest'`:
  il caso `all` a tre provider deve fallire sul controllo pari priorità attuale.
- [ ] Aggiungere il descrittore:

  ```properties
  schemaVersion=1
  id=containerd-deployment-provider
  defaultEnabled=false
  requires.strong=
  requires.oneOf=
  requires.weak=
  conflicts=container-deployment-provider,k8s-deployment-provider
  ```

- [ ] In `selectAll`, eliminare prima gli optional che confliggono con default
  selezionati; poi validare i conflitti fra i candidati rimasti. Due default
  conflittuali o due optional rimasti conflittuali restano errori. Non introdurre
  precedenze alfabetiche o nuovi flag di priorità.
- [ ] Aggiornare inventory/conflitti nei test repository. Il build del nuovo modulo
  include solo dipendenze necessarie e la libreria condivisa; viene completato nel Task 6.
- [ ] Eseguire `./gradlew -p platform/gradle-plugin test`; gate tutti i selettori verdi,
  graph analysis e commit `Support three exclusive deployment providers`.

### Task 4: Implementare adattatore containerd e configurazione rootless

**Files:** `C/ContainerdRuntimeAdapter.java`, `ContainerdProperties.java`,
`ContainerdDeploymentProvider.java`, `ContainerdImageValidator.java`, build del modulo.
Test: `ContainerdRuntimeAdapterTest.java`, `ContainerdPropertiesTest.java`,
`ContainerdDeploymentProviderTest.java`, `ContainerdImageValidatorTest.java` nel nuovo modulo.

**Interfaces:** implementa il contratto interno del Task 2; usa API pubbliche corrette
dal Task 1 e `ImageValidator.validate(FunctionSpec)` esistente. Backend ID `containerd`.

- [ ] Scrivere test con client/container/images mock: immagine, command/env, label,
  CPU/memoria/cpuset, start+attachment, IP IPv4/IPv6 e assenza IP. Caso minimo:

  ```java
  when(containers.networkAttachment("nanofaas-echo-r1")).thenReturn(
      new NetworkAttachment(List.of("10.90.0.2/24"), List.of(), List.of(), List.of(), null));
  ManagedContainer running = adapter.runContainer(instance);
  assertThat(running.baseUrl()).isEqualTo("http://10.90.0.2:8080");
  verify(containers).start("nanofaas-echo-r1");
  ```

- [ ] Eseguire il test e confermare fallimento; implementare create/start attraverso
  `ContainerdClient`. URL IPv6 con host tra parentesi quadre; attachment senza IP
  deve causare errore e cleanup, non endpoint fittizio.
- [ ] Configurare il client in un solo punto:

  ```java
  ContainerdClient.builder()
      .socketPath(properties.socketPath())
      .namespace(properties.namespace())
      .runtimeBinaryName(properties.runtimeBinary()) // default "crun"
      .stateDirectory(properties.stateDirectory())
      .network(CniContainerNetwork.builder()
          .pluginDir(properties.cniPluginDirectory())
          .configDir(properties.cniConfigDirectory())
          .cacheDir(properties.cniCacheDirectory())
          .pluginTimeout(properties.cniPluginTimeout()).build())
      .build();
  ```

  Completare con snapshotter, deadline/stop timeout e le opzioni cgroup provate nel
  Task 1. Il bean client ha `destroyMethod="close"`; non creare connessioni per replica.
- [ ] Defaults: socket `${XDG_RUNTIME_DIR}/containerd/containerd.sock`, namespace
  `nanofaas`, runtime binary `crun`, network `nanofaas`, plugin `/opt/cni/bin`, config
  `${HOME}/.config/cni/net.d`, cache/stato sotto `${HOME}/.local/share/nanofaas`.
  Risolvere questi valori prima di entrare nel namespace e passarli esplicitamente:
  non affidarsi a `user.home=/root`. Config assente/invalida produce diagnostica chiara.
- [ ] Applicare env riservato già nel lifecycle comune. Tradurre CPU request in shares,
  CPU limit in quota/periodo con conversione esatta da BigDecimal, MiB in byte con
  overflow controllato, memory request in reservation e cpuset nelle API del Task 1.
  Preservare ENTRYPOINT/CMD dell'immagine se `command` non impostato; testare immagini
  reali dei runtime Java/Python/Go/JavaScript e watchdog.
- [ ] Generare nomi validi con hash deterministico e label `io.nanofaas.backend=containerd`.
  Testare limite 76 caratteri, collisioni di normalizzazione e indice replica; conservare
  nel metadata il prefisso effettivo, senza imporre la normalizzazione Docker.
- [ ] `isAvailable` controlla il client con deadline; i prerequisiti rootless sono
  verificati dal launcher e da test d'integrazione, non dedotti dalla sola presenza socket.
  `supports` rifiuta `imagePullSecrets` come Docker. ImageValidator usa pull della libreria.
- [ ] Eseguire test nuovi e condivisi; graph analysis e commit
  `Add rootless containerd deployment adapter`.

### Task 5: Recovery e cleanup con ownership persistente

**Files:** `C/ContainerdRuntimeAdapter.java`, `ContainerdDeploymentProvider.java`;
test `ContainerdRecoveryTest.java`, `ContainerdDeprovisionRecoveryTest.java`.
Toccare `S/LocalManagedDeploymentProvider.java` solo se un nuovo test dimostra un
problema comune; ripetere sempre la regressione Docker in quel caso.

**Interfaces:** `reconcile(FunctionSpec,int,Map<String,String>): ProvisionResult`,
`deprovision(String): void` con `PartialDeprovisionException`; metadata e attachment
riaperti dal runtime, non una mappa esclusivamente in memoria.

- [ ] Scrivere test di recovery con adapter nuovo sullo stesso fake daemon/stato:
  replica sana adottata, missing creata, stopped ricreata, extra rimossa; metadata
  estranei o indici duplicati rifiutati prima di operazioni distruttive.
- [ ] Caso di accettazione idempotenza, usando fixture con un errore DEL una tantum:

  ```java
  assertThatThrownBy(() -> provider.deprovision("echo"))
      .isInstanceOf(PartialDeprovisionException.class);
  // Nuovo provider/client, stessa stateDirectory e stesso daemon.
  restartedProvider.deprovision("echo");
  assertThat(restartedAdapter.listManagedContainers("echo")).isEmpty();
  restartedProvider.deprovision("echo"); // nessun errore e nessuna risorsa ricreata
  ```

- [ ] Eseguire test rossi; implementare discovery tramite namespace+labels e lettura
  attachment persistito. Confrontare backend/function/index prima dell'adozione;
  non richiamare start/CNI ADD su replica sana.
- [ ] Rimozione via API libreria con `force(true).removeSnapshot(true)`; tentare ogni
  replica e riportare residui, inclusi quelli del cleanup journal senza container.
  Discovery unisce `containers().list()` e `containers().pendingRemovals()`, deduplica
  per ID e filtra entrambi con le medesime labels. I record in pending removal non
  sono adottabili come repliche sane e vanno ripuliti prima di ricreare lo stesso ID.
  Timeout daemon/CNI non deve diventare lista vuota di risorse.
- [ ] Coprire close senza rimozione, shutdown/restart, start fallito dopo allocazione IP,
  cleanup di snapshot senza container, rimozione concorrente con scaling e pending
  removal. Verificare che il proxy non resti utilizzabile dopo cancellazione parziale.
- [ ] Gate unitario + integration reale su CNI/IPAM: nessun lease/snapshot/task residuo,
  graph analysis e commit `Recover containerd deployments and partial cleanup`.

### Task 6: Wiring, contratto unico e isolamento degli artefatti

**Files:** `C/ContainerdDeploymentProviderConfiguration.java`, file
`AutoConfiguration.imports`, build modulo, `settings.gradle`/repository Gradle se necessari;
`platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/deployment/DeploymentProviderResolverTest.java`;
nuovi `ContainerdDeploymentProviderConfigurationTest.java`, `architecture/ArchitectureTest.java`.

**Interfaces:** un solo bean `ManagedDeploymentProvider` e un solo `ImageValidator`
del backend selezionato; `ProvisionResult` standard. Nessuna condizione containerd
nel codice di scheduling, dispatch o autoscaling.

- [ ] Con `ApplicationContextRunner`, verificare contesto con solo il nuovo modulo:

  ```java
  contextRunner.run(context -> {
      assertThat(context).hasSingleBean(ManagedDeploymentProvider.class);
      assertThat(context.getBean(ManagedDeploymentProvider.class).backendId())
          .isEqualTo("containerd");
      assertThat(context).hasSingleBean(ImageValidator.class);
  });
  ```

  Iniettare un client fake, senza dipendere da socket/CNI nelle prove di wiring.
- [ ] Testare resolver implicito, hint esplicito, backend persistito, indisponibile,
  nessun provider e spec non supportata. Conservare il comportamento esistente del
  fallback EXTERNAL quando richiesto da `endpointUrl`; non introdurre un fallback
  implicito containerd→Docker/K8s.
- [ ] Completare autoconfiguration e dipendenze `containerd-java-cni` dal Task 1.
  Per lo sviluppo abilitare Maven local solo con `-PcontainerdMavenLocal=true` e
  filtro sui gruppi `io.nanofaas`/`io.libcni`. CI risolve artefatti pubblicati o
  preparati da checkout SHA fissati. Nessun `files('/home/michele/...jar')`.
- [ ] Verificare allineamento delle versioni Netty/gRPC/protobuf con il BOM Spring;
  usare `dependencyInsight` sul runtimeClasspath, poi un vero round-trip sul socket.
  Non forzare globalmente versioni per correggere il solo provider.
- [ ] Costruire separatamente:

  ```bash
  ./gradlew :control-plane:bootJar -PcontrolPlaneModules=containerd-deployment-provider -PcontainerdMavenLocal=true
  ./gradlew :control-plane:bootJar -PcontrolPlaneModules=container-deployment-provider
  ./gradlew :control-plane:bootJar -PcontrolPlaneModules=k8s-deployment-provider
  ./gradlew :control-plane:bootJar -PcontrolPlaneModules=all
  ```

  Archiviare ciascun JAR prima della build successiva. Ispezionare `BOOT-INF/lib`:
  containerd non contiene docker-java/Fabric8; Docker e K8s non contengono containerd/CNI.
  La build default/none non deve richiedere credenziali GitHub Packages di containerd.
- [ ] Eseguire suite control plane, nuovo provider, provider esistenti e plugin Gradle.
  Gate: stessi contratti del core, graph analysis e commit
  `Wire exclusive containerd deployment module`.

### Task 7: Riprodurre gli scenari Docker tramite NanoLab

**Files NanoFaaS:** create `deploy/containerd-rootless/nanofaas.service`,
`deploy/containerd-rootless/start-control-plane.sh`, `deploy/containerd-rootless/10-nanofaas.conflist`;
create `docs/deployment-containerd.md`.
**Files NanoLab**, repository `/home/michele/Documenti/nanolab`:
- Modify `packages/nanolab/src/nanolab/core/models.py` e il piano
  `packages/nanolab/src/nanolab/plans/validate.py`.
- Modify `packages/nanolab/src/nanolab/tasks/validate.py`, `validate_recovery.py` e i
  corrispondenti test `packages/nanolab/tests/plans/test_validate.py`,
  `packages/nanolab/tests/tasks/migrated/test_validate_workflow.py`, `test_validate_recovery.py`.
- Create backend task `packages/nanolab/src/nanolab/tasks/containerd_rootless.py` e
  test `packages/nanolab/tests/tasks/test_containerd_rootless.py`.
- Create scenari `packages/nanolab/scenarios-v2/*-containerd.yaml` secondo la matrice.
  Collegare anche i piani loadtest/soak/CLI effettivamente usati dagli scenari dopo
  graph query locale: cambiare il solo YAML `backend` non basta.

**Interfaces:** nuovo backend NanoLab `containerd`, senza riutilizzare il driver
Docker per le operazioni runtime. Restano uguali le asserzioni HTTP e i workload.
Build immagini e runtime deployment sono separati: per costruire le immagini è
ammesso Docker/BuildKit; il loro avvio e lifecycle devono usare il nuovo provider.

- [ ] Scrivere test del piano NanoLab: backend containerd seleziona modulo containerd,
  prepara un utente rootless e definisce cleanup delle risorse anche in caso di failure.
- [ ] Adattare il provisioning già dimostrato dal workflow
  `containerd-java/e2e/containerd_java_e2e.py`: RootlessKit/containerd/crun/CNI,
  subuid/subgid, systemd utente, cgroup v2 delegato e architettura ricavata dalla VM.
  Bloccare esplicitamente la configurazione se i controller richiesti non sono delegati.
- [ ] Il launcher entra nei namespace corretti prima di Java/native, con directory
  assolute e HOME reale. Pubblicare API/management con il port driver RootlessKit,
  cleanup idempotente delle sole pubblicazioni del test e callback al bridge CNI.
  L'API deve essere raggiungibile dall'host e la callback da ogni replica.
- [ ] Preparare immagini raggiungibili da containerd: registry del test con tag/digest
  univoci oppure import OCI gestito dall'harness. Le immagini nel daemon Docker non
  sono automaticamente disponibili nel content store containerd. Provare registry/DNS
  dalla rete rootless e non assumere accesso al loopback host.
- [ ] Parametrizzare le asserzioni comuni e creare questa matrice, senza copiarne la logica:

  | Scenario Docker esistente | Scenario containerd richiesto | Evidenza |
  | --- | --- | --- |
  | `deployment-lifecycle-container.yaml` | `deployment-lifecycle-containerd.yaml` | register, ready, invoke, delete |
  | `persistent-recovery-container.yaml` | `persistent-recovery-containerd.yaml` | restart CP, adozione e cleanup retry |
  | `validate-async-container.yaml` | `validate-async-containerd.yaml` | enqueue, callback, stato finale |
  | `autoscaling-cycle-container.yaml` | `autoscaling-cycle-containerd.yaml` | scale out/in, zero e wake-up |
  | `concurrency-cycle-container.yaml` e varianti Python/Go/JavaScript/budgeted | corrispondenti `*-containerd.yaml` | stessi workload/SLO/assert |
  | `concurrency-co-tenancy-container.yaml` | `concurrency-co-tenancy-containerd.yaml` | cpuset condiviso e contesa reale |
  | `handler-envelope-container.yaml` | `handler-envelope-containerd.yaml` | status/header/body |
  | `cli-contract-container.yaml` | `cli-contract-containerd.yaml` | stessa CLI pubblica |
  | `memory-soak-smoke-container.yaml`, `memory-soak-sync-container.yaml` | corrispondenti `*-containerd.yaml` | stabilità e risorse residue |

  Inventariare anche gli scenari diagnostici `memory-heap-analysis`, `memory-soak-prerequisites`
  e i candidati `memory-soak-*-container.yaml`: annotare per ciascuno il comando
  containerd equivalente e se è diagnostico o gate. Non confrontare numeri di memoria
  fra ambienti diversi senza registrare runtime, configurazione e carico.
- [ ] Eseguire prima lifecycle, poi recovery/async/scaling, quindi intera matrice.
  Comando obiettivo, disponibile dopo questa implementazione in NanoLab:

  ```bash
  NANOFAAS_ROOT=/home/michele/Documenti/nanofaas/.claude/worktrees/containerd-rootless \
    ./nanolab.sh run packages/nanolab/scenarios-v2/deployment-lifecycle-containerd.yaml \
    --environment packages/nanolab/environments/multipass.yaml
  ```

- [ ] Aggiungere failure injection: plugin CNI timeout/DEL failure, daemon disconnesso,
  funzione unhealthy, riavvio durante cleanup. Prima/dopo enumerare container, task,
  snapshot, cache CNI/IPAM, proxy e forwarding; verificare assenza residui del test.
  Immagini condivise possono rimanere nella cache, non vanno eliminate indiscriminatamente.
- [ ] Gate: report di ogni scenario con UID mapping/crun effettivo, versioni e zero
  skip nei job runtime. Commit separati nei due repo dopo le rispettive analisi graph:
  `Add containerd rootless deployment environment` e `Validate containerd deployment parity`.

### Task 8: Native image, documentazione e verifica finale

**Files:** nuovo README modulo, `docs/deployment-containerd.md`, `docs/control-plane.md`,
`README.md`, documentazione dei selettori e build immagini/scripts effettivamente usati;
eventuale `C/ContainerdRuntimeHints.java`, `platform/control-plane/build.gradle`.
Aggiornare OpenAPI solo dove descrive backend/supporto; nessun nuovo endpoint è richiesto.

**Interfaces:** artefatti JVM e native con lo stesso provider/contratto e istruzioni
riproducibili da checkout pulito; manifest delle revisioni di librerie e NanoLab.

- [ ] Compilare native con la selezione esplicita containerd e metadata delle librerie:

  ```bash
  ./gradlew :control-plane:nativeCompile \
    -PcontrolPlaneModules=containerd-deployment-provider \
    -PcontainerdMavenLocal=true
  ```

  Per gli scenari async/scaling aggiungere i moduli previsti dallo scenario al selettore.
- [ ] Eseguire il binario realmente nello stesso ambiente rootless: gRPC UDS, pull,
  CNI ADD/DNS, invoke, callback, restart/reconcile e DEL. Provare arm64 locale e amd64
  CI se disponibile; indicare esplicitamente architetture non verificate. I test native
  devono includere il percorso CNI, non soltanto il core containerd-java.
- [ ] Se fallisce reachability/reflection/FFM, aggiungere solo hints dimostrati dalla
  failure e rieseguire il caso. Verificare che gli script di immagini/build context
  includano la nuova libreria condivisa e il modulo e conservino META-INF/services.
- [ ] Documentare prerequisiti, socket/state/cache, namespace, crun, CNI, forwarding,
  callback, cgroup, selezione esclusiva, recovery e limiti di registry supportati.
  Aggiornare il README Docker obsoleto che descrive solo CLI, senza cambiare comportamento.
- [ ] Eseguire le suite modificate una volta dopo l'ultima modifica, i test di
  integrazione richiesti e la matrice NanoLab; archiviare XML/log e comandi esatti.
  Controllare `git diff --check`, artifact contents e dipendenze di ciascun provider.
- [ ] Rigenerare graph worktree e `detect-changes --scope all` prima del commit;
  eseguire anche `detect-changes --scope compare --base-ref main` per la review del
  branch. Un output partial/truncated richiede nuova analisi e non vale come pass.
- [ ] Commit `Document and verify rootless containerd deployments`. Preparare un
  riepilogo con test pass/fail/non eseguiti e dipendenze cross-repo. Non dichiarare
  completata la parità se mancano prove runtime, cleanup, recovery o limiti rootless.

## Criteri di completamento

- [ ] Tre moduli selezionabili singolarmente, coppie rifiutate, `all`/default invariati.
- [ ] Core e autoscaler consumano solo la SPI; artefatti isolati per dipendenze.
- [ ] Docker e Kubernetes superano le regressioni dopo l'estrazione.
- [ ] containerd-java/libcni-java effettivamente usate, crun rootless dimostrato.
- [ ] Rete bidirezionale, readiness, callback e DNS provati, anche dopo restart.
- [ ] Limiti CPU/memoria/cpuset applicati; cleanup completo e retry persistente.
- [ ] Matrice NanoLab equivalente eseguita; JVM/native validati su architetture dichiarate.
- [ ] Nessun percorso personale obbligatorio e revisioni dipendenze riproducibili in CI.

## Handoff

Piano e spec descrivono lavoro ancora da implementare. La modalità scelta è
`superpowers:subagent-driven-development`, con dispatch e selezione esplicita di
modello/effort gestiti dal coordinatore secondo la tabella e le regole sopra.
Questa modifica documentale non avvia l'esecuzione. Alla successiva richiesta di
implementazione, usare il piano aggiornato e preservare le decisioni dell'utente;
non chiedere nuovamente di scegliere modalità, modello o effort per ciascun task.
