# Handler Response Envelope Examples and fn-init Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Complete #194 by making examples demonstrate response-envelope errors and a useful binary response, then teaching the same minimal pattern through every `fn-init` scaffold.

**Architecture:** Keep normal success values plain. Existing validation failures use each SDK's nominal response-envelope type (or the watchdog marker in Bash). Add `qr-code` in all five languages: it generates a PNG QR code, returns it as base64, and sets `Content-Type: image/png`.

**Tech Stack:** ZXing (Java), `qrcode[pil]` (Python), `github.com/skip2/go-qrcode` (Go), `qrcode` (Node), `qrencode` (Bash), plus existing JUnit/pytest/go test/node:test/shell gates.

---

## Shared contract

- Input: `{ "text": string, "size"?: integer }`; text is non-empty and <=1024 UTF-8 bytes, size defaults to 256 and is in `[128,1024]`.
- Successful QR response: status 200, `Content-Type: image/png`, `encoding: "base64"`, output bytes with PNG signature `89504e470d0a1a0a`.
- Invalid QR and Roman inputs return 422; invalid json-transform inputs return 400. Normal JSON/Roman successes stay plain.
- Do not compare PNG bytes across libraries; decode and check the signature instead.

### Task 1: Convert current Roman and json-transform validation examples

**Files:** Roman handlers/tests in `functions/{go,javascript,bash}/roman-numeral`; all five json-transform handlers/tests; `functions/test-data/json-transform/correctness.json`.

1. Add failing assertions that each invalid corpus case returns the language's envelope and its status (Roman 422, transform 400); update JSON-transform fixture invalid cases with `expectedStatusCode: 400`. Bash checks the watchdog marker object.
2. Run each focused test and confirm red because error branches currently return plain objects.
3. Replace only validation branches with `HandlerResponse` / `NewHandlerResponse` / `HandlerResponse.of` or the Bash marker. Preserve every ordinary success return.
4. Run `./functions/contract-tests/run.sh` and all focused tests.
5. Commit: `feat: expose validation status in example functions`.

### Task 2: Add `qr-code` in all five languages

**Files:** Create standard function directories under `functions/{java,python,go,javascript,bash}/qr-code`; create `functions/test-data/qr-code/correctness.json`; extend `functions/contract-tests/run.sh` and `functions/test-data/README.md`.

1. Write failing handler tests for valid URL, missing/empty/non-string text and invalid size. Success asserts 200/envelope/content type/base64/PNG signature; invalid asserts 422. Add the Bash contract loop and its fixture before implementation.
2. Run focused tests and confirm they fail because the family is absent.
3. Implement the contract with native libraries: ZXing writer + `MatrixToImageWriter`; Python `qrcode[pil]` via `BytesIO`; Go `qrcode.Encode(..., qrcode.Medium, size)`; Node `QRCode.toBuffer(..., { width: size, errorCorrectionLevel: "M" })`; Bash `qrencode -t PNG -s <scale> -o - -- "$text"`. Bash Docker uses Debian bookworm slim with `bash`, `jq`, `qrencode` and the copied watchdog binary.
4. Run focused suites and `./functions/contract-tests/run.sh`.
5. Commit: `feat: add cross-language QR code example`.

### Task 3: Teach envelopes in every fn-init template

**Files:** Handler and handler-test templates for Java, Python, Go, JavaScript and Bash; `tools/fn-init/tests/test_generator.py` and `test_javascript_scaffold_contract.py` as necessary.

1. Add failing generated-scaffold assertions that the missing `text` branch uses the correct 422 envelope idiom: Java `HandlerResponse.of`, Python/JS `HandlerResponse`, Go `nanofaas.NewHandlerResponse`, Bash marker object.
2. Run `uv run --project tools/fn-init pytest tools/fn-init/tests -q` and confirm red.
3. Change only the invalid-input template branch and its generated test. Keep `{result:"ok"}` as a plain success and do not introduce QR dependencies.
4. Rerun the fn-init suite.
5. Commit: `feat: teach response envelopes in fn-init templates`.

### Task 4: Final verification

1. Run `./functions/contract-tests/run.sh`, watchdog `cargo test && cargo clippy -- -D warnings`, SDK Go/JS/Python suites, and `uv run --project tools/fn-init pytest tools/fn-init/tests -q`.
2. Run `gitnexus_detect_changes()` and ensure changes are confined to #194 functions, fixtures, templates, tests, README and this plan.
3. Commit documentation if Task 2 did not include it: `docs: describe QR code response envelope example`.
