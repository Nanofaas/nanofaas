# Dispatch per pod su Kubernetes — piano di implementazione

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** il control plane sceglie il pod di ogni invocazione Kubernetes, leggendo i pod pronti dagli EndpointSlice di un Service headless, con lo stesso pool dei container.

**Architecture:** il Service di funzione diventa `clusterIP: None`. Un solo informer Fabric8 sugli `EndpointSlice` del namespace delle funzioni notifica quale Service è cambiato; il provider ricalcola dallo store i backend pronti di quella funzione e li passa al suo `ManagedReplicaSet`. `acquireInvocationTarget` chiede il pod al pool; `getReplicaStatus` riporta pronti i backend del pool.

**Tech Stack:** Java 25, Spring Boot, Fabric8 kubernetes-client 7.9.0 (`discovery().v1().endpointSlices()`), `container-deployment-runtime` (`ManagedReplicaPoolFactory`, `ManagedReplicaSet`), mock server Fabric8 (`@EnableKubernetesMockClient(crud = true)`), Helm 4, pytest per il chart.

**Spec:** `docs/superpowers/specs/2026-11-14-k8s-pod-level-dispatch-design.md`

## Global Constraints

- Branch: `review/k8s-pod-level-dispatch`. Nessuna compatibilità all'indietro: niente flag o percorso `ClusterIP` alternativo.
- Service di funzione: nome `fn-<funzione>` (`KubernetesDeploymentBuilder.serviceName`), porta e targetPort 8080, `clusterIP: None`, `publishNotReadyAddresses` non impostato.
- URL di un backend: `http://<indirizzo>:8080`; il pool aggiunge `/invoke`. Indirizzi IPv6 tra parentesi quadre.
- Limiti del pool: `perPod = spec.concurrency()` (null → 4, minimo 1); `maxInFlight = max(1, backend × perPod)`; `timeout = spec.timeoutMs()` (null → 30000 ms). Stessi default di `LocalManagedDeploymentProvider`.
- `acquireInvocationTarget` non prende lock di ciclo di vita.
- Il modulo k8s dipende solo da contratti e librerie condivise: `container-deployment-runtime` è permessa (package `it.unimib.datai.nanofaas.containerdeployment`, fuori da `..modules..`).
- Java: 4 spazi, package root `it.unimib.datai.nanofaas`. Test JUnit 5 `*Test.java`.
- Comandi Gradle locali senza `-PcontainerdMavenLocal`: le librerie containerd arrivano da Maven Central.
- Prima di modificare un simbolo: impact GitNexus (`npx -y gitnexus@latest impact "<simbolo>" --direction upstream --file <file> --repo .`). Prima di ogni commit: `npx -y gitnexus@latest detect-changes --scope staged --repo .`.
- Commit con le righe `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` e `Claude-Session: https://claude.ai/code/session_01SFv25TWm5VqhJaynDDKjjP`.

## Review Focus

