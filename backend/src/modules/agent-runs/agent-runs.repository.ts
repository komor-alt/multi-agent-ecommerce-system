import { Injectable } from "@nestjs/common";
import { AgentEventStatus, AgentEventType, AgentRunStatus, Prisma } from "@prisma/client";
import { PrismaService } from "../../infrastructure/database/prisma.service";
import type { AgentServiceRunResponse, AgentServiceRawEvent } from "../../infrastructure/agent-client/agent-service.contract";

const eventTypeMap: Record<string, AgentEventType> = {
  run_started: AgentEventType.RUN_STARTED,
  model_started: AgentEventType.MODEL_STARTED,
  model_completed: AgentEventType.MODEL_COMPLETED,
  tool_started: AgentEventType.TOOL_STARTED,
  tool_completed: AgentEventType.TOOL_COMPLETED,
  retrieval_completed: AgentEventType.RETRIEVAL_COMPLETED,
  warning: AgentEventType.WARNING,
  error: AgentEventType.ERROR,
  run_completed: AgentEventType.RUN_COMPLETED,
  heartbeat: AgentEventType.HEARTBEAT,
};

const eventStatusMap: Record<string, AgentEventStatus> = {
  pending: AgentEventStatus.PENDING,
  running: AgentEventStatus.RUNNING,
  success: AgentEventStatus.SUCCESS,
  failed: AgentEventStatus.FAILED,
  skipped: AgentEventStatus.SKIPPED,
};

@Injectable()
export class AgentRunsRepository {
  constructor(private readonly prisma: PrismaService) {}

  list() {
    return this.prisma.agentRun.findMany({
      orderBy: { createdAt: "desc" },
      take: 20,
      include: {
        events: { select: { id: true } },
        recommendationTask: { select: { id: true, scene: true } },
      },
    });
  }

  findDetail(runId: string) {
    return this.prisma.agentRun.findUnique({
      where: { id: runId },
      include: {
        events: { orderBy: { sequence: "asc" } },
        recommendationTask: true,
      },
    });
  }

  findEvents(runId: string) {
    return this.prisma.agentEvent.findMany({
      where: { runId },
      orderBy: { sequence: "asc" },
    });
  }

  async persistEvent(runId: string, event: AgentServiceRawEvent) {
    await this.prisma.agentEvent.upsert({
      where: { runId_sequence: { runId, sequence: event.sequence } },
      update: this.toAgentEventUpdateInput(event),
      create: this.toAgentEventCreateInput(runId, event),
    });
  }

  async completeRun(runId: string, finalAnswer: Prisma.InputJsonValue, metrics: { latency_ms?: number; input_tokens?: number | null; output_tokens?: number | null; total_tokens?: number | null; tool_call_count?: number }) {
    return this.prisma.agentRun.update({
      where: { id: runId },
      data: {
        status: AgentRunStatus.COMPLETED,
        completedAt: new Date(),
        latencyMs: metrics.latency_ms || 0,
        inputTokens: metrics.input_tokens ?? null,
        outputTokens: metrics.output_tokens ?? null,
        totalTokens: metrics.total_tokens ?? null,
        toolCallCount: metrics.tool_call_count || 0,
        responsePayload: finalAnswer,
      },
    });
  }

  async persistAgentServiceResult(runId: string, response: AgentServiceRunResponse) {
    await this.prisma.$transaction(async (tx) => {
      await tx.agentEvent.createMany({
        data: response.events.map((event) => this.toAgentEventCreateManyInput(runId, event)),
        skipDuplicates: true,
      });
      await tx.agentRun.update({
        where: { id: runId },
        data: {
          status: AgentRunStatus.COMPLETED,
          completedAt: new Date(),
          latencyMs: response.metrics.latency_ms || 0,
          inputTokens: response.metrics.input_tokens ?? null,
          outputTokens: response.metrics.output_tokens ?? null,
          totalTokens: response.metrics.total_tokens ?? null,
          toolCallCount: response.metrics.tool_call_count || 0,
          responsePayload: (response.final_answer || {}) as Prisma.InputJsonValue,
        },
      });
    });
  }

  markFailed(runId: string, message: string) {
    return this.prisma.agentRun.update({
      where: { id: runId },
      data: {
        status: AgentRunStatus.FAILED,
        completedAt: new Date(),
        errorCode: "AGENT_SERVICE_FAILED",
        errorMessage: message,
      },
    });
  }

  private toAgentEventCreateInput(runId: string, event: AgentServiceRawEvent): Prisma.AgentEventCreateInput {
    return {
      id: event.event_id,
      run: { connect: { id: runId } },
      sequence: event.sequence,
      eventType: eventTypeMap[event.type] || AgentEventType.WARNING,
      eventName: event.name,
      outputSummary: this.outputSummary(event),
      latencyMs: event.metrics?.latency_ms ?? undefined,
      status: eventStatusMap[event.status] || AgentEventStatus.SUCCESS,
      rawPayload: event as unknown as Prisma.InputJsonValue,
      createdAt: new Date(event.timestamp),
    };
  }

  private toAgentEventUpdateInput(event: AgentServiceRawEvent): Prisma.AgentEventUpdateInput {
    return {
      eventType: eventTypeMap[event.type] || AgentEventType.WARNING,
      eventName: event.name,
      outputSummary: this.outputSummary(event),
      latencyMs: event.metrics?.latency_ms ?? undefined,
      status: eventStatusMap[event.status] || AgentEventStatus.SUCCESS,
      rawPayload: event as unknown as Prisma.InputJsonValue,
      createdAt: new Date(event.timestamp),
    };
  }

  private toAgentEventCreateManyInput(runId: string, event: AgentServiceRawEvent): Prisma.AgentEventCreateManyInput {
    return {
      id: event.event_id,
      runId,
      sequence: event.sequence,
      eventType: eventTypeMap[event.type] || AgentEventType.WARNING,
      eventName: event.name,
      inputSummary: undefined,
      outputSummary: this.outputSummary(event),
      latencyMs: event.metrics?.latency_ms ?? undefined,
      status: eventStatusMap[event.status] || AgentEventStatus.SUCCESS,
      rawPayload: event as unknown as Prisma.InputJsonValue,
      createdAt: new Date(event.timestamp),
    };
  }

  private outputSummary(event: AgentServiceRawEvent) {
    const summary = event.data?.summary;
    if (typeof summary === "string") return summary;
    if (event.name === "run.completed") return "Agent run completed.";
    return undefined;
  }
}
