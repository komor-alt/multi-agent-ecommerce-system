"""ToolGuard: classifies official τ³ tools and gates mutation calls.

The official agent factory gives us `list[Tool]` — Tool objects carry the
OpenAI schema but NOT the official ToolType (the type lives on the toolkit,
see tau2.environment.toolkit). Classification is therefore built from a
snapshot of the OFFICIAL RetailTools metadata of the pinned τ³ commit
(src/tau2/domains/retail/tools.py), and is enforced by tests:

    READ      -> READ_ONLY
    WRITE     -> MUTATION   (cancel / modify / return / exchange / address)
    GENERIC   -> HANDOFF    (transfer_to_human_agents) else GENERIC
    unknown or unlisted -> FAIL_CLOSED   (new/changed official tools fail
                                          loudly, never silently ignored)

Requirements §10: classification must be based on the pinned official tool
set, and if the official tool set changes the tests must fail
(tests/test_tool_guard.py compares the LIVE official RetailTools inventory
against this snapshot and against benchmark-lock.json).
"""

from __future__ import annotations

import enum
from typing import Optional

from tau2.environment.tool import Tool

from ecommerce_tau3.agent_state import Tau3AgentState
from ecommerce_tau3.policy_guard import PolicyGuard


class ToolCategory(str, enum.Enum):
    READ_ONLY = "read_only"
    MUTATION = "mutation"
    HANDOFF = "handoff"
    GENERIC = "generic"
    FAIL_CLOSED = "fail_closed"


# Handoff is a behavioral category, not a ToolType: only tools whose purpose
# is transferring the user to a human count.
_HANDOFF_TOOL_NAMES = frozenset({"transfer_to_human_agents"})

# Classification snapshot of the OFFICIAL RetailTools @ pinned commit
# 79975ac5741e23fbb1d2ac44262d62398a6d87bd (src/tau2/domains/retail/tools.py).
# Values are the official ToolType values. MUST match benchmark-lock.json;
# tests fail loudly on any drift or official tool-set change.
OFFICIAL_RETAIL_TOOL_TYPES: dict[str, str] = {
    "calculate": "generic",
    "cancel_pending_order": "write",
    "exchange_delivered_order_items": "write",
    "find_user_id_by_email": "read",
    "find_user_id_by_name_zip": "read",
    "get_item_details": "read",
    "get_order_details": "read",
    "get_product_details": "read",
    "get_user_details": "read",
    "list_all_product_types": "read",
    "modify_pending_order_address": "write",
    "modify_pending_order_items": "write",
    "modify_pending_order_payment": "write",
    "modify_user_address": "write",
    "return_delivered_order_items": "write",
    "transfer_to_human_agents": "generic",
}


class ToolCheckResult:
    """Outcome of a ToolGuard.check() call."""

    __slots__ = ("allowed", "reason_code")

    def __init__(self, allowed: bool, reason_code: Optional[str] = None):
        self.allowed = allowed
        self.reason_code = reason_code

    def __repr__(self) -> str:  # pragma: no cover - debug helper
        return f"ToolCheckResult(allowed={self.allowed}, reason_code={self.reason_code})"


class ToolGuard:
    """Server-side guard placed in front of every tool call.

    The LLM proposes; the guard decides. READ_ONLY tools pass through after
    PolicyGuard authentication checks; MUTATION tools additionally require a
    valid, matching, unconsumed confirmation (PolicyGuard). Tools that exist
    in the environment but are not in the pinned snapshot fail closed.
    """

    def __init__(self, tools: list[Tool]):
        self._tools: dict[str, Tool] = {tool.name: tool for tool in tools}

    # ------------------------------------------------------------------
    # Classification (from the pinned official tool metadata)
    # ------------------------------------------------------------------

    def classify(self, tool_name: str) -> ToolCategory:
        tool = self._tools.get(tool_name)
        if tool is None:
            return ToolCategory.FAIL_CLOSED
        if tool_name in _HANDOFF_TOOL_NAMES:
            return ToolCategory.HANDOFF
        tool_type = OFFICIAL_RETAIL_TOOL_TYPES.get(tool_name)
        if tool_type is None:
            # The tool exists in the environment but is not in the pinned
            # snapshot: official tool set changed -> fail closed, never
            # silently ignore the new tool.
            return ToolCategory.FAIL_CLOSED
        if tool_type == "write":
            return ToolCategory.MUTATION
        if tool_type == "read":
            return ToolCategory.READ_ONLY
        return ToolCategory.GENERIC  # generic / think / unknown-type value

    @property
    def tool_names(self) -> set[str]:
        return set(self._tools.keys())

    def classification_snapshot(self) -> dict[str, str]:
        """tool_name -> official ToolType value, for lock/snapshot verification."""
        return {
            name: OFFICIAL_RETAIL_TOOL_TYPES[name]
            for name in sorted(self._tools)
            if name in OFFICIAL_RETAIL_TOOL_TYPES
        }

    # ------------------------------------------------------------------
    # Guard logic
    # ------------------------------------------------------------------

    def check(
        self, tool_name: str, arguments: dict, state: Tau3AgentState
    ) -> ToolCheckResult:
        """Check whether a proposed tool call may be executed.

        Only structural checks live here; authentication/ownership/confirmation
        are enforced by PolicyGuard, which this method invokes for READ and
        MUTATION tools (defense-in-depth — the full official policy stays in
        the system prompt and is never rewritten by the guard).
        """
        category = self.classify(tool_name)
        if category is ToolCategory.FAIL_CLOSED:
            return ToolCheckResult(
                allowed=False,
                reason_code="UNKNOWN_TOOL_FAIL_CLOSED",
            )

        if category is ToolCategory.READ_ONLY:
            return PolicyGuard().check_read(tool_name, arguments, state)

        if category in (ToolCategory.MUTATION, ToolCategory.HANDOFF):
            return PolicyGuard().check_mutation(tool_name, arguments, state)

        return ToolCheckResult(allowed=True)  # GENERIC (e.g. calculate)
