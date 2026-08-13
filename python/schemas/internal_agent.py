from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Any

from pydantic import BaseModel, Field


class AgentTaskType(str, Enum):
    PRODUCT_RECOMMENDATION = "product_recommendation"
    AFTER_SALES_ASSISTANT = "after_sales_assistant"
    EVALUATION = "evaluation"


class AgentRunStatus(str, Enum):
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"
    TIMEOUT = "timeout"


class AgentEventType(str, Enum):
    RUN_STARTED = "run_started"
    MODEL_STARTED = "model_started"
    MODEL_COMPLETED = "model_completed"
    TOOL_STARTED = "tool_started"
    TOOL_COMPLETED = "tool_completed"
    RETRIEVAL_COMPLETED = "retrieval_completed"
    WARNING = "warning"
    ERROR = "error"
    RUN_COMPLETED = "run_completed"
    HEARTBEAT = "heartbeat"


class AgentEventStatus(str, Enum):
    PENDING = "pending"
    RUNNING = "running"
    SUCCESS = "success"
    FAILED = "failed"
    SKIPPED = "skipped"


class AgentConfig(BaseModel):
    model: str
    max_steps: int = Field(default=8, ge=1, le=32)
    temperature: float = Field(default=0.3, ge=0, le=2)
    max_tokens: int = Field(default=1024, ge=1, le=8192)
    rag_top_k: int = Field(default=5, ge=1, le=20)
    tool_whitelist: list[str] = Field(default_factory=list)


class InternalAgentRunRequest(BaseModel):
    run_id: str
    task_type: AgentTaskType
    user_id: str
    input: dict[str, Any] = Field(default_factory=dict)
    agent_config: AgentConfig


class AgentEventMetrics(BaseModel):
    latency_ms: int | None = None
    input_tokens: int = 0
    output_tokens: int = 0
    total_tokens: int = 0


class InternalAgentRunEvent(BaseModel):
    event_id: str
    run_id: str
    sequence: int
    type: AgentEventType
    name: str
    status: AgentEventStatus
    timestamp: datetime
    data: dict[str, Any] = Field(default_factory=dict)
    metrics: AgentEventMetrics = Field(default_factory=AgentEventMetrics)


class InternalAgentRunResponse(BaseModel):
    run_id: str
    status: AgentRunStatus
    final_answer: dict[str, Any] | None = None
    events: list[InternalAgentRunEvent] = Field(default_factory=list)
    metrics: dict[str, Any] = Field(default_factory=dict)
    error: dict[str, str] | None = None
