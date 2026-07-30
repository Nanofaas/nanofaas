#!/usr/bin/env bash

# Parse input.
if ! word="$(jq -er '.input')"; then
    jq -n '{error:"Invalid input JSON."}'
    exit 1
fi

# Process input.
if ! output="$(figlet "$word")"; then
    jq -n '{error:"figlet failed."}'
    exit 1
fi

# Output response as JSON (required).
jq -n --arg output "$output" '{output: $output}'
