
scritta in /Users/micheleciavotta/Downloads/mcFaas/docs/experiments/baseline-2026-08/README.md fra i marcatori A3-misto-3x
| Build | Runs | Throughput (rps) | p50 (ms) | p95 (ms) | p99 (ms) | Shed (%) | Control plane peak (MiB) | Control plane CPU (cores) |
|---|---|---|---|---|---|---|---|---|
| JVM (serial GC, full tiering) | 3 | 1371.5 ± 0.0 | 1.2 ± 0.0 | 169.3 ± 95.9 | 814.5 ± 109.8 | 12.63 ± 0.40 | 1717.1 ± 5.7 | 0.89 ± 0.04 |

Quello che il report di confronto non guarda:

| Build | Servite/s (dispatch) | Vivo a fine cella | CPU strozzata (%) | Heap picco (MB) | Collezioni GC | Pausa GC media (ms) | Tempo in GC (%) | gauge gc_time_fraction | quota async degli arrivi (%) | rifiuti porta sync (%) | rifiuti porta async (%) | coda sync (max) | coda async (max) | replay sync | chiavi idempotenza (max) | Compilazione (s) | Immagine (MB) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| JVM (serial GC, full tiering) | 776 ± 4 | si | 11.3 ± 1.9 | 1366 ± 32 | 812 ± 876 | 47.6 ± 30.3 | 3.36 ± 0.40 | NaN | 19.9 ± 0.0 | 13.27 ± 0.44 | 12.87 ± 0.33 | 18 ± 0 | 8 ± 2 | 25599 ± 172 | 52578 ± 25818 | 14.1 | 244 |
