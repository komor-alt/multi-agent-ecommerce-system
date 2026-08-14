"""BoundedPlanner: proposes ONE bounded plan step per turn.

Transfers the Java after-sales agent's "bounded planning" idea into the τ³
Retail environment (requirements §9):

    User Message
        -> Bounded Intent / State Understanding
        -> Allowed Operation Planning
        -> Server-side Policy Guard / Tool Guard
        -> Tool Call -> Observation -> Re-plan

The planner's OUTPUT is bounded to a small structured schema (BoundedPlan):
either a message to the user, or exactly one candidate tool call. The
candidate is NOT executed directly — it must pass ToolGuard + PolicyGuard
in agent.py first.

The LLM integration uses the OFFICIAL `tau2.utils.llm_utils.generate`
utility (the same one `tau2.agent.llm_agent` uses). The system prompt keeps
the complete official domain policy and adds bounded-planning instructions.
The model may return either a plain assistant response or exactly one
official ToolCall; malformed or multiple tool calls fail safely to the
deterministic fallback. No gold actions, expected actions, evaluation
criteria, evaluator output, or task ids are ever used here
(requirements §15 / §16 / §37).
"""

from __future__ import annotations

import logging
from typing import Literal, Optional

from pydantic import BaseModel, Field

from tau2.data_model.message import AssistantMessage, SystemMessage, ToolCall
from tau2.environment.tool import Tool
from tau2.utils.llm_utils import generate

from ecommerce_tau3.agent_state import Tau3AgentState

logger = logging.getLogger(__name__)

# Small fixed intent vocabulary for BoundedPlan.intent (guard-event label).
_READ_INTENT = "READ"
_REPLY_INTENT = "REPLY"
_INTENT_BY_TOOL: dict[str, str] = {
    "cancel_pending_order": "CANCEL_ORDER",
    "exchange_delivered_order_items": "EXCHANGE_ITEMS",
    "find_user_id_by_email": "AUTHENTICATE",
    "find_user_id_by_name_zip": "AUTHENTICATE",
    "get_item_details": _READ_INTENT,
    "get_order_details": _READ_INTENT,
    "get_product_details": _READ_INTENT,
    "get_user_details": _READ_INTENT,
    "list_all_product_types": _READ_INTENT,
    "modify_pending_order_address": "MODIFY_ADDRESS",
    "modify_pending_order_items": "MODIFY_ITEMS",
    "modify_pending_order_payment": "MODIFY_PAYMENT",
    "modify_user_address": "MODIFY_USER_ADDRESS",
    "return_delivered_order_items": "RETURN_ITEMS",
    "transfer_to_human_agents": "HANDOFF",
}

# Bounded-planning instructions. This is protocol guidance mirroring what the
# server-side guards enforce (defense-in-depth); the official policy itself is
# included verbatim below and is the authority the model must follow. Nothing
# here references tasks, gold actions, or evaluation criteria (§37).
PLANNER_INSTRUCTION = """
You are a customer service agent that helps the user according to the <policy> provided below.

In each turn you must produce EXACTLY ONE of the following:
- A message to the user (no tool call).
- Exactly one tool call (no message text).
Producing both a message and a tool call, or more than one tool call, is an error.

Follow the policy strictly:
- At the beginning of the conversation, authenticate the user by locating
  their user id via find_user_id_by_email, or via find_user_id_by_name_zip.
  Never act on an identity that was not returned by a successful
  authentication tool call.
- Only operate on the authenticated user's own orders and profile. Never
  reference an order id, user id, or payment method id that you did not
  obtain from your own successful tool calls.
- Before any database-updating action (cancel, modify, return, exchange,
  address change), summarize the exact action and wait for explicit user
  confirmation ("yes") before calling the mutation tool.
- Do not make up information: use only what the tools return.

Always make sure you generate valid JSON only.
""".strip()

SYSTEM_PROMPT = """
<instructions>
{instruction}
</instructions>
<policy>
{domain_policy}
</policy>
""".strip()


class BoundedPlan(BaseModel):
    """A single bounded planning step.

    Exactly one of `message_text` or `candidate_tool_call` is set.
    `requires_confirmation` is a *proposal*; the server-side guards decide
    whether confirmation is actually enforced (the plan never bypasses them).
    """

    intent: str = Field(
        description="Bounded intent label (small fixed vocabulary)."
    )
    message_text: Optional[str] = Field(
        default=None, description="Text to send to the user (if not calling a tool)."
    )
    candidate_tool_call: Optional[ToolCall] = Field(
        default=None,
        description="Exactly one candidate tool call, subject to server-side guards.",
    )
    requires_confirmation: bool = Field(
        default=False,
        description="Proposal flag: server-side guards re-decide this.",
    )
    plan_source: Literal["planner", "fallback"] = Field(
        default="planner",
        description="'planner' when produced by the bounded planner, "
        "'fallback' when produced by the deterministic fallback.",
    )


