# Control-plane Tooling (`controlplane-tool`)

Canonical project root: `tools/controlplane/`.

This package provides one control-plane tooling surface for milestone 5:

- `jar`
- `build`
- `run`
- `image`
- `native`
- `test`
- `inspect`
- `matrix`
- `vm up|sync|provision-base|provision-k3s|registry|down|inspect`
- `functions list|show|show-preset`
- `cli-test list|run|inspect`
- `e2e list|run|all`
- `loadtest list-profiles|show-profile|run|inspect`
- `tui`

## Local-first execution and optional Prefect deployments

Local execution stays the default. All supported flows can still run directly through the CLI/TUI without a Prefect API server or worker.

Optional remote-readiness artifacts now live alongside the local tooling:

- deployment metadata helper: `tools/controlplane/src/controlplane_tool/prefect_deployments.py`
- example Prefect config: `tools/controlplane/prefect.yaml`
- optional profile/scenario metadata blocks: `[prefect]`

The current example deployment is intentionally conservative: it wires `build.build` through a real entrypoint helper that resolves the catalog flow from serializable parameters and then executes it through the same local runtime facade.

The intended split is:

- local developer runs: use `scripts/controlplane.sh ...` or `controlplane-tool ...`
- optional remote orchestration: reuse the same flow IDs and task metadata through the Prefect helper/config when you decide to wire a work pool later

This is deliberately non-mandatory. If no remote Prefect setup exists, nothing changes in the local execution path.

## Recommended entrypoints

Use the canonical wrapper for orchestration across build, VM, and E2E flows:

```bash
scripts/controlplane.sh building --profile core --dry-run
scripts/controlplane.sh functions list
scripts/controlplane.sh functions show-preset demo-java
scripts/controlplane.sh functions show-preset demo-javascript
scripts/controlplane.sh functions show-preset demo-loadtest
scripts/controlplane.sh vm up --lifecycle multipass --name nanofaas-e2e --dry-run
scripts/controlplane.sh cli-test list
scripts/controlplane.sh cli-test run vm --saved-profile demo-java --dry-run
scripts/controlplane.sh cli-test run cli-stack --saved-profile demo-java --dry-run
scripts/controlplane.sh cli-test run cli-stack --saved-profile demo-javascript --dry-run
scripts/controlplane.sh cli-test run host-platform --saved-profile demo-java --dry-run
scripts/controlplane.sh cli-test run deploy-host --function-preset demo-java --dry-run
scripts/controlplane.sh e2e run k3s-junit-curl --function-preset demo-java --dry-run
scripts/controlplane.sh e2e run k3s-junit-curl --function-preset demo-javascript --dry-run
scripts/controlplane.sh e2e run helm-stack --dry-run
scripts/controlplane.sh e2e run --scenario-file tools/controlplane/scenarios/k8s-demo-java.toml --dry-run
scripts/controlplane.sh e2e run k3s-junit-curl --saved-profile demo-java --dry-run
scripts/controlplane.sh e2e all --only k3s-junit-curl --dry-run
scripts/controlplane.sh loadtest list-profiles
scripts/controlplane.sh loadtest show-profile quick
scripts/controlplane.sh loadtest run --scenario-file tools/controlplane/scenarios/k8s-demo-java.toml --load-profile quick --dry-run
scripts/controlplane.sh loadtest run --saved-profile demo-java --dry-run
scripts/controlplane.sh loadtest inspect --saved-profile demo-java
scripts/e2e-loadtest.sh --profile demo-java --dry-run
```

`scripts/controlplane.sh loadtest run ...` is the first-class load generation surface. `scripts/e2e-loadtest.sh` is intentionally narrower: it is a compatibility wrapper over `experiments/e2e-loadtest.sh` for the legacy Helm/Grafana/parity workflow, and registry-only summary flags such as `--summary-only` belong to `scripts/e2e-loadtest-registry.sh`.

For VM-backed E2E runs, the tool resolves the actual VM host for Ansible/SSH operations and treats `e2e all` as one shared VM session with one final teardown point. Use `--no-cleanup-vm` to preserve a Multipass VM after the run; external VM lifecycle mode is always preserved.

