from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from models.schemas import (  # noqa: E402
    AgentResult,
    Product,
    RecommendationRequest,
    RecommendationResponse,
)
from services.evaluator import RecommendationEvaluator  # noqa: E402


def test_recommendation_evaluator_passes_valid_response():
    request = RecommendationRequest(user_id="user_001", num_items=2)
    response = RecommendationResponse(
        request_id="req_001",
        user_id="user_001",
        products=[
            Product(product_id="P001", name="Phone", category="手机", price=1000, stock=10),
            Product(product_id="P002", name="Pods", category="耳机", price=500, stock=20),
        ],
        marketing_copies=[
            {"product_id": "P001", "copy": "适合日常使用的手机推荐。"},
            {"product_id": "P002", "copy": "通勤和运动都方便的耳机。"},
        ],
        agent_results={
            "user_profile": AgentResult(agent_name="user_profile", success=True),
            "product_rec": AgentResult(agent_name="product_rec", success=True),
            "inventory": AgentResult(agent_name="inventory", success=True),
            "marketing_copy": AgentResult(agent_name="marketing_copy", success=True),
        },
        total_latency_ms=1200,
    )

    report = RecommendationEvaluator().evaluate(request, response)

    assert report.passed is True
    assert report.score == 1.0


def test_recommendation_evaluator_flags_risky_response():
    request = RecommendationRequest(user_id="user_001", num_items=2)
    response = RecommendationResponse(
        request_id="req_001",
        user_id="user_001",
        products=[
            Product(product_id="P001", name="Phone", category="手机", price=1000, stock=0),
        ],
        marketing_copies=[
            {"product_id": "P999", "copy": "这是最好用的产品，100%满意。"},
        ],
        agent_results={
            "product_rec": AgentResult(agent_name="product_rec", success=False, error="llm failed"),
        },
        total_latency_ms=5000,
    )

    report = RecommendationEvaluator(latency_threshold_ms=3000).evaluate(request, response)
    failed_names = {check.name for check in report.checks if not check.passed}

    assert report.passed is False
    assert "inventory_filter" in failed_names
    assert "copy_coverage" in failed_names
    assert "copy_compliance" in failed_names
    assert "agent_success" in failed_names
    assert "latency_budget" in failed_names
