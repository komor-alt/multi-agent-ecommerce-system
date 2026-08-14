"""Benchmark driver: run a registered custom agent through the official runner.

The official tau2 CLI only knows agents registered inside the tau2 package
(llm_agent, llm_agent_gt, ...). Custom agents are registered in-process via
the official registry, following the official example
(examples/agents/minimal_text_agent.py), so they run through
`tau2.runner.run_domain(TextRunConfig(...))` — the same official code path
the CLI uses (`tau2 run` calls run_domain with a TextRunConfig).

Usage (see scripts/run_guarded.sh):

    uv run python -m ecommerce_tau3.run \
        --config configs/smoke.yaml \
        --agent guarded_retail_agent \
        --save-to guarded-agent

Results are written by the official runner to
    <TAU2_DATA_DIR>/simulations/<save-to>/results.json
and the adapter's guard events (supplemental, never part of official results)
to guard_events.json in the same directory.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Optional

from ecommerce_tau3.runconfig import load_config, resolve_run_config


def build_parser() -> argparse.ArgumentParser:
    """Build the driver's argument parser.

    Description and help text are deliberately ASCII-only: on Windows the
    console code page is GBK (cp936), which cannot encode superscript
    characters, so a Unicode description raised UnicodeEncodeError on
    `python -m ecommerce_tau3.run --help` there.
    """
    parser = argparse.ArgumentParser(
        description="Run a registered tau3 Retail agent via the official runner."
    )
    parser.add_argument("--config", required=True, help="Path to a config YAML.")
    parser.add_argument(
        "--stage",
        default=None,
        choices=("dev", "final"),
        help="Stage for configs without a 'run' block (default: TAU3_STAGE or dev).",
    )
    parser.add_argument(
        "--agent",
        default=None,
        help="Registered agent name (default: from config; the CLI '--agent' "
        "choices only include official agents - custom agents must be "
        "registered in-process, which this driver does).",
    )
    parser.add_argument(
        "--save-to", default=None, help="Save name under data/simulations/."
    )
    parser.add_argument(
        "--agent-llm", default=None, help="Agent LLM model (overrides config)."
    )
    parser.add_argument(
        "--user-llm", default=None, help="User simulator LLM model (overrides config)."
    )
    parser.add_argument(
        "--num-trials",
        type=int,
        default=None,
        help="Number of trials (overrides config).",
    )
    parser.add_argument("--seed", type=int, default=None, help="Seed (overrides config).")
    parser.add_argument(
        "--task-ids",
        nargs="+",
        default=None,
        help="Run only these task ids (overrides config taskIds).",
    )
    parser.add_argument(
        "--num-tasks",
        type=int,
        default=None,
        help="Limit to the first N tasks of the split (official semantics).",
    )
    return parser


def main(argv: Optional[list[str]] = None) -> int:
    args = build_parser().parse_args(argv)

    config = load_config(Path(args.config))
    run_cfg = resolve_run_config(config, args.stage)
    agent_cfg = config.get("agent", {})
    user_cfg = config.get("user", {})
    save_cfg = config.get("save", {})

    agent_name = args.agent or agent_cfg.get("implementation", "guarded_retail_agent")

    # Register the custom agent with the official registry (idempotent).
    from ecommerce_tau3.factory import ensure_registered

    ensure_registered()

    # Import the official runner pieces (same path as `tau2 run`).
    from tau2.data_model.simulation import TextRunConfig
    from tau2.runner import run_domain

    # Overrides (CLI > config)
    seed = args.seed if args.seed is not None else run_cfg.get("seed")
    num_trials = (
        args.num_trials if args.num_trials is not None else run_cfg.get("numTrials", 1)
    )

    task_ids = args.task_ids or run_cfg.get("taskIds") or None
    num_tasks = (
        args.num_tasks if args.num_tasks is not None else run_cfg.get("numTasks")
    )

    config_obj = TextRunConfig(
        domain=config["benchmark"]["domain"],
        task_split_name=config["benchmark"].get("taskSplitName", "base"),
        task_ids=task_ids,
        num_tasks=num_tasks,
        agent=agent_name,
        llm_agent=args.agent_llm or agent_cfg.get("llm"),
        llm_args_agent=agent_cfg.get("llmArgs"),
        user=user_cfg.get("implementation", "user_simulator"),
        llm_user=args.user_llm or user_cfg.get("llm"),
        llm_args_user=user_cfg.get("llmArgs"),
        num_trials=num_trials,
        max_steps=run_cfg.get("maxSteps", 200),
        max_errors=run_cfg.get("maxErrors", 10),
        max_concurrency=run_cfg.get("maxConcurrency", 1),
        seed=seed,
        save_to=args.save_to or save_cfg.get("guarded"),
        log_level="ERROR",
    )

    # Run through the official batch runner (writes results to
    # <TAU2_DATA_DIR>/simulations/<save_to>/results.json).
    results = run_domain(config_obj)

    # Supplemental: write the adapter's guard events next to the official
    # results (paired by index; official results are never modified).
    from ecommerce_tau3.agent import drain_guard_event_collector
    from tau2.utils.utils import DATA_DIR

    events = drain_guard_event_collector()
    if events:
        save_dir = DATA_DIR / "simulations" / (args.save_to or save_cfg.get("guarded"))
        save_dir.mkdir(parents=True, exist_ok=True)
        guard_path = save_dir / "guard_events.json"
        with open(guard_path, "w", encoding="utf-8") as f:
            json.dump(events, f, indent=2)
        print(f"[ecommerce_tau3] guard events -> {guard_path}")

    # Print official summary (from the official Results object).
    rewards = [
        sim.reward_info.reward if sim.reward_info else None
        for sim in results.simulations
    ]
    n = len(rewards)
    n_ok = sum(1 for r in rewards if r == 1.0)
    print(
        f"[ecommerce_tau3] {n} simulation(s), "
        f"official reward mean={sum(r or 0 for r in rewards) / n if n else 0:.4f}, "
        f"success={n_ok}/{n}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
