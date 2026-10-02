#!/usr/bin/env python3
"""Summarize an official τ³ Retail run directory into comparison.json / .md
and a reproducibility metadata.json.

Reads ONLY official artifacts:
  - results.json produced by the official runner (Results format), loaded
    with the official `tau2.data_model.simulation.Results` loader, and
  - (optional) guard_events.json written by the guarded-agent driver
    (supplemental adapter events, never part of official results).

Before writing anything, the baseline and guarded runs are validated as a
fair pair: the INSTALLED tau2 dependency commit (read from the installed
distribution's direct_url.json) must equal the tau2 commit pinned in
benchmark-lock.json — fail closed if the installed commit is unknown — and
the runner git commits recorded in the official results (the working-tree
commit the official runner stamped at run time, NOT the tau2 dependency
commit) must equal each other. Also checked: same domain, same agent LLM +
args, same user implementation/LLM/args, same seed, trials, max_steps, and
the exact same (task_id, trial) set. Any mismatch aborts with a clear error
and writes nothing. The agent implementation is expected to differ between
the two experiments.

Every number in the output comes from an actual run; missing data is
reported as "unavailable", never estimated (requirements §18 / §24 / §41).
metadata.json records reproducibility facts only — it never contains scores;
scores live in comparison.json and are always derived from actual official
results.

Usage:
    uv run python scripts/summarize.py results/<timestamp> \
        [--baseline official-baseline] [--guarded guarded-agent]
"""

from __future__ import annotations

import argparse
import importlib.metadata
import json
import math
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

from ecommerce_tau3.metrics import aggregate_guard_metrics

# benchmark-lock.json lives next to the scripts/ directory, so the default
# works regardless of the working directory the script is invoked from.
DEFAULT_LOCK_PATH = Path(__file__).resolve().parents[1] / "benchmark-lock.json"


def _load_results(run_dir: Path) -> dict:
    """Load a run's official results + optional guard events."""
    results_path = run_dir / "results.json"
    if not results_path.exists():
        raise FileNotFoundError(f"No official results.json in {run_dir}")

    from tau2.data_model.simulation import Results

    results = Results.load(results_path)
    df = results.to_df()

    # Tool calls per simulation, counted from the official trajectories.
    tool_calls: list[int] = []
    for sim in results.simulations:
        n = sum(
            1
            for m in sim.get_messages()
            if getattr(m, "is_tool_call", lambda: False)()
        )
        tool_calls.append(n)

    evaluated_mask = df["reward"].notna()
    evaluated_df = df[evaluated_mask]
    evaluated_tool_calls = [
        count
        for count, evaluated in zip(tool_calls, evaluated_mask.tolist())
        if evaluated
    ]

    info = results.info
    entry = {
        "implementation": info.agent_info.implementation,
        "agent_llm": info.agent_info.llm,
        "agent_llm_args": info.agent_info.llm_args,
        "user_implementation": info.user_info.implementation,
        "user_llm": info.user_info.llm,
        "user_llm_args": info.user_info.llm_args,
        "domain": info.environment_info.domain_name,
        "seed": info.seed,
        "num_trials": info.num_trials,
        "max_steps": info.max_steps,
        # The official runner stamps info.git_commit with the git commit of
        # its CURRENT WORKING DIRECTORY (tau2.utils.utils.get_commit_hash
        # runs `git rev-parse HEAD`), i.e. the runner/project working tree at
        # run time — NOT the installed tau2 dependency commit (that one is
        # read separately, see _installed_tau2_commit).
        "runner_git_commit": info.git_commit,
        "tasks": int(df["task_id"].nunique()),
        "simulations": int(len(df)),
        "evaluated_tasks": int(evaluated_df["task_id"].nunique()),
        "evaluated_simulations": int(len(evaluated_df)),
        "infrastructure_errors": int(len(df) - len(evaluated_df)),
        "official_reward_mean": (
            round(float(evaluated_df["reward"].mean()), 4)
            if len(evaluated_df)
            else None
        ),
        "success_rate": (
            round(float((evaluated_df["reward"] == 1.0).mean()), 4)
            if len(evaluated_df)
            else None
        ),
        "avg_turns": (
            round(float(evaluated_df["num_messages"].mean()), 2)
            if len(evaluated_df)
            else None
        ),
        "avg_tool_calls": (
            round(sum(evaluated_tool_calls) / len(evaluated_tool_calls), 2)
            if evaluated_tool_calls
            else None
        ),
        "per_task": [
            {
                "task_id": row["task_id"],
                "trial": row["trial"],
                "reward": row["reward"],
                "termination_reason": row["termination_reason"],
            }
            for _, row in df.iterrows()
        ],
    }
    return entry


