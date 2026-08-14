"""PolicyGuard tests: authentication and evidence-based binding (§13).

The guard mirrors the official policy's own rules (authenticate at
conversation start; explicit confirmation before DB updates) as
defense-in-depth — it never rewrites the official policy.
"""

from __future__ import annotations

from ecommerce_tau3.agent_state import Tau3AgentState
from ecommerce_tau3.policy_guard import (
    AUTH_REQUIRED,
    CONFIRMATION_REQUIRED,
    ORDER_NOT_OBSERVED,
    PAYMENT_METHOD_UNKNOWN,
    USER_NOT_AUTHENTICATED,
    PolicyGuard,
)


def _state() -> Tau3AgentState:
    """Fully-evidenced state: auth, an owned order, and the user's observed
    payment methods (as if from a validated get_user_details observation)."""
    state = Tau3AgentState(
        known_entities={
            "orders_owned": ["#W1"],
            "users_seen": ["sara_doe_496"],
            "reads_performed": ["get_user_details:sara_doe_496"],
            "payment_methods": ["gift_card_0"],
            "emitted_calls": {},
        }
    )
    return state


# ---------------------------------------------------------------------------
# Authentication (§13)
# ---------------------------------------------------------------------------


def test_mutation_requires_authentication():
    result = PolicyGuard().check_mutation(
        "cancel_pending_order",
        {"order_id": "#W1", "reason": "no longer needed"},
        _state(),
    )
    assert result.allowed is False
    assert result.reason_code == AUTH_REQUIRED


def test_private_read_requires_authentication():
    result = PolicyGuard().check_read(
        "get_user_details", {"user_id": "sara_doe_496"}, _state()
    )
    assert result.allowed is False
    assert result.reason_code == AUTH_REQUIRED


def test_public_read_does_not_require_authentication():
    result = PolicyGuard().check_read(
        "list_all_product_types", {}, _state()
    )
    assert result.allowed is True


# ---------------------------------------------------------------------------
# Authentication tools are the auth mechanism itself (no deadlock)
# ---------------------------------------------------------------------------


def test_find_user_id_by_email_callable_before_authentication():
    """Regression: find_user_id_by_* IS the authentication mechanism — it
    must be callable on an unauthenticated state, or the agent can never
    authenticate (deadlock)."""
    result = PolicyGuard().check_read(
        "find_user_id_by_email", {"email": "a@b.com"}, _state()
    )
    assert result.allowed is True


def test_find_user_id_by_name_zip_callable_before_authentication():
    result = PolicyGuard().check_read(
        "find_user_id_by_name_zip",
        {"first_name": "Sara", "last_name": "Doe", "zip": "12345"},
        _state(),
    )
    assert result.allowed is True


def test_private_reads_still_require_authentication():
    """Regression: get_user_details / get_order_details remain user-private
    reads — only the auth tools are callable pre-auth."""
    for tool_name in ("get_user_details", "get_order_details"):
        result = PolicyGuard().check_read(
            tool_name, {"user_id": "sara_doe_496"}, _state()
        )
        assert result.allowed is False
        assert result.reason_code == AUTH_REQUIRED


def test_get_user_details_of_foreign_user_rejected():
    """Even while authenticated, get_user_details may only be called for the
    authenticated user (policy: only help one user per conversation)."""
    state = _state()
    state.authenticated_user_id = "sara_doe_496"
    result = PolicyGuard().check_read(
        "get_user_details", {"user_id": "someone_else_1"}, state
    )
    assert result.allowed is False
    assert result.reason_code == USER_NOT_AUTHENTICATED


def test_get_user_details_of_authenticated_user_allowed():
    state = _state()
    state.authenticated_user_id = "sara_doe_496"
    result = PolicyGuard().check_read(
        "get_user_details", {"user_id": "sara_doe_496"}, state
    )
    assert result.allowed is True


# ---------------------------------------------------------------------------
# get_order_details read gate: ownership must come from a validated
# get_user_details observation BEFORE the order may be read
# ---------------------------------------------------------------------------


def test_get_order_details_unknown_order_rejected():
    """An order not learned from the authenticated user's validated
    get_user_details record must not be readable — reading arbitrary order
    ids would disclose other users' orders."""
    state = _state()
    state.authenticated_user_id = "sara_doe_496"
    result = PolicyGuard().check_read(
        "get_order_details", {"order_id": "#W9999"}, state  # never observed
    )
    assert result.allowed is False
    assert result.reason_code == ORDER_NOT_OBSERVED


def test_get_order_details_missing_order_id_rejected():
    state = _state()
    state.authenticated_user_id = "sara_doe_496"
    result = PolicyGuard().check_read("get_order_details", {}, state)
    assert result.allowed is False
    assert result.reason_code == ORDER_NOT_OBSERVED


def test_get_order_details_owned_order_allowed():
    """The official flow: get_user_details first (learns the user's orders),
    then the owned order is readable."""
    state = _state()
    state.authenticated_user_id = "sara_doe_496"
    result = PolicyGuard().check_read(
        "get_order_details", {"order_id": "#W1"}, state
    )
    assert result.allowed is True


