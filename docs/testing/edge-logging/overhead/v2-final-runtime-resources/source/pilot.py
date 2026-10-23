#!/usr/bin/env python3
"""Three logging off/on pairs on six owned heterogeneous Multipass VMs.

This is an observer interference pilot, not a one-shot algorithm experiment.
Uses the existing NanoLab/Sonata VM/bootstrap/collector lifecycles.
"""

import argparse
import hashlib
import json
import subprocess
import time
from pathlib import Path
from uuid import uuid4

import httpx
from nanolab.config import EnvironmentConfig
from nanolab.config.one_shot import OneShotConfig
from nanolab.one_shot.infrastructure import build_one_shot_resources
from nanolab.plans.one_shot_common import _bootstrap
from nanolab.tasks.one_shot.logging import backend_resource, collector_resource
from sonata_engine import Task, TaskOutcome, Workflow
from sonata_tasks.compensation import compensated_resource

SIZES = [
    ("edge-1", "edge", 1, 2048, 0.35, 1),
    ("edge-2", "edge", 1, 3072, 0.5, 2),
    ("edge-3", "edge", 2, 3072, 0.6, 2),
    ("edge-4", "edge", 2, 4096, 0.7, 3),
    ("edge-5", "edge", 3, 6144, 0.85, 3),
    ("cloud", "cloud", 8, 12288, 1.0, 4),
]
CP_IMAGE = "nanofaas/edge-logging-control-plane:20261010"
FN_IMAGE = "nanofaas/edge-logging-workload:20261010-final"
K6 = r"""import http from "k6/http";
import {Counter,Trend} from "k6/metrics";
import {sleep} from "k6";
import execution from "k6/execution";
const generated=new Counter("generated"), completed=new Counter("completed"), duration=new Trend("node_latency",true);
const nodes=JSON.parse(__ENV.NODES),seconds=Number(__ENV.SECONDS);
export const options={scenarios:Object.fromEntries(nodes.map(n=>[n.id,{executor:"constant-arrival-rate",rate:n.rate,timeUnit:"1s",duration:seconds+"s",preAllocatedVUs:16,maxVUs:32,exec:"invoke",env:{NODE:JSON.stringify(n)}}])),
summaryTrendStats:["avg","med","p(95)"],thresholds:Object.fromEntries(nodes.map(n=>["node_latency{node:"+n.id+"}",[]]))};
export function invoke(){const n=JSON.parse(__ENV.NODE);sleep(((execution.scenario.iterationInTest*37+7)%101)/1000);generated.add(1);
const response=http.post(n.url+"/v1/functions/logging-work:invoke",JSON.stringify({input:{iterations:20000000,working_set_bytes:4096,seed:7}}),{headers:{"Content-Type":"application/json","X-Trace-Id":"logging-overhead"},timeout:"5s",tags:{node:n.id}});
duration.add(response.timings.duration,{node:n.id});if(response.status===200 && response.json("status")==="success" && /"output"\s*:\s*(\d+)\s*[,}]/.exec(response.body)?.[1]===__ENV.EXPECTED_OUTPUT)completed.add(1);}
export function handleSummary(data){return {[__ENV.SUMMARY]:JSON.stringify(data,null,2)};}
"""


def response_checksum(body: str) -> str:
    result = json.loads(body)
    output = result.get("output")
    if (
        result.get("status") != "success"
        or type(output) is not int
        or not 0 <= output <= 2**64 - 1
    ):
        raise ValueError("workload did not return a successful u64 checksum")
    return str(output)


def accounting_valid(result: dict) -> bool:
    # k6 may schedule a boundary iteration (confirmed by the local HTTP probe).
    # Preserve nominal and actual counts; reject deficits, drops and failed outcomes.
    return (
        not result["generatorDeficit"]
        and not result["dropped"]
        and result["completed"] == result["sent"]
    )


