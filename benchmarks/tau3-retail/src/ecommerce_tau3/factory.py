"""Agent factory + official registry registration for the guarded agent.

The τ³ framework integrates agents through factory functions registered with
the official `tau2.registry.registry`:

    registry.register_agent_factory(factory, name)

Factory contract (see .tau3-upstream/src/tau2/registry.py and the official
Agent Developer Guide, src/tau2/agent/README.md):

    factory(tools, domain_policy, **kwargs) -> agent instance
    kwargs may include: llm, llm_args, task, audio_native_config, ...

Official agents (llm_agent, llm_agent_gt, ...) are registered inside the tau2
package at import time; the tau2 CLI's `--agent` choices come from that
registry. A community agent cannot modify the official package, so it follows
the official example (examples/agents/minimal_text_agent.py): register
in-process from this module, then run through `tau2.runner.run_domain` —
see scripts/run_guarded.sh / ecommerce_tau3.run.
"""

from __future__ import annotations

from typing import Optional

from ecommerce_tau3.agent import Tau3TrustBoundaryAgent

# The name used on the CLI / in TextRunConfig: `--agent guarded_retail_agent`
AGENT_NAME = "guarded_retail_agent"


def create_guarded_retail_agent(tools, domain_policy, **kwargs):
    """Factory function for Tau3TrustBoundaryAgent (official factory contract).

    Args:
        tools: Environment tools the agent can call.
        domain_policy: Policy text the agent must follow.
        **kwargs: Additional arguments from the framework:
            - llm (str): LLM model name (from --agent-llm)
            - llm_args (dict): Additional LLM arguments
    """
    llm: Optional[str] = kwargs.get("llm")
    llm_args: Optional[dict] = kwargs.get("llm_args")
    task = kwargs.get("task")
    task_id = str(task.id) if task is not None and task.id is not None else None
    if llm is None:
        raise ValueError(
            f"{AGENT_NAME} requires an LLM. Pass --agent-llm <model> "
            "(or llm_agent=... in TextRunConfig)."
        )
    return Tau3TrustBoundaryAgent(
        tools=tools,
        domain_policy=domain_policy,
        llm=llm,
        llm_args=llm_args,
        task_id=task_id,
    )


def register_guarded_retail_agent() -> None:
    """Register the guarded agent with the official tau2 registry (idempotent).

    Safe to call multiple times (e.g. from the driver and from tests).
    """
    from tau2.registry import registry

    if registry.get_agent_factory(AGENT_NAME) is None:
        registry.register_agent_factory(create_guarded_retail_agent, AGENT_NAME)


def ensure_registered() -> None:
    """Idempotent registration; raises if registration fails loudly."""
    register_guarded_retail_agent()
