#!/usr/bin/env bash
set -euo pipefail
request="$(cat)"
jq -cn --arg body "$(jq -r '.input.message // ""' <<<"$request")" --arg header "$(jq -r '.headers["x-e2e-token"] // ""' <<<"$request")" '{body:$body,header:$header}'
