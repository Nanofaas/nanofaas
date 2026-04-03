# Control-plane Tooling (`controlplane-tool`)

Canonical project root: `tools/controlplane/`.

This package provides one control-plane tooling surface for milestone 1:

- `build`
- `run`
- `image`
- `native`
- `test`
- `inspect`
- `pipeline-run`
- `tui`

## Recommended entrypoints

Use the thin wrapper for non-interactive control-plane actions:

```bash
scripts/control-plane-build.sh build --profile core --dry-run
scripts/control-plane-build.sh image --profile all --dry-run
scripts/control-plane-build.sh test --profile k8s -- --tests '*CoreDefaultsTest'
scripts/control-plane-build.sh inspect --profile container-local --dry-run
```

Use the compatibility wrapper for the saved-profile / interactive pipeline flow:

```bash
scripts/controlplane-tool.sh --profile-name dev
scripts/controlplane-tool.sh --profile-name dev --use-saved-profile
```

## Profiles and overrides

Built-in profiles map to module selectors:

- `core` -> `none`
- `k8s` -> `k8s-deployment-provider`
- `container-local` -> `container-deployment-provider`
- `all` -> `all`

Use `--modules <csv|none|all>` when you need to override the profile-derived selector.

## Artifacts

Pipeline runs write profiles and reports under:

- `tools/controlplane/profiles/<profile>.toml`
- `tools/controlplane/runs/<timestamp>-<profile>/summary.json`
- `tools/controlplane/runs/<timestamp>-<profile>/report.html`

## Metrics and k6 notes

- Prometheus URL is not requested in the wizard.
- The metrics flow auto-registers the `tool-metrics-echo` fixture before k6.
- The metrics flow verifies `demo-word-stats-deployment` in `DEPLOYMENT` mode.
- The metrics flow auto-starts a mock Kubernetes API backend when needed.
- The metrics flow auto-starts a tool-managed control-plane runtime when needed.
- Strict metric gating can be enabled with `strict_required = true`.

## Advanced

Raw Gradle commands remain available for low-level workflows, but the wrapper-based UX above is the primary path for milestone 1.
