# Function test data

Each function family owns one correctness corpus and three static performance
corpora. Corpus entries contain raw function input; transport-specific envelopes
are added by the caller.

Regenerate and verify performance corpora from the repository root:

```bash
python3 experiments/generate-payload-corpora.py
python3 experiments/generate-payload-corpora.py --check
```

The generator is deterministic and uses only the Python standard library.
