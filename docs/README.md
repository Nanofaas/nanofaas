# NanoFaaS documentation

## Get started

| Doc | What it covers |
| --- | --- |
| [Quickstart](quickstart.md) | Build the platform and CLI, provision and validate a platform |
| [Tutorial: writing a function](tutorial-function.md) | End-to-end walkthrough: scaffold, handler, tests, deploy, invoke, contract tests (Java / Python / JavaScript) |
| [Local development](local.md) | How to run and develop the control plane, nanofaas-cli, and functions locally |

## Guides

| Doc | What it covers |
| --- | --- |
| [CLI guide](nanofaas-cli.md) | `nanofaas` commands, payloads, and `deploy` behavior |
| [Function definition](function-definition.md) | `function.yaml` manifest reference |
| [Example functions](example-function.md) | Worked examples for every execution mode + real examples in the repo |
| [Container-only validation](no-k8s-profile.md) | Run the platform without Kubernetes (`container-local` backend) |
| [E2E tutorial](e2e-tutorial.md) | Validation environments and scenarios (container, Multipass, SSH, load test) |

## Architecture

| Doc | What it covers |
| --- | --- |
| [Control-plane](control-plane.md) | Control-plane overview |
| [Function pod architecture](function-pod-architecture.md) | Watchdog, runtimes, and managed deployment model |
| [Kubernetes deployment](k8s.md) | k8s backend: resources, HPA, labels, secrets |
| [Observability](observability.md) | Metrics, PromQL queries, health, logging, tracing |

## Operations

| Doc | What it covers |
| --- | --- |
| [Testing](testing.md) | Test layers and commands |
| [SonarQube analysis](sonarqube.md) | On-demand local analysis via `scripts/sonar.sh` |
| [Image releases](operations/image-releases.md) | The 52-cell matrix, tag policy, and paid Azure release flow |
| [Release performance](performance/history.md) | Per-release benchmark records (also under `performance/releases/`) |

## Reference

| Doc | What it covers |
| --- | --- |
| [SLO and performance targets](slo.md) | Observed targets and how to measure them |
| [Load-test payload profiles](loadtest-payload-profile.md) | Corpus layout, generation, and the k6 matrix |

## Historical

`plans/` and `superpowers/plans/` hold dated design and implementation plans.
They are the design record of past work — do not edit them for freshness; if a
plan contradicts this documentation, this documentation wins.
