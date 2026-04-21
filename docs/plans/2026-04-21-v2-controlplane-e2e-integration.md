# V2 Direction: Controlplane and E2E Integration

## Summary

This direction takes JavaScript from "authoring workflow supported" to "platform workflow supported".
Today the repository can scaffold, build, test, and containerize JavaScript functions, but the VM-backed
`tools/controlplane` flows still treat JavaScript as out of scope. V2 would close that gap.

## Why It Matters

Without this step, JavaScript remains a local developer convenience rather than a first-class runtime in
the broader NanoFaaS product surface. Users can write a function, but they cannot consistently select it
in the same scenario catalogs, dry-run flows, or VM-backed validation paths used by the other supported
languages.

## Main Scope

- add JavaScript demos or presets to `tools/controlplane` catalogs
- allow JavaScript selection in scenario manifests and saved profiles
- make the relevant smoke and E2E flows build and deploy JavaScript examples
- extend user-facing docs so the controlplane tool treats JavaScript as a normal option

## Success Criteria

- JavaScript appears as a valid runtime choice in the controlplane tool
- at least one VM-backed end-to-end path can build and run a JavaScript function
- dry-run output, docs, and test coverage all reflect the new support

## Out of Scope

- npm publication strategy
- release automation for the SDK package
- major API redesign of the JavaScript runtime
