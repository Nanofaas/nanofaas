#!/bin/bash
#
# Mock FILE handler for testing FILE mode.
# Reads from INPUT_FILE, writes to OUTPUT_FILE.
#

set -e

SCENARIO="${TEST_SCENARIO:-success}"
DELAY_MS="${DELAY_MS:-0}"

# Get input/output paths from environment
INPUT="${INPUT_FILE:-/tmp/input.json}"
OUTPUT="${OUTPUT_FILE:-/tmp/output.json}"

# Debug output
echo "FILE handler: scenario=$SCENARIO, input=$INPUT, output=$OUTPUT" >&2

# Simulate processing delay
if [ "$DELAY_MS" -gt 0 ]; then
    sleep "$(echo "scale=3; $DELAY_MS / 1000" | bc)"
fi

case "$SCENARIO" in
    success)
        # Read input, transform, write output
        if command -v jq &> /dev/null; then
            jq '{output: .input | ascii_upcase}' "$INPUT" > "$OUTPUT"
        else
            # Fallback without jq
            cat "$INPUT" | sed 's/"input"/"output"/' > "$OUTPUT"
        fi
        ;;

    echo)
        # Pure copy
        cp "$INPUT" "$OUTPUT"
        ;;

    error_exit)
        echo "Error occurred!" >&2
        exit 1
        ;;

    error_exit_42)
        echo "Exit code 42!" >&2
        exit 42
        ;;

    no_output)
        # Don't create output file
        ;;

    empty_output)
        # Create empty output file
        touch "$OUTPUT"
        ;;

    invalid_json)
        # Write invalid JSON
        echo "not valid json {{{" > "$OUTPUT"
        ;;

    hang)
        # Hang forever
        sleep 3600
        ;;

    spawn_child_hang)
        printf '%s\n' "$$" > "$HANDLER_PID_FILE"
        sleep 3600 &
        printf '%s\n' "$!" > "$CHILD_PID_FILE"
        wait
        ;;

    large_output)
        # Large output file
        echo '{"data": "' > "$OUTPUT"
        head -c 1000000 /dev/zero | tr '\0' 'x' >> "$OUTPUT"
        echo '"}' >> "$OUTPUT"
        ;;

    permission_denied)
        # Try to write to unwritable location
        echo '{"result": "ok"}' > /root/output.json 2>/dev/null || exit 1
        ;;

    crash)
        # Simulate crash
        kill -9 $$
        ;;

    modify_input)
        # Modify input file (shouldn't affect watchdog)
        echo '{"modified": true}' > "$INPUT"
        echo '{"result": "ok"}' > "$OUTPUT"
        ;;

    *)
        echo "{\"scenario\": \"$SCENARIO\"}" > "$OUTPUT"
        ;;
esac

echo "FILE handler: completed successfully" >&2
