import { Injectable } from "@nestjs/common";
import { ConfigService } from "@nestjs/config";
import type { AgentServiceRawEvent, AgentServiceRunRequest, AgentServiceRunResponse } from "./agent-service.contract";
import { internalServiceHeaders } from "./internal-service-auth";

type SseFrame = {
  event: string;
  data: Record<string, unknown>;
};

@Injectable()
export class AgentClientService {
  constructor(private readonly config: ConfigService) {}

  async createLegacyPythonAgentRun(request: AgentServiceRunRequest): Promise<AgentServiceRunResponse> {
    const response = await fetch(`${this.legacyPythonBaseUrl()}/internal/agent-runs`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify(request),
    });
    const payload = await response.json().catch(() => null);
    if (!response.ok) {
      throw new Error(payload?.detail || `Legacy Python agent request failed: HTTP ${response.status}`);
    }
    return payload as AgentServiceRunResponse;
  }

  async *streamRecommendationRun(request: AgentServiceRunRequest): AsyncGenerator<AgentServiceRawEvent> {
    const context = objectValue(request.input.context);
    const response = await fetch(`${this.javaBaseUrl()}/api/v1/recommend/agent-loop/stream`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "text/event-stream", ...internalServiceHeaders(this.config) },
      body: JSON.stringify({
        runId: request.run_id,
        request: {
          userId: request.user_id,
          scene: stringValue(request.input.scene) || "homepage",
          numItems: numberValue(request.input.num_items) ?? numberValue(request.input.numItems) ?? 5,
          platform: stringValue(request.input.platform) || stringValue(context.platform) || "shopify",
          region: stringValue(request.input.region) || stringValue(context.region) || "SEA",
          country: stringValue(request.input.country) || stringValue(context.country) || "SG",
          locale: stringValue(request.input.locale) || stringValue(context.locale) || "en-SG",
          currency: stringValue(request.input.currency) || stringValue(context.currency) || "SGD",
          context,
        },
        config: {
          maxSteps: request.agent_config.max_steps,
          toolWhitelist: request.agent_config.tool_whitelist,
        },
      }),
    });

    if (!response.ok || !response.body) {
      const detail = await response.text().catch(() => "");
      throw new Error(`Java recommendation agent stream failed: HTTP ${response.status}${detail ? ` - ${detail}` : ""}`);
    }

    let fallbackSequence = 0;
    for await (const frame of this.readSseFrames(response.body)) {
      fallbackSequence += 1;
      yield this.toRawEvent(request.run_id, fallbackSequence, frame.event, frame.data);
    }
  }

  private javaBaseUrl() {
    return this.config.get<string>("JAVA_AGENT_SERVICE_BASE_URL") || "http://localhost:8080";
  }

  private legacyPythonBaseUrl() {
    return this.config.get<string>("LEGACY_PYTHON_AGENT_SERVICE_BASE_URL")
      || this.config.get<string>("AGENT_SERVICE_BASE_URL")
      || "http://localhost:8000";
  }

  private async *readSseFrames(body: ReadableStream<Uint8Array>): AsyncGenerator<SseFrame> {
    const reader = body.getReader();
    const decoder = new TextDecoder("utf-8");
    let buffer = "";
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const chunks = buffer.split(/\r?\n\r?\n/);
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

  private toRawEvent(runId: string, fallbackSequence: number, eventName: string, payload: Record<string, unknown>): AgentServiceRawEvent {
    const eventData = objectValue(payload.data);
    const summary = stringValue(payload.summary);
    const response = objectValue(payload.response);
    const llmMetrics = objectValue(eventData.llmMetrics || response.llmMetrics);
    return {
      event_id: stringValue(payload.eventId) || crypto.randomUUID(),
      run_id: stringValue(payload.requestId) || runId,
      sequence: numberValue(payload.sequence) ?? fallbackSequence,
      type: stringValue(payload.type) || this.mapEventType(eventName),
      name: stringValue(payload.name) || eventName,
      status: stringValue(payload.status) || this.mapStatus(eventName),
      timestamp: stringValue(payload.timestamp) || new Date().toISOString(),
      data: { ...eventData, ...(summary ? { summary } : {}) },
      metrics: {
        latency_ms: numberValue(payload.elapsedMs) ?? numberValue(eventData.latencyMs) ?? 0,
        input_tokens: nullableNumber(llmMetrics.promptTokens),
        output_tokens: nullableNumber(llmMetrics.completionTokens),
        total_tokens: nullableNumber(llmMetrics.totalTokens),
      },
    };
  }

  private mapEventType(eventName: string) {
    const map: Record<string, string> = {
      "run.started": "run_started",
      "planner.decision": "model_completed",
      "tool.started": "tool_started",
      "tool.completed": "tool_completed",
      "tool.failed": "error",
      observation: "retrieval_completed",
      "run.completed": "run_completed",
    };
    return map[eventName] || "warning";
  }

  private mapStatus(eventName: string) {
    if (eventName === "run.started" || eventName === "tool.started") return "running";
    if (eventName === "tool.failed") return "failed";
    return "success";
  }
}

function nullableNumber(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? Math.round(value) : null;
}

function numberValue(value: unknown) {
  return typeof value === "number" && Number.isFinite(value) ? Math.round(value) : undefined;
}

function stringValue(value: unknown) {
  return typeof value === "string" && value.trim() ? value : undefined;
}

function objectValue(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}
