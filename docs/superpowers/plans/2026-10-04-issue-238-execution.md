# Issue 238: esecuzione del piano

Data: 2026-10-04. Issue: https://github.com/miciav/nanofaas/issues/238.
Base: `ef856960e99a6c56b93be6a978965535f7a3eba7`.
Branch locale: `codex/issue-238-publication-readiness`.
Worktree: `/private/tmp/nanofaas-238`.

## Modifiche consegnate

- Gate aggregato che rifiuta job falliti, saltati o cancellati; gate degli strumenti, dipendenze native riproducibili, Python 3.12 esplicito, composizioni core-only/P07, sync e async.
- `releaseChecks` verifica Helm e SpotBugs sull'intero grafo dei progetti. Baseline congelata di 228 hash distinti di debito preesistente (226 iniziali più due rilevati con le dipendenze containerd fissate), separata dalle esclusioni di falsi positivi. Nessuna rigenerazione per rendere verde il gate.
- Corpus condiviso dei fallimenti con validazione rigorosa e quattro scenari eseguiti dai sei SDK: serializzazione/envelope, rifiuto callback, timeout ingresso e timeout callback. Verificati identità, payload, tentativi, esito di consegna e rilascio fisico delle risorse.
- Errori di gestione delle funzioni in JSON uniforme `{error,message,details?}`, factory immutabile e OpenAPI aggiornato; HTTP reale sul binario nativo verifica 404 e 400.
- Una sola costruzione dei profili di carico, con comportamento precedente conservato. Commit `e3a14669`, caratterizzazione 16 casi e confronto esatto su 57.974 combinazioni più cinque errori: riferimento per la futura migrazione #240.
- Confini e proprietà dello stato degli engine documentati; nessun trasferimento di stato o modifica dei percorsi di esecuzione. Storia e motivazioni operative conservate nell'ADR 238.

## Verifiche

| Verifica | Risultato |
|---|---|
| Java Linux, `test releaseChecks`, composizione completa | BUILD SUCCESSFUL; ultima ripetizione 2m37s |
| Governor async esplicito | Tre E2E eseguiti, zero errori e zero skip; BUILD SUCCESSFUL |
| Core-only, API, sync; calibrazione P07 su due CPU | Verdi |
| Strumenti, esperimenti e corpus | 220 test e 11 sottotest verdi |
| Scaffolder fn-init | 80 test verdi |
| SDK Python / JavaScript Node 24 | 153 / 91 test verdi |
| SDK Java / Java-lite | Suite complete verdi |
| SDK Go, Linux Go 1.24 | Suite completa verde |
| SDK Rust | 91 unit test, un esempio del corpus e tre test HTTP verdi |
| Native compile, GraalVM fissata | BUILD SUCCESSFUL; binario ELF eseguibile |
| HTTP sul binario nativo | 404 FUNCTION_NOT_FOUND e 400 VALIDATION_ERROR verificati |
| Prove negative dei gate | Disallineamento Helm e nuovo finding SpotBugs causano fallimento |

Log locali conservati sotto `/private/tmp/nanofaas-238-*.log`; i percorsi e i comandi esatti sono nel record di esecuzione sottostante. La verifica sul solo host macOS non sostituisce Linux, Node 24 e Go 1.24 richiesti dalla CI.

## Revisione finale

Un revisore indipendente ha esaminato l'intero branch. Due rilievi Important sono entrati in un unico passaggio di correzione: composizione async assente dalla CI e mancata osservazione dell'esito finale dei callback. Il test di scheduling ha fallito prima della correzione; i controlli sui callback hanno rilevato mutazioni temporanee che sopprimono la registrazione dei fallimenti in tutti i sei SDK. Le suite complete sono poi tornate verdi. Tutti i sorgenti di produzione sono ripristinati dopo le mutazioni.

Rilievo minore differito: i report Gradle delle composizioni precedenti possono essere sovrascritti prima dell'upload finale. Conservare report distinti per composizione in un seguito; i fallimenti dei comandi continuano a bloccare il gate.

## Limiti espliciti

Java-lite conserva un difetto preesistente con upload TCP parziale: la chiusura dello stream di HttpServer può bloccare il timeout. Il test sullo stream bloccato verifica la logica, non certifica il timeout TCP. Le differenze storiche nei codici SDK sono dichiarate nel corpus. Rust accetta soltanto JSON Value: il test di envelope usa il rifiuto dello status invalido, non un output arbitrariamente non serializzabile.

