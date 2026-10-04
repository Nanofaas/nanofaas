# One-shot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** consegnare prima NanoFaaS con one-shot verificabile autonomamente, poi sviluppare e verificare i workflow NanoLab su Multipass; lasciare gli esperimenti finali Azure a un lavoro successivo.

**Architecture:** due fasi di implementazione strettamente ordinate, con piani separati, seguite in futuro da una fase sperimentale distinta. NanoFaaS possiede protocollo, solver, forecast, attuazione e contratti pubblici; NanoLab consuma tali contratti tramite task Sonata e li esercita prima su VM locali Multipass. Nessun componente necessario a eseguire one-shot è rinviato a NanoLab.

**Tech Stack:** Java 25/Spring Boot/Reactor/GraalVM, SDK Rust, JUnit e test locali con container; Python >=3.12/NanoLab/Sonata e Multipass nella seconda fase. Azure riguarda il lavoro sperimentale futuro.

**Spec:** [One-shot decentralizzato per NanoFaaS](../specs/2026-10-04-one-shot-offload-design.md).

## Global Constraints

- Prima versione senza ricerca locale PG e senza aste gerarchiche.
- Riferimento DFaaSOptimizer: branch `feat/uv-migration-and-extended-tests`, commit `71899f7`; nessuna compatibilità richiesta con il protocollo DFaaS.
- Contratti P2P e previsioni in `p2p-api` e `forecasting-api`, non in `control-plane-spi`.
- Una esecuzione fisica per replica; one-shot unico proprietario delle repliche gestite.
- `T_asta << T`: periodo scelto dopo misure dell'asta distribuita, non fissato a un minuto.
- Cloud raggiungibile e sufficientemente dimensionato; inoltro terminale one-hop.
- Nessuna modifica a NanoLab, Sonata o infrastruttura Azure durante la fase A.
- Fase B verificata end-to-end su Multipass, senza account Azure. Provisioning ed esperimenti finali Azure non fanno parte del completamento di A o B.

## Review Focus

| Condizione | Comportamento atteso | Task proprietario |
| --- | --- | --- |
| Forecast e funzione con generazioni diverse | Nessuna applicazione alla nuova funzione | A3, A4, A10 |
| Timeout HTTP con handler ancora attivo | Slot fisico e memoria restano protetti | A7, A8 |
| Conferma persa o duplicata | Nessuna doppia vendita, nessun invio non confermato | A6, A9 |
| Profilo sintetico o incompatibile in una campagna | Rifiuto, non conversione implicita in calibrazione | A1, B3, B5 |
| Asta troncata che sembra veloce | Campione censurato e run non qualificato | A11, B4 |

## Ordine e confine dei repository

| Fase | Repository modificato | Risultato |
| --- | --- | --- |
| [A — NanoFaaS](2026-10-04-one-shot-nanofaas.md) | `nanofaas` | Runtime completo, API documentate, immagini costruibili, test locali e contratti per NanoLab |
| [B — NanoLab su Multipass](2026-10-04-one-shot-nanolab.md) | `nanolab` | Workflow Sonata completi, provisioning locale, prove di calibrazione, qualificazione temporale e confronti di verifica |
| C — Esperimenti finali, lavoro futuro | Da definire nel lavoro sperimentale | Configurazione Azure, nuova calibrazione sul target, qualificazione temporale e campagne scientifiche |

DFaaSOptimizer è un riferimento read-only per esportare fixture: non è una dipendenza runtime. Sonata viene usato tramite API esistenti; non si pianifica una sua modifica. Un limite reale di Sonata scoperto in fase B deve diventare un intervento separato motivato, senza anticipare lavoro NanoLab nella fase A.

I due piani sono salvati qui per mantenere insieme decisioni e passaggio di consegne. I percorsi nel piano B sono relativi a NanoLab, non a NanoFaaS. Durante questa stesura NanoLab è stato soltanto consultato.

## Gate A → B

- [ ] A1–A14 completati e relativi test superati, con risultati e limitazioni registrati.
- [ ] Offload ordinario e profilo senza moduli opzionali ancora funzionanti.
- [ ] Asta reale via P2P, forecasting, routing, scaling e one-hop verificati insieme su più nodi locali.
- [ ] Schemi v1, OpenAPI, esempi, fixture, funzione Rust e immagini JVM/native disponibili a un commit NanoFaaS fissato.
- [ ] Nessuna dipendenza da NanoLab per eseguire test o usare one-shot; nessuna credenziale Azure necessaria per il gate.
- [ ] Dossier `docs/testing/one-shot-phase-a.md` distingue prove funzionali completate da misure Azure ancora da fare.
- [ ] Solo a questo punto iniziare B1. La fase A non è dichiarata incompleta perché manca la calibrazione Azure; la campagna scientifica non è dichiarata valida grazie ai soli test A.

## Sequenza della fase B

`contratti/client → VM Multipass → prova della calibrazione locale → prova della qualificazione dell'asta → confronti locali di verifica → rapporto e cleanup`.

In fase B si esercitano davvero tutti i workflow su VM: non basta un dry-run o un fake provider. Le misure e i profili sono reali ma validi solo per quell'ambiente locale, con host condiviso dichiarato; non qualificano Azure e non fissano il periodo degli esperimenti finali.

## Gate B → lavoro sperimentale futuro

- [ ] Workflow di calibrazione, qualificazione e confronto completati su Multipass, comprese prove di errore, raccolta evidenze e cleanup.
- [ ] Artefatti con provider, fingerprint dell'ambiente e finalità `workflow-validation`; nessun riuso implicito di profili locali su Azure.
- [ ] Dossier con comandi, dimensioni della topologia locale, risorse dell'host e limiti osservati; nessuna richiesta di credenziali Azure per completare B.

La fase C richiederà un piano sperimentale separato: dimensionamento e configurazione Azure, verifica del provider sul target, calibrazione del servizio su Azure, qualificazione dell'asta e scelta di `T`, poi campagne finali. Non viene avviata automaticamente dopo B e non è descritta qui come lavoro già implementato o verificato.

Le prove locali o i futuri esperimenti possono rivelare bug: si corregge NanoFaaS con una regressione locale, si produce una nuova versione e si invalidano gli artefatti influenzati. Questo è un ciclo di correzione, non il rinvio pianificato di funzionalità NanoFaaS alla fase B.

## Stato e metodo di esecuzione

Questo documento è un piano da revisionare, non l'avvio dell'implementazione. I checkbox sono inizialmente tutti aperti. Per l'esecuzione si consiglia la modalità nativa, con verifica al termine di ogni task e revisione finale: i contratti tra task sono strettamente collegati. La scelta del metodo resta all'utente prima dell'avvio.
