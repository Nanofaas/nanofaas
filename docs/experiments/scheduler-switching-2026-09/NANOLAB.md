# The manual switch, as executed against the NanoLab stacks

Task 13c, issue #208. This file records the **procedure that was actually run** — the load, the
revision read, the alternating PATCH, the assertions on the live selection, the admitted/completed
counts, the polling, the restart and the restore — together with the real output of each step.
Nothing here is a recipe that was written and not followed: every block below is a command whose
result is quoted beside it.

## Read this first: this file is not the procedure's durable home

The procedure belongs in **NanoLab**, as a workflow that provisions the stack and drives the switch
itself, so that the next reader runs it rather than re-deriving it. That workflow does not exist:
NanoLab's task modules live under `packages/nanolab/src/nanolab/tasks/` and none of its scenarios
names `runtime-config`, `PATCH`, `expectedRevision` or `scheduler`. Writing it is a separate task in
the NanoLab repository — a task module, a workflow builder, a `scenarios-v2/scheduler-switch-*.yaml`
and NanoLab's own tests — and nothing of it was written here, because the NanoLab checkout is a
different repository and was not modified by this task.

**It is less work than "the capability does not exist" would suggest.** NanoLab already drives a
`runtime-config` namespace of its own: `runtime_config_tasks()` in
`packages/nanolab/src/nanolab/tasks/cli_function.py` GETs, validates (including a deliberately
invalid patch) and PATCHes one, wired into `plans/cli.py` and exercised through the `nanofaas-cli`
binary. It targets the `control-plane` namespace — the rate limiter — rather than `scheduler`, and it
goes through the CLI rather than over HTTP, so it is the same shape aimed elsewhere and not this
procedure. The follow-on is extending that shape to the `scheduler` namespace and wiring it as a
workflow, not building a runtime-config driver from scratch.

So: what follows is a record of an execution, not a supported entry point. When the NanoLab
workflow exists, it supersedes this file.

## 1. What was run, and in what order

Both scenarios were inspected with `plan` before anything executed, as NanoLab's README asks, from
`/home/michele/Documenti/nanolab`.

```bash
export NANOFAAS_ROOT=/home/michele/Documenti/nanofaas
uv run --package nanolab nanolab plan packages/nanolab/scenarios-v2/deployment-lifecycle-container.yaml
uv run --package nanolab nanolab run  packages/nanolab/scenarios-v2/deployment-lifecycle-container.yaml
uv run --package nanolab nanolab plan packages/nanolab/scenarios-v2/deployment-lifecycle-k8s.yaml --environment packages/nanolab/environments/multipass.yaml
uv run --package nanolab nanolab run  packages/nanolab/scenarios-v2/deployment-lifecycle-k8s.yaml --environment packages/nanolab/environments/multipass.yaml
```

| scenario | plan | run | outcome |
|---|---|---|---|
| `deployment-lifecycle-container.yaml` | 11 tasks, no cluster required | executed | **all 11 passed, exit 0** (§2) |
| `deployment-lifecycle-k8s.yaml` + `environments/multipass.yaml` | 21 tasks, `stack` role = managed VM | executed | **all 21 passed, exit 0** (§3) |

The container stack serves the deployment lifecycle but **cannot serve the switch procedure as the
scenario ships it** — §4 is what the procedure needs on top, and §5 is the procedure as executed.

## 2. The container scenario, as the brief's command runs it

```
[001.acquire-local-registry] passed   0.1s
[002.acquire-docker-compose-project-nanofaas-validate] passed  76.8s
[003.build-application-artifact-word-stats-java] passed   6.3s
[004.build-image-word-stats-java] passed  13.9s
[005.push-image-127-0-0-1-5000-nanofaas-java-word-stats-e2e] passed   1.8s
[006.acquire-word-stats-java] passed   2.0s
[007.invoke-word-stats-java] passed   0.2s
[008.inspect-resources-of-nanofaas-word-stats-java-r1] passed   0.0s
[009.release-word-stats-java] passed   0.2s
[010.release-docker-compose-project-nanofaas-validate] passed   2.8s
[011.release-local-registry] passed   0.1s
EXIT=0
```

The stack it brings up is `docker compose -f deploy/compose/compose.yaml -p nanofaas-validate up -d
--build --wait`, ready on `http://127.0.0.1:8080` (API) and `http://127.0.0.1:8081` (management),
with Prometheus beside it. Nothing was kept: the release tasks at 009–011 remove the function, the
compose project and the registry.

