#!/bin/sh
#
# FILE-mode fixture: write back the INPUT_FILE/OUTPUT_FILE env vars into OUTPUT_FILE as JSON.
#

set -eu

in="${INPUT_FILE:-}"
out="${OUTPUT_FILE:-}"

if [ -z "$out" ]; then
  echo "OUTPUT_FILE not set" >&2
  exit 2
fi

# Minimal JSON (paths in tests don't include quotes/newlines).
printf '{"input":"%s","output":"%s"}' "$in" "$out" >"$out"

