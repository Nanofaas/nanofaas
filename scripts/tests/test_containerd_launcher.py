"""Exercise the real rootless control-plane launcher with an inert nsenter shim."""

import json
import os
import subprocess
import sys
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
LAUNCHER = REPO / "deploy/containerd-rootless/start-control-plane.sh"
SERVICE = REPO / "deploy/containerd-rootless/nanofaas.service"


def _executable(path: Path, body: str) -> None:
    path.write_text(f"#!{sys.executable}\n{body}")
    path.chmod(0o755)


def _run(tmp_path: Path, mode: str = "jvm", artifact: str | None = None,
         extra_env: dict[str, str] | None = None, child_pid: str | None = "12345\n"
         ) -> tuple[subprocess.CompletedProcess[str], dict, dict]:
    home = tmp_path / "home"
    runtime = tmp_path / "runtime"
    root = tmp_path / "checkout"
    bin_dir = tmp_path / "bin"
    for directory in (home, runtime / "containerd-rootless", root, bin_dir):
        directory.mkdir(parents=True)
    if child_pid is not None:
        (runtime / "containerd-rootless/child_pid").write_text(child_pid)
    default_jar = root / "platform/control-plane/build/libs/app.jar"
    default_jar.parent.mkdir(parents=True)
    default_jar.write_bytes(b"jar")
    final = tmp_path / "final.json"
    entered = tmp_path / "nsenter.json"
    _executable(bin_dir / "nsenter", """
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
Path(os.environ['ENTERED']).write_text(json.dumps(args))
os.execvpe(args[args.index('--') + 1], args[args.index('--') + 1:], os.environ)
""")
    recorder = """
import json, os, sys
from pathlib import Path
Path(os.environ['FINAL']).write_text(json.dumps({
    'argv': sys.argv, 'cwd': os.getcwd(),
    'home': os.environ.get('HOME'), 'xdg': os.environ.get('XDG_RUNTIME_DIR'),
    'socket': os.environ.get('NANOFAAS_CONTAINERD_SOCKETPATH'),
    'state': os.environ.get('NANOFAAS_CONTAINERD_STATEDIRECTORY'),
    'java_options': os.environ.get('JAVA_TOOL_OPTIONS'),
}))
"""
    _executable(bin_dir / "java", recorder)
    native = tmp_path / "native-control-plane"
    _executable(native, recorder)
    env = os.environ | {
        "PATH": f"{bin_dir}:{os.environ['PATH']}",
        "NANOFAAS_ROOT": str(root), "HOME": str(home),
        "XDG_RUNTIME_DIR": str(runtime), "ENTERED": str(entered), "FINAL": str(final),
        "JAVA_TOOL_OPTIONS": "-Xmx256m",
        "NANOFAAS_CONTROL_PLANE_MODE": mode,
        "NANOFAAS_CONTAINERD_SOCKETPATH": str(runtime / "containerd/containerd.sock"),
        "NANOFAAS_CONTAINERD_CNIPLUGINDIRECTORY": str(tmp_path / "plugins"),
        "NANOFAAS_CONTAINERD_CNICONFIGDIRECTORY": str(tmp_path / "config"),
        "NANOFAAS_CONTAINERD_CNICACHEDIRECTORY": str(tmp_path / "cache"),
        "NANOFAAS_CONTAINERD_STATEDIRECTORY": str(tmp_path / "state"),
    }
    if artifact == "native":
        env["NANOFAAS_CONTROL_PLANE_ARTIFACT"] = str(native)
    elif artifact is not None:
        env["NANOFAAS_CONTROL_PLANE_ARTIFACT"] = artifact
    else:
        env.pop("NANOFAAS_CONTROL_PLANE_ARTIFACT", None)
    env.update(extra_env or {})
    result = subprocess.run([str(LAUNCHER)], env=env, cwd=tmp_path,
                            capture_output=True, text=True, check=False)
    return result, json.loads(entered.read_text()) if entered.exists() else {}, \
        json.loads(final.read_text()) if final.exists() else {}


