"""Order-ownership binding tests (requirements §13 + reviewer fixes #3/#4).

Ownership comes from the authenticated user's VALIDATED get_user_details
record (returned user_id == requested user_id == authenticated user):
- only orders listed in that record are readable at all (get_order_details
  read gate) and mutation-eligible;
- only payment method ids from that record's payment_methods mapping are
  usable in mutations.
get_order_details observations re-confirm ownership (returned order_id ==
requested order_id AND user_id == authenticated user) but never establish
it on their own. Malformed/foreign observations fail closed.

Official observation shapes (pinned commit, tau2.environment.to_json_str):
  - get_order_details -> JSON object {"order_id": ..., "user_id": ...}
  - get_user_details  -> JSON object {"user_id": ..., "orders": [...],
    "payment_methods": {payment_method_id: {...}}} (payment methods keyed
    by id — the mapping mutation payment_method_id args resolve against)
  - find_user_id_by_* -> plain user id string
"""

from __future__ import annotations

import json
from typing import Optional

from tau2.data_model.message import ToolMessage

from ecommerce_tau3.agent import Tau3TrustBoundaryAgent
from ecommerce_tau3.agent_state import PendingAction, Tau3AgentState
from ecommerce_tau3.policy_guard import (
    ORDER_NOT_OBSERVED,
    CONFIRMATION_REQUIRED,
    PolicyGuard,
)
from ecommerce_tau3.tests.test_tool_guard import (
    OFFICIAL_RETAIL_TOOL_TYPES,
    _stub_tool,
)

AUTH_USER = "sara_doe_496"
FOREIGN_USER = "someone_else_1"
ORDER_ID = "#W1"
FOREIGN_ORDER_ID = "#W9"
GIFT_CARD_ID = "gift_card_0"
CREDIT_CARD_ID = "credit_card_0"


def _agent() -> Tau3TrustBoundaryAgent:
    tools = [
        _stub_tool(name, ttype)
        for name, ttype in OFFICIAL_RETAIL_TOOL_TYPES.items()
    ]
    return Tau3TrustBoundaryAgent(
        tools=tools, domain_policy="", llm="stub-model"
    )


def _state(agent: Tau3TrustBoundaryAgent) -> Tau3AgentState:
    state = agent.get_init_state()
    state.authenticated_user_id = AUTH_USER
    return state


def _observe(
    agent: Tau3TrustBoundaryAgent,
    state: Tau3AgentState,
    name: str,
    arguments: dict,
    content: str,
    *,
    error: bool = False,
    call_id: str = "call_1",
) -> None:
    """Feed one official ToolMessage through the agent's observation
    recorder, linked to the call the agent itself emitted."""
    state.known_entities.setdefault("emitted_calls", {})[call_id] = {
        "name": name,
        "arguments": dict(arguments),
    }
    tool_msg = ToolMessage(
        id=call_id, role="tool", content=content, error=error
    )
    agent._record_observation(tool_msg, state)


def _order_payload(order_id: str = ORDER_ID, user_id: str = AUTH_USER) -> str:
    return json.dumps({"order_id": order_id, "user_id": user_id})


def _user_details_payload(
    user_id: str = AUTH_USER,
    orders: Optional[list[str]] = None,
    payment_method_ids: Optional[list[str]] = None,
) -> str:
    """The official User object serialized to JSON: user_id, orders, and
    payment_methods (dict keyed by payment method id — the official
    mapping mutation payment_method_id args resolve against)."""
    return json.dumps(
        {
            "user_id": user_id,
            "name": {"first_name": "Sara", "last_name": "Doe"},
            "orders": orders if orders is not None else [ORDER_ID, "#W2"],
            "payment_methods": {
                pm_id: {"source": "gift_card"} for pm_id in payment_method_ids or []
            },
        }
    )


def _confirmed_pending(tool_name: str, arguments: dict) -> PendingAction:
    return PendingAction(
        tool_name=tool_name,
        arguments=arguments,
        summary="confirmed",
        proposed_turn=1,
        confirmation_received=True,
    )


