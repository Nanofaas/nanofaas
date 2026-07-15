# Cross-SDK function contracts

The JSON fixtures under `functions/test-data/<family>/correctness.json` define
shared inputs and outputs for the three reference function families. Every
implementation consumes the same family-owned files from its native test suite.
These fixtures test semantic correctness; the adjacent `performance-*.json`
files provide static benchmark inputs and deliberately omit expected outputs.

| SDK | word-stats | json-transform | roman-numeral |
| --- | --- | --- | --- |
| Java | yes | yes | yes |
| Java Lite | yes | yes | yes |
| Go | yes | yes | yes |
| Python | yes | yes | yes |
| JavaScript | yes | yes | yes |
| exec/bash | yes | yes | yes |

Run the complete SDK and 18-function parity gate from the repository root:

```bash
./functions/contract-tests/run.sh
```

The gate runs the native contract suites for Java, Java Lite, Go, Python,
JavaScript, and exec/bash. Benchmark matrix validation is separate because it
measures payload selection and load behavior rather than contract outputs.
