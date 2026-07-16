# TUI Provider Discovery Design

## Problem

The control-plane supports `local`, `multipass`, `external`, `azure`, and
`proxmox` environments, but the TUI intentionally lists only executable
`*.yaml` files. Azure and Proxmox ship only as `.yaml.example` templates, so
they disappear from the Environment picker until the user manually copies and
configures them. The README mentions the copy step, but the TUI does not expose
that requirement where the user encounters it.

## Design

Keep executable environments and provider setup entries distinct. The
Environment picker continues to pass only concrete `*.yaml` files to
`_environment()` and workflow execution. When the conventional
`azure.yaml` or `proxmox.yaml` file is absent but its template exists, the same
picker also shows `Azure (setup required)` or `Proxmox (setup required)`.

Selecting a setup entry renders one branded static screen with the exact copy
command, the fields that must be reviewed, and the provider-specific credential
requirement. After acknowledgement it returns to the Environment picker and
rebuilds the choices, so a configuration created in another terminal becomes
available without restarting the TUI.

The TUI must never execute a `.yaml.example` file, copy it automatically, edit
credentials, or persist secrets. Azure setup points to `az login`, provider
values, and `ssh_key_path`. Proxmox setup points to host/node/template/key
values and the environment variable named by `password_env`.

If a conventional concrete file exists, its normal environment entry replaces
the corresponding setup entry. Other user-created `*.yaml` files remain
available exactly as today.

## Error Handling and Chrome

Missing templates do not create broken setup entries. Setup guidance uses the
existing `_show_static()` lifecycle: clear, render one shared NANOFAAS header,
TTY-aware acknowledgement, and clear before returning. Back, Esc, and Ctrl+C
retain their existing navigation semantics.

## Documentation and Tests

README and quickstart will explicitly state that `.example` files are templates,
not executable environments, and describe the setup-required entries.

Regression tests will prove that:

- Azure and Proxmox setup entries appear when only templates exist;
- selecting either entry renders the correct guidance and returns to Environment;
- concrete `azure.yaml`/`proxmox.yaml` files replace their setup entries;
- `.yaml.example` paths never reach `_environment()` or workflow execution;
- setup screens retain the single-header invariant and existing navigation.

