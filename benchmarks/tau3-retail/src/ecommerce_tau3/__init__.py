"""ecommerce_tau3: τ³-bench Retail trust-boundary adapter.

Public benchmark adapter that validates the project's bounded-planning +
server-side-guard architecture inside the OFFICIAL τ³ Retail environment
(official tasks, user simulator, tools, policy, evaluator — unmodified).

This package is a benchmark adapter, NOT the Java production agent. It
transfers design principles only (see README.md, "Positioning").

Pinned upstream: sierra-research/tau2-bench @ 79975ac5741e23fbb1d2ac44262d62398a6d87bd
(see benchmark-lock.json).
"""

from ecommerce_tau3.agent import Tau3TrustBoundaryAgent
from ecommerce_tau3.agent_state import PendingAction, Tau3AgentState
from ecommerce_tau3.factory import (
    AGENT_NAME,
    create_guarded_retail_agent,
    ensure_registered,
    register_guarded_retail_agent,
)
from ecommerce_tau3.metrics import GuardEvent, GuardEventType
from ecommerce_tau3.policy_guard import PolicyGuard
from ecommerce_tau3.planner import BoundedPlan, BoundedPlanner
from ecommerce_tau3.tool_guard import ToolCategory, ToolGuard

__version__ = "0.1.0"

__all__ = [
    "AGENT_NAME",
    "BoundedPlan",
    "BoundedPlanner",
    "GuardEvent",
    "GuardEventType",
    "PendingAction",
    "PolicyGuard",
    "Tau3AgentState",
    "Tau3TrustBoundaryAgent",
    "ToolCategory",
    "ToolGuard",
    "create_guarded_retail_agent",
    "ensure_registered",
    "register_guarded_retail_agent",
]
