import { apiPost } from "./client";

export type CreateRecommendationTaskRequest = {
  userId: string;
  scene: string;
  numItems: number;
  context: Record<string, unknown>;
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
  finalAnswer?: Record<string, unknown>;
  metrics?: Record<string, unknown>;
};

export function createRecommendationTask(input: CreateRecommendationTaskRequest) {
  return apiPost<CreateRecommendationTaskResponse>("/recommendation-tasks", input);
}
