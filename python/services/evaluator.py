from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from models.schemas import RecommendationRequest, RecommendationResponse


FORBIDDEN_COPY_WORDS = {
    "最好",
    "第一",
    "国家级",
    "全球首",
    "绝对",
    "100%",
    "永久",
    "万能",
    "祖传",
    "纯天然",
}


@dataclass
class EvaluationCheck:
    name: str
    passed: bool
    detail: str
    weight: float = 1.0


@dataclass
class EvaluationReport:
    passed: bool
    score: float
    checks: list[EvaluationCheck] = field(default_factory=list)
    summary: str = ""

    def to_dict(self) -> dict[str, Any]:
        return {
            "passed": self.passed,
            "score": round(self.score, 3),
            "summary": self.summary,
            "checks": [
                {
                    "name": check.name,
                    "passed": check.passed,
                    "detail": check.detail,
                    "weight": check.weight,
                }
                for check in self.checks
            ],
        }


class RecommendationEvaluator:
    """Rule-based smoke evaluator for the recommendation agent workflow."""

    def __init__(self, latency_threshold_ms: float = 3000.0):
        self.latency_threshold_ms = latency_threshold_ms

    def evaluate(
        self,
        request: RecommendationRequest,
        response: RecommendationResponse,
    ) -> EvaluationReport:
        checks = [
            self._check_product_count(request, response),
            self._check_inventory(response),
            self._check_copy_coverage(response),
            self._check_copy_compliance(response),
            self._check_agent_success(response),
            self._check_latency(response),
        ]
        total_weight = sum(check.weight for check in checks)
        passed_weight = sum(check.weight for check in checks if check.passed)
        score = passed_weight / total_weight if total_weight else 0.0
        passed = all(check.passed for check in checks)
        summary = f"{sum(1 for check in checks if check.passed)}/{len(checks)} checks passed"
        return EvaluationReport(passed=passed, score=score, checks=checks, summary=summary)

    def _check_product_count(
        self,
        request: RecommendationRequest,
        response: RecommendationResponse,
    ) -> EvaluationCheck:
        count = len(response.products)
        passed = 0 < count <= request.num_items
        return EvaluationCheck(
            name="product_count",
            passed=passed,
            detail=f"returned {count} products, requested at most {request.num_items}",
            weight=1.2,
        )

    def _check_inventory(self, response: RecommendationResponse) -> EvaluationCheck:
        out_of_stock = [product.product_id for product in response.products if product.stock <= 0]
        return EvaluationCheck(
            name="inventory_filter",
            passed=not out_of_stock,
            detail="all returned products have stock" if not out_of_stock else f"out of stock: {out_of_stock}",
            weight=1.2,
        )

    def _check_copy_coverage(self, response: RecommendationResponse) -> EvaluationCheck:
        product_ids = {product.product_id for product in response.products}
        copy_ids = {item.get("product_id", "") for item in response.marketing_copies}
        missing = sorted(product_ids - copy_ids)
        extra = sorted(copy_ids - product_ids)
        passed = not missing and not extra
        detail = "every returned product has one copy" if passed else f"missing={missing}, extra={extra}"
        return EvaluationCheck(name="copy_coverage", passed=passed, detail=detail)

    def _check_copy_compliance(self, response: RecommendationResponse) -> EvaluationCheck:
        violations: list[str] = []
        for item in response.marketing_copies:
            copy = item.get("copy", "")
            for word in FORBIDDEN_COPY_WORDS:
                if word in copy:
                    violations.append(f"{item.get('product_id', '-')}: {word}")
        return EvaluationCheck(
            name="copy_compliance",
            passed=not violations,
            detail="no forbidden advertising words" if not violations else "; ".join(violations),
            weight=1.2,
        )

    def _check_agent_success(self, response: RecommendationResponse) -> EvaluationCheck:
        failed = [
            name
            for name, result in response.agent_results.items()
            if not getattr(result, "success", False)
        ]
        return EvaluationCheck(
            name="agent_success",
            passed=not failed,
            detail="all agents succeeded" if not failed else f"failed agents: {failed}",
            weight=1.5,
        )

    def _check_latency(self, response: RecommendationResponse) -> EvaluationCheck:
        latency = response.total_latency_ms
        passed = latency <= self.latency_threshold_ms
        return EvaluationCheck(
            name="latency_budget",
            passed=passed,
            detail=f"{latency:.1f} ms <= {self.latency_threshold_ms:.1f} ms",
        )
