
scritta in /home/michele/Documenti/nanofaas/docs/experiments/overload-path-2026-09/README.md fra i marcatori A-mem1024
| Build | Runs | Throughput (rps) | p50 (ms) | p95 (ms) | p99 (ms) | Shed (%) | Control plane peak (MiB) | Control plane CPU (cores) |
|---|---|---|---|---|---|---|---|---|
| JVM (serial GC, full tiering) | 3 | 435.1 ± 0.0 | 1.1 ± 0.0 | 1.8 ± 0.2 | 4.1 ± 0.2 | 0.00 ± 0.01 | 871.3 ± 123.6 | 0.37 ± 0.06 |

Quello che il report di confronto non guarda:

| Build | Servite/s (dispatch) | Vivo a fine cella | CPU strozzata (%) | Heap picco (MB) | Collezioni GC | Pausa GC media (ms) | Tempo in GC (%) | gauge gc_time_fraction | quota async degli arrivi (%) | rifiuti porta sync (%) | rifiuti porta async (%) | coda sync (max) | coda async (max) | replay sync | chiavi idempotenza (max) | Compilazione (s) | Immagine (MB) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| JVM (serial GC, full tiering) | 302 ± 0 | si | 0.0 ± 0.1 | 434 ± 110 | 838 ± 951 | 5.4 ± 2.5 | 0.56 ± 0.41 | ok | 0.0 ± 0.0 | 0.00 ± 0.01 | — | 4 ± 5 | — | 0 ± 0 | 0 ± 0 | 43.7 | 244 |