def _load_guard_events(run_dir: Path) -> dict:
    guard_path = run_dir / "guard_events.json"
    if not guard_path.exists():
        return {}
    raw = json.loads(guard_path.read_text(encoding="utf-8"))

    from ecommerce_tau3.metrics import GuardEvent

    events = [GuardEvent.model_validate(e) for block in raw for e in block["events"]]
    return aggregate_guard_metrics(events)


def _task_trial_set(entry: dict) -> set[tuple[str, object]]:
    """The exact (task_id, trial) pairs actually run, as a comparable set."""
    return {(str(row["task_id"]), row["trial"]) for row in entry["per_task"]}


def _shared_evaluated_metrics(baseline: dict, guarded: dict) -> dict:
    """Compare rewards only where both sides produced an evaluated result."""
    def has_reward(row: dict) -> bool:
        value = row["reward"]
        return value is not None and not math.isnan(float(value))

    baseline_rows = {
        (str(row["task_id"]), row["trial"]): row
        for row in baseline["per_task"]
        if has_reward(row)
    }
    guarded_rows = {
        (str(row["task_id"]), row["trial"]): row
        for row in guarded["per_task"]
        if has_reward(row)
    }
    shared_keys = baseline_rows.keys() & guarded_rows.keys()

    def summarize(rows: dict) -> dict:
        rewards = [float(rows[key]["reward"]) for key in shared_keys]
        successes = sum(reward == 1.0 for reward in rewards)
        return {
            "evaluated_simulations": len(rewards),
            "successes": successes,
            "official_reward_mean": (
                round(sum(rewards) / len(rewards), 4) if rewards else None
            ),
            "success_rate": (
                round(successes / len(rewards), 4) if rewards else None
            ),
        }

    return {
        "shared_task_trials": len(shared_keys),
        "baseline": summarize(baseline_rows),
        "guarded": summarize(guarded_rows),
    }


def _installed_tau2_commit() -> Optional[str]:
    """Commit of the INSTALLED tau2 package, or None when unknown.

    uv writes a direct_url.json into the installed distribution for git
    dependencies; the pinned SHA lives in its vcs_info.commit_id entry (the
    same read setup_tau3.sh performs). Returns None when the distribution,
    direct_url.json, or vcs_info commit is missing/unparseable — callers
    fail closed on that.
    """
    try:
        dist = importlib.metadata.distribution("tau2")
    except importlib.metadata.PackageNotFoundError:
        return None
    direct_url = dist.read_text("direct_url.json")
    if not direct_url:
        return None
    try:
        vcs_info = json.loads(direct_url).get("vcs_info") or {}
    except json.JSONDecodeError:
        return None
    return vcs_info.get("commit_id")


