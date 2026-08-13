import { Injectable } from "@nestjs/common";
import { ConfigService } from "@nestjs/config";
import type { AgentServiceRawEvent, AgentServiceRunRequest, AgentServiceRunResponse } from "./agent-service.contract";

type SseFrame = {
  event: string;
  data: Record<string, unknown>;
};

@Injectable()
export class AgentClientService {
  constructor(private readonly config: ConfigService) {}

  async createAgentRun(request: AgentServiceRunRequest): Promise<AgentServiceRunResponse> {
    const baseUrl = this.baseUrl();
    const response = await fetch(`${baseUrl}/internal/agent-runs`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify(request),
    });
    const payload = await response.json().catch(() => null);
    if (!response.ok) {
      throw new Error(payload?.detail || `Agent service request failed: HTTP ${response.status}`);
    }
    return payload as AgentServiceRunResponse;
  }

  async *streamRecommendationRun(request: AgentServiceRunRequest): AsyncGenerator<AgentServiceRawEvent> {
    const response = await fetch(`${this.baseUrl()}/api/v1/recommend/stream`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
      body: JSON.stringify({
        user_id: request.user_id,
        scene: request.input.scene || "homepage",
        num_items: request.input.num_items || request.input.numItems || 5,
        context: request.input.context || {},
      }),
    });

    if (!response.ok || !response.body) {
      throw new Error(`Agent stream failed: HTTP ${response.status}`);
    }

    let sequence = 0;
    for await (const frame of this.readSseFrames(response.body)) {
      sequence += 1;
      yield this.toRawEvent(request.run_id, sequence, frame.event, frame.data);
    }
  }

  private baseUrl() {
    return this.config.get<string>("AGENT_SERVICE_BASE_URL") || "http://localhost:8000";
  }

  private async *readSseFrames(body: ReadableStream<Uint8Array>): AsyncGenerator<SseFrame> {
    const reader = body.getReader();
    const decoder = new TextDecoder("utf-8");
    let buffer = "";

    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const chunks = buffer.split("\n\n");
      buffer = chunks.pop() || "";
      for (const chunk of chunks) {
        const frame = this.parseSseFrame(chunk);
        if (frame) yield frame;
      }
    }

    if (buffer.trim()) {
      const frame = this.parseSseFrame(buffer);
      if (frame) yield frame;
    }
  }

  private parseSseFrame(chunk: string): SseFrame | null {
    let event = "message";
    const dataLines: string[] = [];
    for (const line of chunk.split(/\r?\n/)) {
      if (line.startsWith("event:")) event = line.slice(6).trim();
      if (line.startsWith("data:")) dataLines.push(line.slice(5).trimStart());
    }
    if (!dataLines.length) return null;
    return { event, data: JSON.parse(dataLines.join("\n")) as Record<string, unknown> };
  }

  private toRawEvent(runId: string, sequence: number, eventName: string, payload: Record<string, unknown>): AgentServiceRawEvent {
    return {
      event_id: crypto.randomUUID(),
      run_id: runId,
      sequence,
      type: this.mapEventType(eventName),
      name: eventName,
      status: payload.success === false ? "failed" : this.mapStatus(eventName),
      timestamp: new Date().toISOString(),
      data: payload,
      metrics: {
        latency_ms: numberValue(payload.latency_ms) ?? numberValue(payload.elapsed_ms) ?? 0,
        input_tokens: 0,
        output_tokens: 0,
        total_tokens: 0,
      },
    };
  }

  private mapEventType(eventName: string) {
    const map: Record<string, string> = {
      "run.started": "run_started",
      "experiment.assigned": "model_completed",
      "phase.started": "model_started",
      "agent.started": "model_started",
      "agent.completed": "model_completed",
      "phase.completed": "model_completed",
      "run.completed": "run_completed",
    };
    return map[eventName] || "warning";
  }

  private mapStatus(eventName: string) {
    if (eventName === "run.started" || eventName === "phase.started" || eventName === "agent.started") return "running";
    return "success";
  }
}

function numberValue(value: unknown) {
  return typeof value === "number" && Number.isFinite(value) ? Math.round(value) : undefined;
}
