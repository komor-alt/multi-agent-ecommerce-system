"""Supplemental guard-event metrics for the τ³ Retail adapter.

These are benchmark-internal events, NOT part of the official τ³ reward.
The official reward (RewardInfo.reward in the official Results files) is the
primary metric; everything here is supplemental (see requirements §18).

Guard events deliberately record only public decisions:
    {type, action, tool, reasonCode, turn}
They never record chain-of-thought, hidden reasoning, prompts, or model output.
"""

from __future__ import annotations

from enum import Enum
from typing import Any, Optional

from pydantic import BaseModel, Field


class GuardEventType(str, Enum):
    """Structured benchmark-internal guard events."""

    PLAN_PROPOSED = "PLAN_PROPOSED"
    TOOL_ALLOWED = "TOOL_ALLOWED"
    TOOL_REJECTED = "TOOL_REJECTED"
    CONFIRMATION_REQUIRED = "CONFIRMATION_REQUIRED"
    CONFIRMATION_ACCEPTED = "CONFIRMATION_ACCEPTED"
    AUTH_REQUIRED = "AUTH_REQUIRED"
    FALLBACK = "FALLBACK"


class GuardEvent(BaseModel):
    """A single guard event.

    Only public data: what was proposed, which tool, why (reasonCode), and
    which turn. Never reasoning traces.
    """

    type: GuardEventType = Field(description="The type of the guard event.")
    action: Optional[str] = Field(
        default=None, description="Short human-readable summary of the proposed action."
    )
    tool: Optional[str] = Field(
        default=None, description="The name of the tool involved (if any)."
    )
    reasonCode: Optional[str] = Field(
        default=None, description="Machine-readable reason code."
    )
    turn: Optional[int] = Field(
        default=None, description="The conversation turn the event occurred in."
    )


# =============================================================================
# Supplemental metrics (never substitute the official reward)
# =============================================================================


def aggregate_guard_metrics(events: list[GuardEvent]) -> dict[str, Any]:
    """Aggregate guard events into supplemental metrics.

    Returns a dict with counts that can be reported per-run:
        - guard_events: total count
        - tool_rejections, confirmation_blocks, auth_blocks, fallbacks
        - allowed_mutations (TOOL_ALLOWED on a MUTATION tool)
    """
    counts: dict[str, int] = {
        "guard_events": len(events),
        "tool_rejections": 0,
        "confirmation_blocks": 0,
        "auth_blocks": 0,
        "fallbacks": 0,
        "allowed_mutations": 0,
    }
    for event in events:
        if event.type == GuardEventType.TOOL_REJECTED:
            counts["tool_rejections"] += 1
        elif event.type == GuardEventType.CONFIRMATION_REQUIRED:
            counts["confirmation_blocks"] += 1
        elif event.type == GuardEventType.AUTH_REQUIRED:
            counts["auth_blocks"] += 1
        elif event.type == GuardEventType.FALLBACK:
            counts["fallbacks"] += 1
        elif event.type == GuardEventType.TOOL_ALLOWED and event.tool:
            counts["allowed_mutations"] += 1
    return counts


# =============================================================================
# Metrics computed from official results (all numbers come from actual runs)
# =============================================================================


def summarize_results(df) -> dict[str, Any]:
    """Compute summary metrics from an official tau2 results DataFrame.

    The DataFrame is produced by the official `Results.df_from_path` /
    `Results.to_df` helpers; every value here is derived from actual runs.
    Returns a dict with keys used by scripts/summarize.py; never fabricates
    numbers (missing data -> None, reported as "unavailable").
    """
    if df is None or len(df) == 0:
        return {"tasks": 0, "trials": 0, "reward": None, "success_rate": None}

    rewards = df["reward"].dropna()
    summary = {
        "tasks": int(df["task_id"].nunique()),
        "trials": int(len(df)),
        "reward": float(rewards.mean()) if len(rewards) else None,
        "success_rate": (
            float((df["reward"] == 1.0).mean()) if len(df) else None
        ),
        "avg_tool_calls": None,  # computed by caller from messages if available
        "avg_turns": (
            float(df["num_messages"].mean()) if "num_messages" in df.columns else None
        ),
    }
    return summary
