from __future__ import annotations

import pandas as pd

from ecommerce_tau3.metrics import (
    GuardEvent,
    GuardEventType,
    aggregate_guard_metrics,
    summarize_results,
)


def _allowed(tool: str) -> GuardEvent:
    return GuardEvent(type=GuardEventType.TOOL_ALLOWED, tool=tool)


def test_allowed_mutations_excludes_read_and_handoff_tools():
    metrics = aggregate_guard_metrics(
        [
            _allowed("get_order_details"),
            _allowed("transfer_to_human_agents"),
            _allowed("modify_pending_order_address"),
            _allowed("cancel_pending_order"),
        ]
    )

    assert metrics["guard_events"] == 4
    assert metrics["allowed_mutations"] == 2


def test_guard_event_counters_remain_independent():
    events = [
        GuardEvent(type=GuardEventType.TOOL_REJECTED),
        GuardEvent(type=GuardEventType.CONFIRMATION_REQUIRED),
        GuardEvent(type=GuardEventType.AUTH_REQUIRED),
        GuardEvent(type=GuardEventType.FALLBACK),
    ]

def test_summarize_results_excludes_infrastructure_errors():
    df = pd.DataFrame(
        [
            {"task_id": "1", "reward": 1.0, "num_messages": 8},
            {"task_id": "2", "reward": 0.0, "num_messages": 12},
            {"task_id": "3", "reward": None, "num_messages": 0},
        ]
    )

    summary = summarize_results(df)

    assert summary["tasks"] == 3
    assert summary["trials"] == 3
    assert summary["evaluated_tasks"] == 2
    assert summary["evaluated_trials"] == 2
    assert summary["infrastructure_errors"] == 1
    assert summary["reward"] == 0.5
    assert summary["success_rate"] == 0.5
    assert summary["avg_turns"] == 10.0
