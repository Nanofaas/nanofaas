# Function test data

Each function family owns one correctness corpus and three static performance
corpora. Corpus entries contain raw function input; transport-specific envelopes
are added by the caller.

| Family | small | medium | large | Profile meaning |
| --- | ---: | ---: | ---: | --- |
| word-stats | 100 | 5,000 | 50,000 | words per input, four cases |
| json-transform | 10 | 500 | 5,000 | records per input, five operations |
| roman-numeral | 8 | 64 | 3,999 | unique values across 1–3999 |
| qr-code | — | — | — | correctness-only PNG QR response envelope |

Roman `small` emphasizes boundaries and subtractive notation, `medium` is
stratified over the complete interval, and `large` exhausts the valid domain.

Regenerate and verify performance corpora from the repository root:

```bash
python3 experiments/generate-payload-corpora.py
python3 experiments/generate-payload-corpora.py --check
```

The generator is deterministic, uses only the Python standard library, and also
derives the three control-plane catalog samples from the first `small` case.
See `docs/loadtest-payload-profile.md` for the schema and k6 usage.
