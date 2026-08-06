# SonarQube #166 — Findings MINOR + INFO (188) — Design

**Data:** 2026-08-06
**Issue:** https://github.com/miciav/mcFaas/issues/166
**Fonte:** scansione fresca `./gradlew ... sonar` su main @ 650bcac2 (2026-08-06), server locale sonar-sonata (porta 9000, admin/admin), progetto `nanofaas-java`.

## Contesto

Dopo la chiusura della #165 (220 MAJOR → 0, verificato), restano **188 findings aperti: 186 MINOR + 2 INFO**, distribuiti su 19 regole. Nessun BLOCKER/CRITICAL/MAJOR. Questo tranche li azzera tutti.

Le occorrenze in questa spec sono la fonte esatta per i task: ogni fix deve riferirsi alla lista file:line della propria regola. I file estratti sono nel `jq`/API di SonarQube; la lista completa è incorporata qui sotto (sezione "Occorrenze per regola").

## Decisioni utente (2026-08-06)

1. **Scope: tutti i 188 findings.** Fix completo; i soli casi con motivazione legittima (S4507) si chiudono come won't-fix documentati su SonarQube, zero modifiche al codice.
2. **S4507 (printStackTrace nel CLI): won't-fix motivato.** La stack trace nell'handler errori picocli è diagnostica intenzionale di un tool da terminale. Il finding si chiude su SonarQube con commento motivato.
3. **S1133 (2 INFO, @Deprecated sintetici su FunctionQueueState): rimozione.** I metodi `canDispatch()`, `incrementInFlight()`, `decrementInFlight()` hanno callers attivi (per questo in #165 il `forRemoval` fu tolto); la deprecazione è un segnale sintetico. Si rimuovono annotazione `@Deprecated` e tag javadoc `@deprecated` → tornano API normali, S1133 si chiude, S6355 non si riattiva (niente più marker di deprecazione). Behavior-preserving, nessun caller da toccare.

## Triage per regola

| Regola | N | Severità | Tipo | Fix shape |
|---|---|---|---|---|
| S5838 | 56 | MINOR | CODE_SMELL | AssertJ: `isEqualTo(0)` → `isZero()`, `isNotEqualTo(0)` → `isNotZero()` (vale per int/long/double/float). Solo uguaglianza/non-uguaglianza con 0, nient'altro. |
| S7467 | 55 | MINOR | CODE_SMELL | Parametro eccezione inutilizzato nei `catch` → unnamed pattern `_` (Java 25, `_` finale da Java 22). Solo dove il param non è usato nel body; i catch multi-type `catch (A \| B e)` inclusi. |
| S1128 | 15 | MINOR | CODE_SMELL | Rimozione import inutilizzati (incluso l'import same-package in SyncQueueProperties:5). |
| S1130 | 12 | MINOR | CODE_SMELL | Rimozione `throws Exception`/`throws IOException` ridondanti dai metodi di test (il corpo non le lancia). |
| S5853 | 12 | MINOR | CODE_SMELL | Asserzioni AssertJ consecutive sullo stesso soggetto → unica catena. Semantica identica (AssertJ fallisce alla prima asserzione comunque). |
| S1612 | 10 | MINOR | CODE_SMELL | Lambda → method reference esatta suggerita dal messaggio (es. `ExecutionStatus::status`, `ZipEntry::getName`, `Objects::isNull`). |
| S1611 | 7 | MINOR | CODE_SMELL | Parentesi ridondanti attorno al parametro lambda singolo: `(t) ->` → `t ->`. |
| S135 | 5 | MINOR | CODE_SMELL | Loop con >1 `break`/`continue` → refactor a guard clause / early return. Siti: SyncQueueService:174, FnTestCommand:47, InternalScaler:112, Scheduler:98, TargetLoadMetrics:47. Ciascuno con test che copre i percorsi prima/doppio break. |
| S1481 | 2 | MINOR | CODE_SMELL | Rimozione locali inutilizzati: `scaled` (InternalScaler), `allowedThisSecond` (RateLimiterTest:115). |
| S3077 | 2 | MINOR | BUG | `volatile ExecutorService` → `AtomicReference<ExecutorService>` (Scheduler:25, SyncScheduler:33). Riscrittura con `get()`/`set()`; semantica di visibilità preservata. |
| S4030 | 2 | MINOR | CODE_SMELL | Collection costruita e mai usata nei test (RateLimiterTest:68, FunctionQueueStateTest:55) → rimozione (o consumo se la collection è il soggetto del test). |
| S4276 | 2 | MINOR | CODE_SMELL | `Function<String,String>` → `UnaryOperator<String>` (KubernetesClientConfig:35, ConfigStore:26). |
| S899 | 1 | MINOR | BUG | `activeFunctions.offer(functionName)` (Scheduler:44) → `add(...)` (LinkedBlockingQueue unbounded: mai piena; `add` lancia solo se piena, impossibile). Semantica identica. |
| S5411 | 1 | MINOR | CODE_SMELL | `properties.enabled()` boxed in espressione booleana (OffloadConfiguration:22) → `Boolean.TRUE.equals(properties.enabled())`. La compact ctor di OffloadProperties normalizza null→true, quindi post-costruzione `enabled()` non è mai null → semantica identica, default invariati. |
| S6068 | 1 | MINOR | CODE_SMELL | InternalScalerTest:229: rimozione `eq(...)` inutili, valori passati diretti. |
| S8714 | 1 | MINOR | CODE_SMELL | ContainerLocalDeploymentProviderTest:423: try/catch + `fail()` → `assertDoesNotThrow(...)`. |
| S8924 | 1 | MINOR | CODE_SMELL | AdaptivePerPodConcurrencyControllerTest:134: `mock` → static import (import statico). |
| S1133 | 2 | INFO | CODE_SMELL | Rimozione `@Deprecated(since = "0.16.0")` + tag `@deprecated` da canDispatch/incrementInFlight/decrementInFlight (FunctionQueueState). Vedi Decisione 3. |
| S4507 | 1 | MINOR | VULNERABILITY | **Won't-fix** su SonarQube con commento motivato (Decisione 2). Nessuna modifica al codice. |

Totale: **187 findings chiusi via modifiche** (185 nel codice + 2 INFO via rimozione deprecazioni), **1 won't-fix motivato** (S4507).

## Vincoli globali

- **Behavior-preserving**: nessun cambiamento di comportamento osservabile. Quando il fix suggerito cambierebbe semantica (es. default, eccezioni), usare la forma equivalente indicata nella tabella.
- **Nessun nuovo @SuppressWarnings** (i soli casi documentati in #165 restano).
- **New findings in scope**: se un fix ne introduce di nuovi (es. S2142, S1068, S5738 — le famiglie note del #165), vanno risolti nello stesso task; il re-scan finale deve riportare **0 aperti** (esclusi i won't-fix).
- **Gate per task**: `./gradlew test --no-parallel` completo, verde, prima di dichiarare DONE un task.
- **GitNexus impact** prima di modificare qualsiasi simbolo di produzione (main code: SyncQueueService, InternalScaler, TargetLoadMetrics, Scheduler, SyncScheduler, WaitEstimator, VertxRuntimeHints, KubernetesImageValidator, KubernetesClientConfig, ConfigStore, OffloadConfiguration, PoolDispatcher, InvocationController, ExecutionCompletionHandler, SchedulerLifecycleSupport, ScalingDecisionCalculator, AdminRuntimeConfigController, KubernetesMetricsTranslator, RoundRobinFunctionProxy, HttpEndpointProbe, ProcessCliCommandExecutor, ContainerLocalDeploymentProvider, DockerJavaContainerRuntimeAdapter, CallbackClient, CallbackDispatcher, InvokeController, InvokeHandler, NanofaasRuntime, FunctionQueueState, FigletHandler, RomanNumeralHandler, JsonTransformLite, JsonTransformHandler, ControlPlaneError, NanofaasCli, FnTestCommand).
- **Niente trailer Co-Authored-By** nei commit (repo a autore singolo).
- Commit piccoli e frequenti, messaggi `fix: <rule>: <descrizione>` (convenzione #165).
- Lingua: italiano per le comunicazioni; commit in inglese.

## Verifica

1. Token: `curl -u admin:admin http://127.0.0.1:9000/api/user_tokens/generate` (token precedente in /tmp/sonar-token-165, se ancora valido riusarlo).
2. Scansione: `./gradlew test --no-parallel sonar -Dsonar.host.url=http://127.0.0.1:9000 -Dsonar.token=$TOKEN -Dsonar.projectKey=nanofaas-java`; attendere il termine via `/api/ce/component`.
3. Query: `/api/issues/search?...&severities=MINOR,INFO` → deve tornare **0 aperti** (dopo la chiusura del won't-fix S4507).
4. Chiusura won't-fix via API: `POST /api/issues/do_transition` (transition `wontfix`) + `POST /api/issues/add_comment` con la motivazione.
5. Eventuali residui da fix wave (atteso: 1-2 iterazioni, famiglie note: nuovi finding esposti dai fix stessi).
6. Merge fast-forward su main, suite su main, push, chiusura issue #166 con commento di risoluzione, ri-analisi GitNexus.

## Fuori scope

- Findings Python (8) e Rust (4) emersi da `./scripts/sonar.sh` — tranche separati, non toccati qui.
- Il contenitore sonar-sonata e la sua password (admin/admin) restano come sono.

---

## Occorrenze per regola

### java:S1128 (15)
  platform/modules/container-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/DockerJavaContainerRuntimeAdapterTest.java:24 — Remove this unused import 'org.mockito.ArgumentMatchers.any'.
  platform/modules/container-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/HttpEndpointProbeTest.java:11 — Remove this unused import 'java.net.URI'.
  platform/modules/container-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/HttpEndpointProbeTest.java:13 — Remove this unused import 'java.net.http.HttpHeaders'.
  platform/modules/container-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/HttpEndpointProbeTest.java:17 — Remove this unused import 'java.util.List'.
  platform/modules/container-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/HttpEndpointProbeTest.java:20 — Remove this unused import 'javax.net.ssl.SSLSession'.
  sdks/java/src/test/java/it/unimib/datai/nanofaas/sdk/runtime/InvocationRuntimeContextResolverTest.java:6 — Remove this unused import 'org.junit.jupiter.api.Assertions.assertNull'.
  platform/modules/sync-queue/src/test/java/it/unimib/datai/nanofaas/controlplane/sync/WaitEstimatorTest.java:8 — Remove this unused import 'java.util.Deque'.
  platform/modules/autoscaler/src/test/java/it/unimib/datai/nanofaas/modules/autoscaler/InternalScalerTest.java:18 — Remove this unused import 'org.assertj.core.api.Assertions.assertThat'.
  platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/ControlPlaneApplicationModulesTest.java:5 — Remove this unused import 'java.util.Set'.
  platform/modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/controlplane/config/SyncQueueProperties.java:5 — Remove this unnecessary import: same package classes are always implicitly imported.
  platform/modules/autoscaler/src/main/java/it/unimib/datai/nanofaas/modules/autoscaler/InternalScaler.java:5 — Remove this unused import 'it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode'.
  sdks/java-lite/src/test/java/it/unimib/datai/nanofaas/sdk/lite/NanofaasRuntimeTest.java:3 — Remove this unused import 'it.unimib.datai.nanofaas.common.model.InvocationRequest'.
  platform/modules/autoscaler/src/main/java/it/unimib/datai/nanofaas/modules/autoscaler/TargetLoadMetrics.java:10 — Remove this unused import 'java.util.ArrayList'.
  platform/modules/autoscaler/src/main/java/it/unimib/datai/nanofaas/modules/autoscaler/InternalScaler.java:3 — Remove this unused import 'it.unimib.datai.nanofaas.common.model.ExecutionMode'.
  platform/modules/autoscaler/src/main/java/it/unimib/datai/nanofaas/modules/autoscaler/InternalScaler.java:7 — Remove this unused import 'it.unimib.datai.nanofaas.common.model.ScalingMetric'.
### java:S1130 (12)
  sdks/java/src/test/java/it/unimib/datai/nanofaas/sdk/runtime/CallbackClientTest.java:145 — Remove the declaration of thrown exception 'java.lang.Exception', as it cannot be thrown from method's body.
  sdks/java/src/test/java/it/unimib/datai/nanofaas/sdk/runtime/InvokeControllerTest.java:215 — Remove the declaration of thrown exception 'java.lang.Exception', as it cannot be thrown from method's body.
  platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/InvocationServiceDispatchTest.java:485 — Remove the declaration of thrown exception 'java.lang.Exception', as it cannot be thrown from method's body.
  platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/InvocationServiceDispatchTest.java:343 — Remove the declaration of thrown exception 'java.lang.Exception', as it cannot be thrown from method's body.
  sdks/java-lite/src/test/java/it/unimib/datai/nanofaas/sdk/lite/handler/InvokeHandlerTest.java:37 — Remove the declaration of thrown exception 'java.io.IOException', as it cannot be thrown from method's body.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/RootCommandTest.java:92 — Remove the declaration of thrown exception 'java.lang.Exception', as it cannot be thrown from method's body.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/RootCommandTest.java:114 — Remove the declaration of thrown exception 'java.lang.Exception', as it cannot be thrown from method's body.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/exec/ExecGetCommandTest.java:62 — Remove the declaration of thrown exception 'java.lang.Exception', as it cannot be thrown from method's body.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/exec/ExecGetCommandTest.java:90 — Remove the declaration of thrown exception 'java.lang.Exception', as it cannot be thrown from method's body.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/exec/ExecGetCommandTest.java:126 — Remove the declaration of thrown exception 'java.lang.Exception', as it cannot be thrown from method's body.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/exec/ExecGetCommandTest.java:144 — Remove the declaration of thrown exception 'java.lang.Exception', as it cannot be thrown from method's body.
  sdks/java/src/test/java/it/unimib/datai/nanofaas/sdk/runtime/CallbackClientTest.java:null — Remove the declaration of thrown exception 'java.lang.Exception', as it cannot be thrown from method's body.
### java:S1133 (2)
  platform/modules/async-queue/src/main/java/it/unimib/datai/nanofaas/modules/asyncqueue/FunctionQueueState.java:91 — Do not forget to remove this deprecated code someday.
  platform/modules/async-queue/src/main/java/it/unimib/datai/nanofaas/modules/asyncqueue/FunctionQueueState.java:99 — Do not forget to remove this deprecated code someday.
### java:S135 (5)
  platform/modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/controlplane/sync/SyncQueueService.java:174 — Reduce the total number of break and continue statements in this loop to use at most one.
  clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/fn/FnTestCommand.java:47 — Reduce the total number of break and continue statements in this loop to use at most one.
  platform/modules/autoscaler/src/main/java/it/unimib/datai/nanofaas/modules/autoscaler/InternalScaler.java:112 — Reduce the total number of break and continue statements in this loop to use at most one.
  platform/modules/async-queue/src/main/java/it/unimib/datai/nanofaas/modules/asyncqueue/Scheduler.java:98 — Reduce the total number of break and continue statements in this loop to use at most one.
  platform/modules/autoscaler/src/main/java/it/unimib/datai/nanofaas/modules/autoscaler/TargetLoadMetrics.java:47 — Reduce the total number of break and continue statements in this loop to use at most one.
### java:S1481 (2)
  platform/modules/autoscaler/src/main/java/it/unimib/datai/nanofaas/modules/autoscaler/InternalScaler.java:null — Remove this unused "scaled" local variable.
  platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/RateLimiterTest.java:115 — Remove this unused "allowedThisSecond" local variable.
### java:S1611 (7)
  platform/modules/sync-queue/src/test/java/it/unimib/datai/nanofaas/controlplane/scheduler/SyncSchedulerTest.java:67 — Remove the parentheses around the "t" parameter
  platform/modules/sync-queue/src/test/java/it/unimib/datai/nanofaas/controlplane/scheduler/SyncSchedulerTest.java:97 — Remove the parentheses around the "t" parameter
  platform/modules/sync-queue/src/test/java/it/unimib/datai/nanofaas/controlplane/scheduler/SyncSchedulerTest.java:121 — Remove the parentheses around the "t" parameter
  platform/modules/sync-queue/src/test/java/it/unimib/datai/nanofaas/controlplane/scheduler/SyncSchedulerTest.java:150 — Remove the parentheses around the "t" parameter
  platform/modules/sync-queue/src/test/java/it/unimib/datai/nanofaas/controlplane/scheduler/SyncSchedulerTest.java:185 — Remove the parentheses around the "t" parameter
  platform/modules/sync-queue/src/test/java/it/unimib/datai/nanofaas/controlplane/scheduler/SyncSchedulerTest.java:222 — Remove the parentheses around the "t" parameter
  platform/modules/sync-queue/src/test/java/it/unimib/datai/nanofaas/controlplane/scheduler/SyncSchedulerTest.java:45 — Remove the parentheses around the "t" parameter
### java:S1612 (10)
  platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/InvocationServiceDispatchTest.java:442 — Replace this lambda with method reference 'rejectedMono::block'.
  platform/modules/container-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/DockerJavaContainerRuntimeAdapterIntegrationTest.java:49 — Replace this lambda with method reference 'inspectCmd::exec'.
  sdks/java-lite/src/test/java/it/unimib/datai/nanofaas/sdk/lite/NanofaasRuntimeTest.java:56 — Replace this lambda with method reference 'builder::build'.
  platform/modules/container-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/DockerJavaContainerRuntimeAdapterIntegrationTest.java:82 — Replace this lambda with method reference 'Objects::isNull'.
  platform/modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/controlplane/sync/WaitEstimator.java:29 — Replace this lambda with method reference 'this.perFunctionEvents::put'.
  platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/InvocationServiceDispatchTest.java:515 — Replace this lambda with method reference 'ExecutionStatus::status'.
  platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/InvocationServiceDispatchTest.java:552 — Replace this lambda with method reference 'ExecutionStatus::status'.
  platform/modules/k8s-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/k8s/config/VertxRuntimeHints.java:96 — Replace this lambda with method reference 'ZipEntry::getName'.
  platform/modules/image-validator/src/main/java/it/unimib/datai/nanofaas/modules/imagevalidator/KubernetesImageValidator.java:116 — Replace this lambda with method reference 'Objects::nonNull'.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/http/ControlPlaneClientTest.java:246 — Replace this lambda with method reference 'client::listFunctions'.
### java:S3077 (2)
  platform/modules/async-queue/src/main/java/it/unimib/datai/nanofaas/modules/asyncqueue/Scheduler.java:25 — Use a thread-safe type; adding "volatile" is not enough to make this field thread-safe.
  platform/modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/controlplane/scheduler/SyncScheduler.java:33 — Use a thread-safe type; adding "volatile" is not enough to make this field thread-safe.
### java:S4030 (2)
  platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/RateLimiterTest.java:68 — Consume or remove this unused collection
  platform/modules/async-queue/src/test/java/it/unimib/datai/nanofaas/modules/asyncqueue/FunctionQueueStateTest.java:55 — Consume or remove this unused collection
### java:S4276 (2)
  platform/modules/k8s-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/k8s/config/KubernetesClientConfig.java:35 — Refactor this code to use the more specialised Functional Interface 'UnaryOperator<String>'
  clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/config/ConfigStore.java:26 — Refactor this code to use the more specialised Functional Interface 'UnaryOperator<String>'
### java:S4507 (1)
  clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/NanofaasCli.java:19 — Make sure this debug feature is deactivated before delivering the code in production.
### java:S5411 (1)
  platform/modules/offload/src/main/java/it/unimib/datai/nanofaas/modules/offload/OffloadConfiguration.java:22 — Use a primitive boolean expression here.
### java:S5838 (56)
  platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/execution/IdempotencyStoreTest.java:106 — Use isZero() instead.
  platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/ExecutionCompletionHandlerTest.java:207 — Use isZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/fn/FnTestCommandTest.java:71 — Use isZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/fn/FnTestCommandTest.java:214 — Use isZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/fn/FnApplyCommandTest.java:278 — Use isNotZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/fn/FnApplyCommandTest.java:302 — Use isNotZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/fn/FnGetCommandTest.java:44 — Use isZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/fn/FnListCommandTest.java:41 — Use isZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/fn/FnListCommandTest.java:60 — Use isZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/RootCommandTest.java:88 — Use isNotZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/RootCommandTest.java:151 — Use isZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/deploy/DeployCommandTest.java:null — Use isNotZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/deploy/DeployCommandTest.java:null — Use isNotZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/deploy/DeployCommandTest.java:52 — Use isNotZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/deploy/DeployCommandTest.java:89 — Use isNotZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/deploy/DeployCommandTest.java:117 — Use isNotZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/deploy/DeployCommandTest.java:151 — Use isZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/deploy/DeployCommandTest.java:198 — Use isZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/deploy/DeployCommandTest.java:247 — Use isZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/deploy/DeployCommandTest.java:287 — Use isZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/exec/ExecGetCommandTest.java:50 — Use isZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/exec/ExecGetCommandTest.java:79 — Use isZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/exec/ExecGetCommandTest.java:114 — Use isZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/exec/ExecGetCommandTest.java:139 — Use isZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/exec/ExecGetCommandTest.java:161 — Use isNotZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/fn/FnApplyCommandTest.java:55 — Use isZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/fn/FnApplyCommandTest.java:156 — Use isZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/fn/FnApplyCommandTest.java:229 — Use isZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/fn/FnApplyCommandTest.java:255 — Use isNotZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/fn/FnDeleteCommandTest.java:38 — Use isZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/fn/FnGetCommandTest.java:61 — Use isNotZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/invoke/EnqueueCommandTest.java:56 — Use isZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/invoke/EnqueueCommandTest.java:85 — Use isZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/invoke/EnqueueCommandTest.java:115 — Use isZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/invoke/EnqueueCommandTest.java:147 — Use isZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/invoke/EnqueueCommandTest.java:169 — Use isNotZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/invoke/EnqueueCommandTest.java:182 — Use isNotZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/invoke/InvokeCommandTest.java:57 — Use isZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/invoke/InvokeCommandTest.java:93 — Use isZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/invoke/InvokeCommandTest.java:121 — Use isZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/invoke/InvokeCommandTest.java:152 — Use isZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/invoke/InvokeCommandTest.java:174 — Use isNotZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/invoke/InvokeCommandTest.java:187 — Use isNotZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/fn/FnApplyCommandTest.java:105 — Use isZero() instead.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/RootCommandTest.java:44 — Use isZero() instead.
  platform/modules/async-queue/src/test/java/it/unimib/datai/nanofaas/modules/asyncqueue/FunctionQueueStateFloorTest.java:15 — Use isZero() instead.
  platform/modules/async-queue/src/test/java/it/unimib/datai/nanofaas/modules/asyncqueue/FunctionQueueStateFloorTest.java:21 — Use isZero() instead.
  platform/modules/async-queue/src/test/java/it/unimib/datai/nanofaas/modules/asyncqueue/FunctionQueueStateFloorTest.java:38 — Use isZero() instead.
  platform/modules/async-queue/src/test/java/it/unimib/datai/nanofaas/modules/asyncqueue/FunctionQueueStateFloorTest.java:46 — Use isZero() instead.
  platform/modules/async-queue/src/test/java/it/unimib/datai/nanofaas/modules/asyncqueue/FunctionQueueStateFloorTest.java:50 — Use isZero() instead.
  platform/modules/async-queue/src/test/java/it/unimib/datai/nanofaas/modules/asyncqueue/FunctionQueueStateFloorTest.java:60 — Use isZero() instead.
  platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/execution/IdempotencyStoreTest.java:79 — Use isZero() instead.
  platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/RateLimiterTest.java:145 — Use isZero() instead.
  platform/modules/async-queue/src/test/java/it/unimib/datai/nanofaas/modules/asyncqueue/FunctionQueueStateTest.java:93 — Use isZero() instead.
  platform/modules/async-queue/src/test/java/it/unimib/datai/nanofaas/modules/asyncqueue/FunctionQueueStateTest.java:105 — Use isZero() instead.
  platform/modules/async-queue/src/test/java/it/unimib/datai/nanofaas/modules/asyncqueue/FunctionQueueStateTest.java:129 — Use isZero() instead.
### java:S5853 (12)
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/http/HttpJsonTest.java:42 — Join these multiple assertions subject to one assertion chain.
  platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/api/FunctionResponseContractTest.java:193 — Join these multiple assertions subject to one assertion chain.
  platform/modules/offload/src/test/java/it/unimib/datai/nanofaas/modules/offload/OffloadPressureE2eTest.java:130 — Join these multiple assertions subject to one assertion chain.
  platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/api/FunctionResponseContractTest.java:153 — Join these multiple assertions subject to one assertion chain.
  platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/api/FunctionResponseContractTest.java:251 — Join these multiple assertions subject to one assertion chain.
  platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/api/FunctionResponseContractTest.java:50 — Join these multiple assertions subject to one assertion chain.
  platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/api/FunctionResponseContractTest.java:87 — Join these multiple assertions subject to one assertion chain.
  platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/api/FunctionResponseContractTest.java:115 — Join these multiple assertions subject to one assertion chain.
  platform/modules/autoscaler/src/test/java/it/unimib/datai/nanofaas/modules/autoscaler/StaticPerPodConcurrencyControllerTest.java:74 — Join these multiple assertions subject to one assertion chain.
  platform/modules/k8s-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/k8s/config/VertxRuntimeHintsBranchTest.java:40 — Join these multiple assertions subject to one assertion chain.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/exec/ExecGetCommandTest.java:121 — Join these multiple assertions subject to one assertion chain.
  clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/fn/FnListCommandTest.java:44 — Join these multiple assertions subject to one assertion chain.
### java:S6068 (1)
  platform/modules/autoscaler/src/test/java/it/unimib/datai/nanofaas/modules/autoscaler/InternalScalerTest.java:229 — Remove this and every subsequent useless "eq(...)" invocation; pass the values directly.
### java:S7467 (55)
  sdks/java-lite/src/main/java/it/unimib/datai/nanofaas/sdk/lite/callback/CallbackClient.java:51 — Replace "ie" with an unnamed pattern.
  sdks/java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/CallbackClient.java:52 — Replace "ie" with an unnamed pattern.
  platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/ExecutionCompletionHandler.java:254 — Replace "ex" with an unnamed pattern.
  functions/java/figlet/src/main/java/it/unimib/datai/nanofaas/examples/figlet/FigletHandler.java:46 — Replace "e" with an unnamed pattern.
  platform/modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/DockerJavaContainerRuntimeAdapter.java:39 — Replace "e" with an unnamed pattern.
  platform/modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/DockerJavaContainerRuntimeAdapter.java:81 — Replace "ignored" with an unnamed pattern.
  sdks/java-lite/src/main/java/it/unimib/datai/nanofaas/sdk/lite/handler/InvokeHandler.java:74 — Replace "ignored" with an unnamed pattern.
  sdks/java-lite/src/main/java/it/unimib/datai/nanofaas/sdk/lite/handler/InvokeHandler.java:129 — Replace "ex" with an unnamed pattern.
  sdks/java-lite/src/main/java/it/unimib/datai/nanofaas/sdk/lite/handler/InvokeHandler.java:165 — Replace "ex" with an unnamed pattern.
  sdks/java-lite/src/main/java/it/unimib/datai/nanofaas/sdk/lite/handler/InvokeHandler.java:201 — Replace "ex" with an unnamed pattern.
  sdks/java-lite/src/test/java/it/unimib/datai/nanofaas/sdk/lite/handler/InvokeHandlerTest.java:238 — Replace "ex" with an unnamed pattern.
  platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/dispatch/PoolDispatcher.java:85 — Replace "ignored" with an unnamed pattern.
  platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/api/InvocationController.java:115 — Replace "ex" with an unnamed pattern.
  functions/java/roman-numeral/src/main/java/it/unimib/datai/nanofaas/examples/romannumeral/RomanNumeralHandler.java:36 — Replace "e" with an unnamed pattern.
  platform/modules/container-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/ContainerLocalDeploymentProviderTest.java:401 — Replace "ignored" with an unnamed pattern.
  platform/modules/container-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/ContainerLocalDeploymentProviderTest.java:422 — Replace "e" with an unnamed pattern.
  platform/modules/container-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/ContainerLocalDeploymentProviderTest.java:572 — Replace "e" with an unnamed pattern.
  platform/modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/HttpEndpointProbe.java:49 — Replace "e" with an unnamed pattern.
  platform/modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/ProcessCliCommandExecutor.java:56 — Replace "ignored" with an unnamed pattern.
  platform/modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/ContainerLocalDeploymentProvider.java:271 — Replace "ignored" with an unnamed pattern.
  platform/modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/HttpEndpointProbe.java:52 — Replace "ignored" with an unnamed pattern.
  platform/modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/ProcessCliCommandExecutor.java:47 — Replace "e" with an unnamed pattern.
  platform/modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/RoundRobinFunctionProxy.java:89 — Replace "e" with an unnamed pattern.
  sdks/java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/InvokeController.java:96 — Replace "ex" with an unnamed pattern.
  sdks/java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/CallbackDispatcher.java:101 — Replace "ex" with an unnamed pattern.
  sdks/java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/CallbackDispatcher.java:85 — Replace "ex" with an unnamed pattern.
  platform/modules/autoscaler/src/main/java/it/unimib/datai/nanofaas/modules/autoscaler/ScalingDecisionCalculator.java:55 — Replace "ex" with an unnamed pattern.
  platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/scheduler/SchedulerLifecycleSupport.java:32 — Replace "ex" with an unnamed pattern.
  platform/modules/runtime-config/src/main/java/it/unimib/datai/nanofaas/modules/runtimeconfig/AdminRuntimeConfigController.java:132 — Replace "e" with an unnamed pattern.
  platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/InvocationServiceDispatchTest.java:792 — Replace "ex" with an unnamed pattern.
  platform/modules/k8s-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/k8s/dispatch/KubernetesMetricsTranslator.java:98 — Replace "e" with an unnamed pattern.
  platform/modules/runtime-config/src/test/java/it/unimib/datai/nanofaas/modules/runtimeconfig/RuntimeConfigServiceTest.java:84 — Replace "e" with an unnamed pattern.
  platform/modules/runtime-config/src/test/java/it/unimib/datai/nanofaas/modules/runtimeconfig/RuntimeConfigServiceTest.java:86 — Replace "ignored" with an unnamed pattern.
  platform/modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/controlplane/sync/SyncQueueService.java:117 — Replace "ignored" with an unnamed pattern.
  platform/modules/async-queue/src/main/java/it/unimib/datai/nanofaas/modules/asyncqueue/Scheduler.java:106 — Replace "e" with an unnamed pattern.
  functions/java/json-transform-lite/src/main/java/it/unimib/datai/nanofaas/examples/jsontransformlite/JsonTransformLite.java:45 — Replace "e" with an unnamed pattern.
  sdks/java-lite/src/main/java/it/unimib/datai/nanofaas/sdk/lite/NanofaasRuntime.java:54 — Replace "e" with an unnamed pattern.
  platform/modules/k8s-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/k8s/config/VertxRuntimeHints.java:85 — Replace "ignored" with an unnamed pattern.
  platform/modules/k8s-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/k8s/config/VertxRuntimeHints.java:104 — Replace "ignored" with an unnamed pattern.
  platform/modules/k8s-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/k8s/config/VertxRuntimeHints.java:122 — Replace "ignored" with an unnamed pattern.
  platform/modules/k8s-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/k8s/config/VertxRuntimeHints.java:139 — Replace "ignored" with an unnamed pattern.
  clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/http/ControlPlaneError.java:18 — Replace "ignored" with an unnamed pattern.
  platform/modules/image-validator/src/main/java/it/unimib/datai/nanofaas/modules/imagevalidator/KubernetesImageValidator.java:60 — Replace "e" with an unnamed pattern.
  platform/modules/image-validator/src/main/java/it/unimib/datai/nanofaas/modules/imagevalidator/KubernetesImageValidator.java:74 — Replace "ignored" with an unnamed pattern.
  platform/modules/image-validator/src/main/java/it/unimib/datai/nanofaas/modules/imagevalidator/KubernetesImageValidator.java:97 — Replace "e" with an unnamed pattern.
  platform/modules/autoscaler/src/main/java/it/unimib/datai/nanofaas/modules/autoscaler/TargetLoadMetrics.java:103 — Replace "ignored" with an unnamed pattern.
  platform/modules/autoscaler/src/main/java/it/unimib/datai/nanofaas/modules/autoscaler/InternalScaler.java:85 — Replace "ex" with an unnamed pattern.
  functions/java/json-transform/src/main/java/it/unimib/datai/nanofaas/examples/jsontransform/JsonTransformHandler.java:39 — Replace "e" with an unnamed pattern.
  services/java/warm-echo/src/test/java/it/unimib/datai/nanofaas/services/warmecho/core/TraceLoggingFilterHeaderPriorityTest.java:88 — Replace "ignored" with an unnamed pattern.
  platform/modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/controlplane/scheduler/SyncScheduler.java:153 — Replace "ignored" with an unnamed pattern.
  platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionServiceConcurrencyTest.java:69 — Replace "e" with an unnamed pattern.
  platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionServiceConcurrencyTest.java:111 — Replace "e" with an unnamed pattern.
  platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/RateLimiterTest.java:78 — Replace "e" with an unnamed pattern.
  platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/RateLimiterTest.java:133 — Replace "e" with an unnamed pattern.
  platform/modules/async-queue/src/test/java/it/unimib/datai/nanofaas/modules/asyncqueue/FunctionQueueStateTest.java:72 — Replace "e" with an unnamed pattern.
### java:S8714 (1)
  platform/modules/container-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/ContainerLocalDeploymentProviderTest.java:423 — Use assertDoesNotThrow() instead of try/catch and fail() in the catch block.
### java:S8924 (1)
  platform/modules/autoscaler/src/test/java/it/unimib/datai/nanofaas/modules/autoscaler/AdaptivePerPodConcurrencyControllerTest.java:134 — Use a static import for "mock".
### java:S899 (1)
  platform/modules/async-queue/src/main/java/it/unimib/datai/nanofaas/modules/asyncqueue/Scheduler.java:44 — Do something with the "boolean" value returned by "offer".

