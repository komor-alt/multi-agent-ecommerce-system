import { AgentEventStatus, AgentEventType } from "../enums/agent.enums";

export type AgentRunSseMetrics = {
  latencyMs?: number;
  inputTokens?: number;
  outputTokens?: number;
  totalTokens?: number;
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
  metrics?: AgentRunSseMetrics;
};
