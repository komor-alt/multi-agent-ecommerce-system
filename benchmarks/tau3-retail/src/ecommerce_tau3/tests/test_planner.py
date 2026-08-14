"""BoundedPlanner LLM integration tests (offline, no API calls).

`_plan_with_llm` uses the OFFICIAL `tau2.utils.llm_utils.generate` utility
(the same one tau2.agent.llm_agent uses). These tests monkeypatch `generate`
in the planner module: no real model is ever called, and the conversion rules
(malformed / multiple tool calls -> fail-safe fallback) are exercised
directly.
"""

from __future__ import annotations

import pytest

from tau2.data_model.message import AssistantMessage, SystemMessage, ToolCall, UserMessage

import ecommerce_tau3.planner as planner_module
from ecommerce_tau3.agent_state import Tau3AgentState
from ecommerce_tau3.planner import BoundedPlanner, PlanOutputError
from ecommerce_tau3.tests.test_tool_guard import (
    OFFICIAL_RETAIL_TOOL_TYPES,
    _stub_tool,
)

POLICY_TEXT = "## Official retail policy (full text used verbatim)\n- authenticate ..."


def _planner() -> BoundedPlanner:
    tools = [
        _stub_tool(name, ttype)
        for name, ttype in OFFICIAL_RETAIL_TOOL_TYPES.items()
    ]
    return BoundedPlanner(
        tools=tools, domain_policy=POLICY_TEXT, llm="stub-model"
    )


def _state() -> Tau3AgentState:
    return Tau3AgentState()


def _assistant(content: str | None, tool_calls=None) -> AssistantMessage:
    return AssistantMessage.text(content=content, tool_calls=tool_calls)


def _plan_with_output(
    planner: BoundedPlanner, assistant_message: AssistantMessage
) -> "BoundedPlan":
    """Run planner.plan() with the official generate stubbed to return a
    fixed model output (offline)."""
    monkeypatch = pytest.MonkeyPatch()
    monkeypatch.setattr(planner_module, "generate", lambda **kwargs: assistant_message)
    try:
        return planner.plan(_state(), None)
    finally:
        monkeypatch.undo()


# ---------------------------------------------------------------------------
# Conversion: valid outputs
# ---------------------------------------------------------------------------


def test_text_output_becomes_message_plan():
    planner = _planner()
    plan = planner._convert_llm_output(
        _assistant("I can help with that. Could you confirm your email?")
    )
    assert plan.plan_source == "planner"
    assert plan.intent == "REPLY"
    assert plan.message_text == "I can help with that. Could you confirm your email?"
    assert plan.candidate_tool_call is None


def test_single_tool_call_becomes_candidate_plan():
    planner = _planner()
    call = ToolCall(
        id="call_abc",
        name="cancel_pending_order",
        arguments={"order_id": "#W1", "reason": "no longer needed"},
    )
    plan = planner._convert_llm_output(_assistant(None, [call]))
    assert plan.plan_source == "planner"
    assert plan.intent == "CANCEL_ORDER"
    assert plan.candidate_tool_call is call
    assert plan.message_text is None


def test_read_tool_call_intent():
    planner = _planner()
    call = ToolCall(id="call_1", name="get_order_details", arguments={"order_id": "#W1"})
    plan = planner._convert_llm_output(_assistant(None, [call]))
    assert plan.intent == "READ"


def test_auth_tool_call_intent():
    planner = _planner()
    call = ToolCall(id="call_1", name="find_user_id_by_email", arguments={"email": "a@b.c"})
    plan = planner._convert_llm_output(_assistant(None, [call]))
    assert plan.intent == "AUTHENTICATE"


# ---------------------------------------------------------------------------
# Malformed / multiple calls fail safely (never a guessed action)
# ---------------------------------------------------------------------------


def test_both_text_and_tool_call_fails_safe():
    call = ToolCall(name="cancel_pending_order", arguments={"order_id": "#W1"})
    plan = _plan_with_output(_planner(), _assistant("Cancelling now", [call]))
    assert plan.plan_source == "fallback"
    assert plan.candidate_tool_call is None
    assert plan.intent == "CLARIFY"
    assert plan.message_text


