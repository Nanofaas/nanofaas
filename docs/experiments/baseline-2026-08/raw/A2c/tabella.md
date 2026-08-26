
scritta in /Users/micheleciavotta/Downloads/mcFaas/docs/experiments/baseline-2026-08/README.md fra i marcatori A2c
| Build | Runs | Throughput (rps) | p50 (ms) | p95 (ms) | p99 (ms) | Shed (%) | Control plane peak (MiB) | Control plane CPU (cores) |
|---|---|---|---|---|---|---|---|---|
| JVM (Java 25, JIT) | 3 | 435.1 ± 0.0 | 1.9 ± 0.3 | 9.3 ± 9.8 | 17.2 ± 12.5 | 0.08 ± 0.09 | 867.3 ± 2.1 | 0.67 ± 0.08 |
| JVM (serial GC, full tiering) | 3 | 435.1 ± 0.0 | 1.2 ± 0.2 | 2.3 ± 0.6 | 7.1 ± 1.2 | 0.02 ± 0.00 | 952.0 ± 5.1 | 0.42 ± 0.02 |

Quello che il report di confronto non guarda:

| Build | Servite/s (dispatch) | Vivo a fine cella | CPU strozzata (%) | Heap picco (MB) | Collezioni GC | Pausa GC media (ms) | Tempo in GC (%) | gauge gc_time_fraction | Compilazione (s) | Immagine (MB) |
|---|---|---|---|---|---|---|---|---|---|---|
| JVM (Java 25, JIT) | 302 ± 0 | si | 3.5 ± 3.7 | 663 ± 6 | 849 ± 3 | 5.8 ± 0.4 | 1.03 ± 0.08 | NaN | 31.7 | 244 |
| JVM (serial GC, full tiering) | 302 ± 0 | si | 0.1 ± 0.0 | 614 ± 26 | 975 ± 8 | 4.7 ± 0.2 | 0.96 ± 0.04 | NaN | 14.6 | 244 |
