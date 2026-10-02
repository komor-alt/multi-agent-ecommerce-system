"""Cross-platform paired runner for the official and guarded Retail agents.

This entry point avoids shell-specific orchestration and guarantees that the
two experiments share one config, task selection and run id. Official tau3
artifacts remain untouched; each run uses unique source save names and copies
them into the stable comparison layout consumed by scripts/summarize.py.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional
from dotenv import load_dotenv

from ecommerce_tau3.runconfig import (
    BASELINE_IMPL,
    GUARDED_IMPL,
    load_config,
)

BENCH_ROOT = Path(__file__).resolve().parents[2]
_SAFE_RUN_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Run a fair tau3 Retail baseline/guarded comparison."
    )
    parser.add_argument(
        "--config",
        default="configs/smoke.yaml",
        help="Benchmark config relative to the tau3-retail directory.",
    )
    parser.add_argument(
        "--run-id",
        default=None,
        help="Result run id (default: UTC timestamp).",
    )
    parser.add_argument(
        "--task-ids",
        nargs="+",
        default=None,
        help="Optional identical task-id override for both experiments.",
    )
    parser.add_argument(
        "--num-tasks",
        type=int,
        default=None,
        help="Optional identical task-count override for both experiments.",
    )
    parser.add_argument(
        "--num-trials",
        type=int,
        default=None,
        help="Optional identical trial-count override for both experiments.",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=None,
        help="Optional identical seed override for both experiments.",
    )
    return parser


def _run_id(value: Optional[str]) -> str:
    run_id = value or datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    if not _SAFE_RUN_ID.fullmatch(run_id):
        raise ValueError(
            "run id must start with an alphanumeric character and contain "
            "only letters, digits, '.', '_' or '-'"
        )
    return run_id


def _config_path(value: str) -> Path:
    path = Path(value)
    if not path.is_absolute():
        path = BENCH_ROOT / path
    path = path.resolve()
    if not path.is_file():
        raise FileNotFoundError(f"benchmark config not found: {path}")
    return path


def _prepare_official_checkout() -> Path:
    lock = json.loads((BENCH_ROOT / "benchmark-lock.json").read_text("utf-8"))
    expected_commit = lock["commit"]
    checkout = BENCH_ROOT / "env" / "tau2-bench"
    git_dir = checkout / ".git"
    data_dir = checkout / "data"
    retail_tasks = data_dir / "tau2" / "domains" / "retail" / "tasks.json"
    if not git_dir.exists() or not retail_tasks.exists():
        raise RuntimeError(
            "pinned official checkout is missing; run scripts/setup_tau3.sh "
            "first (or create env/tau2-bench at the locked commit)"
        )
    actual_commit = subprocess.run(
        ["git", "-C", str(checkout), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    if actual_commit != expected_commit:
        raise RuntimeError(
            f"official checkout is {actual_commit}, expected {expected_commit}"
        )
    os.environ["TAU2_DATA_DIR"] = str(data_dir)
    return data_dir


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
    if args.task_ids:
        argv.extend(["--task-ids", *args.task_ids])
    if args.num_tasks is not None:
        argv.extend(["--num-tasks", str(args.num_tasks)])
    if args.num_trials is not None:
        argv.extend(["--num-trials", str(args.num_trials)])
    if args.seed is not None:
        argv.extend(["--seed", str(args.seed)])
    return argv


def _copy_official_result(data_dir: Path, source_name: str, destination: Path) -> None:
    source = data_dir / "simulations" / source_name
    if not (source / "results.json").is_file():
        raise RuntimeError(f"official results missing after run: {source}")
    if destination.exists():
        raise FileExistsError(f"comparison destination already exists: {destination}")
    shutil.copytree(source, destination)


def _run_report_scripts(run_dir: Path) -> None:
    subprocess.run(
        [sys.executable, str(BENCH_ROOT / "scripts" / "summarize.py"), str(run_dir)],
        cwd=BENCH_ROOT,
        check=True,
    )
    for agent_dir in (run_dir / "official-baseline", run_dir / "guarded-agent"):
        subprocess.run(
            [
                sys.executable,
                str(BENCH_ROOT / "scripts" / "analyze_failures.py"),
                str(agent_dir),
            ],
            cwd=BENCH_ROOT,
            check=True,
        )


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

    # Fail before checkout validation, result-directory creation or any model
    # call. A public score is never fabricated with a fake fallback.
    if not os.environ.get("OPENAI_API_KEY"):
        print("PUBLIC_BENCHMARK_NOT_RUN_NO_API_KEY", file=sys.stderr)
        print(
            "Required OpenAI credentials (OPENAI_API_KEY) are absent; "
            "no model call was made.",
            file=sys.stderr,
        )
        return 2

    try:
        run_id = _run_id(args.run_id)
        config_path = _config_path(args.config)
        config = load_config(config_path)
        save_cfg = config.get("save", {})
        stable_baseline = save_cfg.get("baseline", "official-baseline")
        stable_guarded = save_cfg.get("guarded", "guarded-agent")
        run_dir = BENCH_ROOT / "results" / run_id
        if run_dir.exists():
            raise FileExistsError(f"run directory already exists: {run_dir}")

        data_dir = _prepare_official_checkout()
        source_baseline = f"{stable_baseline}-{run_id}"
        source_guarded = f"{stable_guarded}-{run_id}"
        for source_name in (source_baseline, source_guarded):
            source_dir = data_dir / "simulations" / source_name
            if source_dir.exists():
                raise FileExistsError(
                    f"official source directory already exists: {source_dir}"
                )

        from ecommerce_tau3.run import main as run_one

        baseline_argv = _run_arguments(
            config_path=config_path,
            agent=BASELINE_IMPL,
            save_to=source_baseline,
            args=args,
        )
        guarded_argv = _run_arguments(
            config_path=config_path,
            agent=GUARDED_IMPL,
            save_to=source_guarded,
            args=args,
        )

        if run_one(baseline_argv) != 0:
            raise RuntimeError("official baseline run failed")
        run_dir.mkdir(parents=True, exist_ok=False)
        _copy_official_result(data_dir, source_baseline, run_dir / stable_baseline)
        shutil.copy2(config_path, run_dir / "run-config.yaml")

        if run_one(guarded_argv) != 0:
            raise RuntimeError("guarded agent run failed")
        _copy_official_result(data_dir, source_guarded, run_dir / stable_guarded)
        _run_report_scripts(run_dir)
        print(f"[ecommerce_tau3] paired comparison -> {run_dir}")
        return 0
    except (
        FileNotFoundError,
        FileExistsError,
        RuntimeError,
        ValueError,
        subprocess.CalledProcessError,
    ) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
