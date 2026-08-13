#!/usr/bin/env bash
#
# nanoFaaS bash example: json-transform
#
# Reads InvocationRequest JSON from stdin and writes handler output JSON to stdout.
#

set -euo pipefail

req="$(cat)"

jq '
  .input as $in
  | if ($in | type) != "object" then
      {"__nanofaas_envelope__":true,"output":{"error":"Input must be a JSON object"},"statusCode":400}
    else
      ($in.data) as $data
      | ($in.groupBy) as $groupBy
      | ($in.operation // "count") as $op
      | ($in.valueField) as $vf
      | if ($data == null or $groupBy == null) then
          {"__nanofaas_envelope__":true,"output":{"error":"Fields '\''data'\'' (array) and '\''groupBy'\'' (string) are required"},"statusCode":400}
        elif ($op != "count" and ($vf == null or ($vf|tostring|length) == 0)) then
          {"__nanofaas_envelope__":true,"output":{"error":("Field '\''valueField'\'' is required for operation: " + ($op|tostring))},"statusCode":400}
        else
          (reduce ($data[]? ) as $item ({}; .[(($item[$groupBy] // "null")|tostring)] += [$item])) as $grouped
          | ($grouped | with_entries(
              .value as $items
              | .value = (
                  if $op == "count" then
                    ($items|length)
                  else
                    ($items | map(.[$vf]) | map(select(.!=null))) as $vals
                    | if ($vals|length) == 0 then 0
                      elif $op == "sum" then ($vals|add)
                      elif $op == "avg" then (($vals|add) / ($vals|length))
                      elif $op == "min" then ($vals|min)
                      elif $op == "max" then ($vals|max)
                      else ("unknown operation: " + ($op|tostring))
                      end
                  end
                )
            )) as $groups
          | {groupBy:$groupBy, operation:$op, groups:$groups}
        end
    end
' <<<"$req"
