"""Tau3TrustBoundaryAgent: the τ³ Retail trust-boundary adapter.

Implements the OFFICIAL HalfDuplexAgent interface
(tau2.agent.base_agent.HalfDuplexAgent):

    - get_init_state(message_history) -> StateType
    - generate_next_message(message, state) -> (AssistantMessage, StateType)

Per-turn pipeline (requirements §9 / §11 / §12 / §13):

    User Message / Tool Observation
        -> BoundedPlanner (proposes ONE bounded step)
        -> ToolGuard + PolicyGuard (server-side, deterministic)
        -> allowed ? emit tool call (MUTATION needs matching confirmation)
                  : CONFIRMATION_REQUIRED / TOOL_REJECTED -> message to user
        -> observation -> re-plan next turn

Positioning (requirements §8 / §30): this is a BENCHMARK ADAPTER that
validates the project's bounded-planning + server-side-guard architecture
against the official τ³ Retail environment. It is NOT the Java production
agent's code path — the τ³ Retail tools/policy/DB/conversation protocol are
official and untouched.
"""

from __future__ import annotations

import json
import threading
from typing import Optional

from loguru import logger

from tau2.agent.base.llm_config import LLMConfigMixin
from tau2.agent.base_agent import HalfDuplexAgent, ValidAgentInputMessage
from tau2.data_model.message import (
    AssistantMessage,
    Message,
    MultiToolMessage,
    ToolCall,
    ToolMessage,
    UserMessage,
)
from tau2.environment.tool import Tool

from ecommerce_tau3.agent_state import PendingAction, Tau3AgentState
from ecommerce_tau3.metrics import GuardEvent, GuardEventType
from ecommerce_tau3.planner import BoundedPlan, BoundedPlanner
from ecommerce_tau3.policy_guard import (
    AUTH_REQUIRED,
    CONFIRMATION_MISMATCH,
    CONFIRMATION_REQUIRED,
    PolicyGuard,
    _AUTH_TOOLS,
)

# Reasons that route a proposed mutation back through the confirmation gate
# (the new proposal replaces any older pending action/confirmation).
_CONFIRMATION_REASONS = frozenset(
    {CONFIRMATION_REQUIRED, CONFIRMATION_MISMATCH}
)
from ecommerce_tau3.tool_guard import ToolCategory, ToolGuard

# ---------------------------------------------------------------------------
# Guard-event collector (supplemental data only, never part of official
# results). The batch runner builds one agent per simulation; stop() drains
# each agent's guard events into this list, and the driver
# (ecommerce_tau3.run) writes them next to the official results as
# guard_events.json. Pairing with simulations is by index at max_concurrency=1.
# ---------------------------------------------------------------------------
_EVENT_COLLECTOR: list[dict] = []
_EVENT_COLLECTOR_LOCK = threading.Lock()


def drain_guard_event_collector() -> list[dict]:
    """Return and clear the collected guard events (driver-side only)."""
    with _EVENT_COLLECTOR_LOCK:
        events = list(_EVENT_COLLECTOR)
        _EVENT_COLLECTOR.clear()
    return events


