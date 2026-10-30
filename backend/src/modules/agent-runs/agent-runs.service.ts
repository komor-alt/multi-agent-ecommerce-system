import { BadRequestException, Injectable, MessageEvent, NotFoundException } from "@nestjs/common";
import { Prisma } from "@prisma/client";
import { Observable } from "rxjs";
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
        scene: run.recommendationTask?.scene || undefined,
        recommendationTaskId: run.recommendationTask?.id || undefined,
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
        completedAt: run.completedAt?.toISOString() || null,
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
      items: events.map((event) => {
        const raw = event.rawPayload && typeof event.rawPayload === "object" && !Array.isArray(event.rawPayload)
          ? event.rawPayload as Record<string, unknown>
          : {};
        const data = raw.data && typeof raw.data === "object" && !Array.isArray(raw.data)
          ? raw.data as Record<string, unknown>
          : raw;
        return {
          eventId: event.id,
          runId: event.runId,
          sequence: event.sequence,
          type: event.eventType.toLowerCase(),
          name: event.eventName,
          status: event.status.toLowerCase(),
          timestamp: event.createdAt.toISOString(),
          data,
          metrics: { latencyMs: event.latencyMs ?? null },
        };
      }),
      page: 1,
      pageSize: events.length,
      total: events.length,
    };
  }

  async stream(runId: string, lastEventId?: string): Promise<Observable<MessageEvent>> {
    const runStatus = await this.repository.findRunStatus(runId);
    if (!runStatus) throw new NotFoundException("Agent run not found");

    let afterSequence = 0;
    const requestedId = lastEventId?.trim();
    if (requestedId) {
      const sequence = await this.repository.findEventSequence(runId, requestedId);
      if (sequence == null) throw new BadRequestException("Last-Event-ID is not valid for this run");
      afterSequence = sequence;
    }

    return new Observable<MessageEvent>((subscriber) => {
      let replayReady = false;
      let liveCompleted = false;
      let lastSequence = afterSequence;
      const buffered: MessageEvent[] = [];
      const seenIds = new Set<string>();
      let heartbeat: NodeJS.Timeout | undefined;

      const deliver = (event: MessageEvent) => {
        const payload = event.data && typeof event.data === "object"
          ? event.data as Record<string, unknown>
          : {};
        const eventId = event.id || (typeof payload.eventId === "string" ? payload.eventId : undefined);
        const sequence = Number(payload.sequence);
        if (eventId && seenIds.has(eventId)) return;
        if (Number.isSafeInteger(sequence) && sequence > 0 && sequence <= lastSequence) return;
        if (Number.isSafeInteger(sequence) && sequence > 0) lastSequence = sequence;
        if (eventId) {
          seenIds.add(eventId);
          if (seenIds.size > 1024) {
            const oldest = seenIds.values().next().value;
            if (oldest) seenIds.delete(oldest);
          }
        }
        subscriber.next(event);
      };

      // Subscribe before reading history so commits racing the query are buffered and merged.
      const live = this.eventBus.stream(runId).subscribe({
        next: (event) => replayReady ? deliver(event) : buffered.push(event),
        error: (error: unknown) => subscriber.error(error),
        complete: () => {
          liveCompleted = true;
          if (replayReady) subscriber.complete();
        },
      });

      subscriber.add(() => {
        if (heartbeat) clearInterval(heartbeat);
        live.unsubscribe();
      });

      void (async () => {
        if (subscriber.closed) return;
        const history = await this.repository.findEventsAfterSequence(runId, lastSequence);
        if (subscriber.closed) return;
        for (const event of history) {
          const mapped = this.mapStoredEvent(event);
          deliver({ id: mapped.eventId, type: mapped.type, data: mapped });
        }

        replayReady = true;
        buffered.sort((left, right) => this.sequenceOf(left) - this.sequenceOf(right));
        for (const event of buffered) deliver(event);
        buffered.length = 0;

        const currentStatus = await this.repository.findRunStatus(runId);
        if (!currentStatus) throw new NotFoundException("Agent run not found");
        if (subscriber.closed) return;
        if (this.isTerminal(currentStatus) || liveCompleted) {
          if (this.isTerminal(currentStatus)) this.eventBus.complete(runId);
          subscriber.complete();
          return;
        }
        heartbeat = setInterval(() => subscriber.next({
          data: { type: "heartbeat", runId, timestamp: new Date().toISOString() },
        }), 15000);
      })().catch((error: unknown) => subscriber.error(error));
    });
  }

  private mapStoredEvent(event: { id: string; runId: string; sequence: number; eventType: string; eventName: string;
    status: string; createdAt: Date; rawPayload: Prisma.JsonValue; latencyMs: number | null }) {
    const raw = event.rawPayload && typeof event.rawPayload === "object" && !Array.isArray(event.rawPayload)
      ? event.rawPayload as Record<string, unknown>
      : {};
    const data = raw.data && typeof raw.data === "object" && !Array.isArray(raw.data)
      ? raw.data as Record<string, unknown>
      : raw;
    return {
      eventId: event.id,
      runId: event.runId,
      sequence: event.sequence,
      type: event.eventType.toLowerCase(),
      name: event.eventName,
      status: event.status.toLowerCase(),
      timestamp: event.createdAt.toISOString(),
      data,
      metrics: { latencyMs: event.latencyMs ?? null },
    };
  }

  private sequenceOf(event: MessageEvent) {
    const payload = event.data && typeof event.data === "object"
      ? event.data as Record<string, unknown>
      : {};
    const sequence = Number(payload.sequence);
    return Number.isSafeInteger(sequence) ? sequence : Number.MAX_SAFE_INTEGER;
  }

  private isTerminal(status: string) {
    return ["COMPLETED", "FAILED", "CANCELLED", "TIMEOUT"].includes(status.toUpperCase());
  }

  async persistAndPublishEvent(runId: string, event: AgentServiceRawEvent) {
    await this.repository.persistEvent(runId, event);
    this.eventBus.publish(event);
  }

  completeRealtimeRun(runId: string, finalAnswer: Record<string, unknown>, metrics: { latency_ms?: number; input_tokens?: number | null; output_tokens?: number | null; total_tokens?: number | null; tool_call_count?: number }) {
    return this.repository.completeRun(runId, finalAnswer as Prisma.InputJsonObject, metrics)
      .then((run) => {
        this.eventBus.complete(runId);
        return run;
      });
  }

  failRealtimeRun(runId: string, message: string) {
    return this.repository.markFailed(runId, message)
      .then((run) => {
        this.eventBus.error(runId, new Error(message));
        return run;
      });
  }

  persistAgentServiceResult(runId: string, response: AgentServiceRunResponse) {
    return this.repository.persistAgentServiceResult(runId, response);
  }

  markFailed(runId: string, message: string) {
    return this.repository.markFailed(runId, message);
  }
}

