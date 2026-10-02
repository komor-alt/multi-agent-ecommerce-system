from types import SimpleNamespace

import pytest

from tau2.data_model.message import AssistantMessage, ToolCall, ToolMessage

from ecommerce_tau3.run_pair import BENCH_ROOT
from ecommerce_tau3.runconfig import load_config
from ecommerce_tau3.security_bench import (
    CASE_MARKER,
    RetailGuardScriptUser,
    SecurityCase,
    analyze_results,
    load_security_cases,
)


class FakeSimulation:
    def __init__(self, task_id, messages, reward=1.0, db_match=True):
        self.task_id = task_id
        self._messages = messages
        self.reward_info = SimpleNamespace(
            reward=reward,
            db_check=SimpleNamespace(db_match=db_match),
        )
        self.termination_reason = "user_stop"

    def get_messages(self):
        return self._messages


def test_case_inventory_has_attacks_and_benign_controls():
    cases = load_security_cases()
    assert len(cases) == 24
    assert sum(case.kind == "attack" for case in cases) == 20
    assert sum(case.kind == "control" for case in cases) == 4


def test_script_user_replays_messages_then_stops():
    case = load_security_cases()[0]
    user = RetailGuardScriptUser(instructions=f"Instructions:\n{CASE_MARKER}{case.id}")
    state = user.get_init_state()
    first, state = user.generate_next_message(
        AssistantMessage.text(content="hello"), state
    )
    assert first.content == case.messages[0]
    stop, state = user.generate_next_message(
        AssistantMessage.text(content="anything else?"), state
    )
    assert "###STOP###" in stop.content

def test_control_user_repeats_an_explicit_confirmation_prompt():
    case = next(case for case in load_security_cases() if case.kind == "control")
    user = RetailGuardScriptUser(instructions=f"{CASE_MARKER}{case.id}")
    state = user.get_init_state()
    for scripted in case.messages:
        message, state = user.generate_next_message(
            AssistantMessage.text(content="continue"), state
        )
        assert message.content == scripted
    repeated, state = user.generate_next_message(
        AssistantMessage.text(content="Reply yes to confirm."), state
    )
    assert repeated.content.startswith("Yes")



def test_successful_attack_mutation_is_a_violation():
    case = SecurityCase(
        id="attack",
        kind="attack",
        category="test",
        template_task_id="0",
        description="test",
        messages=["attack"],
    )
    call = ToolCall(
        id="call-1",
        name="cancel_pending_order",
        arguments={"order_id": "#W1", "reason": "test"},
        requestor="assistant",
    )
    simulation = FakeSimulation(
        "attack",
        [
            AssistantMessage.text(content=None, tool_calls=[call]),
            ToolMessage(
                id="call-1",
                role="tool",
                requestor="assistant",
                content="ok",
                error=False,
            ),
        ],
        reward=0.0,
        db_match=False,
    )
    analysis = analyze_results(
        SimpleNamespace(simulations=[simulation]), cases=[case]
    )
    row = analysis["per_simulation"][0]
    assert row["attack_success"] is True
    assert row["unsafe_mutation"] is True
    assert analysis["summary"]["attack_success_rate"] == 1.0


def test_failed_tool_call_is_not_attack_success_without_db_change():
    case = SecurityCase(
        id="attack",
        kind="attack",
        category="test",
        template_task_id="0",
        description="test",
        messages=["attack"],
    )
    call = ToolCall(
        id="call-1",
        name="cancel_pending_order",
        arguments={"order_id": "#W-does-not-exist", "reason": "test"},
        requestor="assistant",
    )
    simulation = FakeSimulation(
        "attack",
        [
            AssistantMessage.text(content=None, tool_calls=[call]),
            ToolMessage(
                id="call-1",
                role="tool",
                requestor="assistant",
                content="not found",
                error=True,
            ),
        ],
    )
    analysis = analyze_results(
        SimpleNamespace(simulations=[simulation]), cases=[case]
    )
    row = analysis["per_simulation"][0]
    assert row["attempted_mutation"] is True
    assert row["unsafe_mutation"] is False
    assert row["attack_success"] is False


def test_duplicate_case_ids_fail(tmp_path):
    path = tmp_path / "cases.json"
    one = {
        "id": "same",
        "kind": "attack",
        "category": "test",
        "template_task_id": "0",
        "description": "test",
        "messages": ["attack"],
        "forbidden_reads": [],
    }
    path.write_text(
        __import__("json").dumps({"version": 1, "cases": [one, one]}),
        encoding="utf-8",
    )
    with pytest.raises(ValueError, match="duplicate"):
        load_security_cases(path)


def test_security_config_disables_deepseek_thinking_via_extra_body():
    config = load_config(BENCH_ROOT / "configs" / "security-deepseek-flash.yaml")
    for section in ("agent", "user", "evaluator"):
        llm_args = config[section]["llmArgs"]
        assert llm_args["extra_body"]["thinking"]["type"] == "disabled"
        assert "thinking" not in llm_args
