# SDK Function Parity Design

## Goal

Make the Java, Java Lite, Go, Python, and JavaScript SDKs expose the same runtime contract and ship the same three reference functions: `word-stats`, `json-transform`, and `roman-numeral`.

## Current state

The reference-function matrix is incomplete: Java Lite and JavaScript do not provide `roman-numeral`. JavaScript's `json-transform` implements field renaming while every other runtime and the shared catalog payload implement grouped aggregation. Runtime behavior also differs in callback payloads and retry semantics, request validation, timeouts, dispatch-attempt propagation, cold-start metadata, and metric names.

## Options considered

1. **Canonical contracts with native implementations (recommended).** Keep one language-neutral set of payload fixtures and expected responses, then implement the small handler and runtime adapter natively in each SDK. This preserves idiomatic APIs while giving CI a single definition of parity.
2. **Generate all implementations from a shared schema.** This reduces repetition but introduces a generator and cross-language templates for fewer than a dozen small behaviors. The maintenance cost is higher than the duplicated, straightforward handlers.
3. **Treat each SDK example as independent.** This requires the least immediate work but preserves the current failure mode where catalog payloads can be incompatible with the selected runtime.

Option 1 is the smallest approach that prevents drift.

## Architecture

Parity has two layers. The function layer defines the three reference families and validates them with shared fixtures. Each runtime may use idiomatic handler signatures, but identical input must produce semantically identical JSON. The SDK layer defines `/invoke`, `/health`, `/metrics`, execution and trace context, timeout behavior, callback shape, retry classification, `X-Dispatch-Attempt` propagation, cold-start headers, and a common metric vocabulary.

`NANOFAAS_HANDLER_TIMEOUT` is a positive integer number of milliseconds in every SDK. Go also accepts its previous duration-string format (for example `30s`) for backward compatibility.

`fn-init` remains the source of scaffold structure. JavaScript uses its native template. Java Lite currently has no dedicated template, so the Java scaffold is reduced to the existing `NanofaasRuntime.builder()` pattern; adding a new `fn-init` language is deferred until another Lite function is requested.

## Error handling and compatibility

Handlers return structured error objects for domain validation, matching the existing examples. Runtime transport failures retain HTTP error semantics and callback error codes. Callback JSON must always match the control-plane `InvocationResult` shape: `success`, `output`, and `error`. Permanent callback `4xx` responses are not retried except `408` and `429`.

## Verification

Each new function gets focused unit tests plus `happy-path.json` and `missing-input.json` contract payloads. A later parity gate will execute the shared fixtures against every implementation. SDK changes are delivered in independent, TDD-sized slices so existing known JavaScript validation failures cannot hide regressions in the new examples.
