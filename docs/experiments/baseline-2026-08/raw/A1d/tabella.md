
scritta in /Users/micheleciavotta/Downloads/mcFaas/docs/experiments/baseline-2026-08/README.md fra i marcatori A1d
| Build | Runs | Throughput (rps) | p50 (ms) | p95 (ms) | p99 (ms) | Shed (%) | Control plane peak (MiB) | Control plane CPU (cores) |
|---|---|---|---|---|---|---|---|---|
| JVM (Java 25, JIT) | 3 | 435.1 ± 0.0 | 1.8 ± 0.1 | 6.1 ± 4.3 | 16.6 ± 14.6 | 0.33 ± 0.06 | 1571.8 ± 1.2 | 0.65 ± 0.02 |
| JVM (serial GC, full tiering) | 3 | 435.1 ± 0.0 | 1.1 ± 0.1 | 2.0 ± 0.5 | 5.8 ± 1.2 | 0.23 ± 0.15 | 1649.4 ± 10.2 | 0.37 ± 0.07 |

Quello che il report di confronto non guarda:

| Build | Servite/s (dispatch) | Vivo a fine cella | CPU strozzata (%) | Heap picco (MB) | Collezioni GC | Pausa GC media (ms) | Tempo in GC (%) | gauge gc_time_fraction | Compilazione (s) | Immagine (MB) |
|---|---|---|---|---|---|---|---|---|---|---|
| JVM (Java 25, JIT) | 301 ± 0 | si | 2.2 ± 2.6 | 1004 ± 16 | 1546 ± 10 | 4.3 ± 0.1 | 1.39 ± 0.02 | NaN | 43.0 | 244 |
| JVM (serial GC, full tiering) | 301 ± 0 | si | 0.1 ± 0.1 | 1103 ± 147 | 1270 ± 890 | 13.0 ± 14.5 | 1.06 ± 0.39 | NaN | 15.4 | 244 |