## 3. The k8s scenario on Multipass, and the VM's lifecycle

`multipass` 1.16.4 is installed and `environments/multipass.yaml` binds the `stack` role to a
**managed VM**, so the k8s path ran for real: NanoLab created the VM, ran the Ansible bootstrap and
put k3s inside it. No minikube and no other cluster was substituted. The VM lifecycle, verbatim
(first the provision half, then the 21 plan tasks):

```
[provision.stack.ensure] passed 35.9s
[provision.stack.vm.provision_base] passed 28.9s
[provision.stack.k3s.install] passed 14.1s
[provision.stack.registry.ensure_container] passed 3.9s
[provision.stack.k3s.configure_registry] passed 8.5s
[provision.stack.loadtest.install_k6] passed 5.2s
[provision.stack.assets.sync_to_vm] passed 0.4s
[provision.stack.repo.sync_to_vm] passed 1.5s
[001.check-kubectl-is-usable] passed 0.4s
[002.build-control-plane] passed 45.7s
[003.build-image-127-0-0-1-5000-nanofaas-control-plane] passed 12.5s
[004.push-image-127-0-0-1-5000-nanofaas-control-plane] passed 0.9s
[005.build-application-artifact-word-stats-java] passed 11.3s
[006.build-image-word-stats-java] passed 7.5s
[007.push-image-127-0-0-1-5000-nanofaas-java-word-stats-e2e] passed 0.6s
[008.build-application-artifact-k8s-sync-queue] passed 2.6s
[009.build-image-k8s-sync-queue] passed 7.4s
[010.push-image-127-0-0-1-5000-nanofaas-java-warm-echo-e2e] passed 0.7s
[011.acquire-helm-release-nanofaas] passed 23.8s
[012.acquire-word-stats-java] passed 8.6s
[013.invoke-word-stats-java] passed 0.8s
[014.inspect-resources-of-fn-word-stats-java] passed 0.4s
[015.release-word-stats-java] passed 0.3s
[016.acquire-k8s-sync-queue] passed 7.6s
[017.invoke-k8s-sync-queue] passed 0.4s
[018.check-k6-is-usable] passed 0.3s
[019.burst-the-synchronous-queue] passed 2.5s
[020.release-k8s-sync-queue] passed 0.3s
[021.release-helm-release-nanofaas] passed 4.4s
[provision.stack.destroy] passed 0.8s
EXIT=0
```

The VM is created as `nanofaas-stack` from the Multipass Ubuntu 26.04 LTS image and reported its own
address while it lived; the registry the workload images are pushed to runs **inside** it, not on the
host. The whole run took roughly four minutes of wall clock and **`--keep` was not needed**: the run
passed, and the managed VM was destroyed by the run itself — `multipass list` answers
`No instances found` afterwards, and no container, volume or network was left behind on the host.

The k8s scenario does **not** run the switch procedure either: it validates the deployment lifecycle
(functions registered, invoked, resources inspected, the sync queue burst through k6) against a
cluster. Its control plane does carry both queue modules — the k8s plan passes
`-PcontrolPlaneModules` with `sync-queue` added and builds the control plane in the VM — but the
Helm release does not enable the admin API, for the reason in §4.

## 4. The switch procedure needs two settings the scenario cannot express

The scenario's compose build uses `NANOFAAS_CONTROL_PLANE_MODULES`'s default
(`container-deployment-provider`), which carries **no queue module**, and `deploy/compose/compose.yaml`
forwards **no variable that enables the admin API**. Measured on the stack the scenario builds,
before anything else was changed:

```
$ curl -s -w '\nHTTP=%{http_code}\n' http://127.0.0.1:8080/v1/admin/runtime-config
{"error":"404 NOT_FOUND","message":"No static resource v1/admin/runtime-config for request 'http://127.0.0.1:8080/v1/admin/runtime-config'."}
HTTP=404
$ curl -s -w '\nHTTP=%{http_code}\n' http://127.0.0.1:8080/v1/admin/runtime-config/scheduler
{"error":"404 NOT_FOUND","message":"No static resource v1/admin/runtime-config/scheduler for request ..."}
HTTP=404
```

