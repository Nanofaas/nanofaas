#!/usr/bin/env python3
"""Control-plane staging and comparison manager."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable

try:
    from experiments.staging.benchmark import load_benchmark_config
    from experiments.staging.campaign import CampaignCell, run_campaign
    from experiments.staging.promotion import promote_candidate_to_baseline
    from experiments.staging.report import aggregate_campaign_reports
    from experiments.staging.scaffold import create_version
except ModuleNotFoundError:  # direct script execution: python3 experiments/staging_manager.py
    from staging.benchmark import load_benchmark_config
    from staging.campaign import CampaignCell, run_campaign
    from staging.promotion import promote_candidate_to_baseline
    from staging.report import aggregate_campaign_reports
    from staging.scaffold import create_version


CommandHandler = Callable[[argparse.Namespace], int]
DEFAULT_STAGING_ROOT = Path(__file__).resolve().parent / "control-plane-staging"


def _noop_command(_: argparse.Namespace) -> int:
    return 0


def _cmd_create_version(args: argparse.Namespace) -> int:
    create_version(
        root=Path(args.staging_root).resolve(),
        slug=args.slug,
        source=args.source,
    )
    return 0


def _cmd_build_images(_: argparse.Namespace) -> int:
    return 0


def _campaign_noop_executor(_: CampaignCell) -> dict:
    return {}


def _cmd_run_campaign(args: argparse.Namespace) -> int:
    staging_root = Path(args.staging_root).resolve()
    benchmark_path = Path(args.benchmark_path).resolve()
    benchmark = load_benchmark_config(benchmark_path)

    campaign_id = args.campaign_id
    if not campaign_id:
        timestamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
        campaign_id = f"campaign-{timestamp}"

    campaign_result = run_campaign(
        root=staging_root,
        campaign_id=campaign_id,
        benchmark_path=benchmark_path,
        baseline_slug=args.baseline,
        candidate_slug=args.candidate,
        runs=args.runs,
        platform_modes=benchmark.platform_modes,
        executor=_campaign_noop_executor,
    )
    aggregate_campaign_reports(campaign_result.campaign_dir)
    return 0


def _cmd_promote(args: argparse.Namespace) -> int:
    promote_candidate_to_baseline(
        root=Path(args.staging_root).resolve(),
        candidate_slug=args.candidate,
        campaign_id=args.campaign_id,
    )
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="staging_manager",
        description="Manage staged control-plane versions and comparison campaigns.",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    create_version_parser = subparsers.add_parser("create-version")
    create_version_parser.add_argument("--slug", required=True)
    create_version_parser.add_argument("--from", dest="source", required=True)
    create_version_parser.add_argument(
        "--staging-root",
        default=str(DEFAULT_STAGING_ROOT),
    )
    create_version_parser.set_defaults(handler=_cmd_create_version)

    build_images_parser = subparsers.add_parser("build-images")
    build_images_parser.add_argument("--slug", required=False)
    build_images_parser.add_argument("--force-rebuild-images", action="store_true")
    build_images_parser.add_argument(
        "--force-rebuild-mode",
        action="append",
        choices=("jvm", "native"),
        default=[],
    )
    build_images_parser.set_defaults(handler=_cmd_build_images)

    run_campaign_parser = subparsers.add_parser("run-campaign")
    run_campaign_parser.add_argument("--baseline", required=True)
    run_campaign_parser.add_argument("--candidate", required=True)
    run_campaign_parser.add_argument("--runs", type=int, default=10)
    run_campaign_parser.add_argument("--campaign-id", required=False)
    run_campaign_parser.add_argument(
        "--staging-root",
        default=str(DEFAULT_STAGING_ROOT),
    )
    run_campaign_parser.add_argument(
        "--benchmark-path",
        default=str(DEFAULT_STAGING_ROOT / "benchmark" / "benchmark.yaml"),
    )
    run_campaign_parser.set_defaults(handler=_cmd_run_campaign)

    promote_parser = subparsers.add_parser("promote")
    promote_parser.add_argument("--candidate", required=True)
    promote_parser.add_argument("--campaign-id", required=True)
    promote_parser.add_argument(
        "--staging-root",
        default=str(DEFAULT_STAGING_ROOT),
    )
    promote_parser.set_defaults(handler=_cmd_promote)

    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    handler: CommandHandler = getattr(args, "handler", _noop_command)
    return handler(args)


if __name__ == "__main__":
    raise SystemExit(main())
