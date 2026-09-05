
scritta in /home/michele/Documenti/nanofaas/docs/experiments/overload-path-2026-09/README.md fra i marcatori A-mem512
| Build | Runs | Throughput (rps) | p50 (ms) | p95 (ms) | p99 (ms) | Shed (%) | Control plane peak (MiB) | Control plane CPU (cores) |
|---|---|---|---|---|---|---|---|---|
| JVM (serial GC, full tiering) | 3 | 411.8 ± 11.0 | 1.2 ± 0.1 | 4.6 ± 0.8 | 112.9 ± 16.5 | 2.27 ± 0.60 | 495.5 ± 5.9 | 0.53 ± 0.03 |

Quello che il report di confronto non guarda:

| Build | Servite/s (dispatch) | Vivo a fine cella | CPU strozzata (%) | Heap picco (MB) | Collezioni GC | Pausa GC media (ms) | Tempo in GC (%) | gauge gc_time_fraction | quota async degli arrivi (%) | rifiuti porta sync (%) | rifiuti porta async (%) | coda sync (max) | coda async (max) | replay sync | chiavi idempotenza (max) | Compilazione (s) | Immagine (MB) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| JVM (serial GC, full tiering) | 143 ± 3 | NO: 3/3 | 5.5 ± 0.7 | 262 ± 6 | 998 ± 14 | 3.2 ± 0.3 | 0.66 ± 0.04 | ok | 0.0 ± 0.0 | 3.32 ± 0.35 | — | 40 ± 0 | — | 0 ± 0 | 0 ± 0 | 45.1 | 244 |
