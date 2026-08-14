"""Confirmation-gate tests (requirements §12).

A confirmation is only valid for the exact pending action (tool + arguments);
it is consumed when the mutation executes and can never be reused for a
different action.
"""

from __future__ import annotations

from tau2.data_model.message import ToolCall, UserMessage

from ecommerce_tau3.agent import Tau3TrustBoundaryAgent
from ecommerce_tau3.agent_state import PendingAction, Tau3AgentState
from ecommerce_tau3.planner import BoundedPlan
from ecommerce_tau3.policy_guard import (
    CONFIRMATION_MISMATCH,
    CONFIRMATION_REQUIRED,
    PolicyGuard,
)
from ecommerce_tau3.tests.test_tool_guard import (
    OFFICIAL_RETAIL_TOOL_TYPES,
    _stub_tool,
)


def _agent() -> Tau3TrustBoundaryAgent:
    tools = [
        _stub_tool(name, ttype)
        for name, ttype in OFFICIAL_RETAIL_TOOL_TYPES.items()
    ]
    return Tau3TrustBoundaryAgent(
        tools=tools, domain_policy="", llm="stub-model"
    )


def _state_with_confirmed_pending(agent: Tau3TrustBoundaryAgent) -> Tau3AgentState:
    """State where cancel_pending_order('#W1') is confirmed and everything
    else the guard needs (auth, order evidence, payment binding) is present."""
    state = agent.get_init_state()
    state.authenticated_user_id = "sara_doe_496"
    state.authentication_evidence = ["find_user_id_by_email"]
    state.known_entities["orders_owned"] = ["#W1"]
    state.known_entities["reads_performed"] = ["get_user_details:sara_doe_496"]
    state.known_entities["payment_methods"] = ["gift_card_0"]
    state.pending_action = PendingAction(
        tool_name="cancel_pending_order",
        arguments={"order_id": "#W1", "reason": "no longer needed"},
        summary="cancel #W1",
        proposed_turn=1,
        confirmation_received=True,
    )
    return state


# ---------------------------------------------------------------------------
# PolicyGuard._check_confirmation semantics
# ---------------------------------------------------------------------------


def test_confirmation_matches_exact_action():
    state = _state_with_confirmed_pending(_agent())
    result = PolicyGuard._check_confirmation(
        "cancel_pending_order",
        {"order_id": "#W1", "reason": "no longer needed"},
        state,
    )
    assert result.allowed is True


def test_confirmation_mismatch_different_arguments():
    state = _state_with_confirmed_pending(_agent())
    result = PolicyGuard._check_confirmation(
        "cancel_pending_order",
        {"order_id": "#W2", "reason": "no longer needed"},  # different order
        state,
    )
    assert result.allowed is False
    assert result.reason_code == CONFIRMATION_MISMATCH


def test_confirmation_mismatch_different_tool():
    state = _state_with_confirmed_pending(_agent())
    # User confirmed cancelling #W1; the planner proposes returning #W1.
    result = PolicyGuard._check_confirmation(
        "return_delivered_order_items",
        {"order_id": "#W1", "item_ids": ["1"], "payment_method_id": "gift_card_0"},
        state,
    )
    assert result.allowed is False
    assert result.reason_code == CONFIRMATION_MISMATCH


def test_no_confirmation_blocks_mutation():
    state = _state_with_confirmed_pending(_agent())
    state.pending_action.confirmation_received = False
    result = PolicyGuard._check_confirmation(
        "cancel_pending_order",
        {"order_id": "#W1", "reason": "no longer needed"},
        state,
    )
    assert result.allowed is False
    assert result.reason_code == CONFIRMATION_REQUIRED


# ---------------------------------------------------------------------------
# Agent-level confirmation flow
# ---------------------------------------------------------------------------


def test_affirmative_user_message_accepts_pending_confirmation():
    agent = _agent()
    state = _state_with_confirmed_pending(agent)
    state.pending_action.confirmation_received = False
    state.messages.append(UserMessage(role="user", content="yes"))

    assert agent._confirm_if_matches(state) is True
    assert state.pending_action.confirmation_received is True


def test_non_affirmative_message_does_not_accept_confirmation():
    agent = _agent()
    state = _state_with_confirmed_pending(agent)
    state.pending_action.confirmation_received = False
    state.messages.append(UserMessage(role="user", content="no, wait"))

    assert agent._confirm_if_matches(state) is False
    assert state.pending_action.confirmation_received is False


def test_no_pending_action_means_no_confirmation():
    agent = _agent()
    state = agent.get_init_state()
    state.messages.append(UserMessage(role="user", content="yes"))
    assert agent._confirm_if_matches(state) is False


def test_confirmation_is_consumed_when_mutation_executes():
    """After the confirmed mutation is emitted, the pending action is gone:
    the old confirmation can never be reused for another action."""
    agent = _agent()
    state = _state_with_confirmed_pending(agent)

    # Phase 1 planner falls back to a message; drive the guard directly:
    from ecommerce_tau3.planner import BoundedPlan

    plan = BoundedPlan(
        intent="CANCEL_ORDER",
        candidate_tool_call=ToolCall(
            name="cancel_pending_order",
            arguments={"order_id": "#W1", "reason": "no longer needed"},
        ),
    )
    message, new_state = agent._execute_plan(plan, state)

    assert message.is_tool_call()
    assert new_state.pending_action is None
    # And the guard now blocks the same call again (confirmation consumed).
    result = agent.tool_guard.check(
        "cancel_pending_order",
        {"order_id": "#W1", "reason": "no longer needed"},
        new_state,
    )
    assert result.allowed is False
    assert result.reason_code == CONFIRMATION_REQUIRED


def test_new_pending_action_invalidates_old_confirmation():
    """A confirmation for cancel #W1 must not authorize return #W1:
    staging a new pending action replaces the old one."""
    agent = _agent()
    state = _state_with_confirmed_pending(agent)
    state.messages.append(UserMessage(role="user", content="yes"))
    agent._confirm_if_matches(state)

    # Planner now proposes a DIFFERENT mutation -> new pending staged.
    plan = BoundedPlan(
        intent="RETURN_ITEMS",
        candidate_tool_call=ToolCall(
            name="return_delivered_order_items",
            arguments={
                "order_id": "#W1",
                "item_ids": ["1"],
                "payment_method_id": "gift_card_0",
            },
        ),
    )
    message, new_state = agent._execute_plan(plan, state)

    assert message.is_tool_call() is False
    assert new_state.pending_action is not None
    assert new_state.pending_action.tool_name == "return_delivered_order_items"
    assert new_state.pending_action.confirmation_received is False
