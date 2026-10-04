"""Export deterministic fixtures from the pinned DFaaSOptimizer checkout.

Regeneration uses that checkout's Python environment. Normal Java tests read the
checked-in JSON and do not import or execute the Python optimizer.
"""

from datetime import datetime
import hashlib
import json
from pathlib import Path
import random
import subprocess
import sys


REFERENCE_COMMIT = "71899f720a4ffffd070ebf7ddc5b74afddfde9c5"
ROOT = Path(__file__).resolve().parents[2]
SOURCES = ("models/local_sp.py", "models/sp.py", "plasma/core/sbm.py",
           "decentralized_auction.py", "run_faasmadea.py", "run_faasmacro.py")


def sha256(content: bytes) -> str:
    return "sha256:" + hashlib.sha256(content).hexdigest()


def validate_contract(name: str, document: dict) -> None:
    """Validate structure and cross-field invariants; JSON numbers must be finite."""
    from jsonschema import Draft202012Validator, FormatChecker

    try:
        json.dumps(document, allow_nan=False)
        schema = json.loads((ROOT / "docs/contracts/one-shot" / f"{name}.schema.json").read_text())
        Draft202012Validator(schema, format_checker=FormatChecker()).validate(document)
        if name == "forecast":
            seen = set()
            windows = {}
            for entry in document["entries"]:
                start, end = (datetime.fromisoformat(entry[key].replace("Z", "+00:00"))
                              for key in ("start", "end"))
                key = (entry["function"], entry["generation"])
                identity = (*key, start, end)
                if start >= end or identity in seen:
                    raise ValueError("invalid or duplicate forecast interval")
                seen.add(identity)
                for previous_start, previous_end in windows.get(key, []):
                    if start < previous_end and previous_start < end:
                        raise ValueError("overlapping forecast intervals")
                windows.setdefault(key, []).append((start, end))
        elif name == "service-profile":
            names = set()
            for function in document["functions"]:
                validity = function["validity"]
                if validity["minReplicas"] > validity["maxReplicas"]:
                    raise ValueError("invalid replica validity interval")
                if function["function"] in names:
                    raise ValueError("duplicate profile function")
                names.add(function["function"])
    except Exception as error:
        raise ValueError(f"invalid {name} contract: {error}") from error


def _data(problem: dict) -> dict:
    """Translate a compact local fixture into the original indexed parameters."""
    rows = problem["functions"]
    v = {"whoami": {None: 1}, "Nn": {None: 1}, "Nf": {None: len(rows)},
         "memory_capacity": {1: problem["memoryCapacityMiB"]}}
    fields = {"incoming_load": "load", "demand": "demandSeconds",
              "alpha": "alpha", "delta": "delta", "gamma": "gamma",
              "x_bar": "fixedLocal", "omega_bar": "fixedOffload"}
    for parameter, field in fields.items():
        v[parameter] = {(1, f): row[field] for f, row in enumerate(rows, 1)}
    for parameter, field in {"memory_requirement": "memoryMiB",
                             "max_utilization": "utilization", "pi": "price"}.items():
        v[parameter] = {f: row[field] for f, row in enumerate(rows, 1)}
    v["y_bar"] = {(1, 1, f): row["inbound"] for f, row in enumerate(rows, 1)}
    return {None: v}


def _cases() -> list[dict]:
    rng = random.Random(238)
    cases = []
    for model in ("LSP", "LSPr_x"):
        for n in range(40):
            rows = []
            for f in range(1 + n % 3):
                load = rng.randrange(0, 9)
                fixed_local = rng.randrange(load + 1)
                rows.append({"id": f"f{f}", "load": load,
                             "demandSeconds": rng.choice([0.1, 0.25, 0.5, 1.0]),
                             "utilization": rng.choice([0.8, 1.0]),
                             "memoryMiB": rng.choice([1, 2, 4]),
                             "alpha": rng.choice([0.0, 1.0, 9.0]),
                             "delta": rng.choice([0.0, 0.9, 2.0]),
                             "gamma": rng.choice([0.0, 0.1, 1.0]),
                             "price": rng.choice([0.0, 1.0, 3.0]),
                             "fixedLocal": fixed_local,
                             "fixedOffload": rng.randrange(load - fixed_local + 1),
                             "inbound": rng.randrange(0, 3)})
            cases.append({"id": f"{model}-{n}", "model": model,
                          "memoryCapacityMiB": rng.randrange(0, 13), "functions": rows})
    return cases


