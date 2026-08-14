"""Shared run-config resolution for the benchmark scripts and driver.

A top-level `run:` block ALWAYS wins when present:

  - smoke.yaml: `run:` = 10 tasks x 1 trial;
  - full.yaml:  `run:` = the full retail base split (114 tasks x 1 trial);
                a 50-task development run uses a `--num-tasks` CLI override
                (both wrapper scripts forward extra arguments).

Legacy configs may instead carry `dev:` / `final:` blocks, selected
explicitly via --stage / TAU3_STAGE (default `dev`).

This module is the SINGLE place that decides which block executes, so the
bash scripts (`scripts/run_baseline.sh`, `scripts/run_guarded.sh`) and
`python -m ecommerce_tau3.run` always agree.

The module also exposes a tiny CLI used by the bash scripts:

    uv run python -m ecommerce_tau3.runconfig --config configs/full.yaml \
        --stage dev --impl guarded

which prints `export KEY=value` lines (shell-quoted) the scripts `eval`.
"""

from __future__ import annotations

import argparse
import json
import os
import shlex
import sys
from pathlib import Path
from typing import Optional

import yaml

DEFAULT_STAGE = "dev"
VALID_STAGES = ("dev", "final")

# Implementation names: baseline (official llm_agent) vs guarded adapter.
BASELINE_IMPL = "llm_agent"
GUARDED_IMPL = "guarded_retail_agent"


def load_config(path: Path) -> dict:
    with open(path, "r", encoding="utf-8") as f:
        return yaml.safe_load(f)


def resolve_run_config(config: dict, stage: Optional[str] = None) -> dict:
    """Pick the executable run block from a config.

    `run:` (smoke) wins when present; otherwise the explicitly chosen
    `dev:`/`final:` block of full.yaml is used. Fails loudly when the chosen
    stage does not exist (never silently runs the wrong stage).
    """
    if "run" in config:
        return config["run"]
    if stage is None:
        stage = os.environ.get("TAU3_STAGE") or DEFAULT_STAGE
    if stage not in VALID_STAGES:
        raise ValueError(
            f"Unknown TAU3 stage {stage!r}; expected one of {VALID_STAGES}"
        )
    if stage not in config:
        raise KeyError(
            f"config has no '{stage}' block (and no 'run' block); "
            f"available blocks: {sorted(k for k in config if k not in ('benchmark', 'agent', 'user', 'save'))}"
        )
    return config[stage]


def _agent_impl(config: dict, impl: str) -> str:
    agent = config.get("agent", {})
    if impl == "guarded":
        # smoke.yaml: `implementation` names the baseline (both experiments
        # share one config). full.yaml names the guarded agent explicitly and
        # adds `baselineImplementation` for the baseline side.
        if "baselineImplementation" in agent:
            return agent.get("implementation", GUARDED_IMPL)
        return GUARDED_IMPL
    return agent.get("baselineImplementation") or agent.get(
        "implementation", BASELINE_IMPL
    )


def export_lines(config_path: Path, stage: Optional[str], impl: str) -> list[str]:
    """Build `export KEY=<shell-quoted value>` lines for the bash scripts."""
    config = load_config(config_path)
    bench = config["benchmark"]
    run = resolve_run_config(config, stage)
    agent = config.get("agent", {})
    user = config.get("user", {})
    save = config.get("save", {})

    fields: dict[str, str] = {
        "DOMAIN": bench["domain"],
        "SPLIT": bench.get("taskSplitName", "base"),
        "TASK_IDS": " ".join(shlex.quote(str(t)) for t in run.get("taskIds", [])),
        "NUM_TASKS": str(run.get("numTasks") or ""),
        "TRIALS": str(run.get("numTrials", 1)),
        "SEED": str(run.get("seed", 300)),
        "MAX_STEPS": str(run.get("maxSteps", 200)),
        "MAX_ERRORS": str(run.get("maxErrors", 10)),
        "MAX_CONCURRENCY": str(run.get("maxConcurrency", 1)),
        "AGENT_IMPL": _agent_impl(config, impl),
        "AGENT_LLM": agent["llm"],
        "AGENT_LLM_ARGS": json.dumps(agent.get("llmArgs", {})),
        "USER_LLM": user["llm"],
        "USER_LLM_ARGS": json.dumps(user.get("llmArgs", {})),
    }
    if impl == "guarded":
        fields["SAVE_NAME"] = save.get("guarded", "guarded-agent")
    else:
        fields["SAVE_NAME"] = save.get("baseline", "official-baseline")
    return [f"export {k}={shlex.quote(v)}" for k, v in fields.items()]


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Resolve a benchmark config into shell exports "
        "(single source of truth for scripts/run_baseline.sh and run_guarded.sh)."
    )
    parser.add_argument("--config", required=True, help="Path to a config YAML.")
    parser.add_argument(
        "--stage",
        default=None,
        choices=VALID_STAGES,
        help=f"Stage for configs without a 'run' block (default: TAU3_STAGE or {DEFAULT_STAGE}).",
    )
    parser.add_argument(
        "--impl",
        default="guarded",
        choices=("baseline", "guarded"),
        help="Which agent implementation to resolve (baseline=llm_agent).",
    )
    args = parser.parse_args(argv)
    for line in export_lines(Path(args.config), args.stage, args.impl):
        print(line)
    return 0


if __name__ == "__main__":
    sys.exit(main())
