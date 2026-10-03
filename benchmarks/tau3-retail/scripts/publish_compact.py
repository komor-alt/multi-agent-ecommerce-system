"""Publish auditable scores, never conversations, from official result files.

Resume shards may replace ONLY unscored infrastructure errors. Scored failures
are immutable. This is a provenance-aware export, not a new benchmark runner.
Raw files stay local; their SHA-256 digests allow comparison with retained files.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from datetime import datetime, timezone
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                     separators=(",", ":")).encode()).hexdigest()


def file_digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def text_digest(path):
    """Git checkouts may convert CRLF/LF; published text hashes normalize LF."""
    return hashlib.sha256(path.read_text("utf-8").encode("utf-8")).hexdigest()


def key(sim):
    return str(sim["task_id"]), sim["trial"], sim["seed"]


def reward(sim):
    return (sim.get("reward_info") or {}).get("reward")


def index(results):
    entries = {}
    for sim in results["simulations"]:
        k = key(sim)
        if k in entries:
            raise ValueError(f"duplicate simulation: {k}")
        r = reward(sim)
        if r is not None and (not math.isfinite(r) or r not in (0, 1)):
            raise ValueError(f"unexpected Retail reward: {k}")
        entries[k] = sim
    return entries


def signature(results):
    info = dict(results["info"])
    info.pop("git_commit", None)
    info["agent_info"] = dict(info["agent_info"])
    info["agent_info"].pop("implementation")
    return digest(info)


def merge(baseline, guarded, shards):
    """Fail closed on changed configuration, tasks, seeds or scored retries."""
    b, g = index(baseline), index(guarded)
    if set(b) != set(g):
        raise ValueError("original baseline/guarded task-trial-seed sets differ")
    if any(reward(s) is None for s in b.values()):
        raise ValueError("baseline is not fully scored")
    tasks = {str(t["id"]): digest(t) for t in baseline["tasks"]}
    if tasks != {str(t["id"]): digest(t) for t in guarded["tasks"]}:
        raise ValueError("original task definitions differ")
    origins = {k: "historical-guarded" for k in g}
    expected = signature(baseline)
    for source, result in [("historical-guarded", guarded), *shards]:
        if signature(result) != expected:
            raise ValueError(f"configuration/policy/tools mismatch: {source}")
        if result["info"]["agent_info"]["implementation"] != "guarded_retail_agent":
            raise ValueError("resume source is not guarded_retail_agent")
        for task in result["tasks"]:
            if tasks.get(str(task["id"])) != digest(task):
                raise ValueError(f"task definition mismatch: {source}")
        if source == "historical-guarded":
            continue
        for k, sim in index(result).items():
            if k not in g or reward(g[k]) is not None:
                raise ValueError(f"cannot replace an evaluated/unknown task: {k}")
            if g[k]["termination_reason"] != "infrastructure_error":
                raise ValueError(f"original missing reward is not infrastructure error: {k}")
            if reward(sim) is None:
                raise ValueError("unscored attempts belong in --attempt, not --shard")
            g[k], origins[k] = sim, source
    return b, g, origins


def compact(sim, source):
    info = sim.get("reward_info") or {}
    error_text = json.dumps(sim.get("info") or {}).lower()
    error = None
    if reward(sim) is None:
        error = ("authentication_failed" if "authentication" in error_text else
                 "insufficient_balance" if "insufficient balance" in error_text else
                 "infrastructure_error")
    return {
        "source": source, "simulationId": sim["id"], "simulationSha256": digest(sim),
        "reward": reward(sim), "rewardBreakdown": info.get("reward_breakdown"),
        "terminationReason": sim["termination_reason"], "errorCategory": error,
        "startTime": sim.get("start_time"), "endTime": sim.get("end_time"),
        "durationSeconds": sim.get("duration"), "messageCount": len(sim.get("messages") or []),
        "toolCalls": sum(len(m.get("tool_calls") or []) for m in sim.get("messages") or []),
    }


def summarize(rows):
    paired = [r for r in rows if all(r[x]["reward"] is not None for x in ("baseline", "guarded"))]
    result = {"totalTasks": len(rows), "pairedEvaluated": len(paired),
              "missingGuardedTaskIds": [r["taskId"] for r in rows if r["guarded"]["reward"] is None]}
    for name in ("baseline", "guarded"):
        evaluated = [r[name] for r in rows if r[name]["reward"] is not None]
        n, success = len(evaluated), sum(r["reward"] == 1 for r in evaluated)
        paired_success = sum(r[name]["reward"] == 1 for r in paired)
        result[name] = {"evaluated": n, "successes": success,
                        "successRate": success / n if n else None,
                        "pairedSuccesses": paired_success,
                        "pairedSuccessRate": paired_success / len(paired) if paired else None,
                        "meanToolCallsEvaluated": sum(r["toolCalls"] for r in evaluated) / n if n else None}
    result["pairedOutcomes"] = {
        "bothPass": sum(r["baseline"]["reward"] == r["guarded"]["reward"] == 1 for r in paired),
        "bothFail": sum(r["baseline"]["reward"] == r["guarded"]["reward"] == 0 for r in paired),
        "guardedOnlyPass": sum(r["guarded"]["reward"] == 1 and r["baseline"]["reward"] == 0 for r in paired),
        "baselineOnlyPass": sum(r["baseline"]["reward"] == 1 and r["guarded"]["reward"] == 0 for r in paired),
    }
    return result


def verify(directory):
    cases = directory / "cases.jsonl"
    rows = [json.loads(line) for line in cases.read_text("utf-8").splitlines()]
    summary = json.loads((directory / "summary.json").read_text("utf-8"))
    metadata = json.loads((directory / "metadata.json").read_text("utf-8"))
    keys = [(r["taskId"], r["trial"], r["seed"]) for r in rows]
    if len(set(keys)) != len(keys) or len(rows) != metadata["expectedTaskCount"]:
        raise ValueError("duplicate or missing published case")
    if summary != summarize(rows) or text_digest(cases) != metadata["casesSha256"]:
        raise ValueError("published summary or case digest mismatch")
    if metadata["completePaired"] != (summary["pairedEvaluated"] == len(rows)):
        raise ValueError("incorrect completePaired claim")
    print(f"Verified {len(rows)} cases; {summary['pairedEvaluated']} scored pairs")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--historical", type=Path)
    parser.add_argument("--shard", action="append", type=Path, default=[])
    parser.add_argument("--attempt", action="append", type=Path, default=[])
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    if args.verify:
        verify(args.output)
        return
    if args.historical is None:
        parser.error("--historical is required to publish")
    sources = {}

    def read(path, label):
        value = json.loads(path.read_text("utf-8"))
        sources[label] = {
            "rawFileSha256": file_digest(path), "rawFileName": path.name,
            "runnerGitCommit": value["info"]["git_commit"],
            "runName": path.parent.name, "simulationCount": len(value["simulations"]),
            "configurationSignature": signature(value),
        }
        return value

    baseline = read(args.historical / "official-baseline/results.json", "historical-baseline")
    guarded = read(args.historical / "guarded-agent/results.json", "historical-guarded")
    shards = [(f"resume-{i}", read(p, f"resume-{i}")) for i, p in enumerate(args.shard)]
    b, g, origins = merge(baseline, guarded, shards)
    attempts = []
    for i, path in enumerate(args.attempt):
        source = f"unscored-attempt-{i}"
        attempt = read(path, source)
        if signature(attempt) != signature(baseline):
            raise ValueError("unscored attempt configuration differs from baseline")
        for k, sim in index(attempt).items():
            if k not in b:
                raise ValueError("unscored attempt task/trial/seed is outside original run")
            if reward(sim) is not None:
                raise ValueError("scored attempt must be included as a shard, never discarded")
            attempts.append({"taskId": k[0], "trial": k[1], "seed": k[2], **compact(sim, source)})
    rows = [{"taskId": k[0], "trial": k[1], "seed": k[2],
             "baseline": compact(b[k], "historical-baseline"),
             "guarded": compact(g[k], origins[k])} for k in sorted(b, key=lambda k: (int(k[0]), k[1], k[2]))]
    if set(r["taskId"] for r in rows) != {str(i) for i in range(114)} or len(rows) != 114:
        raise ValueError("expected exactly the 114 Retail base tasks, one trial each")
    config_path = args.historical / "run-config.yaml"
    config = yaml.safe_load(config_path.read_text("utf-8"))
    summary = summarize(rows)
    # Deliberate atomic creation: never overwrite an existing release artifact.
    args.output.mkdir(parents=True, exist_ok=False)
    cases = args.output / "cases.jsonl"
    cases.write_text("".join(json.dumps(r, ensure_ascii=False, sort_keys=True) + "\n" for r in rows), "utf-8")
    metadata = {
        "schemaVersion": 1, "generatedUtc": datetime.now(timezone.utc).isoformat(),
        "benchmark": "sierra-research/tau2-bench", "benchmarkCommit": config["benchmark"]["commit"],
        "domain": "retail", "split": "base", "expectedTaskCount": 114,
        "completePaired": summary["pairedEvaluated"] == 114,
        "runSeed": baseline["info"]["seed"], "trials": baseline["info"]["num_trials"],
        "maxSteps": baseline["info"]["max_steps"], "maxErrors": baseline["info"]["max_errors"],
        "agentModel": baseline["info"]["agent_info"]["llm"],
        "userModel": baseline["info"]["user_info"]["llm"],
        "llmArgs": {k: baseline["info"]["agent_info"]["llm_args"][k]
                    for k in ("api_base", "temperature", "extra_body")},
        "nlAssertionJudge": config["evaluator"], "configSha256": file_digest(config_path),
        "sources": sources, "unscoredResumeAttempts": attempts, "casesSha256": text_digest(cases),
        "textHashNormalization": "cases and adapter source: UTF-8, normalized LF; raw result/config hashes: original bytes",
        "adapterSourceHashes": {p.relative_to(ROOT).as_posix(): text_digest(p)
                                for p in sorted((ROOT / "src/ecommerce_tau3").glob("*.py"))},
        "limitations": [
            "Python benchmark adapter, not the Java service or a public leaderboard submission.",
            "Historical runner HEAD does not capture uncommitted adapter source. Exact historical source parity is unverified.",
            "Resume is on a later date. A hosted model alias can change despite matching model names and configuration.",
            "One trial only; no claim of statistical significance or multi-agent architectural superiority.",
            "Official evaluator logic is retained; NL assertion judge is configured to DeepSeek, not the upstream default.",
            "Compact export omits conversations, task instructions and tool arguments. Hashes are not a substitute for full trajectory review.",
        ],
    }
    for name, value in (("summary.json", summary), ("metadata.json", metadata)):
        (args.output / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", "utf-8")
    verify(args.output)


if __name__ == "__main__":
    main()