def _auction_transcripts() -> list[dict]:
    import numpy as np
    from run_faasmadea import compute_residual_capacity, define_bids, evaluate_bids

    # These are actual base-auction helpers, with replacement and tentative
    # starts disabled exactly as in decentralized_auction.run.
    n, nf = 3, 1
    data = {None: {"Nn": {None: n}, "Nf": {None: nf},
                   "max_utilization": {1: 1.0},
                   "memory_capacity": {1: 2, 2: 2, 3: 2},
                   "memory_requirement": {1: 1},
                   "demand": {(i, 1): 1.0 for i in range(1, n + 1)},
                   "beta": {(i, j, 1): 1.0 for i in range(1, n + 1)
                            for j in range(1, n + 1)},
                   "gamma": {(i, 1): 0.1 for i in range(1, n + 1)}}}
    options = {"latency_weight": 0.0, "fairness_weight": 0.0,
               "epsilon": 0.01, "unit_bids": False, "eta": 0.0, "zeta": 0.0}
    x, r = np.array([[2.0], [0.0], [0.0]]), np.array([[2], [2], [2]])
    omega = np.array([[3.0], [0.0], [0.0]])
    y, prices = np.zeros((n, n, nf)), np.zeros((n, nf))
    neighborhood = np.ones((n, n)) - np.eye(n)
    latency, fairness, rho = np.zeros((n, n)), np.zeros((n, nf)), np.zeros(n)
    rounds = []
    for iteration in range(2):
        capacity, blackboard, ell = compute_residual_capacity(x, y, r, data)
        bids, memory_bids, _ = define_bids(omega, blackboard, prices, data,
                                         neighborhood, rho, options, latency, fairness, False)
        grant, prices, _, _ = evaluate_bids(
            bids, blackboard, data, previous_y=y, ell=ell, p=prices,
            capacity=capacity, u0=np.ones((n, nf)) * 0.9,
            auction_options=options, tentatively_start_replicas=False,
            may_replace_existing_assignments=False)
        rounds.append({"round": iteration, "offeredCapacity": blackboard.tolist(),
                       "bids": bids.to_dict(orient="records"),
                       "memoryBids": memory_bids.to_dict(orient="records"),
                       "grants": grant.tolist(), "prices": prices.tolist()})
        y += grant
        omega = np.maximum(0, np.array([[3.0], [0.0], [0.0]]) - y.sum(axis=1))
        if not omega.any():
            break
    return [{"id": "three-node-aggregate-bids", "parameters": {"nodes": n,
             "functions": nf, "local": x.tolist(), "replicas": r.tolist(),
             "residualLoad": [[3.0], [0.0], [0.0]], "auctionOptions": options},
             "rounds": rounds, "finalAssignments": y.tolist()}]


def export_reference(repo: Path, output: Path) -> None:
    """Verify the reference revision and source cleanliness before importing it."""
    revision = subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"],
                                       text=True).strip()
    if revision != REFERENCE_COMMIT:
        raise ValueError(f"reference revision must be {REFERENCE_COMMIT}, got {revision}")
    dirty = subprocess.check_output(["git", "-C", str(repo), "status", "--porcelain",
                                    "--", *SOURCES], text=True)
    if dirty:
        raise ValueError("reference sources contain local changes")
    sys.path.insert(0, str(repo.resolve()))
    try:
        from models.local_sp import try_solve_local
        from models import sp
        cases = []
        for problem in _cases():
            solution = try_solve_local(getattr(sp, problem["model"])(), _data(problem))
            expected = ({"status": "OPTIMAL", "objective": solution["obj"],
                         "local": solution["x"], "replicas": solution["r"],
                         "rejected": solution["z"]} if solution else {"status": "INFEASIBLE"})
            if solution and "omega" in solution:
                expected["offload"] = solution["omega"]
            cases.append({"input": problem, "expected": expected})
        transcripts = _auction_transcripts()
    finally:
        sys.path.pop(0)
    output.mkdir(parents=True, exist_ok=True)
    manifest = {"schemaVersion": 1, "commit": revision,
                "sources": {name: sha256((repo / name).read_bytes()) for name in SOURCES},
                "fixtures": {}}
    for filename, content in {"local-problems.json": cases,
                              "auction-transcripts.json": transcripts}.items():
        encoded = (json.dumps(content, indent=2, allow_nan=False) + "\n").encode()
        (output / filename).write_bytes(encoded)
        manifest["fixtures"][filename] = sha256(encoded)
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    export_reference(arguments.reference, arguments.output)
