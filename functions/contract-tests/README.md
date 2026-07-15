# Cross-SDK function contracts

The JSON fixtures in this directory define shared inputs and outputs for the
three reference function families. Every implementation consumes these files
from its native test suite.

| SDK | word-stats | json-transform | roman-numeral |
| --- | --- | --- | --- |
| Java | yes | yes | yes |
| Java Lite | yes | yes | yes |
| Go | yes | yes | yes |
| Python | yes | yes | yes |
| JavaScript | yes | yes | yes |

Run the complete SDK and 15-function parity gate from the repository root:

```bash
./functions/contract-tests/run.sh
```
