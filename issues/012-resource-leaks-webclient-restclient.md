# Issue 012: Resource leak - WebClient e RestClient non gestiti

**Severità**: BASSA
**Componente**: control-plane/core/PoolDispatcher.java, function-runtime/core/CallbackClient.java
**Linee**: PoolDispatcher:14-16, CallbackClient:10

## Descrizione

`PoolDispatcher` e `CallbackClient` creano istanze di `WebClient` e `RestClient` senza gestirle come bean Spring. Questo può causare leak di risorse (connection pool, thread) e non segue le best practice Spring.

```java
// PoolDispatcher.java - PROBLEMA
public PoolDispatcher(WebClient.Builder builder) {
    this.webClient = builder.build();  // Crea nuovo WebClient, non bean
}

// CallbackClient.java - PROBLEMA
private final RestClient restClient = RestClient.create();  // Crea nuovo RestClient
```

## Problemi

1. **Connection pool non condiviso**: Ogni istanza ha il suo pool
2. **Configurazione non centralizzata**: Timeout, retry non configurabili
3. **Non segue best practice Spring**: Client dovrebbero essere bean
4. **Difficile da testare**: Non iniettabile con mock
5. **Potenziale memory leak**: Se WebClient/RestClient non chiusi

## Impatto

1. Uso memoria subottimale
2. Troppi connection pool
3. Configurazione dispersa
4. Testing più complesso

## Piano di Risoluzione

### Step 1: Creare bean WebClient configurato (control-plane)

```java
// HttpClientConfig.java
@Configuration
public class HttpClientConfig {

    @Bean
    public WebClient webClient(WebClient.Builder builder) {
        return builder
            .codecs(configurer -> configurer
                .defaultCodecs()
                .maxInMemorySize(1024 * 1024))  // 1MB
            .build();
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        HttpClient httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
            .responseTimeout(Duration.ofSeconds(30));

        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
```

### Step 2: Iniettare WebClient in PoolDispatcher

```java
// PoolDispatcher.java - CORRETTO
@Component
public class PoolDispatcher implements Dispatcher {
    private final WebClient webClient;

    public PoolDispatcher(WebClient webClient) {  // Inietta bean
        this.webClient = webClient;
    }

    // ... rest unchanged ...
}
```

### Step 3: Creare bean RestClient configurato (function-runtime)

```java
// HttpClientConfig.java (function-runtime)
@Configuration
public class HttpClientConfig {

    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        return builder
            .requestFactory(clientHttpRequestFactory())
            .build();
    }

    @Bean
    public ClientHttpRequestFactory clientHttpRequestFactory() {
        HttpComponentsClientHttpRequestFactory factory =
            new HttpComponentsClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        return factory;
    }
}
```

### Step 4: Iniettare RestClient in CallbackClient

```java
// CallbackClient.java - CORRETTO
@Component
public class CallbackClient {
    private final RestClient restClient;
    private final String baseUrl;

    public CallbackClient(RestClient restClient) {  // Inietta bean
        this.restClient = restClient;
        this.baseUrl = System.getenv("CALLBACK_URL");
    }

    // ... rest unchanged ...
}
```

### Step 5: Aggiungere configurazione esterna

```yaml
# application.yml (control-plane)
nanofaas:
  http-client:
    connect-timeout-ms: 5000
    read-timeout-ms: 30000
    max-connections: 100
    max-in-memory-size-mb: 1

# application.yml (function-runtime)
nanofaas:
  callback:
    connect-timeout-ms: 5000
    read-timeout-ms: 10000
```

```java
// HttpClientProperties.java
@ConfigurationProperties(prefix = "nanofaas.http-client")
public record HttpClientProperties(
    int connectTimeoutMs,
    int readTimeoutMs,
    int maxConnections,
    int maxInMemorySizeMb
) {
    public HttpClientProperties {
        if (connectTimeoutMs <= 0) connectTimeoutMs = 5000;
        if (readTimeoutMs <= 0) readTimeoutMs = 30000;
        if (maxConnections <= 0) maxConnections = 100;
        if (maxInMemorySizeMb <= 0) maxInMemorySizeMb = 1;
    }
}
```