def _validate_fair_pair(
    baseline: dict, guarded: dict, lock: dict, installed_tau2_commit: Optional[str] = None
) -> dict:
    """Validate both experiments form a fair pair; raise ValueError otherwise.

    Checks (in order): the INSTALLED tau2 dependency commit equals the tau2
    commit pinned in benchmark-lock.json — fail closed if the installed
    commit is unknown; the runner git commits stamped into the official
    results (the working-tree commit at run time, NOT the tau2 dependency
    commit) equal each other; both runs in the locked environment domain;
    identical agent LLM and LLM args; identical user implementation, LLM and
    LLM args; identical seed, num_trials and max_steps; identical
    (task_id, trial) set. The agent implementation is expected to differ
    between the two runs.

    installed_tau2_commit may be passed in (main threads it through so the
    read happens once); when None it is looked up via _installed_tau2_commit.

    On success returns the parity record (validated + compared field values)
    written into metadata.json. On mismatch raises ValueError with every
    differing field; nothing is written.
    """
    lock_commit = lock["commit"]
    lock_domain = lock["domain"]

    installed_commit = installed_tau2_commit
    if installed_commit is None:
        installed_commit = _installed_tau2_commit()
    if installed_commit is None:
        raise ValueError(
            "Refusing to summarize: the installed tau2 commit is unknown "
            "(no tau2 distribution / direct_url.json / vcs_info commit). "
            "Run setup_tau3.sh to install the pinned dependency. Nothing "
            "was written."
        )

    checks = [
        (
            "installed tau2 commit == benchmark-lock.json",
            installed_commit,
            lock_commit,
        ),
        (
            "runner git commit (baseline vs guarded)",
            baseline["runner_git_commit"],
            guarded["runner_git_commit"],
        ),
        ("environment domain == benchmark-lock.json", baseline["domain"], lock_domain),
        ("environment domain == benchmark-lock.json", guarded["domain"], lock_domain),
        ("agent LLM", baseline["agent_llm"], guarded["agent_llm"]),
        ("agent LLM args", baseline["agent_llm_args"], guarded["agent_llm_args"]),
        (
            "user implementation",
            baseline["user_implementation"],
            guarded["user_implementation"],
        ),
        ("user LLM", baseline["user_llm"], guarded["user_llm"]),
        ("user LLM args", baseline["user_llm_args"], guarded["user_llm_args"]),
        ("seed", baseline["seed"], guarded["seed"]),
        ("num_trials", baseline["num_trials"], guarded["num_trials"]),
        ("max_steps", baseline["max_steps"], guarded["max_steps"]),
        ("(task_id, trial) set", _task_trial_set(baseline), _task_trial_set(guarded)),
    ]

    mismatches = [
        f"  - {label}: {a!r} != {b!r}" for label, a, b in checks if a != b
    ]
    if mismatches:
        raise ValueError(
            "Refusing to summarize: the baseline and guarded runs are not a "
            "fair pair.\n"
            + "\n".join(mismatches)
            + "\nRe-run both experiments with identical settings (README "
            "'Fair comparison'). Nothing was written."
        )

    if baseline["implementation"] == guarded["implementation"]:
        raise ValueError(
            "Refusing to summarize: both runs used the same agent "
            f"implementation {baseline['implementation']!r}; a fair comparison "
            "requires two different implementations. Nothing was written."
        )

    return {
        "validated": True,
        "fields": {
            "tau2Commit": installed_commit,  # installed, verified == lock
            "runnerGitCommit": baseline["runner_git_commit"],  # verified == guarded's
            "domain": lock_domain,
            "agentLlm": baseline["agent_llm"],
            "agentLlmArgs": baseline["agent_llm_args"],
            "userImplementation": baseline["user_implementation"],
            "userLlm": baseline["user_llm"],
            "userLlmArgs": baseline["user_llm_args"],
            "seed": baseline["seed"],
            "numTrials": baseline["num_trials"],
            "maxSteps": baseline["max_steps"],
            # Sorted JSON-shaped list of [task_id, trial] pairs, as written
            # into metadata.json (tuples would break the JSON shape).
            "taskTrialSet": sorted(
                (list(pair) for pair in _task_trial_set(baseline)), key=str
            ),
        },
    }


