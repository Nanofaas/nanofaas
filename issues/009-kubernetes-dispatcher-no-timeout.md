# Issue 009: KubernetesDispatcher non ha timeout sulle chiamate K8s API

**Severità**: MEDIA
**Componente**: control-plane/core/KubernetesDispatcher.java
**Linee**: 21-31

## Descrizione

`KubernetesDispatcher.dispatch()` chiama l'API Kubernetes senza timeout configurato. Se l'API server è lento o non risponde, la chiamata può bloccarsi indefinitamente.

```java
// KubernetesDispatcher.java - CODICE ATTUALE
@Override
public CompletableFuture<InvocationResult> dispatch(InvocationTask task) {
    return CompletableFuture.supplyAsync(() -> {
        KubernetesJobBuilder builder = new KubernetesJobBuilder(properties);
        Job job = builder.build(task);
        client.batch().v1().jobs()
                .inNamespace(namespace())
                .resource(job)
                .create();  // NESSUN TIMEOUT! Può bloccarsi per sempre
        return InvocationResult.success(null);
    });
}
```

## Scenari Problematici

1. **K8s API server sovraccarico**: Risposta lenta (>30s)
2. **Network partition**: Connessione appesa
3. **API server in manutenzione**: Connection timeout
4. **DNS failure**: Risoluzione nome lenta

## Impatto

1. Thread del pool bloccati indefinitamente
2. Esaurimento del thread pool di CompletableFuture
3. Invocazioni successive non vengono processate
4. Sistema diventa non responsivo
5. Nessun errore riportato al client

## Piano di Risoluzione

### Step 1: Configurare timeout sul Kubernetes client

```java
// KubernetesDispatcher.java - CODICE CORRETTO
@Component
public class KubernetesDispatcher implements Dispatcher {
    private static final Logger log = LoggerFactory.getLogger(KubernetesDispatcher.class);

    private final KubernetesClient client;
    private final KubernetesProperties properties;
    private final Duration apiTimeout;

    public KubernetesDispatcher(KubernetesClient client, KubernetesProperties properties) {
        this.client = client;
        this.properties = properties;
        this.apiTimeout = Duration.ofSeconds(
            properties.apiTimeoutSeconds() != null ? properties.apiTimeoutSeconds() : 10
        );
    }

    @Override
    public CompletableFuture<InvocationResult> dispatch(InvocationTask task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                KubernetesJobBuilder builder = new KubernetesJobBuilder(properties);
                Job job = builder.build(task);

                Job created = client.batch().v1().jobs()
                        .inNamespace(namespace())
                        .resource(job)
                        .create();

                log.debug("Created Job {} for execution {}",
                    created.getMetadata().getName(), task.executionId());

                return InvocationResult.success(null);

            } catch (KubernetesClientException ex) {
                log.error("K8s API error creating job for {}: {}",
                    task.executionId(), ex.getMessage());
                throw new DispatchException("K8s job creation failed: " + ex.getMessage(), ex);
            }
        }).orTimeout(apiTimeout.toMillis(), TimeUnit.MILLISECONDS)
          .exceptionally(ex -> {
              if (ex instanceof TimeoutException || ex.getCause() instanceof TimeoutException) {
                  log.error("K8s API timeout creating job for {}", task.executionId());
                  return InvocationResult.error("K8S_TIMEOUT", "Kubernetes API timeout");
              }
              log.error("Dispatch error for {}: {}", task.executionId(), ex.getMessage());
              return InvocationResult.error("DISPATCH_ERROR", ex.getMessage());
          });
    }
}
```

### Step 2: Aggiungere configurazione

```yaml
# application.yml
nanofaas:
  k8s:
    namespace: ""
    callbackUrl: "http://control-plane.default.svc.cluster.local:8080/v1/internal/executions"
    apiTimeoutSeconds: 10  # NUOVO
```

```java
// KubernetesProperties.java
@ConfigurationProperties(prefix = "nanofaas.k8s")
public record KubernetesProperties(
    String namespace,
    String callbackUrl,
    Integer apiTimeoutSeconds  // NUOVO
) {}
```

### Step 3: Configurare timeout a livello di client Fabric8

```java
// KubernetesClientConfig.java
@Configuration
public class KubernetesClientConfig {

    @Bean
    public KubernetesClient kubernetesClient(KubernetesProperties props) {
        Config config = new ConfigBuilder()
            .withRequestTimeout(10_000)      // 10s request timeout
            .withConnectionTimeout(5_000)    // 5s connection timeout
            .withRequestRetryBackoffLimit(2) // Max 2 retry
            .build();

        return new KubernetesClientBuilder()
            .withConfig(config)
            .build();
    }
}
```

### Step 4: Aggiungere metriche per K8s latency

```java
// Metrics.java
public void k8sJobCreationLatency(String functionName, long latencyMs) {
    Timer.builder("nanofaas_k8s_job_creation_latency_ms")
        .tag("function", functionName)
        .register(registry)
        .record(latencyMs, TimeUnit.MILLISECONDS);
}

public void k8sJobCreationError(String functionName, String errorType) {
    Counter.builder("nanofaas_k8s_job_creation_errors")
        .tag("function", functionName)
        .tag("error_type", errorType)  // TIMEOUT, API_ERROR, etc.
        .register(registry)
        .increment();
}
```