Il branch resta locale. Nessun push, merge, PR o modifica delle regole GitHub è stato eseguito; l'amministratore deve rendere obbligatorio `release-gate`. La baseline SpotBugs resta debito da ridurre. Gli skip dipendenti dalla composizione sono coperti dalle invocazioni complementari; il caso dei permessi sui file richiede un utente non-root.

## Decisioni registrate, con relativo costo

- Ruling: Native worktree tools target the chat's DFaaS repository; use git worktree for NanoFaaS at /private/tmp/nanofaas-238 — isolates the approved repository — cost: checkout managed manually.
- Ruling: Preserve active AGENTS.md instructions as a local worktree overlay, excluded from commits — original checkout stays unchanged — cost: overlay must survive execution.
- Ruling: Freeze existing SpotBugs debt with exact instance hashes via baselineFile, separate from false-positive exclusions — more precise than package or method-wide matches — cost: review and retire 227 existing findings rather than fixing unrelated debt in this release-gate increment.
- Ruling: Graph build/API class reports UNKNOWN; confirmed Gradle task use and Spring HTTP route/test consumers by text inspection — dynamic boundaries are not unused — cost: regression suites are required for these changes.
- Ruling: Run independent task 3 tests while task 1 platform prerequisite is unresolved — no shared implementation interface — cost: task 1 remains pending verification, not marked complete.
- Ruling: GitNexus detect-changes text formatter truncates affected process display even with --limit; use its MCP/local backend JSON result before committing — incomplete text report is not a clean graph review — cost: extra tooling check.
- Ruling: Extend the shared runtime corpus with a companion failure-wire-corpus.json validated by the same validator, preserving saturation/v3's closed action model and twelve scenarios — actual transport/serialization tests reuse production runtimes and shared semantic expectations — cost: two fixture documents, one validator/policy location; adapters must execute the companion explicitly.
- Ruling: releaseChecks analyzes the full configured project graph rather than filtering optional modules by application classpath — Gradle includes optional module projects independently of controlPlaneModules — cost: even core-only static checks require the pinned containerd prerequisites; no finding is hidden.
- Ruling: Preserve existing Python/JavaScript/Java-lite encoding error classification and JavaScript ingress code as explicit knownDifferences — issue requires characterization without unrelated runtime changes — cost: cross-runtime error codes remain different pending a compatibility fix.
- Ruling: Rust public HandlerResponse holds only JSON Value; test invalid envelope status through real encoding rejection — arbitrary unserializable envelope output is unrepresentable — cost: this is encoding rejection coverage, not arbitrary envelope serializer fault coverage.
- Ruling: Java-lite partial TCP input exposes blocking HttpServer stream close; execute its blocking-stream logic test and document TCP conformance gap — preserve runtime behavior and avoid claiming a false finite TCP deadline — cost: a transport bug remains and must be fixed separately.
- Ruling: Native HTTP errors are exercised by a standalone script reused directly in CI rather than NanoLab orchestration — NanoLab's current catalogue supports deployment workflows but no generic binary runner; direct HTTP proves AOT serialization without a benchmark dependency — cost: no NanoLab deployment scenario is asserted.
- Ruling: Function-management error normalization is the API scope; execution/callback results, invocation quotas and application-selected statuses retain their own contracts — preserve existing semantics and latency-sensitive invocation paths — cost: invocation admission error formats remain distinct.
- Ruling: Use exact CI environment for full conformance — Node 24 succeeds whereas host Node 26 shutdown fails; Go 1.24 Linux succeeds whereas macOS bind semantics hang; native recipe tests require Linux — cost: local host alone cannot establish release readiness.
- Ruling: Preserve test environment skips only when their complementary composition is executed or the environment cannot express the permission condition — avoid weakening gates — cost: unreadable-file semantics require a non-root host test for full coverage.
- Final: Ruling: Java-lite partial TCP stall remains separate — existing behavior is preserved and the blocking-stream test is explicitly bounded — cost: no TCP deadline conformance claim until the transport bug is fixed.
- Final: Ruling: Existing SDK error classifications remain declared differences — consumers retain their current codes — cost: cross-runtime portability still needs a compatibility follow-up.
- Final: Ruling: Rust invalid-status envelope rejection stands as the bounded public-API test — arbitrary non-JSON output cannot be constructed — cost: no arbitrary serializer-fault coverage.
- Final: Ruling: GitHub branch-rule activation is outside this local execution — required checks are implemented but no administrator setting was changed — cost: branch rules must still require release-gate.
- Final: Ruling: No further engine extraction or optimization — comment-only ownership clarification preserves state and hot paths — cost: existing complexity remains where extraction lacks evidence.
- Final: Ruling: Native smoke remains a direct binary HTTP script — current NanoLab has no generic native-binary runner — cost: no NanoLab orchestration coverage.
- Final: Ruling: Preserve the completed local branch and worktree as the handoff — native plan execution authorizes implementation, not an external publish or merge — cost: integration and required GitHub branch rules remain a separate action.