def _build_metadata(
    baseline: dict,
    guarded: dict,
    lock: dict,
    parity: dict,
    baseline_name: str,
    guarded_name: str,
    installed_tau2_commit: Optional[str] = None,
) -> dict:
    """Reproducibility metadata for the run pair.

    Reproducibility facts only: UTC generation time, the pinned
    repository/domain/task split from benchmark-lock.json, the pinned tau2
    commit ("pinnedTau2Commit" = lock "commit") and the INSTALLED tau2 commit
    verified against it ("installedTau2Commit", distinct from the pinned one
    in origin but validation-ensured equal), the runner git commit stamped
    into the official results ("officialResultsRunnerGitCommit" — the
    working-tree commit at run time, NOT the tau2 commit), per-run model +
    args, seed/max_steps, task/simulation/trial counts and parity status.
    Never contains scores — comparison.json holds those, always derived from
    the actual official results.

    installed_tau2_commit may be passed in (main threads it through so the
    read happens once); when None it is read from the validated parity
    record.
    """

    def run_block(entry: dict) -> dict:
        return {
            "implementation": entry["implementation"],
            "agentModel": entry["agent_llm"],
            "agentLlmArgs": entry["agent_llm_args"],
            "userModel": entry["user_llm"],
            "userLlmArgs": entry["user_llm_args"],
            "seed": entry["seed"],
            "numTrials": entry["num_trials"],
            "maxSteps": entry["max_steps"],
            "taskCount": entry["tasks"],
            "simulationCount": entry["simulations"],
            "trialCount": len({row["trial"] for row in entry["per_task"]}),
        }

    installed_commit = installed_tau2_commit
    if installed_commit is None:
        installed_commit = parity["fields"]["tau2Commit"]

    return {
        "generatedUtc": datetime.now(timezone.utc).isoformat(),
        "repository": lock["repository"],
        # Pinned tau2 commit from benchmark-lock.json, the benchmark the runs
        # are supposed to use; "commit" is the historical short alias.
        "pinnedTau2Commit": lock["commit"],
        "commit": lock["commit"],
        # ...the commit of the tau2 package actually installed in the run
        # environment, validated equal to the pin above.
        "installedTau2Commit": installed_commit,
        # The commit the official runner stamped into the results
        # (Results.info.git_commit = the runner's working-tree commit at run
        # time) — deliberately NOT the tau2 commit.
        "officialResultsRunnerGitCommit": parity["fields"]["runnerGitCommit"],
        "domain": lock["domain"],
        "taskSplit": lock["taskSplit"],
        "runs": {
            baseline_name: run_block(baseline),
            guarded_name: run_block(guarded),
        },
        "parity": parity,
    }


def build_parser() -> argparse.ArgumentParser:
    """Parser with an ASCII-only description (Windows consoles: --help must
    not emit non-ASCII characters, see test_run_cli.py for the same rule)."""
    parser = argparse.ArgumentParser(
        description=(
            "Summarize an official tau3 Retail run directory into "
            "comparison.json/.md and a fair-pair-validated metadata.json. "
            "Reads ONLY official artifacts; missing data is reported as "
            "'unavailable', never estimated."
        )
    )
    parser.add_argument(
        "run_dir", type=Path, help="results/<timestamp> directory"
    )
    parser.add_argument(
        "--baseline",
        default="official-baseline",
        help="baseline experiment subdirectory (default: official-baseline)",
    )
    parser.add_argument(
        "--guarded",
        default="guarded-agent",
        help="guarded experiment subdirectory (default: guarded-agent)",
    )
    parser.add_argument(
        "--lock",
        type=Path,
        default=DEFAULT_LOCK_PATH,
        help="path to benchmark-lock.json (default: next to scripts/)",
    )
    return parser


