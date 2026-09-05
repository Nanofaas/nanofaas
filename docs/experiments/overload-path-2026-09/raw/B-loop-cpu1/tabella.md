
scritta in /home/michele/Documenti/nanofaas/docs/experiments/overload-path-2026-09/README.md fra i marcatori B-loop-cpu1
| Build | Runs | Throughput (rps) | p50 (ms) | p95 (ms) | p99 (ms) | Shed (%) | Control plane peak (MiB) | Control plane CPU (cores) |
|---|---|---|---|---|---|---|---|---|
| JVM (Java 25, JIT) | 3 | 435.1 ± 0.0 | 2.4 ± 0.1 | 110.5 ± 3.2 | 176.5 ± 2.4 | 26.63 ± 0.27 | 620.2 ± 1.7 | 0.56 ± 0.01 |
| JVM (serial GC, full tiering) | 3 | 435.1 ± 0.0 | 1.1 ± 0.1 | 8.4 ± 9.1 | 19.7 ± 13.8 | 0.46 ± 0.47 | 716.4 ± 1.2 | 0.41 ± 0.01 |
| JVM (serial GC, C1 only, 1 event loop) | 3 | 435.1 ± 0.0 | 2.1 ± 0.1 | 57.6 ± 0.6 | 72.7 ± 0.8 | 11.15 ± 0.13 | 626.1 ± 1.3 | 0.50 ± 0.01 |
| JVM (serial GC, full tiering, 1 event loop) | 3 | 435.1 ± 0.0 | 1.1 ± 0.0 | 2.5 ± 0.1 | 5.4 ± 0.4 | 0.05 ± 0.02 | 711.2 ± 2.7 | 0.37 ± 0.01 |

Quello che il report di confronto non guarda:

| Build | Servite/s (dispatch) | Vivo a fine cella | CPU strozzata (%) | Heap picco (MB) | Collezioni GC | Pausa GC media (ms) | Tempo in GC (%) | gauge gc_time_fraction | quota async degli arrivi (%) | rifiuti porta sync (%) | rifiuti porta async (%) | coda sync (max) | coda async (max) | replay sync | chiavi idempotenza (max) | Compilazione (s) | Immagine (MB) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| JVM (Java 25, JIT) | 207 ± 1 | si | 22.6 ± 0.2 | 471 ± 4 | 1378 ± 6 | 4.2 ± 0.3 | 1.22 ± 0.07 | ok | 0.0 ± 0.0 | 26.63 ± 0.27 | — | 40 ± 0 | — | 0 ± 0 | 0 ± 0 | 48.2 | 244 |
| JVM (serial GC, full tiering) | 300 ± 2 | si | 5.5 ± 3.5 | 492 ± 3 | 2042 ± 20 | 2.6 ± 0.2 | 1.12 ± 0.06 | ok | 0.0 ± 0.0 | 0.46 ± 0.47 | — | 14 ± 10 | — | 0 ± 0 | 0 ± 0 | 48.7 | 244 |
| JVM (serial GC, C1 only, 1 event loop) | 257 ± 1 | si | 17.1 ± 1.4 | 480 ± 4 | 1586 ± 14 | 3.1 ± 0.0 | 1.02 ± 0.02 | ok | 0.0 ± 0.0 | 11.15 ± 0.13 | — | 26 ± 6 | — | 0 ± 0 | 0 ± 0 | 20.3 | 244 |
| JVM (serial GC, full tiering, 1 event loop) | 302 ± 0 | si | 2.5 ± 0.2 | 490 ± 1 | 2025 ± 8 | 2.5 ± 0.0 | 1.05 ± 0.00 | ok | 0.0 ± 0.0 | 0.05 ± 0.02 | — | 1 ± 1 | — | 0 ± 0 | 0 ± 0 | 18.1 | 244 |