Use the canonical wrapper for saved-profile / interactive runs as well:

```bash
scripts/controlplane.sh tui
```

The interactive TUI owns profile selection and profile creation. Use the `Profiles` section to inspect saved profiles and the `Load Testing` / `Validation` sections to consume them.
Within `Validation -> platform -> k3s-junit-curl`, the TUI can now reuse the built-in default selection, a function preset such as `demo-javascript`, a scenario manifest such as `tools/controlplane/scenarios/k8s-demo-javascript.toml`, or a compatible saved profile such as `demo-javascript`.
The TUI only offers saved profiles and scenario manifests compatible with `k3s-junit-curl`; incompatible entries are filtered out instead of failing at execution time.
The same generalized selection model is available at `Validation -> cli -> cli-stack`, `Validation -> host -> deploy-host`, and `Validation -> platform -> container-local`.
`cli-stack` and `deploy-host` accept built-in defaults, compatible presets, scenario files, and saved profiles. `container-local` supports single function selection, compatible single-function scenario files, and compatible single-function saved profiles.
`helm-stack` remains excluded from this selector path because its runtime allowlist intentionally omits JavaScript for the compatibility workflow.

In the live workflow view, the left pane is plan-ordered top-level phases only. nested work is separate detail, not peer phases, so verification substeps stay attached under the active phase instead of becoming new rows.

`loadtest run` is the canonical surface for load generation and Prometheus validation. The top-level `scripts/e2e-loadtest.sh` wrapper stays separate because it still models the legacy experimental workflow rather than the generic `loadtest run` planner.

Operational Ansible assets are canonical under `ops/ansible/`.

## Profiles and overrides

Built-in profiles map to module selectors:

- `core` -> `none`
- `k8s` -> `k8s-deployment-provider`
- `container-local` -> `container-deployment-provider`
- `all` -> `all`

Use `--modules <csv|none|all>` when you need to override the profile-derived selector.

## Function and scenario selection

Function selection is first-class in the tool:

- inspect the built-in catalog with `scripts/controlplane.sh functions list`
- inspect one function with `scripts/controlplane.sh functions show word-stats-java`
- inspect one preset with `scripts/controlplane.sh functions show-preset demo-java`
- reuse TOML scenarios from `tools/controlplane/scenarios/`
- reuse saved defaults from `tools/controlplane/profiles/<profile>.toml`

`e2e run` accepts one of:

- `--function-preset <name>`
- `--functions <csv>`
- `--scenario-file <path>`
- `--saved-profile <name>`

Selection precedence is:

1. explicit CLI override (`--function-preset` or `--functions`)
2. scenario file
3. saved profile defaults

When a CLI override is combined with `--scenario-file` or `--saved-profile`, the tool preserves the base scenario metadata (`invoke`, payload mapping, namespace, and `load.profile`) and narrows `load.targets` and payloads to the selected subset instead of rebuilding the scenario from scratch.

`controlplane_tool.scenario.selection_resolution` owns shared scenario/profile
selection primitives used by CLI surfaces: CSV parsing, workspace-relative
scenario paths, explicit selection detection, construction from
`ScenarioSelectionConfig`, and overlaying runtime/namespace/registry or
function-selection overrides onto an existing scenario. Command modules keep
request construction, validation, and rendering local to their command surface.
Use `controlplane_tool.scenario.scenario_loader` directly only for lower-level
scenario manifest loading and raw scenario overlay operations.

Loadtest saved profiles can also persist:

- `loadtest.default_load_profile`
- `loadtest.metrics_gate_mode`
- `loadtest.scenario_file` or `loadtest.function_preset`

CLI validation saved profiles can also persist:

- `cli_test.default_scenario`

