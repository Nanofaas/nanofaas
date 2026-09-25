| arm | metric | baseline median | candidate median | change % | budget % | verdict |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| per-function (no change) | steady p99 ms | 2.8094 | 2.6011 | -7.42 | 5 | PASS |
| per-function (no change) | steady useful/s | 21.5000 | 21.5000 | +0.00 | 5 | PASS |
| per-function (no change) | thread CPU/completion us | 368.8510 | 343.4470 | -6.89 | 10 | PASS |
| per-function (no change) | post-GC heap MiB | 35.4356 | 35.4404 | +0.01 | 10 | PASS |
| per-function -> shared-queue | steady p99 ms | 2.6708 | 2.5837 | -3.26 | 5 | PASS |
| per-function -> shared-queue | steady useful/s | 21.5000 | 21.5000 | +0.00 | 5 | PASS |
| per-function -> shared-queue | thread CPU/completion us | 406.7050 | 332.1310 | -18.34 | 10 | PASS |
| per-function -> shared-queue | post-GC heap MiB | 35.4356 | 35.4404 | +0.01 | 10 | PASS |
| shared-queue (no change) | steady p99 ms | 2.5714 | 2.7786 | +8.06 | 5 | FAIL |
| shared-queue (no change) | steady useful/s | 21.5000 | 21.5000 | +0.00 | 5 | PASS |
| shared-queue (no change) | thread CPU/completion us | 387.3330 | 359.0640 | -7.30 | 10 | PASS |
| shared-queue (no change) | post-GC heap MiB | 35.4356 | 35.4403 | +0.01 | 10 | PASS |
| shared-queue -> per-function | steady p99 ms | 2.6789 | 2.5569 | -4.56 | 5 | PASS |
| shared-queue -> per-function | steady useful/s | 21.5000 | 21.5000 | +0.00 | 5 | PASS |
| shared-queue -> per-function | thread CPU/completion us | 370.2040 | 363.7680 | -1.74 | 10 | PASS |
| shared-queue -> per-function | post-GC heap MiB | 35.4357 | 35.4406 | +0.01 | 10 | PASS |
