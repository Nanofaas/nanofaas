# nanoFaaS Release Manager

Automated tool to manage nanoFaaS releases using a GitHub-centric workflow.

## Features

- **GitHub Integration**: Uses `gh` CLI to automate Pull Requests and Merges.
- **Automated PR flow**: Automatically pushes your feature branch, creates a PR to `main`, and merges it.
- **Coordinated Bumping**: Updates `build.gradle`, `pyproject.toml`, and `Cargo.toml` in sync.
- **Release Notes**: Automatically generates a summary of changes from commit history.
- **GitOps Ready**: Pushes tags to GitHub to trigger the automated CI/CD pipeline.
- **Safety First**: Supports `--dry-run` and validates `gh` authentication status.

## Prerequisites

- [GitHub CLI (gh)](https://cli.github.com/) installed and authenticated (`gh auth login`).
- [uv](https://github.com/astral-sh/uv) for running the script easily.

## Usage

Run it from the project root:

```bash
uv run --project scripts/release-manager scripts/release-manager/release.py
```

### Typical Workflow

1.  Work on your feature branch (e.g., `feature/my-cool-function`).
2.  Commit all your changes.
3.  Run the Release Manager.
4.  Follow the prompts to:
    -   Merge your branch into `main`.
    -   Sync local `main` with GitHub.
    -   Bump the version (Patch/Minor/Major).
    -   Review/Edit the generated Release Notes.
    -   Commit and Push the new version.
    -   Create and Push the Tag (triggers GitOps).

### Options

- `--dry-run`: Preview the entire process without modifying files, creating PRs, or pushing tags.

## Requirements

- Python 3.11+
- Dependencies (managed by uv): `questionary`, `rich`, `semver`.