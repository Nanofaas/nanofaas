# V2 Direction: SDK Hardening

## Summary

This direction improves the quality bar of the JavaScript runtime itself. V1 proved the basic model:
embedded HTTP runtime, examples, scaffolding, and docs. V2 hardening would make the SDK more robust,
more predictable under failure, and easier to support long term.

## Why It Matters

An SDK can be feature-complete on paper and still feel fragile in practice. Hardening work reduces the
risk of subtle runtime bugs, confusing error behavior, and support churn once real users begin relying on
the JavaScript path for non-trivial functions.

## Main Scope

- strengthen runtime contract coverage around malformed requests, timeout races, callbacks, and concurrency
- improve logging, metrics, and error reporting consistency
- review the API surface for anything that is awkward or underspecified before it becomes harder to change
- expand compatibility and regression testing for clean installs, scaffolded projects, and Docker builds

## Success Criteria

- the SDK has a clearer and more stable contract
- the main operational behaviors are covered by stronger automated tests
- clean-install, generated-project, and container-build paths are reliable and documented

## Out of Scope

- controlplane preset integration
- npm publication and release automation
- support for a broader runtime model such as CommonJS or browser execution