# ---------------------------------------------------------------------------
# get_order_details: ownership comes from the VALIDATED get_user_details
# record — the read itself is gated on that evidence
# ---------------------------------------------------------------------------


def test_owned_order_read_flow_marks_order_eligible():
    """Official flow: authenticate -> read the user's OWN details (which
    lists their orders) -> the owned order becomes readable and its
    observation re-confirms mutation eligibility."""
    agent = _agent()
    state = _state(agent)
    _observe(
        agent, state, "get_user_details", {"user_id": AUTH_USER},
        _user_details_payload(orders=[ORDER_ID], payment_method_ids=[GIFT_CARD_ID]),
    )
    assert ORDER_ID in state.known_entities["orders_owned"]

    # The read gate: an owned order is readable.
    result = agent.tool_guard.check(
        "get_order_details", {"order_id": ORDER_ID}, state
    )
    assert result.allowed is True

    # The order details observation re-confirms the ownership evidence.
    _observe(
        agent, state, "get_order_details", {"order_id": ORDER_ID},
        _order_payload(),
    )
    assert ORDER_ID in state.known_entities["orders_owned"]

    # Eligible means the guard's remaining gates (confirmation) apply — not
    # ORDER_NOT_OBSERVED.
    result = agent.tool_guard.check(
        "cancel_pending_order",
        {"order_id": ORDER_ID, "reason": "no longer needed"},
        state,
    )
    assert result.reason_code == CONFIRMATION_REQUIRED

    # With the matching confirmation, the owned order's mutation passes.
    state.pending_action = _confirmed_pending(
        "cancel_pending_order",
        {"order_id": ORDER_ID, "reason": "no longer needed"},
    )
    result = agent.tool_guard.check(
        "cancel_pending_order",
        {"order_id": ORDER_ID, "reason": "no longer needed"},
        state,
    )
    assert result.allowed is True


def test_foreign_order_read_blocked_before_observation():
    """Reviewer scenario: an order not learned from the authenticated user's
    validated get_user_details record is NOT readable at all — the read is
    blocked before any observation could disclose another user's order."""
    agent = _agent()
    state = _state(agent)  # authenticated as AUTH_USER
    result = agent.tool_guard.check(
        "get_order_details", {"order_id": FOREIGN_ORDER_ID}, state
    )
    assert result.allowed is False
    assert result.reason_code == ORDER_NOT_OBSERVED

    # Defense in depth: even if a foreign observation were somehow fed
    # directly (bypassing the guard), it never marks the order eligible.
    _observe(
        agent, state, "get_order_details", {"order_id": FOREIGN_ORDER_ID},
        _order_payload(order_id=FOREIGN_ORDER_ID, user_id=FOREIGN_USER),
    )
    assert FOREIGN_ORDER_ID not in state.known_entities["orders_owned"]

    # Even with a user-confirmed pending action, the foreign order is blocked.
    state.pending_action = _confirmed_pending(
        "cancel_pending_order",
        {"order_id": FOREIGN_ORDER_ID, "reason": "no longer needed"},
    )
    result = agent.tool_guard.check(
        "cancel_pending_order",
        {"order_id": FOREIGN_ORDER_ID, "reason": "no longer needed"},
        state,
    )
    assert result.allowed is False
    assert result.reason_code == ORDER_NOT_OBSERVED


