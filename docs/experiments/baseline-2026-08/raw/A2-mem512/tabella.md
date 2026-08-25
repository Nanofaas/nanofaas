
scritta in /Users/micheleciavotta/Downloads/mcFaas/docs/experiments/baseline-2026-08/README.md fra i marcatori A2-512
| Build | Runs | Throughput (rps) | p50 (ms) | p95 (ms) | p99 (ms) | Shed (%) | Control plane peak (MiB) | Control plane CPU (cores) |
|---|---|---|---|---|---|---|---|---|
| JVM (Java 25, JIT) | 3 | 418.4 ± 12.0 | 2.1 ± 0.5 | 3.6 ± 0.2 | 36.8 ± 23.7 | 56.32 ± 23.69 | 496.9 ± 22.0 | 0.38 ± 0.10 |
| Native, -Os, serial GC | 3 | 435.1 ± 0.0 | 2.7 ± 0.0 | 194.0 ± 55.8 | 397.7 ± 34.8 | 8.83 ± 2.41 | 349.4 ± 112.4 | 0.59 ± 0.01 |
| Native, -O3, serial GC | 3 | 435.1 ± 0.0 | 2.6 ± 0.0 | 210.8 ± 42.8 | 403.4 ± 20.2 | 9.09 ± 1.76 | 299.2 ± 36.1 | 0.56 ± 0.01 |
| Native, -O3, G1 (Oracle GraalVM) | 3 | 431.2 ± 0.2 | 2.7 ± 0.0 | 751.4 ± 4.2 | 1300.1 ± 101.0 | 28.25 ± 0.56 | 165.2 ± 1.6 | 0.82 ± 0.01 |

Quello che il report di confronto non guarda:

| Build | Servite/s (dispatch) | Vivo a fine cella | CPU strozzata (%) | Heap picco (MB) | Collezioni GC | Pausa GC media (ms) | Tempo in GC (%) | gauge gc_time_fraction | Compilazione (s) | Immagine (MB) |
|---|---|---|---|---|---|---|---|---|---|---|
| JVM (Java 25, JIT) | 212 ± 41 | si | 0.6 ± 0.1 | 316 ± 48 | 475 ± 47 | 5.4 ± 2.0 | 0.95 ± 0.07 | NaN | 15.3 | 244 |
| Native, -Os, serial GC | 273 ± 8 | si | 1.8 ± 0.3 | 258 ± 9 | 1505 ± 36 | 26.2 ± 6.4 | 8.17 ± 1.87 | ok | 250.8 | 242 |
| Native, -O3, serial GC | 272 ± 6 | si | 1.0 ± 0.2 | 267 ± 31 | 1466 ± 79 | 30.9 ± 5.3 | 9.40 ± 1.32 | ok | 260.8 | 278 |
| Native, -O3, G1 (Oracle GraalVM) | 211 ± 2 | si | 20.6 ± 1.0 | — | — | — | — | ok | 836.9 | 840 |
