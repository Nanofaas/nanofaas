#!/usr/bin/env bash
set -euo pipefail

input="$(jq -c '.input' 2>/dev/null || true)"
error() { jq -n --arg error "$1" '{__nanofaas_envelope__:true,output:{error:$error},statusCode:422}'; }

[[ "$(jq -r 'type' <<<"$input")" == "object" ]] || { error "Input must be a JSON object"; exit; }
jq -e 'has("text")' <<<"$input" >/dev/null || { error "missing required field: text"; exit; }
[[ "$(jq -r '.text | type' <<<"$input")" == "string" && -n "$(jq -r '.text' <<<"$input")" ]] || { error "field 'text' must be a non-empty string"; exit; }
text="$(jq -r '.text' <<<"$input")"
[[ "$(printf %s "$text" | wc -c | tr -d ' ')" -le 1024 ]] || { error "field 'text' must be at most 1024 UTF-8 bytes"; exit; }
size="$(jq -r '.size // 256' <<<"$input")"
[[ "$(jq -r '(.size // 256) | type' <<<"$input")" == "number" && "$size" =~ ^[0-9]+$ && "$size" -ge 128 && "$size" -le 1024 ]] || { error "field 'size' must be an integer between 128 and 1024"; exit; }

scale=$((size / 32))
png="$(qrencode -t PNG -s "$scale" -o - -- "$text" | base64 | tr -d '\n')"
jq -n --arg output "$png" '{__nanofaas_envelope__:true,output:$output,statusCode:200,headers:{"Content-Type":"image/png"},encoding:"base64"}'
