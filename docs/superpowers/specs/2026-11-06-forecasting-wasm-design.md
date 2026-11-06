# Forecasting WASM — design proposto

Stato: proposta da revisionare; nessuna implementazione autorizzata da questo documento.
Base osservata: `aba820d4`. Il lavoro precedente sul dispatch diretto è già in main.

## Obiettivo

Aggiungere un modulo opzionale `forecasting-wasm` che esegua Holt scritto in Rust e compilato in WebAssembly, utilizzabile sia su JVM sia nel control plane Native Image. Confrontare la stessa formula con l'implementazione Java prima di investire in supporto multilinguaggio generico.

## Decisioni

- Il modulo resta un normale modulo NanoFaaS con adattatore Java, autoconfigurazione Spring e dipendenze Polyglot/GraalWasm.
- Il nuovo provider si chiama `HOLT_WASM` nella configurazione Java e `holt_wasm` nell'API runtime-config, coerentemente con `Provider.id()`.
- `forecasting-wasm` richiede `forecasting`; dipende dal contratto `forecasting-api`. Il modulo forecasting non dipende da GraalWasm.
- Il core SPI, scheduler, dispatch, observer e lifecycle delle generazioni restano invariati. Lo storico resta nel `TrendForecastSource` esistente; Java conserva aggregazione dell'intervallo, revisioni, validità e stati MISSING/STALE.
- Il calcolo WASM avviene sullo snapshot staccato dello storico, fuori dal monitor di osservazione. Non si invoca WASM per ogni arrivo.
- ABI numerica esplicita e versionata, senza JSON, callback verso Spring, filesystem guest, rete guest o WASI. Primo guest: codice fidato incluso nel pacchetto, non plugin caricati dagli utenti.
- Engine e Context vengono creati a runtime, mai durante Spring AOT/native-image build. Un solo Context per adattatore, accesso serializzato, chiusura alla distruzione del bean. Nessun pool o hot reload iniziale.
- Modulo non selezionato: nessuna dipendenza Polyglot/GraalWasm nel control plane. Selezionato ma forecasting disabilitato: nessun Engine/Context avviato.
- Startup fallisce chiaramente se `holt_wasm` è richiesto ma il modulo manca. L'API runtime-config pubblicizza solo provider disponibili e rifiuta modifiche verso quelli assenti senza alterare lo stato.
- ABI invalida o artifact corrotto: fallimento di startup. Errore di esecuzione/trap o output non valido: previsione MISSING con identità/revisione coerenti e log diagnostico, senza fallback Java silenzioso e senza convertire l'errore in traffico zero.

## Contratto

Nuova interfaccia `HoltForecastAlgorithm` in `forecasting-api`:

`double[] forecast(double[] rates, int steps, double level, double trend, double damping)`

Input: 1..100000 campioni finiti e non negativi, 1..4096 passi, tre pesi finiti in (0,1]. Output: esattamente `steps` valori finiti e non negativi. Input immutato. Lo storico vuoto è gestito dal chiamante come MISSING, prima di invocare l'algoritmo.

ABI WASM v1: export `memory`, `abi_version() -> i32` (=1), `input_ptr() -> i32`, `output_ptr() -> i32`, `forecast(n:i32, steps:i32, level:f64, trend:f64, damping:f64) -> i32`. Array f64 little-endian in memoria lineare. Buffer statici separati da 100000 e 4096 elementi; indirizzi allineati e intervalli verificati dal lato Java prima della copia. Codici: 0 successo, 1 input invalido, 2 risultato numerico non finito. Il guest ricontrolla limiti e valori. Nessuna allocazione guest proporzionale alle chiamate; memoria massima WASM proposta 16 MiB, da verificare nel primo prototipo, distinta dall'heap totale del runtime.

La formula replica esattamente `TrendForecastSource.holt`: level iniziale=rates[0], slope iniziale=0, aggiornamento sequenziale, trend smorzato, clamp a zero. Nessun fast-math. Tolleranza di parità: `abs(a-b) <= 1e-9 * max(1, abs(a))` per input di test non in overflow.

## Build e distribuzione

Nessuna modifica iniziale al plugin Gradle centrale. Il build.gradle del modulo dichiara le dipendenze e i task locali di rigenerazione/verifica WASM. Prima si verifica la compatibilità fra il GraalVM fissato nel repository e una versione esplicitamente fissata di Polyglot/GraalWasm: non assumere che i numeri di versione dei diversi prodotti coincidano.

Per non introdurre Cargo o Docker annidato nel builder native, il piccolo artifact `.wasm` viene inizialmente versionato in `src/main/resources/wasm/`, insieme a un manifest con hash del sorgente, toolchain e artifact. Un task locale `compileWasm` lo rigenera con Rust fissato, `wasm32-unknown-unknown` e Cargo.lock; `verifyWasmArtifact` verifica manifest e hash prima di processResources. CI/prova di riproducibilità ricompila e confronta l'artifact. Questa scelta limita il primo esperimento a un guest incluso alla build.

L'inclusione del file nelle risorse Native Image deve essere esplicita con RuntimeHints e verificata nel binario reale. Il packaging deve preservare anche i service provider e le risorse GraalWasm; la documentazione sconsiglia di appiattire queste librerie in un uber-JAR per Native Image.

Solo se il prototipo dimostra un limite del plugin attuale si propone un'estensione separata e concreta per compilazione/distribuzione WASM. Non si aggiunge preventivamente un linguaggio o un nuovo schema recipe.

## Gate di fattibilità e misure

Prima dell'integrazione col forecasting: lo stesso guest deve funzionare con JVM e con Native Image Linux usando le toolchain del repository. Se fallisce, registrare il motivo e risolvere quel limite prima delle altre attività.

Al termine confrontare tre composizioni: forecasting Java senza runtime WASM; modulo incluso e provider Java; modulo incluso e provider WASM. Misurare dimensione immagine/binario, tempo di build, startup, RSS a riposo e sotto carico, latenza cold/warm del calcolo e degli arrivi concorrenti. Provare il budget già usato di heap 64 MiB e misurare RSS separatamente. Non aumentarlo silenziosamente e non equiparare heap e memoria totale. Nessun obiettivo percentuale di performance è stato concordato: riportare delta e decidere l'adozione sui dati.

La validazione iniziale è locale: nessuna nuova campagna Spot, nessun merge/push automatico. Non include moduli arbitrari non fidati, isolamento forte, hot reload, FFI, Python, modifica dell'algoritmo di offloading o supporto WASM generico nelle recipe.

## Riferimenti

- `platform/modules/forecasting/.../TrendForecastSource.java`, `WindowedForecastSource.java`, `ForecastingConfiguration.java`, `ForecastingRuntimeConfigExtension.java`.
- `platform/libs/forecasting-api/.../ForecastSource.java`, `ForecastQuery.java`, `ForecastSnapshot.java`.
- https://www.graalvm.org/jdk25/reference-manual/embed-languages/#build-native-executables-from-polyglot-applications
- https://www.graalvm.org/webassembly/

L'indice GitNexus consultato era arretrato e la query parziale: la progettazione è stata corroborata leggendo i file attuali. Prima di modificare codice, aggiornare l'indice ed eseguire impact sui simboli interessati.
