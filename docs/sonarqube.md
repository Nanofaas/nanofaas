# SonarQube (Local Analysis)

On-demand SonarQube analysis for the monorepo: `scripts/sonar.sh` starts an
ephemeral SonarQube container with Docker, runs the analysis for Java, Python,
Rust, Go and JavaScript, prints the open-issue counts per language and leaves the server up so
the issues can be browsed in the UI. This is **not** a CI pipeline step — the
analysis runs only when you invoke the script.

## Why no quality gate

The server is ephemeral (no volume, no history), so SonarQube treats the
entire codebase as "new code" on every run. The default `Sonar way` quality
gate fails whenever any pre-existing issue exists, which means a gate verdict
would be a constant, information-free FAIL. The script therefore reports
**issue counts per severity** instead, and issue counts never affect the exit
code. If you want real "new code" deltas and a meaningful gate, you need a
persistent server — deliberately out of scope here.

## Prerequisites

- Docker daemon running (macOS Docker Desktop needs no extra sysctl; on bare
  Linux hosts raise `vm.max_map_count`).
- `sonar-scanner` on PATH: `brew install sonar-scanner`.
- `python3` on PATH (used to parse the SonarQube API responses).
- For Java analysis: the containerd Maven repository produced by
  `scripts/bootstrap-containerd-dependencies.sh` (default `.gradle/containerd-m2`,
  override with `CONTAINERD_MAVEN_REPO`); `./gradlew test` builds the containerd module too.
- For Rust analysis only: `cargo` + `cargo clippy` (`rustup component add clippy`) —
  the Sonar Rust analyzer runs Clippy itself on the host, not in a container.

## Usage

```bash
./scripts/sonar.sh                          # Java, Python, Rust, Go and JavaScript
./scripts/sonar.sh --only java              # only one language (--only python|rust|go|javascript)
./scripts/sonar.sh --rm                     # remove the container when the run finishes
./scripts/sonar.sh --dry-run                # print the commands without executing them
./scripts/sonar.sh --help
```

What happens:

1. A SonarQube Community container (`sonarqube:26.7.0.124771-community`,
   pinned) is started on `127.0.0.1:9000` and polled until ready (timeout
   300s). A stale container from a previous run is replaced; a running one is
   reused as-is.
2. Java is analysed via the Gradle `org.sonarqube` plugin
   (`./gradlew test --no-parallel sonar`) — the per-module JaCoCo XML reports
   already produced by the test task feed the coverage. Python, Rust, Go and
   JavaScript are analysed via the `sonar-scanner` CLI (static analysis; no
   coverage import). The Rust project covers the watchdog, the Rust SDK and
   the Rust functions: every tracked `Cargo.toml` is listed in
   `sonar.rust.cargo.manifestPaths`, and `scripts/tests/test_sonar_script.py`
   fails when a new crate is missing from that list.
3. The script prints the open-issue counts per severity for each project
   (`nanofaas-java`, `nanofaas-python`, `nanofaas-rust`, `nanofaas-go`,
   `nanofaas-javascript`) plus the UI URL, and
   leaves the server running so the issue lists can be read in the browser.
4. The exit code is 0 when every requested analysis was submitted; it is
   non-zero on infrastructure errors (Docker, readiness, token, scanner) or a
   failed analysis. A failing analysis of one language does not block the
   others.

## Caveats

- The first run downloads the image (~1-2 GB) and the server boot takes a few
  minutes; subsequent runs are faster (image cached, boot still ~1-3 min).
- Every run is a fresh server with `admin/admin` — a token is generated
  automatically, nothing to configure. If the token API rejects the default
  password, the script changes it via the API and retries; on a reused server
  with an already-changed password it prints a clear error and suggests
  `docker rm -f sonar-nanofaas` (the next run starts a fresh container and
  resets to `admin/admin`).
- The port guard uses `lsof` (macOS); on minimal Linux builds without `lsof`
  the check is skipped and Docker's own port error surfaces instead.
- Python, Rust, Go and JavaScript coverage is not imported (only static analysis); `experiments/`
  is not analysed.

## Cleaning up

The server is left running by default so you can browse the issues. Remove it
any time with:

```bash
docker rm -f sonar-nanofaas
```

or pass `--rm` on the next run. State is ephemeral either way: no volume, no
history.