## Record completo

```text
# SDD ledger — plan: docs/superpowers/plans/2026-10-04-issue-238-publication-readiness.md
Execution: native, approved by user 2026-10-04. Base ef856960e99a6c56b93be6a978965535f7a3eba7.
Tasks: 1 complete; 2 complete; 3 complete; 4 complete; 5 complete; final review and single fix pass complete.
Ruling: Native worktree tools target the chat's DFaaS repository; use git worktree for NanoFaaS at /private/tmp/nanofaas-238 — isolates the approved repository — cost: checkout managed manually.
Ruling: Preserve active AGENTS.md instructions as a local worktree overlay, excluded from commits — original checkout stays unchanged — cost: overlay must survive execution.
Pre-flight 1/4: experiment gate consumes current profiles; no migration landed at base, both remain NanoFaaS until #240.
Pre-flight 2/3: runtime nested error envelope and control-plane flat ApiError are distinct protocols; preserve runtime schema.
Pre-flight 1/5: comment cleanup changes no workflow semantics; preserve gate edits from task 1.
Pre-flight 2/5: README policy/conformance sections remain current; move history without duplicating policy.
Task 1: RED 5 release-gate tests; GREEN 5. Baselines: tools 172 passed; fn-init 80 passed; Java execution-runtime/java/java-lite BUILD SUCCESSFUL.
Ruling: Freeze existing SpotBugs debt with exact instance hashes via baselineFile, separate from false-positive exclusions — more precise than package or method-wide matches — cost: review and retire 227 existing findings rather than fixing unrelated debt in this release-gate increment.
Ruling: Graph build/API class reports UNKNOWN; confirmed Gradle task use and Spring HTTP route/test consumers by text inspection — dynamic boundaries are not unused — cost: regression suites are required for these changes.
Task 1: baseline XML corrected preserving exactly the original 226 unique hashes (227 observations); common SpotBugs is green. Automated review rejected combined baseline regeneration/module filtering; proceeded only after whitelist equality and unchanged Java sources proved zero newly excluded findings.
Task 1 verification limitation: containerd grpc generator artifact labeled osx-aarch_64 contains x86_64 Mach-O; bad CPU type on this host. Docker daemon unavailable. No production dependency bumped.
Ruling: Run independent task 3 tests while task 1 platform prerequisite is unresolved — no shared implementation interface — cost: task 1 remains pending verification, not marked complete.
Task 3: RED observed, 46 tests, 7 failed due to missing structured HTTP bodies; factory/controller implementation in progress.
Task 3: targeted API suite GREEN (46 tests), control-plane SpotBugs GREEN. CLI diagnostics preserve HTTP body, no parser update needed. Native HTTP verification still pending.
Task 4: characterization 16 tests GREEN before refactor; impact helper LOW (6 callers, wizard/experiments); preset variable UNKNOWN confirmed with text search. Single helper and sequence source implemented.
Task 4: full experiments suite 109 passed; exact baseline parity 57,974 valid cases plus 5 invalid-input exception cases. Ready to commit after graph change analysis.
Ruling: GitNexus detect-changes text formatter truncates affected process display even with --limit; use its MCP/local backend JSON result before committing — incomplete text report is not a clean graph review — cost: extra tooling check.
Task 1: Docker started; broad /private/tmp bind rejected by automatic review; narrower read-only source/worktree and dedicated writable Maven/Gradle mounts succeeded. Pinned containerd dependencies built successfully under Linux ARM64.
Ruling: Extend the shared runtime corpus with a companion failure-wire-corpus.json validated by the same validator, preserving saturation/v3's closed action model and twelve scenarios — actual transport/serialization tests reuse production runtimes and shared semantic expectations — cost: two fixture documents, one validator/policy location; adapters must execute the companion explicitly.

Ruling: releaseChecks analyzes the full configured project graph rather than filtering optional modules by application classpath — Gradle includes optional module projects independently of controlPlaneModules — cost: even core-only static checks require the pinned containerd prerequisites; no finding is hidden.
Ruling: Preserve existing Python/JavaScript/Java-lite encoding error classification and JavaScript ingress code as explicit knownDifferences — issue requires characterization without unrelated runtime changes — cost: cross-runtime error codes remain different pending a compatibility fix.
Ruling: Rust public HandlerResponse holds only JSON Value; test invalid envelope status through real encoding rejection — arbitrary unserializable envelope output is unrepresentable — cost: this is encoding rejection coverage, not arbitrary envelope serializer fault coverage.
Ruling: Java-lite partial TCP input exposes blocking HttpServer stream close; execute its blocking-stream logic test and document TCP conformance gap — preserve runtime behavior and avoid claiming a false finite TCP deadline — cost: a transport bug remains and must be fixed separately.
Ruling: Native HTTP errors are exercised by a standalone script reused directly in CI rather than NanoLab orchestration — NanoLab's current catalogue supports deployment workflows but no generic binary runner; direct HTTP proves AOT serialization without a benchmark dependency — cost: no NanoLab deployment scenario is asserted.
Ruling: Function-management error normalization is the API scope; execution/callback results, invocation quotas and application-selected statuses retain their own contracts — preserve existing semantics and latency-sensitive invocation paths — cost: invocation admission error formats remain distinct.
Ruling: Use exact CI environment for full conformance — Node 24 succeeds whereas host Node 26 shutdown fails; Go 1.24 Linux succeeds whereas macOS bind semantics hang; native recipe tests require Linux — cost: local host alone cannot establish release readiness.
Task 1: Full releaseChecks GREEN; intentional Helm mismatch nonzero; new ES_COMPARING_PARAMETER_STRING_WITH_EQ finding outside exact baseline nonzero. Native CI bootstrap missing from base caught RED (1 failed/5 passed), now GREEN (6 passed).
Task 2: Java/Java-lite four cases GREEN; Python full 153 passed before latest output-source-only edit; JavaScript Node24 full 91 passed; Go Linux suite GREEN; Rust 91 passed. Fixture and validator tests plus tools 216 passed/11 subtests.
Task 3: Native compile succeeded (3m40s), executable ELF verified, real HTTP 404 FUNCTION_NOT_FOUND and 400 VALIDATION_ERROR verified.
Task 5: Engine impact CRITICAL (25 upstream, dynamic interface boundary) acknowledged; comments only. Execution-runtime and CoreArchitecture tests GREEN. No new engine state, no benchmark necessary.
Task 4: complete (commits ef85696..e3a1466, tests: env UV_CACHE_DIR=/private/tmp/nanofaas-238-uv-cache uv run --python 3.12 --with pytest --with pyyaml python -m pytest experiments/tests -q → 109 passed in 2.41s)

Task 3: API implementation committed after all-composition FunctionController, replicas, exception and immutable publication tests GREEN; native HTTP verified. Core-only invocation pending, no completion claim yet.

Task 1: Linux CI prerequisites complete (Docker/Buildx/Python/git); full test+releaseChecks GREEN in 7m53s, 239 tasks. Core-only/API GREEN, sync composition GREEN, async composition GREEN. Added missing core-only P07 calibration scheduling: RED 1/7, GREEN 7/7; calibration GREEN on two CPU quota with Java processor count 2.
Task 2: Fresh final SDK suites: Python153, JavaScript91, Go Linux full GREEN, Rust91 unit+1 corpus example+3 HTTP integration GREEN. Java152 and Java-lite90 GREEN in Linux all. Tests assert shared success payload and stable execution/dispatch/trace identity. Validator rejects numeric booleans as well as missing/error/body/deadline/counter contradictions.
Task 1/3 skips: all-composition CP skips CoreOnlyApiTest (2) and P07 core-only case (1), each executed in dedicated none composition. FunctionCatalog permission test skips under root because root can read chmod-restricted files; its malformed JSON assertion already ran. No silent skipped required job is accepted. All includes sync and therefore skips async governor E2E, covered separately by async composition; sync E2E covered separately.
Ruling: Preserve test environment skips only when their complementary composition is executed or the environment cannot express the permission condition — avoid weakening gates — cost: unreadable-file semantics require a non-root host test for full coverage.
Task 1: complete (commits ef85696..b0be7fe, tests: env JAVA_HOME=/private/tmp/nanofaas-238-jdk/Contents/Home ./gradlew releaseChecks -PcontrolPlaneModules=all -PcontainerdMavenLocal=true -Dmaven.repo.local=/private/tmp/nanofaas-238-linux-maven --continue → 87 actionable tasks: 4 executed, 83 up-to-date)
Task 3: complete (commits ef85696..6b4ef59, tests: env JAVA_HOME=/private/tmp/nanofaas-238-jdk/Contents/Home ./gradlew :control-plane:test -PcontrolPlaneModules=none --tests *FunctionController*Test --tests *GlobalExceptionHandlerTest --tests *FunctionResponseContractTest --tests *ApiErrorResponsesTest --tests *CoreOnlyApiTest → 47 actionable tasks: 10 executed, 37 up-to-date)
Task 5: complete (commits ef85696..6b4ef59, tests: env JAVA_HOME=/private/tmp/nanofaas-238-jdk/Contents/Home ./gradlew :execution-runtime:test :control-plane:test --tests *CoreArchitecture*Test -PcontrolPlaneModules=none → 56 actionable tasks: 2 executed, 54 up-to-date)
Task 2: complete (commits ef85696..66a73ca, tests: bash .superpowers/sdd/2026-10-04-issue-238-publication-readiness/verify-runtime.sh → test result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s)

Task 1 follow-up: explicit --python 3.12 on the SDK gate prevents uv from choosing a different runner default after install; scheduling test RED 1/8, GREEN 8/8. No runtime dependency changed.

Final review: fresh gpt-6-astra reviewer, base ef856960..3de55dac. Re-graded: missing async CI composition Important (gate can falsely pass); unobserved callback delivery outcome Important (regression can falsely pass); overwritten composition reports Minor (diagnostic evidence loss; test failures still block).
Final: minor (deferred): Gradle reports from earlier compositions are overwritten before the final artifact upload; preserve each composition under distinct artifact names in a follow-up.
Final: Ruling: Java-lite partial TCP stall remains separate — existing behavior is preserved and the blocking-stream test is explicitly bounded — cost: no TCP deadline conformance claim until the transport bug is fixed.
Final: Ruling: Existing SDK error classifications remain declared differences — consumers retain their current codes — cost: cross-runtime portability still needs a compatibility follow-up.
Final: Ruling: Rust invalid-status envelope rejection stands as the bounded public-API test — arbitrary non-JSON output cannot be constructed — cost: no arbitrary serializer-fault coverage.
Final: Ruling: GitHub branch-rule activation is outside this local execution — required checks are implemented but no administrator setting was changed — cost: branch rules must still require release-gate.
Final: Ruling: No further engine extraction or optimization — comment-only ownership clarification preserves state and hot paths — cost: existing complexity remains where extraction lacks evidence.
Final: Ruling: Native smoke remains a direct binary HTTP script — current NanoLab has no generic native-binary runner — cost: no NanoLab orchestration coverage.

Final: fixed missing async composition — test_async_governor_composition_is_scheduled RED 1 failed/8 passed → GREEN 9/9; Linux test+releaseChecks GREEN (2m37s), async governor three executed/zero skipped GREEN (11s).
Final: fixed unobserved delivery outcome — six SDK adapters consume callbackDelivered through actual failure metrics; suppression mutation RED in Java, Java-lite, Python, JavaScript, Go and Rust; restored whole SDK suites GREEN: Java152, Java-lite90, Python153, JS91, Go package, Rust91+1+3, validator37+11 subtests. The first Rust mutation touched admission counting rather than dispatcher counting and survived; the actual dispatcher mutation failed on delivery outcome 0 vs 1.
Final: tools suite GREEN 220 passed/11 subtests; git diff --check GREEN. Temporary metric mutations restored byte-for-byte; only test assertions, CI scheduling, documentation changed in the fix pass.
Final: Ruling: Preserve the completed local branch and worktree as the handoff — native plan execution authorizes implementation, not an external publish or merge — cost: integration and required GitHub branch rules remain a separate action.
Final: graph risk CRITICAL for the full committed range, expected from shared validator and API consumers; no partial/truncated/error result. Metrics impact HIGH acknowledged for temporary mutations; production metric changes were all restored. New tests are framework-discovered, not unused despite UNKNOWN graph callers.

```
