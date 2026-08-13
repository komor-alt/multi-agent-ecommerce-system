import { AgentRunStatus, AgentTaskType, ReviewStatus } from "../../../common/enums/agent.enums";

export type AgentRunSummaryDto = {
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

export type AgentRunDetailDto = AgentRunSummaryDto & {
  originalRequest: Record<string, unknown>;
  finalAnswer?: Record<string, unknown>;
  promptSummary?: Record<string, unknown>;
  costSummary?: Record<string, unknown>;
  error?: { code?: string; message?: string };
  review: {
    status: ReviewStatus;
    reviewerId?: string;
    comment?: string;
    reviewedAt?: string;
  };
};
