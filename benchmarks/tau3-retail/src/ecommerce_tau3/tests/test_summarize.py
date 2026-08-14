"""Focused offline tests for scripts/summarize.py.

No real benchmark and no API key: official Results loading is monkeypatched
with an in-memory fake, and validation/metadata functions are exercised with
small dict helpers. The only official artifact touched is benchmark-lock.json
(read-only, same as test_tool_guard.py).
"""

from __future__ import annotations

import json
import types
from datetime import datetime, timezone
from pathlib import Path

import pandas as pd
import pytest

import summarize

COMMIT = "79975ac5741e23fbb1d2ac44262d62398a6d87bd"
# The runner/project working-tree commit the official runner stamps into
# Results.info.git_commit (get_commit_hash runs `git rev-parse HEAD` in the
# runner's cwd) — a DIFFERENT commit from the installed tau2 dependency.
RUNNER_COMMIT = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b"
AGENT_LLM = "gpt-4.1-2025-04-14"

_LOCK_PATH = Path(__file__).resolve().parents[3] / "benchmark-lock.json"


@pytest.fixture
def lock() -> dict:
    with open(_LOCK_PATH, "r", encoding="utf-8") as f:
        return json.load(f)


@pytest.fixture(autouse=True)
def _installed_tau2_commit(monkeypatch):
    """Tests stay offline: fake the installed-tau2 commit read (what
    _installed_tau2_commit() would return from the venv's direct_url.json).

    The default is the benchmark-lock commit (the installed dependency at
    the pin); individual tests override it to simulate a wrong/missing
    install.
    """
    monkeypatch.setattr(summarize, "_installed_tau2_commit", lambda: COMMIT)


# ---------------------------------------------------------------------------
# In-memory fakes (no tau2 results files, no LLM)
# ---------------------------------------------------------------------------


class _FakeSim:
    """Stand-in for SimulationRun: only get_messages() is used."""

    def __init__(self, tool_calls: int):
        self._tool_calls = tool_calls

    def get_messages(self):
        class _Msg:
            def __init__(self, is_call: bool):
                self._is_call = is_call

            def is_tool_call(self) -> bool:
                return self._is_call

        return [_Msg(True) for _ in range(self._tool_calls)]


def _fake_info(
    agent_implementation: str = "llm_agent", **overrides
) -> types.SimpleNamespace:
    """A minimal Info-shaped object with official defaults for the pinned run."""
    info = types.SimpleNamespace(
        git_commit=RUNNER_COMMIT,
        seed=300,
        num_trials=1,
        max_steps=200,
        agent_info=types.SimpleNamespace(
            implementation=agent_implementation,
            llm=AGENT_LLM,
            llm_args={"temperature": 0.0},
        ),
        user_info=types.SimpleNamespace(
            implementation="user_simulator",
            llm=AGENT_LLM,
            llm_args={"temperature": 0.0},
        ),
        environment_info=types.SimpleNamespace(domain_name="retail"),
    )
    for key, value in overrides.items():
        setattr(info, key, value)
    return info


def _rows() -> list[dict]:
    return [
        {
            "task_id": "0",
            "trial": 1,
            "reward": 1.0,
            "termination_reason": "user_stop",
            "num_messages": 4,
        },
        {
            "task_id": "10",
            "trial": 1,
            "reward": 0.0,
            "termination_reason": "max_steps",
            "num_messages": 8,
        },
    ]


def _patch_results(monkeypatch, infos, rows, tool_calls=()):
    """Replace tau2's Results with an in-memory fake returning fixed data.

    infos: one Info-shaped object for every run, or a dict keyed by run
    subdirectory name so main()-level tests can give each run its own info.
    """
    if not isinstance(infos, dict):
        infos = {"*": infos}

    class _FakeResults:
        def __init__(self, info):
            self._info = info

        @classmethod
        def load(cls, path):
            info = infos.get(Path(path).parent.name, infos.get("*"))
            assert Path(path).name == "results.json"
            return cls(info)

        def to_df(self):
            return pd.DataFrame(rows)

        @property
        def simulations(self):
            return [_FakeSim(n) for n in tool_calls]

        @property
        def info(self):
            return self._info

    monkeypatch.setattr("tau2.data_model.simulation.Results", _FakeResults)


