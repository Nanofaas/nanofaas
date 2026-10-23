
## Structured logging

Set `NANOFAAS_LOG_FORMAT=json` to emit the shared [logging contract](../runtime-contract/logging-schema.json); `NANOFAAS_LOG_SERVICE` selects the service name. Default text logging remains available. Invocation IDs are scoped across handler and callback threads. Spring hosts may retain their own `logging.config`; an explicit host configuration takes precedence. The shared configuration can also be selected with `LOGGING_CONFIG=classpath:nanofaas-logback.xml`. No SDK sends logs directly to Loki.
