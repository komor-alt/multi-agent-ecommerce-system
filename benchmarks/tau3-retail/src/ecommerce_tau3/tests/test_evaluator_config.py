from __future__ import annotations

from ecommerce_tau3 import run as run_module


def test_explicit_nl_assertion_judge_overrides_upstream_defaults(monkeypatch):
    from tau2.evaluator import evaluator_nl_assertions

    monkeypatch.setattr(
        evaluator_nl_assertions,
        "DEFAULT_LLM_NL_ASSERTIONS",
        "upstream-default",
    )
    monkeypatch.setattr(
        evaluator_nl_assertions,
        "DEFAULT_LLM_NL_ASSERTIONS_ARGS",
        {"temperature": 1.0},
    )
    llm_args = {
        "api_base": "https://api.deepseek.com",
        "extra_body": {"thinking": {"type": "disabled"}},
        "temperature": 0.0,
    }

    run_module._configure_nl_assertion_evaluator(
        {
            "evaluator": {
                "llm": "openai/deepseek-v4-flash",
                "llmArgs": llm_args,
            }
        }
    )

    assert (
        evaluator_nl_assertions.DEFAULT_LLM_NL_ASSERTIONS
        == "openai/deepseek-v4-flash"
    )
    assert evaluator_nl_assertions.DEFAULT_LLM_NL_ASSERTIONS_ARGS == llm_args
    assert evaluator_nl_assertions.DEFAULT_LLM_NL_ASSERTIONS_ARGS is not llm_args


def test_missing_nl_assertion_judge_is_a_noop(monkeypatch):
    from tau2.evaluator import evaluator_nl_assertions

    monkeypatch.setattr(
        evaluator_nl_assertions,
        "DEFAULT_LLM_NL_ASSERTIONS",
        "upstream-default",
    )

    run_module._configure_nl_assertion_evaluator({})

    assert evaluator_nl_assertions.DEFAULT_LLM_NL_ASSERTIONS == "upstream-default"
