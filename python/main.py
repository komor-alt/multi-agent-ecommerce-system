"""
Multi-Agent E-Commerce Recommendation System — FastAPI Entry Point

Endpoints:
  POST /api/v1/recommend          - 获取个性化推荐
  POST /api/v1/recommend/graph    - 通过LangGraph pipeline推荐
  GET  /api/v1/experiments        - 查看A/B实验状态
  GET  /api/v1/metrics            - 查看系统监控指标
  GET  /health                    - 健康检查
"""

from __future__ import annotations

import sys
import os
import asyncio
import json
import time
import uuid
from pathlib import Path

sys.path.insert(0, os.path.dirname(__file__))

from contextlib import asynccontextmanager
from typing import Any

import structlog
import uvicorn
from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from fastapi.encoders import jsonable_encoder
from fastapi.middleware.cors import CORSMiddleware

from config import get_settings
from models.schemas import BehaviorEventRequest, Product, RecommendationRequest, RecommendationResponse, UserProfile
from schemas.internal_agent import InternalAgentRunRequest
from orchestrator.supervisor import SupervisorOrchestrator
from orchestrator.graph import build_recommendation_graph
from services.ab_test import ABTestEngine
from services.evaluator import RecommendationEvaluator
from services.feature_store import FeatureStore
from services.metrics import MetricsCollector

logger = structlog.get_logger()
settings = get_settings()
FRONTEND_DIR = Path(__file__).parent / 'frontend'


ab_engine = ABTestEngine()
metrics_collector = MetricsCollector()
evaluator = RecommendationEvaluator()
feature_store = FeatureStore(ttl=settings.feature_ttl_seconds)
supervisor = SupervisorOrchestrator(ab_engine=ab_engine)
rec_graph = None


async def _build_feature_store() -> FeatureStore:
    try:
        import redis.asyncio as redis

        client = redis.from_url(settings.redis_url, decode_responses=True)
        await client.ping()
        logger.info("feature_store.redis.connected", redis_url=settings.redis_url)
        return FeatureStore(redis_client=client, ttl=settings.feature_ttl_seconds)
    except Exception as exc:
        logger.warning("feature_store.redis.unavailable", error=str(exc))
        return FeatureStore(redis_client=None, ttl=settings.feature_ttl_seconds)

@asynccontextmanager
async def lifespan(app: FastAPI):
    global rec_graph, feature_store
    rec_graph = build_recommendation_graph()
    feature_store = await _build_feature_store()
    supervisor.user_profile_agent.feature_store = feature_store
    logger.info("app.startup", model=settings.llm_model, feature_store_enabled=feature_store.redis is not None)
    yield
    if feature_store.redis:
        await feature_store.redis.aclose()
    logger.info("app.shutdown")


