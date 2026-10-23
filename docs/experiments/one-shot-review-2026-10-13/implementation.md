# Implementazione e chiusura della revisione — 13 ottobre 2026

Il rapporto iniziale resta storico. Le correzioni sono state applicate native nel
worktree NanoFaaS e nel checkout NanoLab indicati dal piano. Nessuna campagna reale
avviata, nessuna VM acquisita, nessun push o merge. Il runtime è nel commit locale
`f3222af4`; NanoLab è nel commit locale `6353fb2`; la distribuzione costruita tramite `assembleRecipe` usa esattamente
quel commit pulito. I dati storici non vengono riscritti.

## Disposizione dei finding

| Finding | Correzione / disposizione | Regressione principale |
|---|---|---|
| A01 | ConfigureOracle usa lead congelato90 / prep10; payload effettivo validato | test_observation_workflow.py |
| A02 | DEGRADED con piano sicuro prosegue; incompletezza separata, outbound autorizzato, inbound inutilizzato osservato | ReadinessObservationTest, LogicalReadinessTest, test_readiness_observations.py |
| A03 | I/O heartbeat/status/stdout fallito non impedisce interruzione e compensation; prima causa conservata | test_supervision.py |
| A04 | vmTelemetry nel manifest; grafici attivati anche con raw legacy/parziale | test_report.py, test_observation_workflow.py |
| A05 | Watcher dal lifetime di CP/collector/Loki; inventario Docker separato dagli errori cgroup | test_resources.py, test_supervision.py, test_vm_resources.py |
| A06 | Ogni risposta viene conservata anche con JSONnull/nonoggetto e statuserrore; checksumu64 esatto | JS reale in k6_harness.mjs / test_capacity_generator.py |
| A07 | Una sola scadenza monotona e allowance180; ORACLE4230; nono intervallo coperto anche con k6 già terminato | test_operational_limits.py, test_monotonic_resources.py |
| A08 | Freeze di package/script/ricette, inventario completo source incluse aggiunte untracked, byte/versioni engine/provider | test_observation_builder.py, test_first_observation.py |
| A09 | Query parziale dichiarata; store Loki fermato/copiato e stato collector esportato prima della cancellazione; export fallito trattiene sole-copy storage | test_logging.py, test_observation_final_regressions.py |
| A10 | Builder e task reali su engine Sonata: nove epoche, DEG, guasto, SIGTERM, dipendenza rimossa | test_observation_workflow.py |
| A11 | Lateness osservazionale e parametri che cambiano risorse/contesa invariati; tutti101 budget classificati | dispositions.md, test_operational_limits.py |
| F01 (review finale) | Nessuna lettura diagnostica sincrona sul driver epoche; timer mancanti null, raw nel collector | test_slow_diagnostics_cannot_consume_next_epoch_lead RED→GREEN |
| F02 | Errori di scrittura dei raw propagati, non convertiti in misure mancanti | test_physical_collector_propagates_artifact_write_failure RED→GREEN |
| F03 | Raccolta cancellabile, batch16, scadenza passata/finale30 dal drain; dopo guasto niente nuova raccolta di rete | test_collection_stops_before_retry_or_queued_proof_work RED→GREEN |
| F04 | Failed export: servizi fermati, volumi/VM contenenti unica copia trattenuti, cleanup incompleto e recovery espliciti | test_failed_backend_copy_retains_sole_copy_volume RED→GREEN; test_sonata_retains_only_vm_containing_unexported_raw |
| F05 | Errori secondari delle receipt/eventi diventano note; prima causa rimane l’esito | test_entry_reporting_failure_keeps_original_error RED→GREEN |

Altri confini corretti durante integrazione: file del generatore chiusi anche
quando l’acquisizione fallisce a metà; assenza dei primi campioni osservata se
sampler vivo; bootstrap proof120 anziché cap30; pagine eventi completate salvate
prima dell’errore della pagina successiva; report finale allineato a result.json;
righe raw parziali segnalate senza perdere quelle precedenti. Le regressioni sono
nella suite one-shot. Non attribuiamo questi finding alla causa del run storico04.

## Evidenza finale