Two different facts are behind those two `404`s, and in this round both were measured. The first:
`nanofaas.admin.runtime-config.enabled` is `false` by default, so the whole admin surface is unmounted
and **both** paths answer `404` regardless of what the artifact was built with — measured again on a
four-module stack with the flag still off, where the same two paths answered `404` while
`/v1/functions` answered `200` and `/actuator/health/readiness` answered `UP`. The second: the
scenario's build carries no queue module, so no engine exposes `SchedulerControl` and the `scheduler`
namespace is absent **whatever the flag says**. Measured separately, with the flag on: an image built
with `container-deployment-provider,runtime-config` and started with
`NANOFAAS_ADMIN_RUNTIMECONFIG_ENABLED=true` serves `/v1/admin/runtime-config` **200**, its envelope
listing `control-plane` and no `scheduler` namespace at all, and answers
`/v1/admin/runtime-config/scheduler` **404**. The two causes are therefore independent, and the
procedure needs both fixed — the module list *and* the flag.

Both have to be supplied for the procedure to mean anything, and both are supplied the way the
campaign's own profile works — the both-queue profile for the build, and the explicit admin
enablement:

```bash
export NANOFAAS_CONTROL_PLANE_MODULES="container-deployment-provider,async-queue,sync-queue,runtime-config"
```

The module list can be passed straight through, because `deploy/compose/compose.yaml` interpolates
`NANOFAAS_CONTROL_PLANE_MODULES` as a build arg and NanoLab passes the caller's environment to
`docker compose`. The **admin enablement cannot**: the compose file lists the container's
environment explicitly and names no such variable, so no export from outside reaches the container.
It was supplied with a one-file compose overlay — applied to the scenario's own compose file and
project, and living outside both checkouts:

```yaml
# /tmp/13c/switch-env.yaml
services:
  control-plane:
    environment:
      NANOFAAS_ADMIN_RUNTIMECONFIG_ENABLED: ${NANOFAAS_ADMIN_RUNTIMECONFIG_ENABLED:-}
```