def test_multiple_tool_calls_fails_safe():
    calls = [
        ToolCall(name="get_order_details", arguments={"order_id": "#W1"}),
        ToolCall(name="get_order_details", arguments={"order_id": "#W2"}),
    ]
    plan = _plan_with_output(_planner(), _assistant(None, calls))
    assert plan.plan_source == "fallback"
    assert plan.candidate_tool_call is None


def test_empty_output_fails_safe():
    plan = _plan_with_output(_planner(), _assistant(None, None))
    assert plan.plan_source == "fallback"


def test_whitespace_content_fails_safe():
    plan = _plan_with_output(_planner(), _assistant("   ", None))
    assert plan.plan_source == "fallback"


def test_tool_call_without_name_fails_safe():
    plan = _plan_with_output(
        _planner(), _assistant(None, [ToolCall(name="", arguments={})])
    )
    assert plan.plan_source == "fallback"


def test_convert_raises_plan_output_error_on_malformed():
    planner = _planner()
    call = ToolCall(name="x", arguments={})
    with pytest.raises(PlanOutputError):
        planner._convert_llm_output(_assistant("text", [call]))
    with pytest.raises(PlanOutputError):
        planner._convert_llm_output(
            _assistant(None, [call, ToolCall(name="y", arguments={})])
        )
    with pytest.raises(PlanOutputError):
        planner._convert_llm_output(_assistant(None, None))


# ---------------------------------------------------------------------------
# _plan_with_llm uses the official generate API with the bounded system prompt
# ---------------------------------------------------------------------------


def test_plan_with_llm_calls_official_generate(monkeypatch):
    captured = {}
    planner = _planner()

    def fake_generate(**kwargs):
        captured.update(kwargs)
        return _assistant("Let me look that up for you.")

    monkeypatch.setattr(planner_module, "generate", fake_generate)
    plan = planner._plan_with_llm(_state(), None)

    assert plan.plan_source == "planner"
    assert plan.message_text == "Let me look that up for you."
    assert captured["model"] == "stub-model"
    assert captured["call_name"] == "guarded_agent_plan"
    # The exact official Tool objects of this planner are passed to generate.
    assert captured["tools"] is planner.tools
    assert [t.name for t in captured["tools"]] == [t.name for t in planner.tools]
    # The first message is the bounded system prompt.
    assert isinstance(captured["messages"][0], SystemMessage)


def test_system_prompt_keeps_complete_official_policy(monkeypatch):
    captured = {}

    def fake_generate(**kwargs):
        captured["system"] = kwargs["messages"][0].content
        return _assistant("ok")

    monkeypatch.setattr(planner_module, "generate", fake_generate)
    _planner()._plan_with_llm(_state(), None)

    assert POLICY_TEXT in captured["system"], (
        "the complete official domain policy must stay in the system prompt"
    )


def test_system_prompt_contains_bounded_planning_instructions():
    prompt = _planner().system_prompt
    assert "EXACTLY ONE" in prompt
    assert "find_user_id_by_email" in prompt
    assert "confirmation" in prompt.lower()


def test_plan_uses_message_history(monkeypatch):
    captured = {}

    def fake_generate(**kwargs):
        captured["messages"] = kwargs["messages"]
        return _assistant("ok")

    monkeypatch.setattr(planner_module, "generate", fake_generate)
    state = _state()
    state.messages.append(UserMessage(role="user", content="hi"))
    _planner()._plan_with_llm(state, None)

    contents = [m.content for m in captured["messages"]]
    assert "hi" in contents  # conversation history is passed through


def test_fallback_never_proposes_mutation():
    """Even on failure the fallback is a clarifying message: no tool call."""
    plan = _planner()._fallback_plan(_state(), None)
    assert plan.candidate_tool_call is None
    assert plan.message_text