1. **Slice già presenti quando la funzione viene registrata** (riavvio del control plane, informer sincronizzato prima di `reconcile`): la funzione deve avere subito i suoi backend, non aspettare il prossimo evento. Test in Task 3: `reconcileAdoptsBackendsAlreadyInTheStore`.
2. **Ultimo slice cancellato** (Service ricreato, scale a zero): il pool deve svuotarsi e `acquire` restituire `UNAVAILABLE`. Test in Task 2: `deletingTheLastSliceLeavesNoBackends`.
3. **Nomi con prefisso comune** (`fn-a` e `fn-a-b`): uno slice di `fn-a-b` non deve toccare `fn-a`; si confronta il valore esatto dell'etichetta `kubernetes.io/service-name`. Test in Task 2: `slicesOfAnotherServiceWithACommonPrefixAreIgnored`.
4. **`conditions.ready` assente**: per l'API Kubernetes "sconosciuto" va letto come pronto; `terminating == true` esclude sempre. Test in Task 2: `readyBackendsFollowEndpointConditions`.
5. **Eventi persi o fuori ordine** (riconnessione dell'informer, evento che arriva mentre `provision` registra la funzione): ogni aggiornamento ricalcola dallo store dell'informer, mai dal payload dell'evento, e l'informer ha un resync periodico (30 s). Test in Task 3: `aStaleRefreshCannotOverwriteANewerOne`.

---

### Task 1: Service headless, ricreato se non lo è

**Files:**
- Modify: `platform/modules/k8s-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/k8s/dispatch/KubernetesDeploymentBuilder.java` (`buildService`, righe ~110-130)
- Modify: `platform/modules/k8s-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/k8s/dispatch/KubernetesResourceManager.java` (`createOrPatchService`, `reconcile`)
- Test: `platform/modules/k8s-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/k8s/dispatch/KubernetesDeploymentBuilderTest.java`
- Test: `platform/modules/k8s-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/k8s/dispatch/MockK8sDeploymentReplicaSetFlowTest.java`

**Interfaces:**
- Produces: `static boolean KubernetesDeploymentBuilder.isHeadless(io.fabric8.kubernetes.api.model.Service service)`: vero se `spec.clusterIP` è `"None"`.

- [ ] **Step 1: Test che falliscono**
  - `KubernetesDeploymentBuilderTest.serviceIsHeadless`: `buildService(spec).getSpec().getClusterIP()` è `"None"`; selettore `function=<nome>`, porta 8080 e annotazioni Prometheus invariati.
  - `MockK8sDeploymentReplicaSetFlowTest.reconcileRecreatesAServiceThatIsNotHeadless`: crea nel mock server un Service `fn-echo` con `clusterIP: 10.43.0.9`, poi `reconcile(spec, 1, objects)`; il Service letto dopo ha `clusterIP` `"None"`.
  - `MockK8sDeploymentReplicaSetFlowTest.provisionReplacesAServiceThatIsNotHeadless`: come sopra ma con `provision(spec)` (oggi la patch fallirebbe: `clusterIP` è immutabile).
  - `MockK8sDeploymentReplicaSetFlowTest.reconcileLeavesAHeadlessServiceAlone`: Service già headless con un'etichetta extra; dopo `reconcile` l'etichetta c'è ancora (non è stato ricreato).
- [ ] **Step 2:** `./gradlew :control-plane-modules:k8s-deployment-provider:test --tests '*KubernetesDeploymentBuilderTest*' --tests '*MockK8sDeploymentReplicaSetFlowTest*'` → i quattro test FALLISCONO.
- [ ] **Step 3: Implementare.** `buildService` aggiunge `.withClusterIP("None")`. In `KubernetesResourceManager` un Service esistente non headless viene cancellato e ricreato sia in `createOrPatchService` (provision) sia in `reconcile`; uno headless viene patchato (provision) o lasciato com'è (reconcile), come oggi. La cancellazione deve essere completata prima della creazione: aspettare con `waitUntilCondition(Objects::isNull, …)` o equivalente.
- [ ] **Step 4:** stesso comando → PASS; poi tutto il modulo: `./gradlew :control-plane-modules:k8s-deployment-provider:test` → PASS.
- [ ] **Step 5: Commit** "Make function Services headless and recreate the ones that are not".

### Task 2: Backend pronti dagli EndpointSlice

**Files:**
- Create: `platform/modules/k8s-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/k8s/deployment/EndpointSliceWatcher.java`
- Test: `platform/modules/k8s-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/k8s/deployment/EndpointSliceWatcherTest.java`

**Interfaces:**
- Produces:
  - `final class EndpointSliceWatcher implements AutoCloseable`
  - `EndpointSliceWatcher(KubernetesClient client, String namespace, java.util.function.Consumer<String> changedService)`: `changedService` riceve il nome del Service (valore dell'etichetta `kubernetes.io/service-name`) a ogni aggiunta, modifica, cancellazione o resync di un suo slice.
  - `void start()`: avvia l'informer (`client.discovery().v1().endpointSlices().inNamespace(namespace).withLabel("kubernetes.io/service-name").inform(handler, 30_000)`); ritorna dopo la prima sincronizzazione; idempotente.
  - `List<String> readyBackends(String serviceName)`: dallo store, unione di tutti gli slice del Service; un `http://<indirizzo>:8080` per ogni indirizzo di un endpoint con `ready != FALSE` e `terminating != TRUE`; senza duplicati, ordinata; vuota se nessuno slice.
  - `void close()`: chiude l'informer.

- [ ] **Step 1: Test che falliscono** (`@EnableKubernetesMockClient(crud = true)`, namespace `default`, slice creati con `EndpointSliceBuilder`):
  - `readyBackendsFollowEndpointConditions`: uno slice di `fn-echo` con endpoint `10.0.0.1` (ready true), `10.0.0.2` (ready false), `10.0.0.3` (ready assente), `10.0.0.4` (ready true, terminating true) → `readyBackends("fn-echo")` = `["http://10.0.0.1:8080", "http://10.0.0.3:8080"]`.
  - `backendsOfAServiceAreMergedAcrossSlices`: due slice di `fn-echo` (`10.0.0.1`; `10.0.0.2`, `10.0.0.1`) → `["http://10.0.0.1:8080", "http://10.0.0.2:8080"]`.
  - `ipv6AddressesAreBracketed`: slice `addressType: IPv6` con `fd00::5` → `["http://[fd00::5]:8080"]`.
  - `changesAreReportedByServiceName`: dopo `start()`, creare, modificare e cancellare uno slice di `fn-echo`; il consumer riceve `"fn-echo"` per ciascun cambiamento (attesa con timeout di 5 s).
  - `deletingTheLastSliceLeavesNoBackends`: dopo la cancellazione dell'unico slice, `readyBackends("fn-echo")` è vuota.
  - `slicesOfAnotherServiceWithACommonPrefixAreIgnored`: slice solo per `fn-a-b` → `readyBackends("fn-a")` vuota.
- [ ] **Step 2:** `./gradlew :control-plane-modules:k8s-deployment-provider:test --tests '*EndpointSliceWatcherTest*'` → FALLISCE (classe assente).
- [ ] **Step 3: Implementare `EndpointSliceWatcher`** come da Interfaces. Lo store si interroga per servizio con un indice dell'informer sull'etichetta oppure filtrando `getStore().list()`; scelta libera.
- [ ] **Step 4:** stesso comando → PASS.
- [ ] **Step 5: Commit** "Read a Service's ready pods from its EndpointSlices".

### Task 3: Il provider sceglie il pod

**Files:**
- Modify: `platform/modules/k8s-deployment-provider/build.gradle` (aggiungere `implementation project(':container-deployment-runtime')`)
- Modify: `platform/modules/k8s-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/k8s/deployment/KubernetesManagedDeploymentProvider.java`
- Modify: `platform/modules/k8s-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/k8s/dispatch/KubernetesResourceManager.java` (`provision` restituisce `void`; `provisionResult` con `endpointUrl` `null`; togliere `serviceUrl`)
- Test: `platform/modules/k8s-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/k8s/deployment/KubernetesManagedDeploymentProviderTest.java` (riscritto sul mock server)
- Test: aggiornare gli altri test del modulo che si aspettano `http://fn-…svc.cluster.local:8080/invoke` (`MockK8sDeploymentReplicaSetFlowTest`, `KubernetesResourceManagerTest`): ora `endpointUrl` è `null`.

**Interfaces:**
- Consumes: `EndpointSliceWatcher` (Task 2); `ManagedReplicaPoolFactory.create(String)`, `ManagedReplicaSet.updateBackends(List<String>)`, `updateLimits(int, int, Duration)`, `acquire(String, String)`, `close()`.
- Produces:
  - Costruttore `KubernetesManagedDeploymentProvider(KubernetesResourceManager resourceManager, ObjectProvider<KubernetesClient> clients)`; il provider crea e possiede un `ManagedReplicaPoolFactory` e, alla prima `provision`/`reconcile`, un `EndpointSliceWatcher` sul namespace `resourceManager.getResolvedNamespace()`.
  - `ManagedTargetResolution acquireInvocationTarget(String functionName, Map<String,String> deploymentObjects, String executionId, String dispatchAttempt)`: `reserved(target)` con `target.endpointUrl()` = `http://<pod>:8080/invoke`, oppure `UNAVAILABLE` (funzione sconosciuta, nessun backend, pool pieno).
  - `ReplicaStatus getReplicaStatus(String functionName)`: `desired` dal Deployment; `ready = min(desired, backend nel pool)`; 0 per funzione sconosciuta.
  - `void updateSpec(FunctionSpec spec)`: riapplica i limiti.
  - `void close()` (`AutoCloseable`): chiude watcher, pool e factory.
  - `provision` e `reconcile` restituiscono `ProvisionResult` con `endpointUrl` `null` e gli stessi `deploymentObjects` di oggi.

- [ ] **Step 1: Test che falliscono** (mock server CRUD; i pod sono simulati creando EndpointSlice con l'etichetta `kubernetes.io/service-name=fn-echo`; spec `echo` con `concurrency` 4 salvo dove indicato):
  - `dispatchGoesToTheReadyPodsInTurn`: provision, slice con `10.0.0.1` e `10.0.0.2` pronti; quattro `acquire` (chiudendo ogni target) restituiscono a turno `http://10.0.0.1:8080/invoke` e `http://10.0.0.2:8080/invoke`, due volte ciascuno.
  - `noReadyPodMeansUnavailable`: provision senza slice → `UNAVAILABLE`; funzione mai registrata → `UNAVAILABLE`.
  - `aPodThatStartsTerminatingReceivesNoNewWork`: due pod pronti, poi lo slice segna `10.0.0.2` `terminating`; le `acquire` successive vanno solo a `10.0.0.1`.
  - `aFullPoolRefuses`: `concurrency` 1, un pod; primo `acquire` riservato e non chiuso, secondo `UNAVAILABLE`.
  - `readyReplicasCountPodsTheDispatchCanReach`: Deployment con `status.readyReplicas` 2 e `spec.replicas` 2 ma nessuno slice → `getReplicaStatus` = `(2, 0)`; con lo slice di due pod pronti → `(2, 2)`; con tre pod pronti (surge) → `(2, 2)`.
  - `reconcileAdoptsBackendsAlreadyInTheStore`: slice di `fn-echo` creato prima della `reconcile`; subito dopo `reconcile`, senza altri eventi, `acquire` restituisce il pod.
  - `aStaleRefreshCannotOverwriteANewerOne`: l'aggiornamento di una funzione legge lo store al momento dell'applicazione sotto il lock della funzione; il test applica due cambiamenti di slice in sequenza rapida e verifica che il pool finisca con i backend dell'ultimo.
  - `deprovisionStopsDispatch`: dopo `deprovision("echo")`, `acquire` → `UNAVAILABLE`.
  - `provisionNoLongerPublishesAServiceUrl`: `provision(spec).endpointUrl()` è `null`; `deploymentObjects` contiene ancora `DEPLOYMENT`, `SERVICE`, `NAMESPACE`.
- [ ] **Step 2:** `./gradlew :control-plane-modules:k8s-deployment-provider:test --tests '*KubernetesManagedDeploymentProviderTest*'` → FALLISCONO.
- [ ] **Step 3: Implementare.** Stato per funzione (spec corrente, pool, numero di backend), in una mappa concorrente con chiave il nome del Service. Il consumer del watcher e `provision`/`reconcile` chiamano lo stesso metodo di aggiornamento, che sotto il lock della funzione legge `watcher.readyBackends(service)` e chiama `updateBackends` e `updateLimits`. `deprovision` toglie lo stato e chiude il pool prima di cancellare le risorse.
- [ ] **Step 4:** test del modulo → PASS: `./gradlew :control-plane-modules:k8s-deployment-provider:test`; architettura: `./gradlew :control-plane:test --tests '*CoreArchitecture*' --tests '*ModuleBoundaryArchitectureTest' -PcontrolPlaneModules=all` → PASS.
- [ ] **Step 5: Commit** "Dispatch Kubernetes invocations to pods the control plane picks".

### Task 4: Permessi, build native, documentazione

**Files:**
- Modify: `deploy/helm/nanofaas/templates/rbac.yaml`, `deploy/k8s/rbac.yaml` (regola `apiGroups: ["discovery.k8s.io"]`, `resources: ["endpointslices"]`, `verbs: ["get", "list", "watch"]`)
- Modify: `platform/modules/k8s-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/k8s/config/Fabric8RuntimeHints.java` (aggiungere `"io.fabric8.kubernetes.api.model.discovery.v1"` a `FABRIC8_MODEL_PACKAGES`)
- Modify: `docs/k8s.md` (righe 15 e 24), `docs/function-pod-architecture.md` (riga 92), `deploy/helm/nanofaas/README.md` (nota di aggiornamento: Service headless, ricreazione al primo riavvio, nuovo permesso)
- Test: `scripts/tests/test_helm_chart.py`, `platform/modules/k8s-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/k8s/config/Fabric8RuntimeHintsTest.java`

- [ ] **Step 1: Test che falliscono**
  - `test_control_plane_may_watch_endpointslices`: `render("templates/rbac.yaml")` contiene una regola con `discovery.k8s.io`, `endpointslices` e i verbi `get`, `list`, `watch`; la stessa regola c'è in `deploy/k8s/rbac.yaml`.
  - `Fabric8RuntimeHintsTest.registerHints_registersEndpointSliceTypes`: reflection registrata per `io.fabric8.kubernetes.api.model.discovery.v1.EndpointSlice` e `EndpointSliceList`.
- [ ] **Step 2:** `uv run --python 3.12 --with pytest --with pyyaml --with jsonschema python -m pytest scripts/tests/test_helm_chart.py -ra` e `./gradlew :control-plane-modules:k8s-deployment-provider:test --tests '*Fabric8RuntimeHintsTest*'` → FALLISCONO.
- [ ] **Step 3: Implementare** regole RBAC, pacchetto nei suggerimenti native, testi di documentazione.
- [ ] **Step 4:** stessi comandi → PASS.
- [ ] **Step 5: Commit** "Grant EndpointSlice reads and document headless function Services".

### Task 5: Verifica completa

Nessun codice nuovo, salvo correzioni a problemi trovati qui (ognuna con il suo test e commit).

- [ ] **Step 1: Suite e controlli.** `./gradlew test --no-parallel --continue` e `./gradlew releaseChecks -PcontrolPlaneModules=all --continue` → BUILD SUCCESSFUL (su macOS sono attesi solo i casi `warm-echo native` di `WarmEchoAotTest`, che richiedono Linux).
- [ ] **Step 2: VM.** Creare una VM Multipass nuova (mai il server .35) con un file d'ambiente NanoLab `provider: multipass` nella scratchpad.
- [ ] **Step 3: Misura di partenza su `main`** (prima, con `ClusterIP`): con `NANOFAAS_ROOT` su un worktree di `main`, `deployment-lifecycle-k8s` con `--keep`; carico costante sul control plane, scale-up da 1 a 4 pod con `PUT /v1/functions/<f>/replicas`; contare le richieste servite da ogni pod nei 60 s dopo che tutti sono pronti (dai log o dalle metriche di ciascun pod). Registrare i conteggi.
- [ ] **Step 4: Stessa misura sul branch.** Atteso: ogni pod entro ±20% di 1/4 del totale, nessun 503.
- [ ] **Step 5: Scenari NanoLab sul branch**, ciascuno su VM nuova: `deployment-lifecycle-k8s`, `autoscaling-cycle-k8s`, `autoscaling-cycle-k8s-hpa`, `persistent-recovery-k8s`, `retry-backoff-k8s`, `deployment-lifecycle-k8s-native` (informer nella build native). Tutti verdi.
- [ ] **Step 6:** eliminare le VM; riportare all'utente misure e risultati.