That lets the same saved profile drive `scripts/controlplane.sh cli-test run --saved-profile <name>` without repeating the scenario name on the command line.
`k3s-junit-curl`, `helm-stack`, and `cli-stack` are the self-bootstrapping VM-backed scenarios. When no explicit VM request is provided, the controlplane tool creates and configures a managed VM and installs scenario-specific software inside that VM instead of requiring host-installed Helm, kubectl, k3s, local-registry tooling, or `nanofaas-cli`.
`cli-stack` is the canonical VM-backed CLI stack scenario: it compiles the CLI in the VM, installs Helm, k3s, and the local registry there, then validates function build/push/apply/invoke/enqueue/delete together with `platform install/status/uninstall`. `host-platform` is intentionally platform-only, so saved-profile runtime and namespace defaults still apply there but function selections do not. `vm` preserves the legacy in-VM CLI validation path, `deploy-host` builds, pushes, and registers every selected function on the host, and missing saved profiles or scenario files fail validation with exit code 2.

Scenario defaults are scenario-aware:

- `container-local` is intentionally single-function; multi-function presets such as `demo-java` are rejected before the backend starts.
- `helm-stack` defaults to `demo-loadtest`, which excludes Go functions because the Helm/loadtest compatibility backend does not exercise Go.
- `k3s-junit-curl` consumes the full resolved function selection in manifest mode instead of silently collapsing to the first entry.
- unsupported selections such as `scripts/controlplane.sh e2e run helm-stack --functions word-stats-go --dry-run` fail in CLI validation before the backend starts.

For `k3s-junit-curl`, the resolved manifest is not only rendered in dry-run output. The real VM command now passes `-Dnanofaas.e2e.scenarioManifest=...` into `K8sE2eTest`, so the selected functions and payloads are consumed inside the VM execution path.

Examples:

```bash
scripts/controlplane.sh e2e run container-local --functions word-stats-java --dry-run
scripts/controlplane.sh e2e run k3s-junit-curl --function-preset demo-java --dry-run
scripts/controlplane.sh e2e run helm-stack --dry-run
scripts/controlplane.sh e2e run helm-stack --functions word-stats-java,json-transform-java --dry-run
scripts/controlplane.sh e2e run --scenario-file tools/controlplane/scenarios/k8s-demo-java.toml --dry-run
scripts/controlplane.sh e2e run --scenario-file tools/controlplane/scenarios/k8s-demo-java.toml --functions word-stats-java --dry-run
scripts/controlplane.sh e2e run k3s-junit-curl --saved-profile demo-java --dry-run
```

## Artifacts

Loadtest runs write profiles and reports under:

- `tools/controlplane/profiles/<profile>.toml`
- `tools/controlplane/scenarios/<scenario>.toml`
- `tools/controlplane/runs/<timestamp>-<profile>/summary.json`
- `tools/controlplane/runs/<timestamp>-<profile>/report.html`

## Metrics and k6 notes

- Prometheus URL is not requested in the wizard.
- The wizard stores loadtest defaults without inventing a separate execution semantic.
- The compatibility metrics flow auto-registers the `tool-metrics-echo` fixture before k6.
- The metrics flow verifies `demo-word-stats-deployment` in `DEPLOYMENT` mode.
- The metrics flow auto-starts a mock Kubernetes API backend when needed.
- The metrics flow auto-starts a tool-managed control-plane runtime when needed.
- Strict metric gating can be enabled with `strict_required = true`.

## Package architecture checks

The Python package is split into semantic packages under `controlplane_tool/`.
`controlplane_tool.app` is reserved for entrypoints; shared path discovery, settings,
and saved-profile persistence live under `controlplane_tool.workspace`.
Use the import contracts as the hard boundary check:

```bash
uv run lint-imports
```

Use the coupling report to inspect package cohesion and cross-package imports:

```bash
uv run controlplane-package-report
```

Use `pydeps` for a visual graph when investigating a dependency tangle:

```bash
uv run pydeps controlplane_tool --show-deps --max-bacon 2
```

Use GitNexus before moving public symbols or changing flow-level dependencies:

```text
gitnexus_context({name: "LoadtestRunner"})
gitnexus_impact({target: "LoadtestRunner", direction: "upstream"})
gitnexus_detect_changes({scope: "staged"})
```

## Advanced

Raw Gradle commands remain available for low-level workflows, but the wrapper-based UX above is the primary path for milestone 3.
