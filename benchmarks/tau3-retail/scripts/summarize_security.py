"""Recompute deterministic RetailGuardBench metrics from saved results."""

from __future__ import annotations

import argparse
from pathlib import Path

from ecommerce_tau3.security_bench import analyze_and_write


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("run_dir", type=Path)
    parser.add_argument(
        "--baseline", default="security-official-baseline"
    )
    parser.add_argument("--guarded", default="security-guarded-agent")
    args = parser.parse_args()
    comparison = analyze_and_write(
        args.run_dir / args.baseline,
        args.run_dir / args.guarded,
        args.run_dir,
    )
    print(
        f"Wrote {args.run_dir / 'security-comparison.md'} "
        f"for {comparison['paired_task_trial_count']} paired simulations."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
