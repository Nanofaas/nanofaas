#!/usr/bin/env python3
"""
Mock STDIO handler for testing STDIO mode.
Reads JSON from stdin, writes JSON to stdout.
"""

import json
import os
import subprocess
import sys
import time

SCENARIO = os.environ.get("TEST_SCENARIO", "success")
DELAY_MS = int(os.environ.get("DELAY_MS", "0"))


def main():
    # Read input from stdin
    try:
        input_data = json.load(sys.stdin)
    except json.JSONDecodeError:
        input_data = None

    # Simulate processing delay
    if DELAY_MS > 0:
        time.sleep(DELAY_MS / 1000)

    if SCENARIO == "success":
        # Echo with uppercase transformation
        result = {
            "output": input_data.get("input", "").upper() if isinstance(input_data, dict) and isinstance(input_data.get("input"), str) else input_data
        }
        json.dump(result, sys.stdout)

    elif SCENARIO == "echo":
        # Pure echo
        json.dump(input_data, sys.stdout)

    elif SCENARIO == "error_exit":
        print("Error occurred!", file=sys.stderr)
        sys.exit(1)

    elif SCENARIO == "error_exit_42":
        print("Exit code 42!", file=sys.stderr)
        sys.exit(42)

    elif SCENARIO == "invalid_json":
        # Write invalid JSON
        sys.stdout.write("not valid json {{{")

    elif SCENARIO == "empty_output":
        # Write nothing
        pass

    elif SCENARIO == "hang":
        # Hang forever
        time.sleep(3600)

    elif SCENARIO == "spawn_child_hang":
        handler_pid_file = os.environ.get("HANDLER_PID_FILE")
        if handler_pid_file:
            with open(handler_pid_file, "w", encoding="utf-8") as pid_file:
                pid_file.write(str(os.getpid()))
        child = subprocess.Popen(["sleep", "3600"])
        child_pid_file = os.environ.get("CHILD_PID_FILE")
        if child_pid_file:
            with open(child_pid_file, "w", encoding="utf-8") as pid_file:
                pid_file.write(str(child.pid))
        time.sleep(3600)

    elif SCENARIO == "stderr_output":
        # Write to both stdout and stderr
        print("This goes to stderr", file=sys.stderr)
        json.dump({"result": "ok"}, sys.stdout)

    elif SCENARIO == "large_output":
        # Large output
        json.dump({"data": "x" * 100000}, sys.stdout)  # 100KB (exercises buffering without stalling CI)

    elif SCENARIO == "binary_output":
        # Binary output (non-UTF8)
        sys.stdout.buffer.write(b"\x80\x81\x82")

    elif SCENARIO == "multi_json":
        # Multiple JSON objects (only first should be read)
        json.dump({"first": True}, sys.stdout)
        json.dump({"second": True}, sys.stdout)

    elif SCENARIO == "crash":
        # Crash with segfault-like behavior
        os._exit(139)

    else:
        json.dump({"scenario": SCENARIO, "input": input_data}, sys.stdout)


if __name__ == "__main__":
    main()