def _entry(**overrides) -> dict:
    """A run entry in the shape _load_results produces (dict helper)."""
    entry = {
        "implementation": "llm_agent",
        "agent_llm": AGENT_LLM,
        "agent_llm_args": {"temperature": 0.0},
        "user_implementation": "user_simulator",
        "user_llm": AGENT_LLM,
        "user_llm_args": {"temperature": 0.0},
        "domain": "retail",
        "seed": 300,
        "num_trials": 1,
        "max_steps": 200,
        "runner_git_commit": RUNNER_COMMIT,
        "tasks": 2,
        "simulations": 2,
        "per_task": [
            {"task_id": "0", "trial": 1, "reward": 1.0, "termination_reason": "user_stop"},
            {"task_id": "10", "trial": 1, "reward": 0.0, "termination_reason": "max_steps"},
        ],
    }
    entry.update(overrides)
    return entry


def _guarded(**overrides) -> dict:
    return _entry(implementation="guarded_retail_agent", **overrides)


# ---------------------------------------------------------------------------
# _load_results: captures all reproducibility fields
# ---------------------------------------------------------------------------


def test_load_results_captures_reproducibility_fields(tmp_path, monkeypatch):
    _patch_results(monkeypatch, _fake_info(), _rows(), tool_calls=(1, 2))
    (tmp_path / "results.json").write_text("{}", encoding="utf-8")

    entry = summarize._load_results(tmp_path)

    assert entry["runner_git_commit"] == RUNNER_COMMIT
    assert entry["agent_llm"] == AGENT_LLM
    assert entry["agent_llm_args"] == {"temperature": 0.0}
    assert entry["user_implementation"] == "user_simulator"
    assert entry["user_llm"] == AGENT_LLM
    assert entry["user_llm_args"] == {"temperature": 0.0}
    assert entry["domain"] == "retail"
    assert entry["seed"] == 300
    assert entry["num_trials"] == 1
    assert entry["max_steps"] == 200
    assert entry["tasks"] == 2
    assert entry["simulations"] == 2
    assert entry["official_reward_mean"] == 0.5
    assert entry["success_rate"] == 0.5
    assert entry["avg_turns"] == 6.0
    assert entry["avg_tool_calls"] == 1.5
    assert entry["per_task"][0]["task_id"] == "0"


def test_load_results_missing_results_raises(tmp_path):
    with pytest.raises(FileNotFoundError, match="results.json"):
        summarize._load_results(tmp_path)


# ---------------------------------------------------------------------------
# _validate_fair_pair: invariants
# ---------------------------------------------------------------------------


def test_fair_pair_validation_passes(lock):
    parity = summarize._validate_fair_pair(_entry(), _guarded(), lock)
    assert parity["validated"] is True
    assert parity["fields"]["tau2Commit"] == COMMIT
    assert parity["fields"]["runnerGitCommit"] == RUNNER_COMMIT
    assert parity["fields"]["domain"] == "retail"
    assert parity["fields"]["taskTrialSet"] == [["0", 1], ["10", 1]]


@pytest.mark.parametrize(
    ("field", "bad_value"),
    [
        ("agent_llm", "gpt-4o"),
        ("agent_llm_args", {"temperature": 0.5}),
        ("user_implementation", "dummy_user"),
        ("user_llm", "gpt-4o"),
        ("user_llm_args", {"temperature": 0.5}),
        ("seed", 999),
        ("num_trials", 3),
        ("max_steps", 50),
    ],
)
def test_fair_pair_field_mismatch_raises(lock, field, bad_value):
    with pytest.raises(ValueError, match="not a fair pair"):
        summarize._validate_fair_pair(_entry(), _guarded(**{field: bad_value}), lock)


def test_runner_commit_mismatch_between_runs_raises(lock):
    # The official Results.info.git_commit is the runner's working-tree
    # commit at run time; a fair pair must come from ONE working tree, so
    # the two runs must record the same commit — not a benchmark-lock value.
    with pytest.raises(ValueError, match="runner git commit"):
        summarize._validate_fair_pair(
            _entry(), _guarded(runner_git_commit="deadbeef" * 5), lock
        )


def test_installed_tau2_commit_must_equal_lock_commit(monkeypatch, lock):
    monkeypatch.setattr(
        summarize, "_installed_tau2_commit", lambda: "deadbeef" * 5
    )
    with pytest.raises(ValueError, match="installed tau2 commit"):
        summarize._validate_fair_pair(_entry(), _guarded(), lock)


