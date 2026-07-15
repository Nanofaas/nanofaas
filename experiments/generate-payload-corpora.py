#!/usr/bin/env python3
"""Write or verify the static performance payload corpora."""

import argparse

from lib.payload_corpora import REPO_ROOT, generate_all, generate_catalog_samples


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--check",
        action="store_true",
        help="fail when a committed corpus differs from deterministic output",
    )
    args = parser.parse_args()

    generated = generate_all()
    generated.update(generate_catalog_samples(generated))
    for relative_path, expected in generated.items():
        path = REPO_ROOT / relative_path
        if args.check:
            if not path.exists() or path.read_text(encoding="utf-8") != expected:
                raise SystemExit(f"corpus differs: {relative_path}")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(expected, encoding="utf-8")


if __name__ == "__main__":
    main()