def test_malformed_observation_fails_closed():
    agent = _agent()
    state = _state(agent)
    for content in (
        "not json at all",
        "{}",  # no user_id / order_id
        json.dumps({"order_id": ORDER_ID}),  # missing user_id
        json.dumps({"user_id": AUTH_USER}),  # missing order_id
        json.dumps({"order_id": ORDER_ID, "user_id": 123}),  # wrong types
        "[1, 2, 3]",  # not an object
        "",  # empty
    ):
        state = _state(agent)  # fresh state per case
        # Without a validated user-details record, the read itself is blocked.
        result = agent.tool_guard.check(
            "get_order_details", {"order_id": ORDER_ID}, state
        )
        assert result.allowed is False
        assert result.reason_code == ORDER_NOT_OBSERVED

        # Defense in depth: a directly-fed malformed observation also fails.
        _observe(
            agent, state, "get_order_details", {"order_id": ORDER_ID}, content
        )
        assert ORDER_ID not in state.known_entities["orders_owned"], content

        state.pending_action = _confirmed_pending(
            "cancel_pending_order",
            {"order_id": ORDER_ID, "reason": "no longer needed"},
        )
        result = agent.tool_guard.check(
            "cancel_pending_order",
            {"order_id": ORDER_ID, "reason": "no longer needed"},
            state,
        )
        assert result.allowed is False
        assert result.reason_code == ORDER_NOT_OBSERVED


def test_returned_order_id_mismatch_fails_closed():
    """The returned order_id must equal the requested one; a mismatched
    observation is not trusted."""
    agent = _agent()
    state = _state(agent)
    _observe(
        agent, state, "get_order_details", {"order_id": ORDER_ID},
        _order_payload(order_id="DIFFERENT_ORDER", user_id=AUTH_USER),
    )
    assert ORDER_ID not in state.known_entities["orders_owned"]


def test_error_observation_fails_closed():
    agent = _agent()
    state = _state(agent)
    _observe(
        agent, state, "get_order_details", {"order_id": ORDER_ID},
        "Error: Order not found", error=True,
    )
    assert ORDER_ID not in state.known_entities["orders_owned"]


def test_no_authentication_fails_closed():
    """Without an authenticated user there is no owner to match against."""
    agent = _agent()
    state = agent.get_init_state()  # no auth
    _observe(
        agent, state, "get_order_details", {"order_id": ORDER_ID},
        _order_payload(),
    )
    assert ORDER_ID not in state.known_entities["orders_owned"]


# ---------------------------------------------------------------------------
# get_user_details: the authenticated user's own record (validated: returned
# user_id == requested user_id == authenticated user) establishes ownership
# of its orders AND its payment methods
# ---------------------------------------------------------------------------


def test_authenticated_users_order_list_marks_orders_owned():
    agent = _agent()
    state = _state(agent)
    _observe(
        agent, state, "get_user_details", {"user_id": AUTH_USER},
        _user_details_payload(
            orders=[ORDER_ID, "#W2"],
            payment_method_ids=[GIFT_CARD_ID, CREDIT_CARD_ID],
        ),
    )
    assert {"#W1", "#W2"} <= set(state.known_entities["orders_owned"])
    assert f"get_user_details:{AUTH_USER}" in state.known_entities["reads_performed"]
    # Payment methods come from the SAME validated mapping.
    assert {GIFT_CARD_ID, CREDIT_CARD_ID} <= set(
        state.known_entities["payment_methods"]
    )


def test_foreign_user_details_not_recorded():
    """The guard blocks reading a foreign user's details; defensively, the
    observation recorder also refuses to learn anything from it."""
    agent = _agent()
    state = _state(agent)
    _observe(
        agent, state, "get_user_details", {"user_id": FOREIGN_USER},
        _user_details_payload(user_id=FOREIGN_USER, orders=[FOREIGN_ORDER_ID]),
    )
    assert FOREIGN_ORDER_ID not in state.known_entities["orders_owned"]
    assert "get_user_details:" not in " ".join(state.known_entities["reads_performed"])
    assert state.known_entities["payment_methods"] == []


def test_user_details_returned_user_id_mismatch_fails_closed():
    """The returned user_id must equal the requested one: a record that
    answers for a DIFFERENT user is not trusted at all."""
    agent = _agent()
    state = _state(agent)
    _observe(
        agent, state, "get_user_details", {"user_id": AUTH_USER},
        _user_details_payload(user_id=FOREIGN_USER, orders=[FOREIGN_ORDER_ID]),
    )
    assert state.known_entities["users_seen"] == []
    assert "get_user_details:" not in " ".join(state.known_entities["reads_performed"])
    assert state.known_entities["orders_owned"] == []
    assert state.known_entities["payment_methods"] == []


