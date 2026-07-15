#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

./gradlew \
  :sdks:java:test \
  :sdks:java-lite:test \
  :functions:java:word-stats:test \
  :functions:java:word-stats-lite:test \
  :functions:java:json-transform:test \
  :functions:java:json-transform-lite:test \
  :functions:java:roman-numeral:test \
  :functions:java:roman-numeral-lite:test

GOCACHE="${GOCACHE:-$ROOT/build/go-cache}"
export GOCACHE
for directory in sdks/go functions/go/word-stats functions/go/json-transform functions/go/roman-numeral; do
  (cd "$directory" && go test ./...)
done

env PYTHONPATH=sdks/python/src uv run --project sdks/python pytest -q \
  sdks/python/tests \
  functions/python/word-stats/tests \
  functions/python/json-transform/tests \
  functions/python/roman-numeral/tests

npm --prefix sdks/javascript test
for directory in functions/javascript/word-stats functions/javascript/json-transform functions/javascript/roman-numeral; do
  (cd "$directory" && npm test)
done
