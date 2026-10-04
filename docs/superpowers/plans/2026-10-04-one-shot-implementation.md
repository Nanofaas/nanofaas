# One-shot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** consegnare prima NanoFaaS con one-shot verificabile autonomamente, poi NanoLab con calibrazione ed esperimenti Azure riproducibili.

**Architecture:** due fasi strettamente ordinate, con piani separati. NanoFaaS possiede protocollo, solver, forecast, attuazione e contratti pubblici; NanoLab consuma tali contratti tramite task Sonata. Nessun componente necessario a eseguire one-shot è rinviato a NanoLab.

**Tech Stack:** Java 25/Spring Boot/Reactor/GraalVM, SDK Rust, JUnit e test locali con container; Python >=3.12/NanoLab/Sonata e Azure nella seconda fase.

**Spec:** [One-shot decentralizzato per NanoFaaS](../specs/2026-10-04-one-shot-offload-design.md).

## Global Constraints

- Prima versione senza ricerca locale PG e senza aste gerarchiche.
- Riferimento DFaaSOptimizer: branch `feat/uv-migration-and-extended-tests`, commit `71899f7`; nessuna compatibilità richiesta con il protocollo DFaaS.
- Contratti P2P e previsioni in `p2p-api` e `forecasting-api`, non in `control-plane-spi`.
- Una esecuzione fisica per replica; one-shot unico proprietario delle repliche gestite.
- `T_asta << T`: periodo scelto dopo misure dell'asta distribuita, non fissato a un minuto.
- Cloud raggiungibile e sufficientemente dimensionato; inoltro terminale one-hop.
- Nessuna modifica a NanoLab, Sonata o infrastruttura Azure durante la fase A.

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
| [B — NanoLab](2026-10-04-one-shot-nanolab.md) | `nanolab` | Workflow Sonata, provisioning Azure, calibrazione, qualificazione dei tempi e campagne |

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

`contratti/client → infrastruttura → calibrazione servizio → qualificazione asta → periodo e anticipo fissati → confronti → rapporto e cleanup`.

Le prove Azure possono rivelare bug: in tal caso si corregge NanoFaaS con una regressione locale, si produce una nuova versione e si invalidano gli artefatti influenzati. Questo è un ciclo di correzione, non il rinvio pianificato di funzionalità NanoFaaS alla fase B.

## Stato e metodo di esecuzione

Questo documento è un piano da revisionare, non l'avvio dell'implementazione. I checkbox sono inizialmente tutti aperti. Per l'esecuzione si consiglia la modalità nativa, con verifica al termine di ogni task e revisione finale: i contratti tra task sono strettamente collegati. La scelta del metodo resta all'utente prima dell'avvio.
