from __future__ import annotations

import argparse
from pathlib import Path

import pytest

from ecommerce_tau3 import run_pair


def _args(**overrides) -> argparse.Namespace:
    values = {
        "task_ids": None,
        "num_tasks": None,
        "num_trials": None,
        "seed": None,
    }
    values.update(overrides)
    return argparse.Namespace(**values)


def test_run_id_default_and_validation():
    assert run_pair._run_id("run-20260829") == "run-20260829"
    assert run_pair._run_id(None)
    for invalid in ("../escape", "/absolute", "has space", ""):
        if invalid == "":
            assert run_pair._run_id(invalid)
        else:
            with pytest.raises(ValueError):
                run_pair._run_id(invalid)


def test_run_arguments_apply_identical_overrides(tmp_path):
    config = tmp_path / "smoke.yaml"
    argv = run_pair._run_arguments(
        config_path=config,
        agent="llm_agent",
        save_to="official-baseline-run1",
        args=_args(
            task_ids=["0", "10"],
            num_tasks=None,
            num_trials=3,
            seed=42,
        ),
    )
    assert argv == [
        "--config",
        str(config),
        "--agent",
        "llm_agent",
        "--save-to",
        "official-baseline-run1",
        "--task-ids",
        "0",
        "10",
        "--num-trials",
        "3",
        "--seed",
        "42",
    ]


def test_missing_api_key_fails_before_writes(tmp_path, monkeypatch, capsys):
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    monkeypatch.setattr(run_pair, "BENCH_ROOT", tmp_path)
    assert run_pair.main(["--run-id", "no-key"]) == 2
    captured = capsys.readouterr()
    assert "PUBLIC_BENCHMARK_NOT_RUN_NO_API_KEY" in captured.err
    assert not (tmp_path / "results").exists()
