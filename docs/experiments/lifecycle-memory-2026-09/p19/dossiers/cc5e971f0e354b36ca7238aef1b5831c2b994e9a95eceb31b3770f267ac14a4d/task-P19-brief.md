### P19 — Congelare una baseline corretta prima dei movimenti strutturali

**Dipendenze:** P00–P18 e P20a completati; P20b resta successivo a questo gate.

1. Esegui i test di regressione e i profili brevi della sezione 8. Registra il
   commit/build esatto e un artefatto identificabile, separato dalla baseline
   difettosa e dalla futura versione con SPI.
2. Misura throughput utile, p50/p95/p99, allocazioni per successo, heap post-GC,
   conteggi live/key/outcome e tutte le popolazioni introdotte nei task precedenti.
3. Per il confronto prestazionale usa almeno tre ripetizioni alternate delle
   revisioni, stesso ambiente e stesso lavoro offerto/ammesso. Escludi warm-up
   e checkpoint GC dalla finestra usata per la latenza ordinaria.
4. Se compare un problema di correttezza o un nuovo retainer, torna al task
   responsabile. Una baseline che contiene un bug noto non è il controllo
   adatto a provare la neutralità di un semplice spostamento di moduli.

**Accettazione:** dossier baseline riutilizzabile da P23; tutti gli R1–R8 hanno
evidenza green e i rischi aggiuntivi hanno fix oppure decisione misurata ammessa
dal relativo task. Le metriche di ammissione distinguono rifiuti da lavoro utile.