# Docker's cumulative counters bracket the load, including backend CPU and block I/O.
COUNTERS = r"""import http.client,json,socket,sys,urllib.parse
class Docker(http.client.HTTPConnection):
 def connect(self):
  self.sock=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM);self.sock.settimeout(10);self.sock.connect('/var/run/docker.sock')
def get(path):
 c=Docker('localhost');c.request('GET',path);r=c.getresponse();body=r.read();c.close()
 if r.status!=200:raise RuntimeError('Docker API '+str(r.status))
 return json.loads(body)
filters=json.loads(sys.argv[1]);result={}
for c in get('/containers/json?filters='+urllib.parse.quote(json.dumps(filters))):
 s=get('/containers/'+c['Id']+'/stats?stream=false&one-shot=true');io=s.get('blkio_stats',{}).get('io_service_bytes_recursive') or []
 result[c['Id']]={'name':c['Names'][0],'cpuNs':s['cpu_stats']['cpu_usage']['total_usage'],'memoryBytes':s['memory_stats'].get('usage',0),'readBytes':sum(x['value'] for x in io if x['op'].lower()=='read'),'writeBytes':sum(x['value'] for x in io if x['op'].lower()=='write')}
print(json.dumps(result))
"""


def resource_delta(before: dict, after: dict, *, elapsed_seconds: float) -> dict:
    if elapsed_seconds <= 0 or before.keys() != after.keys() or not before:
        raise ValueError("resource counter interval/containers changed")
    result = {}
    for key, start in before.items():
        end = after[key]
        changes = {
            field: end[field] - start[field]
            for field in ["cpuNs", "readBytes", "writeBytes"]
        }
        if any(value < 0 for value in changes.values()):
            raise ValueError("resource counter reset")
        result[key] = {
            **changes,
            "name": end.get("name", key),
            "meanCores": changes["cpuNs"] / (elapsed_seconds * 1e9),
            "memoryStartBytes": start["memoryBytes"],
            "memoryEndBytes": end["memoryBytes"],
        }
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--nanofaas", type=Path, required=True)
    parser.add_argument("--images", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--host-address", required=True)
    parser.add_argument("--loki-port", type=int, default=43100)
    parser.add_argument("--seconds", type=int, default=60)
    args = parser.parse_args()
    if args.seconds < 60:
        raise ValueError("at least 60 seconds per measured window")
    args.output.mkdir(parents=True, exist_ok=True)
    token = uuid4().hex[:10]
    cp_name = "nl-logcp-" + token
    fn_name = "nl-logfn-" + token

    def remove_owned(node, name):
        present = node.command(
            ("docker", "ps", "-aq", "--filter", "name=^/" + name + "$")
        ).stdout.strip()
        if not present:
            return
        inspected = node.command(("docker", "inspect", name))
        metadata = json.loads(inspected.stdout)[0]
        if (
            metadata["Config"].get("Labels", {}).get("nanolab.logging-overhead")
            != token
        ):
            raise RuntimeError(
                "refuse removing container without matching run ownership"
            )
        node.command(("docker", "rm", "-f", name))

    settings = OneShotConfig.model_validate(
        {
            "provider": "multipass",
            "purpose": "workflow-validation",
            "nodes": [
                {
                    "id": name,
                    "kind": kind,
                    "cpus": cpus,
                    "memoryMiB": memory,
                    "memoryCapacityMiB": 256,
                }
                for name, kind, cpus, memory, _, _ in SIZES
            ],
            "functions": {
                "work": {"input": {}, "cpu": 0.35, "memoryMiB": 256, "maxReplicas": 1}
            },
            "observability": {
                "logs": {
                    "enabled": True,
                    "endpoint": f"http://{args.host_address}:{args.loki_port}/loki/api/v1/push",
                    "backendOwned": True,
                    "backendBind": args.host_address,
                    "campaignId": "logging-overhead",
                }
            },
        }
    )
    environment = EnvironmentConfig.model_validate(
        {"provider": "multipass", "roles": {"stack": {"name": "logging-overhead"}}}
    )
    topology = build_one_shot_resources(
        settings, environment, run_id=token, repo_root=args.nanofaas
    )
    backend = backend_resource(
        settings.observability.logs, root=args.nanofaas, run_dir=args.output
    )
    bootstraps = {
        key: _bootstrap(node, args.nanofaas) for key, node in topology.nodes.items()
    }
    quotas = {name: quota for name, _, _, _, quota, _ in SIZES}
    rates = {name: rate for name, _, _, _, _, rate in SIZES}

    def runtime_resource(node):
        def release(_inputs, _value=None):
            for name in [cp_name, fn_name]:
                remove_owned(node, name)

        def acquire(inputs):
            vm = inputs.resource(node.vm)
            destination = vm.home + "/logging-images.tar"
            staged = node.provider.transfer_to(
                node.request, source=args.images, destination=destination
            )
            if staged.return_code:
                raise RuntimeError("image transfer failed")
            node.command(("docker", "load", "-i", destination))
            return vm.host

        return compensated_resource(
            title="Stage " + node.config.id + " native runtimes",
            acquire=acquire,
            release=release,
            compensate=release,
            requires=(node.vm, bootstraps[node.config.id]),
        )

    runtimes = {key: runtime_resource(node) for key, node in topology.nodes.items()}
    script = args.output / "load.js"
    script.write_text(K6)
    results = []
    owned = []

    class Window(Task[dict]):
        def __init__(self, index, on):
            self.index, self.on = index, on
            self.title = f"Measure logging window {index} {on}"

        def run(self, inputs):
            number = self.index
            on = self.on
            directory = args.output / f"window-{number}"
            directory.mkdir(exist_ok=True)
            urls = []
            for key, node in topology.nodes.items():
                host = inputs.resource(runtimes[key])
                for name in [cp_name, fn_name]:
                    remove_owned(node, name)
                fmt = "json" if on else "text"
                node.command(
                    (
                        "docker",
                        "run",
                        "-d",
                        "--name",
                        cp_name,
                        "--label",
                        "nanolab.logging-overhead=" + token,
                        "--network",
                        "host",
                        "--cpus",
                        ".25",
                        "--memory",
                        "512m",
                        "--log-driver",
                        "json-file",
                        "--log-opt",
                        "max-size=10m",
                        "--log-opt",
                        "max-file=3",
                        "-e",
                        "NANOFAAS_LOG_FORMAT=" + fmt,
                        CP_IMAGE,
                        "--server.port=8080",
                        "--management.server.port=9090",
                        "--logging.level.root=WARN",
                        "--spring.main.banner-mode=off",
                        "--nanofaas.registry.path=/tmp/functions.json",
                    )
                )
                node.command(
                    (
                        "docker",
                        "run",
                        "-d",
                        "--name",
                        fn_name,
                        "--label",
                        "nanolab.logging-overhead=" + token,
                        "--network",
                        "host",
                        "--cpus",
                        str(quotas[key]),
                        "--memory",
                        "256m",
                        "--log-driver",
                        "json-file",
                        "--log-opt",
                        "max-size=10m",
                        "--log-opt",
                        "max-file=3",
                        "-e",
                        "PORT=8085",
                        "-e",
                        "FUNCTION_NAME=logging-work",
                        "-e",
                        "NANOFAAS_ONE_SHOT_PROFILE=true",
                        "-e",
                        "NANOFAAS_LOG_FORMAT=" + fmt,
                        "-e",
                        "CALLBACK_URL=http://127.0.0.1:8080/v1/internal/executions",
                        FN_IMAGE,
                    )
                )
                url = f"http://{host}:8080"
                with httpx.Client(trust_env=False) as http:
                    deadline = time.monotonic() + 60
                    while time.monotonic() < deadline:
                        try:
                            http.get(
                                url + "/v1/functions", timeout=1
                            ).raise_for_status()
                            http.get(
                                f"http://{host}:8085/health", timeout=1
                            ).raise_for_status()
                            break
                        except httpx.HTTPError:
                            time.sleep(0.2)
                    else:
                        raise RuntimeError("runtime readiness")
                    http.post(
                        url + "/v1/functions",
                        json={
                            "name": "logging-work",
                            "image": "fixture",
                            "executionMode": "EXTERNAL",
                            "endpointUrl": "http://127.0.0.1:8085/invoke",
                            "timeoutMs": 5000,
                            "concurrency": 1,
                            "queueSize": 20,
                            "maxRetries": 0,
                        },
                        timeout=3,
                    ).raise_for_status()
                    expected = None
                    for _ in range(4):
                        response = http.post(
                            url + "/v1/functions/logging-work:invoke",
                            json={
                                "input": {
                                    "iterations": 20000000,
                                    "working_set_bytes": 4096,
                                    "seed": 7,
                                }
                            },
                            timeout=10,
                        )
                        response.raise_for_status()
                        (directory / (key + "-warmup-response.json")).write_text(
                            response.text + "\n"
                        )
                        checksum = response_checksum(response.text)
                        if expected is not None:
                            assert checksum == expected
                        expected = checksum
                urls.append({"id": key, "url": url, "rate": rates[key]})
            summary = directory / "summary.json"
            env = {
                "NODES": json.dumps(urls),
                "SECONDS": str(args.seconds),
                "SUMMARY": str(summary),
                "EXPECTED_OUTPUT": expected,
            }
            argv = [
                "k6",
                "run",
                "--quiet",
                "--out",
                "json=" + str(directory / "raw.jsonl"),
            ]
            for key, value in env.items():
                argv += ["-e", key + "=" + value]
            project = subprocess.check_output(
                [
                    "docker",
                    "ps",
                    "--filter",
                    "publish=" + str(args.loki_port),
                    "--format",
                    '{{.Label "com.docker.compose.project"}}',
                ],
                text=True,
            ).strip()
            if not project.startswith("nanolab-logs-") or "\n" in project:
                raise RuntimeError(
                    "owned backend resource measurement identity unavailable"
                )

            def capture_resources():
                nodes = {
                    key: json.loads(
                        node.command(
                            (
                                "python3",
                                "-c",
                                COUNTERS,
                                json.dumps(
                                    {"name": [cp_name, fn_name, "nanolab-logs-"]}
                                ),
                            )
                        ).stdout
                    )
                    for key, node in topology.nodes.items()
                }
                nodes["backend"] = json.loads(
                    subprocess.check_output(
                        [
                            "python3",
                            "-c",
                            COUNTERS,
                            json.dumps(
                                {"label": ["com.docker.compose.project=" + project]}
                            ),
                        ],
                        text=True,
                    )
                )
                return {"monotonicSeconds": time.monotonic(), "containers": nodes}

            before = capture_resources()
            (directory / "resources-before.json").write_text(
                json.dumps(before, indent=2) + "\n"
            )
            with (directory / "generator.log").open("w") as stream:
                subprocess.run(
                    [*argv, str(script)],
                    stdout=stream,
                    stderr=subprocess.STDOUT,
                    check=True,
                    timeout=args.seconds + 60,
                )
            after = capture_resources()
            (directory / "resources-after.json").write_text(
                json.dumps(after, indent=2) + "\n"
            )
            elapsed = after["monotonicSeconds"] - before["monotonicSeconds"]
            costs = {
                key: resource_delta(
                    value, after["containers"][key], elapsed_seconds=elapsed
                )
                for key, value in before["containers"].items()
            }
            (directory / "resource-costs.json").write_text(
                json.dumps(
                    {
                        "intervalSeconds": elapsed,
                        "measurement": "cumulative counters bracketing generator (includes sequential capture overhead); memory endpoints only",
                        "nodes": costs,
                    },
                    indent=2,
                )
                + "\n"
            )
            data = json.loads(summary.read_text())["metrics"]
            planned = sum(rates.values()) * args.seconds
            sent = data["generated"]["values"]["count"]
            completed = data.get("completed", {}).get("values", {}).get("count", 0)
            dropped = (
                data.get("dropped_iterations", {}).get("values", {}).get("count", 0)
            )
            result = {
                "window": number,
                "logging": on,
                "planned": planned,
                "sent": sent,
                "completed": completed,
                "dropped": dropped,
                "generatorDeficit": max(0, planned - sent),
                "goodput": completed / args.seconds,
                "p95Ms": data["node_latency"]["values"]["p(95)"],
                "perNodeP95Ms": {
                    key: data[f"node_latency{{node:{key}}}"]["values"]["p(95)"]
                    for key in topology.nodes
                },
            }
            for key, node in topology.nodes.items():
                snapshot = node.command(
                    ("docker", "stats", "--no-stream", "--format={{json .}}")
                )
                (directory / (key + "-stats.jsonl")).write_text(snapshot.stdout)
            for node in topology.nodes.values():
                for container in [cp_name, fn_name]:
                    remove_owned(node, container)
            (directory / "result.json").write_text(json.dumps(result, indent=2) + "\n")
            results.append(result)
            print(json.dumps(result), flush=True)
            return TaskOutcome(value=result)

    workflow = Workflow("logging-overhead-pilot")
    for index, on in enumerate([False, True, True, False, False, True]):
        directory = args.output / f"window-{index}"
        collectors = {}
        if on:
            collectors = {
                key: collector_resource(
                    node,
                    settings.observability.logs,
                    bootstrap=bootstraps[key],
                    backend=backend,
                    root=args.nanofaas,
                    run_dir=directory,
                    mode="LOGGING_ON",
                )
                for key, node in topology.nodes.items()
            }
        requires = (*topology.vms, *runtimes.values(), backend, *collectors.values())
        # Sharing runtime resources across all windows keeps the VMs alive. Per-window
        # collector handles are released when their consumer is finished.
        workflow.add(Window(index, on), requires=requires)
    try:
        workflow.run()
    finally:
        # The Sonata resource manager compensates VM/agent/backend resources on failure.
        existing = json.loads(
            subprocess.check_output(
                ["multipass", "list", "--format", "json"], text=True
            )
        )["list"]
        owned = [
            entry["name"]
            for entry in existing
            if entry["name"].startswith("nl-os-" + token + "-")
        ]
        (args.output / "cleanup.json").write_text(
            json.dumps({"ownedVmsRemaining": owned}, indent=2) + "\n"
        )
        if owned:
            raise RuntimeError("owned VM cleanup unconfirmed")
    pairs = []
    for index in range(0, 6, 2):
        a, b = results[index : index + 2]
        off, on = (a, b) if not a["logging"] else (b, a)
        pairs.append(
            {
                "pair": index // 2,
                "goodputChange": on["goodput"] / off["goodput"] - 1,
                "p95Change": on["p95Ms"] / off["p95Ms"] - 1,
            }
        )
    import statistics

    goodput = statistics.median(pair["goodputChange"] for pair in pairs)
    latency = statistics.median(pair["p95Change"] for pair in pairs)
    gate = (
        abs(goodput) <= 0.05
        and abs(latency) <= 0.05
        and all(accounting_valid(r) for r in results)
    )
    report = {
        "purpose": "observer-interference-pilot",
        "scientificCampaignQualified": False,
        "resourceCostMeasured": True,
        "arrivalProtocol": {
            "version": 2,
            "phaseOffsetsMs": "(iteration * 37 + 7) % 101",
            "reason": "Spread fixed arrival rates across the 100ms CPU quota period; same deterministic offsets in every window.",
        },
        "pairs": pairs,
        "medianGoodputChange": goodput,
        "medianP95Change": latency,
        "gatePassed": gate,
        "topology": SIZES,
        "images": {
            image: subprocess.check_output(
                ["docker", "image", "inspect", "--format={{.Id}}", image], text=True
            ).strip()
            for image in [CP_IMAGE, FN_IMAGE]
        },
        "logging": settings.observability.logs.fingerprint(),
        "limitations": [
            "Single replica per node; P2P disabled. Repeat gate on frozen full campaign stack before calibration."
        ],
    }
    (args.output / "receipt.json").write_text(json.dumps(report, indent=2) + "\n")
    hashes = {
        str(path.relative_to(args.output)): hashlib.sha256(
            path.read_bytes()
        ).hexdigest()
        for path in args.output.rglob("*")
        if path.is_file() and path.name != "sha256.json"
    }
    (args.output / "sha256.json").write_text(json.dumps(hashes, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