def test_jvm_enters_namespaces_and_runs_from_checkout_with_preserved_environment(tmp_path: Path) -> None:
    result, entered, final = _run(tmp_path, extra_env={"NANOFAAS_CONTROL_PLANE_MODE": ""})
    assert result.returncode == 0, result.stderr
    assert entered == ["-t", "12345", "-U", "--preserve-credentials", "-n", "-m", "--",
                       "env", f"HOME={tmp_path / 'home'}", f"XDG_RUNTIME_DIR={tmp_path / 'runtime'}",
                       "java", "--enable-native-access=ALL-UNNAMED",
                       f"-Duser.home={tmp_path / 'home'}", "-jar",
                       str(tmp_path / "checkout/platform/control-plane/build/libs/app.jar")]
    assert final == {
        "argv": [str(tmp_path / "bin/java"), "--enable-native-access=ALL-UNNAMED",
                 f"-Duser.home={tmp_path / 'home'}", "-jar",
                 str(tmp_path / "checkout/platform/control-plane/build/libs/app.jar")],
        "cwd": str(tmp_path / "checkout"), "home": str(tmp_path / "home"),
        "xdg": str(tmp_path / "runtime"),
        "socket": str(tmp_path / "runtime/containerd/containerd.sock"),
        "state": str(tmp_path / "state"), "java_options": "-Xmx256m",
    }


def test_native_uses_explicit_artifact_and_passes_options_as_argv(tmp_path: Path) -> None:
    result, entered, final = _run(tmp_path, "native", "native",
                                  {"NANOFAAS_CONTROL_PLANE_NATIVE_ARGS": "-Xmx256m --expr=$(id)"})
    assert result.returncode == 0, result.stderr
    assert entered[-3:] == [str(tmp_path / "native-control-plane"), "-Xmx256m", "--expr=$(id)"]
    assert final["argv"] == entered[-3:]
    assert final["cwd"] == str(tmp_path / "checkout")
    assert final["home"] == str(tmp_path / "home")
    assert final["xdg"] == str(tmp_path / "runtime")
    assert final["socket"] == str(tmp_path / "runtime/containerd/containerd.sock")


def test_invalid_configuration_fails_before_nsenter(tmp_path: Path) -> None:
    cases = [
        ("unknown", None, {}, "mode"),
        ("native", None, {}, "artifact"),
        ("jvm", "relative.jar", {}, "absolute"),
        ("jvm", "/does/not/exist.jar", {}, "artifact"),
        ("jvm", None, {"HOME": "relative"}, "HOME"),
        ("jvm", None, {"NANOFAAS_CONTAINERD_STATEDIRECTORY": "relative"}, "STATEDIRECTORY"),
        ("native", "native", {"NANOFAAS_CONTROL_PLANE_NATIVE_ARGS": "--flag\n--hidden"}, "NATIVE_ARGS"),
    ]
    for index, (mode, artifact, extra_env, error) in enumerate(cases):
        run_dir = tmp_path / str(index)
        run_dir.mkdir()
        result, entered, final = _run(run_dir, mode, artifact, extra_env)
        assert result.returncode != 0 and error.lower() in result.stderr.lower(), (mode, result.stderr)
        assert entered == {} and final == {}


def test_missing_or_malformed_rootless_child_pid_fails_before_nsenter(tmp_path: Path) -> None:
    for value in (None, "not-a-pid\n", "0\n"):
        run_dir = tmp_path / ("missing" if value is None else value.strip())
        run_dir.mkdir()
        result, entered, final = _run(run_dir, child_pid=value)
        assert result.returncode != 0 and "child PID" in result.stderr
        assert entered == {} and final == {}


def test_service_template_is_foreground_and_scoped_to_each_run() -> None:
    service = SERVICE.read_text()
    assert "EnvironmentFile=@NANOLAB_ENV_FILE@" in service
    assert "WorkingDirectory=@NANOFAAS_ROOT@" in service
    assert "ExecStart=@NANOFAAS_ROOT@/deploy/containerd-rootless/start-control-plane.sh" in service
    assert "CPUQuota=" not in service and "MemoryMax=" not in service
