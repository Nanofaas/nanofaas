| Build | Runs | Throughput (rps) | p50 (ms) | p95 (ms) | p99 (ms) | Shed (%) | Control plane peak (MiB) | Control plane CPU (cores) |
|---|---|---|---|---|---|---|---|---|
| JVM (serial GC, full tiering) | 3 | 914.5 ± 0.2 | 1.1 ± 0.0 | 5.2 ± 0.4 | 102.7 ± 15.6 | 2.00 ± 0.14 | 1720.5 ± 7.1 | 0.67 ± 0.04 |

Quello che il report di confronto non guarda:

| Build | Servite/s (dispatch) | Vivo a fine cella | CPU strozzata (%) | Heap picco (MB) | Collezioni GC | Pausa GC media (ms) | Tempo in GC (%) | gauge gc_time_fraction | quota async degli arrivi (%) | rifiuti porta sync (%) | rifiuti porta async (%) | coda sync (max) | coda async (max) | replay sync | chiavi idempotenza (max) | Compilazione (s) | Immagine (MB) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| JVM (serial GC, full tiering) | 584 ± 1 | si | 0.8 ± 0.4 | 1331 ± 54 | 756 ± 887 | 38.6 ± 24.8 | 1.99 ± 0.17 | NaN | 19.9 ± 0.0 | 2.10 ± 0.13 | 2.08 ± 0.19 | 16 ± 13 | 4 ± 2 | 19081 ± 102 | 37786 ± 18414 | 14.8 | 244 |