def test_unknown_installed_tau2_commit_fails_closed(monkeypatch, lock):
    # Fail closed: if the installed tau2 commit cannot be read (no
    # distribution / direct_url.json / vcs_info), refuse to summarize.
    monkeypatch.setattr(summarize, "_installed_tau2_commit", lambda: None)
    with pytest.raises(ValueError, match="installed tau2 commit is unknown"):
        summarize._validate_fair_pair(_entry(), _guarded(), lock)


def test_domain_must_equal_lock_domain(lock):
    with pytest.raises(ValueError, match="domain"):
        summarize._validate_fair_pair(_entry(domain="airline"), _guarded(), lock)


def test_same_agent_implementation_is_rejected(lock):
    with pytest.raises(ValueError, match="same agent implementation"):
        summarize._validate_fair_pair(_entry(), _entry(), lock)


def test_task_trial_set_mismatch_raises(lock):
    extra = {"task_id": "113", "trial": 1, "reward": 0.0, "termination_reason": "timeout"}
    guarded = _guarded(per_task=[*_entry()["per_task"], extra])
    with pytest.raises(ValueError, match=r"\(task_id, trial\) set"):
        summarize._validate_fair_pair(_entry(), guarded, lock)


# ---------------------------------------------------------------------------
# metadata.json
# ---------------------------------------------------------------------------


def _parity(**overrides) -> dict:
    """A parity record shaped like _validate_fair_pair's, with the commit
    fields _build_metadata reads."""
    fields = {
        "tau2Commit": COMMIT,  # installed tau2 commit, verified == lock
        "runnerGitCommit": RUNNER_COMMIT,
        "domain": "retail",
        "agentLlm": AGENT_LLM,
        "agentLlmArgs": {"temperature": 0.0},
        "userImplementation": "user_simulator",
        "userLlm": AGENT_LLM,
        "userLlmArgs": {"temperature": 0.0},
        "seed": 300,
        "numTrials": 1,
        "maxSteps": 200,
        "taskTrialSet": [["0", 1], ["10", 1]],
    }
    fields.update(overrides)
    return {"validated": True, "fields": fields}


def test_metadata_contains_lock_and_run_facts(lock):
    meta = summarize._build_metadata(
        _entry(), _guarded(), lock, _parity(),
        "official-baseline", "guarded-agent",
    )
    assert meta["repository"] == lock["repository"]
    assert meta["commit"] == COMMIT
    assert meta["domain"] == "retail"
    assert meta["taskSplit"] == "base"
    # actual UTC timestamp, parseable
    generated = datetime.fromisoformat(meta["generatedUtc"])
    assert generated.tzinfo is not None

    baseline = meta["runs"]["official-baseline"]
    assert baseline["implementation"] == "llm_agent"
    assert baseline["agentModel"] == AGENT_LLM
    assert baseline["agentLlmArgs"] == {"temperature": 0.0}
    assert baseline["userModel"] == AGENT_LLM
    assert baseline["userLlmArgs"] == {"temperature": 0.0}
    assert baseline["seed"] == 300
    assert baseline["numTrials"] == 1
    assert baseline["maxSteps"] == 200
    assert baseline["taskCount"] == 2
    assert baseline["simulationCount"] == 2
    assert baseline["trialCount"] == 1

    guarded = meta["runs"]["guarded-agent"]
    assert guarded["implementation"] == "guarded_retail_agent"
    assert guarded["agentModel"] == AGENT_LLM  # same settings, fair pair


def test_metadata_trial_count_from_actual_trials(lock):
    per_task = [
        {"task_id": "0", "trial": 1, "reward": 1.0, "termination_reason": "user_stop"},
        {"task_id": "0", "trial": 2, "reward": 1.0, "termination_reason": "user_stop"},
    ]
    meta = summarize._build_metadata(
        _entry(per_task=per_task), _guarded(), lock, _parity(),
        "official-baseline", "guarded-agent",
    )
    assert meta["runs"]["official-baseline"]["trialCount"] == 2