class Tau3TrustBoundaryAgent(
    LLMConfigMixin, HalfDuplexAgent[Tau3AgentState]
):
    """Bounded-planning, server-side-guarded half-duplex agent for τ³ Retail."""

    def __init__(
        self,
        tools: list[Tool],
        domain_policy: str,
        llm: Optional[str] = None,
        llm_args: Optional[dict] = None,
    ):
        super().__init__(
            tools=tools,
            domain_policy=domain_policy,
            llm=llm,
            llm_args=llm_args,
        )
        self.tool_guard = ToolGuard(tools)
        self.policy_guard = PolicyGuard()
        self.planner = BoundedPlanner(
            tools=tools,
            domain_policy=domain_policy,
            llm=llm,
            llm_args=llm_args,
        )

    # ------------------------------------------------------------------
    # Official HalfDuplexAgent interface
    # ------------------------------------------------------------------

    def get_init_state(
        self, message_history: Optional[list[Message]] = None
    ) -> Tau3AgentState:
        """Build the initial agent state (empty evidence, no authentication)."""
        if message_history is None:
            message_history = []
        return Tau3AgentState(
            messages=list(message_history),
            known_entities={
                "orders_owned": [],  # ownership-verified via tool observations
                "users_seen": [],
                "reads_performed": [],
                "payment_methods": [],  # ids from the validated User.payment_methods
                "emitted_calls": {},  # tool_call_id -> {name, arguments}
            },
        )

    def generate_next_message(
        self, message: ValidAgentInputMessage, state: Tau3AgentState
    ) -> tuple[AssistantMessage, Tau3AgentState]:
        """One turn: record input -> plan -> guard -> act / ask."""
        state.turn += 1
        self._record_input(message, state)

        if isinstance(message, (ToolMessage, MultiToolMessage)):
            # Observation step: learn evidence, then re-plan.
            self._record_observation(message, state)
            plan = self.planner.plan(state, message)
            return self._execute_plan(plan, state)

        # User message turn: first try to satisfy the pending confirmation,
        # then plan a fresh step. A confirmation is accepted ONLY when the
        # user's message is affirmative and a pending action exists; it is
        # consumed when the matching mutation later executes.
        self._confirm_if_matches(state)
        plan = self.planner.plan(state, message)
        return self._execute_plan(plan, state)

    # ------------------------------------------------------------------
    # Lifecycle: drain guard events for supplemental metrics
    # ------------------------------------------------------------------

    def stop(
        self,
        message: Optional[ValidAgentInputMessage] = None,
        state: Optional[Tau3AgentState] = None,
    ) -> None:
        """Official hook: persist structured guard events for metrics."""
        if state is not None and state.guard_events:
            with _EVENT_COLLECTOR_LOCK:
                _EVENT_COLLECTOR.append(
                    {
                        "turn": state.turn,
                        "events": [e.model_dump(mode="json") for e in state.guard_events],
                    }
                )

    # ------------------------------------------------------------------
    # Turn mechanics
    # ------------------------------------------------------------------

    def _record_input(
        self, message: ValidAgentInputMessage, state: Tau3AgentState
    ) -> None:
        if isinstance(message, MultiToolMessage):
            state.messages.extend(message.tool_messages)
        else:
            state.messages.append(message)

    def _record_observation(
        self, message: ValidAgentInputMessage, state: Tau3AgentState
    ) -> None:
        """Learn conservative, evidence-based facts from tool outcomes.

        The official retail tools return their domain objects as JSON
        (environment.to_json_str: Order -> {"order_id": ..., "user_id": ...},
        find_user_id_by_* -> the plain user id string). Observations are
        parsed conservatively and FAIL CLOSED: an order is recorded as
        mutation-eligible ONLY when the returned order's user_id matches the
        authenticated user. Merely calling get_order_details — on any order —
        never authorizes mutating it.
        """
        tool_messages: list[ToolMessage] = (
            message.tool_messages
            if isinstance(message, MultiToolMessage)
            else [message]
        )
        emitted_calls: dict = state.known_entities.get("emitted_calls", {})
        for tool_msg in tool_messages:
            if not isinstance(tool_msg, ToolMessage):
                continue
            # Official linkage is by tool-call id: ToolMessage carries the id
            # of the assistant's tool call, not the tool name. We resolve the
            # name/arguments from the calls the agent itself emitted.
            call_info = emitted_calls.get(tool_msg.id)
            if call_info is None:
                continue  # cannot attribute the observation: learn nothing
            name = call_info["name"]
            arguments = call_info["arguments"]
            result = tool_msg.content or ""
            ok = (
                not tool_msg.error
                and "not found" not in (result or "").lower()
                and "error" not in (result or "").lower()
            )

            if name in _AUTH_TOOLS and state.authenticated_user_id is None and ok:
                user_id = self._parse_user_id_result(result)
                if user_id:
                    state.authenticated_user_id = user_id
                    state.authentication_evidence.append(name)

            # Ownership-verified order evidence (never gold data), recorded
            # only for successful observations that establish ownership.
            if ok and name == "get_order_details":
                if self._order_owned_by_authenticated_user(arguments, result, state):
                    self._add_entity(state, "orders_owned", arguments.get("order_id"))
            if ok and name == "get_user_details":
                # The official User record establishes ownership of its
                # orders AND its payment methods — but ONLY when the
                # observation is validated: returned user_id == requested
                # user_id == authenticated user (triple match). Malformed or
                # foreign observations record NOTHING (no user, no read, no
                # orders, no payment methods) — fail closed.
                parsed = self._parse_user_details(arguments, result, state)
                if parsed is not None:
                    user_id, orders, payment_method_ids = parsed
                    self._add_entity(state, "users_seen", user_id)
                    self._add_read(state, f"get_user_details:{user_id}")
                    for order_id in orders:
                        self._add_entity(state, "orders_owned", order_id)
                    for pm_id in payment_method_ids:
                        self._add_entity(state, "payment_methods", pm_id)

            state.tool_observations.append(
                {
                    "tool": name,
                    "arguments": arguments,
                    "ok": ok,
                }
            )

    # ------------------------------------------------------------------
    # Conservative observation parsing (fail closed)
    # ------------------------------------------------------------------

    @staticmethod
    def _parse_user_id_result(content: str) -> Optional[str]:
        """Parse the official find_user_id_by_* result.

        Officially the tool returns the plain user id string (e.g.
        'sara_doe_496'); on failure it errors (error=True) and content is
        'Error: User not found'. Accept only a plain non-empty string —
        anything else (JSON objects, lists, error text) fails closed.
        """
        text = (content or "").strip()
        if not text or text.lower().startswith("error"):
            return None
        try:
            parsed = json.loads(text)
        except json.JSONDecodeError:
            parsed = text
        if isinstance(parsed, str):
            return parsed.strip() or None
        return None  # unexpected shape: fail closed

    @staticmethod
    def _order_owned_by_authenticated_user(
        arguments: dict, result: str, state: Tau3AgentState
    ) -> bool:
        """Ownership check on the official get_order_details observation.

        The official tool returns the Order object serialized to JSON with
        'order_id' and 'user_id' fields. The order is mutation-eligible ONLY
        when the returned order_id equals the requested one AND its user_id
        equals the authenticated user. Malformed, mismatched, or unparseable
        observations fail closed (never eligible).
        """
        authenticated_user_id = state.authenticated_user_id
        if authenticated_user_id is None:
            return False
        try:
            payload = json.loads(result)
        except (json.JSONDecodeError, TypeError):
            return False
        if not isinstance(payload, dict):
            return False
        order_id = payload.get("order_id")
        user_id = payload.get("user_id")
        if not isinstance(order_id, str) or not isinstance(user_id, str):
            return False
        return order_id == arguments.get("order_id") and user_id == authenticated_user_id

    @staticmethod
    def _parse_user_details(
        arguments: dict, result: str, state: Tau3AgentState
    ) -> Optional[tuple[str, list[str], list[str]]]:
        """Conservative parse of the official get_user_details observation.

        The official User object serialized to JSON carries 'user_id',
        'orders': [order ids] and 'payment_methods': {payment_method_id:
        {...}} (keyed by payment method id — the official tool resolves
        payment_method_id args against this exact mapping). The observation
        is trusted ONLY when the returned user_id equals BOTH the requested
        user_id AND the authenticated user; otherwise NOTHING is recorded —
        no user, no read, no orders, no payment methods (fail closed).

        Returns (user_id, orders, payment_method_ids) or None.
        """
        authenticated_user_id = state.authenticated_user_id
        requested_user_id = arguments.get("user_id")
        if (
            authenticated_user_id is None
            or requested_user_id != authenticated_user_id
        ):
            return None
        try:
            payload = json.loads(result)
        except (json.JSONDecodeError, TypeError):
            return None
        if not isinstance(payload, dict):
            return None
        if payload.get("user_id") != requested_user_id:
            return None

        # Per-field fail closed: only well-formed data is ever learned.
        orders = payload.get("orders")
        if not isinstance(orders, list):
            orders = []
        orders = [order_id for order_id in orders if isinstance(order_id, str)]

        payment_methods = payload.get("payment_methods")
        if not isinstance(payment_methods, dict):
            payment_methods = {}
        payment_method_ids = [
            pm_id for pm_id in payment_methods if isinstance(pm_id, str)
        ]

        return requested_user_id, orders, payment_method_ids

    @staticmethod
    def _add_entity(state: Tau3AgentState, key: str, value) -> None:
        if value is None:
            return
        values = set(state.known_entities.get(key, []))
        values.add(str(value))
        state.known_entities[key] = sorted(values)

    @staticmethod
    def _add_read(state: Tau3AgentState, key: str) -> None:
        reads = set(state.known_entities.get("reads_performed", []))
        reads.add(key)
        state.known_entities["reads_performed"] = sorted(reads)

    # ------------------------------------------------------------------
    # Plan execution through the server-side guards
    # ------------------------------------------------------------------

    def _execute_plan(
        self, plan: BoundedPlan, state: Tau3AgentState
    ) -> tuple[AssistantMessage, Tau3AgentState]:
        self._record_event(
            state,
            GuardEventType.PLAN_PROPOSED,
            action=plan.intent,
            tool=plan.candidate_tool_call.name if plan.candidate_tool_call else None,
        )

        if plan.candidate_tool_call is None:
            # No tool proposed: message turn (with fallback bookkeeping).
            if plan.plan_source == "fallback":
                self._record_event(state, GuardEventType.FALLBACK, action=plan.intent)
            return self._respond(plan.message_text or "", state)

        tool_call = plan.candidate_tool_call
        result = self.tool_guard.check(tool_call.name, tool_call.arguments, state)

        if result.allowed:
            self._record_event(
                state,
                GuardEventType.TOOL_ALLOWED,
                tool=tool_call.name,
                reasonCode=result.reason_code,
            )
            # A mutation consumes its confirmation: the executed action's
            # confirmation can never be reused for another action (§12).
            if self.tool_guard.classify(tool_call.name) is ToolCategory.MUTATION:
                state.pending_action = None
            return self._tool_call_message(tool_call, state)

        reason = result.reason_code or "TOOL_REJECTED"
        if reason in _CONFIRMATION_REASONS:
            # Mutation without a valid, matching confirmation: summarize and
            # wait. Staging a new pending action replaces (and therefore
            # invalidates) any older confirmation (§12).
            self._stage_pending_action(plan, tool_call, state)
            self._record_event(
                state,
                GuardEventType.CONFIRMATION_REQUIRED,
                action=plan.intent,
                tool=tool_call.name,
                reasonCode=reason,
            )
            return self._respond(self._confirmation_text(plan, tool_call), state)

        if reason == AUTH_REQUIRED:
            self._record_event(
                state,
                GuardEventType.AUTH_REQUIRED,
                tool=tool_call.name,
                reasonCode=reason,
            )
            return self._respond(
                "For security, I first need to verify your account. "
                "Could you provide your email address, or your name and zip code?",
                state,
            )

        self._record_event(
            state,
            GuardEventType.TOOL_REJECTED,
            tool=tool_call.name,
            reasonCode=reason,
        )
        return self._respond(
            f"I cannot proceed with that action ({reason}). Let me help you "
            "resolve this differently — what exactly would you like to do?",
            state,
        )

    # ------------------------------------------------------------------
    # Confirmation gate (requirements §12)
    # ------------------------------------------------------------------

    def _stage_pending_action(
        self, plan: BoundedPlan, tool_call: ToolCall, state: Tau3AgentState
    ) -> None:
        """Store the action awaiting confirmation. A NEW pending action
        replaces (and therefore invalidates) any older confirmation."""
        state.pending_action = PendingAction(
            tool_name=tool_call.name,
            arguments=tool_call.arguments,
            summary=self._confirmation_text(plan, tool_call),
            proposed_turn=state.turn,
        )

    def _confirm_if_matches(self, state: Tau3AgentState) -> bool:
        """Try to match the latest user message against the pending action.

        A confirmation is accepted ONLY when it matches the pending action
        (tool + arguments) and is then consumed — it can never be reused for
        a different action. This is checked before ANY mutation executes.
        """
        pending = state.pending_action
        if pending is None or pending.confirmation_received:
            return False
        latest = state.messages[-1] if state.messages else None
        if not isinstance(latest, UserMessage) or not latest.content:
            return False
        if not self._is_affirmative(latest.content):
            return False
        pending.confirmation_received = True
        self._record_event(
            state,
            GuardEventType.CONFIRMATION_ACCEPTED,
            action=pending.summary,
            tool=pending.tool_name,
        )
        return True

    @staticmethod
    def _is_affirmative(text: str) -> bool:
        lowered = text.strip().lower()
        return lowered.startswith(("yes", "yeah", "yep", "sure", "ok", "okay", "correct"))

    @staticmethod
    def _confirmation_text(plan: BoundedPlan, tool_call: ToolCall) -> str:
        args = ", ".join(f"{k}={v}" for k, v in tool_call.arguments.items())
        return (
            f"Before I proceed, please confirm: I will call "
            f"{tool_call.name}({args}). Reply 'yes' to confirm."
        )

    # ------------------------------------------------------------------
    # Output construction (official tau2 message types)
    # ------------------------------------------------------------------

    def _tool_call_message(
        self, tool_call: ToolCall, state: Tau3AgentState
    ) -> tuple[AssistantMessage, Tau3AgentState]:
        # Give the call an id and record it, so later ToolMessages can be
        # attributed to this call (official id-based linkage).
        if not tool_call.id:
            tool_call.id = f"call_{state.turn}_{tool_call.name}"
        emitted_calls = state.known_entities.setdefault("emitted_calls", {})
        emitted_calls[tool_call.id] = {
            "name": tool_call.name,
            "arguments": dict(tool_call.arguments),
        }
        message = AssistantMessage.text(
            content=None, tool_calls=[tool_call]
        )
        state.messages.append(message)
        return message, state

    def _respond(
        self, content: str, state: Tau3AgentState
    ) -> tuple[AssistantMessage, Tau3AgentState]:
        message = AssistantMessage.text(content=content)
        state.messages.append(message)
        return message, state

    @staticmethod
    def _record_event(
        state: Tau3AgentState,
        event_type: GuardEventType,
        *,
        action: Optional[str] = None,
        tool: Optional[str] = None,
        reasonCode: Optional[str] = None,
    ) -> None:
        state.guard_events.append(
            GuardEvent(
                type=event_type,
                action=action,
                tool=tool,
                reasonCode=reasonCode,
                turn=state.turn,
            )
        )