def test_user_details_untrusted_payload_records_nothing():
    """Unparseable or non-binding get_user_details observations record
    NOTHING: no user, no read, no orders, no payment methods."""
    agent = _agent()
    for content in (
        "not json at all",
        "",  # empty
        "[1, 2]",  # not an object
        "{}",  # no user_id in the record
    ):
        state = _state(agent)  # fresh state per case
        _observe(
            agent, state, "get_user_details", {"user_id": AUTH_USER}, content
        )
        assert state.known_entities["users_seen"] == [], content
        assert state.known_entities["reads_performed"] == [], content
        assert state.known_entities["orders_owned"] == [], content
        assert state.known_entities["payment_methods"] == [], content


def test_user_details_malformed_fields_learn_nothing():
    """A record that binds to the authenticated user (triple match) but has
    malformed fields: the malformed fields are never learned. orders must
    be a list of strings; payment_methods must be the official dict keyed
    by payment method id — anything else yields NO ids."""
    agent = _agent()
    # (content, expected_orders, expected_payment_methods)
    cases = (
        # orders not a list -> no orders; payment_methods not a dict -> none
        (
            json.dumps(
                {"user_id": AUTH_USER, "orders": "none", "payment_methods": []}
            ),
            [],
            [],
        ),
        # orders list with non-string ids -> filtered to none
        (
            json.dumps(
                {"user_id": AUTH_USER, "orders": [1, 2], "payment_methods": {}}
            ),
            [],
            [],
        ),
        # payment_methods as a list instead of the official dict -> no ids
        (
            json.dumps(
                {
                    "user_id": AUTH_USER,
                    "orders": [ORDER_ID],
                    "payment_methods": [GIFT_CARD_ID],
                }
            ),
            [ORDER_ID],  # well-formed orders ARE learned
            [],
        ),
    )
    for content, expected_orders, expected_payment_methods in cases:
        state = _state(agent)  # fresh state per case
        _observe(
            agent, state, "get_user_details", {"user_id": AUTH_USER}, content
        )
        # The record bound to the authenticated user: user + read recorded.
        assert AUTH_USER in state.known_entities["users_seen"], content
        assert f"get_user_details:{AUTH_USER}" in state.known_entities[
            "reads_performed"
        ], content
        assert state.known_entities["orders_owned"] == expected_orders, content
        assert state.known_entities["payment_methods"] == expected_payment_methods, content


def test_user_details_unauthenticated_fails_closed():
    """Without an authenticated user there is no one to bind the record to."""
    agent = _agent()
    state = agent.get_init_state()  # no auth
    _observe(
        agent, state, "get_user_details", {"user_id": AUTH_USER},
        _user_details_payload(),
    )
    assert state.known_entities["orders_owned"] == []
    assert state.known_entities["payment_methods"] == []


# ---------------------------------------------------------------------------
# Authentication observations (official plain-string result)
# ---------------------------------------------------------------------------


def test_auth_observation_establishes_authenticated_user():
    agent = _agent()
    state = agent.get_init_state()
    _observe(
        agent, state, "find_user_id_by_email",
        {"email": "sara@example.com"}, AUTH_USER,
    )
    assert state.authenticated_user_id == AUTH_USER
    assert "find_user_id_by_email" in state.authentication_evidence


def test_auth_observation_accepts_quoted_json_string():
    agent = _agent()
    state = agent.get_init_state()
    _observe(
        agent, state, "find_user_id_by_name_zip",
        {"first_name": "Sara", "last_name": "Doe", "zip": "12345"},
        json.dumps(AUTH_USER),  # officially the result is a plain string
    )
    assert state.authenticated_user_id == AUTH_USER


