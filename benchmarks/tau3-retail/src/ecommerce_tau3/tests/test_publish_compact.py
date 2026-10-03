"""Offline release integrity: no result cherry-picking or fabricated totals."""
from copy import deepcopy
from pathlib import Path

import pytest

from publish_compact import merge, summarize, verify


def result(implementation, rewards):
    return {
        "info": {"git_commit": "historical", "seed": 300,
                 "agent_info": {"implementation": implementation, "llm": "same-model"}},
        "tasks": [{"id": str(i)} for i in range(len(rewards))],
        "simulations": [{"task_id": str(i), "trial": 0, "seed": 626729,
                         "reward_info": {"reward": r},
                         "termination_reason": "infrastructure_error" if r is None else "user_stop"}
                        for i, r in enumerate(rewards)],
    }


def test_resume_only_unscored_preserves_real_failure():
    base = result("llm_agent", [1, 1])
    guarded = result("guarded_retail_agent", [0, None])
    shard = result("guarded_retail_agent", [0, 1])
    shard["simulations"] = shard["simulations"][1:]
    shard["info"]["git_commit"] = "later-source-disclosed"
    _, g, origins = merge(base, guarded, [("resume", shard)])
    assert g[("0", 0, 626729)]["reward_info"]["reward"] == 0
    assert origins[("1", 0, 626729)] == "resume"


@pytest.mark.parametrize("change", ["scored", "seed", "config", "task", "duplicate", "unscored"])
def test_invalid_resume_fails_closed(change):
    base = result("llm_agent", [1])
    guarded = result("guarded_retail_agent", [None])
    shard = result("guarded_retail_agent", [1])
    if change == "scored":
        guarded["simulations"][0]["reward_info"]["reward"] = 0
    elif change == "seed":
        shard["simulations"][0]["seed"] += 1
    elif change == "config":
        shard["info"]["agent_info"]["llm"] = "different-model"
    elif change == "task":
        shard["tasks"][0]["changed"] = True
    elif change == "duplicate":
        shard["simulations"].append(deepcopy(shard["simulations"][0]))
    else:
        shard["simulations"][0]["reward_info"]["reward"] = None
    with pytest.raises(ValueError):
        merge(base, guarded, [("resume", shard)])


def test_paired_denominator_excludes_infrastructure_not_failures():
    rows = [{"taskId": str(i), "baseline": {"reward": b, "toolCalls": 2},
             "guarded": {"reward": g, "toolCalls": 1}}
            for i, (b, g) in enumerate([(1, 0), (0, 1), (1, None)])]
    summary = summarize(rows)
    assert summary["pairedEvaluated"] == 2
    assert summary["guarded"]["pairedSuccessRate"] == 0.5
    assert summary["baseline"]["successRate"] == 2 / 3


def test_checked_in_public_results_are_internally_consistent():
    directory = Path(__file__).resolve().parents[3] / "published/retail-base-single-trial"
    assert directory.is_dir(), "checked-in benchmark artifact must be present in CI"
    verify(directory)
