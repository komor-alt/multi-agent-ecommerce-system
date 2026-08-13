import { Injectable, MessageEvent } from "@nestjs/common";
import { Prisma } from "@prisma/client";
import { merge, Observable } from "rxjs";
import type { AgentServiceRawEvent, AgentServiceRunResponse } from "../../infrastructure/agent-client/agent-service.contract";
import { AgentRunEventBus } from "./agent-run-event-bus";
import { AgentRunsRepository } from "./agent-runs.repository";

@Injectable()
export class AgentRunsService {
  constructor(
    private readonly repository: AgentRunsRepository,
    private readonly eventBus: AgentRunEventBus,
  ) {}

  async list() {
    const runs = await this.repository.list();
    return {
      items: runs.map((run) => ({
        id: run.id,
        taskType: run.taskType.toLowerCase(),
        userId: run.userId,
        status: run.status.toLowerCase(),
        modelName: run.modelName,
        promptVersion: run.promptVersion,
        stepCount: run.events.length,
        toolCallCount: run.toolCallCount,
        latencyMs: run.latencyMs,
        inputTokens: run.inputTokens,
        outputTokens: run.outputTokens,
        totalTokens: run.totalTokens,
        createdAt: run.createdAt.toISOString(),
      })),
      page: 1,
      pageSize: 20,
      total: runs.length,
    };
  }

  async detail(runId: string) {
    const run = await this.repository.findDetail(runId);
    if (!run) return null;
    return {
      id: run.id,
      taskType: run.taskType.toLowerCase(),
      userId: run.userId,
      status: run.status.toLowerCase(),
      modelName: run.modelName,
      promptVersion: run.promptVersion,
      stepCount: run.events.length,
      toolCallCount: run.toolCallCount,
      latencyMs: run.latencyMs,
      inputTokens: run.inputTokens,
      outputTokens: run.outputTokens,
      totalTokens: run.totalTokens,
      createdAt: run.createdAt.toISOString(),
      originalRequest: run.requestPayload,
      finalAnswer: run.responsePayload,
      error: run.errorMessage ? { code: run.errorCode, message: run.errorMessage } : undefined,
      review: { status: "not_required" },
    };
  }

  async events(runId: string) {
    const events = await this.repository.findEvents(runId);
    return {
      items: events.map((event) => ({
        eventId: event.id,
        runId: event.runId,
        sequence: event.sequence,
        type: event.eventType.toLowerCase(),
        name: event.eventName,
        status: event.status.toLowerCase(),
        timestamp: event.createdAt.toISOString(),
        data: event.rawPayload,
        metrics: { latencyMs: event.latencyMs || 0 },
      })),
      page: 1,
      pageSize: events.length,
      total: events.length,
    };
  }

  stream(runId: string): Observable<MessageEvent> {
    const history$ = new Observable<MessageEvent>((subscriber) => {
      void this.events(runId)
        .then((result) => {
          for (const event of result.items) {
            subscriber.next({ id: event.eventId, type: event.type, data: event });
          }
          subscriber.complete();
        })
        .catch((error) => subscriber.error(error));
    });

    const heartbeat$ = new Observable<MessageEvent>((subscriber) => {
      const timer = setInterval(() => {
        subscriber.next({ data: { type: "heartbeat", runId, timestamp: new Date().toISOString() } });
      }, 15000);
      return () => clearInterval(timer);
    });

    return merge(history$, this.eventBus.stream(runId), heartbeat$);
  }

  async persistAndPublishEvent(runId: string, event: AgentServiceRawEvent) {
    await this.repository.persistEvent(runId, event);
    this.eventBus.publish(event);
  }

  completeRealtimeRun(runId: string, finalAnswer: Record<string, unknown>, metrics: { latency_ms?: number; input_tokens?: number; output_tokens?: number; total_tokens?: number; tool_call_count?: number }) {
    this.eventBus.complete(runId);
    return this.repository.completeRun(runId, finalAnswer as Prisma.InputJsonObject, metrics);
  }

  failRealtimeRun(runId: string, message: string) {
    this.eventBus.error(runId, new Error(message));
    return this.repository.markFailed(runId, message);
  }

  persistAgentServiceResult(runId: string, response: AgentServiceRunResponse) {
    return this.repository.persistAgentServiceResult(runId, response);
  }

  markFailed(runId: string, message: string) {
    return this.repository.markFailed(runId, message);
  }
}

