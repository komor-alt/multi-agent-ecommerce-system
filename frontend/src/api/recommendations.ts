import { apiGet, apiPost } from "./client";

export type CreateRecommendationTaskRequest = {
  userId: string;
  scene: string;
  numItems: number;
  context: Record<string, unknown>;
  platform?: string;
  region?: string;
  country?: string;
  locale?: string;
  currency?: string;
  agentConfig?: {
    model?: string;
    maxSteps?: number;
    temperature?: number;
    maxTokens?: number;
    ragTopK?: number;
    toolWhitelist?: string[];
  };
};

export type CreateRecommendationTaskResponse = {
  id: string;
  runId: string;
  status: "completed" | "running" | "failed" | string;
  streamUrl?: string;
  finalAnswer?: Record<string, unknown>;
  metrics?: Record<string, unknown>;
};

export type RecommendationTaskSummary = {
  id: string;
  taskType: string;
  userId: string;
  scene: string;
  market: {
    platform: string;
    region: string;
    country: string;
    locale: string;
    currency: string;
  };
  status: string;
  runId?: string | null;
  finalRecommendationPlan?: Record<string, unknown> | null;
  createdAt: string;
  completedAt?: string | null;
};

export type RecommendationTaskPage = {
  items: RecommendationTaskSummary[];
  page: number;
  pageSize: number;
  total: number;
};

export function listRecommendationTasks() {
  return apiGet<RecommendationTaskPage>("/recommendations");
}

export function getRecommendationTask(taskId: string) {
  return apiGet<RecommendationTaskSummary & { input?: Record<string, unknown>; run?: Record<string, unknown> | null }>(
    `/recommendations/${encodeURIComponent(taskId)}`,
  );
}

export function createRecommendationTask(input: CreateRecommendationTaskRequest) {
  return apiPost<CreateRecommendationTaskResponse>("/recommendations", input);
}