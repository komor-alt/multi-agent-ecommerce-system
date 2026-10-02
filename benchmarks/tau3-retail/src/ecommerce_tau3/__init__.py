"""ecommerce_tau3: τ³-bench Retail trust-boundary adapter.

Public benchmark adapter that validates the project's bounded-planning +
server-side-guard architecture inside the OFFICIAL τ³ Retail environment
(official tasks, user simulator, tools, policy, evaluator — unmodified).

This package is a benchmark adapter, NOT the Java production agent. It
transfers design principles only (see README.md, "Positioning").

Pinned upstream: sierra-research/tau2-bench @ 79975ac5741e23fbb1d2ac44262d62398a6d87bd
(see benchmark-lock.json).
"""

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

# Keep package import side-effect free. In particular, ``python -m
# ecommerce_tau3.run_pair`` must set TAU2_DATA_DIR before importing tau2;
# eager public re-exports used to import tau2 while Python initialized this
# package, locking tau2 onto its non-existent default data directory.
_EXPORT_MODULES = {
    "AGENT_NAME": "factory",
    "BoundedPlan": "planner",
    "BoundedPlanner": "planner",
    "GuardEvent": "metrics",
    "GuardEventType": "metrics",
    "PendingAction": "agent_state",
    "PolicyGuard": "policy_guard",
    "Tau3AgentState": "agent_state",
    "Tau3TrustBoundaryAgent": "agent",
    "ToolCategory": "tool_guard",
    "ToolGuard": "tool_guard",
    "create_guarded_retail_agent": "factory",
    "ensure_registered": "factory",
    "register_guarded_retail_agent": "factory",
}


def __getattr__(name: str):
    """Lazily preserve the package's public re-exports."""
    module_name = _EXPORT_MODULES.get(name)
    if module_name is None:
        raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
    from importlib import import_module

    value = getattr(import_module(f"{__name__}.{module_name}"), name)
    globals()[name] = value
    return value
