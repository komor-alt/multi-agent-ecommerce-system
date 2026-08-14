"""Agent state for the τ³ Retail trust-boundary adapter.

Mirrors the Java after-sales agent's trust-boundary state concepts:
the LLM may propose, but the state carries the *evidence* the deterministic
guards need (authentication, pending action, confirmation, known entities,
tool observations) — see the requirements §12 / §13 / §34.
"""

from __future__ import annotations

from typing import Any, Optional

from pydantic import BaseModel, Field

from tau2.data_model.message import Message

from ecommerce_tau3.metrics import GuardEvent


class PendingAction(BaseModel):
    """A mutation proposed by the planner that is waiting for confirmation.

    Confirmation semantics (requirements §12):
      - a confirmation is only valid for THIS action (tool name + arguments);
      - a new plan to a different action invalidates the old confirmation;
      - a used confirmation is consumed and cannot be reused.
    """

    tool_name: str = Field(description="The official tool name proposed.")
    arguments: dict = Field(description="The proposed tool arguments.")
    summary: str = Field(
        description="User-facing summary of the proposed action (shown to the user)."
    )
    proposed_turn: int = Field(description="Turn in which the action was proposed.")
    confirmation_received: bool = Field(
        default=False, description="Whether the user explicitly confirmed this action."
    )

    def matches(self, tool_name: str, arguments: dict) -> bool:
        """A confirmation matches only the exact same tool + arguments."""
        if tool_name != self.tool_name:
            return False
        return self.arguments == arguments


class Tau3AgentState(BaseModel):
    """State carried between turns by Tau3TrustBoundaryAgent.

    Fields (requirements §34):
        message_history, authenticated_user, pending_action, pending_confirmation,
        known_entities, tool_observations, guard_events
    """

    messages: list[Message] = Field(
        default_factory=list,
        description="The conversation history (official tau2 Message objects).",
    )
    turn: int = Field(default=0, description="Current conversation turn counter.")

    # --- Authentication / user binding (requirements §13) ---
    authenticated_user_id: Optional[str] = Field(
        default=None, description="The user id established by authentication."
    )
    authentication_evidence: list[str] = Field(
        default_factory=list,
        description="Tools that established authentication, e.g. "
        "['find_user_id_by_email', 'find_user_id_by_name_zip'].",
    )

    # --- Confirmation gate (requirements §12) ---
    pending_action: Optional[PendingAction] = Field(
        default=None, description="The mutation currently awaiting confirmation."
    )

    # --- Evidence learned from tool observations (requirements §34) ---
    known_entities: dict[str, Any] = Field(
        default_factory=dict,
        description="Conservative, evidence-based entity knowledge: "
        "{'orders_owned': set[str] (orders listed in the authenticated "
        "user's validated get_user_details record, or re-confirmed by a "
        "get_order_details observation whose returned user_id == "
        "authenticated user), 'reads_performed': set[str], 'users_seen': "
        "set[str], 'payment_methods': set[str] (ids from the authenticated "
        "user's validated User.payment_methods mapping)} — built only from "
        "tool names/arguments/results the agent itself observed, never "
        "from gold data.",
    )
    tool_observations: list[dict] = Field(
        default_factory=list,
        description="Recorded tool observations (tool name, arguments, outcome).",
    )

    # --- Structured internal events for supplemental metrics (§35) ---
    guard_events: list[GuardEvent] = Field(
        default_factory=list,
        description="Structured guard events (never reasoning traces).",
    )
