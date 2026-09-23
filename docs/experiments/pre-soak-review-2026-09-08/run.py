"""Compile reviewed production sources and run the diagnostic reproductions.

These checks assert the defective behavior observed on 1d9e2f55. They are audit
evidence, not regression tests whose passing means the product is correct.
"""
import argparse
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--classpath-file", type=Path)
args = parser.parse_args()
here = Path(__file__).resolve().parent
root = here.parents[2]
classpath_file = args.classpath_file or (
    root / "docs/experiments/control-plane-tuning-2026-09/.classpath-control-plane"
)
if not classpath_file.exists():
    parser.error("Provide --classpath-file; see README.md for the Gradle command.")
classpath = classpath_file.read_text().strip()
sources = []
for module in ["platform/common", "platform/workload-metrics", "platform/control-plane"]:
    sources.extend(str(p) for p in (root / module / "src/main/java").rglob("*.java"))
for name in ["ContainerLocalDeploymentProvider", "ContainerLocalProperties", "ContainerRuntimeAdapter",
             "ContainerInstanceSpec", "ManagedContainer", "ManagedFunctionProxy", "ManagedFunctionProxyFactory",
             "EndpointProbe", "PortAllocator"]:
    sources.append(str(next((root / "platform/modules/container-deployment-provider/src/main/java").rglob(name + ".java"))))
print("Revision:", subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(), flush=True)
print("Production source files compiled:", len(sources), flush=True)
with tempfile.TemporaryDirectory(prefix="nanofaas-review-0908-") as output:
    compile_result = subprocess.run(["javac", "-cp", classpath, "-d", output, *sources, str(here / "Audit.java")])
    if compile_result.returncode:
        raise SystemExit(compile_result.returncode)
    result = subprocess.run(["java", "-Xmx256m", "-cp", output + ":" + classpath,
                             "it.unimib.datai.nanofaas.controlplane.execution.Audit"])
    raise SystemExit(result.returncode)
