export type UserRole = "admin" | "operator" | "viewer";
export type AgentTaskType = "product_recommendation" | "after_sales_assistant" | "evaluation";
export type AgentRunStatus = "pending" | "running" | "completed" | "failed" | "cancelled" | "timeout";
export type AgentEventType = "run_started" | "model_started" | "model_completed" | "tool_started" | "tool_completed" | "retrieval_completed" | "warning" | "error" | "run_completed" | "heartbeat";
export type AgentEventStatus = "pending" | "running" | "success" | "failed" | "skipped";
export type ReviewStatus = "not_required" | "pending" | "approved" | "rejected";

export type ApiResponse<T> =
  | { success: true; data: T; requestId: string }
  | { success: false; error: { code: string; message: string; details?: Record<string, unknown> }; requestId: string };

export type PageResult<T> = {
  items: T[];
  page: number;
  pageSize: number;
  total: number;
};

export type AgentRunSseEvent = {
  eventId: string;
  runId: string;
  sequence: number;
  type: AgentEventType;
  name: string;
  status: AgentEventStatus;
  timestamp: string;
  data: Record<string, unknown>;
  metrics?: {
    latencyMs?: number;
    inputTokens?: number;
    outputTokens?: number;
    totalTokens?: number;
  };
};

export type AgentRunSummary = {
  id: string;
  taskType: AgentTaskType;
  userId?: string;
  status: AgentRunStatus;
  modelName: string;
  promptVersion: string;
  stepCount: number;
  toolCallCount: number;
  latencyMs?: number;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  createdAt: string;
};

export type AgentRunDetail = AgentRunSummary & {
  originalRequest: Record<string, unknown>;
  finalAnswer?: Record<string, unknown>;
  review: {
    status: ReviewStatus;
    reviewerId?: string;
    comment?: string;
    reviewedAt?: string;
  };
  events: AgentRunSseEvent[];
};

export type ProductSummary = {
  productId: string;
  sku?: string;
  name: string;
  category: string;
  brand?: string;
  sellerId?: string;
  price: number;
  currency: string;
  stock: number;
  tags: string[];
  status: "active" | "inactive" | "archived";
  knowledgeStatus: "pending" | "ready" | "failed";
  embeddingStatus: "pending" | "ready" | "failed";
  updatedAt: string;
};

export type ProductDetail = ProductSummary & {
  description: string;
  metadata: Record<string, unknown>;
  createdAt: string;
};

export type ProductCategory = {
  category: string;
  count: number;
};

export type DashboardOverview = {
  metrics: {
    todayRuns: number;
    successRate: number;
    avgLatencyMs: number;
    totalTokens: number;
    runningRuns: number;
    productCount: number;
    readyProductCount: number;
  };
  recentRuns: AgentRunSummary[];
  recentEvents: AgentRunSseEvent[];
};
