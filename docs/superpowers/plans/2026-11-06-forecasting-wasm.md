# Forecasting WASM Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Aggiungere un provider Holt Rust/WASM opzionale, equivalente al Java, e misurarne la praticabilità su JVM e Native Image.

**Architecture:** Un modulo Java `forecasting-wasm` integra GraalWasm e implementa un piccolo contratto numerico in forecasting-api. Il forecasting esistente conserva lo storico, le generazioni e la semantica delle query; cambia solo il calcolo del percorso Holt. L'artifact WASM fidato è incluso alla build, senza modificare inizialmente il plugin Gradle centrale.

**Tech Stack:** Java 25, Spring Boot e GraalVM fissati da gradle.properties; Rust con toolchain fissata, wasm32-unknown-unknown, GraalWasm/Polyglot con dipendenze fissate dopo il probe; JUnit 5 e Cargo test.

**Spec:** `docs/superpowers/specs/2026-11-06-forecasting-wasm-design.md` (proposta da approvare prima dell'esecuzione).

## Global Constraints

- ABI v1; 1..100000 campioni, 1..4096 passi, pesi finiti in (0,1]; output finito e non negativo; nessuna mutazione input.
- Parità: `abs(a-b) <= 1e-9 * max(1, abs(a))` per input non in overflow.
- Provider `HOLT_WASM` / `holt_wasm`; nessuna modifica del comportamento di EWMA, ORACLE, HOLT e ARI.
- Un Context serializzato a runtime, nessun Engine durante AOT; calcolo fuori dal lock degli arrivi.
- Modulo assente: nessun runtime Polyglot nel classpath del control plane; forecasting disabilitato: nessun Context.
- Guest fidato incluso alla build; niente WASI, hot reload, RPC, plugin non fidati o API amministrativa aggiuntiva.
- Primo budget da provare: heap 64 MiB; RSS misurata separatamente. Nessun aumento silenzioso.
- Nessuna modifica iniziale del plugin Gradle centrale; niente VM Spot, merge o push impliciti.
- Esecuzione in worktree isolato; preservare file estranei. GitNexus impact prima delle modifiche e detect_changes completo prima dei commit; UNKNOWN richiede verifica testuale.

## Review Focus

1. Provider configurato ma modulo assente: startup diagnostico e patch runtime rifiutata atomicamente — Task 4.
2. Trap, output NaN/Infinity o ABI errata: mai un falso forecast zero, mai fallback nascosto — Task 2/3.
3. Query contemporanee a record/retain e cambio generazione: nessun Context usato contemporaneamente, nessun lock degli arrivi trattenuto dal guest — Task 3/4.
4. Build native da snapshot senza Rust e file WASM assente/stale: errore prima del packaging oppure artifact incluso e verificato — Task 1/5.
5. Modulo incluso ma disabilitato o toggle verso provider Java: risorse e provider disponibili coerenti, storico preservato — Task 4/5.

## Mappa dei file

- `platform/libs/forecasting-api/.../HoltForecastAlgorithm.java`: solo contratto numerico, senza dipendenze GraalVM.
- `platform/modules/forecasting-wasm/`: modulo opzionale, adapter, autoconfigurazione, RuntimeHints, test, risorsa WASM e sorgenti Rust.
- `platform/modules/forecasting/.../TrendForecastSource.java`: hook per il percorso Holt, conservando formula Java e aggregazione esistente.
- `platform/modules/forecasting/.../{ForecastingConfiguration,ForecastingProperties,ForecastingRuntimeConfigExtension}.java`: selezione e disponibilità del provider opzionale.
- `docs/forecasting.md`, `docs/recipes.md`, `platform/modules/runtime-config/openapi.yaml`: configurazione e contratto pubblico quando elenca i provider.
- `docs/experiments/forecasting-wasm-2026-11-06/`: recipe JVM/native, harness e risultati.

Nei task, `F` indica `platform/modules/forecasting/src/main/java/it/unimib/datai/nanofaas/modules/forecasting`, `W` indica `platform/modules/forecasting-wasm`, `WP` indica `W/src/main/java/it/unimib/datai/nanofaas/modules/forecastingwasm`; `FT` e `WT` sono i corrispondenti percorsi src/test/java. Espandere questi alias prima degli edit.

### Task 1: provare l'embedding reale prima di integrare forecasting

**Files:** creare `W/{build.gradle,module.properties}`, `W/src/test/java/it/unimib/datai/nanofaas/modules/forecastingwasm/WasmEmbeddingTest.java`; creare il probe e le recipe in `docs/experiments/forecasting-wasm-2026-11-06/`.

**Interfaces:** produce prova ripetibile JVM/native e coordinate/versioni compatibili; modulo defaultEnabled=false, requires.strong=forecasting, altri vincoli vuoti, schemaVersion=1.

- [ ] Aggiornare indice e registrare baseline di `:forecasting-api:test` e `:control-plane-modules:forecasting:test`; leggere la spec. Registrare versione plugin e toolchain effettive.
- [ ] Scrivere `WasmEmbeddingTest`: un minuscolo guest binario esporta add(i32,i32); chiamata via Polyglot deve restituire 42 e chiudere Context/Engine. Eseguire prima senza implementazione/dipendenze e constatare il fallimento atteso.
- [ ] Fissare dipendenze Polyglot/GraalWasm compatibili con il JDK/GraalVM del repo; registrare edition/licenza. Non aggiornare globalmente toolchain o plugin per tentativi. Implementare probe, senza allowAllAccess.
- [ ] Eseguire `./gradlew -PcontrolPlaneModules=forecasting,forecasting-wasm :control-plane-modules:forecasting-wasm:test`. Ripetere il probe come vero eseguibile Native Image Linux, registrando comando completo nel report, non solo processAot o un mock.
- [ ] Verificare nel binario add(20,22)=42 e chiusura corretta; rilevare dimensione/RSS e distinguere runtime ottimizzato da fallback interpreter-only. Se non funziona, il gate resta rosso: documentare il limite prima di procedere ai task successivi.
- [ ] Completare detect_changes e commit `Add optional WASM embedding probe`. Tenere il probe nel modulo come regressione.

### Task 2: Holt Rust con ABI stabile e artifact riproducibile

**Files:** creare `W/guest/{Cargo.toml,Cargo.lock,rust-toolchain.toml,.cargo/config.toml,src/lib.rs}`, `W/src/main/resources/wasm/{holt-v1.wasm,holt-v1.manifest.json}`; modificare `W/build.gradle`; creare `W/src/test/resources/holt-vectors.json`.

**Interfaces:** ABI esatta della spec: memory, abi_version, input_ptr, output_ptr, forecast. Produzione artifact senza libc/WASI/import host; memoria massima 16 MiB, niente fast-math.

- [ ] Scrivere test Rust RED per costante, singolo valore, crescita, decrescita con clamp, damping=1, pesi ai limiti, 100000 campioni/4096 passi; invalidi: array vuoto, dimensioni eccedenti, pesi zero/NaN, campioni negativi/non finiti. Errori numerici restituiscono status 2.
- [ ] Copiare l'ordine delle operazioni di `TrendForecastSource.holt` nel core Rust; implementare export C-ABI e buffer statici separati senza esporre riferimenti mutabili globali. Anche il guest valida gli input.
- [ ] Eseguire `cargo test --locked --manifest-path platform/modules/forecasting-wasm/guest/Cargo.toml`; poi build release locked per wasm32-unknown-unknown. Registrare rustc/cargo effettivi e flag nel manifest.
- [ ] Aggiungere `compileWasm` locale e `verifyWasmArtifact`; manifest con SHA256 di sorgenti/config/lock/toolchain e artifact. `processResources` dipende solo dalla verifica, non dalla disponibilità di Rust. Se sorgente o artifact cambia senza rigenerare, la build fallisce esplicitamente.
- [ ] Eseguire `./gradlew -PcontrolPlaneModules=forecasting,forecasting-wasm :control-plane-modules:forecasting-wasm:compileWasm :control-plane-modules:forecasting-wasm:verifyWasmArtifact`; ripetere in directory pulita con stessa toolchain e confrontare SHA256. Provare hash errato e artifact mancante.
- [ ] detect_changes e commit `Add reproducible Rust Holt WASM guest`.

### Task 3: adapter Java e parità con Holt esistente

**Files:** creare `platform/libs/forecasting-api/src/main/java/it/unimib/datai/nanofaas/forecastingapi/HoltForecastAlgorithm.java`, `WP/WasmHoltForecastAlgorithm.java`, `WT/WasmHoltForecastAlgorithmTest.java`; creare `WT/HoltParityTest.java` nel package forecasting per accedere alla formula package-private senza renderla pubblica.

**Interfaces:** `HoltForecastAlgorithm.forecast(double[] rates, int steps, double level, double trend, double damping) -> double[]`; adapter implementa AutoCloseable. Errori di guest con RuntimeException diagnostica; conversione in MISSING appartiene al Task 4.

- [ ] Test RED: vettori Java come oracle, 100 sequenze deterministiche seed=42, confini della spec e verifica input non modificato. Assert tolleranza su ogni elemento e output esattamente steps.
- [ ] Test RED: file assente, export assente, ABI!=1, puntatori fuori memoria/non allineati/sovrapposti, status nonzero, trap, NaN/Infinity, chiamata dopo close e due chiamate contemporanee con input diversi.
- [ ] Implementare adapter: caricare bytes dal classpath; verificare ABI, export, bounds e memoria; copiare f64 little-endian tramite API documentate compatibili con la versione selezionata; eseguire e copiare output staccato. Una sezione serializzata comprende input-call-output; un Context/Engine riusato, chiusura idempotente. Nessun JSON o callback host.
- [ ] Eseguire i test JVM con il vero guest; verificare che chiamate ripetute non accrescano la memoria lineare. Chiudere Context e Engine anche su errori di inizializzazione.
- [ ] detect_changes e commit `Bridge Holt forecasts through GraalWasm`.

### Task 4: integrare la selezione senza duplicare lo storico

**Files:** modificare `F/TrendForecastSource.java`, `F/ForecastingProperties.java`, `F/ForecastingConfiguration.java`, `F/ForecastingRuntimeConfigExtension.java`; creare `WP/ForecastingWasmConfiguration.java`, `W/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`; aggiornare `FT/{TrendForecastSourceTest,ForecastConcurrencyTest,ForecastingConfigurationTest,ForecastingRuntimeConfigExtensionTest,ForecastingRuntimeConfigIntegrationTest}.java`; creare `WT/ForecastingWasmIntegrationTest.java`.

**Interfaces:** overload `TrendForecastSource.forecast(ForecastQuery query, Holt model, HoltForecastAlgorithm algorithm) -> ForecastSnapshot`. Selezione HOLT_WASM usa lo stesso storico e modello Holt già in ForecastSelection. Le altre chiamate conservano i metodi esistenti.

- [ ] Eseguire impact sui simboli toccati e riportare rischi/callers, inclusa la costruzione dell'extension nei test. Non introdurre dipendenze dal modulo forecasting verso l'implementazione WASM.
- [ ] Test RED: HOLT e HOLT_WASM producono lo stesso snapshot salvo provider; storico vuoto/altro nodo/nuova generazione restano MISSING; finestre osservate a zero restano distinguibili; query STALE resta STALE; intervalli parziali e cap 4096 riusano mean e limiti correnti.
- [ ] Implementare il piccolo hook nel ramo Holt di estimate. Mantenere lo snapshot sotto lock e il calcolo fuori lock; Java conserva mean, producedAt, revision e query. Trap/output invalido restituiscono MISSING con provider holt_wasm, rate=null e revision catturata, senza fallback.
- [ ] Aggiungere bean opzionale HoltForecastAlgorithm e autoconfigurazione del modulo con lifecycle close; risolverlo senza dipendenze circolari e senza richiedere ordine implicito delle autoconfigurazioni. Quando forecasting è disabled non creare il runtime.
- [ ] Aggiungere HOLT_WASM al provider e al routing; verificare che ForecastSelection.trendModel continui a selezionare Holt per questo caso. Rendere `available` dell'extension dipendente dalla presenza del bean: assente non pubblicizzato, patch rifiutata senza side effect, configurazione iniziale invalida fallisce con messaggio chiaro.
- [ ] Test RED→GREEN per toggle HOLT↔HOLT_WASM con storico caldo e cambio pesi; usare un algoritmo controllato da latch per provare che record/retain proseguono mentre il calcolo attende. Provare errori senza mutare query/generazione/revisione e senza creare un secondo observer.
- [ ] Eseguire suite forecasting e forecasting-wasm con modulo incluso; ripetere suite forecasting senza modulo e controllare dependency tree del control plane privo di Polyglot/GraalWasm.
- [ ] detect_changes e commit `Expose optional WASM Holt forecasting provider`.

### Task 5: packaging Native Image, misure e documentazione

**Files:** creare `WP/ForecastingWasmRuntimeHints.java` e relativo test; recipe `docs/experiments/forecasting-wasm-2026-11-06/{java-only,jvm-wasm,native-wasm}.yaml`, harness `benchmark.py`, `README.md`; modificare `docs/forecasting.md`, `docs/recipes.md` e `platform/modules/runtime-config/openapi.yaml` se enum/esempi dei provider richiedono aggiornamento.

**Interfaces:** recipe normali con moduli forecasting, runtime-config, build-metadata e opzionalmente forecasting-wasm; risorsa wasm/holt-v1.wasm registrata per Native Image. Harness registra artifact hash, versioni, parametri e dati grezzi.

- [ ] Test RED per risorsa nel binario e bootstrap di un control plane nativo con holt_wasm; nessuna inizializzazione guest durante AOT. Usare il builder Linux container già supportato e un checkout/snapshot pulito senza Cargo installato nel builder.
- [ ] Implementare RuntimeHints minimi e packaging richiesto dal probe; verificare assenza del runtime nelle composizioni senza modulo. Se il plugin non preserva artifact/dipendenze necessari, documentare il difetto e preparare un cambiamento mirato separato invece di introdurre uno schema WASM generico.
- [ ] Smoke JVM/native: attivare una funzione locale, generare arrivi per più finestre e verificare via test client del contesto la previsione; usare API runtime-config esistente per selezione/toggle. Non inventare un endpoint pubblico per leggere forecast. Chiudere il contesto e verificare assenza di thread/risorse guest residui.
- [ ] Eseguire tre composizioni indicate nella spec, stesso host e budget, tre ripetizioni locali. Microbenchmark: history 60/360/4096 e steps 1/60/4096, cold separato da warm; misurare p50/p95 di calcolo e costo con arrivi concorrenti. Scenario sostenuto separato: history=360, steps=60 per 60 s dopo warmup, registrando RSS e throughput. Provare inoltre i massimi una volta per correttezza/limiti, senza inventare un grande benchmark cloud.
- [ ] Provare heap 64 MiB e riportare eventuale OOM/non avvio come risultato, senza aumenti impliciti. Riportare RSS totale e footprint del runtime, build time, immagine/binario e startup. Nessuna affermazione che WASM sia più veloce prima delle misure.
- [ ] Documentare configurazione, ABI, pin, rigenerazione artifact, disponibilità provider, errori e limiti. Riportare decisione motivata: adottabile, solo sperimentale, oppure non adatto al budget NanoFaaS.
- [ ] Eseguire test completi, distinguendo i due WarmEchoAotTest macOS già noti da nuove regressioni; verificare anche build JVM/native senza modulo. detect_changes completo, review finale e commit `Validate and document WASM forecasting on JVM and native`.

## Criteri di completamento

- [ ] Guest Rust vero eseguito su JVM e Native Image Linux con parità numerica verificata.
- [ ] Nessuna regressione su generazioni, finestre, MISSING/STALE, provider esistenti e runtime-config.
- [ ] Artifact riproducibile e verificato, modulo assente realmente privo di runtime WASM.
- [ ] Costi misurati e limite heap 64 MiB esplicitamente provato, anche se il risultato è negativo.
- [ ] Report e decisione di adozione disponibili; nessun merge o push senza istruzione dell'utente.

## Autorevisione del piano

Spec coperta dai task 1–5; ABI e firme coerenti; cinque casi di Review Focus associati a test. La scelta dell'artifact versionato evita Cargo annidato nel builder native. Disponibilità effettiva dei provider e ordine delle autoconfigurazioni sono verifiche obbligatorie. Nessun lavoro di implementazione eseguito durante la stesura.