- Suite Java offload:158 test, zero failure/error/skip (`:control-plane-modules:offload:test`). Sorgenti invariati dopo quel run; nuova distribuzione costruita con successo.
- Suite one-shot:859 test passati, zero saltati, con distribuzione aggiornata e riferimento Python reale; JUnit `evidence/implementation-one-shot-tests.xml`.
- Suite completa NanoLab:4808 passati,3 saltati; i tre casi sono combinazioni tipo-artefatto non applicabili nei test CLI, non prove della campagna saltate. JUnit `evidence/implementation-all-tests.xml`.
- Ruff: nessun errore su codice/test one-shot e tre script; basedpyright dal package:0 errori/0 warning.
- SDK Rust:110 test passati eseguiti in serie; workload Rust:4 test passati. Un test di capture logging terminale ha fallito con esecuzione parallela ma passa nella suite seriale: interferenza da investigare, nessuna regressione del prodotto dimostrata e nessun cambiamento Rust applicato.
- Una revisione indipendente, read-only:5 Important,0 Critical/Minor; tutti i cinque riprodotti e corretti nel passaggio finale, con regressioni. Nessuna seconda revisione dopo i fix.

## DAG Sonata e fingerprint

Il DAG compilato del freeze ha141 unità:69 acquire,69 release,3 consumer.
Inventario per unità e Resource.requires in `evidence/compiled-workflow.json`.
Il primo consumer contiene preflight → warm instances → observer precheck →
freeze schedule → ConfigureOracle; segue RunOneShotLoadTask (warmup+nove epoche)
e ObservationReport. VM/operator/bootstrap/immagini/logging/CP/probe/funzioni
sono risorse dipendenti. Telemetry precede generator, collector dipende da
producer/topology, clock è dichiarato. Release e compensation sono dell’engine.
Copia finale e verifica avvengono nel supervisore dopo quiescenza di tutti gli
scrittori. Il test negativo di dipendenza non è aggirato dal wrapper.

Prepare e verifica fingerprint sono operazioni offline del comando; non sono
provisioning mascherato. Per compilare il DAG finale offline abbiamo simulato
solo inventario host/VM: il preflight fisico reale resta nel futuro run. I test
eseguono davvero Workflow.run; non certificano il comportamento su Multipass.

Freeze05: `/home/michele/Documenti/nanofaas/.superpowers/archives/oneshot-readiness-20261013/oracle-freeze-05`.
Fingerprint SHA256: `f766a97752bfbcacb6a0924c8a77b9293e06e2f0e183808f510644c1974aff81`.
Verificati453 file congelati,6921 file source e99 file implementazione dipendenze.
CPU/RAM, pool e trace identici agli input tarati;8 round e RAMsolver64MiB invariati.
Freeze04 storico:451 file verificati,0 mismatch. Dettagli in
`evidence/freeze-verification.json`. Nessun run reale creato dal freeze.

## Copertura e limiti

[Inventario per file](coverage.md) e [hash/ambito JSON](coverage.json) delimitano
106 file sperimentali e contratti raggiunti. Percorsi qualificati/pilot separati
sono conservati e non usati per escludere osservazioni. L’audit include staging,
provisioning/provider/compensation, riferimento Python usato dalla taratura,
SDK/workload e gli asset; non è una certificazione di tutto NanoFaaS o dell’intero
software esterno. Non cancelliamo legacy sulla base di callers non risolti.

L’overhead aggiornato viene misurato dal precheck reale prima del futuro warmup,
senza soglia5% osservazionale. Non è stato misurato fisicamente in questo task,
che esclude l’avvio di campagne. Se l’export non può preservare l’unica copia,
le risorse trattenute richiedono recupero/rimozione manuale: è un cleanup
incompleto dichiarato, non un successo. Il modello storico dei tempi resta
esplicitamente non qualificato; le differenze saranno osservazioni del run.


[Ledger di esecuzione e tutte le ruling](execution-ledger.md). Analisi completa precommit NanoLab:416 simboli,51 file,79 processi,CRITICAL; output strutturato non parziale in `evidence/nanolab-graph-changes.json`. FTS/BM25 GitNexus non disponibile; non usata come prova di assenza di callers. Nessun Minor rinviato nella review finale.
