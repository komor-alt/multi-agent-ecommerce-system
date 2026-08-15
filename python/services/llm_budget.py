from __future__ import annotations

from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass, field
import hashlib
from typing import Any, Iterator


@dataclass
class LlmCallBudget:
    """Per-request guard; stores only opaque fingerprints, never prompts or keys."""

    max_calls: int
    llm_call_count: int = 0
    budget_blocked_calls: int = 0
    duplicate_calls_blocked: int = 0
    _fingerprints: set[str] = field(default_factory=set)
    _by_phase: dict[str, int] = field(default_factory=dict)

    def try_acquire(self, phase: str, fingerprint: str) -> bool:
        safe_phase = phase or "unknown"
        opaque = hashlib.sha256(
            f"{safe_phase}:{fingerprint}".encode("utf-8")
        ).hexdigest()
        if opaque in self._fingerprints:
            self.duplicate_calls_blocked += 1
            return False
        if self.llm_call_count >= max(0, self.max_calls):
            self.budget_blocked_calls += 1
            return False
        self._fingerprints.add(opaque)
        self.llm_call_count += 1
        self._by_phase[safe_phase] = self._by_phase.get(safe_phase, 0) + 1
        return True

    def snapshot(self) -> dict[str, Any]:
        return {
            "llmCallCount": self.llm_call_count,
            "maxLlmCalls": max(0, self.max_calls),
            "budgetBlockedCalls": self.budget_blocked_calls,
            "duplicateCallsBlocked": self.duplicate_calls_blocked,
            "promptTokens": None,
            "completionTokens": None,
            "estimatedCost": None,
            "usageStatus": "unavailable",
            "usageUnavailableReason": "provider_usage_not_returned",
            "byPhase": dict(sorted(self._by_phase.items())),
        }


_CURRENT_BUDGET: ContextVar[LlmCallBudget | None] = ContextVar(
    "ecom_llm_call_budget", default=None
)


@contextmanager
def bind_budget(max_calls: int) -> Iterator[LlmCallBudget]:
    budget = LlmCallBudget(max_calls=max_calls)
    token = _CURRENT_BUDGET.set(budget)
    try:
        yield budget
    finally:
        _CURRENT_BUDGET.reset(token)


def current_budget() -> LlmCallBudget | None:
    return _CURRENT_BUDGET.get()


def allow_llm_call(phase: str, fingerprint: str) -> bool:
    budget = current_budget()
    return budget is not None and budget.try_acquire(phase, fingerprint)