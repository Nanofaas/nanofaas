#!/usr/bin/env bash
#
# nanoFaaS roman-numeral handler
#
# Reads InvocationRequest JSON from stdin and writes handler output JSON to stdout.
#

set -euo pipefail

req="$(cat)"

jq '
  .input as $in
  | if ($in | type) != "object" then
      {"error": "Input must be a JSON object"}
    elif ($in | has("number") | not) then
      {"error": "missing required field: number"}
    elif ($in.number | type) != "number" then
      {"error": "field '\''number'\'' must be an integer"}
    elif ($in.number < 1 or $in.number > 3999) then
      {"error": ("number must be between 1 and 3999, got: " + ($in.number | tostring))}
    else
      ($in.number | floor) as $n
      | [
          {v:1000,s:"M"},{v:900,s:"CM"},{v:500,s:"D"},{v:400,s:"CD"},
          {v:100,s:"C"},{v:90,s:"XC"},{v:50,s:"L"},{v:40,s:"XL"},
          {v:10,s:"X"},{v:9,s:"IX"},{v:5,s:"V"},{v:4,s:"IV"},{v:1,s:"I"}
        ] as $table
      | reduce $table[] as $entry (
          {n: $n, roman: ""};
          until(.n < $entry.v; {n: (.n - $entry.v), roman: (.roman + $entry.s)})
        )
      | {roman: .roman}
    end
' <<<"$req"
