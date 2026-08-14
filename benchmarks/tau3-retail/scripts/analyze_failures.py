#!/usr/bin/env python3
"""Failure analysis for an official τ³ Retail run (requirement §40).

Analyzes ONLY the official trajectory data of actual runs:
  - termination_reason (official TerminationReason)
  - official reward components (db_check, communicate_checks, action_checks)
  - conversation length / tool-call counts from official messages

It NEVER reads gold actions / expected answers to judge "you should have
called tool X at step N" — the official evaluator is the only arbiter of
task success (requirements §16 / §17 / §40).

Usage:
    uv run python scripts/analyze_failures.py results/<timestamp>/<agent-dir>
"""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path

from tau2.data_model.simulation import Results


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "run_dir", type=Path, help="a run directory containing results.json"
    )
    parser.add_argument(
        "--out", type=Path, default=None, help="output markdown path (default: failure-analysis.md next to results.json)"
    )
    args = parser.parse_args()

    results_path = args.run_dir / "results.json"
    if not results_path.exists():
        parser.error(f"no results.json in {args.run_dir}")

    results = Results.load(results_path)
    failed = [
        sim for sim in results.simulations
        if sim.reward_info is None or sim.reward_info.reward < 1.0
    ]

    buckets: Counter[str] = Counter()
    rows: list[dict] = []
    for sim in failed:
        reason = sim.termination_reason.value
        reward = sim.reward_info.reward if sim.reward_info else None
        db_match = None
        if sim.reward_info and sim.reward_info.db_check:
            db_match = sim.reward_info.db_check.db_match
        communicate_unmet = []
        if sim.reward_info and sim.reward_info.communicate_checks:
            communicate_unmet = [
                c.info for c in sim.reward_info.communicate_checks if not c.met
            ]

        if reward is None:
            label = f"no_reward/{reason}"
        elif reason in ("max_steps", "timeout", "too_many_errors", "agent_error", "infrastructure_error", "unexpected_error"):
            label = f"execution/{reason}"
        elif db_match is False:
            label = "db_end_state_mismatch"
        elif communicate_unmet:
            label = "required_info_not_communicated"
        else:
            label = f"other/{reason}"
        buckets[label] += 1
        rows.append(
            {
                "task_id": sim.task_id,
                "trial": sim.trial,
                "reward": reward,
                "termination_reason": reason,
                "db_match": db_match,
                "communicate_unmet": communicate_unmet,
                "num_messages": len(sim.get_messages()),
                "duration": sim.duration,
            }
        )

    out_path = args.out or (results_path.parent / "failure-analysis.md")
    lines = [
        "# Failure Analysis",
        "",
        f"- run: `{args.run_dir}`",
        f"- official git commit in results: `{results.info.git_commit}`",
        f"- simulations analyzed: {len(results.simulations)}, failed: {len(failed)}",
        "",
        "## Failure buckets (from official termination reasons + reward components)",
        "",
        "| Bucket | Count |",
        "|---|---|",
    ]
    for label, count in buckets.most_common():
        lines.append(f"| {label} | {count} |")
    lines += ["", "## Per-task details (official data only)", ""]
    for row in rows:
        lines.append(
            f"- task {row['task_id']} (trial {row['trial']}): reward={row['reward']}, "
            f"termination={row['termination_reason']}, db_match={row['db_match']}, "
            f"messages={row['num_messages']}"
            + (
                f", communicate unmet: {row['communicate_unmet']}"
                if row["communicate_unmet"]
                else ""
            )
        )
    lines += [
        "",
        "## Notes",
        "",
        "- This analysis never compares against gold actions; it only buckets",
        "  outcomes the official evaluator and runner produced.",
        "- To diagnose a specific failure, inspect the official trajectory",
        "  with `tau2 view --file <path>/results.json`.",
        "",
    ]
    out_path.write_text("\n".join(lines), encoding="utf-8")
    print(f"failure analysis -> {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
