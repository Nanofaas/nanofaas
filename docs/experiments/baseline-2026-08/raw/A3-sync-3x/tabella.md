
scritta in /Users/micheleciavotta/Downloads/mcFaas/docs/experiments/baseline-2026-08/README.md fra i marcatori A3-sync-3x
| Build | Runs | Throughput (rps) | p50 (ms) | p95 (ms) | p99 (ms) | Shed (%) | Control plane peak (MiB) | Control plane CPU (cores) |
|---|---|---|---|---|---|---|---|---|
| JVM (serial GC, full tiering) | 3 | 1306.3 ± 0.0 | 1.2 ± 0.0 | 58.9 ± 27.3 | 541.4 ± 79.3 | 9.62 ± 0.23 | 1707.3 ± 4.2 | 0.84 ± 0.01 |

Quello che il report di confronto non guarda:

| Build | Servite/s (dispatch) | Vivo a fine cella | CPU strozzata (%) | Heap picco (MB) | Collezioni GC | Pausa GC media (ms) | Tempo in GC (%) | gauge gc_time_fraction | quota async degli arrivi (%) | rifiuti porta sync (%) | rifiuti porta async (%) | coda sync (max) | coda async (max) | replay sync | chiavi idempotenza (max) | Compilazione (s) | Immagine (MB) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| JVM (serial GC, full tiering) | 804 ± 2 | si | 9.3 ± 0.1 | 1375 ± 31 | 231 ± 2 | 52.1 ± 5.1 | 2.48 ± 0.24 | NaN | 0.0 ± 0.0 | 9.62 ± 0.23 | — | 20 ± 0 | — | 0 ± 0 | 0 ± 0 | 32.4 | 244 |
