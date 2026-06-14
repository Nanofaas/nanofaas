#!/usr/bin/env bash
#
# nanoFaaS bash example: word-stats
#
# Reads InvocationRequest JSON from stdin:
#   {"input": {"text":"...", "topN":3}, "metadata": {...}}
# Writes handler output JSON to stdout.
#

set -euo pipefail

req="$(cat)"

# Extract input; allow string input.
input_type="$(jq -r '.input | type' <<<"$req" 2>/dev/null || echo "null")"

if [[ "$input_type" == "string" ]]; then
  text="$(jq -r '.input' <<<"$req")"
  top_n=10
elif [[ "$input_type" == "object" ]]; then
  text="$(jq -r '.input.text // empty' <<<"$req")"
  top_n="$(jq -r '.input.topN // 10' <<<"$req")"
else
  jq -n --arg msg "Field 'text' is required" '{error:$msg}'
  exit 0
fi

if [[ -z "${text}" ]]; then
  jq -n --arg msg "Text is empty" '{error:$msg}'
  exit 0
fi

tmp_words="$(mktemp)"
trap 'rm -f "$tmp_words"' EXIT

# Normalize: lowercase, split on non-alnum.
printf "%s" "$text" \
  | tr '[:upper:]' '[:lower:]' \
  | tr -cs '[:alnum:]' '\n' \
  | awk 'NF' >"$tmp_words"

word_count="$(wc -l <"$tmp_words" | tr -d ' ')"
if [[ "${word_count}" == "0" ]]; then
  jq -n --arg msg "No words found" '{error:$msg}'
  exit 0
fi

unique_words="$(sort -u "$tmp_words" | wc -l | tr -d ' ')"

avg_len="$(awk '{sum+=length($0)} END { if (NR>0) printf "%.2f", sum/NR; else printf "0.0" }' "$tmp_words")"

top_lines="$(awk '{c[$0]++} END { for (w in c) print c[w] "\t" w }' "$tmp_words" | sort -nr | head -n "$top_n")"
top_words_json="$(printf "%s\n" "$top_lines" | jq -R -s '
  split("\n")
  | map(select(length>0))
  | map(split("\t") | {word: .[1], count: (.[0] | tonumber)})
')"

jq -n \
  --argjson wordCount "$word_count" \
  --argjson uniqueWords "$unique_words" \
  --argjson topWords "$top_words_json" \
  --argjson averageWordLength "$avg_len" \
  '{wordCount:$wordCount, uniqueWords:$uniqueWords, topWords:$topWords, averageWordLength:$averageWordLength}'

