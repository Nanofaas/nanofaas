# CLI Control-Plane Preflight Design

## Goal

Prevent a `workflow: cli` run against the local provider from starting any workflow task when the expected control plane is absent or unhealthy. The behavior must be shared by the command-line interface and the TUI, while `plan` remains usable offline.

## Scope

This iteration checks one prerequisite only: the local control plane required by a CLI workflow. Registry availability, image existence, and remote-provider prerequisites remain future scenario-specific checks.

## Design

Add a small shared preflight function in the controlplane tool's CLI/application layer. It receives the validated scenario, environment, and effective control-plane URL.

The function is a no-op unless both conditions hold:

- `scenario.workflow == "cli"`
- `environment.provider == "local"`

For that combination it requests `<control-plane-url>/actuator/health` with a short timeout and accepts only a successful JSON response whose `status` is `UP`. Connection failures, HTTP errors, malformed responses, and non-`UP` states raise one actionable preflight error containing the checked endpoint and the local startup command.

The CLI invokes the preflight after configuration validation but before workflow construction and `workflow.run()`. The TUI invokes the same function after previewing the plan but before opening the live workflow dashboard; on failure it displays a normal static error screen. Neither path starts a build or any other workflow task after a failed preflight.

`plan` and TUI plan preview do not invoke preflights because they must remain offline operations.

The effective CLI endpoint is also passed into `build_cli_plan`; this keeps the preflight target and the endpoint used by generated CLI tasks consistent when `--control-plane-url` is supplied.

## Future Checks

Future scenarios may add checks to the shared dispatch point when they depend on externally supplied resources, such as a registry, a prebuilt image, cluster access, or cloud credentials. A workflow must not preflight an artifact that one of its own tasks is responsible for creating.

## Testing

Tests cover:

- no-op behavior outside local CLI scenarios;
- healthy, unreachable, malformed, and unhealthy control-plane responses;
- CLI abort before workflow construction/run;
- CLI custom endpoint consistency;
- TUI abort before the live workflow starts;
- unchanged offline `plan` behavior.
