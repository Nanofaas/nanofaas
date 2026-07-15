# Static Function Payload Corpora Design

**Date:** 2026-07-15

## Objective

Provide every function family with shared, reproducible payloads for correctness and performance testing. Java, Java Lite, Python, Go, JavaScript, and exec/bash must consume the same family-owned inputs so runtime comparisons measure implementations rather than different workloads.

## Current State

Correctness fixtures exist under `functions/contract-tests`, but exec/bash does not consume them. The function catalog has sample payloads for `word-stats` and `json-transform`; `roman-numeral` has none and validation therefore falls back to an invalid empty input.

The k6 experiments already provide invocation helpers, response checks, payload-size metrics, and load profiles. They generate bounded word and JSON inputs at runtime, however, and cover only part of the runtime/family matrix. There are no explicit payload-size profiles named `small`, `medium`, and `large`.

## Chosen Architecture

Payload ownership moves to `functions/test-data/<family>/`. Each family contains one correctness corpus and three performance corpora:

```text
functions/test-data/
  word-stats/
    correctness.json
    performance-small.json
    performance-medium.json
    performance-large.json
  json-transform/
    correctness.json
    performance-small.json
    performance-medium.json
    performance-large.json
  roman-numeral/
    correctness.json
    performance-small.json
    performance-medium.json
    performance-large.json
```

Corpus entries contain raw function input only. HTTP envelopes, function names, and runtime identifiers remain outside the corpus. `experiments/k6/common.js` adds the nanoFaaS invocation envelope and records its serialized size.

Versioned corpus files are static during a benchmark. A deterministic Python generator based only on the standard library creates the larger files, while a validator checks the committed output. This avoids hand-maintained bulk JSON without reintroducing runtime generation.

## Corpus Scale

The profiles represent meaningful work rather than arbitrary file padding:

| Family | Cases per profile | Small | Medium | Large |
|---|---:|---:|---:|---:|
| `word-stats` | 4 | about 100 words per input | about 5,000 words | about 50,000 words |
| `json-transform` | 5 | 10 records per input | 500 records | 5,000 records |
| `roman-numeral` | variable | 8 canonical values | 64 stratified values | all values from 1 through 3999 |

For `word-stats` and `json-transform`, profile growth increases per-invocation work. For `roman-numeral`, a request always contains one integer, so profile growth intentionally increases corpus diversity rather than payload size. This distinction must be documented in benchmark output.

Four word cases provide enough variation in vocabulary, punctuation, case, and frequency distribution without duplicating large blobs unnecessarily. Five JSON cases provide exactly one case for each of `count`, `sum`, `avg`, `min`, and `max`. Performance corpora contain valid inputs only; invalid and boundary cases belong to correctness corpora.

## k6 Data Flow

A single `experiments/k6/function-benchmark.js` replaces family/runtime wrapper scripts. It accepts:

```text
NANOFAAS_FUNCTION=<deployed function name>
NANOFAAS_FAMILY=word-stats|json-transform|roman-numeral
K6_PAYLOAD_PROFILE=small|medium|large
K6_PAYLOAD_SELECTION=sequential|random
```

The script loads the selected static corpus during k6 initialization, selects cases by iteration, wraps the raw input as `{ "input": ... }`, invokes the configured function, and uses a family-specific minimal response predicate. Sync execution must return the expected output shape and no error. Async execution must return an execution identifier.

`run-all.sh` expands the complete matrix of three families and six runtimes. Its default remains the `small` profile. Running all three profiles must require an explicit option so routine smoke checks do not accidentally launch 54 benchmark runs.

## Validation and Failure Handling

Corpus validation fails before load generation when a file is absent, malformed, empty, duplicated, assigned to the wrong family/profile, or outside its declared scale. It also verifies that generated files match fresh deterministic output. Catalog samples are generated or verified from canonical small inputs so they cannot drift from the shared corpus.

k6 fails during initialization for an unknown family, profile, selection mode, or empty case list. During execution, HTTP success alone is insufficient: response checks require `wordCount`, `groups`, or `roman`, depending on the family. This prevents fast error responses from contaminating performance results.

## Verification

Completion requires:

1. All twelve family corpus files validate and regenerate without a diff.
2. Correctness fixtures run against all six runtimes, including exec/bash.
3. Node tests cover corpus loading, sequential/random selection, and configuration errors.
4. A k6 smoke run can invoke at least one configured implementation for every family and profile.
5. `run-all.sh` contains all 18 family/runtime combinations and defaults to `small`.
6. All 18 catalog entries resolve a valid default payload, including `roman-numeral`.

Load stages, VUs, rates, and durations remain independent of corpus profiles. No new benchmark framework or third-party dependency is introduced.
