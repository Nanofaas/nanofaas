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
      {"error":"Input must be a JSON object"}
    else
      ($in.data) as $data
      | ($in.groupBy) as $groupBy
      | ($in.operation // "count") as $op
      | ($in.valueField) as $vf
      | if ($data == null or $groupBy == null) then
          {"error":"Fields '\''data'\'' and '\''groupBy'\'' are required"}
        elif ($op != "count" and ($vf == null or ($vf|tostring|length) == 0)) then
          {"error":("Field '\''valueField'\'' is required for operation: " + ($op|tostring))}
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