class PlanOutputError(ValueError):
    """The model output does not match the bounded protocol (malformed or
    multiple tool calls). Failing safe means: never emit a guessed action;
    route to the deterministic fallback instead."""


class BoundedPlanner:
    """Produces BoundedPlan steps from the conversation state."""

    def __init__(
        self,
        tools: list[Tool],
        domain_policy: str,
        llm: Optional[str] = None,
        llm_args: Optional[dict] = None,
    ):
        self.tools = tools
        self.domain_policy = domain_policy  # always kept in the system prompt
        self.llm = llm
        self.llm_args = llm_args or {}

    # ------------------------------------------------------------------
    # Entry point
    # ------------------------------------------------------------------

    def plan(self, state: Tau3AgentState, incoming_message) -> BoundedPlan:
        """Produce the next bounded plan step for the current turn.

        Args:
            state: The current agent state (message history, evidence).
            incoming_message: The user/tool message that triggered this turn.

        Returns:
            A BoundedPlan. The candidate tool call is a PROPOSAL: it is only
            executed after ToolGuard / PolicyGuard approve it.

        Malformed model output (mixed or multiple tool calls) fails safely to
        the deterministic fallback, which also passes the guards.
        """
        try:
            return self._plan_with_llm(state, incoming_message)
        except PlanOutputError as exc:
            logger.warning(
                "Bounded planner output rejected (%s); using deterministic fallback.",
                exc,
            )
            return self._fallback_plan(state, incoming_message)

    # ------------------------------------------------------------------
    # Bounded LLM planning (official tau2 generate/message APIs)
    # ------------------------------------------------------------------

    @property
    def system_prompt(self) -> str:
        """Complete official domain policy + bounded planning instructions."""
        return SYSTEM_PROMPT.format(
            instruction=PLANNER_INSTRUCTION,
            domain_policy=self.domain_policy,
        )

    def _plan_with_llm(
        self, state: Tau3AgentState, incoming_message
    ) -> BoundedPlan:
        """One bounded planning step via the official `generate` utility.

        Same official call shape as `tau2.agent.llm_agent`:
        generate(model=self.llm, tools=self.tools, messages=..., call_name=...).
        The model must return either one plain assistant response or exactly
        one tool call — anything else is PlanOutputError and fails safe.
        """
        messages: list = [SystemMessage(role="system", content=self.system_prompt)]
        messages.extend(state.messages)
        assistant_message = generate(
            model=self.llm,
            tools=self.tools,
            messages=messages,
            call_name="guarded_agent_plan",
            **self.llm_args,
        )
        return self._convert_llm_output(assistant_message)

    def _convert_llm_output(self, assistant_message: AssistantMessage) -> BoundedPlan:
        """Convert the official model output into exactly one BoundedPlan.

        Valid outputs (the model can only do one per turn):
          - text content, no tool call  -> message plan
          - exactly one tool call, no text -> candidate tool-call plan
        Everything else (both, several calls, nothing) fails safe.
        """
        tool_calls = assistant_message.tool_calls or []
        content = (assistant_message.content or "").strip()

        if len(tool_calls) > 1:
            raise PlanOutputError(
                f"model returned {len(tool_calls)} tool calls; at most one is allowed"
            )
        if tool_calls and content:
            raise PlanOutputError(
                "model returned both a message and a tool call; exactly one is allowed"
            )
        if tool_calls:
            tool_call = tool_calls[0]
            if not tool_call.name:
                raise PlanOutputError("tool call has no name")
            return BoundedPlan(
                intent=self._intent_for(tool_call.name),
                candidate_tool_call=tool_call,
            )
        if content:
            return BoundedPlan(intent=_REPLY_INTENT, message_text=content)
        raise PlanOutputError("model returned neither text nor a tool call")

    @staticmethod
    def _intent_for(tool_name: str) -> str:
        return _INTENT_BY_TOOL.get(tool_name, _READ_INTENT)

    # ------------------------------------------------------------------
    # Deterministic fallback (never bypasses the guards)
    # ------------------------------------------------------------------

    @staticmethod
    def _fallback_plan(
        state: Tau3AgentState, incoming_message
    ) -> BoundedPlan:
        """Deterministic fallback: acknowledge and ask for clarification.

        It deliberately does NOT guess mutations: a wrong fallback action
        would be worse than a clarifying question (same fail-safe posture as
        the Java agent's rule fallback).
        """
        return BoundedPlan(
            intent="CLARIFY",
            message_text=(
                "I understood your request. To proceed safely, could you "
                "confirm the details (for example the order id, items, and "
                "how you would like the refund handled)?"
            ),
            requires_confirmation=False,
            plan_source="fallback",
        )
