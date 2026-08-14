"""ToolGuard + official tool inventory pinning tests.

Requirement §10: the adapter's tool classification must be based on the
pinned official retail tools, and if the official tool set changes, tests
must FAIL instead of silently ignoring new tools.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from ecommerce_tau3.agent_state import Tau3AgentState
from ecommerce_tau3.tool_guard import (
    OFFICIAL_RETAIL_TOOL_TYPES,
    ToolCategory,
    ToolGuard,
)

_LOCK_PATH = Path(__file__).resolve().parents[3] / "benchmark-lock.json"


def _lock_snapshot() -> dict[str, str]:
    with open(_LOCK_PATH, "r", encoding="utf-8") as f:
        lock = json.load(f)
    return lock["snapshot"]["retailTools"]


def _stub_tool(name: str, tool_type: str):
    """Build an official Tool object from a stub function (offline)."""
    from tau2.environment.tool import as_tool
    from tau2.environment.toolkit import is_tool, ToolType

    def tool_func(**kwargs) -> str:
        """Stub tool.

        Args:
            **kwargs: arbitrary stub arguments.

        Returns:
            An empty stub result.
        """
        return ""

    tool_func.__name__ = name
    return as_tool(is_tool(ToolType(tool_type))(tool_func))


def _stub_guard() -> ToolGuard:
    tools = [
        _stub_tool(name, ttype)
        for name, ttype in OFFICIAL_RETAIL_TOOL_TYPES.items()
    ]
    return ToolGuard(tools)


# ---------------------------------------------------------------------------
# Pinning: official tool set must match the lock snapshot
# ---------------------------------------------------------------------------


def test_official_retail_inventory_matches_lock_snapshot():
    """The LIVE official RetailTools must match benchmark-lock.json.

    If the official tool set changes (new tool, renamed tool, changed type),
    this test fails loudly instead of silently ignoring the change.
    """
    from tau2.domains.retail.tools import RetailTools

    toolkit = RetailTools(db=None)
    actual = {
        name: toolkit.tool_type(name).value for name in sorted(toolkit.tools)
    }
    assert actual == _lock_snapshot(), (
        "Official retail tool inventory differs from benchmark-lock.json. "
        "The pinned τ³ version changed — review the change, update the lock "
        "snapshot deliberately, and re-verify the guard classification."
    )


def test_guard_snapshot_matches_lock_snapshot():
    guard = _stub_guard()
    assert guard.classification_snapshot() == _lock_snapshot()


# ---------------------------------------------------------------------------
# Classification
# ---------------------------------------------------------------------------


def test_classification_of_official_tools():
    guard = _stub_guard()
    assert guard.classify("get_order_details") is ToolCategory.READ_ONLY
    assert guard.classify("get_user_details") is ToolCategory.READ_ONLY
    assert guard.classify("find_user_id_by_email") is ToolCategory.READ_ONLY
    assert guard.classify("cancel_pending_order") is ToolCategory.MUTATION
    assert guard.classify("return_delivered_order_items") is ToolCategory.MUTATION
    assert guard.classify("modify_user_address") is ToolCategory.MUTATION
    assert guard.classify("transfer_to_human_agents") is ToolCategory.HANDOFF
    assert guard.classify("calculate") is ToolCategory.GENERIC


def test_fail_closed_on_new_official_tool():
    """A tool that exists in the environment but is NOT in the pinned
    snapshot must fail closed (never silently pass through)."""
    tools = [_stub_tool("get_order_details", "read"), _stub_tool("brand_new_tool", "write")]
    guard = ToolGuard(tools)
    state = Tau3AgentState()
    assert guard.classify("brand_new_tool") is ToolCategory.FAIL_CLOSED
    result = guard.check("brand_new_tool", {}, state)
    assert result.allowed is False
    assert result.reason_code == "UNKNOWN_TOOL_FAIL_CLOSED"


def test_fail_closed_on_unknown_name():
    guard = _stub_guard()
    assert guard.classify("does_not_exist") is ToolCategory.FAIL_CLOSED
    result = guard.check("does_not_exist", {}, Tau3AgentState())
    assert result.allowed is False
    assert result.reason_code == "UNKNOWN_TOOL_FAIL_CLOSED"
