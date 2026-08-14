"""PolicyGuard: deterministic, evidence-based preconditions for tool calls.

This is defense-in-depth ON TOP of the official retail policy, which is
always provided in full in the system prompt (requirements §14). The guard
never rewrites or relaxes the official policy; it only enforces three
evidence preconditions that mirror the policy's own rules:

  - Authentication first (policy.md: "At the beginning of the conversation,
    you have to authenticate the user identity by locating their user id via
    email, or via name + zip code").
  - Explicit user confirmation before any DB-updating action
    (policy.md: "Before taking any action that updates the database
    (cancel, modify, return, exchange), you must list the action details and
    obtain explicit user confirmation (yes) to proceed").
  - Object binding: mutations must reference entities the agent actually
    observed through its own tool calls with verified ownership
    (orders_owned / payment_methods), which prevents operating on
    hallucinated ids and on other users' orders.
  - Read binding: get_order_details is user-private TOO — an order may only
    be read once it was learned as belonging to the authenticated user from a
    successful, parsed get_user_details observation. The official flow is
    authenticate -> read the user's own details (which lists their orders)
    -> read order details; reading arbitrary order ids would disclose other
    users' orders before the mutation guard could catch it.

Rules are generic — derived from the official policy and tool metadata of the
pinned τ³ version. There are NO rules keyed on task ids and no use of gold
actions (requirements §15 / §16).
"""

from __future__ import annotations

from typing import Optional

from ecommerce_tau3.agent_state import PendingAction, Tau3AgentState

# Reason codes (machine-readable, recorded in GuardEvent.reasonCode)
AUTH_REQUIRED = "AUTH_REQUIRED"
CONFIRMATION_REQUIRED = "CONFIRMATION_REQUIRED"
CONFIRMATION_MISMATCH = "CONFIRMATION_MISMATCH"
ORDER_NOT_OBSERVED = "ORDER_NOT_OBSERVED"
USER_NOT_AUTHENTICATED = "USER_NOT_AUTHENTICATED"
PAYMENT_METHOD_UNKNOWN = "PAYMENT_METHOD_UNKNOWN"

# Authentication tools: the OFFICIAL mechanism for establishing user identity
# (policy.md: "authenticate the user identity by locating their user id via
# email, or via name + zip code"). They MUST be callable BEFORE authentication —
# requiring auth for them would deadlock the conversation. The agent may never
# self-declare authentication; only a successful observation of these tools
# establishes authenticated_user_id (see agent._record_observation).
_AUTH_TOOLS = frozenset(
    {"find_user_id_by_email", "find_user_id_by_name_zip"}
)

# User-private READ tools: require authentication per official policy.
_PRIVATE_READ_TOOLS = frozenset(
    {
        "get_user_details",
        "get_order_details",
    }
)

# Mutation tools whose args reference an order (ownership/observation binding).
_ORDER_TOOLS = frozenset(
    {
        "cancel_pending_order",
        "exchange_delivered_order_items",
        "modify_pending_order_address",
        "modify_pending_order_items",
        "modify_pending_order_payment",
        "return_delivered_order_items",
    }
)


class PolicyCheck:
    """Result of a PolicyGuard check."""

    __slots__ = ("allowed", "reason_code", "confirmation_required")

    def __init__(
        self,
        allowed: bool,
        reason_code: Optional[str] = None,
        confirmation_required: bool = False,
    ):
        self.allowed = allowed
        self.reason_code = reason_code
        self.confirmation_required = confirmation_required

    def __repr__(self) -> str:  # pragma: no cover - debug helper
        return (
            f"PolicyCheck(allowed={self.allowed}, reason_code={self.reason_code}, "
            f"confirmation_required={self.confirmation_required})"
        )


