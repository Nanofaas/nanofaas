# Wakeup localization Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Localizzare il percorso release-signal-scheduler senza correlare eventi fra thread.

**Architecture:** Riutilizzare `function_scheduler_wakeup_delay`, che copre il segnale accettato fino alla `poll()` dello scheduler. Aggiungere solo il timer sincrono dell'accodamento del segnale accettato; la differenza resta il wake/scheduling del thread.

**Tech Stack:** Java 25, Micrometer, JUnit 5, NanoLab Azure comparison.

---

### Task 1: Misura e test

**Files:**
- Modify: `platform/modules/async-queue/src/main/java/it/unimib/datai/nanofaas/modules/asyncqueue/QueueManager.java`
- Modify: `platform/modules/async-queue/src/main/java/it/unimib/datai/nanofaas/modules/asyncqueue/Scheduler.java`
- Test: `platform/modules/async-queue/src/test/java/it/unimib/datai/nanofaas/modules/asyncqueue/AsyncQueueDiagnosticsTest.java`

1. Scrivere un test fallente che richieda un conteggio del timer per un segnale accettato.
2. Eseguire il test e osservarne il fallimento.
3. Aggiungere il solo timer `function_scheduler_signal_enqueue_duration` attorno a `activeFunctions.add` quando `enqueuedFunctions.add` riesce.
4. Rieseguire test mirato e suite del modulo.

### Task 2: Esperimento e archiviazione

1. Eseguire una cella Azure `native-o3-g1`, 2/20, una ripetizione, sotto `caffeinate -dimsu`.
2. Eseguire sempre il teardown NanoLab e verificare l'inventario Azure vuoto.
3. Copiare manifest e tre raw JSON nel registro, aggiornare checksum e documentare il verdetto.

## Risultato

Completato con mcFaas `0d924d08` e NanoLab `c77cfba`. Nel run
`azure-dispatch-wakeup-localization-c2`, al `peak900`, l'inserimento in
`activeFunctions` costa 61,5 µs contro 995,2 µs dal segnale all'attivazione
dello scheduler. L'ipotesi che l'`add` sia il collo dominante è invalidata; il
residuo di 933,7 µs localizza il costo nel percorso esterno all'`add` fino al
poll/bookkeeping dello scheduler, senza ancora attribuirlo a una singola
istruzione. Raw e protocollo sono conservati nel registro esperimenti.
