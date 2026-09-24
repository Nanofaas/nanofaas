| arm | metric | baseline median | candidate median | change % | budget % | verdict |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| per-function (no change) | steady p99 ms | 2.8094 | 2.7571 | -1.86 | 5 | PASS |
| per-function (no change) | steady useful/s | 21.5000 | 21.5000 | +0.00 | 5 | PASS |
| per-function (no change) | thread CPU/completion us | 368.8510 | 405.7600 | +10.01 | 10 | FAIL |
| per-function (no change) | post-GC heap MiB | 35.4356 | 35.4397 | +0.01 | 10 | PASS |
| per-function -> shared-queue | steady p99 ms | 2.6708 | 2.5757 | -3.56 | 5 | PASS |
| per-function -> shared-queue | steady useful/s | 21.5000 | 21.5000 | +0.00 | 5 | PASS |
| per-function -> shared-queue | thread CPU/completion us | 406.7050 | 385.5420 | -5.20 | 10 | PASS |
| per-function -> shared-queue | post-GC heap MiB | 35.4356 | 35.4397 | +0.01 | 10 | PASS |
| shared-queue (no change) | steady p99 ms | 2.5714 | 2.7260 | +6.02 | 5 | FAIL |
| shared-queue (no change) | steady useful/s | 21.5000 | 21.5000 | +0.00 | 5 | PASS |
| shared-queue (no change) | thread CPU/completion us | 387.3330 | 385.1330 | -0.57 | 10 | PASS |
| shared-queue (no change) | post-GC heap MiB | 35.4356 | 35.4396 | +0.01 | 10 | PASS |
| shared-queue -> per-function | steady p99 ms | 2.6789 | 2.6025 | -2.85 | 5 | PASS |
| shared-queue -> per-function | steady useful/s | 21.5000 | 21.5000 | +0.00 | 5 | PASS |
| shared-queue -> per-function | thread CPU/completion us | 370.2040 | 410.4040 | +10.86 | 10 | FAIL |
| shared-queue -> per-function | post-GC heap MiB | 35.4357 | 35.4398 | +0.01 | 10 | PASS |
