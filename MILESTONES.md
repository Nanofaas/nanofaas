# Progetto nanoFaaS - Milestones & Future Improvements

## Completate
- [x] Java SDK per lo sviluppo di funzioni.
- [x] Python SDK con supporto FastAPI e contextvars.
- [x] Porting degli esempi `word-stats` e `json-transform` in Python.
- [x] Suite di test integrata per l'SDK Python.

## In Corso
- [ ] Pipeline GitOps per test e pubblicazione su GHCR.

## Debito Tecnico e Miglioramenti Futuri
- [ ] **Ottimizzazione Packaging Funzioni**: Attualmente ogni container ingloba l'intero SDK/Runtime. Valutare il passaggio a un modello con Sidecar o l'uso di layer Docker condivisi per ridurre la ridondanza e il peso delle immagini.
- [ ] Supporto per linguaggi aggiuntivi (Node.js, Go).
- [ ] Implementazione di un sistema di AuthN/AuthZ per il Control Plane.
- [ ] Miglioramento della gestione dei callback con un sistema di retry più robusto e persistente.