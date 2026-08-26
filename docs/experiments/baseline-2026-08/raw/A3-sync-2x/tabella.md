
scritta in /Users/micheleciavotta/Downloads/mcFaas/docs/experiments/baseline-2026-08/README.md fra i marcatori A3-sync-2x
| Build | Runs | Throughput (rps) | p50 (ms) | p95 (ms) | p99 (ms) | Shed (%) | Control plane peak (MiB) | Control plane CPU (cores) |
|---|---|---|---|---|---|---|---|---|
| JVM (serial GC, full tiering) | 3 | 871.2 ± 0.0 | 1.1 ± 0.0 | 4.6 ± 0.5 | 50.6 ± 10.6 | 1.61 ± 0.03 | 1705.1 ± 16.2 | 0.63 ± 0.05 |

Quello che il report di confronto non guarda:

| Build | Servite/s (dispatch) | Vivo a fine cella | CPU strozzata (%) | Heap picco (MB) | Collezioni GC | Pausa GC media (ms) | Tempo in GC (%) | gauge gc_time_fraction | Compilazione (s) | Immagine (MB) |
|---|---|---|---|---|---|---|---|---|---|---|
| JVM (serial GC, full tiering) | 587 ± 0 | si | 0.5 ± 0.4 | 1325 ± 39 | 741 ± 868 | 34.2 ± 21.8 | 1.78 ± 0.23 | NaN | 40.9 | 244 |
