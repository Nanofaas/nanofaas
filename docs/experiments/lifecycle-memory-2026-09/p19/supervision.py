"""Bounded ownership of a fresh runner session, including orphaned descendants."""
import json
import os
import pathlib
import signal
import subprocess
import time


def live_group(pgid):
    live = []
    for path in pathlib.Path('/proc').glob('[0-9]*/stat'):
        try:
            fields = path.read_text().split(') ')[1].split()
            if int(fields[2]) == pgid and fields[0] not in ['Z', 'X']:
                live.append(int(path.parent.name))
        except (OSError, ValueError, IndexError):
            continue
    return live


def supervise(command, record_path, timeout, grace=25):
    record_path = pathlib.Path(record_path)
    record_path.parent.mkdir(parents=True, exist_ok=True)
    record = dict(command=command, timeout_s=timeout, grace_s=grace, status='starting', kill_sent=False,
                  started_ns=time.monotonic_ns())
    process = None
    try:
        process = subprocess.Popen(command, start_new_session=True)
        record['pid'] = record['owned_pgid'] = process.pid
        try:
            process.wait(timeout=timeout)
            record['status'] = 'exited'
        except subprocess.TimeoutExpired:
            record['status'] = 'timeout'
    except BaseException as error:
        record.update(status='failed', failure=repr(error))
        raise
    finally:
        if process is not None:
            try:
                os.killpg(process.pid, signal.SIGTERM)
                deadline = time.monotonic() + grace
                while time.monotonic() < deadline:
                    process.poll()
                    try:
                        os.killpg(process.pid, 0)
                    except ProcessLookupError:
                        break
                    time.sleep(.02)
                else:
                    os.killpg(process.pid, signal.SIGKILL)
                    record['kill_sent'] = True
            except ProcessLookupError:
                pass
            process.wait(timeout=5)
            record['exit_code'] = process.returncode
            deadline = time.monotonic() + 5
            while live_group(process.pid) and time.monotonic() < deadline:
                time.sleep(.02)
            record['remaining_live_pids'] = live_group(process.pid)
            if record['remaining_live_pids']:
                record['status'] = 'cleanup_failed'
        record['ended_ns'] = time.monotonic_ns()
        record_path.write_text(json.dumps(record, sort_keys=True, indent=2) + '\n')
    return record
