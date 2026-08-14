"""Registration + interface tests.

The guarded agent must be a real HalfDuplexAgent registered through the
OFFICIAL factory registry, exactly like the official examples
(examples/agents/minimal_text_agent.py). No official interface is invented.
"""

from __future__ import annotations

import pytest

from tau2.agent.base_agent import HalfDuplexAgent
from tau2.agent.base.participant import HalfDuplexParticipant
from tau2.registry import registry

from ecommerce_tau3 import (
    AGENT_NAME,
    Tau3TrustBoundaryAgent,
    create_guarded_retail_agent,
    ensure_registered,
)
from ecommerce_tau3.tests.test_tool_guard import (
    OFFICIAL_RETAIL_TOOL_TYPES,
    _stub_tool,
)


@pytest.fixture(autouse=True)
def _registered():
    ensure_registered()
    yield


def test_agent_is_registered_in_official_registry():
    factory = registry.get_agent_factory(AGENT_NAME)
    assert factory is not None
    assert AGENT_NAME in registry.get_agents()


def test_factory_contract_matches_official():
    """The factory must accept the official build_agent kwargs
    (tools, domain_policy, llm, llm_args, task, ...)."""
    tools = [
        _stub_tool(name, ttype)
        for name, ttype in OFFICIAL_RETAIL_TOOL_TYPES.items()
    ]
    agent = create_guarded_retail_agent(
        tools=tools,
        domain_policy="official policy text",
        llm="gpt-4.1-2025-04-14",
        llm_args={"temperature": 0.0},
        task=None,  # official build_agent passes task= for all agents
        audio_native_config=None,
        audio_taps_dir=None,
    )
    assert isinstance(agent, Tau3TrustBoundaryAgent)


def test_agent_is_half_duplex_agent():
    tools = [
        _stub_tool(name, ttype)
        for name, ttype in OFFICIAL_RETAIL_TOOL_TYPES.items()
    ]
    agent = Tau3TrustBoundaryAgent(
        tools=tools, domain_policy="policy", llm="gpt-4.1-2025-04-14"
    )
    assert isinstance(agent, HalfDuplexAgent)
    assert isinstance(agent, HalfDuplexParticipant)
    # Official abstract interface: both methods present and callable.
    state = agent.get_init_state()
    assert state is not None
    assert callable(agent.generate_next_message)


def test_factory_requires_llm():
    from ecommerce_tau3.factory import create_guarded_retail_agent

    with pytest.raises(ValueError):
        create_guarded_retail_agent(tools=[], domain_policy="policy")


def test_registration_is_idempotent():
    ensure_registered()
    ensure_registered()
    factory = registry.get_agent_factory(AGENT_NAME)
    assert factory is not None
