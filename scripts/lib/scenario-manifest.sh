#!/usr/bin/env bash
set -euo pipefail

scenario_manifest_require() {
    if [[ -z "${NANOFAAS_SCENARIO_PATH:-}" ]]; then
        printf 'NANOFAAS_SCENARIO_PATH is required\n' >&2
        return 1
    fi
    if [[ ! -f "${NANOFAAS_SCENARIO_PATH}" ]]; then
        printf 'Scenario manifest not found: %s\n' "${NANOFAAS_SCENARIO_PATH}" >&2
        return 1
    fi
}

scenario_json_get() {
    scenario_manifest_require
    python3 - "${NANOFAAS_SCENARIO_PATH}" "$1" <<'PY'
import json
import sys

path = sys.argv[1]
field = sys.argv[2]
value = json.loads(open(path, encoding="utf-8").read())
for part in field.split("."):
    if part == "":
        continue
    if isinstance(value, list):
        value = value[int(part)]
    else:
        value = value.get(part)
        if value is None:
            raise SystemExit(1)

if isinstance(value, bool):
    print("true" if value else "false")
elif value is not None:
    print(value)
PY
}

scenario_selected_functions() {
    scenario_manifest_require
    python3 - "${NANOFAAS_SCENARIO_PATH}" <<'PY'
import json
import sys

payload = json.loads(open(sys.argv[1], encoding="utf-8").read())
for item in payload.get("functions", []):
    key = item.get("key")
    if key:
        print(key)
PY
}

scenario_selected_count() {
    local count
    count=$(scenario_selected_functions | sed '/^[[:space:]]*$/d' | wc -l | tr -d ' ')
    echo "${count:-0}"
}

scenario_require_single_function() {
    local count
    count=$(scenario_selected_count)
    if [[ "${count}" != "1" ]]; then
        printf 'Scenario requires exactly one selected function, got %s\n' "${count}" >&2
        return 1
    fi
}

scenario_first_function_key() {
    scenario_selected_functions | head -n 1
}

scenario_function_field() {
    scenario_manifest_require
    python3 - "${NANOFAAS_SCENARIO_PATH}" "$1" "$2" <<'PY'
import json
import sys

payload = json.loads(open(sys.argv[1], encoding="utf-8").read())
key = sys.argv[2]
field = sys.argv[3]

for item in payload.get("functions", []):
    if item.get("key") == key:
        value = item.get(field)
        if value is not None:
            print(value)
        raise SystemExit(0)

raise SystemExit(1)
PY
}

scenario_function_image() {
    scenario_function_field "$1" image
}

scenario_function_payload_path() {
    scenario_function_field "$1" payloadPath
}

scenario_function_example_dir() {
    scenario_function_field "$1" exampleDir
}

scenario_function_runtime() {
    scenario_function_field "$1" runtime
}

scenario_function_family() {
    scenario_function_field "$1" family
}

scenario_load_targets() {
    scenario_manifest_require
    python3 - "${NANOFAAS_SCENARIO_PATH}" <<'PY'
import json
import sys

payload = json.loads(open(sys.argv[1], encoding="utf-8").read())
for item in payload.get("load", {}).get("targets", []):
    if item:
        print(item)
PY
}

scenario_write_wrapped_input() {
    local payload_path=$1
    local destination=$2
    python3 - "${payload_path}" "${destination}" <<'PY'
import json
import sys
from pathlib import Path

payload_path = Path(sys.argv[1])
destination = Path(sys.argv[2])

with payload_path.open(encoding="utf-8") as handle:
    payload = json.load(handle)

destination.write_text(
    json.dumps({"input": payload}, separators=(",", ":")),
    encoding="utf-8",
)
PY
}
