export type CreateEvaluationRunDto = {
  datasetId: string;
  agentVersion: string;
  modelName: string;
  config: Record<string, unknown>;
};

export type EvaluationRunDto = {
  id: string;
  datasetId: string;
  agentVersion: string;
  status: "pending" | "running" | "completed" | "failed";
  successRate?: number;
  accuracy?: number;
  avgLatencyMs?: number;
  avgToolCalls?: number;
  totalTokens: number;
  createdAt: string;
  completedAt?: string;
};