def test_metadata_labels_both_commits_distinctly(lock):
    # metadata.json must record BOTH commits with distinct, unambiguously
    # labeled keys: the pinned/installed tau2 dependency commit and the
    # runner/project git commit from the official results (different values
    # on a real run from this repo).
    meta = summarize._build_metadata(
        _entry(), _guarded(), lock, _parity(),
        "official-baseline", "guarded-agent",
    )
    assert meta["commit"] == COMMIT  # pinned tau2 commit (benchmark-lock.json)
    assert meta["installedTau2Commit"] == COMMIT  # verified == pinned
    assert meta["officialResultsRunnerGitCommit"] == RUNNER_COMMIT
    assert meta["officialResultsRunnerGitCommit"] != meta["installedTau2Commit"]


def test_metadata_never_contains_scores(lock):
    meta = summarize._build_metadata(
        _entry(), _guarded(), lock, _parity(),
        "official-baseline", "guarded-agent",
    )
    text = json.dumps(meta)
    assert "reward" not in text
    assert "success_rate" not in text


# ---------------------------------------------------------------------------
# main(): end-to-end with fakes, no files written on mismatch
# ---------------------------------------------------------------------------


def _write_fake_run(run_dir: Path) -> None:
    run_dir.mkdir(parents=True)
    (run_dir / "results.json").write_text("{}", encoding="utf-8")


def test_main_writes_metadata_and_comparison(tmp_path, monkeypatch):
    infos = {
        "official-baseline": _fake_info(agent_implementation="llm_agent"),
        "guarded-agent": _fake_info(agent_implementation="guarded_retail_agent"),
    }
    _patch_results(monkeypatch, infos, _rows(), tool_calls=(1, 2))
    _write_fake_run(tmp_path / "official-baseline")
    _write_fake_run(tmp_path / "guarded-agent")

    rc = summarize.main([str(tmp_path), "--lock", str(_LOCK_PATH)])
    assert rc == 0

    meta = json.loads((tmp_path / "metadata.json").read_text(encoding="utf-8"))
    assert meta["parity"]["validated"] is True
    assert meta["commit"] == COMMIT  # pinned tau2 commit
    assert meta["installedTau2Commit"] == COMMIT  # installed, verified == lock
    assert meta["officialResultsRunnerGitCommit"] == RUNNER_COMMIT
    assert meta["domain"] == "retail"
    assert meta["runs"]["official-baseline"]["simulationCount"] == 2
    assert meta["runs"]["guarded-agent"]["implementation"] == "guarded_retail_agent"

    comparison = json.loads((tmp_path / "comparison.json").read_text(encoding="utf-8"))
    assert comparison["tau3"]["commit"] == COMMIT
    assert comparison["tau3"]["domain"] == "retail"
    assert comparison["tau3"]["taskSplit"] == "base"
    assert comparison["baseline"]["official_reward_mean"] == 0.5
    assert comparison["guarded"]["implementation"] == "guarded_retail_agent"

    md = (tmp_path / "comparison.md").read_text(encoding="utf-8")
    assert "Official Reward (mean)" in md
    assert "fair-pair parity: validated" in md


def test_main_aborts_and_writes_nothing_on_mismatch(tmp_path, monkeypatch, capsys):
    guarded_info = _fake_info(agent_implementation="guarded_retail_agent")
    guarded_info.agent_info.llm = "gpt-4o"  # different agent model -> unfair pair
    infos = {
        "official-baseline": _fake_info(),
        "guarded-agent": guarded_info,
    }
    _patch_results(monkeypatch, infos, _rows())
    _write_fake_run(tmp_path / "official-baseline")
    _write_fake_run(tmp_path / "guarded-agent")

    rc = summarize.main([str(tmp_path), "--lock", str(_LOCK_PATH)])
    assert rc == 1
    assert "not a fair pair" in capsys.readouterr().err
    assert not (tmp_path / "metadata.json").exists()
    assert not (tmp_path / "comparison.json").exists()
    assert not (tmp_path / "comparison.md").exists()


# ---------------------------------------------------------------------------
# ASCII-safe argparse help (Windows consoles)
# ---------------------------------------------------------------------------


def test_help_is_ascii_safe():
    help_text = summarize.build_parser().format_help()
    assert help_text.isascii()
    help_text.encode("gbk")  # must not raise UnicodeEncodeError
    for flag in ("--baseline", "--guarded", "--lock", "run_dir"):
        assert flag in help_text
