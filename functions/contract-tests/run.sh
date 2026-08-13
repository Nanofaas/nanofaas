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
  (cd "$directory" && npm ci && npm test)
done

for family in word-stats json-transform roman-numeral; do
  fixture="functions/test-data/$family/correctness.json"
  handler="functions/bash/$family/handler.sh"
  if [[ ! -f "$fixture" ]]; then
    echo "missing bash contract fixture: $fixture" >&2
    exit 1
  fi
  while IFS= read -r contract_case; do
    name="$(jq -r '.name' <<<"$contract_case")"
    input="$(jq -c '.input' <<<"$contract_case")"
    expected="$(jq -c '.expected' <<<"$contract_case")"
    expected_status="$(jq -r '.expectedStatusCode // empty' <<<"$contract_case")"
    actual="$(jq -cn --argjson input "$input" '{input:$input}' | bash "$handler")"
    if [[ -n "$expected_status" ]]; then
      if ! jq -en --argjson actual "$actual" --argjson expected "$expected" --argjson status "$expected_status" \
        '$actual.__nanofaas_envelope__ == true and $actual.statusCode == $status and $actual.output == $expected' >/dev/null; then
        echo "bash $family contract failed: $name" >&2
        echo "expected envelope: $(jq -cn --argjson output "$expected" --argjson status "$expected_status" '{__nanofaas_envelope__:true,output:$output,statusCode:$status}' | jq -cS .)" >&2
        echo "actual:            $(jq -cS . <<<"$actual")" >&2
        exit 1
      fi
      continue
    fi
    if ! jq -en --argjson actual "$actual" --argjson expected "$expected" \
      '$actual == $expected' >/dev/null; then
      echo "bash $family contract failed: $name" >&2
      echo "expected: $(jq -cS . <<<"$expected")" >&2
      echo "actual:   $(jq -cS . <<<"$actual")" >&2
      exit 1
    fi
  done < <(jq -c '.cases[]' "$fixture")
done
