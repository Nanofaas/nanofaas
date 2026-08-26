
scritta in /Users/micheleciavotta/Downloads/mcFaas/docs/experiments/baseline-2026-08/README.md fra i marcatori A2d
| Build | Runs | Throughput (rps) | p50 (ms) | p95 (ms) | p99 (ms) | Shed (%) | Control plane peak (MiB) | Control plane CPU (cores) |
|---|---|---|---|---|---|---|---|---|
| JVM (Java 25, JIT) | 3 | 421.8 ± 7.0 | 1.0 ± 0.6 | 3.5 ± 1.8 | 58.5 ± 76.3 | 50.08 ± 35.44 | 498.0 ± 20.2 | 0.41 ± 0.16 |
| JVM (serial GC, full tiering) | 3 | 416.9 ± 0.2 | 0.4 ± 0.0 | 1.9 ± 0.1 | 57.3 ± 5.5 | 79.34 ± 0.02 | 455.7 ± 12.9 | 0.25 ± 0.02 |

Quello che il report di confronto non guarda:

| Build | Servite/s (dispatch) | Vivo a fine cella | CPU strozzata (%) | Heap picco (MB) | Collezioni GC | Pausa GC media (ms) | Tempo in GC (%) | gauge gc_time_fraction | Compilazione (s) | Immagine (MB) |
|---|---|---|---|---|---|---|---|---|---|---|
| JVM (Java 25, JIT) | 235 ± 69 | si | 1.4 ± 1.2 | 304 ± 53 | 510 ± 84 | 5.5 ± 2.6 | 1.06 ± 0.20 | NaN | 15.2 | nan |
| JVM (serial GC, full tiering) | 160 ± 3 | si | 2.8 ± 0.1 | 255 ± 7 | 419 ± 2 | 3.2 ± 0.1 | 0.80 ± 0.02 | NaN | 11.9 | nan |
