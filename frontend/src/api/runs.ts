import type { AgentRunDetail, AgentRunSseEvent, AgentRunSummary, PageResult } from "../types/contracts";
import { apiGet } from "./client";

export function listAgentRuns() {
  return apiGet<PageResult<AgentRunSummary>>("/agent-runs");
}

export function getAgentRun(runId: string) {
  return apiGet<AgentRunDetail>(`/agent-runs/${encodeURIComponent(runId)}`);
}

export function getAgentRunEvents(runId: string) {
  return apiGet<PageResult<AgentRunSseEvent>>(`/agent-runs/${encodeURIComponent(runId)}/events`);
}