def test_auth_observation_fails_closed_on_unexpected_shapes():
    agent = _agent()
    for content in (
        "Error: User not found",
        "{}",  # JSON object, not a user id
        "[1, 2]",
        "",  # empty
    ):
        state = agent.get_init_state()
        _observe(
            agent, state, "find_user_id_by_email",
            {"email": "sara@example.com"}, content, error="not found" in content,
        )
        assert state.authenticated_user_id is None, content


def test_auth_observation_with_error_is_not_authenticated():
    agent = _agent()
    state = agent.get_init_state()
    _observe(
        agent, state, "find_user_id_by_email",
        {"email": "sara@example.com"}, "Error: User not found", error=True,
    )
    assert state.authenticated_user_id is None


# ---------------------------------------------------------------------------
# End-to-end offline flow:
# authenticate -> validated user details (orders + payment methods)
# -> read own order -> mutate
# ---------------------------------------------------------------------------


def test_full_owned_flow_authenticate_read_mutate():
    agent = _agent()
    state = agent.get_init_state()

    # 1. Authenticate (the auth tools are callable pre-auth).
    assert agent.tool_guard.check(
        "find_user_id_by_email", {"email": "sara@example.com"}, state
    ).allowed
    _observe(
        agent, state, "find_user_id_by_email",
        {"email": "sara@example.com"}, AUTH_USER,
    )
    assert state.authenticated_user_id == AUTH_USER

    # 2. Read the user's OWN details: the validated record establishes the
    #    user's orders AND payment methods (read gate opens from here).
    assert agent.tool_guard.check(
        "get_user_details", {"user_id": AUTH_USER}, state
    ).allowed
    _observe(
        agent, state, "get_user_details", {"user_id": AUTH_USER},
        _user_details_payload(
            orders=[ORDER_ID],
            payment_method_ids=[GIFT_CARD_ID],
        ),
    )
    assert ORDER_ID in state.known_entities["orders_owned"]
    assert GIFT_CARD_ID in state.known_entities["payment_methods"]

    # 3. Read the owned order's details (private read, now allowed).
    assert agent.tool_guard.check(
        "get_order_details", {"order_id": ORDER_ID}, state
    ).allowed
    _observe(
        agent, state, "get_order_details", {"order_id": ORDER_ID},
        _order_payload(),
    )
    assert ORDER_ID in state.known_entities["orders_owned"]

    # 4. Confirmed mutation of the OWNED order with the OBSERVED payment
    #    method passes every guard.
    args = {
        "order_id": ORDER_ID,
        "item_ids": ["1008292230"],
        "payment_method_id": GIFT_CARD_ID,
    }
    state.pending_action = _confirmed_pending("return_delivered_order_items", args)
    result = agent.tool_guard.check("return_delivered_order_items", args, state)
    assert result.allowed is True


def test_foreign_order_never_eligible_after_read():
    """The exact reviewer scenario end-to-end: a foreign order's read is
    blocked by the guard (ownership never established), and even a directly
    fed foreign observation NEVER makes the order mutation-eligible."""
    agent = _agent()
    state = _state(agent)
    result = agent.tool_guard.check(
        "get_order_details", {"order_id": FOREIGN_ORDER_ID}, state
    )
    assert result.allowed is False
    assert result.reason_code == ORDER_NOT_OBSERVED

    _observe(
        agent, state, "get_order_details", {"order_id": FOREIGN_ORDER_ID},
        _order_payload(order_id=FOREIGN_ORDER_ID, user_id=FOREIGN_USER),
    )
    state.pending_action = _confirmed_pending(
        "return_delivered_order_items",
        {
            "order_id": FOREIGN_ORDER_ID,
            "item_ids": ["1008292230"],
            "payment_method_id": "gift_card_0",
        },
    )
    result = PolicyGuard().check_mutation(
        "return_delivered_order_items",
        {
            "order_id": FOREIGN_ORDER_ID,
            "item_ids": ["1008292230"],
            "payment_method_id": "gift_card_0",
        },
        state,
    )
    assert result.allowed is False
    assert result.reason_code == ORDER_NOT_OBSERVED
