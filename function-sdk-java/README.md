# nanofaas Java SDK

SDK Spring Boot per sviluppare funzioni nanoFaaS in Java.

## Cosa fornisce

- `@NanofaasFunction` per la discovery automatica dell'handler.
- `FunctionContext` per accedere a `executionId` e `traceId` dentro il codice utente.
- Runtime HTTP con `POST /invoke`, `GET /health` e `GET /metrics`.
- Callback verso il control plane con retry e gestione del cold start.

## Come viene usato

```java
@NanofaasFunction
public class EchoHandler implements FunctionHandler {
    @Override
    public Object handle(InvocationRequest request) {
        return request.input();
    }
}
```

Il runtime viene avviato come normale applicazione Spring Boot e legge il suo contesto dai seguenti env var:

- `EXECUTION_ID`
- `TRACE_ID`
- `CALLBACK_URL`
- `FUNCTION_HANDLER`

## Contratto runtime

- `X-Execution-Id` e `X-Trace-Id` vengono letti dalla richiesta quando presenti.
- Se `X-Execution-Id` manca, il runtime usa `EXECUTION_ID`.
- L'invocazione fallisce con `400` se nessun execution id è disponibile.
- Timeout handler e fallimenti handler vengono convertiti in callback di errore.
- Il primo invoke può includere gli header `X-Cold-Start` e `X-Init-Duration-Ms`.

## Sviluppo locale

```bash
./gradlew :function-sdk-java:test
```

