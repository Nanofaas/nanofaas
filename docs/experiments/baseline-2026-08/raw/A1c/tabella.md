
scritta in /Users/micheleciavotta/Downloads/mcFaas/docs/experiments/baseline-2026-08/README.md fra i marcatori A1c
| Build | Runs | Throughput (rps) | p50 (ms) | p95 (ms) | p99 (ms) | Shed (%) | Control plane peak (MiB) | Control plane CPU (cores) |
|---|---|---|---|---|---|---|---|---|
| JVM (Java 25, JIT) | 3 | 435.1 ± 0.0 | 3.0 ± 0.5 | 151.1 ± 21.5 | 206.2 ± 30.3 | 20.83 ± 3.31 | 1097.8 ± 18.0 | 0.56 ± 0.02 |
| JVM (serial GC, full tiering) | 3 | 435.1 ± 0.0 | 1.2 ± 0.0 | 4.3 ± 0.4 | 45.9 ± 13.0 | 0.72 ± 0.29 | 1643.3 ± 5.3 | 0.41 ± 0.01 |

Quello che il report di confronto non guarda:

| Build | Servite/s (dispatch) | Vivo a fine cella | CPU strozzata (%) | Heap picco (MB) | Collezioni GC | Pausa GC media (ms) | Tempo in GC (%) | gauge gc_time_fraction | Compilazione (s) | Immagine (MB) |
|---|---|---|---|---|---|---|---|---|---|---|
| JVM (Java 25, JIT) | 225 ± 12 | si | 22.0 ± 0.8 | 972 ± 20 | 1402 ± 32 | 5.5 ± 0.2 | 1.61 ± 0.07 | NaN | 43.4 | 244 |
| JVM (serial GC, full tiering) | 300 ± 1 | si | 4.6 ± 0.4 | 995 ± 15 | 1851 ± 8 | 3.6 ± 0.1 | 1.40 ± 0.03 | NaN | 16.1 | 244 |
