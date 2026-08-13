const API_BASE_URL = (() => {
  const configured = import.meta.env.VITE_API_BASE_URL as string | undefined;
  if (configured) return configured;
  if (typeof window !== "undefined" && window.location.port === "8000") return "http://localhost:3000/api/v1";
  return "/api/v1";
})();

import type { AgentRunSseEvent } from "../types/contracts";

export function createAgentRunEventSource(runId: string, lastEventId?: string) {
  const params = lastEventId ? `?lastEventId=${encodeURIComponent(lastEventId)}` : "";
  return new EventSource(`${API_BASE_URL}/agent-runs/${encodeURIComponent(runId)}/stream${params}`);
}

export function parseAgentRunSseMessage(event: MessageEvent<string>): AgentRunSseEvent | null {
  const parsed = JSON.parse(event.data) as AgentRunSseEvent | { type?: string };
  if (parsed.type === "heartbeat") return null;
  return parsed as AgentRunSseEvent;
}
