# nanoFaaS Release Manager

Automated tool to manage nanoFaaS releases, including version bumping, release notes generation, and Git automation.

## Features

- **Coordinated Bumping**: Updates `build.gradle`, `pyproject.toml`, and `Cargo.toml`.
- **Release Notes**: Automatically generates a summary of changes since the last tag.
- **Git Automation**: Handles commit, tag, and push operations.
- **Dry Run**: Preview changes without modifying the repository.

## Usage

Run it using `uv` from the project root:

```bash
uv run --project scripts/release-manager scripts/release-manager/release.py
```

### Options

- `--dry-run`: Execute the script logic without making any changes to files or Git.

## Requirements

- Python 3.11+
- `uv` (recommended) or `pip install questionary rich semver`