## Test da Creare

### Test 1: WebClientBeanTest
```java
@SpringBootTest
class HttpClientConfigTest {

    @Autowired
    private WebClient webClient;

    @Test
    void webClient_isConfigured() {
        assertThat(webClient).isNotNull();
    }

    @Test
    void webClient_hasTimeout() {
        // Verifica che il timeout sia configurato
        // (difficile da testare direttamente, verificare con mock server)
    }
}
```

### Test 2: PoolDispatcherInjectionTest
```java
@SpringBootTest
class PoolDispatcherTest {

    @Autowired
    private PoolDispatcher poolDispatcher;

    @MockBean
    private WebClient webClient;

    @Test
    void poolDispatcher_usesInjectedWebClient() {
        // Verifica che PoolDispatcher usi il WebClient mockato
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        // ...
    }
}
```

### Test 3: CallbackClientInjectionTest
```java
@SpringBootTest
class CallbackClientTest {

    @Autowired
    private CallbackClient callbackClient;

    @MockBean
    private RestClient restClient;

    @Test
    void callbackClient_usesInjectedRestClient() {
        // Verifica che CallbackClient usi il RestClient mockato
        when(restClient.post()).thenReturn(requestBodyUriSpec);
        // ...
    }
}
```

### Test 4: TimeoutConfigurationTest
```java
@SpringBootTest(properties = {
    "nanofaas.http-client.connect-timeout-ms=1000",
    "nanofaas.http-client.read-timeout-ms=5000"
})
class HttpClientTimeoutTest {

    @Autowired
    private HttpClientProperties properties;

    @Test
    void properties_areLoaded() {
        assertThat(properties.connectTimeoutMs()).isEqualTo(1000);
        assertThat(properties.readTimeoutMs()).isEqualTo(5000);
    }
}
```

## File da Modificare

### control-plane
1. `control-plane/src/main/java/com/nanofaas/controlplane/config/HttpClientConfig.java` (nuovo)
2. `control-plane/src/main/java/com/nanofaas/controlplane/config/HttpClientProperties.java` (nuovo)
3. `control-plane/src/main/java/com/nanofaas/controlplane/core/PoolDispatcher.java`
4. `control-plane/src/main/resources/application.yml`
5. `control-plane/src/test/java/com/nanofaas/controlplane/config/HttpClientConfigTest.java` (nuovo)

### function-runtime
1. `function-runtime/src/main/java/com/nanofaas/runtime/config/HttpClientConfig.java` (nuovo)
2. `function-runtime/src/main/java/com/nanofaas/runtime/core/CallbackClient.java`
3. `function-runtime/src/main/resources/application.yml`
4. `function-runtime/src/test/java/com/nanofaas/runtime/config/HttpClientConfigTest.java` (nuovo)

## Criteri di Accettazione

- [ ] WebClient è un bean Spring singleton
- [ ] RestClient è un bean Spring singleton
- [ ] Timeout configurabili da application.yml
- [ ] PoolDispatcher riceve WebClient via constructor injection
- [ ] CallbackClient riceve RestClient via constructor injection
- [ ] Test con mock funzionano
- [ ] Nessun memory leak (verificare con profiler)

## Note di Sviluppo

_Spazio per appunti durante l'implementazione_

### Dipendenze per timeout configurazione

Per Spring WebFlux (PoolDispatcher):
```groovy
implementation 'io.projectreactor.netty:reactor-netty'
```

Per Spring Web (CallbackClient):
```groovy
implementation 'org.apache.httpcomponents.client5:httpclient5'
```

### Alternativa: configurazione programmatica

Se non si vuole usare HttpComponents:

```java
// Per RestClient con Spring default
SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
factory.setConnectTimeout(5000);
factory.setReadTimeout(10000);
RestClient.builder().requestFactory(factory).build();
```

---