def test_tool_guard_allows_auth_tools_without_authentication():
    """ToolGuard-level regression: the full guard chain lets the agent
    authenticate on a cold state."""
    from ecommerce_tau3.tests.test_tool_guard import _stub_guard

    guard = _stub_guard()
    state = Tau3AgentState()
    result = guard.check(
        "find_user_id_by_email", {"email": "a@b.com"}, state
    )
    assert result.allowed is True
    result = guard.check(
        "find_user_id_by_name_zip",
        {"first_name": "Sara", "last_name": "Doe", "zip": "12345"},
        state,
    )
    assert result.allowed is True
    result = guard.check(
        "get_order_details", {"order_id": "#W1"}, state
    )
    assert result.allowed is False
    assert result.reason_code == AUTH_REQUIRED


# ---------------------------------------------------------------------------
# User binding (§13)
# ---------------------------------------------------------------------------


def test_mutation_on_other_user_rejected():
    state = _state()
    state.authenticated_user_id = "sara_doe_496"
    result = PolicyGuard().check_mutation(
        "modify_user_address",
        {"user_id": "someone_else_1", "address1": "1 Main St", "zip": "10001"},
        state,
    )
    assert result.allowed is False
    assert result.reason_code == USER_NOT_AUTHENTICATED


def test_mutation_on_authenticated_user_passes_binding():
    state = _state()
    state.authenticated_user_id = "sara_doe_496"
    args = {"user_id": "sara_doe_496", "address1": "1 Main St", "zip": "10001"}
    state.pending_action = _confirmed_pending("modify_user_address", args)
    result = PolicyGuard().check_mutation(
        "modify_user_address", args, state
    )
    assert result.allowed is True


# ---------------------------------------------------------------------------
# Evidence binding: order / payment context
# ---------------------------------------------------------------------------


def test_order_not_observed_rejected():
    state = _state()
    state.authenticated_user_id = "sara_doe_496"
    result = PolicyGuard().check_mutation(
        "cancel_pending_order",
        {"order_id": "#W9999", "reason": "no longer needed"},  # never read
        state,
    )
    assert result.allowed is False
    assert result.reason_code == ORDER_NOT_OBSERVED


def test_payment_method_not_observed_rejected():
    """No validated get_user_details observation: no payment methods are
    known, so any payment_method_id is rejected (previously the gate only
    required that get_user_details had been CALLED — never the exact id)."""
    state = _state()
    state.authenticated_user_id = "sara_doe_496"
    state.known_entities["payment_methods"] = []
    result = PolicyGuard().check_mutation(
        "return_delivered_order_items",
        {
            "order_id": "#W1",
            "item_ids": ["1"],
            "payment_method_id": "gift_card_0",
        },
        state,
    )
    assert result.allowed is False
    assert result.reason_code == PAYMENT_METHOD_UNKNOWN


def test_payment_method_id_not_in_observed_set_rejected():
    """Even with a validated get_user_details observation, an id that is NOT
    in the observed User.payment_methods mapping (hallucinated, another
    user's, or malformed) is rejected."""
    state = _state()
    state.authenticated_user_id = "sara_doe_496"
    state.known_entities["payment_methods"] = ["credit_card_0"]
    result = PolicyGuard().check_mutation(
        "return_delivered_order_items",
        {
            "order_id": "#W1",
            "item_ids": ["1"],
            "payment_method_id": "gift_card_0",  # not among observed ids
        },
        state,
    )
    assert result.allowed is False
    assert result.reason_code == PAYMENT_METHOD_UNKNOWN


def test_observed_payment_method_id_passes_payment_gate():
    """The exact id from the validated User.payment_methods mapping passes
    the payment gate (the remaining gate is the confirmation)."""
    state = _state()
    state.authenticated_user_id = "sara_doe_496"
    args = {
        "order_id": "#W1",
        "item_ids": ["1"],
        "payment_method_id": "gift_card_0",
    }
    state.pending_action = _confirmed_pending("return_delivered_order_items", args)
    result = PolicyGuard().check_mutation("return_delivered_order_items", args, state)
    assert result.allowed is True


def test_fully_prepared_mutation_still_requires_confirmation():
    state = _state()
    state.authenticated_user_id = "sara_doe_496"
    result = PolicyGuard().check_mutation(
        "cancel_pending_order",
        {"order_id": "#W1", "reason": "no longer needed"},
        state,
    )
    assert result.allowed is False
    assert result.reason_code == CONFIRMATION_REQUIRED
    assert result.confirmation_required is True


# ---------------------------------------------------------------------------
# Handoff is not a DB update
# ---------------------------------------------------------------------------


def test_handoff_requires_auth_but_not_order_confirmation():
    state = _state()
    result = PolicyGuard().check_mutation(
        "transfer_to_human_agents", {"summary": "issue"}, state
    )
    assert result.allowed is False
    assert result.reason_code == AUTH_REQUIRED

    state.authenticated_user_id = "sara_doe_496"
    result = PolicyGuard().check_mutation(
        "transfer_to_human_agents", {"summary": "issue"}, state
    )
    assert result.allowed is True


def _confirmed_pending(tool_name: str, arguments: dict):
    from ecommerce_tau3.agent_state import PendingAction

    return PendingAction(
        tool_name=tool_name,
        arguments=arguments,
        summary="confirmed",
        proposed_turn=1,
        confirmation_received=True,
    )