class PolicyGuard:
    """Deterministic precondition checks. Stateless: all evidence lives in
    the Tau3AgentState, which is the only input besides the tool call."""

    # ------------------------------------------------------------------
    # Evidence helpers (argument-based bookkeeping, never gold data)
    # ------------------------------------------------------------------

    @staticmethod
    def _orders_owned(state: Tau3AgentState) -> set[str]:
        """Order ids whose OWNERSHIP was verified from official tool
        observations: the authenticated user's validated get_user_details
        record lists them (agent._record_observation only records orders
        from a triple-matched user record — fail closed otherwise)."""
        return set(state.known_entities.get("orders_owned", []))

    @staticmethod
    def _observed_payment_methods(state: Tau3AgentState) -> set[str]:
        """Payment method ids observed in the authenticated user's OFFICIAL
        User.payment_methods mapping (a validated get_user_details
        observation, keyed by payment method id). Nothing is ever assumed:
        a payment method id is usable ONLY if it was actually observed."""
        return set(state.known_entities.get("payment_methods", []))

    # ------------------------------------------------------------------
    # Check entry points
    # ------------------------------------------------------------------

    def check_read(
        self, tool_name: str, arguments: dict, state: Tau3AgentState
    ) -> PolicyCheck:
        """Preconditions for READ_ONLY tools.

        - find_user_id_by_email / find_user_id_by_name_zip are the
          authentication mechanism itself: always callable (no auth yet).
        - get_user_details / get_order_details are user-private: they require
          authentication, and get_user_details may only be called for the
          authenticated user (policy.md: "You can only help one user per
          conversation ... must deny any requests for tasks related to any
          other user").
        - get_order_details additionally requires the order_id to have been
          learned as belonging to the authenticated user from a successful,
          parsed get_user_details observation (orders_owned). Reading an
          arbitrary order id would disclose another user's order before the
          mutation guard could catch it.
        """
        if tool_name in _PRIVATE_READ_TOOLS and not state.authenticated_user_id:
            return PolicyCheck(allowed=False, reason_code=AUTH_REQUIRED)
        if tool_name == "get_user_details":
            user_id = arguments.get("user_id")
            if user_id is not None and user_id != state.authenticated_user_id:
                return PolicyCheck(allowed=False, reason_code=USER_NOT_AUTHENTICATED)
        if tool_name == "get_order_details":
            order_id = arguments.get("order_id")
            if order_id not in self._orders_owned(state):
                return PolicyCheck(allowed=False, reason_code=ORDER_NOT_OBSERVED)
        return PolicyCheck(allowed=True)

    def check_mutation(
        self, tool_name: str, arguments: dict, state: Tau3AgentState
    ) -> PolicyCheck:
        """Preconditions for MUTATION (and HANDOFF) tools."""
        # 1. Authentication (policy.md: authenticate at conversation start).
        if not state.authenticated_user_id:
            return PolicyCheck(allowed=False, reason_code=AUTH_REQUIRED)

        # 2. User binding: any explicit user_id arg must be the authenticated
        #    user. The LLM must not decide "I verified the user" by itself.
        user_id = arguments.get("user_id")
        if user_id is not None and user_id != state.authenticated_user_id:
            return PolicyCheck(allowed=False, reason_code=USER_NOT_AUTHENTICATED)

        # 3. Evidence binding: order mutations must reference an order whose
        #    ownership was established from the agent's own successful tool
        #    observation (returned user_id == authenticated user), so
        #    hallucinated ids and OTHER USERS' orders can never reach a
        #    mutation tool. Merely calling get_order_details on a foreign
        #    order does NOT make it eligible.
        if tool_name in _ORDER_TOOLS:
            order_id = arguments.get("order_id")
            if order_id is None:
                return PolicyCheck(allowed=False, reason_code=ORDER_NOT_OBSERVED)
            if order_id not in self._orders_owned(state):
                return PolicyCheck(allowed=False, reason_code=ORDER_NOT_OBSERVED)

        # 4. Payment binding: a payment_method_id arg must be one of the
        #    payment methods OBSERVED in the authenticated user's validated
        #    User.payment_methods mapping — not merely "user details were
        #    read at some point". An unobserved id (hallucinated, another
        #    user's, or malformed observation) fails closed.
        if "payment_method_id" in arguments:
            if arguments["payment_method_id"] not in self._observed_payment_methods(
                state
            ):
                return PolicyCheck(allowed=False, reason_code=PAYMENT_METHOD_UNKNOWN)

        # 5. Confirmation gate (requirements §12): a DB-updating mutation
        #    requires an explicit, matching, unconsumed confirmation.
        if tool_name in _ORDER_TOOLS or "user_id" in arguments:
            return self._check_confirmation(tool_name, arguments, state)

        # HANDOFF (transfer_to_human_agents) is not a DB update: allowed.
        return PolicyCheck(allowed=True)

    # ------------------------------------------------------------------
    # Confirmation gate
    # ------------------------------------------------------------------

    @staticmethod
    def _check_confirmation(
        tool_name: str, arguments: dict, state: Tau3AgentState
    ) -> PolicyCheck:
        pending: Optional[PendingAction] = state.pending_action
        if pending is None or not pending.confirmation_received:
            return PolicyCheck(
                allowed=False,
                reason_code=CONFIRMATION_REQUIRED,
                confirmation_required=True,
            )
        if not pending.matches(tool_name, arguments):
            return PolicyCheck(allowed=False, reason_code=CONFIRMATION_MISMATCH)
        return PolicyCheck(allowed=True)
