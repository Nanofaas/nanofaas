# Implementation decisions

Task 1: Ruling: change only container-local naming, retaining common default and containerd naming — avoids altering unrelated custom naming hooks; shared state still retains recovered prefix — cost if wrong: missed local recovery edge, covered by legacy reconcile/scale tests.

Task 1: Ruling: creation conflicts fail without replacement; provider cleanup requires returned ownership or exact-label discovery, and skips typed collision — automatic adoption/replacement of preexisting containers would risk destructive ownership confusion — cost if wrong: a partial startup resource can await explicit recovery instead of immediate cleanup.

Task 3: Ruling: reject automatic enable while a manual preparation is active — its monotonic fence has not settled, so checking only lastPreparedEpoch admits a stale grid — cost if wrong: caller must retry configuration after manual completion.

Task 4: Ruling: outside the lead interval, configuration validates the first future grid window using the same exact windowAt calculation — nextWindow correctly returns empty there, which must not reject an otherwise valid configuration — cost if wrong: selecting the next future candidate could miss a different fencing interpretation; explicit manual-fence tests cover it.

Task 5: Ruling: use Reactor timeout’s dedicated lazy fallback after response mapping instead of catching every TimeoutException — only the waiter’s operator can produce the waiter flag, avoiding backend exception misclassification — cost if wrong: a backend TimeoutException is surfaced as EXECUTION_FAILED rather than caller timeout; regression asserts this boundary.

Task 6: Ruling: add test-only control-plane and spring-webflux dependencies to Java SDK tests — lets the same test feed actual servlet-controller HTTP bytes into the real dispatcher and compare callback metadata, avoiding a hand-built wire fixture — cost if wrong: heavier SDK test classpath/build; production SDK dependency graph is unchanged and global suite will verify contexts.

Task 6: Ruling: count the decoder bodyToMono subscription separately from WebClient’s implicit releaseBody drain — exchangeToMono subscribes for cleanup after decoding, so a cold synthetic body inflated the old counter — cost if wrong: the unit probe does not count cleanup as a second read; real HTTP tests, exact output checks and explicit releaseBody verification cover the transport path.

Task 6: additional gate exposed pre-existing expiry test race (terminal/future/live removal observed before archive publication); isolated rerun green, source ExecutionLifecycle.settle confirms publication precedes archive. CRITICAL test impact warned. Ruling: await the archived outcome in the existing expiry barrier — terminal future completion is not archive visibility, and a late dispatch must be tested after settlement — cost if wrong: eventual archive absence still fails within the same five-second limit; production expiry behavior unchanged.

Task 7: Ruling: use a FIFO callback-worker limiter with per-waiter loop futures under the existing condition lock — one manager must retain its global worker bound across successive ASGI event loops without loop-bound semaphores — cost if wrong: handoff/cancellation races can leak a worker; queued cancellation and repeated-loop tests cover them.

Task 7: Ruling: follow HTTPX next_request manually and close streamed redirect responses without reading bodies — automatic redirect handling consumes bodies and can stall before the next hop; preserve HTTPX method/header rules and the former 30-hop limit — cost if wrong: redirect parity differs; real 301/302/303/307/308 and slow-chain tests cover current behavior.

Task 7: Ruling: allow a bounded cleanup grace of at most min(attempt timeout, 100 ms) after the attempt deadline, preserving its original exception — closing transports precedes releasing physical capacity and must itself be finite — cost if wrong: observed attempt duration may exceed the configured deadline by this documented grace.

Task 8: Ruling: native wrapper ignores CONTAINERD_MAVEN_REPO when containerd is not selected (including default/all, which select Kubernetes) — no unnecessary host Maven context should enter unrelated images — cost if wrong: users expecting to mount the repository for unrelated modules must explicitly select containerd; matrix tests freeze the behavior.

Task 9: Ruling: retry the one-shot native gate with nativeBuildMemory=6g after the required 4g run exits 3 with explicit Java heap OutOfMemoryError during code compilation — same sources, recipe and parallelism=2; host has 120 GiB and the original log preserves the failed bound — cost if wrong: the published validation needs a larger builder heap than the plan's 4g example; do not claim 4g passed.

Task 9: Ruling: consume the dispatcher via Gradle compileJava task output, with explicit SPI/execution-runtime/Netty test dependencies, instead of the full control-plane project runtime — real cross-boundary wire proof must not import server/module auto-configuration into the SDK-only Spring context; the global suite exposed both module and core import failures — cost if wrong: compile output rather than packaged resources is the integration-test input; artifact E2E separately proves the packaged/native behavior.