app = FastAPI(
    title="Multi-Agent E-Commerce Recommendation System",
    description="用户画像Agent + 商品推荐Agent + 营销文案Agent + 库存决策Agent，并行+聚合模式",
    version="1.0.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

app.mount("/ui-static", StaticFiles(directory=FRONTEND_DIR), name="ui-static")


@app.get("/", include_in_schema=False)
async def frontend_index():
    return FileResponse(FRONTEND_DIR / "index.html")


@app.get("/health")
async def health():
    return {"status": "healthy", "model": settings.llm_model}


@app.post("/api/v1/users/{user_id}/behaviors")
async def record_user_behavior(user_id: str, event: BehaviorEventRequest):
    """Record a user behavior event into Redis FeatureStore when Redis is available."""
    if not feature_store.redis:
        return {
            "status": "skipped",
            "reason": "redis_unavailable",
            "user_id": user_id,
            "behavior_type": event.behavior_type,
            "item_id": event.item_id,
        }
    await feature_store.record_behavior(
        user_id=user_id,
        behavior_type=event.behavior_type,
        item_id=event.item_id,
        metadata=event.metadata,
    )
    metrics_collector.record_business_event(
        "behavior",
        user_id=user_id,
        behavior_type=event.behavior_type,
        item_id=event.item_id,
    )
    return {
        "status": "recorded",
        "user_id": user_id,
        "behavior_type": event.behavior_type,
        "item_id": event.item_id,
    }


@app.get("/api/v1/users/{user_id}/features")
async def get_user_features(user_id: str):
    """Inspect the real-time feature vector used by UserProfileAgent."""
    features = await feature_store.get_user_features(user_id)
    return {
        "status": "redis" if feature_store.redis else "fallback",
        "user_id": user_id,
        "features": features,
    }

@app.post("/api/v1/recommend/stream", include_in_schema=False)
async def recommend_stream(request: RecommendationRequest):
    """SSE stream for the demo console: emits agent progress as the workflow runs."""
    headers = {
        "Cache-Control": "no-cache",
        "Connection": "keep-alive",
        "X-Accel-Buffering": "no",
    }
    return StreamingResponse(
        _recommend_event_stream(request),
        media_type="text/event-stream",
        headers=headers,
    )


async def _recommend_event_stream(request: RecommendationRequest):
    request_id = str(uuid.uuid4())
    start = time.perf_counter()
    agent_results: dict[str, Any] = {}

    def elapsed_ms() -> float:
        return round((time.perf_counter() - start) * 1000, 1)

    yield _sse("run.started", {
        "request_id": request_id,
        "user_id": request.user_id,
        "scene": request.scene,
        "num_items": request.num_items,
        "elapsed_ms": elapsed_ms(),
    })

    experiment = ab_engine.assign(request.user_id)
    yield _sse("experiment.assigned", {
        "request_id": request_id,
        "group": experiment.get("group", "control"),
        "strategy": experiment.get("strategy", "default"),
        "elapsed_ms": elapsed_ms(),
    })

    yield _sse("phase.started", {
        "phase": "Phase 1",
        "summary": "并行执行用户画像分析和商品候选召回。",
        "elapsed_ms": elapsed_ms(),
    })
    yield _sse("agent.started", _agent_started_payload(
        "user_profile",
        "用户画像 Agent",
        "读取用户行为上下文，生成结构化用户画像。",
        elapsed_ms(),
    ))
    yield _sse("agent.started", _agent_started_payload(
        "product_recall",
        "商品召回 Agent",
        "从候选商品池召回初始商品列表。",
        elapsed_ms(),
    ))

    phase1_tasks = {
        asyncio.create_task(supervisor.user_profile_agent.run(
            user_id=request.user_id,
            context=request.context,
        )): "user_profile",
        asyncio.create_task(supervisor.product_rec_agent.run(
            user_profile=None,
            num_items=request.num_items * 2,
        )): "product_recall",
    }

    while phase1_tasks:
        done, _ = await asyncio.wait(phase1_tasks.keys(), return_when=asyncio.FIRST_COMPLETED)
        for task in done:
            key = phase1_tasks.pop(task)
            result = task.result()
            agent_results[key] = result
            yield _sse("agent.completed", _agent_completed_payload(key, result, elapsed_ms()))

    user_profile: UserProfile | None = getattr(agent_results["user_profile"], "profile", None)
    raw_products: list[Product] = getattr(agent_results["product_recall"], "products", [])

    yield _sse("phase.completed", {
        "phase": "Phase 1",
        "summary": f"画像完成，召回 {len(raw_products)} 个候选商品。",
        "elapsed_ms": elapsed_ms(),
    })

    yield _sse("phase.started", {
        "phase": "Phase 2",
        "summary": "并行执行画像重排和库存校验。",
        "elapsed_ms": elapsed_ms(),
    })
    yield _sse("agent.started", _agent_started_payload(
        "product_rec",
        "商品重排 Agent",
        "结合用户画像、商品类目、价格和标签进行个性化排序。",
        elapsed_ms(),
    ))
    yield _sse("agent.started", _agent_started_payload(
        "inventory",
        "库存决策 Agent",
        "检查库存、低库存预警和限购策略。",
        elapsed_ms(),
    ))

    phase2_tasks = {
        asyncio.create_task(supervisor.product_rec_agent.run(
            user_profile=user_profile,
            num_items=request.num_items,
        )): "product_rec",
        asyncio.create_task(supervisor.inventory_agent.run(products=raw_products)): "inventory",
    }

    while phase2_tasks:
        done, _ = await asyncio.wait(phase2_tasks.keys(), return_when=asyncio.FIRST_COMPLETED)
        for task in done:
            key = phase2_tasks.pop(task)
            result = task.result()
            agent_results[key] = result
            yield _sse("agent.completed", _agent_completed_payload(key, result, elapsed_ms()))

    ranked_products: list[Product] = getattr(agent_results["product_rec"], "products", raw_products)
    available_ids = set(getattr(agent_results["inventory"], "available_products", []))
    final_products = [p for p in ranked_products if p.product_id in available_ids]
    if not final_products:
        final_products = ranked_products[:request.num_items]
    final_products = final_products[:request.num_items]

    yield _sse("phase.completed", {
        "phase": "Phase 2",
        "summary": f"库存过滤后保留 {len(final_products)} 个商品。",
        "products": jsonable_encoder(final_products),
        "elapsed_ms": elapsed_ms(),
    })

    yield _sse("phase.started", {
        "phase": "Phase 3",
        "summary": "根据用户分群生成营销文案。",
        "elapsed_ms": elapsed_ms(),
    })
    yield _sse("agent.started", _agent_started_payload(
        "marketing_copy",
        "营销文案 Agent",
        "选择分群 Prompt 模板，生成个性化营销文案并做规则合规过滤。",
        elapsed_ms(),
    ))

    copy_result = await supervisor.marketing_copy_agent.run(
        user_profile=user_profile,
        products=final_products,
    )
    agent_results["marketing_copy"] = copy_result
    yield _sse("agent.completed", _agent_completed_payload("marketing_copy", copy_result, elapsed_ms()))

    copies = getattr(copy_result, "copies", [])
    total_latency = (time.perf_counter() - start) * 1000
    response = RecommendationResponse(
        request_id=request_id,
        user_id=request.user_id,
        products=final_products,
        marketing_copies=copies,
        experiment_group=experiment.get("group", "control"),
        agent_results={
            "user_profile": agent_results["user_profile"],
            "product_rec": agent_results["product_rec"],
            "marketing_copy": agent_results["marketing_copy"],
            "inventory": agent_results["inventory"],
        },
        total_latency_ms=total_latency,
    )
    _collect_metrics(response)

    yield _sse("run.completed", {
        "request_id": request_id,
        "elapsed_ms": round(total_latency, 1),
        "response": jsonable_encoder(response),
    })


def _sse(event: str, payload: dict[str, Any]) -> str:
    return f"event: {event}\ndata: {json.dumps(payload, ensure_ascii=False)}\n\n"


def _agent_started_payload(key: str, title: str, summary: str, elapsed_ms: float) -> dict[str, Any]:
    return {
        "key": key,
        "title": title,
        "summary": summary,
        "elapsed_ms": elapsed_ms,
    }


def _agent_completed_payload(key: str, result: Any, elapsed_ms: float) -> dict[str, Any]:
    return {
        "key": key,
        "success": getattr(result, "success", False),
        "latency_ms": round(getattr(result, "latency_ms", 0.0), 1),
        "elapsed_ms": elapsed_ms,
        "result": jsonable_encoder(result),
        "summary": _summarize_agent_result(key, result),
    }


def _summarize_agent_result(key: str, result: Any) -> str:
    if not getattr(result, "success", False):
        return f"调用失败，进入降级：{getattr(result, 'error', '未知错误')}"
    if key == "user_profile":
        profile = getattr(result, "profile", None)
        segments = "/".join([s.value if hasattr(s, "value") else str(s) for s in getattr(profile, "segments", [])]) or "active"
        categories = "/".join(getattr(profile, "preferred_categories", []) or []) or "未识别"
        return f"识别用户分群 {segments}，偏好类目 {categories}。"
    if key in {"product_recall", "product_rec"}:
        products = getattr(result, "products", [])
        ids = "/".join([p.product_id for p in products]) or "无"
        return f"输出 {len(products)} 个商品：{ids}。"
    if key == "inventory":
        available = getattr(result, "available_products", [])
        alerts = getattr(result, "low_stock_alerts", [])
        return f"可售商品 {len(available)} 个，低库存预警 {len(alerts)} 条。"
    if key == "marketing_copy":
        copies = getattr(result, "copies", [])
        template = getattr(result, "prompt_template_used", "active")
        return f"使用 {template} 模板生成 {len(copies)} 条文案。"
    return "Agent 已完成。"


@app.post("/internal/agent-runs", include_in_schema=False)
async def internal_agent_run(request: InternalAgentRunRequest):
    """Internal contract used by the NestJS gateway during the migration."""
    agent_input = request.input or {}
    recommendation_request = RecommendationRequest(
        user_id=request.user_id,
        scene=str(agent_input.get("scene", "homepage")),
        num_items=int(agent_input.get("num_items", agent_input.get("numItems", 5))),
        context=dict(agent_input.get("context", {})),
    )

    events: list[dict[str, Any]] = []
    final_answer: dict[str, Any] | None = None
    total_latency_ms = 0

    sequence = 0
    async for frame in _recommend_event_stream(recommendation_request):
        parsed = _parse_sse_frame(frame)
        if not parsed:
            continue
        event_name, payload = parsed
        sequence += 1
        if event_name == "run.completed":
            final_answer = payload.get("response")
            total_latency_ms = int(payload.get("elapsed_ms") or 0)
        events.append(_to_internal_agent_event(request.run_id, sequence, event_name, payload))

    return {
        "run_id": request.run_id,
        "status": "completed",
        "final_answer": final_answer,
        "events": events,
        "metrics": {
            "latency_ms": total_latency_ms,
            "input_tokens": 0,
            "output_tokens": 0,
            "total_tokens": 0,
            "tool_call_count": len([e for e in events if e["type"] == "tool_completed"]),
        },
        "error": None,
    }


def _parse_sse_frame(frame: str) -> tuple[str, dict[str, Any]] | None:
    event_name = "message"
    data_lines: list[str] = []
    for line in frame.splitlines():
        if line.startswith("event:"):
            event_name = line.split(":", 1)[1].strip()
        if line.startswith("data:"):
            data_lines.append(line.split(":", 1)[1].strip())
    if not data_lines:
        return None
    return event_name, json.loads("\n".join(data_lines))


def _to_internal_agent_event(run_id: str, sequence: int, event_name: str, payload: dict[str, Any]) -> dict[str, Any]:
    event_type_map = {
        "run.started": "run_started",
        "experiment.assigned": "model_completed",
        "phase.started": "model_started",
        "agent.started": "model_started",
        "agent.completed": "model_completed",
        "phase.completed": "model_completed",
        "run.completed": "run_completed",
    }
    status = "success"
    if event_name.endswith("started") or event_name in {"phase.started", "agent.started"}:
        status = "running"
    if payload.get("success") is False:
        status = "failed"
    return {
        "event_id": str(uuid.uuid4()),
        "run_id": run_id,
        "sequence": sequence,
        "type": event_type_map.get(event_name, "warning"),
        "name": event_name,
        "status": status,
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "data": payload,
        "metrics": {
            "latency_ms": int(payload.get("latency_ms") or payload.get("elapsed_ms") or 0),
            "input_tokens": 0,
            "output_tokens": 0,
            "total_tokens": 0,
        },
    }
@app.post("/api/v1/evaluations/smoke")
async def smoke_evaluation(request: RecommendationRequest):
    """Run one recommendation and evaluate it with deterministic smoke checks."""
    response = await supervisor.recommend(request)
    _collect_metrics(response)
    report = evaluator.evaluate(request, response)
    metrics_collector.record_business_event(
        "evaluation",
        user_id=request.user_id,
        scene=request.scene,
        score=report.score,
        passed=report.passed,
    )
    return {
        "evaluation": report.to_dict(),
        "response": jsonable_encoder(response),
    }

@app.post("/api/v1/recommend", response_model=RecommendationResponse)
async def recommend(request: RecommendationRequest):
    """使用Supervisor编排器进行推荐 (生产推荐用法)"""
    response = await supervisor.recommend(request)
    _collect_metrics(response)
    return response


@app.post("/api/v1/recommend/graph")
async def recommend_via_graph(request: RecommendationRequest):
    """使用LangGraph状态图进行推荐 (展示LangGraph能力)"""
    if not rec_graph:
        return {"error": "Graph not initialized"}
    state = {
        "user_id": request.user_id,
        "scene": request.scene,
        "num_items": request.num_items,
        "context": request.context,
    }
    result = await rec_graph.ainvoke(state)
    return {
        "request_id": result.get("request_id"),
        "user_id": result.get("user_id"),
        "products": [p.model_dump() for p in result.get("final_products", [])],
        "marketing_copies": result.get("marketing_copies", []),
        "experiment_group": result.get("experiment_group", "control"),
        "total_latency_ms": round(result.get("total_latency_ms", 0), 1),
    }


@app.get("/api/v1/experiments")
async def get_experiments():
    """查看所有A/B实验状态"""
    experiments = {}
    for exp_id, exp in ab_engine.experiments.items():
        experiments[exp_id] = {
            "name": exp.name,
            "enabled": exp.enabled,
            "groups": [
                {
                    "name": g.name,
                    "weight": g.weight,
                    "config": g.config,
                    "successes": g.successes,
                    "failures": g.failures,
                }
                for g in exp.groups
            ],
            "stats": ab_engine.get_stats(exp_id),
        }
    return experiments


@app.get("/api/v1/metrics")
async def get_metrics():
    """查看系统监控指标"""
    return {
        "agents": metrics_collector.get_agent_stats(),
        "business": metrics_collector.get_business_stats(),
    }


@app.post("/api/v1/experiments/{experiment_id}/outcome")
async def record_outcome(experiment_id: str, group: str, success: bool):
    """记录A/B测试结果,更新Thompson Sampling"""
    ab_engine.record_outcome(experiment_id, group, success)
    return {"status": "recorded"}


def _collect_metrics(response: RecommendationResponse):
    for name, result in response.agent_results.items():
        metrics_collector.record_agent_call(
            agent_name=name,
            success=result.success,
            latency_ms=result.latency_ms,
        )


@app.get("/{full_path:path}", include_in_schema=False)
async def frontend_spa_fallback(full_path: str):
    """Serve the React SPA for client-side routes during the migration period."""
    if full_path.startswith("api/") or full_path in {"health", "docs", "openapi.json"}:
        raise HTTPException(status_code=404, detail="Not Found")
    return FileResponse(FRONTEND_DIR / "index.html")

if __name__ == "__main__":
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)