def main(argv: Optional[list[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    if not args.run_dir.is_dir():
        parser.error(f"run dir not found: {args.run_dir}")
    if not args.lock.is_file():
        parser.error(f"benchmark-lock.json not found: {args.lock}")

    with open(args.lock, "r", encoding="utf-8") as f:
        lock = json.load(f)

    baseline = _load_results(args.run_dir / args.baseline)
    guarded = _load_results(args.run_dir / args.guarded)
    guarded_events = _load_guard_events(args.run_dir / args.guarded)

    # Read the installed tau2 commit once and thread it through validation
    # (which fails closed when it is unknown) and metadata generation.
    installed_commit = _installed_tau2_commit()
    try:
        parity = _validate_fair_pair(baseline, guarded, lock, installed_commit)
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    comparison = {
        "tau3": {
            "repository": lock["repository"],
            "commit": lock["commit"],
            "domain": lock["domain"],
            "taskSplit": lock["taskSplit"],
        },
        "baseline": baseline,
        "guarded": guarded,
        "shared_evaluated": _shared_evaluated_metrics(baseline, guarded),
        "guarded_guard_metrics": guarded_events or None,
    }

    out_json = args.run_dir / "comparison.json"
    out_md = args.run_dir / "comparison.md"
    out_meta = args.run_dir / "metadata.json"
    out_json.write_text(json.dumps(comparison, indent=2), encoding="utf-8")
    out_md.write_text(_render_markdown(comparison, parity), encoding="utf-8")
    out_meta.write_text(
        json.dumps(
            _build_metadata(
                baseline, guarded, lock, parity, args.baseline, args.guarded,
                installed_tau2_commit=installed_commit,
            ),
            indent=2,
        ),
        encoding="utf-8",
    )

    print(f"metadata.json  -> {out_meta}")
    print(f"comparison.json -> {out_json}")
    print(f"comparison.md   -> {out_md}")
    return 0


def _render_markdown(comparison: dict, parity: dict) -> str:
    b = comparison["baseline"]
    g = comparison["guarded"]
    gm = comparison.get("guarded_guard_metrics") or {}
    shared = comparison.get("shared_evaluated") or {}
    shared_b = shared.get("baseline") or {}
    shared_g = shared.get("guarded") or {}
    tau3 = comparison["tau3"]

    def cell(entry, key) -> str:
        value = entry.get(key)
        return "unavailable" if value is None else str(value)

    lines = [
        "# τ³-bench Retail — Comparison",
        "",
        f"- τ³ commit: `{tau3.get('commit', 'unavailable')}`",
        f"- domain: {tau3.get('domain', 'unavailable')}, task split: {tau3.get('taskSplit', 'unavailable')}",
        "",
        "| Metric | LLMAgent (baseline) | GuardedRetailAgent |",
        "|---|---|---|",
        f"| Official Reward (mean, evaluated only) | {cell(b, 'official_reward_mean')} | {cell(g, 'official_reward_mean')} |",
        f"| Success Rate (evaluated only) | {cell(b, 'success_rate')} | {cell(g, 'success_rate')} |",
        f"| Evaluated / Total Simulations | {b.get('evaluated_simulations')} / {b.get('simulations')} | {g.get('evaluated_simulations')} / {g.get('simulations')} |",
        f"| Infrastructure Errors | {b.get('infrastructure_errors')} | {g.get('infrastructure_errors')} |",
        f"| Avg Tool Calls / Evaluated Task | {cell(b, 'avg_tool_calls')} | {cell(g, 'avg_tool_calls')} |",
        f"| Avg Turns / Evaluated Task | {cell(b, 'avg_turns')} | {cell(g, 'avg_turns')} |",
        f"| Tasks / Simulations | {b.get('tasks')} / {b.get('simulations')} | {g.get('tasks')} / {g.get('simulations')} |",        "",
        "## Strict paired comparison (only task/trials evaluated by both agents)",
        "",
        f"- shared evaluated task/trials: {shared.get('shared_task_trials', 'unavailable')}",
        "",
        "| Metric | LLMAgent (baseline) | GuardedRetailAgent |",
        "|---|---|---|",
        f"| Successes | {shared_b.get('successes', 'unavailable')} | {shared_g.get('successes', 'unavailable')} |",
        f"| Official Reward Mean | {cell(shared_b, 'official_reward_mean')} | {cell(shared_g, 'official_reward_mean')} |",
        f"| Success Rate | {cell(shared_b, 'success_rate')} | {cell(shared_g, 'success_rate')} |",
        "",
        "## Guarded agent — supplemental guard metrics (never substitute the official reward)",
        "",
        "| Metric | GuardedRetailAgent |",
        "|---|---|",
        f"| Guard Events | {gm.get('guard_events', 'unavailable')} |",
        f"| Tool Rejections | {gm.get('tool_rejections', 'unavailable')} |",
        f"| Confirmation Blocks | {gm.get('confirmation_blocks', 'unavailable')} |",
        f"| Auth Blocks | {gm.get('auth_blocks', 'unavailable')} |",
        f"| Fallbacks | {gm.get('fallbacks', 'unavailable')} |",
        f"| Allowed Mutations | {gm.get('allowed_mutations', 'unavailable')} |",
        "",
        "## Run settings (from official results metadata)",
        "",
        f"- agent model: `{b.get('agent_llm')}` (both experiments)",
        f"- user model: `{b.get('user_llm')}` (both experiments)",
        f"- seed: `{b.get('seed')}`, trials: `{b.get('num_trials')}`, max_steps: `{b.get('max_steps')}`",
        "",
        f"- fair-pair parity: {'validated' if parity.get('validated') else 'FAILED'} (full reproducibility record in metadata.json)",
        "",
        "## Limitations",
        "",
        "- Benchmark adapter != exact Java production codepath.",
        "- tau3 Retail != the project's self-built shipment-delay domain.",
        "- User simulator and agent model both influence results.",
        "- Single-trial runs do not represent stable results (no Pass@k claim).",
        "",
    ]
    return "\n".join(lines)


if __name__ == "__main__":
    raise SystemExit(main())