**Proposal, for NanoLab or nanoFaaS rather than for this task.** `deploy/compose/compose.yaml`
already forwards `NANOFAAS_SCHEDULER_STRATEGY` (the comment describing the PATCH endpoint is the
chart's, in `deploy/helm/nanofaas/values.yaml:13-17`, not this file's); one identical passthrough for
`NANOFAAS_ADMIN_RUNTIMECONFIG_ENABLED` (default empty, so the shipped default stays off) would make
the switch reachable from the compose file's own vocabulary and remove the need for an overlay.
**Compose is the only one of the three paths that lacks a supported way in:** Helm reaches the flag
through its generic `controlPlane.extraEnv` (`templates/control-plane-deployment.yaml:78`, with the
chart's own comment naming that route), and a native image needs it as a build prerequisite instead.
`FINAL.md` §7.4 states all three, with the rendered chart and the spelling measured.

The spelling in the overlay above (`…RUNTIMECONFIG…`) is not load-bearing: both it and the chart's
`NANOFAAS_ADMIN_RUNTIME_CONFIG_ENABLED` turn the route on, and the controls in `FINAL.md` §7.4 show
the route absent with no flag, with a misspelled one, and with the same variable set to `false`.

### Holding the stack up

`--until` does not hold the stack: run with `--until invoke-word-stats-java` the workflow still
executed the release tasks 008–010 and tore the project down (`EXIT=0`, nothing left running).
`--keep` does hold it — the compose project and the registry stay up while the function is
released, which is why the procedure below was run with `--keep`:

```bash
uv run --package nanolab nanolab run packages/nanolab/scenarios-v2/deployment-lifecycle-container.yaml --keep
```

The stack was then given the admin flag, without rebuilding, by recreating only the control plane:

```bash
export NANOFAAS_ADMIN_RUNTIMECONFIG_ENABLED=true
docker compose -f deploy/compose/compose.yaml -f /tmp/13c/switch-env.yaml -p nanofaas-validate up -d --wait
```

and the function the load needs was registered directly, with the image the scenario had just built
and pushed:

```bash
curl -sS -X POST http://127.0.0.1:8080/v1/functions -H 'Content-Type: application/json' \
  -d '{"name":"word-stats-java","image":"127.0.0.1:5000/nanofaas/java-word-stats:e2e","executionMode":"DEPLOYMENT","timeoutMs":5000,"concurrency":2,"queueSize":20,"maxRetries":3}'
# HTTP=201 ... "endpointUrl":"http://127.0.0.1:34931/invoke","deploymentBackend":"container-local"
```

## 5. The procedure, step by step, with what it printed

`BASE=http://127.0.0.1:8080`, `MGMT=http://127.0.0.1:8081`, `FN=word-stats-java`.

### 5.1 The startup selection, and the revision's home

```bash
curl -fsS $BASE/v1/admin/runtime-config
curl -fsS $BASE/v1/admin/runtime-config/scheduler
```

```
root revision = 0
scheduler = {"persistence": "restart", "available": ["per-function", "shared-queue"], "strategy": "per-function"}
{"persistence":"restart","available":["per-function","shared-queue"],"strategy":"per-function"}
```

Two things are worth reading off this. `NANOFAAS_SCHEDULER_STRATEGY` was **empty** in this container
(`NANOFAAS_SCHEDULER_STRATEGY=`), and the process still started on `per-function`: that is the
documented legacy mapping, and it also proves the image really carries both queue modules —
`available` names both ids, which a single-module build cannot do. And the `scheduler` namespace
carries **no `revision`**: the revision lives in the root envelope, which is where every PATCH below
reads its `expectedRevision` from.

### 5.2 Constant load

Two loops, started before the first PATCH and stopped after the last one: an async loop posting
`POST /v1/functions/$FN:enqueue` every 50 ms, and a sync loop posting
`POST /v1/functions/$FN:invoke` every 100 ms. Payload, for both:
`{"input":{"text":"beta alpha gamma beta alpha distributed systems process message network latency","topN":3}}`.

### 5.3 The alternating PATCH, with the selection asserted after each one

```bash
REV=$(curl -fsS $BASE/v1/admin/runtime-config | python3 -c 'import json,sys; print(json.load(sys.stdin)["revision"])')
curl -sS -o patch.json -w '%{http_code}' -X PATCH $BASE/v1/admin/runtime-config/scheduler \
  -H 'Content-Type: application/json' \
  -d "{\"expectedRevision\":$REV,\"values\":{\"strategy\":\"shared-queue\"}}"
curl -fsS $BASE/v1/admin/runtime-config/scheduler     # the live selection, not the request's echo
```

Six switches, alternating direction, under that load:

```
PATCH -> shared-queue  http=200  revision=0->1  live=shared-queue  OK
PATCH -> per-function  http=200  revision=1->2  live=per-function  OK
PATCH -> shared-queue  http=200  revision=2->3  live=shared-queue  OK
PATCH -> per-function  http=200  revision=3->4  live=per-function  OK
PATCH -> shared-queue  http=200  revision=4->5  live=shared-queue  OK
PATCH -> per-function  http=200  revision=5->6  live=per-function  OK
```

`live` is a fresh GET, so each line asserts the engine's own selection, not the PATCH body. The
revision advances once per committed switch, and the last switch leaves the process on the strategy
it started on.

### 5.4 Admitted and completed

```
async admitted (202) = 248   async rejected = 0   sync 200 = 163   sync other = 0
distinct execution ids admitted = 248
polled 20 ids; terminal: 20 success
ids resolving: 248 of 248
```

Every async request admitted across the load window was admitted with `202` and none was rejected;
every id still resolved after the switches, and the 20 polled to a terminal status all reached
`success`. The switch meters agree with the six PATCHes:

```
scheduler_switch_total{outcome="committed"} 6.0
scheduler_switch_duration_seconds_count 6
scheduler_switch_duration_seconds_sum 0.003270313
scheduler_switch_duration_seconds_max 0.002135531
```

### 5.5 Restart, and the restore of the initial selection

```bash
curl -X PATCH ... -d '{"expectedRevision":6,"values":{"strategy":"shared-queue"}}'   # away from the start
# live: {"persistence":"restart","strategy":"shared-queue","available":["per-function","shared-queue"]}
docker restart nanofaas-validate-control-plane-1
# after restart: {"available":["per-function","shared-queue"],"persistence":"restart","strategy":"per-function"}
# revision after restart: 0
```

The PATCH did not survive the restart: the process came back on `per-function`, the selection its
startup configuration derives, with the revision reset to `0`. The same holds when the startup
selection is explicit rather than derived — the container was recreated with
`NANOFAAS_SCHEDULER_STRATEGY=shared-queue`, started on `shared-queue`, was PATCHed to
`per-function` (`200`, live `per-function`), and after a restart answered `shared-queue` again.
The two error paths the contract documents measured the same way:

```
stale revision  -> HTTP=409  {"error":"Revision mismatch: expected 0 but current is 1","currentRevision":1}
unknown id      -> HTTP=422  {"errors":["strategy must be one of the available strategies"]}
```

### 5.6 Restored, and cleaned up

The stack was recreated once more with `NANOFAAS_SCHEDULER_STRATEGY` empty, which is the state it was
built in, and answered `{"persistence":"restart","strategy":"per-function","available":["per-function","shared-queue"]}` —
the initial selection. The function registered for the load was then deleted
(`DELETE /v1/functions/word-stats-java` → `204`, `GET /v1/functions` → `[]`, no container left), and
the kept stack was released:

```bash
docker compose -f deploy/compose/compose.yaml -f /tmp/13c/switch-env.yaml -p nanofaas-validate down -v --remove-orphans
docker rm -f nanofaas-e2e-registry
```

`nanolab run … --teardown` is not the way back for this scenario — it answers
`Invalid value: --teardown requires a release scenario` — so the compose project and the registry
the `--keep` run held were released by hand. After the release no `nanofaas-validate` container,
volume or network remains, and the only container left running is the BuildKit container that was
already there before this task began.

### 5.7 The second pass: every admitted id must return *its own* result

The load in §5.2 repeated one payload, so "248/248 ids still resolve" proves the ids survived but
cannot distinguish one invocation's result from another's. A second bring-up of the same stack ran
the complementary pass: **eight distinct payloads of 3…10 words enqueued before a switch, then the
same eight sizes enqueued after it**, each id polled to a terminal status and its output compared
with the word count its own payload asks for. Sixteen for sixteen, in both halves, returned their
own result across the switch:

```
before 17e64fa5-… success expected=3  actual=3  OK
before 11a9a4a8-… success expected=4  actual=4  OK
…
before daf14ec1-… success expected=10 actual=10 OK
after  c4715f5b-… success expected=3  actual=3  OK
…
after  d6ea32d2-… success expected=10 actual=10 OK
before the switch: 8 of 8 OK
after  the switch: 8 of 8 OK
```

The switch itself, on that stack: `PATCH -> shared-queue http=200 revision=0->1 live=shared-queue`,
then back, `PATCH back -> per-function http=200 live=per-function`, with
`scheduler_switch_total{outcome="committed"} 2.0` and a worst `scheduler_switch_duration_seconds_max`
of `0.001170589`.

The first attempt at this pass is worth recording because it failed for an operational reason and not
a product one: it began the instant `docker compose up -d --wait` reported the control plane healthy,
and every request came back `Recv failure: Connection reset by peer` — compose's health check is on
the **management** port (8081), and the API port was not yet accepting. The retry waits for
`GET /v1/functions` to answer `200` on the API port itself (it took 3 s) and then ran clean. Anything
driving this stack by hand needs that gate, not compose's.

## 6. What this verified, and what it did not

**Verified on the container path**, against a real compose stack with both queue modules:

- the switch is reachable and effective in **both directions** over HTTP, without a restart and
  without draining the queue — six committed switches under live traffic;
- the live selection is what the namespace GET reports afterwards, and the revision lives in the
  root envelope and advances once per committed switch;
- no admitted async invocation was lost: 248/248 admitted with `202`, 248/248 still resolvable, and
  the 20 polled reached `success`; 163/163 sync invocations answered `200`;
- and each admitted invocation returned the result of **its own** payload across a switch — 16/16
  distinct payloads, 8 enqueued before the switch and 8 after, every one `success` with its own word
  count (§5.7);
- `409` on a stale revision and `422` on an unknown id;
- a committed switch **does not survive a restart** — the startup selection wins again, whether that
  selection is derived from an empty variable or named explicitly.

**Not verified here.** The procedure was not driven by a NanoLab workflow (§ the first section), so
it is not reproducible by running a scenario yet. Nothing in this file measures the switch's cost —
that is `RESULTS.md`'s subject — and nothing here is the ≥60-minute soak, which is deferred and
pending (`FINAL.md`).