```java
// KubernetesDispatcher.java - con metriche
@Override
public CompletableFuture<InvocationResult> dispatch(InvocationTask task) {
    long startTime = System.currentTimeMillis();

    return CompletableFuture.supplyAsync(() -> {
        // ... create job ...
        return InvocationResult.success(null);
    }).orTimeout(apiTimeout.toMillis(), TimeUnit.MILLISECONDS)
      .whenComplete((result, ex) -> {
          long latencyMs = System.currentTimeMillis() - startTime;
          metrics.k8sJobCreationLatency(task.functionName(), latencyMs);

          if (ex != null) {
              String errorType = ex instanceof TimeoutException ? "TIMEOUT" : "API_ERROR";
              metrics.k8sJobCreationError(task.functionName(), errorType);
          }
      });
}
```

## Test da Creare

### Test 1: KubernetesDispatcherSuccessTest
```java
@Test
void dispatch_whenApiResponds_createsJob() {
    // Mock K8s client che risponde velocemente
    when(client.batch().v1().jobs().inNamespace(any()).resource(any()).create())
        .thenReturn(mockJob);

    CompletableFuture<InvocationResult> future = dispatcher.dispatch(task);
    InvocationResult result = future.get(5, TimeUnit.SECONDS);

    assertThat(result.success()).isTrue();
    verify(client.batch().v1().jobs()).create();
}
```

### Test 2: KubernetesDispatcherTimeoutTest
```java
@Test
void dispatch_whenApiSlow_timesOut() {
    // Mock K8s client che blocca per 30 secondi
    when(client.batch().v1().jobs().inNamespace(any()).resource(any()).create())
        .thenAnswer(inv -> {
            Thread.sleep(30_000);
            return mockJob;
        });

    // Dispatcher ha timeout di 10s
    CompletableFuture<InvocationResult> future = dispatcher.dispatch(task);
    InvocationResult result = future.get(15, TimeUnit.SECONDS);

    assertThat(result.success()).isFalse();
    assertThat(result.error().code()).isEqualTo("K8S_TIMEOUT");
}
```

### Test 3: KubernetesDispatcherApiErrorTest
```java
@Test
void dispatch_whenApiErrors_returnsError() {
    when(client.batch().v1().jobs().inNamespace(any()).resource(any()).create())
        .thenThrow(new KubernetesClientException("Forbidden"));

    CompletableFuture<InvocationResult> future = dispatcher.dispatch(task);
    InvocationResult result = future.get(5, TimeUnit.SECONDS);

    assertThat(result.success()).isFalse();
    assertThat(result.error().message()).contains("Forbidden");
}
```

### Test 4: KubernetesDispatcherMetricsTest
```java
@Test
void dispatch_recordsLatencyMetric() {
    when(client.batch().v1().jobs().inNamespace(any()).resource(any()).create())
        .thenReturn(mockJob);

    dispatcher.dispatch(task).get(5, TimeUnit.SECONDS);

    // Verifica che la metrica sia stata registrata
    verify(metrics).k8sJobCreationLatency(eq("myFunc"), anyLong());
}

@Test
void dispatch_onTimeout_recordsErrorMetric() {
    when(client.batch().v1().jobs().inNamespace(any()).resource(any()).create())
        .thenAnswer(inv -> {
            Thread.sleep(30_000);
            return mockJob;
        });

    dispatcher.dispatch(task).get(15, TimeUnit.SECONDS);

    verify(metrics).k8sJobCreationError("myFunc", "TIMEOUT");
}
```

## File da Modificare

1. `control-plane/src/main/java/com/nanofaas/controlplane/core/KubernetesDispatcher.java`
2. `control-plane/src/main/java/com/nanofaas/controlplane/config/KubernetesProperties.java`
3. `control-plane/src/main/java/com/nanofaas/controlplane/config/KubernetesClientConfig.java` (nuovo o esistente)
4. `control-plane/src/main/java/com/nanofaas/controlplane/core/Metrics.java`
5. `control-plane/src/main/resources/application.yml`
6. `control-plane/src/test/java/com/nanofaas/controlplane/core/KubernetesDispatcherTest.java` (estendere)

## Criteri di Accettazione

- [ ] Timeout configurabile per K8s API calls (default 10s)
- [ ] Connection timeout 5s, request timeout 10s
- [ ] Dispatch ritorna errore dopo timeout invece di bloccarsi
- [ ] Metriche latency e error registrate
- [ ] Tutti i test passano
- [ ] Nessun thread bloccato indefinitamente

## Note di Sviluppo

_Spazio per appunti durante l'implementazione_

### Verifica: come è configurato il KubernetesClient attualmente?

```bash
grep -r "KubernetesClient" control-plane/src/main/java/
grep -r "Config" control-plane/src/main/java/ | grep -i kube
```

### Alternative a orTimeout

Java 9+ ha `orTimeout()` e `completeOnTimeout()`. Se serve compatibilità Java 8, usare:

```java
ScheduledExecutorService timeoutScheduler = Executors.newSingleThreadScheduledExecutor();
CompletableFuture<InvocationResult> future = // ...
timeoutScheduler.schedule(() -> {
    future.completeExceptionally(new TimeoutException("K8s API timeout"));
}, 10, TimeUnit.SECONDS);
```

---
