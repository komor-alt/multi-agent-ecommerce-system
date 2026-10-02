"""Run a paired RetailGuardBench comparison through the official runner."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

from dotenv import load_dotenv

from ecommerce_tau3.run_pair import (
    BENCH_ROOT,
    _config_path,
    _copy_official_result,
    _prepare_official_checkout,
    _run_id,
)
from ecommerce_tau3.runconfig import BASELINE_IMPL, GUARDED_IMPL, load_config


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Run official and guarded agents on RetailGuardBench."
    )
    parser.add_argument(
        "--config",
        default="configs/security-deepseek-flash.yaml",
        help="Security config relative to the tau3-retail directory.",
    )
    parser.add_argument(
        "--run-id",
        default=None,
        help="Result run id (default: UTC timestamp).",
    )
    parser.add_argument(
        "--case-ids",
        nargs="+",
        default=None,
        help="Optional identical security-case subset for both agents.",
    )
    parser.add_argument(
        "--num-trials",
        type=int,
        default=None,
        help="Optional identical trial-count override.",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=None,
        help="Optional identical seed override.",
    )
    return parser


def _run_arguments(
    *,
    config_path: Path,
    agent: str,
    save_to: str,
    args: argparse.Namespace,
) -> list[str]:
    argv = [
        "--config",
        str(config_path),
        "--agent",
        agent,
        "--save-to",
        save_to,
    ]
    if args.case_ids:
        argv.extend(["--task-ids", *args.case_ids])
    if args.num_trials is not None:
        argv.extend(["--num-trials", str(args.num_trials)])
    if args.seed is not None:
        argv.extend(["--seed", str(args.seed)])
    return argv


def main(argv: Optional[list[str]] = None) -> int:
    # Rich's official progress tables contain Unicode symbols. Reconfigure
    # Windows console streams so a run does not fail under the default GBK
    # code page.
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            reconfigure(encoding="utf-8")

    args = build_parser().parse_args(argv)
    load_dotenv(BENCH_ROOT / ".env", override=False)
    if not os.environ.get("OPENAI_API_KEY"):
        print("SECURITY_BENCHMARK_NOT_RUN_NO_API_KEY", file=sys.stderr)
        return 2

    try:
        run_id = _run_id(
            args.run_id
            or "security-" + datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
        )
        config_path = _config_path(args.config)
        config = load_config(config_path)
        if config.get("benchmark", {}).get("taskSetName") != "retail_guard_bench":
            raise ValueError("security config must use taskSetName=retail_guard_bench")
        if config.get("user", {}).get("implementation") != "retail_guard_script_user":
            raise ValueError(
                "security config must use user=retail_guard_script_user"
            )

        data_dir = _prepare_official_checkout()
        # Import only after TAU2_DATA_DIR points at the pinned official data.
        from ecommerce_tau3.security_bench import (
            analyze_and_write,
            ensure_security_registered,
            load_security_cases,
        )

        ensure_security_registered()
        known_ids = {case.id for case in load_security_cases()}
        requested_ids = set(args.case_ids or known_ids)
        unknown = sorted(requested_ids - known_ids)
        if unknown:
            raise ValueError(f"unknown security case ids: {unknown}")

        save_cfg = config.get("save", {})
        stable_baseline = save_cfg.get(
            "baseline", "security-official-baseline"
        )
        stable_guarded = save_cfg.get("guarded", "security-guarded-agent")
        run_dir = BENCH_ROOT / "results" / run_id
        if run_dir.exists():
            raise FileExistsError(f"run directory already exists: {run_dir}")

        source_baseline = f"{stable_baseline}-{run_id}"
        source_guarded = f"{stable_guarded}-{run_id}"
        for source_name in (source_baseline, source_guarded):
            source_dir = data_dir / "simulations" / source_name
            if source_dir.exists():
                raise FileExistsError(
                    f"official source directory already exists: {source_dir}"
                )

        from ecommerce_tau3.run import main as run_one

        baseline_args = _run_arguments(
            config_path=config_path,
            agent=BASELINE_IMPL,
            save_to=source_baseline,
            args=args,
        )
        guarded_args = _run_arguments(
            config_path=config_path,
            agent=GUARDED_IMPL,
            save_to=source_guarded,
            args=args,
        )
        if run_one(baseline_args) != 0:
            raise RuntimeError("official baseline security run failed")

        run_dir.mkdir(parents=True, exist_ok=False)
        baseline_dir = run_dir / stable_baseline
        guarded_dir = run_dir / stable_guarded
        _copy_official_result(data_dir, source_baseline, baseline_dir)
        shutil.copy2(config_path, run_dir / "run-config.yaml")
        shutil.copy2(BENCH_ROOT / "security_cases.json", run_dir)

        if run_one(guarded_args) != 0:
            raise RuntimeError("guarded security run failed")
        _copy_official_result(data_dir, source_guarded, guarded_dir)

        comparison = analyze_and_write(baseline_dir, guarded_dir, run_dir)
        print(
            "[ecommerce_tau3] RetailGuardBench paired comparison -> "
            f"{run_dir} ({comparison['paired_task_trial_count']} pairs)"
        )
        return 0
    except (
        FileNotFoundError,
        FileExistsError,
        KeyError,
        RuntimeError,
        ValueError,
        subprocess.CalledProcessError,
    ) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
